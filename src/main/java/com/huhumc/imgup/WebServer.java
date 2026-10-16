package com.huhumc.imgup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 插件内置的网站服务器。
 *
 * 用 JDK 自带的 com.sun.net.httpserver —— 不引任何依赖，所以整个 ImgUp 就是
 * 一个 jar：没有 Docker、没有 Python、没有 RCON、没有 shared_secret。
 * 上传码直接落在插件内存里，玩家在游戏里敲完 /imgup，网页当场就知道。
 *
 * 关于线程：请求处理跑在虚拟线程上（服务端是 Java 25）。和游戏世界的交互
 * 一律通过主线程调度器（见 GameBridge），请求线程绝不碰 Bukkit API。
 */
public final class WebServer {

    /** 和游戏侧交互的桥：只暴露这一个方法给 web 层，避免它直接碰 Bukkit。 */
    public interface GameBridge {
        /**
         * 在游戏内给玩家发一条提示；玩家不在线时静默忽略。
         *
         * key 是语言文件里的键，pairs 是 {名字} 替换对（key1, value1, key2, value2…）——
         * 玩家看到的是他自己客户端语言的那一份文案，而不是某一种写死的语言。
         */
        void notify(String player, String key, String... pairs);

        /** 玩家是否在线（用于后台列表里显示状态）。 */
        boolean isOnline(String player);

        /** 服务器 motd（来自 server.properties 的 motd）。 */
        String serverMotd();

        /** 服务器名（来自 server.properties 的 server-name）。 */
        String serverName();
    }

    private static final String COOKIE_SESSION = "imgup_sid";
    private static final String COOKIE_LANG = "imgup_lang";
    private static final String COOKIE_ADMIN = "imgup_admin";

    private final Logger log;
    private final WebConfig config;
    private final UploadStore store;
    private final Sessions sessions;
    private final GameBridge game;
    private final Messages messages;

    /**
     * 插件版本号，由插件在启动时告知（Bukkit 的 getDescription().getVersion()）。
     *
     * 不写死在这里 —— 写死就一定会和 plugin.yml 里的版本号漂移，
     * /api/health 报出来的版本没人敢信。
     */
    private String pluginVersion = "unknown";

    /** 插件名，同样由插件在启动时告知（plugin.yml 的 name）。 */
    private String pluginName = "ImgUp";

    /** 源码仓库地址。只在这里定义一处，模板用 $plugin_url 取。 */
    private static final String PLUGIN_URL = "https://github.com/DICEALPHAHU/IMGup";

    /** 工信部备案查询地址。备案号按国内惯例链到这里。 */
    private static final String ICP_LOOKUP_URL = "https://beian.miit.gov.cn/";

    /** 每个 IP 的取码记录，用于限流。 */
    private final Map<String, List<Long>> codeHits = new LinkedHashMap<>();

    /**
     * 后台登录会话：token -> 过期时间。
     *
     * 只在内存里，重启服务端就要重新登录 —— 管理员本来就在服务器边上，
     * 这个取舍换来的是「不落盘、没有额外凭据文件」。
     */
    private final Map<String, Long> adminTokens = new LinkedHashMap<>();

    private static final long ADMIN_TTL_MILLIS = 12 * 3600_000L;

    private HttpServer server;
    private ExecutorService executor;
    private volatile boolean running;

    public void setPluginInfo(String name, String version) {
        if (name != null && !name.isBlank()) {
            this.pluginName = name;
        }
        if (version != null && !version.isBlank()) {
            this.pluginVersion = version;
        }
    }

    public WebServer(Logger log, WebConfig config, UploadStore store,
                     Sessions sessions, Messages messages, GameBridge game) {
        this.log = log;
        this.config = config;
        this.store = store;
        this.sessions = sessions;
        this.messages = messages;
        this.game = game;
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return server == null ? config.port : server.getAddress().getPort();
    }

    // ------------------------------------------------------------ 启停

