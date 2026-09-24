package org.katacr.kaproxy.kalogin;

import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.core.ProxyAdapter;
import org.katacr.kaproxy.core.ProxyPlayer;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * KaLogin 跨服登录会话模块。
 *
 * <p>代理持有权威的“已登录会话”：后端 KaLogin 在玩家登录/注册成功后上报 {@code auth}，
 * 玩家切服或重连时目标服发送 {@code query} 校验会话。玩家真正断开代理时清除会话，
 * {@code logout} 会同时使会话失效并把玩家断开整个群组。</p>
 *
 * <p>协议（module = {@code kalogin}）：
 * <ul>
 *   <li>后端 → 代理：{@code auth}(uuid,name,ip)、{@code logout}(uuid)、
 *       {@code unregister}(uuid,name)、{@code password_changed}(uuid)、
 *       {@code query}(uuid,name,ip)</li>
 *   <li>代理 → 来源后端：{@code session}(uuid,authenticated,name,authTime,loginIp,reason)</li>
 * </ul></p>
 */
public final class KaloginModule {
    private final ProxyAdapter adapter;
    private volatile KaProxyConfig config;
    private volatile KaProxyLanguage language;
    private final Consumer<UUID> sessionInvalidate;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** 创建 KaLogin 跨服登录会话模块。 */
    public KaloginModule(ProxyAdapter adapter, KaProxyConfig config, KaProxyLanguage language) {
        this(adapter, config, language, playerId -> { });
    }

    /** 创建 KaLogin 跨服登录会话模块，并在会话失效时回调（如清除上次位置记录）。 */
    public KaloginModule(ProxyAdapter adapter, KaProxyConfig config, KaProxyLanguage language,
                         Consumer<UUID> sessionInvalidate) {
        this.adapter = adapter;
        this.config = config;
        this.language = language;
        this.sessionInvalidate = sessionInvalidate == null ? playerId -> { } : sessionInvalidate;
    }

    /** 热更新配置与语言；模块被禁用时清空全部会话。 */
    public void reload(KaProxyConfig config, KaProxyLanguage language) {
        this.config = config;
        this.language = language;
        if (!config.kaloginEnabled()) {
            sessions.clear();
        }
    }

    /** 清理模块状态。 */
    public void disable() {
        sessions.clear();
    }

