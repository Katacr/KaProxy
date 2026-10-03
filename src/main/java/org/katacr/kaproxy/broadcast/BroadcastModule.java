package org.katacr.kaproxy.broadcast;

import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.core.ProxyAdapter;
import org.katacr.kaproxy.core.ProxyPlayer;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 处理群组公告、真实进出服消息和带组件 JSON 的跨服死亡消息。 */
public final class BroadcastModule {
    private static final long EVENT_TTL_MILLIS = 60_000L;
    private static final long TOPOLOGY_TTL_MILLIS = 3_000L;
    private static final long QUIT_CONFIRM_TIMEOUT_MILLIS = 3_000L;
    private final ProxyAdapter adapter;
    private volatile KaProxyConfig config;
    private volatile KaProxyLanguage language;
    private final Map<UUID, String> playerServers = new HashMap<>();
    private final Map<UUID, Long> recentEvents = new HashMap<>();
    private final Map<UUID, String> pendingSwitches = new HashMap<>();
    private final Map<UUID, PendingQuit> pendingQuits = new HashMap<>();
    // 玩家仍在线时由后端上传的退出渲染快照，解决源服最后一人无消息载体的问题。
    private final Map<UUID, QuitSnapshot> quitSnapshots = new HashMap<>();
    // 当前会话已广播过退出，防止 DisconnectEvent 与迟到快照重复发送。
    private final Map<UUID, Long> forwardedQuitSessions = new HashMap<>();
    // 每次代理会话/子服连接递增，防止旧断开任务删除快速重连后的新拓扑。
    private final Map<UUID, Long> playerSessions = new HashMap<>();
    // 标记已收到代理级断开；Bungee 下次连接不能再使用残留拓扑推断为切服。
    private final Map<UUID, Long> disconnectedSessions = new HashMap<>();

    /** 创建群组公共消息模块。 */
    public BroadcastModule(ProxyAdapter adapter, KaProxyConfig config, KaProxyLanguage language) {
        this.adapter = adapter;
        this.config = config;
        this.language = language;
    }

    /** 热更新配置。 */
    public synchronized void reload(KaProxyConfig config, KaProxyLanguage language) {
        this.config = config;
        this.language = language;
        if (!config.broadcastEnabled()) recentEvents.clear();
    }

    /** 清理模块状态。 */
    public synchronized void disable() {
        playerServers.clear();
        recentEvents.clear();
        pendingSwitches.clear();
        pendingQuits.clear();
        quitSnapshots.clear();
        forwardedQuitSessions.clear();
        playerSessions.clear();
        disconnectedSessions.clear();
    }

    /** 用代理快照校准玩家拓扑。 */
    public synchronized void synchronizePlayers(Collection<? extends ProxyPlayer> players) {
        if (!config.broadcastEnabled()) return;
        Map<UUID, String> current = new HashMap<>();
        for (ProxyPlayer player : players) {
            if (!player.serverName().isBlank()) current.put(player.uniqueId(), player.serverName());
        }
        playerServers.clear();
        playerServers.putAll(current);
        playerSessions.keySet().removeIf(playerId -> !current.containsKey(playerId));
        disconnectedSessions.keySet().removeIf(playerId -> !current.containsKey(playerId));
        quitSnapshots.keySet().removeIf(playerId -> !current.containsKey(playerId));
        forwardedQuitSessions.keySet().removeIf(playerId -> !current.containsKey(playerId));
        for (UUID playerId : current.keySet()) {
            playerSessions.putIfAbsent(playerId, 0L);
        }
    }

    /**
     * 代理平台连接事件回调（Velocity ServerPostConnectEvent / Bungee ServerConnectedEvent）。
     *
     * 兼容缺少 previousServer 可靠性标记的旧调用；仅 Bungee 应走此分支并允许拓扑推断。
     */
    public synchronized void playerConnected(ProxyPlayer player, String previousServer) {
        playerConnected(player, previousServer, false);
    }

