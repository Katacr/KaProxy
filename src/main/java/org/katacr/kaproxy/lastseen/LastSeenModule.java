package org.katacr.kaproxy.lastseen;

import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.core.ProxyAdapter;
import org.katacr.kaproxy.core.ProxyPlayer;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * “上次下线位置”模块。
 *
 * <p>坐标由后端 KaLogin 在玩家退服（含切服）时直接写入共享 MySQL（不依赖插件消息载体）；
 * {@code server} 列由代理在玩家<b>真正离开</b>时写入（切服不写），因此只有 server 非空的
 * 记录才是代理确认的离开位置。代理在玩家初次连接时异步读库决定初始子服，认证完成后
 * （后端发送 {@code ready}）投递坐标传送。</p>
 *
 * <p>协议（module = {@code lastseen}）：
 * <ul>
 *   <li>后端 → 代理：{@code ready}(uuid)、{@code abort}(uuid)</li>
 *   <li>代理 → 目标后端：{@code teleport}(uuid,world,x,y,z,yaw,pitch)</li>
 * </ul></p>
 */
public final class LastSeenModule {
    private final ProxyAdapter adapter;
    private final LastSeenRepository repository;
    private volatile KaProxyConfig config;
    private volatile KaProxyLanguage language;
    private final Map<UUID, String> lastKnownServer = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> traveled = ConcurrentHashMap.newKeySet();

    /** 创建“上次下线位置”模块。 */
    public LastSeenModule(ProxyAdapter adapter, LastSeenRepository repository,
                          KaProxyConfig config, KaProxyLanguage language) {
        this.adapter = adapter;
        this.repository = repository;
        this.config = config;
        this.language = language;
    }

    /** 热更新配置；模块被禁用时清空会话标记。 */
    public void reload(KaProxyConfig config, KaProxyLanguage language) {
        this.config = config;
        this.language = language;
        if (!config.lastSeenEnabled()) {
            lastKnownServer.clear();
            traveled.clear();
        }
    }

    /** 清理模块状态。 */
    public void disable() {
        lastKnownServer.clear();
        traveled.clear();
    }

    /** 会话失效（登出/注销）时清除该玩家的位置记录。 */
    public void clear(UUID playerId) {
        traveled.remove(playerId);
        lastKnownServer.remove(playerId);
        repository.delete(playerId);
    }

    /** 直接模式：代理在初次连接时就把玩家指定到上次所在子服。 */
    private boolean isDirectMode() {
        return "direct".equalsIgnoreCase(config.lastSeenMode());
    }

    /** 手动模式：先正常进默认服，登录后弹窗询问。 */
    private boolean isManualMode() {
        return "manual".equalsIgnoreCase(config.lastSeenMode());
    }

    /** 自动模式：先正常进默认服，登录成功后延迟再自动传送。 */
    private boolean isAutoMode() {
        return "auto".equalsIgnoreCase(config.lastSeenMode());
    }

