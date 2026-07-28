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
import java.util.Deque;
import java.util.HashMap;
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

    /** 解析一行只包含 section 或基础标量的 YAML 配置。 */
    private static void parseLine(String line, Deque<Section> sections, Map<String, String> values) {
        String stripped = line.stripLeading();
        if (stripped.isBlank() || stripped.startsWith("#")) {
            return;
        }
        int indent = line.length() - stripped.length();
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
