package org.katacr.kaproxy.broadcast;

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

/** 验证群组连接事件只在代理拓扑变化时转发后端预渲染数据。 */
final class BroadcastModuleTest {
    @TempDir
    Path temporaryDirectory;

    private BroadcastModule module;
    private FakeAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        KaProxyConfig config = KaProxyConfig.load(temporaryDirectory,
                new ByteArrayInputStream(("modules:\n  broadcast:\n    enabled: true\n    servers:\n      - all\n").getBytes(StandardCharsets.UTF_8)));
        KaProxyLanguage language = KaProxyLanguage.load(temporaryDirectory, "zh_CN",
                path -> new ByteArrayInputStream(("unknown-broadcast-action: unknown\n"
                        + "broadcast-packet-failed: failed\n").getBytes(StandardCharsets.UTF_8)));
        adapter = new FakeAdapter();
        module = new BroadcastModule(adapter, config, language);
        module.synchronizePlayers(List.of());
    }

    @Test
    void joinPrepareForwardedOnFirstJoin() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        module.handle(null, "lobby", "join_prepare", decode(buildJoinPayload(eventId, playerId, "Alex", "lobby")));
        assertEquals(List.of("player_join"), adapter.actions());
    }

    @Test
    void duplicateJoinPrepareIsDeduplicated() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        module.handle(null, "lobby", "join_prepare", decode(buildJoinPayload(eventId, playerId, "Alex", "lobby")));
        adapter.clear();
        module.handle(null, "lobby", "join_prepare", decode(buildJoinPayload(eventId, playerId, "Alex", "lobby")));
        assertEquals(List.of(), adapter.actions());
    }

    @Test
    void serverSwitchSendsSwitchRequestInsteadOfJoin() throws Exception {
        UUID joinEventId = UUID.randomUUID();
        UUID switchEventId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        module.handle(null, "lobby", "join_prepare", decode(buildJoinPayload(joinEventId, playerId, "Alex", "lobby")));
        adapter.clear();
        module.handle(null, "survival", "join_prepare", decode(buildJoinPayload(switchEventId, playerId, "Alex", "survival")));
        assertEquals(List.of("switch_request"), adapter.actions());
    }

    @Test
    void quitPrepareIsDeferredAndForwardedWithoutJoin() throws Exception {
        UUID joinEventId = UUID.randomUUID();
        UUID quitEventId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        module.handle(null, "lobby", "join_prepare", decode(buildJoinPayload(joinEventId, playerId, "Alex", "lobby")));
        adapter.clear();
        module.handle(null, "lobby", "quit_prepare", decode(buildQuitPayload(quitEventId, playerId, "Alex", "lobby")));
        assertEquals(List.of(), adapter.actions());
        adapter.runScheduledTasks();
        assertEquals(List.of("player_quit"), adapter.actions());
    }

    @Test
    void switchDuringQuitGraceCancelsQuitForwarding() throws Exception {
        UUID joinEventId = UUID.randomUUID();
        UUID switchEventId = UUID.randomUUID();
        UUID quitEventId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        module.handle(null, "lobby", "join_prepare", decode(buildJoinPayload(joinEventId, playerId, "Alex", "lobby")));
        adapter.clear();
        module.handle(null, "lobby", "quit_prepare", decode(buildQuitPayload(quitEventId, playerId, "Alex", "lobby")));
        // 目标服 join_prepare 在观察期内到达：取消退出转发并触发 switch_request。
        module.handle(null, "survival", "join_prepare", decode(buildJoinPayload(UUID.randomUUID(), playerId, "Alex", "survival")));
        adapter.runScheduledTasks();
        List<String> actions = adapter.actions();
        assertEquals(1, actions.size());
        assertEquals("switch_request", actions.get(0));
    }

    private DataInputStream decode(byte[] packet) {
        return new DataInputStream(new ByteArrayInputStream(packet));
    }

    /** 构建 join_prepare 的内层载荷（eventId + playerId + ...）。 */
    private byte[] buildJoinPayload(UUID eventId, UUID playerId, String name, String server) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            KaProxyProtocol.writeUuid(out, eventId);
            KaProxyProtocol.writeUuid(out, playerId);
            out.writeUTF(name);
            out.writeUTF(server);
            out.writeUTF("&e" + name + " joined");
            out.writeUTF("chat");
            out.writeInt(10); out.writeInt(60); out.writeInt(20);
            out.writeUTF("RED"); out.writeUTF("SOLID"); out.writeFloat(1.0f); out.writeInt(100);
        }
        return buf.toByteArray();
    }

    /** 构建 quit_prepare 的内层载荷。 */
    private byte[] buildQuitPayload(UUID eventId, UUID playerId, String name, String server) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            KaProxyProtocol.writeUuid(out, eventId);
            KaProxyProtocol.writeUuid(out, playerId);
            out.writeUTF(name);
            out.writeUTF(server);
            out.writeUTF("&e" + name + " left");
            out.writeUTF("chat");
            out.writeInt(10); out.writeInt(60); out.writeInt(20);
            out.writeUTF("RED"); out.writeUTF("SOLID"); out.writeFloat(1.0f); out.writeInt(100);
        }
        return buf.toByteArray();
    }

    private static final class FakeAdapter implements ProxyAdapter {
        private final List<byte[]> packets = new ArrayList<>();
        private final List<Runnable> scheduledTasks = new ArrayList<>();

        private List<String> actions() throws IOException {
            List<String> actions = new ArrayList<>();
            for (byte[] packet : packets) {
                actions.add(KaProxyProtocol.decode(packet).action());
            }
            return actions;
        }

        private void clear() { packets.clear(); }

        private void runScheduledTasks() {
            List<Runnable> tasks = new ArrayList<>(scheduledTasks);
            scheduledTasks.clear();
            tasks.forEach(Runnable::run);
        }

        @Override public Collection<? extends ProxyPlayer> players() { return List.of(); }
        @Override public Collection<String> servers() { return List.of(); }
        @Override public void pingServers(Consumer<Map<String, Boolean>> callback) { callback.accept(Map.of()); }
        @Override public Optional<? extends ProxyPlayer> player(UUID playerId) { return Optional.empty(); }
        @Override public void broadcast(String channel, byte[] data, String excludedServer) { packets.add(data); }
        @Override public void broadcastToServer(String channel, byte[] data, String targetServer) { packets.add(data); }
        @Override public void schedule(Runnable task, long delayMillis) { scheduledTasks.add(task); }
        @Override public void info(String message) {}
        @Override public void error(String message, Throwable error) { throw new AssertionError(message, error); }
    }

    private static final class FakePlayer implements ProxyPlayer {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private String server;

        private FakePlayer(String name, String server) { this.name = name; this.server = server; }
        @Override public UUID uniqueId() { return id; }
        @Override public String name() { return name; }
        @Override public String serverName() { return server; }
        @Override public boolean sendToBackend(String channel, byte[] data) { return true; }
        @Override public void connect(String serverName, Consumer<Boolean> completion) { server = serverName; completion.accept(true); }
        @Override public void disconnect(String reason) { server = ""; }
    }
}
