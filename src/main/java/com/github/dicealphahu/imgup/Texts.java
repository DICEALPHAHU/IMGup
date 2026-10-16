package com.github.dicealphahu.imgup;

import java.nio.charset.StandardCharsets;
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
