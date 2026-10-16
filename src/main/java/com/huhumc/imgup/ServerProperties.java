package com.huhumc.imgup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读 server.properties。
 *
 * 页脚要显示服务器自己的 motd 和服务器名，站长是从 server.properties 里配的，
 * 所以插件得去那里取。
 *
 * 为什么不直接用 Bukkit 的 API：
 *   · getMotd() 能拿到 motd，但服务器名没有对应 API —— getServer().getName()
 *     返回的是**服务端实现名**（Purpur / CraftBukkit），
 *     用它当「服务器名称」会印出 "Copyright 2026 Purpur" 这种东西（真踩过）
 *   · 自己读文件，两个值都是站长真正配的那个
 *
 * 这个类刻意不依赖 Bukkit，所以能脱离服务端单独测试。
 */
public final class ServerProperties {

    private ServerProperties() {
    }

    /**
     * 解析一个 server.properties 文件。
     *
     * 容错要求（真实文件里都会遇到）：
     *   · 注释行（# 开头）和空行跳过
     *   · 键和值两侧的空格去掉
     *   · 值里可以含 =（只用第一个 = 分割）
     *   · Windows 换行（\r\n）
     *   · 文件不存在 → 返回空表，不抛异常（页脚留空即可，不影响上传）
     *
     * @param file 文件路径，可以为 null
     */
    public static Map<String, String> read(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    out.put(key, value);
                }
            }
        } catch (IOException e) {
            // 读不到就当没有：页脚那两句会变成空，功能不受影响
            return new LinkedHashMap<>();
        }
        return out;
    }

    /**
     * 取一个键，优先用 JVM 系统属性。
     *
     * 用命令行参数启动（--motd 之类）时，Bukkit 会把它们设成系统属性，
     * 那种情况下文件里的值可能不是生效值，所以系统属性优先。
     */
    public static String get(Path file, String key) {
        String fromSystem = System.getProperty(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem.trim();
        }
        return read(file).getOrDefault(key, "");
    }
}
