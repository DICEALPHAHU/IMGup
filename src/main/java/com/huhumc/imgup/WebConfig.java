package com.huhumc.imgup;

import org.bukkit.configuration.file.FileConfiguration;


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

    // ---------------------------------------------------------- 多语言
    //
    // 页面上显示什么、报错说什么，全部来自 messages/<语言>.yml（见 Messages 类）。
    // 这里只留两件事：默认用哪门语言、语言文件放哪。
    /** 默认语言，对应 messages/<language>.yml。 */
    public final String language;

    /** 语言文件目录（相对插件数据目录）。 */
    public final String messagesDir;

    /** 关掉后首页不显示「手动输入上传码」那一块。 */
    public final boolean showManualClaim;
    /** 关掉后结果页不显示改名区块。 */
    public final boolean allowRename;
    /** 关掉后不显示复制文件名 / 复制指令按钮。 */
    public final boolean showCopyButtons;

    /** 网页后台的管理密码。空字符串 = 后台不可用（登录一律失败）。 */
    public final String adminPassword;

    /** 关掉后 /admin 直接返回 404，连登录页都没有。 */
    public final boolean adminEnabled;

    /**
     * 备案号（例如「京ICP备12345678号-1」）。默认为空 —— 没有备案要求的站点
     * 不用管它，页脚也不会多出一行空占位。填了才会显示在页脚最底部。
     */
    public final String icpLicense;

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

        this.language = normalizeLang(cfg.getString("language", "zh"));
        this.messagesDir = cfg.getString("messages_dir", "messages");
        this.showManualClaim = cfg.getBoolean("site.show_manual_claim", true);
        this.allowRename = cfg.getBoolean("site.allow_rename", true);
        this.showCopyButtons = cfg.getBoolean("site.show_copy_buttons", true);
        this.adminPassword = cfg.getString("web.admin_password", "").trim();
        this.adminEnabled = cfg.getBoolean("web.admin_enabled", true);
        this.icpLicense = cfg.getString("web.icp_license", "").trim();
    }

    /** 从 Bukkit 配置构造（插件里用这个）。 */
    public static WebConfig from(FileConfiguration cfg) {
        return new WebConfig(of(cfg));
    }

    /** 直接从一个 Source 构造（测试用）。 */
    public static WebConfig from(Source src) {
        return new WebConfig(src);
    }

    /** 语言代码只允许字母数字和连字符，避免拿它去拼路径。 */
    private static String normalizeLang(String raw) {
        if (raw == null || raw.isBlank()) {
            return "zh";
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return Messages.isValidLang(value) ? value : "zh";
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
