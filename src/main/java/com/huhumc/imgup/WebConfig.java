package com.huhumc.imgup;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件内置网站的全部可调参数。
 *
 * 这些值都来自 config.yml —— 想让玩家看到什么、允许传多大，全在这里改，
 * 不需要碰代码。改完 /imgup reload 或重启服务端即可生效。
 */
public final class WebConfig {

    /**
     * 读配置的最小接口。
     *
     * 存在的唯一理由：让 {@link WebConfig} 能被单独测试 —— 它只依赖这四个
     * 读取方法，不必为了测一个端口号去起一个 Minecraft 服务端。
     */
    public interface Source {
        boolean getBoolean(String path, boolean fallback);

        String getString(String path, String fallback);

        int getInt(String path, int fallback);

        long getLong(String path, long fallback);
    }

    /** 把 Bukkit 的 FileConfiguration 包成 Source。 */
    public static Source of(FileConfiguration cfg) {
        return new Source() {
            @Override
            public boolean getBoolean(String path, boolean fallback) {
                return cfg.getBoolean(path, fallback);
            }

            @Override
            public String getString(String path, String fallback) {
                return cfg.getString(path, fallback);
            }

            @Override
            public int getInt(String path, int fallback) {
                return cfg.getInt(path, fallback);
            }

            @Override
            public long getLong(String path, long fallback) {
                return cfg.getLong(path, fallback);
            }
        };
    }

    /** 是否启用内置网站。关掉的话插件只剩 /imgup 指令，完全不开端口。 */
    public final boolean enabled;

    /** 监听地址。"0.0.0.0" = 所有网卡（公网可达）；"127.0.0.1" = 只给本机。 */
    public final String host;

    /** 监听端口。 */
    public final int port;

    /** 对外地址，用于拼图片下载链接。留空则按 host:port 自动推断。 */
    public final String publicBaseUrl;

    /** 上传的图直接发布（true）还是先等审核（false）。 */
    public final boolean autoPublish;

    /** 单张图片字节上限。 */
    public final int maxUploadBytes;

    /** 长边像素上限（只缩不放）。 */
    public final int maxDimension;

    /** 图片总像素上限，防解压炸弹。 */
    public final int maxPixels;

    /** 缩放后重编码的 JPEG 质量（1-100）。 */
    public final int jpegQuality;

    /** 生成的文件名总长上限（含扩展名）。 */
    public final int maxNameLength;

    /** 玩家填的昵称长度上限。 */
    public final int maxNicknameLength;

    /** 上传码有效期（秒）。 */
    public final long codeTtlMillis;

    /** 每个玩家 24 小时内的上传张数上限，0 = 不限。 */
    public final int perPlayerDaily;

    /** 待审核队列上限，0 = 不限。 */
    public final int pendingMax;

    /** 每个 IP 每小时能取多少次上传码。 */
    public final int newCodePerHour;

    /** 一次上传响应最多等多久（秒）。 */
    public final int requestTimeoutSeconds;

    /** 玩家上传成功 / 审核结果出来时，是否在游戏内私聊通知玩家。 */
    public final boolean notifyPlayer;

    // ---------------------------------------------------------- 网站元素
    //
    // 页面上显示给玩家的文案。中文取 site.xxx，英文取 site.xxx_en
    // （_en 留空就沿用中文）。插件渲染页面时用中文值填进 HTML，
    // 同时把英文那套喂给前端的翻译表，所以站长在 config.yml 里改一句话，
    // 中英两边一起变 —— 不用碰任何 HTML。
    /** 元素名 -> 中文文案。 */
    public final Map<String, String> siteZh;
    /** 元素名 -> 英文文案（只包含确实填了英文的）。 */
    public final Map<String, String> siteEn;

    /** 关掉后首页不显示「手动输入上传码」那一块。 */
    public final boolean showManualClaim;
    /** 关掉后结果页不显示改名区块。 */
    public final boolean allowRename;
    /** 关掉后不显示复制文件名 / 复制指令按钮。 */
    public final boolean showCopyButtons;

    private WebConfig(Source cfg) {
        this.enabled = cfg.getBoolean("web.enabled", true);
        this.host = cfg.getString("web.host", "0.0.0.0");
        this.port = cfg.getInt("web.port", 8091);
        this.publicBaseUrl = trimTrailingSlash(cfg.getString("web.public_base_url", ""));
        this.autoPublish = cfg.getBoolean("web.auto_publish", true);

        this.maxUploadBytes = clamp(cfg.getInt("upload.max_upload_bytes", 5 * 1024 * 1024),
                64 * 1024, 64 * 1024 * 1024);
        this.maxDimension = clamp(cfg.getInt("upload.max_dimension", 2048), 16, 16384);
        this.maxPixels = clamp(cfg.getInt("upload.max_pixels", 40_000_000),
                10_000, 400_000_000);
        this.jpegQuality = clamp(cfg.getInt("upload.jpeg_quality", 90), 30, 100);
        this.maxNameLength = clamp(cfg.getInt("upload.max_name_length", 64), 16, 180);
        this.maxNicknameLength = clamp(cfg.getInt("upload.max_nickname_length", 32), 1, 120);

        this.codeTtlMillis = Math.max(30L, cfg.getLong("code_ttl_seconds", 300L)) * 1000L;
        this.perPlayerDaily = Math.max(0, cfg.getInt("limits.per_player_daily", 20));
        this.pendingMax = Math.max(0, cfg.getInt("limits.global_pending_max", 200));
        this.newCodePerHour = Math.max(1, cfg.getInt("limits.newcode_per_ip_per_hour", 30));
        this.requestTimeoutSeconds = clamp(cfg.getInt("web.request_timeout_seconds", 60), 5, 600);
        this.notifyPlayer = cfg.getBoolean("notify_player", true);

        this.siteZh = readSite(cfg, false);
        this.siteEn = readSite(cfg, true);
        this.showManualClaim = cfg.getBoolean("site.show_manual_claim", true);
        this.allowRename = cfg.getBoolean("site.allow_rename", true);
        this.showCopyButtons = cfg.getBoolean("site.show_copy_buttons", true);
    }

