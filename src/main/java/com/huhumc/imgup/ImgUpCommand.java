package com.huhumc.imgup;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 指令入口，两种来源：
 *
 *   玩家  → /imgup &lt;上传码&gt; 或 /imgup 看帮助
 *   控制台/RCON → imgup add|poll|pending|info &lt;secret&gt; [code]
 *
 * 只走注册指令、不做 PlayerCommandPreprocessEvent 拦截：
 * 拦截再 dispatchCommand 会递归，而且会绕过权限判定。
 */
final class ImgUpCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_SUBS =
            List.of("add", "poll", "pending", "info", "reload", "help");

    private final ImgUpPlugin plugin;

    ImgUpCommand(ImgUpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            return playerCommand((Player) sender, args);
        }
        return consoleCommand(sender, args);
    }

    // ---------------------------------------------------------------- 玩家
    private boolean playerCommand(Player player, String[] args) {
        if (plugin.getConfig().getBoolean("require_permission", false)
                && !player.hasPermission("imgup.use")) {
            player.sendMessage(ChatColor.RED + "你没有使用这个指令的权限");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.GOLD + "—— 海报上传 ——");
            player.sendMessage(ChatColor.YELLOW + "1. " + ChatColor.WHITE
                    + "打开上传网站，点「获取上传码」");
            player.sendMessage(ChatColor.YELLOW + "2. " + ChatColor.WHITE
                    + "在这里输入 " + ChatColor.AQUA + "/" + "imgup <上传码>");
            player.sendMessage(ChatColor.YELLOW + "3. " + ChatColor.WHITE
                    + "回网页上传图片，审核通过后就能在游戏里贴了");
            return true;
        }

        player.sendMessage(plugin.confirm(player, args[0]));
        return true;
    }

    // ------------------------------------------------------- 控制台 / RCON
    private boolean consoleCommand(CommandSender sender, String[] args) {
        String full = args.length == 0 ? "imgup help" : ("imgup " + String.join(" ", args));
        String response = plugin.handleCommand(sender, full);
        if (response == null) {
            response = "IMGUP usage: imgup add|poll|pending|info <secret> [code]";
        }
        for (String line : response.split("\n")) {
            sender.sendMessage(line);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            if (args.length == 1) {
                return filter(ROOT_SUBS, args[0]);
            }
            return Collections.emptyList();
        }
        if (args.length == 1 && args[0].isEmpty()) {
            return Collections.singletonList("<上传码>");
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
