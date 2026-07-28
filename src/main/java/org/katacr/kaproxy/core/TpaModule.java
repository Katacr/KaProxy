package org.katacr.kaproxy.core;

import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 在代理端权威管理 KaTpa 请求、超时、吟唱完成、切服和到达确认。 */
final class TpaModule {
    private final ProxyAdapter adapter;
    private volatile KaProxyConfig config;
    private volatile KaProxyLanguage language;
    private final Map<UUID, TpaRequest> requests = new HashMap<>();
    private final Map<UUID, UUID> outgoingBySender = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    /** 创建使用代理配置限制的 KaTpa 事务模块。 */
    TpaModule(ProxyAdapter adapter, KaProxyConfig config, KaProxyLanguage language) {
        this.adapter = adapter;
        this.config = config;
        this.language = language;
    }

    /** 更新事务限制和日志语言，保留当前请求状态。 */
    void reload(KaProxyConfig config, KaProxyLanguage language) {
        this.config = config;
        this.language = language;
    }

    /** 模块被热关闭时取消全部活动事务，避免残留请求占用玩家。 */
    synchronized void disable() {
        for (TpaRequest request : new ArrayList<>(requests.values())) {
            fail(request, "module-disabled");
        }
    }

    /** 按动作解码后端请求，并始终以真实载体玩家作为操作身份。 */
    synchronized void handle(ProxyPlayer carrier, String sourceServer, String action,
                             DataInputStream input) throws IOException {
        if (!carrier.serverName().equals(sourceServer)) {
            return;
        }
        switch (action) {
            case "request_create" -> create(carrier, input);
            case "request_accept" -> accept(carrier, input);
            case "request_deny" -> deny(carrier, input);
            case "request_cancel" -> cancel(carrier, input);
            case "warmup_complete" -> warmupComplete(carrier, input);
            case "warmup_cancel" -> warmupCancel(carrier, input);
            case "arrival_complete" -> arrivalComplete(carrier, input);
            case "arrival_failed" -> arrivalFailed(carrier, input);
            default -> {
                if (config.logUnknownModules()) {
                    adapter.info(language.text("unknown-tpa-action", Map.of("action", action)));
                }
            }
        }
    }

    /** 玩家进入子服后交付仍在等待的到达凭证。 */
    synchronized void playerConnected(ProxyPlayer player) {
        for (TpaRequest request : new ArrayList<>(requests.values())) {
            if (request.state == TpaRequest.State.CONNECTING
                    && request.travelerId().equals(player.uniqueId())) {
                ProxyPlayer destination = adapter.player(request.destinationId()).orElse(null);
                if (destination == null) {
                    fail(request, "player-offline");
                    continue;
                }
                if (config.tpaFollowTargetServer()) {
                    request.expectedServer = destination.serverName();
                }
                if (player.serverName().equals(request.expectedServer)) {
                    deliverArrival(request, player);
                } else {
                    connectTraveler(request, player);
                }
            }
        }
    }

    /** 玩家离开代理时取消其参与的所有事务并通知仍在线的一方。 */
    synchronized void playerDisconnected(UUID playerId) {
        for (TpaRequest request : new ArrayList<>(requests.values())) {
            if (!request.involves(playerId)) {
                continue;
            }
            cleanup(request);
            UUID otherId = request.senderId.equals(playerId) ? request.receiverId : request.senderId;
            adapter.player(otherId).ifPresent(other -> sendEvent(other, "request_cancelled", request,
                    output -> output.writeUTF("player-offline")));
        }
    }

