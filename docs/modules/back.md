# Back 跨服返回

KaProxy 的 Back 模块让 KaTpa 的 `/back` 和 `/dback` 可以跨服返回到其他子服的精确坐标。

## 使用条件

* KaProxy 的 Back 模块已启用。
* 所有参与跨服返回的子服都安装兼容的 KaTpa。
* 各子服的 KaTpa 代理功能已启用，例如 `proxy.enabled: true`。
* 所有玩家通过同一个 Velocity 或 BungeeCord 代理进入子服。

## 工作流程

1. 玩家在子服 B 执行 `/back` 或 `/dback`。
2. KaTpa 读取上次位置或死亡位置，发现目标在子服 A。
3. 子服 B 通过 `kaproxy:main` 通道向 KaProxy 发送返回请求，携带目标子服名称和精确坐标。
4. KaProxy 将玩家切换到子服 A。
5. 玩家到达子服 A 后，KaProxy 向子服 A 交付坐标数据。
6. 子服 A 的 KaTpa 将玩家传送到精确位置。
7. 传送完成后子服 A 通知 KaProxy，事务结束。

## 配置

```yaml
modules:
  back:
    enabled: true
    transaction-timeout-seconds: 30
```

| 配置项 | 默认值 | 可用范围 | 说明 |
|--------|--------|----------|------|
| `enabled` | `true` | `true` / `false` | 是否启用跨服返回。关闭时，进行中的返回事务会被取消。 |
| `transaction-timeout-seconds` | `30` | 5–600 秒 | 切服和落点传送必须完成的总时间。超时后事务自动取消并通知玩家。 |

## 返回失败的常见情况

* 目标子服不可用或不存在。
* 玩家在切服过程中离开代理。
* 目标世界已卸载。
* 切服和落点传送未在事务超时前完成。
* 管理员重载配置并关闭了 Back 模块。

这些情况不会留下残留状态，玩家可以在问题解决后重新执行 `/back` 或 `/dback`。
