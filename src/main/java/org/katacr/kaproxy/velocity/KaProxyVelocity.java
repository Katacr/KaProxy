package org.katacr.kaproxy.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.katacr.kaproxy.config.KaProxyConfig;
import org.katacr.kaproxy.core.KaProxyCore;
import org.katacr.kaproxy.core.ProxyAdapter;
import org.katacr.kaproxy.core.ProxyPlayer;
import org.katacr.kaproxy.i18n.KaProxyLanguage;
import org.katacr.kaproxy.protocol.KaProxyProtocol;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Velocity 入口，负责平台事件、通道、命令和 KaProxy 核心装配。 */
@Plugin(id = "kaproxy", name = "KaProxy", version = "1.0.0",
        description = "Ka 系列插件统一跨服事务代理", authors = {"katacr"})
public final class KaProxyVelocity {
    private static final MinecraftChannelIdentifier MAIN_CHANNEL =
            MinecraftChannelIdentifier.from(KaProxyProtocol.CHANNEL);
    private static final MinecraftChannelIdentifier GUILDS_CHANNEL =
            MinecraftChannelIdentifier.from(KaProxyProtocol.LEGACY_GUILDS_CHANNEL);

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private VelocityAdapter adapter;
    private KaProxyCore core;
    private KaProxyConfig config;
    private KaProxyLanguage language;

    /** 注入 Velocity 服务、日志和插件数据目录。 */
    @Inject
    public KaProxyVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /** 加载配置和语言，注册双通道及管理命令。 */
    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            loadSettings();
        } catch (IOException error) {
            throw new IllegalStateException("KaProxy 配置加载失败", error);
        }
        server.getChannelRegistrar().register(MAIN_CHANNEL, GUILDS_CHANNEL);
        adapter = new VelocityAdapter(server, logger, this);
        core = new KaProxyCore(adapter, config, language);
        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("kaproxy").plugin(this).build(), new AdminCommand());
        logger.info(language.text("startup", Map.of("platform", "Velocity")));
        core.playerTopologyChanged();
    }

    /** 释放代理关闭日志。 */
    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (language != null) {
            logger.info(language.text("shutdown"));
        }
    }

    /** 玩家进入或切换子服后同步在线状态并交付到达凭证。 */
    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        if (core != null) {
            core.playerConnected(new VelocityPlayer(server, event.getPlayer()));
        }
    }

    /** 玩家离开代理时取消其参与的临时事务。 */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (core != null) {
            core.playerDisconnected(new VelocityPlayer(server, event.getPlayer()));
        }
    }

    /** 截获后端消息，阻止统一协议被错误转发给客户端。 */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof ServerConnection source)) {
            return;
        }
        if (event.getIdentifier().equals(MAIN_CHANNEL)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            core.handleMessage(new VelocityPlayer(server, source.getPlayer()),
                    source.getServer().getServerInfo().getName(), event.getData());
        } else if (event.getIdentifier().equals(GUILDS_CHANNEL)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            core.handleLegacyGuildMessage(source.getServer().getServerInfo().getName(), event.getData());
        }
    }

    /** 从 config.yml 和独立 lang 文件夹重新构造配置快照。 */
    private void loadSettings() throws IOException {
        config = KaProxyConfig.load(dataDirectory, resource("config.yml"));
        language = KaProxyLanguage.load(dataDirectory, config.language(), this::resource);
    }

    /** 使用插件类加载器打开内置资源。 */
    private InputStream resource(String path) {
        return getClass().getClassLoader().getResourceAsStream(path);
    }

    /** 处理 /kaproxy status 与 /kaproxy reload。 */
    private final class AdminCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] arguments = invocation.arguments();
            if (arguments.length == 0 || arguments[0].equalsIgnoreCase("status")) {
                source.sendMessage(Component.text(language.text("status", Map.of(
                        "guilds", language.text(config.guildsEnabled() ? "enabled" : "disabled"),
                        "tpa", language.text(config.tpaEnabled() ? "enabled" : "disabled"),
                        "players", Integer.toString(server.getPlayerCount())))));
                return;
            }
            if (!arguments[0].equalsIgnoreCase("reload")) {
                source.sendMessage(Component.text(language.text("command-help")));
                return;
            }
            if (!source.hasPermission("kaproxy.admin")) {
                source.sendMessage(Component.text(language.text("no-permission")));
                return;
            }
            try {
                loadSettings();
                core.reload(config, language);
                core.playerTopologyChanged();
                source.sendMessage(Component.text(language.text("config-reloaded")));
            } catch (IOException error) {
                source.sendMessage(Component.text(language.text("reload-failed", Map.of(
                        "error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()))));
            }
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return true;
        }
    }

    /** Velocity 平台能力适配器。 */
    private record VelocityAdapter(ProxyServer server, Logger logger, Object plugin) implements ProxyAdapter {
        @Override
        public Collection<? extends ProxyPlayer> players() {
            return server.getAllPlayers().stream().map(player -> new VelocityPlayer(server, player)).toList();
        }

        @Override
        public Optional<? extends ProxyPlayer> player(UUID playerId) {
            return server.getPlayer(playerId).map(player -> new VelocityPlayer(server, player));
        }

        @Override
        public void broadcast(String channel, byte[] data, String excludedServer) {
            MinecraftChannelIdentifier identifier = MinecraftChannelIdentifier.from(channel);
            server.getAllServers().forEach(target -> {
                if (excludedServer == null || !excludedServer.equals(target.getServerInfo().getName())) {
                    target.sendPluginMessage(identifier, data);
                }
            });
        }

        @Override
        public void schedule(Runnable task, long delayMillis) {
            server.getScheduler().buildTask(plugin, task)
                    .delay(Math.max(0L, delayMillis), TimeUnit.MILLISECONDS).schedule();
        }

        @Override
        public void info(String message) {
            logger.info(message);
        }

        @Override
        public void error(String message, Throwable error) {
            logger.error(message, error);
        }
    }

    /** Velocity 玩家连接包装器。 */
    private record VelocityPlayer(ProxyServer server, Player player) implements ProxyPlayer {
        @Override
        public UUID uniqueId() {
            return player.getUniqueId();
        }

        @Override
        public String name() {
            return player.getUsername();
        }

        @Override
        public String serverName() {
            return player.getCurrentServer()
                    .map(connection -> connection.getServer().getServerInfo().getName()).orElse("");
        }

        @Override
        public boolean sendToBackend(String channel, byte[] data) {
            return player.getCurrentServer().map(connection -> connection.sendPluginMessage(
                    MinecraftChannelIdentifier.from(channel), data)).orElse(false);
        }

        @Override
        public void connect(String serverName, Consumer<Boolean> completion) {
            var target = server.getServer(serverName);
            if (target.isEmpty()) {
                completion.accept(false);
                return;
            }
            player.createConnectionRequest(target.get()).connect().whenComplete((result, error) ->
                    completion.accept(error == null && result != null && result.isSuccessful()));
        }
    }
}
