package org.katacr.kaproxy.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class BackModuleTest {
    @TempDir Path directory;

    @Test
    void retriesUntilMatchingAcknowledgement() throws Exception {
        FakeAdapter adapter = new FakeAdapter();
        KaProxyConfig config = KaProxyConfig.load(directory, new ByteArrayInputStream(
                "modules:\n  back:\n    enabled: true\n".getBytes(StandardCharsets.UTF_8)));
        KaProxyLanguage language = KaProxyLanguage.load(directory, "zh_CN", path ->
                new ByteArrayInputStream("back-encode-failed: error\n".getBytes(StandardCharsets.UTF_8)));
        BackModule module = new BackModule(adapter, config, language);
        module.handle(adapter.player, "source", "back_request", payload(output -> {
            output.writeUTF("target"); output.writeUTF("warp"); output.writeUTF("plot");
            output.writeDouble(1); output.writeDouble(2); output.writeDouble(3);
            output.writeFloat(4); output.writeFloat(5); output.writeLong(6);
        }));
        module.playerConnected(adapter.player);
        assertEquals(1, adapter.player.packets.size());
        var first = KaProxyProtocol.decode(adapter.player.packets.get(0));
        assertEquals("back_arrival", first.action());
        UUID id = KaProxyProtocol.readUuid(first.input());
        assertEquals("warp", first.input().readUTF());
        adapter.tasks.get(1).run();
        assertEquals(2, adapter.player.packets.size());
        module.handle(adapter.player, "target", "back_arrival_complete", payload(output ->
                KaProxyProtocol.writeUuid(output, UUID.randomUUID())));
        adapter.tasks.get(adapter.tasks.size() - 1).run();
        assertEquals(3, adapter.player.packets.size());
        module.handle(adapter.player, "target", "back_arrival_complete", payload(output ->
                KaProxyProtocol.writeUuid(output, id)));
        adapter.tasks.get(adapter.tasks.size() - 1).run();
        assertEquals(3, adapter.player.packets.size());
    }

    private static DataInputStream payload(KaProxyProtocol.PacketWriter writer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) { writer.write(output); }
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static final class FakeAdapter implements ProxyAdapter {
        final FakePlayer player = new FakePlayer();
        final List<Runnable> tasks = new ArrayList<>();
        public Collection<? extends ProxyPlayer> players() { return List.of(player); }
        public Collection<String> servers() { return List.of("source", "target"); }
        public void pingServers(Consumer<Map<String, Boolean>> callback) { callback.accept(Map.of()); }
        public Optional<? extends ProxyPlayer> player(UUID id) { return player.uniqueId().equals(id) ? Optional.of(player) : Optional.empty(); }
        public void broadcast(String channel, byte[] data, String excludedServer) { }
        public void broadcastToServer(String channel, byte[] data, String server) { }
        public void schedule(Runnable task, long delayMillis) { tasks.add(task); }
        public void info(String message) { }
        public void error(String message, Throwable error) { throw new AssertionError(message, error); }
    }

    private static final class FakePlayer implements ProxyPlayer {
        final UUID id = UUID.randomUUID();
        final List<byte[]> packets = new ArrayList<>();
        String server = "source";
        public UUID uniqueId() { return id; }
        public String name() { return "Traveler"; }
        public String serverName() { return server; }
        public boolean sendToBackend(String channel, byte[] data) { packets.add(data); return true; }
        public void connect(String serverName, Consumer<Boolean> completion) { server = serverName; completion.accept(true); }
        public void disconnect(String reason) { server = ""; }
    }
}
