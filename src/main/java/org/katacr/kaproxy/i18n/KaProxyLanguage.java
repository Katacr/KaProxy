package org.katacr.kaproxy.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** 从独立 lang 文件夹加载 KaProxy 日志与命令文本，并完成占位符替换。 */
public final class KaProxyLanguage {
    private final Map<String, String> values;

    /** 创建只读语言快照。 */
    private KaProxyLanguage(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /** 释放内置中英文语言文件，并加载配置选定的语言。 */
    public static KaProxyLanguage load(Path dataDirectory, String languageName,
                                       ResourceProvider resources) throws IOException {
        Path languageDirectory = dataDirectory.resolve("lang");
        Files.createDirectories(languageDirectory);
        copyDefault(languageDirectory.resolve("zh_CN.yml"), resources.open("lang/zh_CN.yml"));
        copyDefault(languageDirectory.resolve("en_US.yml"), resources.open("lang/en_US.yml"));
        Path selected = languageDirectory.resolve(languageName + ".yml");
        if (Files.notExists(selected)) {
            selected = languageDirectory.resolve("zh_CN.yml");
        }
        Map<String, String> values = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(selected, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String stripped = line.strip();
                if (stripped.isBlank() || stripped.startsWith("#")) {
                    continue;
                }
                int separator = stripped.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String key = stripped.substring(0, separator).trim();
                String value = stripped.substring(separator + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        }
        return new KaProxyLanguage(values);
    }

    /** 返回完成占位符替换的语言文本。 */
    public String text(String key, Map<String, String> replacements) {
        String value = values.getOrDefault(key, "Missing language key: " + key);
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            value = value.replace("{" + replacement.getKey() + "}", replacement.getValue());
        }
        return value;
    }

    /** 返回不含占位符的语言文本。 */
    public String text(String key) {
        return text(key, Map.of());
    }

    /** 文件不存在时复制一个内置语言资源。 */
    private static void copyDefault(Path destination, InputStream source) throws IOException {
        if (Files.exists(destination)) {
            if (source != null) {
                source.close();
            }
            return;
        }
        if (source == null) {
            throw new IOException("插件资源缺少 " + destination.getFileName());
        }
        try (source) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 抽象类加载器资源读取，供双平台代理入口共用。 */
    @FunctionalInterface
    public interface ResourceProvider {
        /** 打开指定插件资源。 */
        InputStream open(String path);
    }
}