    /**
     * 代理平台连接事件回调（Velocity ServerPostConnectEvent / Bungee ServerConnectedEvent）。
     *
     * previousServerKnown 为 true 时，null 是 Velocity 明确给出的“新代理会话”事实，绝不能
     * 回退到旧拓扑；Bungee 不提供此前服务器，因此传 false 后才允许从拓扑推断切服来源。
     */
    public synchronized void playerConnected(ProxyPlayer player, String previousServer, boolean previousServerKnown) {
        if (!config.broadcastEnabled()) return;
        UUID playerId = player.uniqueId();
        String currentServer = player.serverName();
        playerSessions.merge(playerId, 1L, Long::sum);
        forwardedQuitSessions.remove(playerId);
        boolean reconnectedAfterDisconnect = disconnectedSessions.remove(playerId) != null;
        PendingQuit pendingQuit = pendingQuits.remove(playerId);
        String pendingSwitch = pendingSwitches.remove(playerId);
        if ((previousServerKnown && previousServer == null) || reconnectedAfterDisconnect) {
            // Velocity 明确无前服或已经观察到代理断开，均为新会话，禁止残留拓扑伪造切服。
            playerServers.remove(playerId);
            quitSnapshots.remove(playerId);
            if (!currentServer.isBlank()) playerServers.put(playerId, currentServer);
            debug(() -> "平台确认新会话: player=" + player.name() + ", to=" + currentServer);
            return;
        }
        String from = previousServerKnown ? previousServer : pendingSwitch;
        if (from == null && pendingQuit != null) from = pendingQuit.server();
        if (from == null) from = playerServers.get(playerId);
        if (from != null && !from.isBlank() && !from.equals(currentServer)) {
            // 切服（平台事件铁证）：等待目标服 join_prepare 后渲染切换消息。
            String finalFrom = from;
            pendingSwitches.put(playerId, finalFrom);
            quitSnapshots.remove(playerId);
            debug(() -> "平台确认切服: player=" + player.name() + ", from=" + finalFrom + ", to=" + currentServer);
        } else {
            // 首次进入或断线重连：清理残留，join_prepare 将转发 player_join。
            pendingSwitches.remove(playerId);
            playerServers.remove(playerId);
        }
        if (!currentServer.isBlank()) playerServers.put(playerId, currentServer);
    }

    /**
     * 玩家真正离开代理（DisconnectEvent）：这是唯一可确认真实退出的平台代理信号。
     * 拓扑延迟删除，给迟到的 quit_prepare（经同服其他玩家载体）留出发送窗口。
     */
    public synchronized void playerDisconnected(ProxyPlayer player) {
        UUID playerId = player.uniqueId();
        pendingSwitches.remove(playerId);
        long session = playerSessions.getOrDefault(playerId, 0L);
        disconnectedSessions.put(playerId, session);
        PendingQuit pending = pendingQuits.get(playerId);
        if (pending != null) {
            quitSnapshots.remove(playerId);
            confirmQuit(pending, true);
        } else {
            QuitSnapshot snapshot = quitSnapshots.remove(playerId);
            if (snapshot != null && snapshot.server().equals(playerServers.get(playerId))) {
                forwardQuitSnapshot(snapshot);
            } else {
                debug(() -> "DisconnectEvent 未收到实时 quit_prepare 且没有可用退出快照: player=" + player.name());
            }
        }
        adapter.schedule(() -> removeDisconnectedTopology(playerId, session), TOPOLOGY_TTL_MILLIS);
    }

    /** 仅删除未重连且仍离线的旧会话拓扑。 */
    private synchronized void removeDisconnectedTopology(UUID playerId, long session) {
        if (playerSessions.getOrDefault(playerId, -1L) != session || adapter.player(playerId).isPresent()) return;
        playerServers.remove(playerId);
        playerSessions.remove(playerId, session);
        disconnectedSessions.remove(playerId, session);
        quitSnapshots.remove(playerId);
        forwardedQuitSessions.remove(playerId, session);
    }

