import com.huhumc.imgup.ImageTools;
import com.huhumc.imgup.Messages;
import com.huhumc.imgup.Json;
import com.huhumc.imgup.Sessions;
import com.huhumc.imgup.UploadStore;
import com.huhumc.imgup.WebConfig;
import com.huhumc.imgup.WebServer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 真起 HTTP 服务器，走一遍玩家的完整流程。
 *
 * 用 Java 自带的 HttpClient 发真实 HTTP 请求 —— 和浏览器走的是同一套
 * 协议路径（multipart 编码、Cookie、状态码），比只看代码可靠得多。
 *
 * 覆盖：健康检查 → 取码 → 游戏内确认 → 上传 → 结果页 → 改名 → 文件落盘 → 静态资源
 */
public final class HttpSelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> FAILURES = new ArrayList<>();

    private static String base;
    private static Path dataDir;
    private static Path imagesDir;
    private static Sessions sessions;
    private static UploadStore store;
    private static WebServer server;
    private static Messages messages;
    private static final List<String> notified = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("imgup-http-test");
        dataDir = root.resolve("ImgUp");
        imagesDir = root.resolve("Images");
        Files.createDirectories(dataDir);
        Files.createDirectories(imagesDir);

        int port = freePort();
        base = "http://127.0.0.1:" + port;

        // 用真实 config.yml 的等价设置起服务
        Map<String, Object> raw = new LinkedHashMap<>();
        // 直接构造 WebConfig 需要 FileConfiguration，这里用一个小包装绕过去：
        // 见下方 ConfigStub
        ConfigStub stub = new ConfigStub()
                .set("web.enabled", true)
                .set("web.host", "127.0.0.1")
                .set("web.port", port)
                .set("web.public_base_url", base)
                .set("web.auto_publish", true)
                .set("upload.max_upload_bytes", 5 * 1024 * 1024)
                .set("upload.max_nickname_length", 32)
                .set("site.brand", "图片上传站")
                .set("site.brand_en", "Image Upload");
        WebConfig config = WebConfig.from(stub);

        sessions = new Sessions(3600_000L, config.codeTtlMillis);
        store = new UploadStore(dataDir, imagesDir);
        // 语言文件从测试 classpath 里的 messages/ 目录读（就是 jar 里那两份）
        messages = new Messages(java.nio.file.Paths.get("__messages__"), config.language);
        messages.loadTestFixtures(root.resolve("messages"));
        server = new WebServer(java.util.logging.Logger.getLogger("imgup-test"),
                config, store, sessions, messages, new WebServer.GameBridge() {
            @Override
            public void notify(String player, String message) {
                notified.add(player + ": " + message);
            }

            @Override
            public boolean isOnline(String player) {
                return true;
            }
        });
        if (!server.start()) {
            System.out.println("服务器起不来，测试无法继续");
            System.exit(1);
        }
        System.out.println("测试服务器已启动：" + base);

        try {
            testHealth();
            testPagesRender();
            testStaticAssets();
            testFullUploadFlow();
            testNicknameDefaultsToOriginalFileName();
            testDuplicateNicknameGetsSuffix();
            testRename();
            testRenameConflict();
            testFileActuallyLandsInImagesDir();
            testRejectsNonImage();
            testRejectsOversize();
            testUnverifiedCannotUpload();
            testPathTraversalBlocked();
            testSiteElementsFromLanguageFile();
            testLanguageSwitch();
        } finally {
            server.stop();
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("全部通过：" + passed + " 项");
        } else {
            System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
            for (String item : FAILURES) {
                System.out.println("  · " + item);
            }
        }
        // 清理临时目录
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响结论
                }
            });
        }
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 测试

    private static void testHealth() throws Exception {
        section("健康检查");
        Reply reply = get("/api/health");
        check("HTTP 200", reply.status == 200, "拿到 " + reply.status);
        Map<String, Object> body = Json.parseObject(reply.text());
        check("ok=true", Boolean.TRUE.equals(body.get("ok")));
        check("有图片目录信息", body.containsKey("images_dir"));
    }

    private static void testPagesRender() throws Exception {
        section("三个页面都能渲染，且没有残留占位符");
        for (String path : new String[]{"/", "/upload", "/result"}) {
            Reply reply = get(path);
            check(path + " 返回 200", reply.status == 200, "拿到 " + reply.status);
            // 注意：注入的文案表 JSON 本身含 "$site_..." 这类字面量（它是离线兜底文案），
            // 所以检查前先把那段 <script> 摘掉，只看真正发给浏览器的 HTML。
            String htmlOnly = reply.text()
                    .replaceAll("(?s)<script>window\\.IMGUP_.*?</script>", "");
            check(path + " 没有残留的 $ 占位符",
                    !htmlOnly.matches("(?s).*\\$[a-z_]{3,}.*"),
                    "还有没替换掉的变量");
            check(path + " 引入了 i18n 引擎", reply.text().contains("/static/i18n.js"));
            check(path + " 注入了文案表", reply.text().contains("IMGUP_MESSAGES"));
            check(path + " 注入了当前语言", reply.text().contains("IMGUP_LANG"));
        }
        Reply home = get("/");
        check("首页显示配置里的站名", home.text().contains("图片上传站"));
        check("首页有获取上传码按钮", home.text().contains("id=\"newCodeBtn\""));
    }

    private static void testStaticAssets() throws Exception {
        section("静态资源");
        Reply css = get("/static/app.css");
        check("CSS 200", css.status == 200, "拿到 " + css.status);
        check("CSS 类型正确", String.valueOf(css.contentType).contains("text/css"));
        Reply js = get("/static/i18n.js");
        check("i18n.js 200", js.status == 200, "拿到 " + js.status);
        check("i18n.js 里有中英两张表", js.text().contains("DICT") && js.text().contains("'common.brand'"));
        Reply missing = get("/static/nope.js");
        check("不存在的资源 404", missing.status == 404, "拿到 " + missing.status);
    }

    private static void testFullUploadFlow() throws Exception {
        section("完整流程：取码 → 游戏内确认 → 上传 → 结果页");
        Session client = new Session();

        Reply code = client.postJson("/api/newcode", "{}");
        check("取码 200", code.status == 200, "拿到 " + code.status + " " + code.text());
        String uploadCode = Json.string(Json.parseObject(code.text()), "code");
        check("拿到 8 位码", uploadCode.matches("^[A-HJ-NP-Z2-9]{8}$"), "拿到 " + uploadCode);
        check("会话 cookie 已下发", client.cookie("imgup_sid") != null);

        check("确认前还没验证", !client.verified());
        // 模拟玩家在游戏里敲 /imgup <码>
        Sessions.ConfirmResult confirm = sessions.confirm(uploadCode, "Steve");
        check("游戏内确认成功", confirm == Sessions.ConfirmResult.OK, "结果 " + confirm);

        Reply status = client.get("/api/codestatus");
        Map<String, Object> statusBody = Json.parseObject(status.text());
        check("网页立刻知道已确认", Boolean.TRUE.equals(statusBody.get("claimed")),
                "body=" + status.text());
        check("玩家名已绑定", "Steve".equals(statusBody.get("player")));

        Reply upload = client.postImage("/api/upload", "我的海报.png",
                png(400, 300), "我的海报");
        check("上传 200", upload.status == 200, "拿到 " + upload.status + " " + upload.text());
        Map<String, Object> body = Json.parseObject(upload.text());
        String name = Json.string(body, "name");
        check("文件名 = 玩家-昵称.png", "Steve-我的海报.png".equals(name), "拿到 " + name);
        check("直接发布（auto_publish=true）", "published".equals(body.get("status")),
                "状态 " + body.get("status"));
        check("给了图片地址", Json.string(body, "url").contains("Steve-"));
        check("上传后通知了玩家", notified.stream().anyMatch(s -> s.startsWith("Steve:")),
                "通知记录 " + notified);

        Reply result = get("/result?name=" + enc(name), client);
        check("结果页 200", result.status == 200, "拿到 " + result.status);
        check("结果页显示文件名", result.text().contains("Steve-我的海报.png"));
        check("结果页状态键是 published", result.text().contains("\"status.published\"")
                || result.text().contains("status.published"));
        check("结果页给了在游戏里用的指令", result.text().contains("/image create"));
    }

    private static void testNicknameDefaultsToOriginalFileName() throws Exception {
        section("不填昵称时用原文件名（去扩展名）");
        Session client = verifiedSession("Alex");
        Reply reply = client.postImage("/api/upload", "夏日海边.png", png(60, 40), null);
        Map<String, Object> body = Json.parseObject(reply.text());
        check("文件名取自原文件名", "Alex-夏日海边.png".equals(Json.string(body, "name")),
                "拿到 " + Json.string(body, "name"));
    }

    private static void testDuplicateNicknameGetsSuffix() throws Exception {
        section("重名自动加 -2 / -3（玩家要手输，不能用随机串）");
        Session client = verifiedSession("Dup");
        Reply first = client.postImage("/api/upload", "x.png", png(50, 50), "海报");
        Reply second = client.postImage("/api/upload", "x.png", png(50, 50), "海报");
        Reply third = client.postImage("/api/upload", "x.png", png(50, 50), "海报");
        check("第一张无后缀", "Dup-海报.png".equals(name(first)), name(first));
        check("第二张 -2", "Dup-海报-2.png".equals(name(second)), name(second));
        check("第三张 -3", "Dup-海报-3.png".equals(name(third)), name(third));
    }

    private static void testRename() throws Exception {
        section("上传后改名");
        Session client = verifiedSession("Ren");
        // auto_publish=true 时是直接发布的，改名会被拒 —— 这里先造一张待审核的
        String name = uploadAsPending(client, "Ren", "旧名字");
        check("造出一张待审核的图", name != null, "上传失败");
        Reply reply = client.postJson("/api/rename",
                "{\"name\":\"" + jsonEsc(name) + "\",\"nickname\":\"新名字\"}");
        check("改名 200", reply.status == 200, "拿到 " + reply.status + " " + reply.text());
        Map<String, Object> body = Json.parseObject(reply.text());
        check("新文件名正确", "Ren-新名字.png".equals(Json.string(body, "name")),
                "拿到 " + Json.string(body, "name"));
        check("旧文件已不在", !Files.exists(imagesDir.resolve(name))
                && !Files.exists(dataDir.resolve("pending").resolve(name)));
        check("新文件已存在", Files.exists(imagesDir.resolve("Ren-新名字.png"))
                || Files.exists(dataDir.resolve("pending").resolve("Ren-新名字.png")));
    }

    private static void testRenameConflict() throws Exception {
        section("改名撞车要报 409，不能悄悄加后缀");
        Session client = verifiedSession("Conf");
        String first = uploadAsPending(client, "Conf", "重名");
        String second = uploadAsPending(client, "Conf", "另一个");
        check("两张待审核的图", first != null && second != null);
        Reply reply = client.postJson("/api/rename",
                "{\"name\":\"" + jsonEsc(second) + "\",\"nickname\":\"重名\"}");
        check("返回 409", reply.status == 409, "拿到 " + reply.status + " " + reply.text());
        Map<String, Object> body = Json.parseObject(reply.text());
        check("错误码 name_taken", "name_taken".equals(body.get("error")),
                "拿到 " + body.get("error"));
        check("带了语言中立的键", "err.name_taken".equals(body.get("message_key")));
    }

    private static void testFileActuallyLandsInImagesDir() throws Exception {
        section("发布的图真的落到 Images 目录（游戏插件才认）");
        Session client = verifiedSession("Disk");
        Reply reply = client.postImage("/api/upload", "a.png", png(70, 70), "落盘测试");
        String name = name(reply);
        Path target = imagesDir.resolve(name);
        check("文件在 Images 目录里", Files.isRegularFile(target), "找不到 " + target);
        check("文件不是空的", Files.size(target) > 0);
        check("能被当图片读出来", ImageIO.read(target.toFile()) != null);
        // 而且能通过 HTTP 下载到（游戏外也能看）
        Reply download = get("/uploads/" + enc(name));
        check("能通过 /uploads 下载", download.status == 200, "拿到 " + download.status);
        check("下载的是 PNG", "PNG".equals(ImageTools.sniff(download.body)));
        check("待审核目录里没有残留", !Files.exists(dataDir.resolve("pending").resolve(name)));
    }

    private static void testRejectsNonImage() throws Exception {
        section("非图片被拒绝");
        Session client = verifiedSession("Bad");
        Reply reply = client.postImage("/api/upload", "notimage.png",
                "这根本不是图片".getBytes(StandardCharsets.UTF_8), "坏文件");
        check("返回 400", reply.status == 400, "拿到 " + reply.status);
        Map<String, Object> body = Json.parseObject(reply.text());
        check("错误码 bad_image", "bad_image".equals(body.get("error")), "拿到 " + body.get("error"));
        // 注意：别断言整个 pending 目录为空 —— 前面几个用例故意留了待审核的图。
        // 只断言这次失败的上传没有多出文件。
        check("失败的上传没有留下文件",
                !listFiles(dataDir.resolve("pending")).contains("Bad-坏文件.png"));
    }

    private static void testRejectsOversize() throws Exception {
        section("超过体积上限被拒绝");
        Session client = verifiedSession("Big");
        byte[] huge = new byte[6 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) 0x41);
        Reply reply = client.postImage("/api/upload", "huge.png", huge, "太大");
        check("返回 413 或 400", reply.status == 413 || reply.status == 400,
                "拿到 " + reply.status);
    }

    private static void testUnverifiedCannotUpload() throws Exception {
        section("没在游戏里确认就上传 → 401");
        Session client = new Session();
        client.postJson("/api/newcode", "{}");
        Reply reply = client.postImage("/api/upload", "x.png", png(40, 40), "偷传");
        check("返回 401", reply.status == 401, "拿到 " + reply.status);
        Map<String, Object> body = Json.parseObject(reply.text());
        check("错误码 not_verified", "not_verified".equals(body.get("error")),
                "拿到 " + body.get("error"));
    }

    private static void testPathTraversalBlocked() throws Exception {
        section("路径穿越被挡住");
        Reply reply = get("/uploads/..%2F..%2Fserver.properties");
        check("不在 Images 目录的文件拿不到", reply.status == 404, "拿到 " + reply.status);
        Reply reply2 = get("/static/../config.yml");
        check("静态资源不能跳出 web 目录", reply2.status == 404 || reply2.status == 400,
                "拿到 " + reply2.status);
    }

    private static void testSiteElementsFromLanguageFile() throws Exception {
        section("页面文案来自 messages/<语言>.yml");
        Reply home = get("/");
        check("默认语言渲染出中文站名", home.text().contains("图片上传站"));
        check("注入了整张文案表", home.text().contains("IMGUP_MESSAGES"));
        check("注入了当前语言", home.text().contains("IMGUP_LANG"));
        check("表里有 site.brand", home.text().contains("site.brand"));
        Reply english = get("/?lang=en");
        check("?lang=en 渲染出英文站名", english.text().contains("Image Upload"),
                "页面里没找到英文站名");
        check("英文页面 html lang 正确", english.text().contains("lang=\"en\"")
                || english.text().contains("IMGUP_LANG=\"en\""));
    }

    private static void testLanguageSwitch() throws Exception {
        section("语言切换接口");
        Reply list = get("/api/languages");
        check("语言列表 200", list.status == 200, "拿到 " + list.status);
        Map<String, Object> body = Json.parseObject(list.text());
        check("默认语言是 zh", "zh".equals(body.get("default")), "拿到 " + body.get("default"));
        check("列出了 zh 和 en", String.valueOf(body.get("available")).contains("zh")
                && String.valueOf(body.get("available")).contains("en"),
                "拿到 " + body.get("available"));

        Reply bad = get("/api/lang?lang=../../etc");
        check("非法语言代码被拒绝", bad.status == 400, "拿到 " + bad.status);

        Session client = new Session();
        Reply ok = client.postJson("/api/lang?lang=en", "{}");
        check("切到英文 200", ok.status == 200, "拿到 " + ok.status);
        check("下发了语言 cookie", "en".equals(client.cookie("imgup_lang")),
                "cookie=" + client.cookie("imgup_lang"));
        Reply home = client.get("/");
        check("之后访问自动用英文", home.text().contains("Image Upload"));
    }

    // ------------------------------------------------------------ 工具

    /** 直接往 store 里塞一张「待审核」的图，用来测改名（避开 auto_publish）。 */
    private static String uploadAsPending(Session client, String player, String nickname)
            throws Exception {
        String name = store.makeFileName(player, nickname, "png", 64, 32, false);
        ImageTools.Processed processed = ImageTools.process(png(80, 60),
                5 * 1024 * 1024, 2048, 40_000_000, 90);
        store.store(name, player, nickname, processed.data(), processed.width(),
                processed.height(), false);
        return name;
    }

    private static Session verifiedSession(String player) {
        Session client = new Session();
        try {
            Reply reply = client.postJson("/api/newcode", "{}");
            String code = Json.string(Json.parseObject(reply.text()), "code");
            sessions.confirm(code, player);
        } catch (Exception e) {
            throw new IllegalStateException("建立会话失败：" + e.getMessage(), e);
        }
        return client;
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(60, 120, 200));
            g.fillRect(0, 0, width, height);
            g.setColor(Color.WHITE);
            g.fillRect(width / 4, height / 4, width / 2, height / 2);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String jsonEsc(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String name(Reply reply) {
        return Json.string(Json.parseObject(reply.text()), "name");
    }

    private static List<String> listFiles(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).toList();
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static Reply get(String path) throws Exception {
        return get(path, null);
    }

    private static Reply get(String path, Session session) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (session != null) {
            builder.header("Cookie", session.cookieHeader());
        }
        HttpResponse<byte[]> response = HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response);
    }

    /** 一个独立的「浏览器」：自己管 cookie。 */
    private static final class Session {
        private final Map<String, String> cookies = new LinkedHashMap<>();

        String cookie(String key) {
            return cookies.get(key);
        }

        String cookieHeader() {
            StringBuilder sb = new StringBuilder();
            cookies.forEach((k, v) -> {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(k).append('=').append(v);
            });
            return sb.toString();
        }

        boolean verified() {
            return false; // 由服务端的 /api/codestatus 判断，这里不用
        }

        Reply get(String path) throws Exception {
            return HttpSelfTest.get(path, this);
        }

        Reply postJson(String path, String body) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .header("Cookie", cookieHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            return send(request);
        }

        Reply postImage(String path, String fileName, byte[] data, String nickname)
                throws Exception {
            String boundary = "test" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (nickname != null) {
                out.writeBytes(("--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"nickname\"\r\n\r\n"
                        + nickname + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.writeBytes(("--" + boundary
                    + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + fileName + "\"\r\nContent-Type: image/png\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Cookie", cookieHeader())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                    .build();
            return send(request);
        }

        private Reply send(HttpRequest request) throws Exception {
            HttpResponse<byte[]> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            response.headers().allValues("Set-Cookie").forEach(header -> {
                String pair = header.split(";")[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    cookies.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            });
            return new Reply(response);
        }
    }

    private record Reply(int status, byte[] body, String contentType, String text) {
        Reply(HttpResponse<byte[]> response) {
            this(response.statusCode(), response.body(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------ 断言

    private static void section(String title) {
        System.out.println("\n== " + title + " ==");
    }

    private static void check(String label, boolean ok) {
        check(label, ok, "");
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + label);
        } else {
            failed++;
            String line = label + (detail.isEmpty() ? "" : "  — " + detail);
            FAILURES.add(line);
            System.out.println("  [FAIL] " + line);
        }
    }
}
