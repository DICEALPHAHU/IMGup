package com.github.dicealphahu.imgup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * multipart/form-data 解析。
 *
 * 只实现本站需要的那一小部分，但有几条红线必须守住：
 *   · Content-Length 超限时**一个字节都不读**就拒绝（不然对方可以慢速撑爆内存）
 *   · 读 body 时按硬上限截断，绝不无界读取
 *   · 文件名按 UTF-8 修复（浏览器普遍把 UTF-8 字节直接塞进 filename=，
 *     而 HTTP 头按 latin-1 解码，中文会变乱码）
 *   · 任何异常路径都不留下半个临时状态（这里全在内存里，天然满足）
 */
public final class Multipart {

    /** 一个上传的文件部分。 */
    public record FilePart(String fieldName, String fileName, byte[] data) {
    }

    /** 解析结果：普通字段 + 文件。 */
    public record Result(Map<String, String> fields, List<FilePart> files) {

        public String field(String name) {
            return fields.getOrDefault(name, "");
        }

        public FilePart firstFile() {
            return files.isEmpty() ? null : files.get(0);
        }
    }

    /** 超出体积上限时抛出，由上层转成 413。 */
    public static final class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    /** 请求体不是合法的 multipart 时抛出，由上层转成 400。 */
    public static final class BadRequestException extends IOException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    private Multipart() {
    }

