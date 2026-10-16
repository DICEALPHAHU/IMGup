import com.huhumc.imgup.ImageTools;
import com.huhumc.imgup.ServerProperties;
import com.huhumc.imgup.Json;
import com.huhumc.imgup.Multipart;
import com.huhumc.imgup.Sessions;
import com.huhumc.imgup.Texts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 不依赖 Paper 的核心逻辑自测：multipart 解析、JSON、文件名、图片处理、会话。
 *
 * 这些恰恰是最容易出 bug 的地方（multipart 解析在 Python 版就栽过跟头），
 * 所以用真实字节跑，而不是"看着像对"。
 *
 * 运行：javac 编译后 java CoreSelfTest
 */
public final class CoreSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testBoundaryParsing();
        testMultipartBasic();
        testMultipartUtf8Filename();
        testMultipartRfc2231Filename();
        testMultipartMissingBoundary();
        testMultipartBinaryIntegrity();
        testMultipartFieldTruncation();
        testJsonRoundTrip();
        testJsonParse();
        testSanitizeNickname();
        testDefaultNickname();
        testSafeFileName();
        testUrlEncode();
        testHtmlEscape();
        testPngRoundTrip();
        testJpegRoundTrip();
        testGifRejected();
        testOversizePixelsRejected();
        testNotAnImageRejected();
        testNoUpscale();
        testSessionsConfirmFlow();
        testServerProperties();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 断言

    private static void check(String label, boolean ok) {
        check(label, ok, "");
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label + (detail.isEmpty() ? "" : "  — " + detail));
        }
    }

    private static void checkEquals(String label, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(label, ok, "期望 " + expected + "，实际 " + actual);
    }

    // ------------------------------------------------------- multipart

    private static byte[] multipart(String boundary, String fileName, byte[] content,
                                    String nickname) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n";
        if (nickname != null) {
            write(out, head
                    + "Content-Disposition: form-data; name=\"nickname\"\r\n\r\n"
                    + nickname + "\r\n");
        }
        write(out, head
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n");
        out.writeBytes(content);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 按 ISO-8859-1 写：模拟 HTTP 头里那些"原始字节"。 */
    private static void writeLatin1(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void testBoundaryParsing() {
        System.out.println("\n== boundary 解析 ==");
        checkEquals("普通 boundary",
                "imgupTest123", Multipart.boundaryOf("multipart/form-data; boundary=imgupTest123"));
        checkEquals("带引号",
                "abc", Multipart.boundaryOf("multipart/form-data; boundary=\"abc\""));
        checkEquals("大小写不敏感",
                "xyz", Multipart.boundaryOf("multipart/form-data; Boundary=xyz"));
        checkEquals("容错：客户端多写了 --",
                "abc", Multipart.boundaryOf("multipart/form-data; boundary=--abc"));
        checkEquals("带其他参数",
                "abc", Multipart.boundaryOf("multipart/form-data; charset=utf-8; boundary=abc"));
        checkEquals("没有 boundary", null, Multipart.boundaryOf("multipart/form-data"));
    }

    private static void testMultipartBasic() throws Exception {
        System.out.println("\n== multipart 基本解析 ==");
        byte[] body = multipart("BOUND1", "photo.png", "PNGDATA-HERE".getBytes(), "我的海报");
        Multipart.Result result = Multipart.parse(body,
                "multipart/form-data; boundary=BOUND1", 1024 * 1024, 200);
        checkEquals("字段 nickname", "我的海报", result.field("nickname"));
        checkEquals("文件数量", 1, result.files().size());
        Multipart.FilePart file = result.firstFile();
        checkEquals("文件名", "photo.png", file.fileName());
        checkEquals("文件内容完整", "PNGDATA-HERE", new String(file.data(), StandardCharsets.UTF_8));
    }

    private static void testMultipartUtf8Filename() throws Exception {
        System.out.println("\n== multipart：浏览器发来的 UTF-8 中文文件名 ==");
        // 关键：浏览器是把「UTF-8 字节」直接塞进 filename= 的，而 HTTP 头规范只有
        // latin-1。所以这里必须用 ISO-8859-1 把 header 写成真实字节，
        // 不能用 UTF-8 写（那样就变成"正确的 UTF-8 header"，测不到修复逻辑）。
        String fileNameInHeader = new String("我的图.png".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLatin1(out, "--B2\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + fileNameInHeader + "\"\r\n\r\n");
        out.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        writeLatin1(out, "\r\n--B2--\r\n");

        Multipart.Result result = Multipart.parse(out.toByteArray(),
                "multipart/form-data; boundary=B2", 1024, 200);
        checkEquals("乱码文件名被修复", "我的图.png", result.firstFile().fileName());
    }

    private static void testMultipartRfc2231Filename() throws Exception {
        System.out.println("\n== multipart：RFC 2231 编码文件名 ==");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--B3\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename*=UTF-8''%E6%B5%B7%E6%8A%A5.png\r\n\r\n");
        out.writeBytes("data".getBytes());
        write(out, "\r\n--B3--\r\n");
        Multipart.Result result = Multipart.parse(out.toByteArray(),
                "multipart/form-data; boundary=B3", 1024, 200);
        checkEquals("filename* 被正确解码", "海报.png", result.firstFile().fileName());
    }

    private static void testMultipartMissingBoundary() {
        System.out.println("\n== multipart：异常输入不能崩 ==");
        try {
            Multipart.parse("garbage".getBytes(), "multipart/form-data; boundary=X", 1024, 100);
            check("读到坏数据要抛 BadRequestException", false, "居然没抛异常");
        } catch (Multipart.BadRequestException e) {
            check("读到坏数据抛 BadRequestException", true);
        } catch (Exception e) {
            check("读到坏数据抛 BadRequestException", false, "抛了 " + e.getClass());
        }
        try {
            Multipart.parse("--X\r\n\r\nabc".getBytes(), "text/plain", 1024, 100);
            check("缺少 boundary 要拒绝", false, "居然没抛异常");
        } catch (Multipart.BadRequestException e) {
            check("缺少 boundary 被拒绝", true);
        } catch (Exception e) {
            check("缺少 boundary 被拒绝", false, "抛了 " + e.getClass());
        }
    }

    private static void testMultipartBinaryIntegrity() throws Exception {
        System.out.println("\n== multipart：二进制内容不能被破坏 ==");
        // 造一段含 CRLF、boundary 前缀片段、零字节的二进制，确认不会被切错
        byte[] tricky = new byte[512];
        for (int i = 0; i < tricky.length; i++) {
            tricky[i] = (byte) (i * 7 % 256);
        }
        tricky[10] = '\r';
        tricky[11] = '\n';
        byte[] body = multipart("B4", "t.bin", tricky, null);
        Multipart.Result result = Multipart.parse(body, "multipart/form-data; boundary=B4",
                1024 * 1024, 200);
        byte[] got = result.firstFile().data();
        checkEquals("长度一致", tricky.length, got.length);
        boolean same = true;
        for (int i = 0; i < tricky.length; i++) {
            if (tricky[i] != got[i]) {
                same = false;
                break;
            }
        }
        check("每个字节都一致", same);
    }

    private static void testMultipartFieldTruncation() throws Exception {
        System.out.println("\n== multipart：超长字段被截断而不是吃光内存 ==");
        String huge = "x".repeat(5000);
        byte[] body = multipart("B5", "a.png", "d".getBytes(), huge);
        Multipart.Result result = Multipart.parse(body, "multipart/form-data; boundary=B5",
                1024 * 1024, 100);
        check("昵称被截断到上限", result.field("nickname").length() <= 100,
                "长度 " + result.field("nickname").length());
    }

    // ------------------------------------------------------------ JSON

    private static void testJsonRoundTrip() {
        System.out.println("\n== JSON 编码 ==");
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        map.put("name", "海报\"带引号\"");
        map.put("n", 42);
        map.put("list", java.util.List.of("a", "b"));
        String text = Json.write(map);
        check("包含转义引号", text.contains("\\\""), text);
        check("中文不被转义成 \\u", text.contains("海报"), text);
        // 用自己写的解析器再读回来，验证两边一致
        Map<String, Object> back = Json.parseObject(text);
        checkEquals("往返后字符串一致", "海报\"带引号\"", back.get("name"));
        checkEquals("往返后数字一致", 42L, back.get("n"));
    }

    private static void testJsonParse() {
        System.out.println("\n== JSON 解析 ==");
        Map<String, Object> map = Json.parseObject(
                "{\"name\":\"A-图.png\",\"nickname\":\"图\",\"nested\":{\"a\":1},\"n\":2.5}");
        checkEquals("普通字段", "A-图.png", Json.string(map, "name"));
        checkEquals("中文值", "图", Json.string(map, "nickname"));
        checkEquals("浮点", 2.5, map.get("n"));
        check("嵌套对象", map.get("nested") instanceof Map);
        checkEquals("空输入安全", "", Json.string(Json.parseObject(""), "x"));
        try {
            Json.parseObject("{bad json");
            check("坏 JSON 要抛异常", false, "居然没抛");
        } catch (IllegalArgumentException e) {
            check("坏 JSON 抛异常", true);
        }
    }

    // ------------------------------------------------------- 文件名

    private static void testSanitizeNickname() {
        System.out.println("\n== 昵称净化 ==");
        checkEquals("保留中文", "我的海报", Texts.sanitizeNickname("我的海报", 32));
        checkEquals("去掉路径分隔符", "etc-passwd",
                Texts.sanitizeNickname("../../etc/passwd", 32));
        check("不含斜杠", !Texts.sanitizeNickname("a/b\\c", 32).contains("/"));
        // 昵称内部允许出现点（"v1..2" 是正常的），'..' 的拦截由 isSafeFileName 兜底 ——
        // 也就是说「玩家-a..b.png」这种整体文件名会被拒，而不是在这里去掉点。
        check("内部点被保留", Texts.sanitizeNickname("a..b", 32).equals("a..b"));
        check("但整体文件名会被判非法",
                !Texts.isSafeFileName("Player-" + Texts.sanitizeNickname("a..b", 32) + ".png", 64));
        checkEquals("去掉首尾空白和点", "abc", Texts.sanitizeNickname("  ..abc..  ", 32));
        checkEquals("空输入", "", Texts.sanitizeNickname("   ", 32));
        checkEquals("纯标点", "", Texts.sanitizeNickname("...", 32));
        checkEquals("超长截断", 10, Texts.sanitizeNickname("一二三四五六七八九十十一十二十三", 10).length());
    }

    private static void testDefaultNickname() {
        System.out.println("\n== 默认昵称 ==");
        checkEquals("去扩展名", "我的图", Texts.defaultNickname("我的图.png"));
        checkEquals("多段扩展名", "a.b.c", Texts.defaultNickname("a.b.c.png"));
        checkEquals("带路径", "y", Texts.defaultNickname("/tmp/x/y.png"));
        checkEquals("无扩展名", "noext", Texts.defaultNickname("noext"));
        checkEquals("空", "image", Texts.defaultNickname(""));
    }

    private static void testSafeFileName() {
        System.out.println("\n== 文件名安全 ==");
        check("正常中文名放行", Texts.isSafeFileName("Steve-我的海报.png", 64));
        check("正常 jpg 放行", Texts.isSafeFileName("A-b.jpg", 64));
        check("拒绝路径穿越", !Texts.isSafeFileName("../etc/passwd.png", 64));
        check("拒绝斜杠", !Texts.isSafeFileName("a/b.png", 64));
        check("拒绝隐藏文件", !Texts.isSafeFileName(".hidden.png", 64));
        check("拒绝无扩展名", !Texts.isSafeFileName("noext", 64));
        check("拒绝非法扩展名", !Texts.isSafeFileName("a.exe", 64));
        check("拒绝超长", !Texts.isSafeFileName("a".repeat(200) + ".png", 64));
        check("拒绝空", !Texts.isSafeFileName("", 64));
    }

    private static void testUrlEncode() {
        System.out.println("\n== URL 编码 ==");
        checkEquals("中文被编码", "%E6%B5%B7%E6%8A%A5.png",
                Texts.encodePathSegment("海报.png"));
        checkEquals("ASCII 不变", "Steve-a_b.png", Texts.encodePathSegment("Steve-a_b.png"));
        checkEquals("空格编码", "a%20b.png", Texts.encodePathSegment("a b.png"));
        checkEquals("解码中文", "海报.png", Texts.urlDecode("%E6%B5%B7%E6%8A%A5.png"));
        checkEquals("解码加号", "a b", Texts.urlDecode("a+b"));
    }

    private static void testHtmlEscape() {
        System.out.println("\n== HTML 转义 ==");
        checkEquals("尖括号", "&lt;script&gt;", Texts.escapeHtml("<script>"));
        checkEquals("引号", "&quot;x&quot;", Texts.escapeHtml("\"x\""));
        checkEquals("and", "a&amp;b", Texts.escapeHtml("a&b"));
        check("中文不动", Texts.escapeHtml("海报").equals("海报"));
    }

    // ------------------------------------------------------- 图片处理

    private static byte[] pngOf(int width, int height, boolean alpha) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, width / 2, height / 2);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] jpegOf(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.GREEN);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private static void testPngRoundTrip() throws Exception {
        System.out.println("\n== PNG 处理 ==");
        byte[] raw = pngOf(120, 60, false);
        ImageTools.Processed p = ImageTools.process(raw, 5 * 1024 * 1024, 2048,
                40_000_000, 90);
        checkEquals("扩展名 png", "png", p.extension());
        checkEquals("宽", 120, p.width());
        checkEquals("高", 60, p.height());
        check("输出仍是 PNG", "PNG".equals(ImageTools.sniff(p.data())));
        // 输出必须能被再读一遍
        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(p.data()));
        check("输出可被解码", back != null && back.getWidth() == 120);
    }

    private static void testJpegRoundTrip() throws Exception {
        System.out.println("\n== JPEG 处理 ==");
        byte[] raw = jpegOf(100, 80);
        ImageTools.Processed p = ImageTools.process(raw, 5 * 1024 * 1024, 2048,
                40_000_000, 90);
        checkEquals("扩展名 jpg", "jpg", p.extension());
        check("输出仍是 JPEG", "JPEG".equals(ImageTools.sniff(p.data())));
        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(p.data()));
        check("输出可被解码", back != null && back.getWidth() == 100);
    }

    private static void testGifRejected() {
        System.out.println("\n== 动图被拒绝 ==");
        try {
            // 最小 GIF89a 头
            byte[] gif = "GIF89a".getBytes(StandardCharsets.ISO_8859_1);
            ImageTools.process(gif, 5 * 1024 * 1024, 2048, 40_000_000, 90);
            check("GIF 要被拒绝", false, "居然通过了");
        } catch (ImageTools.ImageException e) {
            checkEquals("错误码 animated_not_allowed", "animated_not_allowed", e.code());
        }
    }

    private static void testOversizePixelsRejected() throws Exception {
        System.out.println("\n== 像素炸弹被拒绝（只读文件头）==");
        byte[] big = pngOf(3000, 3000, false);
        try {
            ImageTools.process(big, 50 * 1024 * 1024, 2048, 1_000_000, 90);
            check("超过像素上限要拒绝", false, "居然通过了");
        } catch (ImageTools.ImageException e) {
            checkEquals("错误码 image_too_big", "image_too_big", e.code());
        }
    }

    private static void testNotAnImageRejected() {
        System.out.println("\n== 非图片被拒绝 ==");
        try {
            ImageTools.process("这不是图片".getBytes(StandardCharsets.UTF_8),
                    5 * 1024 * 1024, 2048, 40_000_000, 90);
            check("非图片要拒绝", false, "居然通过了");
        } catch (ImageTools.ImageException e) {
            checkEquals("错误码 bad_image", "bad_image", e.code());
        }
    }

    private static void testNoUpscale() throws Exception {
        System.out.println("\n== 只缩不放 ==");
        byte[] small = pngOf(80, 40, false);
        ImageTools.Processed p = ImageTools.process(small, 5 * 1024 * 1024, 2048,
                40_000_000, 90);
        checkEquals("小图不被放大（宽）", 80, p.width());
        checkEquals("小图不被放大（高）", 40, p.height());

        byte[] large = pngOf(3000, 1500, false);
        ImageTools.Processed scaled = ImageTools.process(large, 20 * 1024 * 1024, 1000,
                40_000_000, 90);
        checkEquals("大图长边缩到上限", 1000, Math.max(scaled.width(), scaled.height()));
        checkEquals("保持宽高比", 500, scaled.height());
    }

    // ------------------------------------------------------------ 会话

    // ------------------------------------------- server.properties 解析

    /**
     * 页脚的 motd 和服务器名来自 server.properties。
     *
     * 踩过一次坑：serverName() 原本用 getServer().getName()，那个返回的是服务端
     * 实现名（Purpur），页脚就印出了 "Copyright 2026 Purpur"。所以要解析真实的
     * server.properties —— 这里用真实格式的文件测它。
     */
    private static void testServerProperties() throws Exception {
        System.out.println("\n== 读 server.properties（页脚的 motd 和服务器名靠它） ==");
        Path file = Files.createTempFile("server", ".properties");
        try {
            // 故意混入：注释、空行、行尾空格、值里带 =、Windows 换行
            String content = "#Minecraft server properties\r\n"
                    + "#Thu Oct 16 11:00:00 CST 2026\r\n"
                    + "level-name=shencunshijie\r\n"
                    + "motd=ALPHAHU Minecraft JAVA 26.1 Server   \r\n"
                    + "server-name=alphahu Server\r\n"
                    + "\r\n"
                    + "resource-pack-prompt=点这里下载 = 资源包\r\n"
                    + "  server-port=25565  \r\n";
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));

            Map<String, String> props = ServerProperties.read(file);
            checkEquals("读到了 motd", "ALPHAHU Minecraft JAVA 26.1 Server", props.get("motd"));
            check("行尾空格被去掉", !String.valueOf(props.get("motd")).endsWith(" "));
            checkEquals("读到了 server-name", "alphahu Server", props.get("server-name"));
            checkEquals("值里可以带 =", "点这里下载 = 资源包", props.get("resource-pack-prompt"));
            checkEquals("键前面有空格也认", "25565", props.get("server-port"));
            check("注释和空行被跳过", !props.containsKey("#Minecraft server properties"));
            checkEquals("level-name 正常", "shencunshijie", props.get("level-name"));
        } finally {
            Files.deleteIfExists(file);
        }

        // 文件不存在时不能抛异常，页脚留空即可
        Map<String, String> missing = ServerProperties.read(
                Path.of("/definitely/not/here/server.properties"));
        check("文件不存在时返回空表而不是抛异常", missing.isEmpty());
    }

    private static void testSessionsConfirmFlow() {
        System.out.println("\n== 会话与上传码 ==");
        Sessions sessions = new Sessions(3600_000L, 300_000L);
        Sessions.Session session = sessions.create();
        check("新会话未验证", !session.verified());

        String code = sessions.newCode(session);
        checkEquals("码是 8 位", 8, code.length());
        check("码只含允许的字符", code.matches("^[A-HJ-NP-Z2-9]{8}$"), code);
        check("码在等待确认", sessions.isPending(code));

        checkEquals("错误格式被识别", Sessions.ConfirmResult.BAD_FORMAT,
                sessions.confirm("bad", "Steve"));
        checkEquals("不存在的码", Sessions.ConfirmResult.UNKNOWN,
                sessions.confirm("ZZZZZZZZ", "Steve"));

        checkEquals("正确确认", Sessions.ConfirmResult.OK, sessions.confirm(code, "Steve"));
        check("会话已验证", session.verified());
        checkEquals("玩家名记录正确", "Steve", session.player);
        check("确认后码失效", !sessions.isPending(code));
        checkEquals("同一个码不能重复确认", Sessions.ConfirmResult.UNKNOWN,
                sessions.confirm(code, "Steve"));

        // 小写、带空格和连字符的输入要能归一化
        String code2 = sessions.newCode(session);
        String messy = code2.substring(0, 4).toLowerCase() + "-" + code2.substring(4).toLowerCase();
        checkEquals("杂乱输入也能确认", Sessions.ConfirmResult.OK,
                sessions.confirm(messy, "Alex"));

        // 换新码后旧码立刻失效
        String code3 = sessions.newCode(session);
        String code4 = sessions.newCode(session);
        check("旧码失效", !sessions.isPending(code3));
        check("新码有效", sessions.isPending(code4));
        checkEquals("待确认码只有 1 个", 1, sessions.pendingCodeCount());
    }
}
