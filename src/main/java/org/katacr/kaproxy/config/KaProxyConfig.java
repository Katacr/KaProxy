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
