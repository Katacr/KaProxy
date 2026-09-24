package org.katacr.kaproxy.lastseen;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证“上次下线位置”模块：直连推荐、ready 前往、真离开写服、黑名单清除。 */
final class LastSeenModuleTest {
    @TempDir
    Path temporaryDirectory;

    private LastSeenModule module;
    private FakeAdapter adapter;
    private FakeRepository repository;
    private KaProxyConfig config;
    private KaProxyLanguage language;

    @BeforeEach
    void setUp() throws Exception {
        config = configWithBlacklist("");
        language = KaProxyLanguage.load(temporaryDirectory, "zh_CN",
                path -> new ByteArrayInputStream(("unknown-lastseen-action: unknown\n"
                        + "lastseen-packet-failed: failed\n"
                        + "lastseen-load-failed: failed\n"
                        + "lastseen-save-failed: failed\n").getBytes(StandardCharsets.UTF_8)));
        adapter = new FakeAdapter();
        repository = new FakeRepository();
        module = new LastSeenModule(adapter, repository, config, language);
    }

    @Test
    void readyTravelsToRecordedServerAndSendsTeleport() throws Exception {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "lobby");
        repository.put(playerId, new LastSeenRepository.Record("survival", "world", 10, 64, 20, 90f, 0f));
        module.handle(carrier, "lobby", "ready", decode(uuidPayload(playerId)));
        assertEquals("survival", carrier.connectedTo);
        assertEquals(List.of("teleport"), adapter.actions());
    }

    @Test
    void readyWithoutRecordStaysPut() throws Exception {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "lobby");
        module.handle(carrier, "lobby", "ready", decode(uuidPayload(playerId)));
        assertNull(carrier.connectedTo);
        assertEquals(List.of(), adapter.actions());
    }

    @Test
    void readyOnlyTravelsOncePerSession() throws Exception {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "lobby");
        repository.put(playerId, new LastSeenRepository.Record("survival", "world", 10, 64, 20, 90f, 0f));
        module.handle(carrier, "lobby", "ready", decode(uuidPayload(playerId)));
        carrier.connectedTo = null;
        adapter.clear();
        module.handle(carrier, "lobby", "ready", decode(uuidPayload(playerId)));
        assertNull(carrier.connectedTo);
        assertEquals(List.of(), adapter.actions());
    }

    @Test
    void playerDisconnectedAllowsNextSessionTravel() throws Exception {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "lobby");
        repository.put(playerId, new LastSeenRepository.Record("survival", "world", 10, 64, 20, 90f, 0f));
        module.handle(carrier, "lobby", "ready", decode(uuidPayload(playerId)));
        module.playerDisconnected(carrier);
        carrier.connectedTo = null;
        carrier.server = "lobby";
        module.handle(carrier, "lobby", "ready", decode(uuidPayload(playerId)));
        assertEquals("survival", carrier.connectedTo);
    }

    @Test
    void clearRemovesRecordAfterLogout() throws Exception {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "lobby");
        repository.put(playerId, new LastSeenRepository.Record("survival", "world", 10, 64, 20, 90f, 0f));
        module.clear(playerId);
        module.handle(carrier, "lobby", "ready", decode(uuidPayload(playerId)));
        assertNull(carrier.connectedTo);
        assertTrue(repository.deleted.contains(playerId));
    }

    @Test
    void abortFallsBackToDefaultServer() throws Exception {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "survival");
        module.handle(carrier, "survival", "abort", decode(uuidPayload(playerId)));
        assertEquals("lobby", carrier.connectedTo);
    }

    @Test
    void initialServerForReturnsRecordedServer() throws Exception {
        UUID playerId = UUID.randomUUID();
        repository.put(playerId, new LastSeenRepository.Record("survival", "world", 10, 64, 20, 90f, 0f));
        assertEquals("survival", module.initialServerFor(playerId).get().orElse(null));
    }

    @Test
    void initialServerForEmptyWhenBlacklisted() throws Exception {
        LastSeenModule blacklisted = new LastSeenModule(adapter, repository,
                configWithBlacklist("survival"), language);
        UUID playerId = UUID.randomUUID();
        repository.put(playerId, new LastSeenRepository.Record("survival", "world", 10, 64, 20, 90f, 0f));
        assertTrue(blacklisted.initialServerFor(playerId).get().isEmpty());
    }

    @Test
    void realDisconnectWritesLastServer() throws Exception {
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "survival");
        module.playerConnected(carrier);
        module.playerDisconnected(carrier);
        assertEquals("survival", repository.serverFor(playerId));
    }

    @Test
    void leavingBlacklistedServerDeletesRecord() throws Exception {
        LastSeenModule blacklisted = new LastSeenModule(adapter, repository,
                configWithBlacklist("pve,pvp"), language);
        UUID playerId = UUID.randomUUID();
        FakePlayer carrier = new FakePlayer(playerId, "Alex", "pve");
        repository.put(playerId, new LastSeenRepository.Record("survival", "world", 10, 64, 20, 90f, 0f));
        blacklisted.playerConnected(carrier);
        blacklisted.playerDisconnected(carrier);
        assertTrue(repository.deleted.contains(playerId));
    }

    @Test
    void blacklistMatchIsCaseInsensitive() throws Exception {
        LastSeenModule blacklisted = new LastSeenModule(adapter, repository,
                configWithBlacklist("pve"), language);
        UUID playerId = UUID.randomUUID();
        repository.put(playerId, new LastSeenRepository.Record("PVE", "world", 10, 64, 20, 90f, 0f));
        assertTrue(blacklisted.initialServerFor(playerId).get().isEmpty());
    }

    private KaProxyConfig configWithBlacklist(String blacklist) throws Exception {
        Path directory = temporaryDirectory.resolve("bl-" + (blacklist.isEmpty() ? "none" : blacklist.replace(',', '_')));
        return KaProxyConfig.load(directory,
                new ByteArrayInputStream(("modules:\n  lastseen:\n    enabled: true\n    default-server: lobby\n"
                        + "    blacklist: " + blacklist + "\n").getBytes(StandardCharsets.UTF_8)));
    }

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

    /** 内存实现，模拟共享表：load 返回坐标记录，saveServer 写 server 列。 */
    private static final class FakeRepository implements LastSeenRepository {
        private final Map<UUID, Record> records = new ConcurrentHashMap<>();
        private final List<UUID> deleted = new ArrayList<>();

        private void put(UUID playerId, Record record) {
            records.put(playerId, record);
        }

        private String serverFor(UUID playerId) {
            Record record = records.get(playerId);
            return record == null ? null : record.server();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Optional<Record>> load(UUID playerId) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.ofNullable(records.get(playerId)));
        }

        @Override
        public void saveServer(UUID playerId, String server) {
            Record existing = records.get(playerId);
            if (existing == null) {
                records.put(playerId, new Record(server, "", 0, 0, 0, 0f, 0f));
            } else {
                records.put(playerId, new Record(server, existing.world(), existing.x(), existing.y(),
                        existing.z(), existing.yaw(), existing.pitch()));
            }
        }

        @Override
        public void delete(UUID playerId) {
            records.remove(playerId);
            deleted.add(playerId);
        }
    }

    private static final class FakeAdapter implements ProxyAdapter {
        private final List<byte[]> packets = new ArrayList<>();

        private void clear() {
            packets.clear();
        }

        private List<String> actions() throws IOException {
            List<String> actions = new ArrayList<>();
            for (byte[] packet : packets) {
                actions.add(KaProxyProtocol.decode(packet).action());
            }
            return actions;
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
        private String server;
        private String connectedTo;

        private FakePlayer(UUID id, String name, String server) {
            this.id = id;
            this.name = name;
            this.server = server;
        }

        @Override public UUID uniqueId() { return id; }
        @Override public String name() { return name; }
        @Override public String serverName() { return server; }
        @Override public boolean sendToBackend(String channel, byte[] data) { return true; }
        @Override public void connect(String serverName, Consumer<Boolean> completion) {
            connectedTo = serverName;
            server = serverName;
            completion.accept(true);
        }
        @Override public void disconnect(String reason) { server = ""; }
    }
}
