package com.github.dicealphahu.imgup;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
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
            // 先在磁盘上确认一下再调 saveResource：saveResource 遇到已存在的文件
            // 会打一条 "Could not save ... because it already exists" 的 WARN，
            // 每次 reload 都刷两条，看着像出错，其实完全正常。
            Path target = getDataFolder().toPath().resolve(resource);
            if (java.nio.file.Files.exists(target)) {
                return;
            }
            if (getResource(resource) != null) {
                saveResource(resource, false);
            }
        });
        Path dir = getDataFolder().toPath().resolve(webConfig.messagesDir);
        this.messages = new Messages(dir, webConfig.language);
        // 升级路径：把 jar 里新增的键补进站长磁盘上的语言文件。
        // 不做这一步的话，插件新加的消息在老服务器上会显示成键名。
        this.messages.mergeDefaults(getLogger(), Resources::readText);
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
        web.setPluginInfo(getDescription().getName(),
                getDescription().getVersion());
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
    /**
     * 在游戏里给玩家发一条通知。
     *
     * message 现在传的是**语言文件里的键**（外加 {名字} 替换），因为这句话要按
     * 玩家客户端的语言来显示 —— 英文客户端看英文，中文客户端看中文。
     *
     * 这个方法可能从 HTTP 请求线程调用，所以**必须**跳回主线程再碰 Bukkit。
     */
    @Override
    public void notify(String player, String key, String... pairs) {
        notifyPlayer(player, key, pairs);
    }

    /** 通知某个玩家，文案取自语言文件。可以在任意线程调用。 */
    void notifyPlayer(String player, String key, String... pairs) {
        if (player == null || player.isEmpty() || !webConfig.notifyPlayer) {
            return;
        }
        if (getServer().isPrimaryThread()) {
            deliver(player, key, pairs);
            return;
        }
        getServer().getScheduler().runTask(this, () -> deliver(player, key, pairs));
    }

    private void deliver(String playerName, String key, String... pairs) {
        Player player = getServer().getPlayerExact(playerName);
        if (player == null || !player.isOnline()) {
            // 玩家不在线就没法知道他的客户端语言，用默认语言
            return;
        }
        String text = messages.get(langOf(player), key, pairs);
        player.sendMessage(ChatColor.GREEN + "[" + messages.get(langOf(player), "game.prefix")
                + "] " + ChatColor.WHITE + text);
    }

    /**
     * 该玩家该用哪门语言。
     *
     * 用客户端语言（Player#getLocale，形如 zh_cn / en_us）去 messages/ 里找；
     * 找不到就回退到 config.yml 的 language。控制台也算默认语言。
     */
    String langOf(org.bukkit.command.CommandSender sender) {
        if (sender instanceof Player player) {
            try {
                return messages.forClientLocale(player.getLocale());
            } catch (RuntimeException e) {
                return messages.defaultLang();
            }
        }
        return messages.defaultLang();
    }

    /** 按发送者语言取文案并替换 {名字} 占位符。 */
    String msg(org.bukkit.command.CommandSender sender, String key, String... pairs) {
        return messages.get(langOf(sender), key, pairs);
    }

    /**
     * server.properties 里的 motd，页脚要显示它。
     *
     * 用 Bukkit 的实时值而不是自己去读文件：站长改了 server.properties 重启后
     * 这里立刻就是新值，不用管文件在哪、也不用解析 properties 格式。
     */
    @Override
    public String serverMotd() {
        // server.properties 里的 motd 是唯一来源，和页脚上写的一致
        return serverProperty("motd");
    }

    @Override
    public String serverName() {
        // 注意：不能用 getServer().getName() —— 那个返回的是服务端实现名
        // （Purpur / CraftBukkit），不是站长在 server.properties 里设的 server-name。
        return serverProperty("server-name");
    }

    /**
     * 读 server.properties 里的一个键。
     *
     * 真正的解析在 {@link ServerProperties} 里（不依赖 Bukkit，可单独测试）。
     */
    private String serverProperty(String key) {
        return ServerProperties.get(serverPropertiesFile(), key);
    }

    /** server.properties 在服务端根目录（世界容器的上一层）。 */
    private Path serverPropertiesFile() {
        try {
            return getServer().getWorldContainer().toPath().resolve("server.properties");
        } catch (RuntimeException e) {
            return null;
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
        String lang = langOf(player);
        return switch (result) {
            case OK -> ChatColor.GREEN + messages.get(lang, "game.confirm_ok");
            case BAD_FORMAT -> ChatColor.RED + messages.get(lang, "game.confirm_bad_format");
            case UNKNOWN -> ChatColor.RED + messages.get(lang, "game.confirm_unknown");
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

    /** 网站状态，按发送者语言显示。 */
    String webStatus(org.bukkit.command.CommandSender sender) {
        String lang = langOf(sender);
        if (web == null) {
            return messages.get(lang, "game.web_stopped");
        }
        return web.isRunning()
                ? messages.get(lang, "game.web_running", "port", String.valueOf(web.port()))
                : messages.get(lang, "game.web_failed");
    }

    static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