    /** 内置兜底文案：config.yml 里漏写了某个键时用它，页面不会出现空白。 */
    private static final Map<String, String[]> SITE_DEFAULTS = new LinkedHashMap<>();

    static {
        // 元素名 -> {中文, 英文}
        SITE_DEFAULTS.put("title", new String[]{"图片上传站", "Image Upload"});
        SITE_DEFAULTS.put("brand", new String[]{"图片上传站", "Image Upload"});
        SITE_DEFAULTS.put("nav_admin", new String[]{"站长入口", "Admin"});
        SITE_DEFAULTS.put("home_heading", new String[]{"上传海报", "Upload a poster"});
        SITE_DEFAULTS.put("home_lede", new String[]{
                "先在网页上获取一个 8 位上传码，再进游戏输入 /imgup 你的上传码 完成确认，就可以上传图片了。",
                "Get an 8-character upload code here, then type /imgup <your code> in game to confirm. "
                        + "After that you can upload images."});
        SITE_DEFAULTS.put("home_step1_title", new String[]{"第一步 · 获取上传码", "Step 1 · Get an upload code"});
        SITE_DEFAULTS.put("home_button", new String[]{"获取上传码", "Get upload code"});
        SITE_DEFAULTS.put("home_step2_title", new String[]{"备用方式 · 手动输入上传码", "Fallback · enter the code manually"});
        SITE_DEFAULTS.put("upload_heading", new String[]{"上传图片", "Upload an image"});
        SITE_DEFAULTS.put("upload_lede", new String[]{
                "选一张截图或海报，上传后会生成游戏内可用的图片名。",
                "Pick a screenshot or poster. After uploading you get a file name that works in game."});
        SITE_DEFAULTS.put("upload_button", new String[]{"上传", "Upload"});
        SITE_DEFAULTS.put("upload_nickname_title", new String[]{"图片昵称", "Image nickname"});
        SITE_DEFAULTS.put("result_heading", new String[]{"上传完成", "Upload complete"});
        SITE_DEFAULTS.put("result_rename_title", new String[]{
                "不满意这个名字？现在还能改", "Not happy with this name? You can still change it"});
        SITE_DEFAULTS.put("result_rename_button", new String[]{"保存新名字", "Save new name"});
        SITE_DEFAULTS.put("footer_note", new String[]{
                "图片上传站 · 深色 Minecraft 风格 · 纯静态页面，不加载任何外部资源。",
                "Image Upload · dark Minecraft style · fully self-contained, no external assets."});
        SITE_DEFAULTS.put("footer_permission", new String[]{
                "游戏内贴海报需要 ", "Posting in game requires the "});
        SITE_DEFAULTS.put("footer_permission_suffix", new String[]{
                " 权限，没有权限请找站长。",
                " permission — ask your admin if you do not have it."});
    }

    /**
     * 读出所有网站元素。
     *
     * @param english false 读 site.xxx，true 读 site.xxx_en
     */
    private static Map<String, String> readSite(Source cfg, boolean english) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : SITE_DEFAULTS.entrySet()) {
            String key = entry.getKey();
            String fallback = entry.getValue()[english ? 1 : 0];
            String raw;
            if (english) {
                // 英文留空 = 沿用中文，这样站长只需要填中文也能用
                raw = cfg.getString("site." + key + "_en", "");
                if (raw == null || raw.trim().isEmpty()) {
                    raw = cfg.getString("site." + key, fallback);
                }
            } else {
                raw = cfg.getString("site." + key, fallback);
            }
            if (raw == null || raw.isEmpty()) {
                raw = fallback;
                missing.add(key);
            }
            out.put(key, raw);
        }
        if (!missing.isEmpty()) {
            // 这里不抛异常：少了文案也要能开站，用内置兜底即可
            out.put("__missing__", String.join(",", missing));
        }
        return out;
    }

    /** 取某个网站元素的中文/英文值。 */
    public String site(String key, boolean english) {
        Map<String, String> table = english ? siteEn : siteZh;
        String value = table.get(key);
        if (value == null || value.isEmpty()) {
            value = siteZh.get(key);
        }
        return value == null ? "" : value;
    }

    public static WebConfig from(FileConfiguration cfg) {
        return new WebConfig(of(cfg));
    }

    /** 直接从一个 Source 构造（测试用）。 */
    public static WebConfig from(Source src) {
        return new WebConfig(src);
    }

    /** 拼给玩家看的图片地址。有 public_base_url 就用它，否则按 host:port 推。 */
    public String uploadUrl(String fileName) {
        return base() + "/uploads/" + Texts.encodePathSegment(fileName);
    }

    public String base() {
        if (!publicBaseUrl.isEmpty()) {
            return publicBaseUrl;
        }
        String shown = host;
        if (shown.isEmpty() || shown.equals("0.0.0.0") || shown.equals("::")) {
            shown = "127.0.0.1";
        }
        return "http://" + shown + ":" + port;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