    /** 解码并转发后端公共消息。 */
    public synchronized void handle(ProxyPlayer carrier, String sourceServer, String action, DataInputStream input) {
        if (!config.broadcastEnabled()) return;
        debug(() -> "收到后端消息: action=" + action + ", server=" + sourceServer
                + (carrier == null ? "" : ", 载体=" + carrier.name()));
        try {
            switch (action) {
                case "publish" -> handleAnnouncement(sourceServer, input);
                case "publish_targeted" -> handleTargetedAnnouncement(sourceServer, input);
                case "death" -> handleDeath(sourceServer, input);
                case "join_prepare" -> handleJoinPrepare(sourceServer, input);
                case "quit_prepare" -> handleQuitPrepare(sourceServer, input);
                case "quit_snapshot" -> handleQuitSnapshot(sourceServer, input);
                case "switch_publish" -> handleSwitchPublish(sourceServer, input);
                default -> { if (config.logUnknownModules()) adapter.info(language.text("unknown-broadcast-action", Map.of("action", action))); }
            }
        } catch (IOException | RuntimeException error) {
            adapter.error(language.text("broadcast-packet-failed", Map.of("server", sourceServer)), error);
        }
    }

    private void handleAnnouncement(String sourceServer, DataInputStream input) throws IOException {
        if (!config.broadcastAnnouncementsEnabled()) return;
        UUID eventId = KaProxyProtocol.readUuid(input);
        String sender = input.readUTF();
        String message = input.readUTF();
        String delivery = "chat";
        String title = "";
        String subtitle = "";
        int titleFadeIn = 10;
        int titleStay = 60;
        int titleFadeOut = 20;
        String bossbarColor = "RED";
        String bossbarStyle = "SOLID";
        float bossbarProgress = 1.0f;
        int bossbarDuration = 100;
        try {
            delivery = input.readUTF();
            title = input.readUTF();
            subtitle = input.readUTF();
            titleFadeIn = input.readInt();
            titleStay = input.readInt();
            titleFadeOut = input.readInt();
            bossbarColor = input.readUTF();
            bossbarStyle = input.readUTF();
            bossbarProgress = input.readFloat();
            bossbarDuration = input.readInt();
        } catch (java.io.EOFException ignored) {
            // 兼容旧版只包含普通 chat 字段的公告包。
        }
        if (message.isBlank() || alreadySeen(eventId)) return;
        final String finalDelivery = delivery;
        final String finalTitle = title;
        final String finalSubtitle = subtitle;
        final int finalTitleFadeIn = titleFadeIn;
        final int finalTitleStay = titleStay;
        final int finalTitleFadeOut = titleFadeOut;
        final String finalBossbarColor = bossbarColor;
        final String finalBossbarStyle = bossbarStyle;
        final float finalBossbarProgress = bossbarProgress;
        final int finalBossbarDuration = bossbarDuration;
        forwardEvent("announcement", eventId, output -> {
            output.writeUTF(sender);
            output.writeUTF(sourceServer);
            output.writeUTF(message);
            output.writeUTF(finalDelivery);
            output.writeUTF(finalTitle);
            output.writeUTF(finalSubtitle);
            output.writeInt(finalTitleFadeIn);
            output.writeInt(finalTitleStay);
            output.writeInt(finalTitleFadeOut);
            output.writeUTF(finalBossbarColor);
            output.writeUTF(finalBossbarStyle);
            output.writeFloat(finalBossbarProgress);
            output.writeInt(finalBossbarDuration);
        });
    }

