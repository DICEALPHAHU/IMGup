package com.huhumc.imgup;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /imgup 指令。
 *
 * 一条指令干两件事，按第一个参数区分：
 *
 *   玩家： /imgup &lt;8 位上传码&gt;        —— 确认上传码（和 1.x 一样）
 *
 *   站长： /imgup list [页码]         —— 看待审核的图
 *          /imgup ok &lt;文件名&gt;         —— 通过，图片移进 Images 目录
 *          /imgup no &lt;文件名&gt; [原因]  —— 拒绝，移进 rejected/ 留底
 *          /imgup del &lt;文件名&gt;        —— 彻底删除（不可恢复）
 *          /imgup info                —— 看运行状态
 *          /imgup reload              —— 重读 config.yml 并重启网站
 *
 * 审核在游戏内和网页后台（/admin）都能做，改的是同一份数据。
 *
 * 所有给玩家看的文字都来自 messages/&lt;语言&gt;.yml，按玩家客户端语言自动选 ——
 * 中文客户端看中文，英文客户端看英文；玩家用的语言没有对应文件时回退到
 * config.yml 里的 language。
 */
public final class ImgUpCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 8;

    private final ImgUpPlugin plugin;

    public ImgUpCommand(ImgUpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list", "ls" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                list(sender, label, args.length > 1 ? args[1] : "1");
            }
            case "ok", "approve", "pass" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED
                            + plugin.msg(sender, "game.need_ok_arg", "label", label));
                    return true;
                }
                decide(sender, label, join(args, 1), true, "");
            }
            case "no", "reject", "deny" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED
                            + plugin.msg(sender, "game.need_no_arg", "label", label));
                    return true;
                }
                // 文件名里没有空格（玩家名和昵称都净化过），所以 args[1] 就是文件名
                decide(sender, label, args[1], false, args.length > 2 ? join(args, 2) : "");
            }
            case "del", "delete", "rm" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED
                            + plugin.msg(sender, "game.need_del_arg", "label", label));
                    return true;
                }
                delete(sender, label, join(args, 1));
            }
            case "info", "status" -> info(sender, label);
            case "reload" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.reloadAll();
                sender.sendMessage(ChatColor.GREEN + plugin.msg(sender, "game.reloaded",
                        "status", plugin.webStatus(sender)));
            }
            default -> {
                // 不是子命令，那就当上传码处理
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED
                            + plugin.msg(sender, "game.code_player_only"));
                    return true;
                }
                if (plugin.requirePermission() && !player.hasPermission("imgup.use")) {
                    player.sendMessage(ChatColor.RED + plugin.msg(player, "game.no_permission_use"));
                    return true;
                }
                player.sendMessage(plugin.confirm(player, args[0]));
            }
        }
        return true;
    }

    // ------------------------------------------------------------ 子命令

    private void list(CommandSender sender, String label, String rawPage) {
        List<UploadStore.Item> pending = plugin.store().byStatus(UploadStore.STATUS_PENDING);
        if (pending.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + plugin.msg(sender, "game.list_empty"));
            return;
        }
        int page;
        try {
            page = Math.max(1, Integer.parseInt(rawPage.trim()));
        } catch (NumberFormatException e) {
            page = 1;
        }
        int pages = (pending.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.min(page, pages);

        sender.sendMessage(ChatColor.GOLD + plugin.msg(sender, "game.list_header",
                "count", String.valueOf(pending.size()),
                "page", String.valueOf(page),
                "pages", String.valueOf(pages)));
        int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(from + PAGE_SIZE, pending.size()); i++) {
            UploadStore.Item item = pending.get(i);
            sender.sendMessage(ChatColor.WHITE + plugin.msg(sender, "game.list_line",
                    "dot", plugin.isOnline(item.player) ? "●" : "○",
                    "name", item.name,
                    "width", String.valueOf(item.width),
                    "height", String.valueOf(item.height),
                    "size", WebServer.humanBytes(item.bytes),
                    "player", item.player == null || item.player.isEmpty() ? "?" : item.player));
        }
        sender.sendMessage(ChatColor.GRAY + plugin.msg(sender, "game.list_hint", "label", label));
        if (pages > 1) {
            sender.sendMessage(ChatColor.GRAY + plugin.msg(sender, "game.list_more",
                    "label", label,
                    "page", String.valueOf(page < pages ? page + 1 : 1)));
        }
    }

    private void decide(CommandSender sender, String label, String name,
                        boolean approve, String reason) {
        UploadStore.Item item = plugin.store().get(name);
        if (item == null) {
            // 允许少打扩展名：Player-我的海报 -> Player-我的海报.png
            item = findByPrefix(name);
        }
        if (item == null) {
            sender.sendMessage(ChatColor.RED + plugin.msg(sender, "game.not_found",
                    "name", name, "label", label));
            return;
        }
        try {
            if (approve) {
                UploadStore.Item done = plugin.store().approve(item.name);
                sender.sendMessage(ChatColor.GREEN + plugin.msg(sender, "game.approved",
                        "name", done.name));
                plugin.notifyPlayer(done.player, "game.notify_approved", "name", done.name);
            } else {
                UploadStore.Item done = plugin.store().reject(item.name, reason);
                sender.sendMessage(ChatColor.YELLOW + (reason.isEmpty()
                        ? plugin.msg(sender, "game.rejected", "name", done.name)
                        : plugin.msg(sender, "game.rejected_reason",
                                "name", done.name, "reason", reason)));
                if (reason.isEmpty()) {
                    plugin.notifyPlayer(done.player, "game.notify_rejected", "name", done.name);
                } else {
                    plugin.notifyPlayer(done.player, "game.notify_rejected_reason",
                            "name", done.name, "reason", reason);
                }
            }
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + plugin.msg(sender, "game.failed",
                    "error", String.valueOf(e.getMessage())));
        }
    }

    private void delete(CommandSender sender, String label, String name) {
        UploadStore.Item item = plugin.store().get(name);
        String target = item != null ? item.name : name;
        if (plugin.store().delete(target)) {
            sender.sendMessage(ChatColor.GREEN
                    + plugin.msg(sender, "game.deleted", "name", target));
        } else {
            sender.sendMessage(ChatColor.RED + plugin.msg(sender, "game.not_found",
                    "name", name, "label", label));
        }
    }

    private void info(CommandSender sender, String label) {
        var counts = plugin.store().counts();
        sender.sendMessage(ChatColor.GOLD + plugin.msg(sender, "game.info_header",
                "version", plugin.getDescription().getVersion(),
                "status", plugin.webStatus(sender)));
        sender.sendMessage(ChatColor.GRAY + plugin.msg(sender, "game.info_url",
                "url", plugin.webConfig().base()));
        sender.sendMessage(ChatColor.GRAY + plugin.msg(sender, "game.info_dir",
                "dir", String.valueOf(plugin.imagesDir())));
        sender.sendMessage(ChatColor.GRAY + plugin.msg(sender, "game.info_counts",
                "pending", String.valueOf(counts.get("pending")),
                "published", String.valueOf(counts.get("published")),
                "rejected", String.valueOf(counts.get("rejected")),
                "players", String.valueOf(counts.get("players"))));
        sender.sendMessage(ChatColor.GRAY + plugin.msg(sender, "game.info_codes",
                "codes", String.valueOf(plugin.sessions().pendingCodeCount()),
                "sessions", String.valueOf(plugin.sessions().sessionCount())));
        if (!plugin.webConfig().autoPublish) {
            sender.sendMessage(ChatColor.GRAY
                    + plugin.msg(sender, "game.info_manual_review", "label", label));
        }
    }

    // ------------------------------------------------------------ 工具

    /** 允许只输入文件名的一部分（不带扩展名也可以）。有歧义时返回 null。 */
    private UploadStore.Item findByPrefix(String name) {
        String needle = name.toLowerCase(Locale.ROOT);
        UploadStore.Item found = null;
        for (UploadStore.Item item : plugin.store().byStatus(UploadStore.STATUS_PENDING)) {
            String candidate = item.name.toLowerCase(Locale.ROOT);
            boolean hit = candidate.equals(needle + ".png") || candidate.equals(needle + ".jpg")
                    || candidate.startsWith(needle);
            if (hit) {
                if (found != null) {
                    return null;
                }
                found = item;
            }
        }
        return found;
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private boolean requireAdmin(CommandSender sender) {
        if (plugin.isAdmin(sender)) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + plugin.msg(sender, "game.no_permission_admin"));
        return false;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + plugin.msg(sender, "game.usage_header",
                "version", plugin.getDescription().getVersion(),
                "url", plugin.webConfig().base()));
        sender.sendMessage(ChatColor.GRAY
                + plugin.msg(sender, "game.usage_player", "label", label));
        if (plugin.isAdmin(sender)) {
            sender.sendMessage(ChatColor.GRAY + plugin.msg(sender, "game.usage_admin_title"));
            sender.sendMessage(ChatColor.GRAY
                    + plugin.msg(sender, "game.usage_list", "label", label));
            sender.sendMessage(ChatColor.GRAY
                    + plugin.msg(sender, "game.usage_ok", "label", label));
            sender.sendMessage(ChatColor.GRAY
                    + plugin.msg(sender, "game.usage_no", "label", label));
            sender.sendMessage(ChatColor.GRAY
                    + plugin.msg(sender, "game.usage_del", "label", label));
            sender.sendMessage(ChatColor.GRAY
                    + plugin.msg(sender, "game.usage_info", "label", label));
            sender.sendMessage(ChatColor.GRAY
                    + plugin.msg(sender, "game.usage_reload", "label", label));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (plugin.isAdmin(sender)) {
                for (String sub : new String[]{"list", "ok", "no", "del", "info", "reload"}) {
                    if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                        out.add(sub);
                    }
                }
            }
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("ok") || sub.equals("no") || sub.equals("del")) {
                for (UploadStore.Item item : plugin.store().byStatus(null)) {
                    if (item.name.toLowerCase(Locale.ROOT)
                            .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(item.name);
                    }
                }
            }
        }
        return out;
    }
}
