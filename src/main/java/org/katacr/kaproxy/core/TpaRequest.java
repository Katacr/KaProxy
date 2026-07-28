package org.katacr.kaproxy.core;

import java.util.UUID;

/** 保存由代理权威管理的一次跨服传送请求及其事务阶段。 */
final class TpaRequest {
    enum State {
        PENDING,
        ACCEPTED,
        CONNECTING,
        ARRIVING
    }

    final UUID id;
    final UUID senderId;
    final String senderName;
    final String senderServer;
    final UUID receiverId;
    final String receiverName;
    final String receiverServer;
    final String type;
    final long createdAt;
    final long expiresAt;
    State state = State.PENDING;
    boolean automatic;
    String expectedServer;

    /** 创建一条仍等待接收者处理的跨服传送事务。 */
    TpaRequest(UUID id, ProxyPlayer sender, ProxyPlayer receiver, String type,
               long createdAt, long expiresAt) {
        this.id = id;
        this.senderId = sender.uniqueId();
        this.senderName = sender.name();
        this.senderServer = sender.serverName();
        this.receiverId = receiver.uniqueId();
        this.receiverName = receiver.name();
        this.receiverServer = receiver.serverName();
        this.type = type;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** 返回实际需要切换子服的旅行者 UUID。 */
    UUID travelerId() {
        return "TPA".equals(type) ? senderId : receiverId;
    }

    /** 返回传送目的地玩家 UUID。 */
    UUID destinationId() {
        return "TPA".equals(type) ? receiverId : senderId;
    }

    /** 判断玩家是否参与这条事务。 */
    boolean involves(UUID playerId) {
        return senderId.equals(playerId) || receiverId.equals(playerId);
    }
}
