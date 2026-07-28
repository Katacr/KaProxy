package org.katacr.kaproxy.core;

import java.util.UUID;
import java.util.function.Consumer;

/** 隔离 Velocity 与 BungeeCord 玩家连接、后端消息和切服 API。 */
public interface ProxyPlayer {
    /** 返回玩家 UUID。 */
    UUID uniqueId();

    /** 返回玩家当前名称。 */
    String name();

    /** 返回玩家当前子服名称；尚未进入子服时返回空字符串。 */
    String serverName();

    /** 通过玩家当前连接向所在后端发送插件消息。 */
    boolean sendToBackend(String channel, byte[] data);

    /** 请求切换到指定子服，并回报代理是否建立了连接。 */
    void connect(String serverName, Consumer<Boolean> completion);
}
