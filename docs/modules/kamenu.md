# KaMenu 跨服动作转发

KaProxy 让 KaMenu 菜单中的目标选择器动作触达其他子服的玩家。

## 使用条件

* KaProxy 的 Kamenu 模块已启用。
* 各参与子服已安装 KaMenu 并在 `config.yml` 中启用 `kaproxy.enabled: true`。
* 所有玩家通过同一个 Velocity 或 BungeeCord 代理进入子服。

## 配置

在 KaProxy 的 `config.yml` 中：

```yaml
modules:
  kamenu:
    enabled: true
    # 允许接收跨服动作的后端列表。
    # 设为 all 或 * 表示转发到所有后端（排除来源服）。
    # 未列出的服务器不会收到跨服动作包。
    servers:
      - survival
      - creative
```

## 工作流程

1. 玩家在后端 A 点击菜单按钮，动作带有 `{player: 选择器}{cross}` 标记。
2. KaMenu 先在本服对选择器命中的玩家执行动作。
3. KaMenu 编码动作并通过 `kaproxy:main` 通道发送到 KaProxy。
4. KaProxy 按配置的 `servers` 列表把包转发到目标后端（排除来源服）。
5. 目标后端收到后本地求值选择器，对命中玩家执行动作。

## 支持的动作

跨服白名单仅包含消息类、经济类和数据类动作：

* `tell`、`actionbar`、`title`、`sound`、`hovertext`
* `money`、`points`
* `data`、`gdata`、`tmpdata`、`meta`、`set-data`、`set-gdata`、`set-meta`

非白名单动作会被后端拒绝并记录警告。

## 注意事项

* 选择器条件在每个后端**本地求值**，PAPI 变量和 KaMenu 内置谓词完全可用。
* `servers` 设为 `all` 或 `*` 时转发到所有有玩家连接的后端（排除来源服）。
* 如果目标后端无在线玩家，该后端自然被跳过。
* 代理不解析动作内容，只做透传路由；安全性由后端白名单保证。
