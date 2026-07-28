package org.katacr.kaproxy.core;

import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.ByteArrayOutputStream;
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

    /** 创建绑定具体代理平台的 KaProxy 核心。 */
    public KaProxyCore(ProxyAdapter adapter, KaProxyConfig config, KaProxyLanguage language) {
        this.adapter = adapter;
        this.config = config;
        this.language = language;
        this.tpaModule = new TpaModule(adapter, config, language);
    }

    /** 热更新模块参数和语言文本，不丢弃正在进行的事务。 */
    public void reload(KaProxyConfig config, KaProxyLanguage language) {
        boolean disableTpa = this.config.tpaEnabled() && !config.tpaEnabled();
        this.config = config;
        this.language = language;
        this.tpaModule.reload(config, language);
        if (disableTpa) {
            this.tpaModule.disable();
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
        playerTopologyChanged();
    }

    /** 玩家连接目标子服后尝试交付等待中的一次性到达凭证。 */
    public void playerConnected(ProxyPlayer player) {
        playerTopologyChanged();
        if (config.tpaEnabled()) {
            tpaModule.playerConnected(player);
        }
    }

    /** 保持 KaGuilds 现有 kaguilds:chat 广播语义，迁移时无需修改后端插件。 */
    public void handleLegacyGuildMessage(String sourceServer, byte[] data) {
        if (config.guildsLegacyChannelEnabled()) {
            adapter.broadcast(KaProxyProtocol.LEGACY_GUILDS_CHANNEL, data, sourceServer);
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

    /** 向所有当前有玩家连接的后端广播全服在线玩家快照。 */
    private void broadcastPresence() {
        try {
            byte[] packet = presencePacket();
            adapter.broadcast(KaProxyProtocol.CHANNEL, packet, null);
        } catch (IOException error) {
            adapter.error(language.text("presence-encode-failed"), error);
        }
    }

    /** 向指定玩家所在后端返回全服在线玩家快照。 */
    private void sendPresence(ProxyPlayer carrier) {
        try {
            carrier.sendToBackend(KaProxyProtocol.CHANNEL, presencePacket());
        } catch (IOException error) {
            adapter.error(language.text("presence-encode-failed"), error);
        }
    }

    /** 构建统一协议的在线玩家快照。 */
    private byte[] presencePacket() throws IOException {
        var players = adapter.players().stream()
                .filter(player -> !player.serverName().isBlank())
                .sorted(Comparator.comparing(ProxyPlayer::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return KaProxyProtocol.encode("core", "presence", output -> {
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
