package com.huhumc.imgup;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 多语言文案库。
 *
 * 一个语言一个文件，放在 plugins/ImgUp/messages/ 下：
 *
 *   messages/zh.yml   ← 中文
 *   messages/en.yml   ← 英文
 *   messages/jp.yml   ← 想加就加，把值换成日文即可，网页语言开关会自动多出一项
 *
 * 文件里是「键: 值」，各语言文件的键必须一一对应；某个键在某个语言里缺了，
 * 就回退到 config.yml 里 language 指定的默认语言。所以英文文件没写全也能用。
 *
 * 插件启动时会把 jar 里自带的 zh.yml / en.yml 释放到该目录（已存在就不覆盖），
 * 所以站长拿到的是带注释的可编辑文件，而不是埋在 jar 里的字符串。
 *
 * 页面上显示什么、报错说什么，全部来自这里 —— 想改文案不用碰代码也不用碰 HTML。
 */
public final class Messages {

    /** 语言代码 -> (键 -> 文案)。 */
    private final Map<String, Map<String, String>> tables = new LinkedHashMap<>();

    /** config.yml 里指定的默认语言。 */
    private final String defaultLang;

    private Path dir;
    private Path dirOverride;

    public Messages(Path dir, String defaultLang) {
        this.dir = dir;
        this.defaultLang = defaultLang == null || defaultLang.isBlank()
                ? "zh" : defaultLang.trim().toLowerCase(Locale.ROOT);
    }

    public Path dir() {
        return dir;
    }

    public String defaultLang() {
        return defaultLang;
    }

    /**
     * 把 jar 里自带的语言文件释放到 plugins/ImgUp/messages/。
     *
     * 用 saveResource 而不是自己写文件：它会自己从 jar 里读、已存在则跳过，
     * 站长改过的文件不会被覆盖。
     */
    public static void extractDefaults(Logger log, java.util.function.Consumer<String> saveResource) {
        for (String lang : new String[]{"zh", "en"}) {
            String resource = "messages/" + lang + ".yml";
            try {
                saveResource.accept(resource);
            } catch (RuntimeException e) {
                // 释放失败不算致命：下面 load 会把真实错误报出来
                log.log(Level.FINE, "释放默认语言文件失败 " + resource, e);
            }
        }
    }

    /** 插件自带语言文件的语言代码。这几个文件升级时会自动补齐新键。 */
    private static final String[] BUILT_IN = {"zh", "en"};

    /**
     * 把 jar 里**新增**的键补进磁盘上的语言文件。
     *
     * 为什么需要这个：升级插件时，站长磁盘上的 messages/zh.yml 是他改过的旧版本，
     * 插件新加的键（比如 game.* 那一批）不在里面。不补的话，玩家聊天框里就会直接
     * 显示 game.usage_header 这种键名 —— 对「发给别人用」的插件这不可接受。
     *
     * 只**追加**缺失的键，绝不覆盖站长已有的值，所以他的翻译不会被冲掉。
     *
     * @param bundledResource 取 jar 内资源的函数（传资源路径，返回文本或 null）
     * @return 一共补了多少个键
     */
    public int mergeDefaults(Logger log, java.util.function.Function<String, String> bundledResource) {
        int total = 0;
        for (String lang : BUILT_IN) {
            String bundled = bundledResource.apply("messages/" + lang + ".yml");
            if (bundled == null || bundled.isBlank()) {
                continue;
            }
            Path file = dir.resolve(lang + ".yml");
            if (!Files.isRegularFile(file)) {
                continue;
            }

            Map<String, String> fresh = parse(bundled);
            java.util.Set<String> existing = parse(readFile(file)).keySet();
            List<String> missing = new ArrayList<>();
            for (String key : fresh.keySet()) {
                if (!existing.contains(key)) {
                    missing.add(key);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }

            StringBuilder add = new StringBuilder();
            add.append('\n')
               .append("# ---------------------------------------------------------------- ")
               .append("插件升级新增的文案 / added by a plugin upgrade --\n")
               .append("# 这些键在你升级前不存在，插件自动补上了。\n")
               .append("# These keys did not exist before the upgrade and were added automatically.\n");
            for (String key : missing) {
                add.append(key).append(": ").append(quote(fresh.get(key))).append('\n');
            }
            try {
                Files.writeString(file, readFile(file) + add, StandardCharsets.UTF_8);
                total += missing.size();
                log.info("语言文件 " + lang + ".yml 补上了 " + missing.size()
                        + " 个新键（升级新增，未改动你原有的翻译）");
            } catch (IOException e) {
                log.log(Level.WARNING, "无法补充语言文件 " + file, e);
            }
        }
        return total;
    }

    /** 解析一段 YAML 文本，取所有叶子键值。 */
    private static Map<String, String> parse(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) {
                out.put(key, yaml.getString(key, ""));
            }
        }
        return out;
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 按 YAML 双引号字符串转义。 */
    private static String quote(String value) {
        return '"' + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * 读取目录下所有 *.yml。
     *
     * @return 成功读取的语言数
     */
    public int load(Logger log) {
        if (dirOverride != null) {
            this.dir = dirOverride;
        }
        tables.clear();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.log(Level.SEVERE, "无法创建语言文件目录 " + dir, e);
            return 0;
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            log.log(Level.SEVERE, "无法列出语言文件目录 " + dir, e);
            return 0;
        }

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String lang = fileName.substring(0, fileName.length() - 4)
                    .toLowerCase(Locale.ROOT);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            Map<String, String> table = new LinkedHashMap<>();
            for (String key : yaml.getKeys(true)) {
                // 只收叶子节点（isString 对带子节点的节返回 false，正好过滤掉）
                if (!yaml.isString(key)) {
                    continue;
                }
                String value = yaml.getString(key, "");
                if (value != null) {
                    table.put(key, value);
                }
            }
            if (table.isEmpty()) {
                log.warning("语言文件 " + fileName + " 里没有任何文案，已跳过");
                continue;
            }
            tables.put(lang, table);
        }

