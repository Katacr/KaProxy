# 命令与权限

KaProxy 的管理命令在代理控制台或代理命令系统中执行，而不是在单个子服的控制台中执行。

## 命令列表

### `/kaproxy status`

显示：

* Guilds 模块是否启用
* Tpa 模块是否启用
* 当前代理在线人数

代理控制台和玩家均可使用此状态命令。

### `/kaproxy reload`

重新加载：

* `config.yml`
* 当前选择的语言文件
* Guilds 和 Tpa 模块开关及参数

权限：

```text
kaproxy.admin
```

代理控制台通常不受权限限制。玩家执行时必须拥有 `kaproxy.admin`。

## 权限建议

只向可信管理员授予：

```text
kaproxy.admin
```

普通玩家不需要任何 KaProxy 权限。TPA、公会聊天等玩家权限由子服上的 KaTpa 和 KaGuilds 管理。

## 重载注意事项

* 修改配置或语言后可以安全重载，不需要重启代理。
* 关闭 Tpa 模块会取消当前正在进行的跨服传送请求。
* 更换 KaProxy JAR 或代理版本后，应完整重启代理。
