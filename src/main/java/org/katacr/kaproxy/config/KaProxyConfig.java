package org.katacr.kaproxy.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 加载 KaProxy 的模块开关和轻量标量参数，避免为小型配置打包额外 YAML 依赖。 */
public final class KaProxyConfig {
    private final Map<String, String> values;

    /** 创建只读配置快照。 */
    private KaProxyConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /** 首次启动释放默认 config.yml，并读取缩进式标量节点。 */
    public static KaProxyConfig load(Path dataDirectory, InputStream defaultConfig) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configFile = dataDirectory.resolve("config.yml");
        if (Files.notExists(configFile)) {
            if (defaultConfig == null) {
                throw new IOException("插件资源缺少 config.yml");
            }
            try (defaultConfig) {
                Files.copy(defaultConfig, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } else if (defaultConfig != null) {
            defaultConfig.close();
        }
        Map<String, String> values = new HashMap<>();
        Deque<Section> sections = new ArrayDeque<>();
        try (BufferedReader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, sections, values);
            }
        }
        return new KaProxyConfig(values);
    }

    /** 解析一行只包含 section、基础标量或 YAML 列表项的配置。 */
    private static void parseLine(String line, Deque<Section> sections, Map<String, String> values) {
        String stripped = line.stripLeading();
        if (stripped.isBlank() || stripped.startsWith("#")) {
            return;
        }
        int indent = line.length() - stripped.length();

        // YAML 列表项：以 "- " 开头，追加到当前 section 路径下（逗号分隔）
        if (stripped.startsWith("- ") || stripped.equals("-")) {
            String item = stripped.startsWith("- ") ? stripped.substring(2).trim() : "";
            int comment = item.indexOf(" #");
            if (comment >= 0) item = item.substring(0, comment).trim();
            if (item.length() >= 2 && item.startsWith("\"") && item.endsWith("\"")) {
                item = item.substring(1, item.length() - 1);
            }
            String prefix = sections.stream().map(Section::key)
                    .reduce((first, second) -> first + "." + second).orElse("");
            if (prefix.isEmpty()) return;
            String existing = values.get(prefix);
            String merged = existing == null || existing.isEmpty() ? item : existing + "," + item;
            values.put(prefix, merged);
            return;
        }

        int separator = stripped.indexOf(':');
        if (separator <= 0) {
            return;
        }
        while (!sections.isEmpty() && sections.peekLast().indent() >= indent) {
            sections.removeLast();
        }
        String key = stripped.substring(0, separator).trim();
        String rawValue = stripped.substring(separator + 1).trim();
        String prefix = sections.stream().map(Section::key)
                .reduce((first, second) -> first + "." + second).orElse("");
        String path = prefix.isEmpty() ? key : prefix + "." + key;
        if (rawValue.isEmpty()) {
            sections.addLast(new Section(indent, key));
            return;
        }
        int comment = rawValue.indexOf(" #");
        String value = comment >= 0 ? rawValue.substring(0, comment).trim() : rawValue;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        values.put(path, value);
    }

    /** 返回核心是否记录未知模块数据包。 */
    public boolean logUnknownModules() {
        return bool("core.log-unknown-modules", false);
    }

    /** 返回 lang 文件夹中要加载的语言文件名。 */
    public String language() {
        return values.getOrDefault("core.language", "zh_CN");
    }

    /** 返回公会兼容模块是否启用。 */
    public boolean guildsEnabled() {
        return bool("modules.guilds.enabled", true);
    }

    /** 返回是否注册并转发旧 kaguilds:chat 通道。 */
    public boolean guildsLegacyChannelEnabled() {
        return guildsEnabled() && bool("modules.guilds.legacy-channel-enabled", true);
    }

    /** 返回是否向 KaGuilds 广播旧格式在线玩家列表。 */
    public boolean guildsPlayerListSync() {
        return guildsLegacyChannelEnabled() && bool("modules.guilds.sync-player-list", true);
    }

    /** 返回 KaTpa 跨服事务模块是否启用。 */
    public boolean tpaEnabled() {
        return bool("modules.tpa.enabled", true);
    }

    /** 返回第三方插件跨服传送模块是否启用。 */
    public boolean customTeleportEnabled() {
        return bool("modules.customteleport.enabled", true);
    }

    /** 返回第三方传送事务的切服与落点交付超时。 */
    public int customTeleportTimeoutSeconds() {
        return integer("modules.customteleport.transaction-timeout-seconds", 30, 5, 600);
    }

    /** 返回第三方插件跨服消息模块是否启用。 */
    public boolean customMessageEnabled() {
        return bool("modules.custommessage.enabled", true);
    }

    /** 返回 /back 与 /dback 跨服返回模块是否启用。 */
    public boolean backEnabled() {
        return bool("modules.back.enabled", true);
    }

    /** 返回跨服返回事务的切服与落点交付超时。 */
    public int backTransactionTimeoutSeconds() {
        return integer("modules.back.transaction-timeout-seconds", 30, 5, 600);
    }

    /** 返回代理允许的最短请求有效期。 */
    public int tpaRequestTimeoutMinSeconds() {
        return integer("modules.tpa.request-timeout-min-seconds", 5, 1, 3600);
    }

    /** 返回代理允许的最长请求有效期。 */
    public int tpaRequestTimeoutMaxSeconds() {
        return integer("modules.tpa.request-timeout-max-seconds", 120,
                tpaRequestTimeoutMinSeconds(), 86400);
    }

    /** 返回接受后吟唱、切服和落点确认共用的事务超时。 */
    public int tpaTransactionTimeoutSeconds() {
        return integer("modules.tpa.transaction-timeout-seconds", 60, 10, 600);
    }

    /** 返回后端可提交给代理保存的最大冷却秒数。 */
    public int tpaCooldownMaxSeconds() {
        return integer("modules.tpa.cooldown-max-seconds", 3600, 0, 86400);
    }

    /** 返回吟唱完成时是否跟随目标玩家最新所在子服。 */
    public boolean tpaFollowTargetServer() {
        return bool("modules.tpa.follow-target-server", true);
    }

    /** 返回 KaMenu 跨服动作转发模块是否启用。 */
    public boolean kamenuEnabled() {
        return bool("modules.kamenu.enabled", false);
    }

    /** 返回群组公共消息模块是否启用。 */
    public boolean broadcastEnabled() {
        return bool("modules.broadcast.enabled", false);
    }

    /** 返回群组公共消息链路的诊断日志开关。 */
    public boolean broadcastDebug() {
        return bool("modules.broadcast.debug", false);
    }

    /** 返回群组公告转发是否启用。 */
    public boolean broadcastAnnouncementsEnabled() {
        return broadcastEnabled() && bool("modules.broadcast.announcements-enabled", true);
    }

    /** 返回群组加入退出消息是否启用。 */
    public boolean broadcastConnectionMessagesEnabled() {
        return broadcastEnabled() && bool("modules.broadcast.connection-messages-enabled", true);
    }

    /** 返回群组死亡消息是否启用。 */
    public boolean broadcastDeathMessagesEnabled() {
        return broadcastEnabled() && bool("modules.broadcast.death-messages-enabled", true);
    }

    /** 返回允许接收公共消息的后端列表；all 或 * 返回 null。 */
    public List<String> broadcastServers() {
        String raw = values.get("modules.broadcast.servers");
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("all") || raw.equals("*")) {
            return null;
        }
        List<String> servers = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) servers.add(trimmed);
        }
        return servers;
    }

    /** 返回 KaLogin 跨服登录会话模块是否启用。 */
    public boolean kaloginEnabled() {
        return bool("modules.kalogin.enabled", false);
    }

    /** 返回 KaLogin 会话是否绑定登录时的 IP（IP 变化将拒绝恢复登录态）。 */
    public boolean kaloginBindIp() {
        return bool("modules.kalogin.bind-ip", true);
    }

    /** 返回跨服位置数据库的 JDBC URL；未配置数据库时返回 null。 */
    public String databaseUrl() {
        String host = values.get("database.host");
        String database = values.get("database.database");
        if (host == null || host.isBlank() || database == null || database.isBlank()) {
            return null;
        }
        int port = integer("database.port", 3306, 1, 65535);
        String params = values.getOrDefault("database.params", "");
        return "jdbc:mysql://" + host.trim() + ":" + port + "/" + database.trim() + params;
    }

    /** 返回跨服位置数据库用户名。 */
    public String databaseUsername() {
        return values.getOrDefault("database.username", "root");
    }

    /** 返回跨服位置数据库密码。 */
    public String databasePassword() {
        return values.getOrDefault("database.password", "");
    }

    /** 返回 KaLogin 会话同步链路的诊断日志开关。 */
    public boolean kaloginDebug() {
        return bool("modules.kalogin.debug", false);
    }

    /** 返回是否在他人尝试登录已登录账户时向该玩家定向发送安全告警。 */
    public boolean kaloginSecurityAlertEnabled() {
        return bool("modules.kalogin.security-alert.enabled", true);
    }

    /** 返回同一玩家的安全告警冷却秒数，防止被恶意刷屏。 */
    public int kaloginSecurityAlertCooldownSeconds() {
        return integer("modules.kalogin.security-alert.cooldown-seconds", 600, 0, 86400);
    }

    /** 返回是否在登录失败次数过多时临时封禁来源 IP。 */
    public boolean kaloginIpBanEnabled() {
        return bool("modules.kalogin.ip-ban.enabled", true);
    }

    /** 返回登录失败统计窗口秒数。 */
    public int kaloginIpBanWindowSeconds() {
        return integer("modules.kalogin.ip-ban.window-seconds", 600, 1, 86400);
    }

    /** 返回窗口内登录失败达到多少次后封禁 IP。 */
    public int kaloginIpBanThreshold() {
        return integer("modules.kalogin.ip-ban.threshold", 10, 1, 10000);
    }

    /** 返回临时封禁 IP 的时长秒数。 */
    public int kaloginIpBanDurationSeconds() {
        return integer("modules.kalogin.ip-ban.duration-seconds", 1800, 1, 604800);
    }

    /** 返回“上次下线位置”模块是否启用。 */
    public boolean lastSeenEnabled() {
        return bool("modules.lastseen.enabled", false);
    }

    /** 返回位置无效时回退的默认子服名；空串表示停留在当前子服。 */
    public String lastSeenDefaultServer() {
        String value = values.get("modules.lastseen.default-server");
        return value == null ? "" : value.trim();
    }

    /**
     * 返回 lastseen 模式：
     * direct=初次连接直接连到上次所在子服并传送；
     * manual=进默认服后弹窗询问；
     * auto=先进默认服，登录后延迟再自动传送。
     */
    public String lastSeenMode() {
        String value = values.get("modules.lastseen.mode");
        return value == null ? "direct" : value.trim().toLowerCase();
    }

    /** 返回 auto 模式下登录完成后延迟多少秒再传送（给资源包下载留时间）。 */
    public int lastSeenJoinDelaySeconds() {
        return integer("modules.lastseen.join-delay-seconds", 5, 0, 600);
    }

    /** 返回不记录上次位置的子服名列表；从这些子服下线时按默认服处理。 */
    public List<String> lastSeenBlacklist() {
        String raw = values.get("modules.lastseen.blacklist");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> servers = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) servers.add(trimmed);
        }
        return servers;
    }

    /** 返回“上次下线位置”链路的诊断日志开关。 */
    public boolean lastSeenDebug() {
        return bool("modules.lastseen.debug", false);
    }

    /**
     * 返回允许接收跨服动作的后端服务器名称列表。
     *
     * 配置为 all 或 * 时返回 null 表示匹配全部服务器；空列表表示未配置。
     */
    public List<String> kamenuServers() {
        String raw = values.get("modules.kamenu.servers");
        if (raw == null || raw.isBlank()) return List.of();
        if (raw.equalsIgnoreCase("all") || raw.equals("*")) return null;
        List<String> servers = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) servers.add(trimmed);
        }
        return servers;
    }

    /** 读取布尔值，非法内容使用默认值。 */
    private boolean bool(String path, boolean fallback) {
        String value = values.get(path);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /** 读取并限制整数范围，非法内容使用默认值。 */
    private int integer(String path, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(values.getOrDefault(path, Integer.toString(fallback)));
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 保存解析配置时的缩进层级。 */
    private record Section(int indent, String key) {
    }
}
