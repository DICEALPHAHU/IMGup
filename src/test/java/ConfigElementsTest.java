import com.huhumc.imgup.Messages;
import com.huhumc.imgup.Resources;
import com.huhumc.imgup.Sessions;
import com.huhumc.imgup.UploadStore;
import com.huhumc.imgup.WebConfig;
import com.huhumc.imgup.WebServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 实证「网站元素确实来自配置文件」：把语言文件里的值改成一眼能认出的字符串，
 * 再看渲染出来的页面里有没有它。
 *
 * 只做这件事 —— 不做断言库，失败就打印出来，人眼一眼能看出哪条没生效。
 */
public final class ConfigElementsTest {

    private static int ok = 0;
    private static int bad = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("imgup-config-test");
        Path dataDir = root.resolve("ImgUp");
        Path imagesDir = root.resolve("Images");
        Path msgDir = dataDir.resolve("messages");
        Files.createDirectories(msgDir);
        Files.createDirectories(imagesDir);

        // 语言文件：把每个元素都改成「CUSTOM-<键名>」形式，方便在页面里找
        List<String> elementKeys = List.of(
                "site.brand", "site.nav_admin",
                "site.home_heading", "site.home_lede", "site.home_first_step_title",
                "site.home_button", "site.home_second_step_title",
                "site.upload_heading", "site.upload_lede", "site.upload_button",
                "site.upload_nickname_title", "site.upload_footer",
                "site.nav_reupload", "site.nav_reverify",
                "site.result_heading", "site.result_rename_title",
                "site.result_rename_button",
                // 这两个的值保留占位符，由 checkFooterServerInfo 单独验证，
                // 所以不做 CUSTOM- 断言（见下面的 pageSpecific）
                "site.footer_note", "site.footer_copyright", "site.footer_plugin");

        List<String> pageSpecific = List.of(
                "site.nav_admin", "site.nav_reverify", "site.nav_reupload",
                "site.upload_footer", "site.footer_note", "site.footer_copyright");
        // 先拿真实的语言文件当底子：页面文案现在全部由服务端渲染，
        // 缺任何一个键那一块就会渲染成空白，所以测试用的文件必须是完整的。
        String realZh = Resources.readText("messages/zh.yml");
        String realEn = Resources.readText("messages/en.yml");
        if (realZh == null || realEn == null) {
            throw new IllegalStateException("读不到 jar 里的 messages/*.yml（测试要把 "
                    + "src/main/resources 放进 classpath）");
        }
        for (String key : elementKeys) {
            String value;
            if (key.equals("site.footer_note")) {
                // 页脚要嵌 server.properties 的 motd，保留真实的占位符写法
                value = "MOTD:{motd}";
            } else if (key.equals("site.footer_copyright")) {
                value = "SERVER:{server}";
            } else if (key.equals("site.footer_plugin")) {
                // 插件名和版本由插件在启动时告诉网站，保留占位符才能验证替换
                value = "PLUGIN {name} {version}";
            } else {
                value = "CUSTOM-" + key.substring("site.".length());
            }
            realZh = override(realZh, key, value);
            realEn = override(realEn, key, value);
        }
        Files.writeString(msgDir.resolve("zh.yml"), realZh, StandardCharsets.UTF_8);
        Files.writeString(msgDir.resolve("en.yml"), realEn, StandardCharsets.UTF_8);

        int port = freePort();
        ConfigStub stub = new ConfigStub()
                .set("web.enabled", true)
                .set("web.host", "127.0.0.1")
                .set("web.port", port)
                .set("web.public_base_url", "http://127.0.0.1:" + port)
                .set("language", "zh")
                .set("messages_dir", "messages");
        WebConfig config = WebConfig.from(stub);

        Messages messages = new Messages(msgDir, config.language);
        messages.load(Logger.getLogger("test"));
        UploadStore store = new UploadStore(dataDir, imagesDir);
        WebServer server = new WebServer(Logger.getLogger("test"), config, store,
                new Sessions(3600_000L, 300_000L), messages,
                new WebServer.GameBridge() {
                    public void notify(String player, String key, String... pairs) {
                    }

                    public boolean isOnline(String player) {
                        return false;
                    }

                    public String serverMotd() {
                        return "TEST MOTD";
                    }

                    public String serverName() {
                        return "Test Server";
                    }
                });
        server.setPluginInfo("TestPlugin", "1.2.3");
        if (!server.start()) {
            System.out.println("服务器起不来");
            System.exit(1);
        }
        String base = "http://127.0.0.1:" + port;

