package org.katacr.kaproxy.kalogin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.core.ProxyAdapter;
import org.katacr.kaproxy.core.ProxyPlayer;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 KaLogin 跨服会话的建立、查询、IP 绑定与失效。 */
final class KaloginModuleTest {
    @TempDir
    Path temporaryDirectory;

    private KaloginModule module;
    private FakeAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        KaProxyConfig config = KaProxyConfig.load(temporaryDirectory,
                new ByteArrayInputStream(("modules:\n  kalogin:\n    enabled: true\n    bind-ip: true\n")
                        .getBytes(StandardCharsets.UTF_8)));
        KaProxyLanguage language = KaProxyLanguage.load(temporaryDirectory, "zh_CN",
                path -> new ByteArrayInputStream(("unknown-kalogin-action: unknown\n"
                        + "kalogin-packet-failed: failed\n"
                        + "kalogin-reply-encode-failed: failed\n"
                        + "kalogin-logout-reason: logged out\n").getBytes(StandardCharsets.UTF_8)));
        adapter = new FakeAdapter();
        module = new KaloginModule(adapter, config, language);
    }

    @Test
    void queryWithoutSessionIsNotAuthenticated() throws IOException {
        FakePlayer carrier = new FakePlayer(UUID.randomUUID(), "Alex", "lobby");
        module.handle(carrier, "lobby", "query", decode(identityPayload(carrier, "1.2.3.4")));
        SessionReply reply = adapter.sessionReply();
        assertFalse(reply.authenticated());
        assertEquals("none", reply.reason());
    }

    @Test
    void authThenQueryRestoresSessionOnOtherServer() throws IOException {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "lobby");
        module.handle(carrier, "lobby", "auth", decode(identityPayload(carrier, "1.2.3.4")));
        adapter.clear();
        module.handle(carrier, "survival", "query", decode(identityPayload(carrier, "1.2.3.4")));
        SessionReply reply = adapter.sessionReply();
        assertTrue(reply.authenticated());
        assertEquals("Alex", reply.name());
        assertEquals("ok", reply.reason());
    }

    @Test
    void ipMismatchRejectsRestore() throws IOException {
        FakePlayer carrier = new FakePlayer(UUID.randomUUID(), "Alex", "lobby");
        module.handle(carrier, "lobby", "auth", decode(identityPayload(carrier, "1.2.3.4")));
        adapter.clear();
        module.handle(carrier, "survival", "query", decode(identityPayload(carrier, "5.6.7.8")));
        SessionReply reply = adapter.sessionReply();
        assertFalse(reply.authenticated());
        assertEquals("ip-mismatch", reply.reason());
    }

    @Test
    void logoutRemovesSessionAndDisconnectsPlayer() throws IOException {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "lobby");
        module.handle(carrier, "lobby", "auth", decode(identityPayload(carrier, "1.2.3.4")));
        module.handle(carrier, "lobby", "logout", decode(uuidPayload(playerId)));
        assertTrue(carrier.disconnected);
        adapter.clear();
        module.handle(carrier, "survival", "query", decode(identityPayload(carrier, "1.2.3.4")));
        assertFalse(adapter.sessionReply().authenticated());
    }

    @Test
    void playerDisconnectedDropsSession() throws IOException {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "lobby");
        module.handle(carrier, "lobby", "auth", decode(identityPayload(carrier, "1.2.3.4")));
        module.playerDisconnected(carrier);
        adapter.clear();
        module.handle(carrier, "lobby", "query", decode(identityPayload(carrier, "1.2.3.4")));
        assertFalse(adapter.sessionReply().authenticated());
    }

    @Test
    void authWithMismatchedCarrierIsIgnored() throws IOException {
        FakePlayer carrier = new FakePlayer(UUID.randomUUID(), "Alex", "lobby");
        UUID otherId = UUID.randomUUID();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            KaProxyProtocol.writeUuid(out, otherId);
            out.writeUTF("Alex");
            out.writeUTF("1.2.3.4");
        }
        module.handle(carrier, "lobby", "auth", decode(buf.toByteArray()));
        adapter.clear();
        module.handle(carrier, "lobby", "query", decode(identityPayload(carrier, "1.2.3.4")));
        assertFalse(adapter.sessionReply().authenticated());
    }

    /** 构建 auth/query 的 uuid + name + ip 载荷。 */
    private byte[] identityPayload(FakePlayer player, String ip) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            KaProxyProtocol.writeUuid(out, player.uniqueId());
            out.writeUTF(player.name());
            out.writeUTF(ip);
        }
        return buf.toByteArray();
    }

    /** 构建仅含 uuid 的载荷。 */
    private byte[] uuidPayload(UUID playerId) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            KaProxyProtocol.writeUuid(out, playerId);
        }
        return buf.toByteArray();
    }

    private DataInputStream decode(byte[] packet) {
        return new DataInputStream(new ByteArrayInputStream(packet));
    }

    /** 已解码的 session 响应。 */
    private record SessionReply(UUID playerId, boolean authenticated, String name, String reason) {
    }

    private static final class FakeAdapter implements ProxyAdapter {
        private final List<byte[]> packets = new ArrayList<>();

        private void clear() {
            packets.clear();
        }

        private SessionReply sessionReply() throws IOException {
            if (packets.isEmpty()) {
                throw new AssertionError("未收到 session 响应");
            }
            KaProxyProtocol.Packet packet = KaProxyProtocol.decode(packets.get(packets.size() - 1));
            assertEquals("kalogin", packet.module());
            assertEquals("session", packet.action());
            UUID playerId = KaProxyProtocol.readUuid(packet.input());
            boolean authenticated = packet.input().readBoolean();
            String name = packet.input().readUTF();
            packet.input().readLong();
            packet.input().readUTF();
            String reason = packet.input().readUTF();
            return new SessionReply(playerId, authenticated, name, reason);
        }

        @Override public Collection<? extends ProxyPlayer> players() { return List.of(); }
        @Override public Collection<String> servers() { return List.of(); }
        @Override public void pingServers(Consumer<Map<String, Boolean>> callback) { callback.accept(Map.of()); }
        @Override public Optional<? extends ProxyPlayer> player(UUID playerId) { return Optional.empty(); }
        @Override public void broadcast(String channel, byte[] data, String excludedServer) { packets.add(data); }
        @Override public void broadcastToServer(String channel, byte[] data, String targetServer) { packets.add(data); }
        @Override public void schedule(Runnable task, long delayMillis) { }
        @Override public void info(String message) { }
        @Override public void error(String message, Throwable error) { throw new AssertionError(message, error); }
    }

    private static final class FakePlayer implements ProxyPlayer {
        private final UUID id;
        private final String name;
        private final String server;
        private boolean disconnected;

        private FakePlayer(UUID id, String name, String server) {
            this.id = id;
            this.name = name;
            this.server = server;
        }

        @Override public UUID uniqueId() { return id; }
        @Override public String name() { return name; }
        @Override public String serverName() { return server; }
        @Override public boolean sendToBackend(String channel, byte[] data) { return true; }
        @Override public void connect(String serverName, Consumer<Boolean> completion) { completion.accept(true); }
        @Override public void disconnect(String reason) { disconnected = true; }
    }
}
