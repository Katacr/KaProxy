package org.katacr.kaproxy.core;

import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.Map;

/** 统一管理 Ka 系列插件消息路由、在线玩家同步和模块事务分发。 */
public final class KaProxyCore {
    private final ProxyAdapter adapter;
    private volatile KaProxyConfig config;
    private volatile KaProxyLanguage language;
    private final TpaModule tpaModule;
    private final BackModule backModule;
    // 旧公会在线列表周期性广播间隔（毫秒）。即使后端无在线玩家，
    // 也需主动向所有后端推送 OnlinePlayersList，供 KaGuilds 启动探测使用。
    private static final long LEGACY_PRESENCE_INTERVAL_MS = 5_000L;

    /** 创建绑定具体代理平台的 KaProxy 核心。 */
    public KaProxyCore(ProxyAdapter adapter, KaProxyConfig config, KaProxyLanguage language) {
        this.adapter = adapter;
        this.config = config;
        this.language = language;
        this.tpaModule = new TpaModule(adapter, config, language);
        this.backModule = new BackModule(adapter, config, language);
    }

    /** 启动后台周期任务（如旧公会在线列表广播）。应在代理初始化后调用一次。 */
    public void startBackgroundTasks() {
        scheduleLegacyPresenceBroadcast();
    }

    /** 周期性向所有后端广播旧格式在线列表，确保新启动的后端也能完成探测。 */
    private void scheduleLegacyPresenceBroadcast() {
        adapter.schedule(() -> {
            if (config.guildsLegacyChannelEnabled()) {
                broadcastLegacyGuildPresence();
            }
            scheduleLegacyPresenceBroadcast();
        }, LEGACY_PRESENCE_INTERVAL_MS);
    }

    /** 热更新模块参数和语言文本，不丢弃正在进行的事务。 */
    public void reload(KaProxyConfig config, KaProxyLanguage language) {
        boolean disableTpa = this.config.tpaEnabled() && !config.tpaEnabled();
        boolean disableBack = this.config.backEnabled() && !config.backEnabled();
        this.config = config;
        this.language = language;
        this.tpaModule.reload(config, language);
        this.backModule.reload(config, language);
        if (disableTpa) {
            this.tpaModule.disable();
        }
        if (disableBack) {
            this.backModule.disable();
        }
    }

    /** 玩家进入、切服或离开代理后广播统一及旧公会在线列表。 */
    public void playerTopologyChanged() {
        broadcastPresence();
        broadcastLegacyGuildPresence();
    }

    /** 玩家真正离开代理时取消其参与的短期事务。 */
    public void playerDisconnected(ProxyPlayer player) {
        if (config.tpaEnabled()) {
            tpaModule.playerDisconnected(player.uniqueId());
        }
        if (config.backEnabled()) {
            backModule.playerDisconnected(player.uniqueId());
        }
        playerTopologyChanged();
    }

    /** 玩家连接目标子服后尝试交付等待中的一次性到达凭证。 */
    public void playerConnected(ProxyPlayer player) {
        playerTopologyChanged();
        if (config.tpaEnabled()) {
            tpaModule.playerConnected(player);
        }
        if (config.backEnabled()) {
            backModule.playerConnected(player);
        }
    }

    /** 保持 KaGuilds 现有 kaguilds:chat 广播语义，迁移时无需修改后端插件。 */
    public void handleLegacyGuildMessage(String sourceServer, byte[] data) {
        if (!config.guildsLegacyChannelEnabled()) {
            return;
        }
        // 识别后端发来的探针包：告知其真实服务器名，避免多服误用默认 server-id。
        // 同时回一份全量在线列表，确保后端（即使错过代理启动广播）也能立即完成探测。
        if (isServerHello(data)) {
            sendServerIdentify(sourceServer);
            broadcastLegacyGuildPresence();
            return;
        }
        adapter.broadcast(KaProxyProtocol.LEGACY_GUILDS_CHANNEL, data, sourceServer);
    }

    /** 判断 legacy 包首字段是否为后端身份探针 ServerHello。 */
    private boolean isServerHello(byte[] data) {
        try {
            var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
            return "ServerHello".equals(input.readUTF());
        } catch (IOException ignored) {
            return false;
        }
    }

