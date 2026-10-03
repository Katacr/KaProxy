package org.katacr.kaproxy.custom;

import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.core.ProxyAdapter;
import org.katacr.kaproxy.core.ProxyPlayer;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 第三方插件专用跨服消息模块。
 *
 * <p>后端提交 {@code custommessage/send} 请求（目标范围 + 频道 + 消息），
 * 代理按范围转发给其他后端：{@code all} 广播到所有后端（排除来源服），
 * 否则只发给指定子服列表。用于第三方插件之间的自定义跨服通讯。</p>
 */
public final class CustomMessageModule {
    private final ProxyAdapter adapter;
    private volatile KaProxyConfig config;
    private volatile KaProxyLanguage language;

    /** 创建绑定代理配置的第三方消息模块。 */
    public CustomMessageModule(ProxyAdapter adapter, KaProxyConfig config, KaProxyLanguage language) {
        this.adapter = adapter;
        this.config = config;
        this.language = language;
    }

    /** 热更新配置和语言。 */
    public void reload(KaProxyConfig config, KaProxyLanguage language) {
        this.config = config;
        this.language = language;
    }

    /** 模块热关闭时无状态可清理。 */
    public void disable() {
    }

    /**
     * 处理后端提交的第三方消息请求。
     *
     * 负载字段（顺序）：scope(UTF，all/* 表示全部)、channel(UTF)、payload(UTF，最大 64KB)。
     */
    public void handle(ProxyPlayer carrier, String sourceServer, String action,
                       DataInputStream input) throws IOException {
        if (!"send".equals(action)) {
            adapter.info(language.text("unknown-custommessage-action", Map.of("action", action)));
            return;
        }
        if (!carrier.serverName().equals(sourceServer)) {
            return;
        }

        String scope = input.readUTF();
        String channel = input.readUTF();
        String payload = input.readUTF();

        byte[] packet;
        try {
            packet = KaProxyProtocol.encode("custommessage", "receive", output -> {
                output.writeUTF(sourceServer);
                output.writeUTF(channel);
                output.writeUTF(payload);
            });
        } catch (IOException error) {
            adapter.error(language.text("custommessage-encode-failed"), error);
            return;
        }

        if (scope == null || scope.isBlank() || scope.equalsIgnoreCase("all") || scope.equals("*")) {
            adapter.broadcast(KaProxyProtocol.CHANNEL, packet, sourceServer);
            return;
        }

        List<String> targets = List.of(scope.split(","));
        for (String target : targets) {
            String trimmed = target.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(sourceServer)) {
                adapter.broadcastToServer(KaProxyProtocol.CHANNEL, packet, trimmed);
            }
        }
    }
}