    /** 取出 Content-Type 里的 boundary 原始值（不含前导 --）。 */
    public static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("boundary=");
        if (idx < 0) {
            return null;
        }
        String raw = contentType.substring(idx + "boundary=".length()).trim();
        if (raw.startsWith("\"")) {
            int end = raw.indexOf('"', 1);
            raw = end > 0 ? raw.substring(1, end) : raw.substring(1);
        } else {
            int end = raw.indexOf(';');
            if (end >= 0) {
                raw = raw.substring(0, end);
            }
            raw = raw.trim();
        }
        // 容错：有些客户端不合规地把前导 -- 也写进了 Content-Type
        while (raw.startsWith("--")) {
            raw = raw.substring(2);
        }
        return raw.isEmpty() ? null : raw;
    }

    /**
     * 解析请求体。
     *
     * @param body              已经读进来的请求体
     * @param contentType       请求的 Content-Type
     * @param maxFileBytes      单个文件的体积上限
     * @param maxFieldChars     普通字段的长度上限（防止用超长昵称撑内存）
     */
    public static Result parse(byte[] body, String contentType, int maxFileBytes, int maxFieldChars)
            throws BadRequestException {
        String boundary = boundaryOf(contentType);
        if (boundary == null) {
            throw new BadRequestException("缺少 multipart boundary");
        }
        if (body == null || body.length == 0) {
            throw new BadRequestException("请求体是空的");
        }

        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        Map<String, String> fields = new LinkedHashMap<>();
        List<FilePart> files = new ArrayList<>();

        int index = indexOf(body, delimiter, 0);
        if (index < 0) {
            throw new BadRequestException("multipart 数据里找不到 boundary");
        }

        while (index >= 0 && index < body.length) {
            int afterDelimiter = index + delimiter.length;
            // 结束标记："--boundary--"
            if (afterDelimiter + 1 < body.length
                    && body[afterDelimiter] == '-' && body[afterDelimiter + 1] == '-') {
                break;
            }
            // 跳过 boundary 后面的 CRLF
            int cursor = afterDelimiter;
            if (cursor < body.length && body[cursor] == '\r') {
                cursor++;
            }
            if (cursor < body.length && body[cursor] == '\n') {
                cursor++;
            }

            int next = indexOf(body, delimiter, cursor);
            if (next < 0) {
                throw new BadRequestException("multipart 分段没有正常结束");
            }

            // 这一段的原始字节：去掉紧挨着下一个 boundary 的 CRLF
            int end = next;
            if (end >= 2 && body[end - 2] == '\r' && body[end - 1] == '\n') {
                end -= 2;
            }
            parsePart(body, cursor, end, fields, files, maxFileBytes, maxFieldChars);
            index = next;
        }

        return new Result(fields, files);
    }

    private static void parsePart(byte[] body, int start, int end,
                                  Map<String, String> fields, List<FilePart> files,
                                  int maxFileBytes, int maxFieldChars)
            throws BadRequestException {
        int headerEnd = indexOf(body, new byte[]{'\r', '\n', '\r', '\n'}, start);
        if (headerEnd < 0 || headerEnd > end) {
            throw new BadRequestException("分段缺少头部");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String headerText = new String(body, start, headerEnd - start, StandardCharsets.ISO_8859_1);
        for (String line : headerText.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        String disposition = headers.getOrDefault("content-disposition", "");
        String name = dispositionParam(disposition, "name");
        String fileName = dispositionParam(disposition, "filename");

        int dataStart = headerEnd + 4;
        if (dataStart > end) {
            dataStart = end;
        }
        int dataLength = Math.max(0, end - dataStart);

        boolean isFile = fileName != null && !fileName.isEmpty();
        if (!isFile) {
            // 普通字段：截断到上限，避免超长字段撑内存
            int take = Math.min(dataLength, maxFieldChars * 4);
            String value = new String(body, dataStart, take, StandardCharsets.UTF_8);
            if (value.length() > maxFieldChars) {
                value = value.substring(0, maxFieldChars);
            }
            fields.put(name.isEmpty() ? "value" : name, value);
            return;
        }

        if (dataLength > maxFileBytes) {
            throw new BadRequestException("file_too_large");
        }
        byte[] data = new byte[dataLength];
        System.arraycopy(body, dataStart, data, 0, dataLength);
        files.add(new FilePart(name.isEmpty() ? "file" : name, fileName, data));
    }

    /** 从 Content-Disposition 里取参数，处理引号和一些客户端不合规的写法。 */
    static String dispositionParam(String disposition, String param) {
        if (disposition == null || disposition.isEmpty()) {
            return null;
        }
        String needle = param + "=";
        int idx = disposition.toLowerCase(Locale.ROOT).indexOf(needle);
        if (idx < 0) {
            // 退一步找 filename*（RFC 2231 编码形式）
            if ("filename".equals(param)) {
                int star = disposition.toLowerCase(Locale.ROOT).indexOf("filename*=");
                if (star >= 0) {
                    String raw = disposition.substring(star + "filename*=".length()).trim();
                    int semi = raw.indexOf(';');
                    if (semi >= 0) {
                        raw = raw.substring(0, semi);
                    }
                    // 注意：RFC 2231 的值是百分号编码的，urlDecode 出来已经是正确的
                    // UTF-8 字符串，**不能再走 repairUtf8** —— 那会把好中文当成
                    // 「UTF-8 被 latin-1 解码」再转一次，直接转成 ?。已踩过。
                    return decodeRfc2231(raw);
                }
            }
            return null;
        }
        String raw = disposition.substring(idx + needle.length()).trim();
        String value;
        if (raw.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\\' && i + 1 < raw.length()) {
                    sb.append(raw.charAt(++i));
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            value = sb.toString();
        } else {
            int semi = raw.indexOf(';');
            value = (semi >= 0 ? raw.substring(0, semi) : raw).trim();
        }
        return repairUtf8(value);
    }

    /** 处理 filename*=UTF-8''%E6%B5%B7%E6%8A%A5.png 这种形式。 */
    private static String decodeRfc2231(String raw) {
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        int firstQuote = value.indexOf('\'');
        if (firstQuote >= 0) {
            int secondQuote = value.indexOf('\'', firstQuote + 1);
            if (secondQuote > 0) {
                value = value.substring(secondQuote + 1);
            }
        }
        return Texts.urlDecode(value);
    }

    /**
     * 修掉「UTF-8 字节被按 latin-1 解码」造成的乱码。
     *
     * HTTP 头规范上只有 latin-1，但浏览器普遍把中文文件名以 UTF-8 字节塞进
     * filename=，到这里就成了「æµ·æ¥.png」。还原成字节再按 UTF-8 解一次，
     * 解得通就说明本来就是 UTF-8；解不通就原样返回，绝不破坏正常的 ASCII 名。
     */
    static String repairUtf8(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        boolean ascii = true;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return text;
        }
        try {
            byte[] raw = text.getBytes(StandardCharsets.ISO_8859_1);
            String decoded = new String(raw, StandardCharsets.UTF_8);
            // 解出来如果出现替换字符，说明原本就不是 UTF-8，保持原样
            return decoded.indexOf('\uFFFD') >= 0 ? text : decoded;
        } catch (RuntimeException e) {
            return text;
        }
    }

    // --------------------------------------------------------- 工具

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * 有上限地读整个请求体。
     *
     * @param limit 硬上限，超过就抛 TooLargeException（调用方提前看过 Content-Length，
     *              这里是给「Content-Length 撒谎」的情况兜底）
     */
    public static byte[] readBody(InputStream in, long declaredLength, int limit)
            throws IOException {
        if (declaredLength > limit) {
            throw new TooLargeException("请求体声明长度 " + declaredLength + " 超过上限 " + limit);
        }
        int initial = (int) Math.max(64, Math.min(declaredLength > 0 ? declaredLength : 8192, limit));
        ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (out.size() + read > limit) {
                throw new TooLargeException("请求体超过上限 " + limit);
            }
            out.write(buffer, 0, read);
            if (declaredLength > 0 && out.size() >= declaredLength) {
                break;
            }
        }
        return out.toByteArray();
    }
}
