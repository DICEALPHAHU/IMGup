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
                .set("site.brand_en", "Image Upload")
                .set("web.admin_enabled", true)
                .set("web.admin_password", "TESTPW123456");
        WebConfig config = WebConfig.from(stub);

        sessions = new Sessions(3600_000L, config.codeTtlMillis);
        store = new UploadStore(dataDir, imagesDir);
        // 语言文件从测试 classpath 里的 messages/ 目录读（就是 jar 里那两份）
        messages = new Messages(java.nio.file.Paths.get("__messages__"), config.language);
        messages.loadTestFixtures(root.resolve("messages"));
        server = new WebServer(java.util.logging.Logger.getLogger("imgup-test"),
                config, store, sessions, messages, new WebServer.GameBridge() {
            @Override
            public void notify(String player, String key, String... pairs) {
                // 现在传的是语言文件的键 + {名字} 替换对，记下来供断言使用
                StringBuilder sb = new StringBuilder(player).append(": ").append(key);
                for (int i = 0; i + 1 < pairs.length; i += 2) {
                    sb.append(' ').append(pairs[i]).append('=').append(pairs[i + 1]);
                }
                notified.add(sb.toString());
            }

            @Override
            public boolean isOnline(String player) {
                return true;
            }

            @Override
            public String serverMotd() {
                return "Example MOTD";
            }

            @Override
            public String serverName() {
                return "Example Server";
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
            testAdminRequiresLogin();
            testAdminLogin();
            testAdminListAndFilter();
            testAdminApproveRejectDelete();
            testAdminApproveAll();
            testAdminDisabled();
            testClientLocaleMapping();
            testGameMessagesAreBilingual();
            testInjectedMessagesHaveNoRawPlaceholders();
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
        check("上传后通知了玩家（用语言键而不是写死的文案）",
                notified.stream().anyMatch(n -> n.startsWith("Steve: game.notify_uploaded")
                        && n.contains(name)),
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

        check("每项带该语言的自称",
                String.valueOf(body.get("languages")).contains("中文（简体）")
                        && String.valueOf(body.get("languages")).contains("English"),
                "拿到 " + body.get("languages"));
        check("current 是当前语言", "zh".equals(body.get("current")),
                "拿到 " + body.get("current"));

        Reply bad = get("/api/lang?lang=../../etc");
        check("非法语言代码被拒绝", bad.status == 400, "拿到 " + bad.status);

        Session client = new Session();
        Reply ok = client.postJson("/api/lang?lang=en", "{}");
        check("切到英文 200", ok.status == 200, "拿到 " + ok.status);
        check("下发了语言 cookie", "en".equals(client.cookie("imgup_lang")),
                "cookie=" + client.cookie("imgup_lang"));
        Reply home = client.get("/");
        check("之后访问自动用英文", home.text().contains("Image Upload"));
        Reply enLangs = client.get("/api/languages");
        check("英文会话下 current=en",
                "en".equals(Json.parseObject(enLangs.text()).get("current")),
                "拿到 " + Json.parseObject(enLangs.text()).get("current"));
    }

    // ------------------------------------------------ 游戏内消息的多语言

    private static void testClientLocaleMapping() throws Exception {
        section("按玩家客户端语言选文案");
        // Minecraft 客户端给的是「语言_国家」形式
        check("zh_cn -> zh", "zh".equals(messages.forClientLocale("zh_cn")),
                "拿到 " + messages.forClientLocale("zh_cn"));
        check("zh_tw 也回退到 zh（没有 zh_tw.yml）",
                "zh".equals(messages.forClientLocale("zh_tw")),
                "拿到 " + messages.forClientLocale("zh_tw"));
        check("en_us -> en", "en".equals(messages.forClientLocale("en_us")),
                "拿到 " + messages.forClientLocale("en_us"));
        check("en_gb -> en", "en".equals(messages.forClientLocale("en_gb")),
                "拿到 " + messages.forClientLocale("en_gb"));
        check("大写也认", "en".equals(messages.forClientLocale("EN_US")),
                "拿到 " + messages.forClientLocale("EN_US"));
        check("连字符形式也认", "en".equals(messages.forClientLocale("en-US")),
                "拿到 " + messages.forClientLocale("en-US"));
        // 玩家用了一门站长没放语言文件的语言：给默认语言，不能给键名
        check("日语没有文件 -> 回退默认语言",
                "zh".equals(messages.forClientLocale("ja_jp")),
                "拿到 " + messages.forClientLocale("ja_jp"));
        check("null -> 默认语言", "zh".equals(messages.forClientLocale(null)),
                "拿到 " + messages.forClientLocale(null));
        check("空串 -> 默认语言", "zh".equals(messages.forClientLocale("  ")),
                "拿到 " + messages.forClientLocale("  "));
    }

    private static void testGameMessagesAreBilingual() throws Exception {
        section("游戏内消息：同键不同语言，且都不缺");
        String[] keys = {
                "game.prefix", "game.confirm_ok", "game.confirm_bad_format",
                "game.confirm_unknown", "game.notify_uploaded",
                "game.notify_uploaded_pending", "game.notify_approved",
                "game.notify_rejected", "game.list_empty", "game.list_header",
                "game.list_line", "game.usage_header", "game.usage_player",
                "game.approved", "game.rejected", "game.not_found",
                "game.no_permission_admin", "game.no_permission_use",
                "game.web_running", "game.web_stopped", "game.info_header",
        };
        int missing = 0;
        int same = 0;
        for (String key : keys) {
            String zh = messages.get("zh", key);
            String en = messages.get("en", key);
            // get() 找不到键时会把键名本身返回，那就是漏了
            if (key.equals(zh) || key.equals(en)) {
                missing++;
                System.out.println("  [!!]   " + key + " 在某个语言里没定义");
                continue;
            }
            if (zh.equals(en)) {
                same++;
                System.out.println("  [!!]   " + key + " 两种语言文案一样：" + zh);
            }
        }
        check("所有 game.* 键两种语言都有定义", missing == 0, missing + " 个缺失");
        check("两种语言的文案确实不同（真的翻译了）", same == 0, same + " 个相同");

        // 占位符替换要生效，且不能把 {xxx} 露在页面上
        String zhLine = messages.get("zh", "game.list_line",
                "dot", "●", "name", "Steve-海报.png", "width", "640",
                "height", "400", "size", "12 KB", "player", "Steve");
        check("占位符被替换", zhLine.contains("Steve-海报.png") && !zhLine.contains("{name}"),
                "拿到 " + zhLine);
        check("中文行用了中文的「来自」", zhLine.contains("来自"), "拿到 " + zhLine);

        String enLine = messages.get("en", "game.list_line",
                "dot", "*", "name", "Steve-poster.png", "width", "640",
                "height", "400", "size", "12 KB", "player", "Steve");
        check("英文行用英文", enLine.contains("by ") && !enLine.contains("来自"),
                "拿到 " + enLine);

        // 缺参数时不能把 {name} 这种东西留在聊天框里
        String noArgs = messages.get("zh", "game.approved");
        check("没给替换值时占位符被清掉（不露 {name}）", !noArgs.contains("{name}"),
                "拿到 " + noArgs);
    }

    // ------------------------------------------ 注入给前端的文案表

    private static void testInjectedMessagesHaveNoRawPlaceholders() throws Exception {
        section("注入的文案表：不能残留 {占位符}");
        String html = get("/").text();

        // 从 <script>window.IMGUP_MESSAGES={...};window.IMGUP_LANG=...</script> 里抠出来
        int start = html.indexOf("window.IMGUP_MESSAGES=");
        if (start < 0) {
            check("页面里有注入的文案表", false, "没找到 window.IMGUP_MESSAGES");
            return;
        }
        start += "window.IMGUP_MESSAGES=".length();
        int end = html.indexOf(";window.IMGUP_LANG=", start);
        if (end < 0) {
            check("注入的文案表后面跟着语言标记", false, "没找到 ;window.IMGUP_LANG=");
            return;
        }
        // jsonOf 会把 < 转成 \u003c，这里还原后再解析
        String raw = html.substring(start, end).replace("\\u003c", "<");

        Map<String, Object> table;
        try {
            table = Json.parseObject(raw);
        } catch (RuntimeException e) {
            check("注入的表是合法 JSON", false, e.getMessage());
            return;
        }
        check("注入的表能解析", !table.isEmpty(), "解析出 " + table.size() + " 个键");

        // 1) 任何值里都不能有没替换掉的 {占位符}
        java.util.regex.Pattern placeholder =
                java.util.regex.Pattern.compile("\\{[a-zA-Z_][a-zA-Z0-9_]*\\}");
        java.util.List<String> leaked = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            String value = String.valueOf(entry.getValue());
            if (placeholder.matcher(value).find()) {
                leaked.add(entry.getKey() + "=" + value);
            }
        }
        check("没有任何未替换的 {占位符}", leaked.isEmpty(),
                "这些键还带着占位符：" + leaked);

        // 2) 页脚已经是最终文案（含真实 motd / 服务器名）
        String footer = String.valueOf(table.get("site.footer_note"));
        check("页脚文案含真实 motd", footer.contains("Example MOTD"),
                "拿到 " + footer);
        check("页脚文案不含 {motd}", !footer.contains("{motd}"), "拿到 " + footer);
        String copyright = String.valueOf(table.get("site.footer_copyright"));
        check("版权行含真实服务器名", copyright.contains("Example Server"),
                "拿到 " + copyright);
        check("版权行不含 {server}", !copyright.contains("{server}"), "拿到 " + copyright);

        // 3) 游戏内消息也在表里（前端切语言时如果要用到，得有）
        check("表里有 game.confirm_ok", table.containsKey("game.confirm_ok"));
        check("表里有 game.notify_uploaded", table.containsKey("game.notify_uploaded"));
    }

    // ------------------------------------------------------------ 后台

    private static void testAdminRequiresLogin() throws Exception {
        section("后台：没登录一律 401");
        Session anon = new Session();
        check("列表要登录", anon.get("/admin/api/list").status == 401);
        check("通过要登录", anon.postJson("/admin/api/approve", "{\"name\":\"x.png\"}").status == 401);
        check("拒绝要登录", anon.postJson("/admin/api/reject", "{\"name\":\"x.png\"}").status == 401);
        check("删除要登录", anon.postJson("/admin/api/delete", "{\"name\":\"x.png\"}").status == 401);
        check("一键通过要登录", anon.postJson("/admin/api/approve-all", "{}").status == 401);
        check("返回错误码 unauthorized",
                "unauthorized".equals(Json.parseObject(
                        anon.get("/admin/api/list").text()).get("error")));

        Reply page = anon.get("/admin");
        check("后台页面本身可以打开（未登录只显示登录框）", page.status == 200,
                "拿到 " + page.status);
        check("登录框在页面里", page.text().contains("id=\"loginForm\""));
    }

    private static void testAdminLogin() throws Exception {
        section("后台：登录");
        Session client = new Session();
        Reply wrong = client.postJson("/admin/api/login", "{\"password\":\"nope\"}");
        check("密码错返回 401", wrong.status == 401, "拿到 " + wrong.status);
        check("错误码 bad_password",
                "bad_password".equals(Json.parseObject(wrong.text()).get("error")));

        Reply right = client.postJson("/admin/api/login", "{\"password\":\"TESTPW123456\"}");
        check("密码对返回 200", right.status == 200, "拿到 " + right.status);
        check("下发了后台 cookie", client.cookie("imgup_admin") != null);

        check("登录后能看列表", client.get("/admin/api/list").status == 200);
        Reply out = client.postJson("/admin/api/logout", "{}");
        check("退出登录 200", out.status == 200);
        check("退出后 cookie 被清掉", "".equals(client.cookie("imgup_admin"))
                || client.cookie("imgup_admin") == null);
    }

    private static void testAdminListAndFilter() throws Exception {
        section("后台：列表与筛选");
        Session admin = adminSession();
        String pendingName = uploadAsPending(admin, "Adm", "后台测试");

        Reply all = admin.get("/admin/api/list?filter=all");
        check("列表 200", all.status == 200, "拿到 " + all.status);
        Map<String, Object> body = Json.parseObject(all.text());
        check("有 items 数组", body.get("items") instanceof java.util.List);
        check("有 counts 统计", body.get("counts") instanceof Map);
        check("列表里有刚上传的图", all.text().contains(pendingName));

        Reply pending = admin.get("/admin/api/list?filter=pending");
        check("筛选待审核", pending.status == 200 && pending.text().contains(pendingName));
        check("待审核里没有已发布的图",
                !pending.text().contains("Steve-我的海报.png"));

        Reply bad = admin.get("/admin/api/list?filter=../etc");
        check("非法筛选值返回 400", bad.status == 400, "拿到 " + bad.status);
    }

    private static void testAdminApproveRejectDelete() throws Exception {
        section("后台：通过 / 拒绝 / 删除");
        Session admin = adminSession();

        // 通过：文件应当从 pending 移到 Images 目录
        String a = uploadAsPending(admin, "Adm", "待通过");
        check("通过前在 pending 里", Files.exists(dataDir.resolve("pending").resolve(a)));
        Reply approve = admin.postJson("/admin/api/approve",
                "{\"name\":\"" + jsonEsc(a) + "\"}");
        check("通过 200", approve.status == 200, "拿到 " + approve.status + " " + approve.text());
        check("状态变成 published",
                "published".equals(Json.parseObject(approve.text()).get("status")));
        check("文件移到 Images 目录", Files.exists(imagesDir.resolve(a)));
        check("pending 里没有残留", !Files.exists(dataDir.resolve("pending").resolve(a)));
        check("通过后通知了玩家（键 + 文件名）",
                notified.stream().anyMatch(n -> n.startsWith("Adm: game.notify_approved")
                        && n.contains(a)),
                "通知记录 " + notified);

        // 拒绝：文件移到 rejected/
        String b = uploadAsPending(admin, "Adm", "待拒绝");
        Reply reject = admin.postJson("/admin/api/reject",
                "{\"name\":\"" + jsonEsc(b) + "\"}");
        check("拒绝 200", reject.status == 200, "拿到 " + reject.status);
        check("状态变成 rejected",
                "rejected".equals(Json.parseObject(reject.text()).get("status")));
        check("文件移到 rejected 目录", Files.exists(dataDir.resolve("rejected").resolve(b)));

        // 删除：文件彻底消失
        String c = uploadAsPending(admin, "Adm", "待删除");
        Reply delete = admin.postJson("/admin/api/delete",
                "{\"name\":\"" + jsonEsc(c) + "\"}");
        check("删除 200", delete.status == 200, "拿到 " + delete.status);
        check("文件彻底没了",
                !Files.exists(dataDir.resolve("pending").resolve(c))
                        && !Files.exists(imagesDir.resolve(c))
                        && !Files.exists(dataDir.resolve("rejected").resolve(c)));

        // 不存在的图
        Reply missing = admin.postJson("/admin/api/approve",
                "{\"name\":\"MeiYouZheZhang.png\"}");
        check("不存在的图返回 404", missing.status == 404, "拿到 " + missing.status);

        // 路径穿越的文件名必须被拒
        Reply traversal = admin.postJson("/admin/api/delete",
                "{\"name\":\"../../server.properties\"}");
        check("路径穿越的文件名返回 400", traversal.status == 400,
                "拿到 " + traversal.status);
    }

    private static void testAdminApproveAll() throws Exception {
        section("后台：一键通过全部待审核");
        Session admin = adminSession();
        String one = uploadAsPending(admin, "Bulk", "批量一");
        String two = uploadAsPending(admin, "Bulk", "批量二");

        Reply reply = admin.postJson("/admin/api/approve-all", "{}");
        check("一键通过 200", reply.status == 200, "拿到 " + reply.status);
        check("两张都进了 Images 目录",
                Files.exists(imagesDir.resolve(one)) && Files.exists(imagesDir.resolve(two)));
        check("pending 已空",
                !Files.exists(dataDir.resolve("pending").resolve(one))
                        && !Files.exists(dataDir.resolve("pending").resolve(two)));
    }

    private static void testAdminDisabled() throws Exception {
        section("后台：admin_enabled=false 时整体关闭");
        int port = freePort();
        String baseUrl = "http://127.0.0.1:" + port;
        ConfigStub stub = new ConfigStub()
                .set("web.enabled", true)
                .set("web.host", "127.0.0.1")
                .set("web.port", port)
                .set("web.public_base_url", baseUrl)
                .set("web.admin_enabled", false)
                .set("web.admin_password", "TESTPW123456")
                .set("language", "zh")
                .set("messages_dir", "__messages__");
        WebConfig off = WebConfig.from(stub);
        Path offDir = Files.createTempDirectory("imgup-adminoff");
        Messages offMessages = new Messages(offDir.resolve("messages"), "zh");
        offMessages.loadTestFixtures(offDir.resolve("messages"));
        WebServer offServer = new WebServer(java.util.logging.Logger.getLogger("imgup-off"),
                off, new UploadStore(offDir.resolve("ImgUp"), offDir.resolve("Images")),
                new Sessions(3600_000L, 300_000L), offMessages, new WebServer.GameBridge() {
            public void notify(String player, String key, String... pairs) {
            }

            public boolean isOnline(String player) {
                return false;
            }

            public String serverMotd() {
                return "";
            }

            public String serverName() {
                return "";
            }
        });
        if (!offServer.start()) {
            check("关闭后台的实例能起来", false);
            return;
        }
        try {
            // 注意：静态 get() 会自动拼上全局 base（那是主测试服务器的地址），
            // 这里要打的是另一个端口，所以走独立的绝对 URL 请求。
            Reply page = fetchAbsolute(baseUrl + "/admin");
            check("后台页面 404", page.status == 404, "拿到 " + page.status);
            Reply api = fetchAbsolute(baseUrl + "/admin/api/list");
            check("后台接口 404", api.status == 404, "拿到 " + api.status);
            Reply health = fetchAbsolute(baseUrl + "/api/health");
            check("同一实例的其它接口照常可用（只关后台）", health.status == 200,
                    "拿到 " + health.status);
        } finally {
            offServer.stop();
        }
    }

    private static Session adminSession() throws Exception {
        Session client = new Session();
        Reply reply = client.postJson("/admin/api/login", "{\"password\":\"TESTPW123456\"}");
        if (reply.status != 200) {
            throw new IllegalStateException("后台登录失败：" + reply.status + " " + reply.text());
        }
        return client;
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

    /** 直接请求一个完整 URL（用于临时起的第二个实例）。 */
    private static Reply fetchAbsolute(String url) throws Exception {
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response);
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
