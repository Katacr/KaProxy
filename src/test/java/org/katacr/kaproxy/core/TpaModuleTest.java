package org.katacr.kaproxy.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证代理端跨服请求的创建、接受、切服和一次性到达流程。 */
final class TpaModuleTest {
    @TempDir
    Path temporaryDirectory;

    private FakeAdapter adapter;
    private FakePlayer sender;
    private FakePlayer receiver;
    private TpaModule module;

    /** 创建两个位于不同子服的测试玩家和内存代理。 */
    @BeforeEach
    void setUp() throws Exception {
        adapter = new FakeAdapter();
        sender = adapter.add("Sender", "server-a");
        receiver = adapter.add("Receiver", "server-b");
        String configText = """
                modules:
                  tpa:
                    enabled: true
                    request-timeout-min-seconds: 5
                    request-timeout-max-seconds: 120
                    transaction-timeout-seconds: 60
                    cooldown-max-seconds: 3600
                    follow-target-server: true
                """;
        KaProxyConfig config = KaProxyConfig.load(temporaryDirectory,
                new ByteArrayInputStream(configText.getBytes(StandardCharsets.UTF_8)));
        String languageText = "tpa-event-encode-failed: error\ntpa-operation-failed-encode: error\n";
        KaProxyLanguage language = KaProxyLanguage.load(temporaryDirectory, "zh_CN", path ->
                new ByteArrayInputStream(languageText.getBytes(StandardCharsets.UTF_8)));
        module = new TpaModule(adapter, config, language);
    }

    /** 完整流程应把旅行者切到目标服并只交付一次到达事件。 */
    @Test
    void completesCrossServerArrivalFlow() throws Exception {
        UUID requestId = UUID.randomUUID();
        module.handle(sender, sender.serverName(), "request_create", payload(output -> {
            KaProxyProtocol.writeUuid(output, requestId);
            KaProxyProtocol.writeUuid(output, receiver.uniqueId());
            output.writeUTF("TPA");
            output.writeInt(30);
            output.writeInt(30);
        }));

        assertTrue(sender.actions.contains("request_created"));
        assertTrue(receiver.actions.contains("request_incoming"));

        module.handle(receiver, receiver.serverName(), "request_accept", payload(output -> {
            KaProxyProtocol.writeUuid(output, requestId);
            output.writeBoolean(false);
        }));
        assertTrue(sender.actions.contains("request_accepted"));
        assertTrue(receiver.actions.contains("request_accepted"));

        module.handle(sender, sender.serverName(), "warmup_complete", payload(output ->
                KaProxyProtocol.writeUuid(output, requestId)));
        module.playerConnected(sender);

        assertEquals("server-b", sender.serverName());
        assertEquals(1, sender.actions.stream().filter("arrival"::equals).count());

        module.handle(sender, sender.serverName(), "arrival_complete", payload(output ->
                KaProxyProtocol.writeUuid(output, requestId)));
        assertEquals(1, sender.actions.stream().filter("request_completed"::equals).count());
    }

    /** 旧请求 UUID 不能撤销发送者后来建立的请求。 */
    @Test
    void rejectsStaleCancellationContext() throws Exception {
        UUID requestId = UUID.randomUUID();
        module.handle(sender, sender.serverName(), "request_create", payload(output -> {
            KaProxyProtocol.writeUuid(output, requestId);
            KaProxyProtocol.writeUuid(output, receiver.uniqueId());
            output.writeUTF("TPA_HERE");
            output.writeInt(30);
            output.writeInt(0);
        }));

        module.handle(sender, sender.serverName(), "request_cancel", payload(output ->
                KaProxyProtocol.writeUuid(output, UUID.randomUUID())));

        assertTrue(sender.actions.contains("operation_failed"));
        module.handle(receiver, receiver.serverName(), "request_accept", payload(output -> {
            KaProxyProtocol.writeUuid(output, requestId);
            output.writeBoolean(false);
        }));
        assertTrue(sender.actions.contains("request_accepted"));
    }

    /** 构建只包含动作负载的输入流。 */
    private DataInputStream payload(KaProxyProtocol.PacketWriter writer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writer.write(output);
        }
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }

    /** 保存测试玩家、调度任务和代理查询能力。 */
    private static final class FakeAdapter implements ProxyAdapter {
        private final Map<UUID, FakePlayer> players = new LinkedHashMap<>();
        private final List<Runnable> scheduled = new ArrayList<>();

        /** 添加一个测试玩家。 */
        private FakePlayer add(String name, String server) {
            FakePlayer player = new FakePlayer(UUID.randomUUID(), name, server);
            players.put(player.uniqueId(), player);
            return player;
        }

        @Override
        public Collection<? extends ProxyPlayer> players() {
            return players.values();
        }

        @Override
        public Optional<? extends ProxyPlayer> player(UUID playerId) {
            return Optional.ofNullable(players.get(playerId));
        }

        @Override
        public void broadcast(String channel, byte[] data, String excludedServer) {
        }

        @Override
        public void schedule(Runnable task, long delayMillis) {
            scheduled.add(task);
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void error(String message, Throwable error) {
            throw new AssertionError(message, error);
        }
    }

    /** 模拟可切服并记录后端动作的代理玩家。 */
    private static final class FakePlayer implements ProxyPlayer {
        private final UUID uniqueId;
        private final String name;
        private final List<String> actions = new ArrayList<>();
        private String server;

        /** 创建指定名称和子服的测试玩家。 */
        private FakePlayer(UUID uniqueId, String name, String server) {
            this.uniqueId = uniqueId;
            this.name = name;
            this.server = server;
        }

        @Override
        public UUID uniqueId() {
            return uniqueId;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String serverName() {
            return server;
        }

        @Override
        public boolean sendToBackend(String channel, byte[] data) {
            try {
                actions.add(KaProxyProtocol.decode(data).action());
                return true;
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        @Override
        public void connect(String serverName, Consumer<Boolean> completion) {
            server = serverName;
            completion.accept(true);
        }
    }
}