    /** 校验冷却、目标在线和单发送请求限制后创建事务。 */
    private void create(ProxyPlayer sender, DataInputStream input) throws IOException {
        UUID requestId = KaProxyProtocol.readUuid(input);
        UUID receiverId = KaProxyProtocol.readUuid(input);
        String type = input.readUTF();
        int requestedTimeout = input.readInt();
        int requestedCooldown = input.readInt();
        if (!"TPA".equals(type) && !"TPA_HERE".equals(type)) {
            sendFailure(sender, requestId, "invalid-request", 0L);
            return;
        }
        long now = System.currentTimeMillis();
        long remainingCooldown = Math.max(0L, cooldownUntil.getOrDefault(sender.uniqueId(), 0L) - now);
        if (remainingCooldown > 0L) {
            sendFailure(sender, requestId, "cooldown-active", (remainingCooldown + 999L) / 1000L);
            return;
        }
        if (requests.containsKey(requestId) || outgoingBySender.containsKey(sender.uniqueId())) {
            sendFailure(sender, requestId, "sender-busy", 0L);
            return;
        }
        Optional<? extends ProxyPlayer> receiverOptional = adapter.player(receiverId);
        if (receiverOptional.isEmpty()) {
            sendFailure(sender, requestId, "player-not-found", 0L);
            return;
        }
        ProxyPlayer receiver = receiverOptional.get();
        if (sender.uniqueId().equals(receiver.uniqueId())) {
            sendFailure(sender, requestId, "cannot-self", 0L);
            return;
        }
        if (sender.serverName().equals(receiver.serverName())) {
            sendFailure(sender, requestId, "same-server", 0L);
            return;
        }
        int timeoutSeconds = Math.max(config.tpaRequestTimeoutMinSeconds(),
                Math.min(config.tpaRequestTimeoutMaxSeconds(), requestedTimeout));
        int cooldownSeconds = Math.max(0, Math.min(config.tpaCooldownMaxSeconds(), requestedCooldown));
        TpaRequest request = new TpaRequest(requestId, sender, receiver, type,
                now, now + timeoutSeconds * 1000L);
        requests.put(requestId, request);
        outgoingBySender.put(sender.uniqueId(), requestId);
        if (cooldownSeconds > 0) {
            cooldownUntil.put(sender.uniqueId(), now + cooldownSeconds * 1000L);
        }
        if (!sendEvent(receiver, "request_incoming", request, output -> { })) {
            cleanup(request);
            sendFailure(sender, requestId, "target-unavailable", 0L);
            return;
        }
        sendEvent(sender, "request_created", request, output -> { });
        adapter.schedule(() -> expire(requestId), timeoutSeconds * 1000L + 50L);
    }

    /** 由接收者原子接受待处理请求，并通知旅行者开始源服吟唱。 */
    private void accept(ProxyPlayer receiver, DataInputStream input) throws IOException {
        UUID requestId = KaProxyProtocol.readUuid(input);
        boolean automatic = input.readBoolean();
        TpaRequest request = pendingFor(requestId, receiver.uniqueId(), false);
        if (request == null) {
            sendFailure(receiver, requestId, "request-stale", 0L);
            return;
        }
        request.state = TpaRequest.State.ACCEPTED;
        request.automatic = automatic;
        sendBoth(request, "request_accepted", output -> output.writeBoolean(automatic));
        adapter.schedule(() -> transactionTimeout(requestId), config.tpaTransactionTimeoutSeconds() * 1000L);
    }

    /** 由接收者拒绝待处理请求并结束事务。 */
    private void deny(ProxyPlayer receiver, DataInputStream input) throws IOException {
        UUID requestId = KaProxyProtocol.readUuid(input);
        boolean automatic = input.readBoolean();
        TpaRequest request = pendingFor(requestId, receiver.uniqueId(), false);
        if (request == null) {
            sendFailure(receiver, requestId, "request-stale", 0L);
            return;
        }
        cleanup(request);
        sendBoth(request, "request_denied", output -> output.writeBoolean(automatic));
    }

    /** 只允许原发送者撤销仍待处理的同一 UUID 请求。 */
    private void cancel(ProxyPlayer sender, DataInputStream input) throws IOException {
        UUID requestId = KaProxyProtocol.readUuid(input);
        TpaRequest request = pendingFor(requestId, sender.uniqueId(), true);
        if (request == null) {
            sendFailure(sender, requestId, "request-stale", 0L);
            return;
        }
        cleanup(request);
        sendBoth(request, "request_cancelled", output -> output.writeUTF("sender-cancelled"));
    }