    /** 处理后端发来的定向公告：解析目标玩家后仅路由到其所在后端，由该后端投递给目标玩家。 */
    private void handleTargetedAnnouncement(String sourceServer, DataInputStream input) throws IOException {
        if (!config.broadcastAnnouncementsEnabled()) return;
        UUID eventId = KaProxyProtocol.readUuid(input);
        String targetName = input.readUTF();
        String sender = input.readUTF();
        String message = input.readUTF();
        String delivery = "chat";
        String title = "";
        String subtitle = "";
        int titleFadeIn = 10;
        int titleStay = 60;
        int titleFadeOut = 20;
        String bossbarColor = "RED";
        String bossbarStyle = "SOLID";
        float bossbarProgress = 1.0f;
        int bossbarDuration = 100;
        try {
            delivery = input.readUTF();
            title = input.readUTF();
            subtitle = input.readUTF();
            titleFadeIn = input.readInt();
            titleStay = input.readInt();
            titleFadeOut = input.readInt();
            bossbarColor = input.readUTF();
            bossbarStyle = input.readUTF();
            bossbarProgress = input.readFloat();
            bossbarDuration = input.readInt();
        } catch (java.io.EOFException ignored) {
            // 兼容旧版只包含普通 chat 字段的公告包。
        }
        if (message.isBlank() || alreadySeen(eventId)) return;
        ProxyPlayer target = null;
        for (ProxyPlayer candidate : adapter.players()) {
            if (candidate.name().equalsIgnoreCase(targetName)) {
                target = candidate;
                break;
            }
        }
        if (target == null || target.serverName().isBlank()) {
            debug(() -> "定向公告目标不在线，丢弃: " + targetName);
            return;
        }
        final ProxyPlayer finalTarget = target;
        final String finalDelivery = delivery;
        final String finalTitle = title;
        final String finalSubtitle = subtitle;
        final int finalTitleFadeIn = titleFadeIn;
        final int finalTitleStay = titleStay;
        final int finalTitleFadeOut = titleFadeOut;
        final String finalBossbarColor = bossbarColor;
        final String finalBossbarStyle = bossbarStyle;
        final float finalBossbarProgress = bossbarProgress;
        final int finalBossbarDuration = bossbarDuration;
        byte[] packet = KaProxyProtocol.encode("broadcast", "announcement_targeted", output -> {
            KaProxyProtocol.writeUuid(output, eventId);
            KaProxyProtocol.writeUuid(output, finalTarget.uniqueId());
            output.writeUTF(finalTarget.name());
            output.writeUTF(sender);
            output.writeUTF(sourceServer);
            output.writeUTF(message);
            output.writeUTF(finalDelivery);
            output.writeUTF(finalTitle);
            output.writeUTF(finalSubtitle);
            output.writeInt(finalTitleFadeIn);
            output.writeInt(finalTitleStay);
            output.writeInt(finalTitleFadeOut);
            output.writeUTF(finalBossbarColor);
            output.writeUTF(finalBossbarStyle);
            output.writeFloat(finalBossbarProgress);
            output.writeInt(finalBossbarDuration);
        });
        debug(() -> "定向公告路由到 " + finalTarget.serverName() + ": target=" + finalTarget.name());
        adapter.broadcastToServer(KaProxyProtocol.CHANNEL, packet, finalTarget.serverName());
    }

    private void handleDeath(String sourceServer, DataInputStream input) throws IOException {
        if (!config.broadcastDeathMessagesEnabled()) return;
        UUID eventId = KaProxyProtocol.readUuid(input);
        UUID playerId = KaProxyProtocol.readUuid(input);
        String playerName = input.readUTF();
        String deathMessage = input.readUTF();
        String deathJson;
        try {
            deathJson = input.readUTF();
        } catch (java.io.EOFException ignored) {
            deathJson = "{\"text\":\"\"}";
        }
        final String componentJson = deathJson;
        if (deathMessage.isBlank() || alreadySeen(eventId)) return;
        forwardEvent("player_death", eventId, output -> {
            KaProxyProtocol.writeUuid(output, playerId);
            output.writeUTF(playerName);
            output.writeUTF(sourceServer);
            output.writeUTF(deathMessage);
            output.writeUTF(componentJson);
        });
    }