    /** 向来源后端回执其真实服务器名。 */
    private void sendServerIdentify(String sourceServer) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var output = new java.io.DataOutputStream(bytes)) {
                output.writeUTF("ServerIdentify");
                output.writeUTF(sourceServer);
            }
            adapter.broadcastToServer(KaProxyProtocol.LEGACY_GUILDS_CHANNEL, bytes.toByteArray(), sourceServer);
        } catch (IOException error) {
            adapter.error(language.text("guild-presence-encode-failed"), error);
        }
    }

    /** 校验统一信封并把数据包交给对应业务模块。 */
    public void handleMessage(ProxyPlayer carrier, String sourceServer, byte[] data) {
        try {
            KaProxyProtocol.Packet packet = KaProxyProtocol.decode(data);
            switch (packet.module()) {
                case "core" -> handleCore(carrier, packet.action());
                case "tpa" -> {
                    if (config.tpaEnabled()) {
                        tpaModule.handle(carrier, sourceServer, packet.action(), packet.input());
                    }
                }
                case "back" -> {
                    if (config.backEnabled()) {
                        backModule.handle(carrier, sourceServer, packet.action(), packet.input());
                    }
                }
                case "kamenu" -> {
                    if (config.kamenuEnabled()) {
                        handleKamenuDispatch(carrier, sourceServer, packet.input());
                    }
                }
                default -> {
                    if (config.logUnknownModules()) {
                        adapter.info(language.text("unknown-module", Map.of("module", packet.module())));
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            adapter.error(language.text("packet-decode-failed", Map.of("server", sourceServer)), error);
        }
    }

    /** 处理无需业务状态的核心请求。 */
    private void handleCore(ProxyPlayer carrier, String action) {
        if ("sync_request".equals(action)) {
            sendPresence(carrier);
        }
    }

    /**
     * 把 KaMenu 跨服动作包转发到 config 中配置的目标后端，排除来源服。
     *
     * 代理不解析选择器条件，只做透传路由；后端收到后本地求值并执行。
     * servers 为 null 时转发到所有有玩家连接的后端。
     */
    private void handleKamenuDispatch(ProxyPlayer carrier, String sourceServer, DataInputStream input) {
        try {
            byte[] payload = input.readAllBytes();
            byte[] forwardPacket = KaProxyProtocol.encode("kamenu", "execute", output -> {
                output.writeUTF(sourceServer);
                output.write(payload);
            });
            var targets = config.kamenuServers();
            for (var player : adapter.players()) {
                if (player.serverName().isBlank() || player.serverName().equals(sourceServer)) continue;
                if (targets != null && !targets.contains(player.serverName())) continue;
                player.sendToBackend(KaProxyProtocol.CHANNEL, forwardPacket);
            }
        } catch (IOException error) {
            adapter.error(language.text("kamenu-forward-failed", Map.of("server", sourceServer, "error", error.getMessage())), error);
        }
    }

    /** 向所有当前有玩家连接的后端广播全服在线玩家快照。 */
    private void broadcastPresence() {
        try {
            byte[] packet = presencePacket("");
            adapter.broadcast(KaProxyProtocol.CHANNEL, packet, null);
        } catch (IOException error) {
            adapter.error(language.text("presence-encode-failed"), error);
        }
    }

    /** 向指定玩家所在后端返回全服在线玩家快照，并附带该后端的真实服务器名。 */
    private void sendPresence(ProxyPlayer carrier) {
        try {
            carrier.sendToBackend(KaProxyProtocol.CHANNEL, presencePacket(carrier.serverName()));
        } catch (IOException error) {
            adapter.error(language.text("presence-encode-failed"), error);
        }
    }

    /** 构建统一协议的在线玩家快照，yourServerName 为接收后端的真实服务器名。 */
    private byte[] presencePacket(String yourServerName) throws IOException {
        var players = adapter.players().stream()
                .filter(player -> !player.serverName().isBlank())
                .sorted(Comparator.comparing(ProxyPlayer::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return KaProxyProtocol.encode("core", "presence", output -> {
            output.writeUTF(yourServerName);
            output.writeInt(players.size());
            for (ProxyPlayer player : players) {
                KaProxyProtocol.writeUuid(output, player.uniqueId());
                output.writeUTF(player.name());
                output.writeUTF(player.serverName());
            }
        });
    }

    /** 继续生成 KaGuilds 能直接读取的旧版在线列表。 */
    private void broadcastLegacyGuildPresence() {
        if (!config.guildsPlayerListSync()) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("OnlinePlayersList");
                var players = adapter.players().stream()
                        .filter(player -> !player.serverName().isBlank())
                        .toList();
                output.writeInt(players.size());
                for (ProxyPlayer player : players) {
                    output.writeUTF(player.name());
                    output.writeUTF(player.serverName());
                }
            }
            adapter.broadcast(KaProxyProtocol.LEGACY_GUILDS_CHANNEL, bytes.toByteArray(), null);
        } catch (IOException error) {
            adapter.error(language.text("guild-presence-encode-failed"), error);
        }
    }
}
