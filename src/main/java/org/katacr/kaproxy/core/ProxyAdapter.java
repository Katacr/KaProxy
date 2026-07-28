package org.katacr.kaproxy.core;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** 为 KaProxy 核心提供双平台代理无关的玩家、广播、调度和日志能力。 */
public interface ProxyAdapter {
    /** 返回当前连接到代理的全部玩家。 */
    Collection<? extends ProxyPlayer> players();

    /** 按 UUID 查找在线玩家。 */
    Optional<? extends ProxyPlayer> player(UUID playerId);

    /** 向所有有连接的后端广播数据，可排除来源子服。 */
    void broadcast(String channel, byte[] data, String excludedServer);

    /** 在指定毫秒后执行任务。 */
    void schedule(Runnable task, long delayMillis);

    /** 写入普通日志。 */
    void info(String message);

    /** 写入异常日志。 */
    void error(String message, Throwable error);
}