    /** 旅行者完成源服吟唱后，由代理解析目标最新子服并发起切换。 */
    private void warmupComplete(ProxyPlayer traveler, DataInputStream input) throws IOException {
        UUID requestId = KaProxyProtocol.readUuid(input);
        TpaRequest request = requests.get(requestId);
        if (request == null || request.state != TpaRequest.State.ACCEPTED
                || !request.travelerId().equals(traveler.uniqueId())) {
            sendFailure(traveler, requestId, "request-stale", 0L);
            return;
        }
        Optional<? extends ProxyPlayer> destinationOptional = adapter.player(request.destinationId());
        if (destinationOptional.isEmpty()) {
            fail(request, "player-offline");
            return;
        }
        ProxyPlayer destination = destinationOptional.get();
        String expectedServer = config.tpaFollowTargetServer()
                ? destination.serverName()
                : (request.destinationId().equals(request.senderId) ? request.senderServer : request.receiverServer);
        if (!destination.serverName().equals(expectedServer)) {
            fail(request, "target-moved-server");
            return;
        }
        request.state = TpaRequest.State.CONNECTING;
        request.expectedServer = expectedServer;
        if (traveler.serverName().equals(expectedServer)) {
            deliverArrival(request, traveler);
            return;
        }
        connectTraveler(request, traveler);
    }

    /** 把旅行者连接到事务当前锁定的目标子服，并安排到达重试。 */
    private void connectTraveler(TpaRequest request, ProxyPlayer traveler) {
        UUID requestId = request.id;
        traveler.connect(request.expectedServer, success -> {
            synchronized (TpaModule.this) {
                TpaRequest current = requests.get(requestId);
                if (current != request || current.state != TpaRequest.State.CONNECTING) {
                    return;
                }
                if (!success) {
                    fail(request, "connect-failed");
                    return;
                }
                adapter.schedule(() -> retryArrival(requestId), 250L);
            }
        });
    }

    /** 旅行者在源服移动、受伤或离线中断吟唱时结束事务。 */
    private void warmupCancel(ProxyPlayer traveler, DataInputStream input) throws IOException {
        UUID requestId = KaProxyProtocol.readUuid(input);
        String reason = input.readUTF();
        TpaRequest request = requests.get(requestId);
        if (request == null || request.state != TpaRequest.State.ACCEPTED
                || !request.travelerId().equals(traveler.uniqueId())) {
            return;
        }
        cleanup(request);
        sendBoth(request, "request_cancelled", output -> output.writeUTF(reason));
    }

    /** 目标服完成最终坐标传送后通知双方并清理事务。 */
    private void arrivalComplete(ProxyPlayer traveler, DataInputStream input) throws IOException {
        UUID requestId = KaProxyProtocol.readUuid(input);
        TpaRequest request = requests.get(requestId);
        if (request == null || request.state != TpaRequest.State.ARRIVING
                || !request.travelerId().equals(traveler.uniqueId())) {
            return;
        }
        cleanup(request);
        sendBoth(request, "request_completed", output -> { });
    }

    /** 目标服无法完成落点传送时向双方返回失败结果。 */
    private void arrivalFailed(ProxyPlayer traveler, DataInputStream input) throws IOException {
        UUID requestId = KaProxyProtocol.readUuid(input);
        String reason = input.readUTF();
        TpaRequest request = requests.get(requestId);
        if (request == null || request.state != TpaRequest.State.ARRIVING
                || !request.travelerId().equals(traveler.uniqueId())) {
            return;
        }
        fail(request, reason);
    }

    /** 切服事件可能先于连接回调，延迟后再次尝试交付到达凭证。 */
    private synchronized void retryArrival(UUID requestId) {
        TpaRequest request = requests.get(requestId);
        if (request == null || request.state != TpaRequest.State.CONNECTING) {
            return;
        }
        adapter.player(request.travelerId()).ifPresent(this::playerConnected);
    }

    /** 向目标服发送只能由旅行者连接消费的一次性到达上下文。 */
    private void deliverArrival(TpaRequest request, ProxyPlayer traveler) {
        if (request.state != TpaRequest.State.CONNECTING) {
            return;
        }
        request.state = TpaRequest.State.ARRIVING;
        if (!sendEvent(traveler, "arrival", request, output -> { })) {
            fail(request, "arrival-delivery-failed");
        }
    }

