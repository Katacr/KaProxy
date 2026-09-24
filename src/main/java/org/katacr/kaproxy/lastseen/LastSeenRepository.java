package org.katacr.kaproxy.lastseen;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 跨服上次位置记录的存储抽象。
 *
 * <p>表由后端 KaLogin 创建（{@code kalogin_lastseen}）。坐标由各后端退服时写入，
 * {@code server} 列由代理在玩家真正离开时写入（切服不写），因此只有 {@code server}
 * 非空的记录才是"代理确认的离开位置"。</p>
 */
public interface LastSeenRepository {
    /** 异步读取玩家上次位置；无记录或未确认（server 为空）时返回空。 */
    CompletableFuture<Optional<Record>> load(UUID playerId);

    /** 玩家真正离开代理时写入其所在子服（异步、尽力而为）。 */
    void saveServer(UUID playerId, String server);

    /** 删除玩家的位置记录（登出/注销、黑名单子服离开时调用）。 */
    void delete(UUID playerId);

    /** 释放资源。 */
    default void close() {
    }

    /** 一条位置记录；server 为代理注册的子服名。 */
    record Record(String server, String world,
                  double x, double y, double z, float yaw, float pitch) {
    }
}