        if (tables.isEmpty()) {
            log.severe("messages/ 下一个可用的语言文件都没有！页面会显示键名而不是文案。");
            return 0;
        }
        if (!tables.containsKey(defaultLang)) {
            String fallback = tables.keySet().iterator().next();
            log.warning("config.yml 里的 language: " + defaultLang + " 找不到对应的语言文件，"
                    + "改用 " + fallback + "（现有：" + String.join(", ", tables.keySet()) + "）");
        }
        log.info("已加载 " + tables.size() + " 个语言：" + String.join(", ", tables.keySet())
                + "（默认 " + defaultLang + "）");
        return tables.size();
    }

    /**
     * 测试用：从 classpath 的 messages/ 目录读出语言文件，再按正式流程加载。
     *
     * 正式运行时是插件把 jar 里的文件释放到磁盘再读；这条路径让集成测试
     * 不用真的起一个 Minecraft 服务端也能验证同一套解析逻辑。
     */
    public void loadTestFixtures(Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            for (String lang : new String[]{"zh", "en"}) {
                byte[] data = Resources.read("messages/" + lang + ".yml");
                if (data != null) {
                    Files.write(targetDir.resolve(lang + ".yml"), data);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("准备测试语言文件失败：" + e.getMessage(), e);
        }
        this.dirOverride = targetDir;
        load(Logger.getLogger("imgup-test"));
    }

    /**
     * 按玩家客户端的语言挑一门文案。
     *
     * Minecraft 客户端给的是 {@code 语言_国家} 形式，例如 {@code zh_cn}、
     * {@code en_us}、{@code zh_tw}。这里只取语言部分去找 messages/ 下的文件；
     * 找不到（比如玩家用日语，但站长没放 jp.yml）就回退到默认语言，
     * 所以永远不会出现「没有文案」的情况。
     *
     * @param clientLocale 玩家的客户端语言，可以为 null
     */
    public String forClientLocale(String clientLocale) {
        if (clientLocale == null || clientLocale.isBlank()) {
            return defaultLang;
        }
        String value = clientLocale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        // 先试整串（站长可能真的放了 zh_cn.yml），再试语言部分
        if (has(value)) {
            return value;
        }
        int underscore = value.indexOf('_');
        String language = underscore > 0 ? value.substring(0, underscore) : value;
        if (has(language)) {
            return language;
        }
        // 玩家用了一门站长没有的语言：给默认语言，别给键名
        return defaultLang;
    }

    /**
     * 某门语言的显示名 —— 取自该语言文件里的 meta.name，写的是这门语言自己的叫法
     * （简体中文对应「中文（简体）」，英文对应「English」）。
     */
    public String name(String lang) {
        String value = lookup(lang, "meta.name");
        return value == null || value.isBlank()
                ? (lang == null ? "" : lang.toUpperCase(java.util.Locale.ROOT))
                : value;
    }

    /** 有哪些语言可用，用于网页上的语言开关。 */
    public List<String> languages() {
        return new ArrayList<>(tables.keySet());
    }

    public boolean has(String lang) {
        return lang != null && tables.containsKey(lang.toLowerCase(Locale.ROOT));
    }

    /**
     * 取文案。
     *
     * 找不到时按「指定语言 -> 默认语言 -> 任意语言 -> 键名」逐级回退，
     * 保证页面上永远不会出现空白。
     */
    public String get(String lang, String key) {
        // 没给替换值的占位符直接去掉 —— 否则聊天框里会露出 {name} 这种东西。
        // 注意：两参版也必须做这件事。Java 会优先选它而不是下面的 varargs 版本，
        // 只在 varargs 版里剥离会导致 get(lang, key) 漏掉处理（踩过这个坑）。
        return stripPlaceholders(raw(lang, key));
    }

    /** 原样返回，不做占位符处理。 */
    public String raw(String lang, String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String value = lookup(lang, key);
        if (value == null) {
            value = lookup(defaultLang, key);
        }
        if (value == null) {
            for (Map<String, String> table : tables.values()) {
                value = table.get(key);
                if (value != null) {
                    break;
                }
            }
        }
        return value == null ? key : value;
    }

    /** 把 {xxx} 形式、但没有提供值的占位符去掉。 */
    public static String stripPlaceholders(String text) {
        return text == null ? "" : text.replaceAll("\\{[a-zA-Z_][a-zA-Z0-9_]*\\}", "");
    }

    /**
     * 取文案并替换 {名字} 占位符。
     *
     * 页脚那两句要嵌 server.properties 里的 motd 和服务器名，所以文案里写
     * {motd} / {server}，运行时把实时值填进去。
     */
    public String get(String lang, String key, String... pairs) {
        String text = raw(lang, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return stripPlaceholders(text);
    }

    private String lookup(String lang, String key) {
        if (lang == null) {
            return null;
        }
        Map<String, String> table = tables.get(lang.toLowerCase(Locale.ROOT));
        return table == null ? null : table.get(key);
    }

    /**
     * 某个语言的完整文案表，注入到网页里给前端用。
     *
     * 前端拿到它就不用把文案再抄一份到 JS 里 —— 语言文件是唯一来源。
     */
    public Map<String, String> table(String lang) {
        Map<String, String> table = tables.get(lang == null ? "" : lang.toLowerCase(Locale.ROOT));
        if (table != null) {
            return table;
        }
        table = tables.get(defaultLang);
        return table == null ? Map.of() : table;
    }

    /** 检查各语言文件的键是否一致，把缺失的报给站长（不阻断启动）。 */
    public List<String> missingKeys(Logger log) {
        List<String> problems = new ArrayList<>();
        Map<String, String> base = tables.get(defaultLang);
        if (base == null) {
            return problems;
        }
        for (Map.Entry<String, Map<String, String>> entry : tables.entrySet()) {
            if (entry.getKey().equals(defaultLang)) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String key : base.keySet()) {
                if (!entry.getValue().containsKey(key)) {
                    missing.add(key);
                }
            }
            if (!missing.isEmpty()) {
                String note = "语言 " + entry.getKey() + " 缺 " + missing.size() + " 个键"
                        + "（会回退到 " + defaultLang + "）：" + String.join(", ",
                        missing.subList(0, Math.min(8, missing.size())))
                        + (missing.size() > 8 ? " …" : "");
                problems.add(note);
                log.info(note);
            }
        }
        return problems;
    }

    /** 给调试用：某个键在所有语言下的值。 */
    public Map<String, String> allLanguages(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String lang : tables.keySet()) {
            out.put(lang, get(lang, key));
        }
        return out;
    }

    /**
     * 语言代码是否合法（只允许字母数字和连字符），防止拿它去拼路径。
     */
    public static boolean isValidLang(String lang) {
        if (lang == null || lang.isEmpty() || lang.length() > 16) {
            return false;
        }
        for (int i = 0; i < lang.length(); i++) {
            char c = lang.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 把语言文件写回磁盘的编码固定为 UTF-8，方便站长用记事本/VS Code 编辑。 */
    public static void writeUtf8(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }
}