    /** 到期任务只清理仍待处理的请求，已接受事务由独立看门狗负责。 */
    private synchronized void expire(UUID requestId) {
        TpaRequest request = requests.get(requestId);
        if (request == null || request.state != TpaRequest.State.PENDING) {
            return;
        }
        cleanup(request);
        sendBoth(request, "request_expired", output -> { });
    }

    /** 接受后的事务超时仍未完成时强制结束，避免代理残留状态。 */
    private synchronized void transactionTimeout(UUID requestId) {
        TpaRequest request = requests.get(requestId);
        if (request != null && request.state != TpaRequest.State.PENDING) {
            fail(request, "transaction-timeout");
        }
    }

    /** 根据操作者身份取出待处理请求。 */
    private TpaRequest pendingFor(UUID requestId, UUID actorId, boolean senderAction) {
        TpaRequest request = requests.get(requestId);
        if (request == null || request.state != TpaRequest.State.PENDING) {
            return null;
        }
        UUID expected = senderAction ? request.senderId : request.receiverId;
        return expected.equals(actorId) ? request : null;
    }

    /** 通知双方事务失败并清理索引。 */
    private void fail(TpaRequest request, String reason) {
        cleanup(request);
        sendBoth(request, "request_failed", output -> output.writeUTF(reason));
    }

    /** 从代理权威索引中移除事务。 */
    private void cleanup(TpaRequest request) {
        requests.remove(request.id, request);
        outgoingBySender.remove(request.senderId, request.id);
    }

    /** 向双方当前所在后端发送同一事务事件。 */
    private void sendBoth(TpaRequest request, String action, ExtraWriter extra) {
        ProxyPlayer sender = adapter.player(request.senderId).orElse(null);
        ProxyPlayer receiver = adapter.player(request.receiverId).orElse(null);
        if (sender != null) {
            sendEvent(sender, action, request, extra);
        }
        if (receiver != null && (sender == null || !receiver.serverName().equals(sender.serverName()))) {
            sendEvent(receiver, action, request, extra);
        }
    }

    /** 编码带完整请求上下文的代理事件。 */
    private boolean sendEvent(ProxyPlayer player, String action, TpaRequest request, ExtraWriter extra) {
        try {
            byte[] packet = KaProxyProtocol.encode("tpa", action, output -> {
                writeRequest(output, request);
                extra.write(output);
            });
            return player.sendToBackend(KaProxyProtocol.CHANNEL, packet);
        } catch (IOException error) {
            adapter.error(language.text("tpa-event-encode-failed", Map.of("action", action)), error);
            return false;
        }
    }

    /** 向操作玩家返回不含有效事务的失败结果。 */
    private void sendFailure(ProxyPlayer player, UUID requestId, String reason, long seconds) {
        try {
            byte[] packet = KaProxyProtocol.encode("tpa", "operation_failed", output -> {
                KaProxyProtocol.writeUuid(output, requestId);
                output.writeUTF(reason);
                output.writeLong(seconds);
            });
            player.sendToBackend(KaProxyProtocol.CHANNEL, packet);
        } catch (IOException error) {
            adapter.error(language.text("tpa-operation-failed-encode"), error);
        }
    }

    /** 写入后端恢复请求所需的完整上下文。 */
    private void writeRequest(DataOutputStream output, TpaRequest request) throws IOException {
        KaProxyProtocol.writeUuid(output, request.id);
        KaProxyProtocol.writeUuid(output, request.senderId);
        output.writeUTF(request.senderName);
        output.writeUTF(request.senderServer);
        KaProxyProtocol.writeUuid(output, request.receiverId);
        output.writeUTF(request.receiverName);
        output.writeUTF(request.receiverServer);
        output.writeUTF(request.type);
        output.writeLong(request.createdAt);
        output.writeLong(request.expiresAt);
    }

    /** 为事务事件追加动作专属字段。 */
    @FunctionalInterface
    private interface ExtraWriter {
        /** 写入动作专属字段。 */
        void write(DataOutputStream output) throws IOException;
    }
}
