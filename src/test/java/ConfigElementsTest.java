import com.huhumc.imgup.Messages;
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
                "site.title", "site.brand", "site.nav_admin",
                "site.home_heading", "site.home_lede", "site.home_first_step_title",
                "site.home_button", "site.home_second_step_title",
                "site.upload_heading", "site.upload_lede", "site.upload_button",
                "site.upload_nickname_title", "site.upload_footer",
                "site.nav_reupload", "site.nav_reverify",
                "site.result_heading", "site.result_rename_title", "site.result_rename_button",
                "site.footer_note", "site.footer_permission", "site.footer_permission_suffix",
                "site.upload_footer", "site.nav_reupload", "site.nav_reverify");
        // 这些键只在特定页面出现，跨页断言时跳过
        List<String> pageSpecific = List.of(
                "site.nav_reverify", "site.nav_reupload", "site.upload_footer");
        List<String> lines = new ArrayList<>();
        lines.add("meta.name: \"中文\"");
        lines.add("meta.code: \"zh\"");
        for (String key : elementKeys) {
            lines.add(key + ": \"CUSTOM-" + key.substring("site.".length()) + "\"");
        }
        Files.writeString(msgDir.resolve("zh.yml"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(msgDir.resolve("en.yml"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);

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
                    public void notify(String player, String message) {
                    }

                    public boolean isOnline(String player) {
                        return false;
                    }
                });
        if (!server.start()) {
            System.out.println("服务器起不来");
            System.exit(1);
        }
        String base = "http://127.0.0.1:" + port;

        try {
            // 每个页面应该渲染出来的元素 —— 声明式，多一个少一个都会报出来。
            // 顶栏链接和页脚权限句本来就是页面专属，所以各页不同是正常的。
            checkPage(base + "/", "首页", List.of(
                    "site.title", "site.brand", "site.nav_reupload",
                    "site.home_heading", "site.home_lede",
                    "site.home_first_step_title", "site.home_button",
                    "site.home_second_step_title",
                    "site.footer_note", "site.footer_permission",
                    "site.footer_permission_suffix"));
            checkPage(base + "/upload", "上传页", List.of(
                    "site.title", "site.brand", "site.nav_reverify",
                    "site.upload_heading", "site.upload_lede", "site.upload_button",
                    "site.upload_nickname_title", "site.upload_footer",
                    "site.footer_note"));
            checkPage(base + "/result", "结果页", List.of(
                    "site.title", "site.brand", "site.nav_reupload",
                    "site.result_heading", "site.result_rename_title",
                    "site.result_rename_button", "site.footer_note"));
        } finally {
            server.stop();
        }

        System.out.println();
        System.out.println("渲染出来的元素：" + ok + " 个，缺失：" + bad + " 个");
        if (bad > 0) {
            System.exit(1);
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
