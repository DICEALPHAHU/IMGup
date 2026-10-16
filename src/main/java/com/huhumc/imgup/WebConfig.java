package com.huhumc.imgup;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件内置网站的全部可调参数。
 *
 * 这些值都来自 config.yml —— 想让玩家看到什么、允许传多大，全在这里改，
 * 不需要碰代码。改完 /imgup reload 或重启服务端即可生效。
 */
public final class WebConfig {

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

    private WebConfig(FileConfiguration cfg) {
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
    }

    public static WebConfig from(FileConfiguration cfg) {
        return new WebConfig(cfg);
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
