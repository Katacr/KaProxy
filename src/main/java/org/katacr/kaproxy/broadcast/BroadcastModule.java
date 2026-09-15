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
    }

    /**
     * 代理平台连接事件回调（Velocity ServerPostConnectEvent / Bungee ServerConnectedEvent）。
     *
     * previousServer 为 Velocity 提供的切换前子服（新会话为 null；Bungee 无此信息传 null，
     * 由内部从拓扑和待定退出推断）。判定为切服时记录来源服，等待目标服的 join_prepare
     * 触发 switch_request；全新会话则清理残留状态，保证之后转发 player_join 而非切换消息。
     */
    public synchronized void playerConnected(ProxyPlayer player, String previousServer) {
        if (!config.broadcastEnabled()) return;
        UUID playerId = player.uniqueId();
        String currentServer = player.serverName();
        PendingQuit pendingQuit = pendingQuits.remove(playerId);
        String from = previousServer;
        if (from == null) from = pendingSwitches.remove(playerId);
        if (from == null && pendingQuit != null) from = pendingQuit.server();
        if (from == null) from = playerServers.get(playerId);
        pendingQuits.remove(playerId);
        if (from != null && !from.isBlank() && !from.equals(currentServer)) {
            // 切服（平台事件铁证）：等待目标服 join_prepare 后渲染切换消息。
            String finalFrom = from;
            pendingSwitches.put(playerId, finalFrom);
            debug(() -> "平台确认切服: player=" + player.name() + ", from=" + finalFrom + ", to=" + currentServer);
        } else {
            // 首次进入或断线重连：清理残留，join_prepare 将转发 player_join。
            pendingSwitches.remove(playerId);
            playerServers.remove(playerId);
        }
        if (!currentServer.isBlank()) playerServers.put(playerId, currentServer);
    }

    /**
     * 玩家真正离开代理（DisconnectEvent）：立即确认观察期中的退出转发，
     * 拓扑延迟删除，给迟到的 quit_prepare（经同服其他玩家载体）留出发送窗口。
     */
    public synchronized void playerDisconnected(ProxyPlayer player) {
        UUID playerId = player.uniqueId();
        pendingSwitches.remove(playerId);
        PendingQuit pending = pendingQuits.remove(playerId);
        if (pending != null) {
            confirmQuit(pending);
        }
        if (playerServers.containsKey(playerId)) {
            adapter.schedule(() -> {
                if (adapter.player(playerId).isEmpty()) playerServers.remove(playerId);
            }, TOPOLOGY_TTL_MILLIS);
        }
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

    /** 处理后端发来的离开预渲染数据：真正退出 → 转发。 */
    private void handleQuitPrepare(String sourceServer, DataInputStream input) throws IOException {
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
        if (pendingSwitches.containsKey(playerId)) {
            // 平台已确认切服（ServerPostConnectEvent previousServer 非空）：丢弃本次退出预渲染。
            debug(() -> "quit_prepare 命中切服标记，丢弃: player=" + playerName);
            return;
        }
        String previous = playerServers.remove(playerId);
        if (previous == null) {
            debug(() -> "quit_prepare 未命中拓扑（已清理或从未加入）: player=" + playerName);
            return;
        }
        // 玩家切服时旧服也会触发 PlayerQuitEvent 并送达 quit_prepare；
        // 挂起等待平台信号（DisconnectEvent 确认真实退出 / ServerConnectedEvent 取消），超时兜底确认。
        PendingQuit pending = new PendingQuit(eventId, playerId, playerName, previous, renderedText,
                delivery, titleFadeIn, titleStay, titleFadeOut,
                bossbarColor, bossbarStyle, bossbarProgress, bossbarDuration);
        pendingQuits.put(playerId, pending);
        debug(() -> "quit_prepare 挂起等待平台确认: player=" + playerName + ", server=" + previous);
        adapter.schedule(() -> confirmQuit(pending), QUIT_CONFIRM_TIMEOUT_MILLIS);
    }

    /** 切换观察期结束时仍无该玩家的 join_prepare，则确认真实退出并转发。 */
    private synchronized void confirmQuit(PendingQuit pending) {
        if (!config.broadcastEnabled() || !config.broadcastConnectionMessagesEnabled()) return;
        if (!pendingQuits.remove(pending.playerId(), pending)) {
            debug(() -> "quit_prepare 观察期内检测到切服，取消退出转发: player=" + pending.playerName());
            return;
        }
        debug(() -> "quit_prepare 观察期结束确认真实退出，转发 player_quit: player=" + pending.playerName()
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
}
