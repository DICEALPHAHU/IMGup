package com.huhumc.imgup;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;

/**
 * ImgUp —— 图片上传站，整个网站就跑在这个插件里。
 *
 * 和 1.x 的区别：以前插件只负责「登记玩家确认了哪个码」，网页是外面一个
 * Docker 里的 Python 服务，两边靠 RCON + shared_secret 通信。现在内置一个
 * HTTP 服务（{@link WebServer}），上传码直接落在内存里 ——
 * 玩家敲完 /imgup，网页当场就知道，不需要轮询、不需要密钥、不需要额外进程。
 *
 * 职责边界很清晰：
 *   · 本类只碰 Bukkit 的东西（配置、指令、玩家、日志、调度器）
 *   · WebServer / UploadStore / ImageTools 完全不依赖 Bukkit，可以单独测试
 *   · 两者之间只有 {@link WebServer.GameBridge} 一个接口，且所有 Bukkit 调用
 *     都通过调度器回到主线程 —— HTTP 请求线程绝不直接碰游戏世界
 */
public final class ImgUpPlugin extends JavaPlugin implements WebServer.GameBridge {

    private WebConfig webConfig;
    private Sessions sessions;
    private UploadStore store;
    private WebServer web;
    private Messages messages;

    /** 图片最终目录：Images 插件的数据目录。 */
    private java.nio.file.Path imagesDir;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // ---- 图片目录：优先用 Images 插件的目录，因为 /image create 只认那里 ----
        org.bukkit.plugin.Plugin images = getServer().getPluginManager().getPlugin("Images");
        if (images != null && images.isEnabled()) {
            imagesDir = images.getDataFolder().toPath();
        } else {
            // 没装 Images 插件时退回自己的目录，至少上传功能可用
            imagesDir = getDataFolder().toPath().resolve("images");
            getLogger().warning("没有找到已启用的 Images 插件，上传的图片会存到 "
                    + imagesDir + "；游戏内 /image create 用不了。");
        }