    /** 处理后端发来的加入预渲染数据：首次进入 → 转发；切服 → 通知目标服渲染切换消息。 */
    private void handleJoinPrepare(String sourceServer, DataInputStream input) throws IOException {
        if (!config.broadcastConnectionMessagesEnabled()) return;
        UUID eventId = KaProxyProtocol.readUuid(input);
        UUID playerId = KaProxyProtocol.readUuid(input);
        String playerName = input.readUTF();
        input.readUTF();
        String renderedText = input.readUTF();
        String delivery = input.readUTF();
        int titleFadeIn = input.readInt();
        int titleStay = input.readInt();
        int titleFadeOut = input.readInt();
        String bossbarColor = input.readUTF();
        String bossbarStyle = input.readUTF();
        float bossbarProgress = input.readFloat();
        int bossbarDuration = input.readInt();
        if (renderedText.isBlank() || alreadySeen(eventId)) return;
        String actualServer = adapter.player(playerId).map(ProxyPlayer::serverName).orElse("");
        if (!actualServer.isBlank() && !actualServer.equals(sourceServer)) {
            debug(() -> "join_prepare 来源不是当前后端，丢弃迟到包: player=" + playerName
                    + ", source=" + sourceServer + ", current=" + actualServer);
            return;
        }
        // 切服判定优先级：平台事件标记 > 待定退出 > 拓扑旧记录，覆盖任意消息到达顺序。
        String platformFrom = pendingSwitches.remove(playerId);
        PendingQuit pending = pendingQuits.remove(playerId);
        String previous = playerServers.put(playerId, sourceServer);
        String fromServer = platformFrom != null ? platformFrom
                : previous != null ? previous
                : pending != null ? pending.server() : null;
        if (fromServer != null && !fromServer.equals(sourceServer)) {
            // 切服：向目标服回发切换上下文，由目标服匹配权限规则并渲染。
            debug(() -> "join_prepare 判定为切服，发送 switch_request"
                    + (platformFrom != null ? "（平台信号）" : "") + ": player=" + playerName
                    + ", from=" + fromServer + ", to=" + sourceServer);
            sendSwitchRequest(eventId, playerId, playerName, fromServer, sourceServer);
            return;
        }
        debug(() -> "join_prepare 判定为进入网络，转发 player_join: player=" + playerName + ", server=" + sourceServer);
        forwardConnection("player_join", eventId, playerId, playerName, sourceServer,
                renderedText, delivery, titleFadeIn, titleStay, titleFadeOut,
                bossbarColor, bossbarStyle, bossbarProgress, bossbarDuration);
    }

    /** 向切服的目标后端发送切换渲染请求。 */
    private void sendSwitchRequest(UUID eventId, UUID playerId, String playerName,
                                   String fromServer, String toServer) throws IOException {
        byte[] packet = KaProxyProtocol.encode("broadcast", "switch_request", output -> {
            KaProxyProtocol.writeUuid(output, eventId);
            KaProxyProtocol.writeUuid(output, playerId);
            output.writeUTF(playerName);
            output.writeUTF(fromServer);
            output.writeUTF(toServer);
        });
        adapter.broadcastToServer(KaProxyProtocol.CHANNEL, packet, toServer);
    }