    /**
     * 初次连接时用于选择初始子服的推荐目标：异步读取记录所在服。
     * 仅 direct 模式生效；未启用、无记录、记录未确认（server 为空）或命中黑名单时返回空。
     */
    public CompletableFuture<Optional<String>> initialServerFor(UUID playerId) {
        if (!config.lastSeenEnabled() || !isDirectMode()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return repository.load(playerId)
                .thenApply(record -> record
                        .map(LastSeenRepository.Record::server)
                        .filter(server -> !isBlacklisted(server)))
                .exceptionally(error -> {
                    debug(() -> "读取上次位置失败: " + error.getMessage());
                    return Optional.empty();
                });
    }

    /** 解码并处理后端位置请求。 */
    public void handle(ProxyPlayer carrier, String sourceServer, String action, DataInputStream input) {
        if (!config.lastSeenEnabled()) {
            return;
        }
        try {
            switch (action) {
                case "ready" -> handleReady(carrier, input);
                case "abort" -> handleAbort(carrier, input);
                case "confirm" -> handleConfirm(carrier, input);
                case "decline" -> handleDecline(carrier, input);
                default -> {
                    if (config.logUnknownModules()) {
                        adapter.info(language.text("unknown-lastseen-action", Map.of("action", action)));
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            adapter.error(language.text("lastseen-packet-failed", Map.of("server", sourceServer)), error);
        }
    }

    /**
     * KaLogin 认证完成：按模式处理。
     * direct：直接前往（初始服已是目标服，通常只需传送坐标）；
     * manual：下发 offer，由后端延迟弹窗后回传 confirm/decline；
     * auto：延迟 join-delay-seconds 秒后自动前往（先留在默认服下载资源包）。
     */
    private void handleReady(ProxyPlayer carrier, DataInputStream input) throws IOException {
        UUID playerId = KaProxyProtocol.readUuid(input);
        if (carrier == null || !carrier.uniqueId().equals(playerId)) {
            debug(() -> "忽略载体不匹配的 ready: " + playerId);
            return;
        }
        if (!traveled.add(playerId)) {
            debug(() -> "本会话已处理过前往/询问，忽略: player=" + carrier.name());
            return;
        }
        // 手动模式：不自动前往，改为向后端下发询问（后端延迟后弹窗）
        if (isManualMode()) {
            repository.load(playerId).thenAccept(record -> {
                if (record.isEmpty() || isBlacklisted(record.get().server())) {
                    debug(() -> "手动模式无可用记录，不询问: player=" + carrier.name());
                    return;
                }
                sendOffer(carrier.serverName(), playerId, record.get());
                debug(() -> "下发返回询问: player=" + carrier.name());
            }).exceptionally(error -> {
                debug(() -> "读取位置记录失败，不询问: " + error.getMessage());
                return null;
            });
            return;
        }
        // 自动模式：先留在默认服，延迟后再前往
        if (isAutoMode()) {
            int delaySeconds = config.lastSeenJoinDelaySeconds();
            if (delaySeconds > 0) {
                debug(() -> "自动模式延迟 " + delaySeconds + " 秒后前往: player=" + carrier.name());
                adapter.schedule(() -> {
                    ProxyPlayer player = adapter.player(playerId).orElse(null);
                    if (player == null) {
                        debug(() -> "玩家已离线，取消延迟前往: " + playerId);
                        return;
                    }
                    travelToRecord(player);
                }, delaySeconds * 1000L);
                return;
            }
        }
        // direct 或 auto（延迟为 0）：立即前往
        travelToRecord(carrier);
    }

    /** 读取记录并前往：同服则只传送坐标，否则切服后投递坐标。 */
    private void travelToRecord(ProxyPlayer carrier) {
        UUID playerId = carrier.uniqueId();
        repository.load(playerId).thenAccept(record -> {
            if (record.isEmpty()) {
                debug(() -> "无位置记录，停留在当前服: player=" + carrier.name());
                return;
            }
            LastSeenRepository.Record position = record.get();
            if (isBlacklisted(position.server())) {
                debug(() -> "记录命中黑名单，停留在当前服: player=" + carrier.name());
                return;
            }
            String current = carrier.serverName();
            sendTeleport(position.server(), playerId, position);
            if (position.server().equals(current)) {
                debug(() -> "同服传送: player=" + carrier.name() + ", server=" + current);
                return;
            }
            debug(() -> "前往: player=" + carrier.name() + ", from=" + current + ", to=" + position.server());
            carrier.connect(position.server(), success -> {
                if (!success) {
                    debug(() -> "前往失败，停留在当前服: player=" + carrier.name() + ", target=" + position.server());
                }
            });
        }).exceptionally(error -> {
            debug(() -> "读取位置记录失败，停留在当前服: " + error.getMessage());
            return null;
        });
    }

    /** 手动模式：玩家点击“确定”返回上次位置（切服并投递坐标）。 */
    private void handleConfirm(ProxyPlayer carrier, DataInputStream input) throws IOException {
        UUID playerId = KaProxyProtocol.readUuid(input);
        if (carrier == null || !carrier.uniqueId().equals(playerId)) {
            debug(() -> "忽略载体不匹配的 confirm: " + playerId);
            return;
        }
        travelToRecord(carrier);
    }

    /** 手动模式：玩家点击“取消”，保留记录并停留在当前服。 */
    private void handleDecline(ProxyPlayer carrier, DataInputStream input) throws IOException {
        UUID playerId = KaProxyProtocol.readUuid(input);
        debug(() -> "玩家取消返回上次位置，保留记录: " + playerId);
    }

    /** 目标后端判定位置无效（世界不存在等）：把玩家回退到默认子服。 */
    private void handleAbort(ProxyPlayer carrier, DataInputStream input) throws IOException {
        UUID playerId = KaProxyProtocol.readUuid(input);
        String fallback = config.lastSeenDefaultServer();
        if (fallback.isEmpty()) {
            debug(() -> "位置无效且未配置默认服，停留在当前服: " + playerId);
            return;
        }
        ProxyPlayer player = carrier;
        if (player == null) {
            player = adapter.player(playerId).orElse(null);
        }
        if (player == null || fallback.equals(player.serverName())) {
            return;
        }
        final ProxyPlayer target = player;
        debug(() -> "位置无效，回退默认服: player=" + target.name() + ", fallback=" + fallback);
        target.connect(fallback, success -> {
            if (!success) {
                debug(() -> "回退默认服失败: " + target.name());
            }
        });
    }

    /** 向目标后端发送传送指令（后端缓存在玩家进服完成认证后应用）。 */
    private void sendTeleport(String server, UUID playerId, LastSeenRepository.Record record) {
        try {
            byte[] packet = KaProxyProtocol.encode("lastseen", "teleport", output -> {
                KaProxyProtocol.writeUuid(output, playerId);
                output.writeUTF(record.world());
                output.writeDouble(record.x());
                output.writeDouble(record.y());
                output.writeDouble(record.z());
                output.writeFloat(record.yaw());
                output.writeFloat(record.pitch());
            });
            adapter.broadcastToServer(KaProxyProtocol.CHANNEL, packet, server);
        } catch (IOException error) {
            adapter.error(language.text("lastseen-packet-failed", Map.of("server", server)), error);
        }
    }

    /**
     * 手动模式：向玩家当前所在后端下发询问包，由其立即弹出确认框。
     * 负载为 uuid + 延迟秒数（手动模式固定 0，延迟仅供 auto 模式）+ 目标子服名（仅用于后端日志/提示）。
     */
    private void sendOffer(String server, UUID playerId, LastSeenRepository.Record record) {
        if (server == null || server.isBlank()) {
            return;
        }
        try {
            byte[] packet = KaProxyProtocol.encode("lastseen", "offer", output -> {
                KaProxyProtocol.writeUuid(output, playerId);
                output.writeInt(0);
                output.writeUTF(record.server());
            });
            adapter.broadcastToServer(KaProxyProtocol.CHANNEL, packet, server);
        } catch (IOException error) {
            adapter.error(language.text("lastseen-packet-failed", Map.of("server", server)), error);
        }
    }

    /** 记录玩家当前所在子服，供真正离开时写入。 */
    public void playerConnected(ProxyPlayer player) {
        if (!config.lastSeenEnabled()) {
            return;
        }
        String server = player.serverName();
        if (server != null && !server.isBlank()) {
            lastKnownServer.put(player.uniqueId(), server);
        }
    }

    /**
     * 玩家真正离开代理（DisconnectEvent / PlayerDisconnectEvent）时，把其所在子服写入记录。
     * 黑名单子服（如副本/竞技场）改为删除记录，使下次登录回默认服。
     */
    public void playerDisconnected(ProxyPlayer player) {
        if (!config.lastSeenEnabled()) {
            return;
        }
        UUID playerId = player.uniqueId();
        traveled.remove(playerId);
        String server = lastKnownServer.remove(playerId);
        if (server == null || server.isBlank()) {
            server = player.serverName();
        }
        if (server == null || server.isBlank()) {
            return;
        }
        final String finalServer = server;
        if (isBlacklisted(server)) {
            repository.delete(playerId);
            debug(() -> "离开黑名单子服，清除位置记录: player=" + player.name() + ", server=" + finalServer);
            return;
        }
        repository.saveServer(playerId, server);
        debug(() -> "写入离开子服: player=" + player.name() + ", server=" + finalServer);
    }

    /** 判断子服是否在“不记录位置”黑名单中（大小写不敏感）。 */
    private boolean isBlacklisted(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return false;
        }
        for (String blocked : config.lastSeenBlacklist()) {
            if (blocked.equalsIgnoreCase(serverName)) {
                return true;
            }
        }
        return false;
    }

    private void debug(java.util.function.Supplier<String> message) {
        if (config.lastSeenDebug()) {
            adapter.info("[KaProxy/lastseen debug] " + message.get());
        }
    }
}