        try {
            store = new UploadStore(getDataFolder().toPath(), imagesDir);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "无法初始化上传目录，插件停用", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ---- 指令 ----
        ImgUpCommand executor = new ImgUpCommand(this);
        PluginCommand root = getCommand("imgup");
        if (root == null) {
            getLogger().severe("plugin.yml 里没有注册 imgup 指令，插件无法工作！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        root.setExecutor(executor);
        root.setTabCompleter(executor);

        reloadSettings();
        ensureAdminPassword();
        loadMessages();
        startWeb();

        getLogger().info("ImgUp 已启用：玩家用 /imgup <上传码> 确认，然后到 "
                + webConfig.base() + " 上传图片");
    }

    @Override
    public void onDisable() {
        if (web != null) {
            web.stop();
            web = null;
        }
        getLogger().info("ImgUp 已停用");
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        reloadSettings();
    }

    private void reloadSettings() {
        this.webConfig = WebConfig.from(getConfig());
        this.sessions = new Sessions(6 * 3600_000L, webConfig.codeTtlMillis);
    }

    /**
     * 确保网页后台有密码。
     *
     * 站长不可能自己想一个随机密码再手抄进 config.yml，所以第一次启动时生成一个
     * 写回配置，并在控制台/日志里打印出来 —— 之后他随时可以去 config.yml 改。
     */
    private void ensureAdminPassword() {
        if (!webConfig.adminEnabled || !webConfig.adminPassword.isEmpty()) {
            return;
        }
        String generated = randomPassword();
        getConfig().set("web.admin_password", generated);
        saveConfig();
        // 重新读一遍，让 webConfig 立刻带上新密码
        this.webConfig = WebConfig.from(getConfig());
        getLogger().warning("========================================================");
        getLogger().warning(" 网页后台已生成管理密码：" + generated);
        getLogger().warning(" 打开 " + webConfig.base() + "/admin 就能用；");
        getLogger().warning(" 想改的话编辑 plugins/ImgUp/config.yml 的 web.admin_password");
        getLogger().warning("========================================================");
    }

    private static String randomPassword() {
        // 去掉容易看错的字符，站长可能要手抄
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /**
     * 释放并读取语言文件。
     *
     * 第一次启动时把 jar 里自带的 messages/zh.yml、en.yml 写到
     * plugins/ImgUp/messages/，之后站长改的就是磁盘上那份，不会被覆盖。
     */
    private void loadMessages() {
        Messages.extractDefaults(getLogger(), resource -> {
            if (getResource(resource) != null) {
                saveResource(resource, false);   // false = 已存在就不覆盖
            }
        });
        Path dir = getDataFolder().toPath().resolve(webConfig.messagesDir);
        this.messages = new Messages(dir, webConfig.language);
        this.messages.load(getLogger());
    }

    /** 启动（或重启）内置网站。端口被占用等失败只记日志，不拖挂整个插件。 */
    void startWeb() {
        if (web != null) {
            web.stop();
            web = null;
        }
        if (!webConfig.enabled) {
            getLogger().info("config.yml 里 web.enabled = false，内置网站未启动");
            return;
        }
        web = new WebServer(getLogger(), webConfig, store, sessions, messages, this);
        if (!web.start()) {
            getLogger().warning("网站没起来，但 /imgup 指令仍然可用（玩家确认了也没地方上传）");
        }
    }

    /** /imgup reload 用：重新读配置，网站跟着重启（端口可能变了）。 */
    void reloadAll() {
        reloadConfig();
        loadMessages();
        startWeb();
    }

    // -------------------------------------------------- WebServer.GameBridge

    /**
     * 在游戏里给玩家发提示。
     *
     * 这个方法可能从 HTTP 请求线程调用，所以**必须**跳回主线程再碰 Bukkit ——
     * Paper 的 API 不是线程安全的，从别的线程调 getPlayer 之类是未定义行为。
     * 玩家不在线就静默丢弃（比如他网页还开着但已经退出游戏）。
     */
    @Override
    public void notify(String player, String message) {
        if (player == null || player.isEmpty() || !webConfig.notifyPlayer) {
            return;
        }
        if (getServer().isPrimaryThread()) {
            deliver(player, message);
            return;
        }
        getServer().getScheduler().runTask(this, () -> deliver(player, message));
    }

    private void deliver(String playerName, String message) {
        Player player = getServer().getPlayerExact(playerName);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendMessage(ChatColor.GREEN + "[图片上传] " + ChatColor.WHITE + message);
    }

    /**
     * server.properties 里的 motd，页脚要显示它。
     *
     * 用 Bukkit 的实时值而不是自己去读文件：站长改了 server.properties 重启后
     * 这里立刻就是新值，不用管文件在哪、也不用解析 properties 格式。
     */
    @Override
    public String serverMotd() {
        try {
            return getServer().getMotd();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public String serverName() {
        try {
            return getServer().getName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public boolean isOnline(String player) {
        if (player == null || player.isEmpty()) {
            return false;
        }
        if (getServer().isPrimaryThread()) {
            Player online = getServer().getPlayerExact(player);
            return online != null && online.isOnline();
        }
        // 请求线程不问在线状态：拿不准就返回 false，别去碰主线程的东西
        return false;
    }

    // ------------------------------------------------------------ 玩家确认

    /**
     * 玩家在游戏内确认一个上传码。
     *
     * @return 给玩家看的一句话
     */
    String confirm(Player player, String rawCode) {
        Sessions.ConfirmResult result = sessions.confirm(rawCode, player.getName());
        return switch (result) {
            case OK -> ChatColor.GREEN + "上传码已确认" + ChatColor.GRAY
                    + "，回到网页就能上传图片了（这个码已经作废，不用担心别人看到）";
            case BAD_FORMAT -> ChatColor.RED + "上传码格式不对" + ChatColor.GRAY
                    + "（8 位字母数字，不含容易看错的 I、O、0、1。可以直接从网页复制）";
            case UNKNOWN -> ChatColor.RED + "这个上传码不对或已经过期" + ChatColor.GRAY
                    + "，请回到网页重新获取一个";
        };
    }

    // ------------------------------------------------------------ 访问器

    WebConfig webConfig() {
        return webConfig;
    }

    UploadStore store() {
        return store;
    }

    Sessions sessions() {
        return sessions;
    }

    Messages messages() {
        return messages;
    }

    WebServer web() {
        return web;
    }

    java.nio.file.Path imagesDir() {
        return imagesDir;
    }

    boolean requirePermission() {
        return getConfig().getBoolean("require_permission", false);
    }

    /** 玩家有没有管理权限。控制台恒为 true。 */
    boolean isAdmin(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission("imgup.admin");
    }

    String webStatus() {
        if (web == null) {
            return "未启动";
        }
        return web.isRunning() ? ("运行中，端口 " + web.port()) : "启动失败";
    }

    static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