    /** 处理目标服渲染后的切换通知：校验后按 connection 模式转发全群组。 */
    private void handleSwitchPublish(String sourceServer, DataInputStream input) throws IOException {
        if (!config.broadcastConnectionMessagesEnabled()) return;
        UUID eventId = KaProxyProtocol.readUuid(input);
        UUID playerId = KaProxyProtocol.readUuid(input);
        String playerName = input.readUTF();
        input.readUTF();  // fromServer（转发时以代理拓扑与目标服信息为准）
        String toServer = input.readUTF();
        String renderedText = input.readUTF();
        String delivery = input.readUTF();
        int titleFadeIn = input.readInt();
        int titleStay = input.readInt();
        int titleFadeOut = input.readInt();
        String bossbarColor = input.readUTF();
        String bossbarStyle = input.readUTF();
        float bossbarProgress = input.readFloat();
        int bossbarDuration = input.readInt();
        if (renderedText.isBlank() || alreadySeen(eventId)) return;
        debug(() -> "switch_publish 确认切换，转发 player_switch: player=" + playerName
                + ", from=" + sourceServer + ", to=" + toServer);
        forwardConnection("player_switch", eventId, playerId, playerName, toServer,
                renderedText, delivery, titleFadeIn, titleStay, titleFadeOut,
                bossbarColor, bossbarStyle, bossbarProgress, bossbarDuration);
    }

    /** 缓存玩家在线时上传的退出渲染结果，供最后一名玩家断开时兜底使用。 */
    private void handleQuitSnapshot(String sourceServer, DataInputStream input) throws IOException {
        if (!config.broadcastConnectionMessagesEnabled()) return;
        UUID playerId = KaProxyProtocol.readUuid(input);
        String playerName = input.readUTF();
        String reportedServer = input.readUTF();
        String renderedText = input.readUTF();
        String delivery = input.readUTF();
        int titleFadeIn = input.readInt();
        int titleStay = input.readInt();
        int titleFadeOut = input.readInt();
        String bossbarColor = input.readUTF();
        String bossbarStyle = input.readUTF();
        float bossbarProgress = input.readFloat();
        int bossbarDuration = input.readInt();
        if (renderedText.isBlank()) return;
        if (!sourceServer.equals(reportedServer)) {
            debug(() -> "quit_snapshot 自报服务器与代理来源不一致，使用代理来源: player=" + playerName
                    + ", reported=" + reportedServer + ", source=" + sourceServer);
        }
        String actualServer = adapter.player(playerId).map(ProxyPlayer::serverName).orElse("");
        String currentServer = playerServers.get(playerId);
        if ((!actualServer.isBlank() && !actualServer.equals(sourceServer))
                || (currentServer != null && !currentServer.equals(sourceServer))) {
            debug(() -> "quit_snapshot 来源不是当前后端，丢弃: player=" + playerName
                    + ", source=" + sourceServer + ", current=" + currentServer);
            return;
        }
        QuitSnapshot snapshot = new QuitSnapshot(playerId, playerName, sourceServer, renderedText, delivery,
                titleFadeIn, titleStay, titleFadeOut, bossbarColor, bossbarStyle, bossbarProgress, bossbarDuration);
        if (adapter.player(playerId).isEmpty() && disconnectedSessions.containsKey(playerId)) {
            debug(() -> "quit_snapshot 在 DisconnectEvent 后迟到，立即补发退出: player=" + playerName);
            forwardQuitSnapshot(snapshot);
            return;
        }
        quitSnapshots.put(playerId, snapshot);
        debug(() -> "已缓存退出渲染快照: player=" + playerName + ", server=" + sourceServer);
    }