    /** 解码并处理后端会话请求。 */
    public void handle(ProxyPlayer carrier, String sourceServer, String action, DataInputStream input) {
        if (!config.kaloginEnabled()) {
            return;
        }
        try {
            switch (action) {
                case "auth" -> handleAuth(carrier, input);
                case "logout" -> handleLogout(carrier, input);
                case "unregister" -> handleUnregister(carrier, input);
                case "password_changed" -> handlePasswordChanged(carrier, input);
                case "query" -> handleQuery(carrier, sourceServer, input);
                default -> {
                    if (config.logUnknownModules()) {
                        adapter.info(language.text("unknown-kalogin-action", Map.of("action", action)));
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            adapter.error(language.text("kalogin-packet-failed", Map.of("server", sourceServer)), error);
        }
    }

    /** 登录/注册成功：建立或刷新会话，绑定当前登录 IP。 */
    private void handleAuth(ProxyPlayer carrier, DataInputStream input) throws IOException {
        UUID playerId = KaProxyProtocol.readUuid(input);
        String playerName = input.readUTF();
        String ip = input.readUTF();
        if (!matchesCarrier(carrier, playerId, "auth")) {
            return;
        }
        sessions.put(playerId, new Session(playerName, ip, System.currentTimeMillis()));
        debug(() -> "建立会话: player=" + playerName + ", server=" + carrier.serverName() + ", ip=" + ip);
    }

    /** 登出：使会话失效并从整个代理网络断开该玩家。 */
    private void handleLogout(ProxyPlayer carrier, DataInputStream input) throws IOException {
        UUID playerId = KaProxyProtocol.readUuid(input);
        if (!matchesCarrier(carrier, playerId, "logout")) {
            return;
        }
        boolean removed = sessions.remove(playerId) != null;
        sessionInvalidate.accept(playerId);
        debug(() -> "登出会话: player=" + carrier.name() + ", existed=" + removed);
        carrier.disconnect(language.text("kalogin-logout-reason"));
    }

    /** 注销账户：使会话失效（不主动断网，由后端继续引导重新注册）。 */
    private void handleUnregister(ProxyPlayer carrier, DataInputStream input) throws IOException {
        UUID playerId = KaProxyProtocol.readUuid(input);
        input.readUTF();
        sessions.remove(playerId);
        sessionInvalidate.accept(playerId);
        debug(() -> "注销会话: uuid=" + playerId);
    }

    /** 改密成功：刷新会话时间，保持当前玩家仍处于登录态。 */
    private void handlePasswordChanged(ProxyPlayer carrier, DataInputStream input) throws IOException {
        UUID playerId = KaProxyProtocol.readUuid(input);
        if (!matchesCarrier(carrier, playerId, "password_changed")) {
            return;
        }
        Session session = sessions.get(playerId);
        if (session != null) {
            sessions.put(playerId, new Session(session.name(), session.ip(), System.currentTimeMillis()));
        }
        debug(() -> "刷新会话: player=" + carrier.name());
    }

    /** 目标服进服时查询会话，命中则允许免登录恢复。 */
    private void handleQuery(ProxyPlayer carrier, String sourceServer, DataInputStream input) throws IOException {
        UUID playerId = KaProxyProtocol.readUuid(input);
        input.readUTF();
        String ip = input.readUTF();
        if (!matchesCarrier(carrier, playerId, "query")) {
            sendSessionReply(sourceServer, playerId, false, "", 0L, "", "carrier-mismatch");
            return;
        }
        Session session = sessions.get(playerId);
        boolean authenticated = session != null;
        String reason = authenticated ? "ok" : "none";
        if (authenticated && config.kaloginBindIp()
                && !session.ip().isEmpty() && !session.ip().equals(ip)) {
            authenticated = false;
            reason = "ip-mismatch";
        }
        sendSessionReply(sourceServer, playerId,
                authenticated,
                session == null ? "" : session.name(),
                session == null ? 0L : session.authTimeMillis(),
                session == null ? "" : session.ip(),
                reason);
        final boolean resultAuthenticated = authenticated;
        final String resultReason = reason;
        debug(() -> "查询会话: player=" + (session == null ? "?" : session.name())
                + ", server=" + sourceServer + ", authenticated=" + resultAuthenticated + ", reason=" + resultReason);
    }

    /** 向发起查询的后端返回会话状态。 */
    private void sendSessionReply(String sourceServer, UUID playerId, boolean authenticated,
                                  String playerName, long authTimeMillis, String loginIp, String reason) {
        if (sourceServer == null || sourceServer.isBlank()) {
            return;
        }
        try {
            byte[] packet = KaProxyProtocol.encode("kalogin", "session", output -> {
                KaProxyProtocol.writeUuid(output, playerId);
                output.writeBoolean(authenticated);
                output.writeUTF(playerName == null ? "" : playerName);
                output.writeLong(authTimeMillis);
                output.writeUTF(loginIp == null ? "" : loginIp);
                output.writeUTF(reason == null ? "" : reason);
            });
            adapter.broadcastToServer(KaProxyProtocol.CHANNEL, packet, sourceServer);
        } catch (IOException error) {
            adapter.error(language.text("kalogin-reply-encode-failed"), error);
        }
    }

    /** 玩家真正离开代理（DisconnectEvent / PlayerDisconnectEvent）时销毁会话。 */
    public void playerDisconnected(ProxyPlayer player) {
        if (!config.kaloginEnabled()) {
            return;
        }
        Session removed = sessions.remove(player.uniqueId());
        if (removed != null) {
            debug(() -> "玩家离网销毁会话: player=" + removed.name());
        }
    }

    /** 校验上报/查询的载体确实是该玩家；unregister 由管理员代发，不在此列。 */
    private boolean matchesCarrier(ProxyPlayer carrier, UUID playerId, String action) {
        if (carrier == null) {
            debug(() -> "忽略缺少载体的会话动作: " + action);
            return false;
        }
        if (!carrier.uniqueId().equals(playerId)) {
            debug(() -> "忽略载体不匹配的会话动作: action=" + action
                    + ", carrier=" + carrier.name() + ", target=" + playerId);
            return false;
        }
        return true;
    }

    private void debug(java.util.function.Supplier<String> message) {
        if (config.kaloginDebug()) {
            adapter.info("[KaProxy/kalogin debug] " + message.get());
        }
    }

    /** 已登录会话：名称、登录 IP 与会话建立时间。 */
    private record Session(String name, String ip, long authTimeMillis) {
    }
}