        try {
            // 每个页面应该渲染出来的元素 —— 声明式，多一个少一个都会报出来。
            // 顶栏链接和页脚权限句本来就是页面专属，所以各页不同是正常的。
            checkPage(base + "/", "首页", List.of(
                    "site.brand", "site.nav_admin",
                    "site.home_heading", "site.home_lede",
                    "site.home_first_step_title", "site.home_button",
                    "site.home_second_step_title"));
            checkPage(base + "/upload", "上传页", List.of(
                    "site.brand", "site.nav_reverify",
                    "site.upload_heading", "site.upload_lede", "site.upload_button",
                    "site.upload_nickname_title", "site.upload_footer"));
            checkHeadings(base);
            checkFooterServerInfo(base);
            checkAdminPage(base);
            checkEveryAssetResolves(base);
            checkHiddenAttributeWorks(base);
            checkPage(base + "/result", "结果页", List.of(
                    "site.brand", "site.nav_reupload",
                    "site.result_heading", "site.result_rename_title",
                    "site.result_rename_button"));
        } finally {
            server.stop();
        }

        System.out.println();
        System.out.println("渲染出来的元素：" + ok + " 个，缺失：" + bad + " 个");
        if (bad > 0) {
            System.exit(1);
        }
    }

    /** 首页主标题必须是「上传图片」。 */
    /** 把某个键的值替换掉（键不存在就追加到末尾）。 */
    private static String override(String yaml, String key, String value) {
        String line = key + ": \"" + value + "\"";
        if (java.util.regex.Pattern.compile("^" + java.util.regex.Pattern.quote(key) + ":",
                java.util.regex.Pattern.MULTILINE).matcher(yaml).find()) {
            return yaml.replaceAll("(?m)^" + java.util.regex.Pattern.quote(key) + ":.*$",
                    java.util.regex.Matcher.quoteReplacement(line));
        }
        return yaml + (yaml.endsWith("\n") ? "" : "\n") + line + "\n";
    }

    /**
     * 首页主标题要由 site.home_heading 驱动。
     *
     * 另外单独查真实语言文件里没写错字 —— 之前这里是在渲染出来的页面上找
     * 「上传图片」，但测试用的是 CUSTOM- 值，那种写法根本测不到真实文案。
     */
    private static void checkHeadings(String base) throws Exception {
        System.out.println("\n=== 首页主标题 ===");
        String home = fetch(base + "/").replaceAll("(?s)<script>window\\.IMGUP_.*?</script>", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<h1[^>]*>.*?</h1>", java.util.regex.Pattern.DOTALL).matcher(home);
        String heading = m.find() ? m.group(0).replaceAll("<[^>]+>", "").trim() : "";
        if (heading.contains("CUSTOM-home_heading")) {
            ok++;
            System.out.println("  [OK]   首页主标题来自 site.home_heading");
        } else {
            bad++;
            System.out.println("  [!!]   首页主标题不是 site.home_heading 的值，拿到「"
                    + heading + "」");
        }

        // 真实语言文件里不能出现「上传海报」这种旧措辞
        String zh = Resources.readText("messages/zh.yml");
        String en = Resources.readText("messages/en.yml");
        if (zh != null && en != null && !zh.contains("上传海报") && !en.contains("Upload a poster")) {
            ok++;
            System.out.println("  [OK]   语言文件里没有旧的「上传海报」措辞");
        } else {
            bad++;
            System.out.println("  [!!]   语言文件里还有「上传海报」或 \"Upload a poster\"");
        }
    }

    /**
     * 页脚要显示服务器自己的 motd 和服务器名 —— 值来自 server.properties，
     * 由插件的 GameBridge 实时提供，不是写死在文案里的。
     */
    private static void checkFooterServerInfo(String base) throws Exception {
        System.out.println("\n=== 页脚嵌服务器信息 ===");
        for (String path : new String[]{"/", "/upload", "/result", "/admin"}) {
            String html = fetch(base + path)
                    .replaceAll("(?s)<script>window\\.IMGUP_.*?</script>", "");
            boolean motd = html.contains("TEST MOTD");
            boolean server = html.contains("Test Server");
            if (motd && server) {
                ok++;
                System.out.println("  [OK]   " + path + " 页脚有 motd 和服务器名");
            } else {
                bad++;
                System.out.println("  [!!]   " + path + " 页脚缺 "
                        + (motd ? "" : "motd ") + (server ? "" : "服务器名"));
            }
        }
        // 文案里的 {motd} / {server} 占位符必须被替换掉，不能在页面上露出来
        String home = fetch(base + "/").replaceAll("(?s)<script>window\\.IMGUP_.*?</script>", "");
        if (home.contains("{motd}") || home.contains("{server}")) {
            bad++;
            System.out.println("  [!!]   页面上还露着 {motd} / {server} 占位符");
        } else {
            ok++;
            System.out.println("  [OK]   占位符都被替换掉了");
        }
    }

    /**
     * 页面里引用的每个本地资源都必须真能取到。
     *
     * 这条是血的教训：样式表文件名改过之后，三个页面还指着旧名字，
     * 页面上一点样式都没有，但「页面元素齐不齐」的测试完全看不出来。
     */
    private static void checkEveryAssetResolves(String base) throws Exception {
        System.out.println("\n=== 页面引用的资源都要能取到 ===");
        java.util.regex.Pattern ref =
                java.util.regex.Pattern.compile("(?:href|src)=\"(/[^\"#?]*)\"");
        java.util.TreeSet<String> checked = new java.util.TreeSet<>();
        java.util.List<String> broken = new java.util.ArrayList<>();

        for (String page : new String[]{"/", "/upload", "/result", "/admin"}) {
            String html = fetch(base + page);
            java.util.regex.Matcher m = ref.matcher(html);
            while (m.find()) {
                String path = m.group(1);
                // 模板变量（$url 之类）和纯锚点跳过
                if (path.contains("$") || path.isEmpty()) {
                    continue;
                }
                if (!checked.add(path)) {
                    continue;
                }
                int status = statusOf(base + path);
                // 204 = 没有 favicon 时的正常回应
                if (status != 200 && status != 204) {
                    broken.add(path + " -> HTTP " + status);
                }
            }
        }

        System.out.println("     检查了 " + checked.size() + " 个引用：" + checked);
        if (broken.isEmpty()) {
            ok++;
            System.out.println("  [OK]   所有引用的资源都能取到");
        } else {
            bad++;
            System.out.println("  [!!]   这些引用取不到（页面会缺样式或脚本）：" + broken);
        }

        // 备案号是「隐形链接」：必须是链接、指向工信部，但视觉上不做成链接样子
        System.out.println("\n=== 备案号必须是能点开的隐形链接 ===");
        String css = fetch(base + "/static/app.css");
        boolean noUnderline = css.matches("(?s).*\\.foot-icp a[^{]*\\{[^}]*text-decoration:\\s*none.*");
        boolean inherits = css.matches("(?s).*\\.foot-icp a[^{]*\\{[^}]*color:\\s*inherit.*");
        if (noUnderline && inherits) {
            ok++;
            System.out.println("  [OK]   备案号链接的样式是隐形的（同色、无下划线）");
        } else {
            bad++;
            System.out.println("  [!!]   备案号链接样式不是隐形的（无下划线=" + noUnderline
                    + "，同色=" + inherits + "）");
        }
        if (css.contains(".foot-icp a:focus-visible")) {
            ok++;
            System.out.println("  [OK]   键盘焦点有可见轮廓（隐形不影响可用性）");
        } else {
            bad++;
            System.out.println("  [!!]   隐形链接缺焦点样式，键盘用户会迷失");
        }

        // serverText 必须判断「解析结果是不是还等于键名」。
        // 服务端下发的键如果语言文件里没声明，t() 会把键名原样返回 ——
        // 曾经因此把 err.not_confirmed 直接显示给玩家。
        System.out.println("\n=== 服务端下发的错误键要有兜底 ===");
        for (String page : new String[]{"/", "/upload", "/result", "/admin"}) {
            String html = fetch(base + page);
            if (html.contains("resolved !== data.message_key")) {
                ok++;
                System.out.println("  [OK]   " + page + " 的 serverText 会退回本地兜底");
            } else {
                bad++;
                System.out.println("  [!!]   " + page + " 的 serverText 缺兜底，"
                        + "服务端键缺失时会把键名显示给玩家");
            }
        }

        // 没配备案号时，页脚不该多出一行空的「备案号：」
        System.out.println("\n=== 没配备案号时不显示这一行 ===");
        // 注入的文案表里本来就有 site.footer_icp 这个键，先剥掉 <script> 再查
        String noIcp = fetch(base + "/")
                .replaceAll("(?s)<script>window\\.IMGUP_.*?</script>", "");
        if (!noIcp.contains("foot-icp") && !noIcp.contains("备案号：")) {
            ok++;
            System.out.println("  [OK]   没配备案号时页脚没有多余的空行");
        } else {
            bad++;
            System.out.println("  [!!]   没配备案号，页脚却出现了这一行");
        }

        System.out.println("\n=== 页脚的插件信息行 ===");
        String home = fetch(base + "/");
        if (home.contains("PLUGIN TestPlugin 1.2.3")) {
            ok++;
            System.out.println("  [OK]   页脚显示插件名和版本（占位符已替换）");
        } else {
            bad++;
            System.out.println("  [!!]   页脚没有替换 {name}/{version} 占位符");
        }
        if (home.contains("href=\"https://github.com/DICEALPHAHU/IMGup\"")) {
            ok++;
            System.out.println("  [OK]   页脚有 GitHub 链接");
        } else {
            bad++;
            System.out.println("  [!!]   页脚没有 GitHub 链接");
        }
        if (home.contains("rel=\"noopener noreferrer\"") && home.contains("target=\"_blank\"")) {
            ok++;
            System.out.println("  [OK]   外链带 target=_blank 和 rel=noopener");
        } else {
            bad++;
            System.out.println("  [!!]   外链缺少 target/rel（有安全与体验问题）");
        }

        // 单独把样式表和脚本点出来，它们没了页面就废了
        for (String must : new String[]{"/static/app.css", "/static/i18n.js"}) {
            int status = statusOf(base + must);
            if (status == 200) {
                ok++;
                System.out.println("  [OK]   " + must + " 可访问");
            } else {
                bad++;
                System.out.println("  [!!]   " + must + " 取不到，HTTP " + status);
            }
        }
    }

    /**
     * hidden 属性必须真能隐藏元素。
     *
     * 浏览器默认的 [hidden] { display: none } 是 UA 样式，作者样式里任何一条
     * display 都能盖过它。曾经 .lang-menu 写了 display:flex，于是语言下拉菜单
     * 一直展开着挡住页面 —— 而当时所有测试都是绿的，因为没人查过这件事。
     */
    private static void checkHiddenAttributeWorks(String base) throws Exception {
        System.out.println("\n=== hidden 属性要真能藏住元素 ===");
        String css = fetch(base + "/static/app.css");
        String code = css.replaceAll("(?s)/\\*.*?\\*/", "");   // 去掉注释

        boolean guard = code.matches(
                "(?s).*\\[hidden\\]\\s*\\{\\s*display\\s*:\\s*none\\s*!important.*");
        if (guard) {
            ok++;
            System.out.println("  [OK]   [hidden] { display: none !important } 在");
        } else {
            bad++;
            System.out.println("  [!!]   样式表里没有 [hidden] 兜底规则，"
                    + "hidden 属性会被 display 盖过");
        }

        // 不许有别的 !important display 跟它竞争（否则元素可能藏不住）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("display\\s*:\\s*([^;]+?)\\s*!important").matcher(code);
        java.util.List<String> rivals = new java.util.ArrayList<>();
        while (m.find()) {
            if (!"none".equals(m.group(1).trim())) {
                rivals.add(m.group(1).trim());
            }
        }
        if (rivals.isEmpty()) {
            ok++;
            System.out.println("  [OK]   没有别的 !important display 跟它竞争");
        } else {
            bad++;
            System.out.println("  [!!]   这些 !important display 会跟 [hidden] 竞争：" + rivals);
        }

        // .lang-menu 就是当初出问题的那个：它必须真的被 hidden 属性控制住
        if (code.contains(".lang-menu") && guard) {
            ok++;
            System.out.println("  [OK]   语言下拉菜单（.lang-menu）被 [hidden] 管住了");
        } else {
            bad++;
            System.out.println("  [!!]   语言菜单可能又会一直展开");
        }
    }

    /** 后台页面必须能打开（未登录时显示登录框）。 */
    private static void checkAdminPage(String base) throws Exception {
        System.out.println("\n=== 后台页面 ===");
        String html = fetch(base + "/admin");
        boolean login = html.contains("id=\"loginForm\"");
        boolean list = html.contains("/admin/api/list");
        if (login && list) {
            ok++;
            System.out.println("  [OK]   /admin 能打开，含登录框和列表逻辑");
        } else {
            bad++;
            System.out.println("  [!!]   /admin 页面不对（登录框=" + login + "，列表=" + list + "）");
        }
    }

    /** 检查某个页面渲染出了预期的元素，且没有留下任何未替换的占位符。 */
    private static void checkPage(String url, String label, List<String> expected)
            throws Exception {
        String html = fetch(url);
        // 关键：注入的文案表 JSON 里含所有键和值，直接 contains 必然命中，
        // 那样测的就不是「元素渲染了没有」。先把那段 <script> 摘掉再找。
        String body = html.replaceAll("(?s)<script>window\\.IMGUP_.*?</script>", "");

        System.out.println("\n=== " + label + " " + url + " ===");
        for (String key : expected) {
            String marker = "CUSTOM-" + key.substring("site.".length());
            if (body.contains(marker)) {
                ok++;
                System.out.println("  [OK]   " + key);
            } else {
                bad++;
                System.out.println("  [!!]   " + key + " 没渲染出来（应该是 " + marker + "）");
            }
        }

        // 没有任何 $xxx 残留 —— 占位符拼错、或忘了往模板里传值都会在这里暴露
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\$[A-Za-z_][A-Za-z0-9_]*").matcher(body);
        while (m.find()) {
            bad++;
            System.out.println("  [!!]   页面里有没替换掉的占位符：" + m.group());
        }
    }

    /** 只取状态码，不读 body。 */
    private static int statusOf(String url) throws Exception {
        java.net.http.HttpResponse<Void> response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .method("GET", java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private static String fetch(String url) throws Exception {
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