    /** 启动网站。端口被占用等失败只记日志、不抛异常，免得把整个插件拖挂。 */
    public boolean start() {
        if (running) {
            return true;
        }
        try {
            InetSocketAddress address = config.host == null || config.host.isEmpty()
                    ? new InetSocketAddress(config.port)
                    : new InetSocketAddress(config.host, config.port);
            server = HttpServer.create(address, 64);

            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "ImgUp-Web");
                thread.setDaemon(true);
                return thread;
            };
            executor = Executors.newThreadPerTaskExecutor(factory);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            running = true;
            log.info("内置网站已启动：http://" + displayHost() + ":" + port()
                    + "（对外地址 " + config.base() + "）");
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "内置网站启动失败，插件其余功能仍可用："
                    + "http://" + displayHost() + ":" + config.port, e);
            stop();
            return false;
        }
    }

    public void stop() {
        running = false;
        if (server != null) {
            try {
                server.stop(0);
            } catch (RuntimeException ignored) {
                // 停的时候出问题无所谓
            }
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    private String displayHost() {
        String host = config.host;
        return (host == null || host.isEmpty() || host.equals("0.0.0.0")) ? "127.0.0.1" : host;
    }

    // ------------------------------------------------------------ 入口

    private void handle(HttpExchange exchange) {
        long started = System.nanoTime();
        int status = 500;
        String path = exchange.getRequestURI().getPath();
        try {
            // 请求体上限：文件上限 + 一点 multipart 包装的余量
            long declared = headerLong(exchange, "Content-Length", -1);
            int limit = config.maxUploadBytes + 1024 * 1024;
            if (declared > limit) {
                status = 413;
                sendJson(exchange, 413, error("too_large", 413, "图片超过体积上限",
                        Map.of("max", humanBytes(config.maxUploadBytes))));
                return;
            }
            status = route(exchange, path);
        } catch (IllegalArgumentException e) {
            status = 400;
            safeJson(exchange, 400, error("bad_request", 400, "请求格式不对", null));
        } catch (Throwable t) {
            log.log(Level.WARNING, "处理 " + exchange.getRequestMethod() + " " + path + " 时出错", t);
            status = 500;
            safeJson(exchange, 500, error("internal", 500, "服务器内部错误，请稍后重试", null));
        } finally {
            if (log.isLoggable(Level.FINE)) {
                long ms = (System.nanoTime() - started) / 1_000_000L;
                log.fine(exchange.getRequestMethod() + " " + path + " -> " + status + " (" + ms + "ms)");
            }
            exchange.close();
        }
    }

    private int route(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"POST".equals(method) && !"HEAD".equals(method)) {
            return sendJson(exchange, 405, error("bad_request", 405, "不支持的请求方法", null));
        }

        if (path.equals("/api/health")) {
            return apiHealth(exchange);
        }
        if (path.equals("/api/newcode") && "POST".equals(method)) {
            return apiNewCode(exchange);
        }
        if (path.equals("/api/codestatus")) {
            return apiCodeStatus(exchange);
        }
        if (path.equals("/api/claim") && "POST".equals(method)) {
            return apiClaim(exchange);
        }
        if (path.equals("/api/session")) {
            return apiSession(exchange);
        }
        if (path.equals("/api/upload") && "POST".equals(method)) {
            return apiUpload(exchange);
        }
        if (path.equals("/api/rename") && "POST".equals(method)) {
            return apiRename(exchange);
        }
        if (path.equals("/api/lang")) {
            return apiLang(exchange);
        }
        if (path.equals("/admin") || path.equals("/admin/")) {
            return serveAdminPage(exchange);
        }
        if (path.startsWith("/admin/api/")) {
            return adminApi(exchange, path.substring("/admin/api/".length()));
        }
        if (path.equals("/api/languages")) {
            return apiLanguages(exchange);
        }
        if (path.startsWith("/uploads/")) {
            return servePublished(exchange, path.substring("/uploads/".length()));
        }
        if (path.startsWith("/preview/")) {
            return servePreview(exchange, path.substring("/preview/".length()));
        }
        if (path.startsWith("/static/")) {
            return serveStatic(exchange, path.substring("/static/".length()));
        }
        if (path.equals("/favicon.ico")) {
            exchange.sendResponseHeaders(204, -1);
            return 204;
        }
        if (path.equals("/") ) {
            return servePage(exchange, "index");
        }
        if (path.equals("/upload")) {
            return servePage(exchange, "upload");
        }
        if (path.equals("/result")) {
            return servePage(exchange, "result");
        }
        return sendJson(exchange, 404, error("not_found", 404, "没有这个接口", null));
    }

    // ------------------------------------------------------------ API

    private int apiHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("version", pluginVersion);
        body.put("web", true);
        body.put("images_dir", store.imagesDir().toString());
        body.put("counts", store.counts());
        body.put("sessions", sessions.sessionCount());
        return sendJson(exchange, 200, body);
    }

    private int apiNewCode(HttpExchange exchange) throws IOException {
        String ip = remoteIp(exchange);
        if (!allowNewCode(ip)) {
            return sendJson(exchange, 429, error("rate_limited", 429,
                    "获取上传码太频繁了，请过一会儿再试", null));
        }
        Sessions.Session session = currentSession(exchange, false);
        boolean created = false;
        if (session == null) {
            session = sessions.create();
            created = true;
        }
        String code = sessions.newCode(session);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("code", code);
        body.put("expires_in", (int) (config.codeTtlMillis / 1000L));

        if (created) {
            return sendJson(exchange, 200, body, List.of(
                    cookie(COOKIE_SESSION, session.id,
                            (int) (config.codeTtlMillis / 1000L) * 12)));
        }
        return sendJson(exchange, 200, body);
    }

    /**
     * 切语言：把选择记进 cookie，下次打开这个网站还是这门语言。
     *
     * 前端拿到 200 后刷新页面即可（服务端会用新语言重新渲染）。
     */
    private int apiLang(HttpExchange exchange) throws IOException {
        String lang = queryParam(exchange, "lang");
        if (!Messages.isValidLang(lang) || !messages.has(lang)) {
            return sendJson(exchange, 400, error("bad_request", 400,
                    "不支持这个语言：" + lang, null));
        }
        String normalized = lang.toLowerCase(Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("lang", normalized);
        body.put("available", messages.languages());
        return sendJson(exchange, 200, body,
                List.of(cookie(COOKIE_LANG, normalized, 365 * 24 * 3600)));
    }

    /**
     * 语言开关的选项：列出所有可用的语言文件。
     *
     * 每项都带该语言的自称（meta.name），所以菜单上显示的是
     * 「中文（简体）」「English」这种，而不是裸的语言代码。
     */
    private int apiLanguages(HttpExchange exchange) throws IOException {
        List<Object> options = new ArrayList<>();
        for (String code : messages.languages()) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("code", code);
            option.put("name", messages.name(code));
            options.add(option);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("current", resolveLang(exchange));
        body.put("default", messages.defaultLang());
        body.put("languages", options);
        // 兼容旧字段，避免外部脚本按老格式解析时炸掉
        body.put("available", messages.languages());
        return sendJson(exchange, 200, body);
    }

    private int apiCodeStatus(HttpExchange exchange) throws IOException {
        Sessions.Session session = currentSession(exchange, false);
        if (session == null) {
            return sendJson(exchange, 401, error("no_session", 401,
                    "还没有上传会话，请先获取上传码", null));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("code", session.pendingCode == null ? "" : session.pendingCode);
        body.put("claimed", session.verified());
        body.put("player", session.player);
        long expiresIn = Math.max(0,
                (config.codeTtlMillis - (System.currentTimeMillis() - session.createdAt)) / 1000L);
        body.put("expires_in", (int) expiresIn);
        return sendJson(exchange, 200, body);
    }

    /** 兜底：玩家在游戏里确认过了，回网页手动填同一个码也能进。 */
    private int apiClaim(HttpExchange exchange) throws IOException {
        Sessions.Session session = currentSession(exchange, false);
        if (session == null) {
            session = sessions.create();
        }
        String code = Sessions.normalizeCode(Json.string(readJson(exchange), "code"));
        Sessions.ConfirmResult result = sessions.confirm(code, session.player == null
                ? "" : session.player);
        // 注意：网页端调这个接口时还没拿到玩家名，所以这里只认「这个码已经被确认过」。
        // 真正绑定玩家名是在游戏里敲 /imgup 的那一刻。
        if (result == Sessions.ConfirmResult.BAD_FORMAT) {
            return sendJson(exchange, 400, error("bad_code", 400,
                    "上传码格式不对，应该是 8 位字符", null));
        }
        if (result != Sessions.ConfirmResult.OK) {
            return sendJson(exchange, 400, error("not_confirmed", 400,
                    "这个上传码还没有在游戏里确认过", null));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("player", session.player);
        return sendJson(exchange, 200, body);
    }

    private int apiSession(HttpExchange exchange) throws IOException {
        Sessions.Session session = currentSession(exchange, false);
        if (session == null) {
            return sendJson(exchange, 401, error("no_session", 401,
                    "会话不存在或已过期，请重新获取上传码", null));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("verified", session.verified());
        body.put("player", session.player);
        List<Object> uploads = new ArrayList<>();
        if (session.verified()) {
            for (UploadStore.Item item : store.byPlayer(session.player)) {
                uploads.add(item.toJson(config));
            }
        }
        body.put("uploads", uploads);
        return sendJson(exchange, 200, body);
    }

    private int apiUpload(HttpExchange exchange) throws IOException {
        Sessions.Session session = currentSession(exchange, false);
        if (session == null) {
            return sendJson(exchange, 401, error("no_session", 401,
                    "会话不存在或已过期，请重新获取上传码", null));
        }
        if (!session.verified()) {
            return sendJson(exchange, 401, error("not_verified", 401,
                    "还没在游戏里确认上传码", null));
        }
        String player = session.player;

        String contentType = header(exchange, "Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT)
                .startsWith("multipart/form-data")) {
            return sendJson(exchange, 400, error("bad_request", 400,
                    "上传必须用 multipart/form-data", null));
        }

        byte[] raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = Multipart.readBody(in, headerLong(exchange, "Content-Length", -1),
                    config.maxUploadBytes + 1024 * 1024);
        } catch (Multipart.TooLargeException e) {
            return sendJson(exchange, 413, error("too_large", 413,
                    "图片超过体积上限", Map.of("max", humanBytes(config.maxUploadBytes))));
        }

        Multipart.Result form;
        try {
            form = Multipart.parse(raw, contentType, config.maxUploadBytes,
                    config.maxNicknameLength * 4);
        } catch (Multipart.BadRequestException e) {
            if ("file_too_large".equals(e.getMessage())) {
                return sendJson(exchange, 413, error("too_large", 413,
                        "图片超过体积上限", Map.of("max", humanBytes(config.maxUploadBytes))));
            }
            return sendJson(exchange, 400, error("bad_request", 400, e.getMessage(), null));
        }

        Multipart.FilePart file = form.firstFile();
        if (file == null || file.data().length == 0) {
            return sendJson(exchange, 400, error("no_file", 400, "没有收到文件，请选择一张图片", null));
        }

        ImageTools.Processed processed;
        try {
            processed = ImageTools.process(file.data(), config.maxUploadBytes,
                    config.maxDimension, config.maxPixels, config.jpegQuality);
        } catch (ImageTools.ImageException e) {
            return sendJson(exchange, 400, error(e.code(), 400, e.getMessage(), null));
        }

        if (config.perPlayerDaily > 0 && store.uploadedToday(player) >= config.perPlayerDaily) {
            return sendJson(exchange, 429, error("rate_limited", 429,
                    "你今天上传的图片太多了，明天再来吧",
                    Map.of("daily", config.perPlayerDaily)));
        }
        if (config.pendingMax > 0) {
            Integer pending = store.counts().getOrDefault("pending", 0);
            if (pending >= config.pendingMax) {
                return sendJson(exchange, 503, error("queue_full", 503,
                        "待审核的图片太多了，请等站长处理完再上传",
                        Map.of("max", config.pendingMax)));
            }
        }

        String nickname = form.field("nickname");
        if (nickname.isEmpty()) {
            nickname = form.field("desc");
        }
        if (nickname.isEmpty()) {
            nickname = Texts.defaultNickname(file.fileName());
        }

        String fileName;
        try {
            fileName = store.makeFileName(player, nickname, processed.extension(),
                    config.maxNameLength, config.maxNicknameLength, false);
        } catch (UploadStore.NameConflictException e) {
            return sendJson(exchange, 503, error("busy", 503, e.getMessage(), null));
        }

        UploadStore.Item item;
        try {
            item = store.store(fileName, player, Texts.sanitizeNickname(nickname,
                    config.maxNicknameLength), processed.data(),
                    processed.width(), processed.height(), config.autoPublish);
        } catch (IOException e) {
            log.log(Level.WARNING, "保存上传失败 " + fileName, e);
            return sendJson(exchange, 500, error("storage_error", 500, "保存失败，请稍后重试", null));
        }

        // 通知传的是语言文件的键，插件那边按玩家客户端语言取文案
        notifyPlayer(player, config.autoPublish
                ? "game.notify_uploaded" : "game.notify_uploaded_pending",
                "name", fileName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("name", item.name);
        body.put("nickname", item.nickname);
        body.put("status", item.status);
        body.put("width", item.width);
        body.put("height", item.height);
        body.put("bytes", item.bytes);
        body.put("url", UploadStore.STATUS_PUBLISHED.equals(item.status)
                ? config.uploadUrl(item.name) : "");
        return sendJson(exchange, 200, body);
    }

    private int apiRename(HttpExchange exchange) throws IOException {
        Sessions.Session session = currentSession(exchange, false);
        if (session == null) {
            return sendJson(exchange, 401, error("no_session", 401,
                    "会话不存在或已过期，请重新获取上传码", null));
        }
        if (!session.verified()) {
            return sendJson(exchange, 401, error("not_verified", 401,
                    "还没在游戏里确认上传码", null));
        }
        Map<String, Object> payload = readJson(exchange);
        String name = Json.string(payload, "name");
        String nickname = Json.string(payload, "nickname").trim();
        String player = session.player;

        if (!Texts.isSafeFileName(name, config.maxNameLength)) {
            return sendJson(exchange, 400, error("bad_name", 400, "文件名不合法", null));
        }
        UploadStore.Item item = store.get(name);
        if (item == null) {
            return sendJson(exchange, 404, error("not_found", 404, "找不到这张图片", null));
        }
        if (!UploadStore.STATUS_PENDING.equals(item.status)) {
            return sendJson(exchange, 400, error("not_renamable", 400,
                    "只有待审核的图片可以改名；已经发布的图片改了名，游戏里就找不到了", null));
        }
        if (!player.equals(item.player)) {
            return sendJson(exchange, 403, error("not_owner", 403, "这不是你上传的图片", null));
        }
        if (nickname.isEmpty()) {
            return sendJson(exchange, 400, error("bad_nickname", 400, "昵称不能是空的", null));
        }

        String extension = "png";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }

        String newName;
        try {
            newName = store.makeFileName(player, nickname, extension,
                    config.maxNameLength, config.maxNicknameLength, true);
        } catch (UploadStore.NameConflictException e) {
            return sendJson(exchange, 409, error("name_taken", 409, e.getMessage(), null));
        }
        // 裸名字空闲、但自己的 -2 变体还在时也要拦：否则界面上两张图同名，玩家分不清
        if (!newName.equals(name) && store.nicknameTaken(player, nickname,
                config.maxNicknameLength, name)) {
            return sendJson(exchange, 409, error("name_taken", 409,
                    "你已经有叫这个名字的图片了，换一个昵称吧", null));
        }

        try {
            UploadStore.Item updated = store.rename(name, newName,
                    Texts.sanitizeNickname(nickname, config.maxNicknameLength));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("name", updated.name);
            body.put("nickname", updated.nickname);
            body.put("status", updated.status);
            body.put("url", "");
            return sendJson(exchange, 200, body);
        } catch (IOException e) {
            return sendJson(exchange, 409, error("name_taken", 409,
                    "这个名字已经被别的图片用了，换一个吧", null));
        }
    }

    // ------------------------------------------------------------ 后台
    //
    // 审核做在网页上（管理员入口），因为站长要「管理 image 的所有图片」——
    // 光靠游戏内指令翻页看列表不方便。游戏内的 /imgup list|ok|no 也保留着，
    // 两种方式改的是同一份数据。

    private int serveAdminPage(HttpExchange exchange) throws IOException {
        if (!config.adminEnabled) {
            return sendJson(exchange, 404, error("not_found", 404, "后台已关闭", null));
        }
        String html = Resources.readText("web/admin.html");
        if (html == null) {
            return sendJson(exchange, 500, error("template_missing", 500, "后台页面缺失", null));
        }
        return serveRenderedPage(exchange, html, "admin",
                pageValues(currentSession(exchange, false)));
    }

    /** /admin/api/<动作> */
    private int adminApi(HttpExchange exchange, String action) throws IOException {
        if (!config.adminEnabled) {
            return sendJson(exchange, 404, error("not_found", 404, "后台已关闭", null));
        }
        // 登录接口不需要已登录
        if (action.equals("login")) {
            return adminLogin(exchange);
        }
        if (!checkAdmin(exchange)) {
            return sendJson(exchange, 401, error("unauthorized", 401, "请先登录", null));
        }
        return switch (action) {
            case "logout" -> adminLogout(exchange);
            case "list" -> adminList(exchange);
            case "approve" -> adminSetStatus(exchange, "approve");
            case "reject" -> adminSetStatus(exchange, "reject");
            case "delete" -> adminDelete(exchange);
            case "approve-all" -> adminApproveAll(exchange);
            default -> sendJson(exchange, 404, error("not_found", 404, "没有这个后台接口", null));
        };
    }

    private int adminLogin(HttpExchange exchange) throws IOException {
        if (config.adminPassword.isEmpty()) {
            // 没设密码就不给登录，避免空密码直接进后台
            return sendJson(exchange, 401, error("bad_password", 401,
                    "后台没有设置密码，请在 config.yml 里填 web.admin_password", null));
        }
        String given = Json.string(readJson(exchange), "password");
        if (!constantTimeEquals(given, config.adminPassword)) {
            log.warning("后台登录失败，来自 " + remoteIp(exchange));
            return sendJson(exchange, 401, error("bad_password", 401, "密码不对", null));
        }
        String token = newToken();
        long expiresAt = System.currentTimeMillis() + ADMIN_TTL_MILLIS;
        synchronized (adminTokens) {
            adminTokens.put(token, expiresAt);
            adminTokens.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis());
        }
        log.info("后台登录成功，来自 " + remoteIp(exchange));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return sendJson(exchange, 200, body,
                List.of(cookie(COOKIE_ADMIN, token, (int) (ADMIN_TTL_MILLIS / 1000L))));
    }

    private int adminLogout(HttpExchange exchange) throws IOException {
        String token = cookieValue(exchange, COOKIE_ADMIN);
        if (token != null) {
            synchronized (adminTokens) {
                adminTokens.remove(token);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        // Max-Age=0 让浏览器立刻丢掉这个 cookie
        return sendJson(exchange, 200, body, List.of(cookie(COOKIE_ADMIN, "", 0)));
    }

    /** 看所有图片（可按状态筛），带统计数字给前端画 tab 角标。 */
    private int adminList(HttpExchange exchange) throws IOException {
        String filter = queryParam(exchange, "filter");
        if (filter.isEmpty() || filter.equals("all")) {
            filter = null;
        } else if (!UploadStore.STATUS_PENDING.equals(filter)
                && !UploadStore.STATUS_PUBLISHED.equals(filter)
                && !UploadStore.STATUS_REJECTED.equals(filter)) {
            return sendJson(exchange, 400, error("bad_request", 400, "筛选值不对", null));
        }
        List<Object> items = new ArrayList<>();
        for (UploadStore.Item item : store.byStatus(filter)) {
            items.add(item.toJson(config));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("items", items);
        body.put("counts", store.counts());
        return sendJson(exchange, 200, body);
    }

    private int adminSetStatus(HttpExchange exchange, String action) throws IOException {
        String name = Json.string(readJson(exchange), "name");
        if (!Texts.isSafeFileName(name, config.maxNameLength)) {
            return sendJson(exchange, 400, error("bad_name", 400, "文件名不合法", null));
        }
        UploadStore.Item item = store.get(name);
        if (item == null) {
            return sendJson(exchange, 404, error("not_found", 404, "找不到这张图片", null));
        }
        try {
            UploadStore.Item done;
            if (action.equals("approve")) {
                done = store.approve(name);
                notifyPlayer(done.player, "game.notify_approved", "name", done.name);
            } else {
                done = store.reject(name, "");
                notifyPlayer(done.player, "game.notify_rejected", "name", done.name);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("name", done.name);
            body.put("status", done.status);
            return sendJson(exchange, 200, body);
        } catch (IOException e) {
            log.log(Level.WARNING, "后台操作失败 " + action + " " + name, e);
            return sendJson(exchange, 500, error("storage_error", 500,
                    "操作失败：" + e.getMessage(), null));
        }
    }

    private int adminDelete(HttpExchange exchange) throws IOException {
        String name = Json.string(readJson(exchange), "name");
        if (!Texts.isSafeFileName(name, config.maxNameLength)) {
            return sendJson(exchange, 400, error("bad_name", 400, "文件名不合法", null));
        }
        boolean removed = store.delete(name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", removed);
        if (!removed) {
            body.put("message", "找不到这张图片");
            body.put("message_key", "err.not_found");
            return sendJson(exchange, 404, body);
        }
        return sendJson(exchange, 200, body);
    }

    /** 一键通过所有待审核的图。 */
    private int adminApproveAll(HttpExchange exchange) throws IOException {
        List<String> approved = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (UploadStore.Item item : store.byStatus(UploadStore.STATUS_PENDING)) {
            try {
                store.approve(item.name);
                approved.add(item.name);
                notifyPlayer(item.player, "game.notify_approved", "name", item.name);
            } catch (IOException e) {
                failed.add(item.name);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("approved", approved);
        body.put("failed", failed);
        return sendJson(exchange, 200, body);
    }

    /** 已登录且在有效期内？ */
    private boolean checkAdmin(HttpExchange exchange) {
        String token = cookieValue(exchange, COOKIE_ADMIN);
        if (token == null || token.isEmpty()) {
            return false;
        }
        synchronized (adminTokens) {
            Long expiresAt = adminTokens.get(token);
            if (expiresAt == null) {
                return false;
            }
            if (expiresAt < System.currentTimeMillis()) {
                adminTokens.remove(token);
                return false;
            }
            return true;
        }
    }

    /**
     * 常数时间比较，避免根据响应时间逐字符猜密码。
     * 长度不同也走完整循环，只在最后返回结果。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        int diff = left.length ^ right.length;
        for (int i = 0; i < left.length; i++) {
            diff |= left[i] ^ right[i % Math.max(1, right.length)];
        }
        return diff == 0;
    }

    private static String newToken() {
        byte[] raw = new byte[24];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0x0F, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString();
    }

    // -------------------------------------------------------- 文件服务

    private int servePublished(HttpExchange exchange, String rawName) throws IOException {
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        Path path = store.publicPath(name);
        if (path == null) {
            return sendJson(exchange, 404, error("not_found", 404, "找不到这张图片", null));
        }
        return sendFile(exchange, path, true);
    }

    /**
     * 后台预览：待审核和被拒绝的图也能看，方便站长判断。
     * 没做登录，因为只有站内页面会引用它，而文件名本身是随机化的长名字；
     * 真要严格，可以在 config.yml 里关掉 web.auto_publish 自己审核，或改文件名。
     */
    private int servePreview(HttpExchange exchange, String rawName) throws IOException {
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        if (!Texts.isSafeFileName(name, config.maxNameLength)) {
            return sendJson(exchange, 404, error("not_found", 404, "文件名不合法", null));
        }
        UploadStore.Item item = store.get(name);
        if (item == null) {
            return sendJson(exchange, 404, error("not_found", 404, "找不到这张图片", null));
        }
        Path path = store.pathOf(item);
        if (path == null || !Files.isRegularFile(path)) {
            return sendJson(exchange, 404, error("not_found", 404, "图片文件已丢失", null));
        }
        return sendFile(exchange, path, false);
    }

    private int serveStatic(HttpExchange exchange, String rawName) throws IOException {
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        // 只允许 web/ 目录下的单层文件，杜绝 ../ 穿越
        if (name.isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")) {
            return sendJson(exchange, 404, error("not_found", 404, "找不到这个资源", null));
        }
        byte[] data = Resources.read("web/" + name);
        if (data == null) {
            return sendJson(exchange, 404, error("not_found", 404, "找不到这个资源", null));
        }
        String type = name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : "application/octet-stream";
        return sendBytes(exchange, 200, data, type, "public, max-age=3600", null);
    }

    // -------------------------------------------------------- 页面

    /**
     * 每个页面都要有的模板变量。
     *
     * 抽出来是因为后台页面原本绕过 servePage 直接渲染，导致它拿不到这些值 ——
     * 页脚的 $plugin_url 就渲染成了空的 href。模板变量缺失是**静默**的
     * （渲染成空字符串），所以两条路径必须共用同一份。
     */
    /**
     * 站点名称：config 里填了就用它，留空则用 server.properties 的 server-name，
     * 再兜底用插件名。保证不会是空字符串（空了页头品牌就没了）。
     */
    private String siteName() {
        if (config.siteName != null && !config.siteName.isBlank()) {
            return config.siteName.trim();
        }
        String fromServer = serverName();
        if (fromServer != null && !fromServer.isBlank()) {
            return fromServer.trim();
        }
        return pluginName;
    }

    private Map<String, String> pageValues(Sessions.Session session) {
        String player = session == null || session.player == null ? "" : session.player;
        boolean verified = session != null && session.verified();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", Texts.escapeHtml(player));
        values.put("verified", verified ? "1" : "");
        values.put("base_url", Texts.escapeHtml(config.base()));
        values.put("auto_publish", config.autoPublish ? "1" : "");
        values.put("max_bytes_mb", String.valueOf(config.maxUploadBytes / (1024 * 1024)));
        // 页脚那行「本插件 xxx 版本 · 开源地址」用
        values.put("plugin_name", Texts.escapeHtml(pluginName));
        values.put("plugin_version", Texts.escapeHtml(pluginVersion));
        values.put("plugin_url", Texts.escapeHtml(PLUGIN_URL));
        // 品牌和标签页标题都用它
        values.put("site_name", Texts.escapeHtml(siteName()));
        return values;
    }

    private int servePage(HttpExchange exchange, String page) throws IOException {
        String html = Resources.readText("web/" + page + ".html");
        if (html == null) {
            return sendJson(exchange, 500, error("template_missing", 500,
                    "页面模板缺失", null));
        }

        Sessions.Session session = currentSession(exchange, false);
        Map<String, String> values = pageValues(session);
        if ("result".equals(page)) {
            String player = session == null || session.player == null ? "" : session.player;
            addResultValues(exchange, values, player, session != null && session.verified());
        }
        return serveRenderedPage(exchange, html, page, values);
    }

    /** 结果页要按图片状态给出文案和链接。文案来自语言文件，按请求语言渲染。 */
    private void addResultValues(HttpExchange exchange, Map<String, String> values,
                                 String player, boolean verified) {
        String lang = resolveLang(exchange);
        String name = queryParam(exchange, "name");
        boolean renamed = "1".equals(queryParam(exchange, "renamed"));
        values.put("renamed_text", renamed
                ? messages.html(lang, "status.renamed") : "");

        UploadStore.Item item = Texts.isSafeFileName(name, config.maxNameLength)
                ? store.get(name) : null;
        if (item == null) {
            values.put("name", "");
            values.put("nickname", "");
            values.put("status_text", messages.html(lang, "status.unknown"));
            values.put("url", "");
            values.put("can_rename", "");
            values.put("thumb_params", "");
            return;
        }
        boolean mine = verified && player.equals(item.player);
        boolean pending = UploadStore.STATUS_PENDING.equals(item.status);
        values.put("name", Texts.escapeHtml(item.name));
        values.put("nickname", Texts.escapeHtml(item.nickname == null ? "" : item.nickname));
        values.put("can_rename", mine && pending && config.allowRename ? "1" : "");
        values.put("thumb_params", Texts.escapeHtmlAttr(
                "alt:{\"name\":\"" + jsonSafe(item.name) + "\"}"));

        if (UploadStore.STATUS_PUBLISHED.equals(item.status)) {
            values.put("status_text", messages.html(lang, "status.published"));
            values.put("url", Texts.escapeHtml(config.uploadUrl(item.name)));
        } else if (pending) {
            values.put("status_text", messages.html(lang, "status.pending"));
            values.put("url", "");
        } else {
            String reason = item.rejectedReason == null ? "" : item.rejectedReason;
            values.put("status_text", reason.isEmpty()
                    ? messages.html(lang, "status.rejected")
                    : messages.html(lang, "status.rejected_reason", "reason", reason));
            values.put("url", "");
        }
    }

    private int serveRenderedPage(HttpExchange exchange, String html, String page)
            throws IOException {
        return serveRenderedPage(exchange, html, page, new LinkedHashMap<>());
    }

    /**
     * 渲染并返回一个页面。
     *
     * 三件事必须一起做，所以抽成一个方法：
     *   1. 按请求语言把 messages/<语言>.yml 里的每个键做成 $msg_键 变量
     *   2. 页脚那两句嵌 server.properties 的 motd 和服务器名
     *   3. 把整张文案表注入给前端（切语言时用）
     */
    private int serveRenderedPage(HttpExchange exchange, String html, String page,
                                 Map<String, String> values) throws IOException {
        String lang = resolveLang(exchange);
        values.put("lang", lang);
        values.put("page", page);

        // 页脚要显示服务器自己的信息，直接从 server.properties 实时取
        String motd = serverMotd();
        String server = serverName();

        // 先算出这门语言下的最终文案（占位符全部换掉），再用于两处：
        //   1. 服务端渲染时填进 $msg_xxx
        //   2. 注入给前端的整张表
        // 两处必须用同一份「已替换」的值：如果注入的表里还留着 {motd}，
        // 前端引擎会在浏览器里把页脚覆盖成带 {motd} 的样子。
        // 备案号那一行：没配就整行不出现（而不是显示一个空的「备案号：」）
        String icp = config.icpLicense == null ? "" : config.icpLicense.trim();
        values.put("icp_block", icp.isEmpty() ? ""
                : "<p class=\"foot-icp\"><a href=\"" + ICP_LOOKUP_URL
                  + "\" target=\"_blank\" rel=\"noopener noreferrer\">"
                  + messages.html(lang, "site.footer_icp", "number", icp)
                  + "</a></p>");

        Map<String, String> substituted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : messages.table(lang).entrySet()) {
            String key = entry.getKey();
            // html() = 转义 + 反引号变 <code>，所以页面上可以直接放这段 HTML
            String value = key.equals("site.footer_note")
                    ? messages.html(lang, key, "motd", motd)
                    : key.equals("site.footer_copyright")
                            ? messages.html(lang, key, "server", server)
                            : key.equals("site.footer_plugin")
                                    ? messages.html(lang, key,
                                            "name", pluginName, "version", pluginVersion)
                                    : messages.html(lang, key);
            // 注入给前端的表用的是同一份值（已转义、已带 <code>），
            // 前端引擎按 innerHTML 应用，跟服务端渲染出来的一致。
            substituted.put(key, value);
            // 键里的点换成下划线，于是 yml 里任何一个键
            // 都能像 $msg_upload_nicknamePlaceholder 这样直接写进页面。
            values.put("msg_" + key.replace('.', '_'), value);
        }
        values.put("msg_site_show_manual_claim", config.showManualClaim ? "1" : "");
        values.put("msg_site_allow_rename", config.allowRename ? "1" : "");
        values.put("msg_site_show_copy_buttons", config.showCopyButtons ? "1" : "");
        values.put("messages_json", jsonOf(substituted));

        String output = render(html, values);
        // 把当前语言的整张文案表注入成全局对象，i18n.js 启动时合并进字典
        String injection = "<script>window.IMGUP_MESSAGES="
                + values.getOrDefault("messages_json", "{}")
                + ";window.IMGUP_LANG=" + Json.write(lang) + ";</script>\n";
        int head = output.indexOf("</head>");
        if (head >= 0) {
            output = output.substring(0, head) + injection + output.substring(head);
        }
        return sendHtml(exchange, output);
    }

    /**
     * 极简模板替换：认 $name 和 ${name}。
     *
     * 刻意不用任何模板引擎 —— 站点只有 3 个页面，而引入依赖就破坏了
     * 「一个 jar 搞定」这个前提。转义在调用方做（因为要区分文本和属性上下文）。
     */
    static String render(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length() + 256);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            int start = i + 1;
            boolean braced = start < template.length() && template.charAt(start) == '{';
            if (braced) {
                start++;
            }
            // 变量名允许字母、数字、下划线和点 —— 注意必须允许大写，
            // 因为语言文件里的键有 camelCase（upload.nicknamePlaceholder），
            // 遇到大写就截断会把 $msg_upload_nicknamePlaceholder 切成半截。
            int end = start;
            while (end < template.length()) {
                char ch = template.charAt(end);
                if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                        || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.') {
                    end++;
                } else {
                    break;
                }
            }
            if (end == start || (braced && (end >= template.length()
                    || template.charAt(end) != '}'))) {
                out.append(c);
                i++;
                continue;
            }
            String key = template.substring(start, end);
            out.append(values.getOrDefault(key, ""));
            i = braced ? end + 1 : end;
        }
        return out.toString();
    }

    // -------------------------------------------------------- 响应工具

    private int sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        return sendJson(exchange, status, body, null);
    }

    private int sendJson(HttpExchange exchange, int status, Object body,
                         List<String> cookies) throws IOException {
        return sendBytes(exchange, status, Json.write(body).getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8", null, cookies);
    }

    private void safeJson(HttpExchange exchange, int status, Object body) {
        try {
            sendJson(exchange, status, body);
        } catch (IOException ignored) {
            // 连接已经断了，没什么可做的
        }
    }

    private int sendHtml(HttpExchange exchange, String html) throws IOException {
        return sendBytes(exchange, 200, html.getBytes(StandardCharsets.UTF_8),
                "text/html; charset=utf-8", null, null);
    }

    private int sendFile(HttpExchange exchange, Path path, boolean cache) throws IOException {
        byte[] data = Files.readAllBytes(path);
        String type = path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpg")
                ? "image/jpeg" : "image/png";
        return sendBytes(exchange, 200, data, type,
                cache ? "public, max-age=300" : "no-store", null);
    }

    /**
     * 所有响应的唯一出口。
     *
     * @param cacheControl  Cache-Control 的值，null 表示不设置
     * @param cookies       Set-Cookie 的值列表，null 表示没有
     */
    private int sendBytes(HttpExchange exchange, int status, byte[] data,
                          String contentType, String cacheControl,
                          List<String> cookies) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        if (cacheControl != null) {
            exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        }
        if (cookies != null) {
            for (String value : cookies) {
                exchange.getResponseHeaders().add("Set-Cookie", value);
            }
        }
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return status;
        }
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
        return status;
    }

    private static Map<String, Object> error(String code, int status, String message,
                                             Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", code);
        body.put("status", status);
        body.put("message", message);
        body.put("message_key", "err." + code);
        if (params != null && !params.isEmpty()) {
            body.put("message_params", params);
        }
        return body;
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String cookie(String name, String value, int maxAge) {
        return name + "=" + value + "; Path=/; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax";
    }

    /** 放进 JSON 字符串里的值要转义，避免拼出坏 JSON。 */
    private static String jsonSafe(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------- 请求工具

    private Sessions.Session currentSession(HttpExchange exchange, boolean create) {
        String id = cookieValue(exchange, COOKIE_SESSION);
        Sessions.Session session = sessions.get(id);
        if (session == null && create) {
            session = sessions.create();
        }
        return session;
    }

    static String cookieValue(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String part : header.split(";")) {
                String piece = part.trim();
                int eq = piece.indexOf('=');
                if (eq > 0 && piece.substring(0, eq).equals(name)) {
                    return piece.substring(eq + 1);
                }
            }
        }
        return null;
    }

    static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    static long headerLong(HttpExchange exchange, String name, long fallback) {
        String raw = header(exchange, name);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (pair.substring(0, eq).equals(key)) {
                return Texts.urlDecode(pair.substring(eq + 1));
            }
        }
        return "";
    }

    private static String remoteIp(HttpExchange exchange) {
        // 没做反向代理，直接用对端地址；有 X-Forwarded-For 也只是参考，不采信
        return exchange.getRemoteAddress() == null
                ? "unknown" : exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = Multipart.readBody(in, headerLong(exchange, "Content-Length", -1), 64 * 1024);
        } catch (Multipart.TooLargeException e) {
            throw new IllegalArgumentException("请求体太大");
        }
        if (raw.length == 0) {
            return new LinkedHashMap<>();
        }
        return Json.parseObject(new String(raw, StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------- 限流

    private boolean allowNewCode(String ip) {
        long now = System.currentTimeMillis();
        long window = 3600_000L;
        synchronized (codeHits) {
            List<Long> hits = codeHits.computeIfAbsent(ip, k -> new ArrayList<>());
            hits.removeIf(t -> now - t > window);
            if (hits.size() >= config.newCodePerHour) {
                return false;
            }
            hits.add(now);
            if (codeHits.size() > 4096) {
                // 简单的防膨胀：清掉已经全过期的记录
                codeHits.entrySet().removeIf(e -> e.getValue().stream()
                        .allMatch(t -> now - t > window));
            }
            return true;
        }
    }

    /**
     * 把 config.yml 里的英文文案包成前端翻译表的覆盖项。
     *
     * 注入到页面的 &lt;script&gt; 里，i18n.js 启动时读 window.IMGUP_SITE_EN。
     * 这样「网站元素」的唯一来源就是 config.yml，模板里不用写死任何一句文案。
     */
    /** 把一张表编成 JSON，并防住 </script>（文案里出现这几个字符会提前结束脚本块）。 */
    private static String jsonOf(Map<String, String> table) {
        Map<String, Object> out = new LinkedHashMap<>(table);
        return Json.write(out).replace("<", "\\u003c");
    }

    /**
     * 决定这次请求用哪门语言。
     *
     * 优先级：?lang=xx（玩家点了语言开关）→ cookie（记住上次选的）→ 默认语言。
     * 语言代码会被严格校验，因为它不能用来拼路径。
     */
    private String resolveLang(HttpExchange exchange) {
        String fromQuery = queryParam(exchange, "lang");
        if (Messages.isValidLang(fromQuery) && messages.has(fromQuery)) {
            return fromQuery.toLowerCase(Locale.ROOT);
        }
        String fromCookie = cookieValue(exchange, COOKIE_LANG);
        if (Messages.isValidLang(fromCookie) && messages.has(fromCookie)) {
            return fromCookie.toLowerCase(Locale.ROOT);
        }
        return messages.defaultLang();
    }

    /**
     * 服务器 motd / 服务器名，来自 server.properties。
     *
     * 由插件通过 {@link GameBridge} 传进来（Bukkit 的 getMotd / getName 是
     * 实时值，改完 server.properties 重启就变），web 层不直接碰 Bukkit。
     */
    private String serverMotd() {
        return game == null ? "" : game.serverMotd();
    }

    private String serverName() {
        return game == null ? "" : game.serverName();
    }

    /** 供后台用：把所有待审核的图列出来。 */
    public List<UploadStore.Item> pending() {
        return store.byStatus(UploadStore.STATUS_PENDING);
    }

    /**
     * 状态变化后在游戏里通知玩家。
     *
     * key 是语言文件里的键（外加 {名字} 替换），不是成品文案 —— 具体显示哪门语言
     * 由插件按玩家客户端语言决定。
     */
    public void notifyPlayer(String player, String key, String... pairs) {
        if (game != null && player != null && !player.isEmpty()) {
            game.notify(player, key, pairs);
        }
    }
}
