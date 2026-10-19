package org.katacr.kaproxy.core;

import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 在代理端权威管理 /back 与 /dback 的跨服切服和落点交付。 */
final class BackModule {
    private final ProxyAdapter adapter;
    private volatile KaProxyConfig config;
    private volatile KaProxyLanguage language;
    private final Map<UUID, PendingBack> pending = new HashMap<>();

    /** 创建绑定代理配置的跨服返回模块。 */
    BackModule(ProxyAdapter adapter, KaProxyConfig config, KaProxyLanguage language) {
        this.adapter = adapter;
        this.config = config;
        this.language = language;
    }

    /** 热更新配置和语言。 */
    void reload(KaProxyConfig config, KaProxyLanguage language) {
        this.config = config;
        this.language = language;
    }

    /** 模块热关闭时清理全部待交付事务。 */
    synchronized void disable() {
        pending.clear();
    }

    /** 处理后端提交的返回请求：切服并在到达后交付落点。 */
    synchronized void handle(ProxyPlayer carrier, String sourceServer, String action,
                              DataInputStream input) throws IOException {
        if (!carrier.serverName().equals(sourceServer)) {
            return;
        }
        if ("back_arrival_complete".equals(action) || "back_arrival_failed".equals(action)) {
            UUID id = KaProxyProtocol.readUuid(input);
            PendingBack entry = pending.get(carrier.uniqueId());
            if (entry != null && entry.id.equals(id) && entry.targetServer.equals(sourceServer)) {
                if ("back_arrival_failed".equals(action)) {
                    String reason = input.readUTF();
                    sendFailure(carrier, entry.id, reason);
                }
                pending.remove(carrier.uniqueId());
            }
            return;
        }
        if (!"back_request".equals(action)) {
            return;
        }
        String targetServer = input.readUTF();
        String actionType = input.readUTF();
        String world = input.readUTF();
        double x = input.readDouble();
        double y = input.readDouble();
        double z = input.readDouble();
        float yaw = input.readFloat();
        float pitch = input.readFloat();
        long timestamp = input.readLong();
        if (targetServer.isBlank()) {
            sendFailure(carrier, null, "connect-failed");
            return;
        }
        UUID playerId = carrier.uniqueId();
        PendingBack existing = pending.remove(playerId);
        PendingBack entry = new PendingBack(UUID.randomUUID(), playerId, targetServer, actionType, world, x, y, z, yaw, pitch, timestamp);
        Runnable timeout = () -> expire(playerId, entry);
        entry.timeoutTask = timeout;
        pending.put(playerId, entry);
        adapter.schedule(timeout, config.backTransactionTimeoutSeconds() * 1000L);
        adapter.schedule(() -> retryArrival(playerId, entry), 500L);
        carrier.connect(targetServer, success -> {
            if (!Boolean.TRUE.equals(success)) {
                synchronized (BackModule.this) {
                    if (pending.remove(playerId, entry)) {
                        sendFailure(carrier, entry.id, "connect-failed");
                    }
                }
            }
        });
    }

    /** 玩家连接子服后交付等待中的返回落点。 */
    synchronized void playerConnected(ProxyPlayer player) {
        PendingBack entry = pending.get(player.uniqueId());
        if (entry == null || !player.serverName().equals(entry.targetServer)) {
            return;
        }
        deliverArrival(player, entry);
    }

    /** 投递不等于消费；在目标服确认前持续尝试，直到事务超时。 */
    private synchronized void retryArrival(UUID playerId, PendingBack entry) {
        if (pending.get(playerId) != entry) {
            return;
        }
        adapter.player(playerId).ifPresent(player -> {
            if (entry.targetServer.equals(player.serverName())) {
                deliverArrival(player, entry);
            }
        });
        adapter.schedule(() -> retryArrival(playerId, entry), 500L);
    }

    private void deliverArrival(ProxyPlayer player, PendingBack entry) {
        try {
            byte[] packet = KaProxyProtocol.encode("back", "back_arrival", output -> {
                KaProxyProtocol.writeUuid(output, entry.id);
                output.writeUTF(entry.actionType);
                output.writeUTF(entry.world);
                output.writeDouble(entry.x);
                output.writeDouble(entry.y);
                output.writeDouble(entry.z);
                output.writeFloat(entry.yaw);
                output.writeFloat(entry.pitch);
                output.writeLong(entry.timestamp);
            });
            player.sendToBackend(KaProxyProtocol.CHANNEL, packet);
        } catch (IOException error) {
            adapter.error(language.text("back-encode-failed"), error);
        }
    }

    /** 玩家离开代理时清理其返回事务。 */
    synchronized void playerDisconnected(UUID playerId) {
        pending.remove(playerId);
    }

    /** 目标服确认返回传送完成；事务已在交付时移除，仅做防御性清理。 */
    /** 超时清理未完成交付的返回事务。 */
    private synchronized void expire(UUID playerId, PendingBack entry) {
        if (pending.remove(playerId, entry)) {
            adapter.player(playerId).ifPresent(player -> sendFailure(player, entry.id, "transaction-timeout"));
        }
    }

    /** 向玩家当前后端发送失败原因。 */
    private void sendFailure(ProxyPlayer player, UUID id, String reason) {
        try {
            byte[] packet = KaProxyProtocol.encode("back", "back_failed",
                    output -> {
                        KaProxyProtocol.writeUuid(output, id == null ? new UUID(0L, 0L) : id);
                        output.writeUTF(reason);
                    });
            player.sendToBackend(KaProxyProtocol.CHANNEL, packet);
        } catch (IOException error) {
            adapter.error(language.text("back-encode-failed"), error);
        }
    }

    /** 保存一次待交付的返回落点和超时任务。 */
    private static final class PendingBack {
        final UUID id;
        final UUID playerId;
        final String targetServer;
        final String actionType;
        final String world;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final long timestamp;
        Runnable timeoutTask;

        /** 创建待交付返回事务。 */
        private PendingBack(UUID id, UUID playerId, String targetServer, String actionType, String world,
                            double x, double y, double z, float yaw, float pitch, long timestamp) {
            this.id = id;
            this.playerId = playerId;
            this.targetServer = targetServer;
            this.actionType = actionType;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.timestamp = timestamp;
        }
    }
}