    /** 处理后端发来的离开预渲染数据：真正退出 → 转发。 */
    private void handleQuitPrepare(String sourceServer, DataInputStream input) throws IOException {
        if (!config.broadcastConnectionMessagesEnabled()) return;
        UUID eventId = KaProxyProtocol.readUuid(input);
        UUID playerId = KaProxyProtocol.readUuid(input);
        String playerName = input.readUTF();
        String reportedServer = input.readUTF();
        String renderedText = input.readUTF();
        String delivery = input.readUTF();
        int titleFadeIn = input.readInt();
        int titleStay = input.readInt();
        int titleFadeOut = input.readInt();
        String bossbarColor = input.readUTF();
        String bossbarStyle = input.readUTF();
        float bossbarProgress = input.readFloat();
        int bossbarDuration = input.readInt();
        if (renderedText.isBlank() || alreadySeen(eventId)) return;
        if (!sourceServer.equals(reportedServer)) {
            debug(() -> "quit_prepare 自报服务器与代理来源不一致，使用代理来源: player=" + playerName
                    + ", reported=" + reportedServer + ", source=" + sourceServer);
        }
        String currentServer = playerServers.get(playerId);
        if (currentServer == null) {
            debug(() -> "quit_prepare 未命中拓扑（已清理或从未加入）: player=" + playerName);
            return;
        }
        if (!sourceServer.equals(currentServer)) {
            debug(() -> "quit_prepare 来源不是当前后端，丢弃迟到旧服包: player=" + playerName
                    + ", source=" + sourceServer + ", current=" + currentServer);
            return;
        }
        if (pendingSwitches.containsKey(playerId)) {
            // 平台已确认切服（ServerPostConnectEvent previousServer 非空）：丢弃本次退出预渲染。
            debug(() -> "quit_prepare 命中切服标记，丢弃: player=" + playerName);
            return;
        }
        // 后端 quit_prepare 仅携带渲染结果；真实退出只能由 DisconnectEvent 确认。
        // 超时仅处理已经离线但 DisconnectEvent/载体消息乱序的兜底，玩家仍在线时绝不转发。
        PendingQuit pending = new PendingQuit(eventId, playerId, playerName, currentServer, renderedText,
                delivery, titleFadeIn, titleStay, titleFadeOut,
                bossbarColor, bossbarStyle, bossbarProgress, bossbarDuration);
        pendingQuits.put(playerId, pending);
        debug(() -> "quit_prepare 挂起等待 DisconnectEvent: player=" + playerName + ", server=" + currentServer);
        if (adapter.player(playerId).isEmpty()) {
            confirmQuit(pending, false);
        } else {
            adapter.schedule(() -> confirmQuit(pending, false), QUIT_CONFIRM_TIMEOUT_MILLIS);
        }
    }

    /** 仅代理断开或确认玩家已离线时转发退出；绝不以固定时长推断离线。 */
    private synchronized void confirmQuit(PendingQuit pending, boolean disconnectConfirmed) {
        if (!config.broadcastEnabled() || !config.broadcastConnectionMessagesEnabled()) return;
        if (!disconnectConfirmed && adapter.player(pending.playerId()).isPresent()) {
            debug(() -> "quit_prepare 超时但玩家仍在线，继续等待平台代理信号: player=" + pending.playerName());
            return;
        }
        if (!pendingQuits.remove(pending.playerId(), pending)) {
            debug(() -> "quit_prepare 已被切服或新会话取消: player=" + pending.playerName());
            return;
        }
        if (!markQuitForwarded(pending.playerId())) {
            debug(() -> "退出已广播，丢弃重复 quit_prepare: player=" + pending.playerName());
            return;
        }
        debug(() -> "平台代理确认真实退出，转发 player_quit: player=" + pending.playerName()
                + ", server=" + pending.server());
        try {
            forwardConnection("player_quit", pending.eventId(), pending.playerId(), pending.playerName(),
                    pending.server(), pending.renderedText(), pending.delivery(),
                    pending.titleFadeIn(), pending.titleStay(), pending.titleFadeOut(),
                    pending.bossbarColor(), pending.bossbarStyle(), pending.bossbarProgress(),
                    pending.bossbarDuration());
        } catch (IOException error) {
            adapter.error(language.text("broadcast-packet-failed", Map.of("server", pending.server())), error);
        }
    }

