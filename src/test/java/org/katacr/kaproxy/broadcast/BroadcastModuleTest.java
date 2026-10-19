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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证连接事件仅由代理生命周期确认，后端包只提供渲染载荷。 */
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
    void quitPrepareWaitsForProxyDisconnectWhilePlayerRemainsOnline() throws Exception {
        FakePlayer player = online("Alex", "lobby");
        module.playerConnected(player, null, true);
        module.handle(null, "lobby", "quit_prepare", decode(buildQuitPayload(UUID.randomUUID(), player.uniqueId(), "Alex", "lobby")));
        assertEquals(List.of(), adapter.actions());

        adapter.runScheduledTasks();
        assertEquals(List.of(), adapter.actions());

        adapter.setOffline(player);
        module.playerDisconnected(player);
        assertEquals(List.of("player_quit"), adapter.actions());
    }

    @Test
    void switchDuringPendingQuitCancelsQuitForwarding() throws Exception {
        FakePlayer player = online("Alex", "lobby");
        module.playerConnected(player, null, true);
        module.handle(null, "lobby", "quit_prepare", decode(buildQuitPayload(UUID.randomUUID(), player.uniqueId(), "Alex", "lobby")));

        player.server = "survival";
        module.playerConnected(player, "lobby", true);
        module.handle(null, "survival", "join_prepare", decode(buildJoinPayload(UUID.randomUUID(), player.uniqueId(), "Alex", "survival")));
        adapter.runScheduledTasks();
        assertEquals(List.of("switch_request"), adapter.actions());
    }

    @Test
    void lateQuitPrepareFromOldServerDoesNotRemoveNewTopology() throws Exception {
        FakePlayer player = online("Alex", "lobby");
        module.playerConnected(player, null, true);
        player.server = "survival";
        module.playerConnected(player, "lobby", true);
        module.handle(null, "survival", "join_prepare", decode(buildJoinPayload(UUID.randomUUID(), player.uniqueId(), "Alex", "survival")));
        adapter.clear();

        module.handle(null, "lobby", "quit_prepare", decode(buildQuitPayload(UUID.randomUUID(), player.uniqueId(), "Alex", "lobby")));
        adapter.runScheduledTasks();
        assertEquals(List.of(), adapter.actions());
    }

    @Test
    void lateQuitPrepareAfterDisconnectIsForwardedWhenTopologyStillExists() throws Exception {
        FakePlayer player = online("Alex", "lobby");
        module.playerConnected(player, null, true);
        adapter.setOffline(player);
        module.playerDisconnected(player);

        module.handle(null, "lobby", "quit_prepare", decode(buildQuitPayload(UUID.randomUUID(), player.uniqueId(), "Alex", "lobby")));
        assertEquals(List.of("player_quit"), adapter.actions());
    }

    @Test
    void quitSnapshotIsForwardedWhenLastPlayerDisconnectsWithoutQuitPrepare() throws Exception {
        FakePlayer player = online("Alex", "lobby");
        module.playerConnected(player, null, true);
        module.handle(null, "lobby", "quit_snapshot", decode(buildQuitSnapshotPayload(player.uniqueId(), "Alex", "lobby")));
        assertEquals(List.of(), adapter.actions());

        adapter.setOffline(player);
        module.playerDisconnected(player);
        assertEquals(List.of("player_quit"), adapter.actions());
    }

    @Test
    void disconnectWhileSessionStillOnlineIsIgnoredAsPhantomQuit() throws Exception {
        FakePlayer player = online("Alex", "lobby");
        module.playerConnected(player, null, true);
        module.handle(null, "lobby", "quit_snapshot", decode(buildQuitSnapshotPayload(player.uniqueId(), "Alex", "lobby")));
        adapter.clear();

        module.playerDisconnected(player);
        assertEquals(List.of(), adapter.actions());
    }

    @Test
    void disconnectAfterSessionLeavesProxyForwardsQuit() throws Exception {
        FakePlayer player = online("Alex", "lobby");
        module.playerConnected(player, null, true);
        module.handle(null, "lobby", "quit_snapshot", decode(buildQuitSnapshotPayload(player.uniqueId(), "Alex", "lobby")));
        adapter.clear();

        adapter.setOffline(player);
        module.playerDisconnected(player);
        assertEquals(List.of("player_quit"), adapter.actions());
    }

    @Test
    void bungeeReconnectAfterDisconnectIsNewJoinInsteadOfSwitch() throws Exception {
        UUID playerId = UUID.randomUUID();
        FakePlayer oldSession = online(playerId, "Alex", "lobby");
        module.playerConnected(oldSession, null, false);
        adapter.setOffline(oldSession);
        module.playerDisconnected(oldSession);

        FakePlayer newSession = online(playerId, "Alex", "survival");
        module.playerConnected(newSession, null, false);
        module.handle(null, "survival", "join_prepare", decode(buildJoinPayload(UUID.randomUUID(), playerId, "Alex", "survival")));
        assertEquals(List.of("player_join"), adapter.actions());
    }

    private FakePlayer online(String name, String server) {
        return online(UUID.randomUUID(), name, server);
    }

    private FakePlayer online(UUID id, String name, String server) {
        FakePlayer player = new FakePlayer(id, name, server);
        adapter.setOnline(player);
        return player;
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

    /** 构建 quit_snapshot 的内层载荷。 */
    private byte[] buildQuitSnapshotPayload(UUID playerId, String name, String server) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
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
        private final Map<UUID, ProxyPlayer> onlinePlayers = new HashMap<>();

        private List<String> actions() throws IOException {
            List<String> actions = new ArrayList<>();
            for (byte[] packet : packets) {
                actions.add(KaProxyProtocol.decode(packet).action());
            }
            return actions;
        }

        private void clear() { packets.clear(); }
        private void setOnline(FakePlayer player) { onlinePlayers.put(player.uniqueId(), player); }
        private void setOffline(FakePlayer player) { onlinePlayers.remove(player.uniqueId()); }

        private void runScheduledTasks() {
            List<Runnable> tasks = new ArrayList<>(scheduledTasks);
            scheduledTasks.clear();
            tasks.forEach(Runnable::run);
        }

        @Override public Collection<? extends ProxyPlayer> players() { return List.copyOf(onlinePlayers.values()); }
        @Override public Collection<String> servers() { return List.of(); }
        @Override public void pingServers(Consumer<Map<String, Boolean>> callback) { callback.accept(Map.of()); }
        @Override public Optional<? extends ProxyPlayer> player(UUID playerId) { return Optional.ofNullable(onlinePlayers.get(playerId)); }
        @Override public void broadcast(String channel, byte[] data, String excludedServer) { packets.add(data); }
        @Override public void broadcastToServer(String channel, byte[] data, String targetServer) { packets.add(data); }
        @Override public void schedule(Runnable task, long delayMillis) { scheduledTasks.add(task); }
        @Override public void info(String message) {}
        @Override public void error(String message, Throwable error) { throw new AssertionError(message, error); }
    }

    private static final class FakePlayer implements ProxyPlayer {
        private final UUID id;
        private final String name;
        private String server;

        private FakePlayer(UUID id, String name, String server) {
            this.id = id;
            this.name = name;
            this.server = server;
        }

        @Override public UUID uniqueId() { return id; }
        @Override public String name() { return name; }
        @Override public String serverName() { return server; }
        @Override public boolean sendToBackend(String channel, byte[] data) { return true; }
        @Override public void connect(String serverName, Consumer<Boolean> completion) { server = serverName; completion.accept(true); }
        @Override public void disconnect(String reason) { server = ""; }
    }
}
