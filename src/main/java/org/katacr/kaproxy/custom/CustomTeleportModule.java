package org.katacr.kaproxy.custom;

import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.core.ProxyAdapter;
import org.katacr.kaproxy.core.ProxyPlayer;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 第三方插件专用跨服传送模块。
 *
 * <p>后端提交 {@code customteleport/transfer} 请求（目标子服 + 世界 + 坐标），
 * 代理保存待交付事务并切换玩家子服，玩家到达目标子服后下发
 * {@code customteleport/arrival}，由目标后端完成最终落点传送。</p>
 *
 * <p>与 {@code back} 模块相互独立，字段带 actionType 便于未来扩展多种传送语义。</p>
 */
public final class CustomTeleportModule {
    private final ProxyAdapter adapter;
    private volatile KaProxyConfig config;
    private volatile KaProxyLanguage language;
    private final Map<UUID, PendingTransfer> pending = new HashMap<>();

    /** 创建绑定代理配置的第三方传送模块。 */
    public CustomTeleportModule(ProxyAdapter adapter, KaProxyConfig config, KaProxyLanguage language) {
        this.adapter = adapter;
        this.config = config;
        this.language = language;
    }

    /** 热更新配置和语言。 */
    public void reload(KaProxyConfig config, KaProxyLanguage language) {
        this.config = config;
        this.language = language;
    }

    /** 模块热关闭时清理全部待交付事务。 */
    public synchronized void disable() {
        pending.clear();
    }

    /**
     * 处理后端提交的第三方传送请求。
     *
     * 负载字段（顺序）：targetServer(UTF)、actionType(UTF)、world(UTF)、
     * x/y/z(double)、yaw/pitch(float)、timestamp(long)。
     */
    public synchronized void handle(ProxyPlayer carrier, String sourceServer, String action,
                                    DataInputStream input) throws IOException {
        if (!carrier.serverName().equals(sourceServer)) {
            return;
        }
        switch (action) {
            case "transfer" -> handleTransfer(carrier, input);
            case "arrival_complete" -> handleArrivalComplete(carrier, input);
            case "arrival_failed" -> handleArrivalFailed(carrier, input);
            default -> adapter.info(language.text("unknown-customteleport-action",
                    Map.of("action", action)));
        }
    }

    private void handleTransfer(ProxyPlayer carrier, DataInputStream input) throws IOException {
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
            sendFailure(carrier, "connect-failed");
            return;
        }

        UUID playerId = carrier.uniqueId();
        PendingTransfer existing = pending.remove(playerId);
        if (existing != null && existing.timeoutTask != null) {
            adapter.schedule(existing.timeoutTask, 0L);
        }

        PendingTransfer entry = new PendingTransfer(playerId, targetServer, actionType,
                world, x, y, z, yaw, pitch, timestamp);
        Runnable timeout = () -> expire(playerId, entry);
        entry.timeoutTask = timeout;
        pending.put(playerId, entry);
        adapter.schedule(timeout, config.customTeleportTimeoutSeconds() * 1000L);

        carrier.connect(targetServer, success -> {
            if (!Boolean.TRUE.equals(success)) {
                synchronized (CustomTeleportModule.this) {
                    if (pending.remove(playerId, entry)) {
                        sendFailure(carrier, "connect-failed");
                    }
                }
            }
        });
    }

    private void handleArrivalComplete(ProxyPlayer carrier, DataInputStream input) {
        pending.remove(carrier.uniqueId());
    }

    private void handleArrivalFailed(ProxyPlayer carrier, DataInputStream input) throws IOException {
        String reason = input.readUTF();
        pending.remove(carrier.uniqueId());
        sendFailure(carrier, reason);
    }

    /** 玩家连接子服后交付等待中的传送落点。 */
    public synchronized void playerConnected(ProxyPlayer player) {
        PendingTransfer entry = pending.get(player.uniqueId());
        if (entry == null || !player.serverName().equals(entry.targetServer)) {
            return;
        }
        pending.remove(player.uniqueId());
        if (entry.timeoutTask != null) {
            adapter.schedule(entry.timeoutTask, 0L);
        }
        try {
            byte[] packet = KaProxyProtocol.encode("customteleport", "arrival", output -> {
                output.writeUTF(entry.actionType);
                output.writeUTF(entry.world);
                output.writeDouble(entry.x);
                output.writeDouble(entry.y);
                output.writeDouble(entry.z);
                output.writeFloat(entry.yaw);
                output.writeFloat(entry.pitch);
                output.writeLong(entry.timestamp);
            });
            if (!player.sendToBackend(KaProxyProtocol.CHANNEL, packet)) {
                sendFailure(player, "arrival-delivery-failed");
            }
        } catch (IOException error) {
            adapter.error(language.text("customteleport-encode-failed"), error);
            sendFailure(player, "arrival-delivery-failed");
        }
    }

    /** 玩家离开代理时清理其传送事务。 */
    public synchronized void playerDisconnected(UUID playerId) {
        PendingTransfer entry = pending.remove(playerId);
        if (entry != null && entry.timeoutTask != null) {
            adapter.schedule(entry.timeoutTask, 0L);
        }
    }

    /** 超时清理未完成交付的传送事务。 */
    private synchronized void expire(UUID playerId, PendingTransfer entry) {
        if (pending.remove(playerId, entry)) {
            adapter.player(playerId).ifPresent(player -> sendFailure(player, "transaction-timeout"));
        }
    }

    /** 向玩家当前后端发送失败原因。 */
    private void sendFailure(ProxyPlayer player, String reason) {
        try {
            byte[] packet = KaProxyProtocol.encode("customteleport", "failed",
                    output -> output.writeUTF(reason));
            player.sendToBackend(KaProxyProtocol.CHANNEL, packet);
        } catch (IOException error) {
            adapter.error(language.text("customteleport-encode-failed"), error);
        }
    }

    /** 保存一次待交付的传送落点和超时任务。 */
    private static final class PendingTransfer {
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

        private PendingTransfer(UUID playerId, String targetServer, String actionType, String world,
                                double x, double y, double z, float yaw, float pitch, long timestamp) {
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