    /** 用在线阶段缓存的渲染结果补发退出，专用于源服没有可用消息载体的真实断开。 */
    private void forwardQuitSnapshot(QuitSnapshot snapshot) {
        if (!markQuitForwarded(snapshot.playerId())) {
            debug(() -> "退出已广播，丢弃重复快照: player=" + snapshot.playerName());
            return;
        }
        try {
            debug(() -> "使用退出渲染快照转发 player_quit: player=" + snapshot.playerName()
                    + ", server=" + snapshot.server());
            forwardConnection("player_quit", UUID.randomUUID(), snapshot.playerId(), snapshot.playerName(),
                    snapshot.server(), snapshot.renderedText(), snapshot.delivery(),
                    snapshot.titleFadeIn(), snapshot.titleStay(), snapshot.titleFadeOut(),
                    snapshot.bossbarColor(), snapshot.bossbarStyle(), snapshot.bossbarProgress(),
                    snapshot.bossbarDuration());
        } catch (IOException error) {
            adapter.error(language.text("broadcast-packet-failed", Map.of("server", snapshot.server())), error);
        }
    }

    /** 标记当前会话已经发出退出，避免实时载荷与迟到快照重复广播。 */
    private boolean markQuitForwarded(UUID playerId) {
        long session = playerSessions.getOrDefault(playerId, 0L);
        Long previous = forwardedQuitSessions.put(playerId, session);
        return previous == null || previous != session;
    }

    private void forwardConnection(String action, UUID eventId, UUID playerId, String playerName, String server,
                                   String renderedText, String delivery,
                                   int titleFadeIn, int titleStay, int titleFadeOut,
                                    String bossbarColor, String bossbarStyle, float bossbarProgress,
                                    int bossbarDuration) throws IOException {
        forwardEvent(action, eventId, output -> {
            KaProxyProtocol.writeUuid(output, playerId);
            output.writeUTF(playerName);
            output.writeUTF(server);
            output.writeUTF(renderedText);
            output.writeUTF(delivery);
            output.writeInt(titleFadeIn);
            output.writeInt(titleStay);
            output.writeInt(titleFadeOut);
            output.writeUTF(bossbarColor);
            output.writeUTF(bossbarStyle);
            output.writeFloat(bossbarProgress);
            output.writeInt(bossbarDuration);
        });
    }

    private void forwardEvent(String action, UUID eventId, KaProxyProtocol.PacketWriter writer) throws IOException {
        byte[] packet = KaProxyProtocol.encode("broadcast", action, output -> {
            KaProxyProtocol.writeUuid(output, eventId);
            writer.write(output);
        });
        broadcastPacket(packet);
    }

    private void broadcastPacket(byte[] packet) {
        List<String> servers = config.broadcastServers();
        if (servers == null) {
            debug(() -> "转发广播包到全部后端");
            adapter.broadcast(KaProxyProtocol.CHANNEL, packet, null);
        } else {
            debug(() -> "转发广播包到指定后端: " + String.join(",", servers));
            for (String server : servers) adapter.broadcastToServer(KaProxyProtocol.CHANNEL, packet, server);
        }
    }

    /** 在 modules.broadcast.debug 开启时输出诊断日志。 */
    private void debug(java.util.function.Supplier<String> message) {
        if (config.broadcastDebug()) {
            adapter.info("[KaProxy/broadcast debug] " + message.get());
        }
    }

    private boolean alreadySeen(UUID eventId) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> iterator = recentEvents.entrySet().iterator();
        while (iterator.hasNext()) if (iterator.next().getValue() < now) iterator.remove();
        if (recentEvents.containsKey(eventId)) return true;
        recentEvents.put(eventId, now + EVENT_TTL_MILLIS);
        return false;
    }

    /** 切换观察期中暂缓的退出事件，观察期结束仍无目标服 join_prepare 则确认真实退出。 */
    private record PendingQuit(UUID eventId, UUID playerId, String playerName, String server,
                               String renderedText, String delivery,
                               int titleFadeIn, int titleStay, int titleFadeOut,
                               String bossbarColor, String bossbarStyle, float bossbarProgress,
                               int bossbarDuration) {
    }

    private record QuitSnapshot(UUID playerId, String playerName, String server,
                                String renderedText, String delivery,
                                int titleFadeIn, int titleStay, int titleFadeOut,
                                String bossbarColor, String bossbarStyle, float bossbarProgress,
                                int bossbarDuration) {
    }
}
