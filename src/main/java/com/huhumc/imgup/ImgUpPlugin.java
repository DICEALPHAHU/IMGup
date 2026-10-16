package com.huhumc.imgup;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * ImgUp —— 图片上传站的服务端一半。
 *
 * 职责只有两件事：
 *   1. 玩家在游戏里输入 /imgup &lt;码&gt; 时，登记「哪个玩家确认了哪个码」
 *   2. 通过 RCON 响应网站的 imgup add / imgup poll / imgup pending / imgup info
 *
 * 插件不落盘、不联网、不读图片目录，所有状态都在内存里，重启即清空。
 * 上传码只有 5 分钟寿命，这个取舍是刻意的。
 */
public final class ImgUpPlugin extends JavaPlugin {

    /** 去掉 I / O / 0 / 1，避免玩家抄错。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-HJ-NP-Z2-9]{8}$");

    /** 玩家点过名的上传码 -> 码本身，用于 RCON 取走。 */
    private static final class Claim {
        final String code;
        final String player;
        final UUID uuid;
        final long at;

        Claim(String code, String player, UUID uuid, long at) {
            this.code = code;
            this.player = player;
            this.uuid = uuid;
            this.at = at;
        }
    }

    /** 已登记、等待玩家确认的码：code -> 过期时间戳（毫秒）。 */
    private final Map<String, Long> pending = new ConcurrentHashMap<>();

    /** 玩家已确认、等待网站取走的记录。 */
    private final List<Claim> claimed = new ArrayList<>();

    private String sharedSecret = "";
    private long codeTtlMillis = 300_000L;
    private long claimTtlMillis = 600_000L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        this.pending.clear();
        this.claimed.clear();

        ImgUpCommand executor = new ImgUpCommand(this);
        PluginCommand root = getCommand("imgup");
        if (root == null) {
            getLogger().severe("plugin.yml 里没有注册 imgup 指令，插件无法工作！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        root.setExecutor(executor);
        root.setTabCompleter(executor);

        getLogger().info("ImgUp 已启用：玩家用 /imgup <上传码> 确认上传");
        if (sharedSecret.isEmpty()) {
            getLogger().warning("shared_secret 为空，网站将无法通过 RCON 与本插件通信，请检查 config.yml");
        }
    }

    @Override
    public void onDisable() {
        this.pending.clear();
        this.claimed.clear();
        getLogger().info("ImgUp 已停用");
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        reloadSettings();
    }

    private void reloadSettings() {
        this.sharedSecret = getConfig().getString("shared_secret", "");
        long codeTtlSeconds = getConfig().getLong("code_ttl_seconds", 300L);
        long claimTtlSeconds = getConfig().getLong("claim_ttl_seconds", 600L);
        this.codeTtlMillis = Math.max(30L, codeTtlSeconds) * 1000L;
        this.claimTtlMillis = Math.max(60L, claimTtlSeconds) * 1000L;
    }

    // ------------------------------------------------------------ 玩家确认

    /**
     * 玩家在游戏内确认一个上传码。
     *
     * @return 给玩家看的一句话
     */
    String confirm(Player player, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        code = code.replace("-", "").replace(" ", "").replace("_", "");

        if (!CODE_PATTERN.matcher(code).matches()) {
            return ChatColor.RED + "上传码格式不对" + ChatColor.GRAY
                    + "（应该是 8 位字母数字，注意没有 I、O、0、1）";
        }

        long now = System.currentTimeMillis();
        prune(now);

        Long expiresAt = pending.get(code);
        if (expiresAt == null) {
            // 可能是重复确认
            for (Claim claim : claimed) {
                if (claim.code.equals(code) && claim.uuid.equals(player.getUniqueId())) {
                    return ChatColor.YELLOW + "这个上传码你已经确认过了，回网页继续上传吧";
                }
            }
            return ChatColor.RED + "上传码无效或已过期，请回网站重新获取";
        }
        if (expiresAt < now) {
            pending.remove(code);
            return ChatColor.RED + "上传码已过期，请回网站重新获取";
        }

        pending.remove(code);
        claimed.add(new Claim(code, player.getName(), player.getUniqueId(), now));
        getLogger().info("玩家 " + player.getName() + " 确认了上传码 " + code);

        return ChatColor.GREEN + "上传码已确认！" + ChatColor.GRAY + "请回到网页继续上传图片";
    }

    /** 登记一个网站生成的上传码。 */
    boolean addCode(String code) {
        long now = System.currentTimeMillis();
        prune(now);
        Long previous = pending.putIfAbsent(code, now + codeTtlMillis);
        return previous == null;
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Long>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
        Iterator<Claim> claims = claimed.iterator();
        while (claims.hasNext()) {
            if (now - claims.next().at > claimTtlMillis) {
                claims.remove();
            }
        }
    }

    // ------------------------------------------------------------ RCON 接口

    /**
     * 响应网站的 RCON 命令。返回 null 表示这不是 ImgUp 的命令，交回服务端处理。
     *
     * @param rawCommand 不带前导斜杠的完整命令
     */
    String handleCommand(CommandSender sender, String rawCommand) {
        String[] parts = rawCommand.trim().split("\\s+");
        if (parts.length == 0 || !parts[0].equalsIgnoreCase("imgup")) {
            return null;
        }

        if (parts.length < 2) {
            return usage();
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);

        if (sub.equals("help")) {
            return usage();
        }

        // 以下子命令都要求 secret
        if (parts.length < 3) {
            return "DENY";
        }
        if (sharedSecret.isEmpty() || !sharedSecret.equals(parts[2])) {
            getLogger().warning("收到 shared_secret 不匹配的 RCON 请求，来自 " + sender.getName());
            return "DENY";
        }

        switch (sub) {
            case "add": {
                if (parts.length < 4) {
                    return "DENY";
                }
                String code = parts[3].toUpperCase(Locale.ROOT);
                if (!CODE_PATTERN.matcher(code).matches()) {
                    return "BADCODE";
                }
                return addCode(code) ? "ADDED" : "EXISTS";
            }
            case "poll": {
                prune(System.currentTimeMillis());
                List<String> lines = new ArrayList<>();
                Iterator<Claim> it = claimed.iterator();
                while (it.hasNext()) {
                    Claim claim = it.next();
                    lines.add("OK " + claim.code + " " + claim.player + " " + claim.uuid);
                    it.remove(); // 取走即清空
                }
                if (lines.isEmpty()) {
                    return "EMPTY";
                }
                return String.join("\n", lines);
            }
            case "pending": {
                prune(System.currentTimeMillis());
                return "PENDING " + pending.size();
            }
            case "info": {
                prune(System.currentTimeMillis());
                return "IMGUP " + getDescription().getVersion()
                        + " CODES " + pending.size()
                        + " CLAIMS " + claimed.size();
            }
            case "reload": {
                reloadConfig();
                return "RELOADED";
            }
            default:
                return usage();
        }
    }

    private String usage() {
        return "IMGUP usage: imgup add|poll|pending|info <secret> [code]";
    }

    String getSharedSecret() {
        return sharedSecret;
    }
}
