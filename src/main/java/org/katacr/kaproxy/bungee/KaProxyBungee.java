package org.katacr.kaproxy.bungee;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.core.KaProxyCore;
import org.katacr.kaproxy.core.ProxyAdapter;
import org.katacr.kaproxy.core.ProxyPlayer;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** BungeeCord 入口，负责平台事件、通道、命令和 KaProxy 核心装配。 */
public final class KaProxyBungee extends Plugin implements Listener {
    private BungeeAdapter adapter;
    private KaProxyCore core;
    private KaProxyConfig config;
    private KaProxyLanguage language;

    /** 加载配置、注册通道监听器与管理命令。 */
    @Override
    public void onEnable() {
        try {
            loadSettings();
        } catch (IOException error) {
            throw new IllegalStateException("KaProxy 配置加载失败", error);
        }
        getProxy().registerChannel(KaProxyProtocol.CHANNEL);
        getProxy().registerChannel(KaProxyProtocol.LEGACY_GUILDS_CHANNEL);
        adapter = new BungeeAdapter(this);
        core = new KaProxyCore(adapter, config, language);
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new AdminCommand());
        getLogger().info(language.text("startup", Map.of("platform", "BungeeCord")));
        core.playerTopologyChanged();
    }

    /** 写入关闭日志并注销通道。 */
    @Override
    public void onDisable() {
        if (language != null) {
            getLogger().info(language.text("shutdown"));
        }
        getProxy().unregisterChannel(KaProxyProtocol.CHANNEL);
        getProxy().unregisterChannel(KaProxyProtocol.LEGACY_GUILDS_CHANNEL);
    }

    /** 玩家连接子服后同步在线状态并交付到达凭证。 */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        core.playerConnected(new BungeePlayer(getProxy(), event.getPlayer()));
    }

    /** 玩家离开代理时取消其参与的临时事务。 */
    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        core.playerDisconnected(new BungeePlayer(getProxy(), event.getPlayer()));
    }

    /** 截获来自后端的 KaProxy 或旧公会插件消息。 */
    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSender() instanceof Server source)
                || !(event.getReceiver() instanceof ProxiedPlayer carrier)) {
            return;
        }
        if (event.getTag().equals(KaProxyProtocol.CHANNEL)) {
            event.setCancelled(true);
            core.handleMessage(new BungeePlayer(getProxy(), carrier), source.getInfo().getName(), event.getData());
        } else if (event.getTag().equals(KaProxyProtocol.LEGACY_GUILDS_CHANNEL)) {
            event.setCancelled(true);
            core.handleLegacyGuildMessage(source.getInfo().getName(), event.getData());
        }
    }

    /** 从 config.yml 和独立 lang 文件夹重新构造配置快照。 */
    private void loadSettings() throws IOException {
        config = KaProxyConfig.load(getDataFolder().toPath(), resource("config.yml"));
        language = KaProxyLanguage.load(getDataFolder().toPath(), config.language(), this::resource);
    }

    /** 使用插件类加载器打开内置资源。 */
    private InputStream resource(String path) {
        return getResourceAsStream(path);
    }

    /** 处理 /kaproxy status 与 /kaproxy reload。 */
    private final class AdminCommand extends Command {
        /** 创建代理管理命令。 */
        private AdminCommand() {
            super("kaproxy");
        }

        @Override
        public void execute(CommandSender sender, String[] arguments) {
            if (arguments.length == 0 || arguments[0].equalsIgnoreCase("status")) {
                sender.sendMessage(TextComponent.fromLegacy(language.text("status", Map.of(
                        "guilds", language.text(config.guildsEnabled() ? "enabled" : "disabled"),
                        "tpa", language.text(config.tpaEnabled() ? "enabled" : "disabled"),
                        "players", Integer.toString(getProxy().getOnlineCount())))));
                return;
            }
            if (!arguments[0].equalsIgnoreCase("reload")) {
                sender.sendMessage(TextComponent.fromLegacy(language.text("command-help")));
                return;
            }
            if (!sender.hasPermission("kaproxy.admin")) {
                sender.sendMessage(TextComponent.fromLegacy(language.text("no-permission")));
                return;
            }
            try {
                loadSettings();
                core.reload(config, language);
                core.playerTopologyChanged();
                sender.sendMessage(TextComponent.fromLegacy(language.text("config-reloaded")));
            } catch (IOException error) {
                sender.sendMessage(TextComponent.fromLegacy(language.text("reload-failed", Map.of(
                        "error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()))));
            }
        }
    }

    /** BungeeCord 平台能力适配器。 */
    private record BungeeAdapter(Plugin plugin) implements ProxyAdapter {
        @Override
        public Collection<? extends ProxyPlayer> players() {
            return plugin.getProxy().getPlayers().stream()
                    .map(player -> new BungeePlayer(plugin.getProxy(), player)).toList();
        }

        @Override
        public Optional<? extends ProxyPlayer> player(UUID playerId) {
            return Optional.ofNullable(plugin.getProxy().getPlayer(playerId))
                    .map(player -> new BungeePlayer(plugin.getProxy(), player));
        }

        @Override
        public void broadcast(String channel, byte[] data, String excludedServer) {
            plugin.getProxy().getServers().forEach((name, target) -> {
                if (excludedServer == null || !excludedServer.equals(name)) {
                    target.sendData(channel, data);
                }
            });
        }

        @Override
        public void schedule(Runnable task, long delayMillis) {
            plugin.getProxy().getScheduler().schedule(plugin, task,
                    Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
        }

        @Override
        public void info(String message) {
            plugin.getLogger().info(message);
        }

        @Override
        public void error(String message, Throwable error) {
            plugin.getLogger().severe(message + (error == null ? "" : " " + error.getMessage()));
            if (error != null) {
                error.printStackTrace();
            }
        }
    }

    /** BungeeCord 玩家连接包装器。 */
    private record BungeePlayer(ProxyServer proxy, ProxiedPlayer player) implements ProxyPlayer {
        @Override
        public UUID uniqueId() {
            return player.getUniqueId();
        }

        @Override
        public String name() {
            return player.getName();
        }

        @Override
        public String serverName() {
            return player.getServer() == null ? "" : player.getServer().getInfo().getName();
        }

        @Override
        public boolean sendToBackend(String channel, byte[] data) {
            if (player.getServer() == null) {
                return false;
            }
            player.getServer().sendData(channel, data);
            return true;
        }

        @Override
        public void connect(String serverName, Consumer<Boolean> completion) {
            var target = proxy.getServerInfo(serverName);
            if (target == null) {
                completion.accept(false);
                return;
            }
            player.connect(target, (success, error) -> completion.accept(Boolean.TRUE.equals(success)));
        }
    }
}
