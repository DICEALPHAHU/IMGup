package com.github.dicealphahu.imgup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 文本处理小工具：URL 编码、HTML 转义、文件名净化。 */
public final class Texts {

    /** 文件名里绝对不能出现的字符（和 Images 插件的校验一致）。 */
    private static final Pattern ILLEGAL_IN_NAME = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");

    private static final Pattern DASHES = Pattern.compile("-{2,}");

    private static final String HEX = "0123456789ABCDEF";

    private Texts() {
    }

    // ------------------------------------------------------- URL 编码

    /** 按路径段编码（中文文件名要编码，否则 http 客户端和浏览器行为不一致）。 */
    public static String encodePathSegment(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0x0F));
            }
        }
        return sb.toString();
    }

    /** 解析 URL 里的百分号编码（表单字段值、查询参数都用它）。 */
    public static String urlDecode(String value) {
        if (value == null || value.indexOf('%') < 0 && value.indexOf('+') < 0) {
            return value == null ? "" : value;
        }
        byte[] out = new byte[value.length()];
        int len = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '+') {
                out[len++] = ' ';
            } else if (c == '%' && i + 2 < value.length()) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out[len++] = (byte) ((hi << 4) + lo);
                    i += 2;
                } else {
                    out[len++] = (byte) c;
                }
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : encoded) {
                    if (len < out.length) {
                        out[len++] = b;
                    }
                }
            }
        }
        return new String(out, 0, len, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------ HTML 转义

    /** 把文本放进 HTML 里时必须转义，否则昵称里的 &lt; 会破坏页面。 */
    public static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 网页用的文案渲染：先转义，再把反引号包起来的部分变成 {@code <code>}。
     *
     * 语言文件里这样写：
     *     游戏内贴海报需要 `/image create` 权限
     * 页面上就是：
     *     游戏内贴海报需要 &lt;code&gt;/image create&lt;/code&gt; 权限
     *
     * 顺序很关键 —— **先转义再插标签**。反着来的话，语言文件里写个 &lt;script&gt;
     * 就会被当成真标签执行（语言文件是管理员能改的，不能信）。
     * 转义之后反引号本身不受影响，所以这一步只会插入 code 标签。
     *
     * 没配对的单个反引号原样留着，不会把后面整段吃掉。
     */
    public static String inlineHtml(String value) {
        if (value == null) {
            return "";
        }
        String escaped = escapeHtml(value);
        StringBuilder out = new StringBuilder(escaped.length() + 16);
        int i = 0;
        while (i < escaped.length()) {
            int open = escaped.indexOf('`', i);
            if (open < 0) {
                out.append(escaped, i, escaped.length());
                break;
            }
            int close = escaped.indexOf('`', open + 1);
            if (close < 0) {
                out.append(escaped, i, escaped.length());
                break;
            }
            out.append(escaped, i, open)
               .append("<code>").append(escaped, open + 1, close).append("</code>");
            i = close + 1;
        }
        return out.toString();
    }

    /** 纯文本上下文（游戏内聊天）用：反引号只是标记，去掉即可。 */
    public static String stripBackticks(String value) {
        return value == null ? "" : value.replace("`", "");
    }

    /** 放进 HTML 属性里的 JSON（把双引号转成实体，避免提前闭合属性）。 */
    public static String escapeHtmlAttr(String value) {
        return escapeHtml(value);
    }

    // ------------------------------------------------------- 文件名

    /**
     * 把玩家填的昵称净化成能安全放进文件名的形式。
     *
     * 刻意保留中文：玩家填「我的海报」就该得到「玩家-我的海报.png」，
     * 只挡掉真正会出问题的字符（路径分隔符、控制字符等）。
     */
    public static String sanitizeNickname(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String out = ILLEGAL_IN_NAME.matcher(text.trim()).replaceAll("-");
        out = DASHES.matcher(out).replaceAll("-");
        out = trimEdges(out);
        if (out.length() > maxLength) {
            out = trimEdges(out.substring(0, maxLength));
        }
        return out;
    }

    /** 默认昵称 = 上传文件名去掉扩展名。 */
    public static String defaultNickname(String originalFileName) {
        if (originalFileName == null) {
            return "image";
        }
        String name = originalFileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.trim();
        return name.isEmpty() ? "image" : name;
    }

    /**
     * 文件名是否安全。和 Images 插件的 findImageFile 校验保持一致：
     * 不能以 . 开头、不能含 ..、不能含路径分隔符、要有扩展名。
     */
    public static boolean isSafeFileName(String name, int maxNameLength) {
        if (name == null || name.isEmpty() || name.length() > maxNameLength) {
            return false;
        }
        if (name.startsWith(".") || name.contains("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || c < 0x20) {
                return false;
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg");
    }

    /**
     * 拒绝原因是不是「语言文件的键」。
     *
     * 管理员手写的原因是自由文字，原样显示。但自动作废这类由插件自己产生的原因
     * 需要按查看者的语言显示，所以存成 "@site.expired_reason" 这种形式，显示时再翻。
     */
    public static boolean isReasonKey(String reason) {
        return reason != null && reason.length() > 1 && reason.charAt(0) == '@';
    }

    // ------------------------------------------------------- 文件名模板

    /** 命名模板支持的变量。 */
    public static final String[] NAME_PATTERN_VARS =
            {"player", "nickname", "original", "date", "time"};

    /** 模板里是否至少含一个已知变量。写成 "{nicknam}" 这种纯字面量就没意义了。 */
    public static boolean hasNamePatternVar(String pattern) {
        if (pattern == null) {
            return false;
        }
        for (String v : NAME_PATTERN_VARS) {
            if (pattern.contains("{" + v + "}")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNamePatternVar(String name) {
        for (String v : NAME_PATTERN_VARS) {
            if (v.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用命名模板生成文件名主体（不含扩展名）。
     *
     * 未知变量按空串处理 —— 写错成 {nicknam} 时宁可少一段，也不要让文件名里留下一对花括号。
     */
    public static String applyNamePattern(String pattern, String player, String nickname,
                                          String original, long epochMillis) {
        if (pattern == null || pattern.isEmpty()) {
            return "";
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault());
        String out = pattern;
        out = out.replace("{player}", player == null ? "" : player);
        out = out.replace("{nickname}", nickname == null ? "" : nickname);
        out = out.replace("{original}", original == null ? "" : original);
        out = out.replace("{date}",
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(now));
        out = out.replace("{time}",
                java.time.format.DateTimeFormatter.ofPattern("HHmmss").format(now));
        return out.replaceAll("\\{[^{}]*\\}", "");
    }

    /**
     * 用命名模板从文件名主体（不含扩展名）反解各字段。
     *
     * player 限定成合法 Minecraft 用户名，其余变量按「非贪婪但非空」匹配，
     * 所以 {player}-{nickname} 遇到 Steve-a-b 会得到 player=Steve、nickname=a-b。
     * 解不出来返回空 Map，由调用方兜底。
     */
    public static Map<String, String> parseNamePattern(String pattern, String baseName) {
        Map<String, String> out = new LinkedHashMap<>();
        if (pattern == null || baseName == null || baseName.isEmpty()) {
            return out;
        }
        StringBuilder regex = new StringBuilder("^");
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < pattern.length()) {
            int open = pattern.indexOf('{', i);
            int close = open < 0 ? -1 : pattern.indexOf('}', open);
            if (open < 0 || close < 0) {
                regex.append(Pattern.quote(pattern.substring(i)));
                break;
            }
            regex.append(Pattern.quote(pattern.substring(i, open)));
            String var = pattern.substring(open + 1, close).trim();
            if (isNamePatternVar(var)) {
                regex.append("player".equals(var) ? "([A-Za-z0-9_]{1,16})" : "(.+?)");
                names.add(var);
            }
            i = close + 1;
        }
        regex.append('$');
        Matcher m = Pattern.compile(regex.toString()).matcher(baseName);
        if (!m.matches()) {
            return out;
        }
        for (int g = 0; g < names.size(); g++) {
            out.putIfAbsent(names.get(g), m.group(g + 1));
        }
        return out;
    }

    /**
     * 从「玩家-图片名.png」里反解出玩家名，解不出来返回空串。
     *
     * 用来补登记 ImgUp 之前就存在的图片 —— 那时没有上传记录，只能从文件名反推。
     *
     * 只认合法的 Minecraft 用户名（字母数字下划线、1-16 位）。宁可留空也不能瞎猜：
     * 「我的海报-最终版.png」第一个连字符左边是「我的海报」，那不是玩家名。
     */
    public static String playerFromFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dash = fileName.indexOf('-');
        if (dash <= 0 || dash > 16) {
            return "";
        }
        String player = fileName.substring(0, dash);
        for (int i = 0; i < player.length(); i++) {
            char c = player.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return "";
            }
        }
        return player;
    }

    /**
     * 从「玩家-图片名.png」里取出图片名（去掉玩家前缀和扩展名）。
     * 没有玩家前缀时就等于「文件名去扩展名」。
     */
    public static String nicknameFromFileName(String fileName) {
        String base = defaultNickname(fileName);
        String player = playerFromFileName(fileName);
        if (!player.isEmpty()) {
            base = base.substring(player.length() + 1);
        }
        base = base.trim();
        return base.isEmpty() ? defaultNickname(fileName) : base;
    }

    private static String trimEdges(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isEdge(value.charAt(start))) {
            start++;
        }
        while (end > start && isEdge(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isEdge(char c) {
        return c == ' ' || c == '.' || c == '-' || c == '_';
    }
}
