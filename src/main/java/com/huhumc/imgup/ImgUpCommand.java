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
 * 审核做在游戏里而不是网页上，是为了不再引一套管理员密码：权限直接用
 * LuckPerms 的 imgup.admin，谁有权限谁就能审，和服务器其它权限体系统一。
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
                    sender.sendMessage(ChatColor.RED + "用法：" + ChatColor.WHITE
                            + "/" + label + " ok <文件名>");
                    return true;
                }
                decide(sender, join(args, 1), true, "");
            }
            case "no", "reject", "deny" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "用法：" + ChatColor.WHITE
                            + "/" + label + " no <文件名> [原因]");
                    return true;
                }
                // 文件名里没有空格（玩家名和昵称都净化过），所以 args[1] 就是文件名
                decide(sender, args[1], false, args.length > 2 ? join(args, 2) : "");
            }
            case "del", "delete", "rm" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "用法：" + ChatColor.WHITE
                            + "/" + label + " del <文件名>");
                    return true;
                }
                delete(sender, join(args, 1));
            }
            case "info", "status" -> info(sender, label);
            case "reload" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.reloadAll();
                sender.sendMessage(ChatColor.GREEN + "ImgUp 配置已重新加载，网站："
                        + plugin.webStatus());
            }
            default -> {
                // 不是子命令，那就当上传码处理
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "上传码要在游戏里确认（或用上面的管理子命令）");
                    return true;
                }
                if (plugin.requirePermission() && !player.hasPermission("imgup.use")) {
                    player.sendMessage(ChatColor.RED + "你没有确认上传码的权限（imgup.use）");
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
            sender.sendMessage(ChatColor.GRAY + "现在没有待审核的图片。");
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

        sender.sendMessage(ChatColor.GOLD + "待审核图片（" + pending.size() + " 张）"
                + ChatColor.GRAY + " 第 " + page + "/" + pages + " 页");
        int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(from + PAGE_SIZE, pending.size()); i++) {
            UploadStore.Item item = pending.get(i);
            String dot = plugin.isOnline(item.player)
                    ? ChatColor.GREEN + "●" : ChatColor.DARK_GRAY + "○";
            sender.sendMessage("  " + dot + " " + ChatColor.WHITE + item.name
                    + ChatColor.GRAY + "  " + item.width + "x" + item.height
                    + "  " + WebServer.humanBytes(item.bytes)
                    + "  来自 " + (item.player == null || item.player.isEmpty() ? "?" : item.player));
        }
        sender.sendMessage(ChatColor.GRAY + "通过：" + ChatColor.WHITE + "/" + label
                + " ok <文件名>" + ChatColor.GRAY + "   拒绝：" + ChatColor.WHITE + "/"
                + label + " no <文件名> [原因]");
        if (pages > 1) {
            sender.sendMessage(ChatColor.GRAY + "翻页：" + ChatColor.WHITE + "/" + label
                    + " list " + (page < pages ? page + 1 : 1));
        }
    }

    private void decide(CommandSender sender, String name, boolean approve, String reason) {
        UploadStore.Item item = plugin.store().get(name);
        if (item == null) {
            // 允许少打扩展名：Player-我的海报 -> Player-我的海报.png
            item = findByPrefix(name);
        }
        if (item == null) {
            sender.sendMessage(ChatColor.RED + "找不到叫「" + name + "」的图片。"
                    + "用 /" + "imgup list 看看待审核的文件名。");
            return;
        }
        try {
            if (approve) {
                UploadStore.Item done = plugin.store().approve(item.name);
                sender.sendMessage(ChatColor.GREEN + "已通过：" + ChatColor.WHITE + done.name
                        + ChatColor.GRAY + "（已进入 Images 目录，玩家现在可以贴了）");
                plugin.notify(done.player,
                        "你的图片「" + done.name + "」审核通过了，可以在游戏里贴了");
            } else {
                UploadStore.Item done = plugin.store().reject(item.name, reason);
                sender.sendMessage(ChatColor.YELLOW + "已拒绝：" + ChatColor.WHITE + done.name
                        + (reason.isEmpty() ? "" : ChatColor.GRAY + "（原因：" + reason + "）"));
                plugin.notify(done.player, "你的图片「" + done.name + "」没有通过审核"
                        + (reason.isEmpty() ? "" : "，原因：" + reason));
            }
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "操作失败：" + e.getMessage());
        }
    }

    private void delete(CommandSender sender, String name) {
        UploadStore.Item item = plugin.store().get(name);
        String target = item != null ? item.name : name;
        if (plugin.store().delete(target)) {
            sender.sendMessage(ChatColor.GREEN + "已删除：" + ChatColor.WHITE + target);
        } else {
            sender.sendMessage(ChatColor.RED + "找不到叫「" + name + "」的图片");
        }
    }

    private void info(CommandSender sender, String label) {
        var counts = plugin.store().counts();
        sender.sendMessage(ChatColor.GOLD + "ImgUp " + plugin.getDescription().getVersion()
                + ChatColor.GRAY + "  网站：" + ChatColor.WHITE + plugin.webStatus());
        sender.sendMessage(ChatColor.GRAY + "对外地址：" + ChatColor.WHITE + plugin.webConfig().base());
        sender.sendMessage(ChatColor.GRAY + "图片目录：" + ChatColor.WHITE + plugin.imagesDir());
        sender.sendMessage(ChatColor.GRAY + "统计：" + ChatColor.WHITE
                + "待审核 " + counts.get("pending")
                + "，已发布 " + counts.get("published")
                + "，已拒绝 " + counts.get("rejected")
                + "，玩家 " + counts.get("players") + " 人");
        sender.sendMessage(ChatColor.GRAY + "上传码：" + ChatColor.WHITE
                + plugin.sessions().pendingCodeCount() + " 个等待确认"
                + ChatColor.GRAY + "，会话 " + ChatColor.WHITE + plugin.sessions().sessionCount());
        if (!plugin.webConfig().autoPublish) {
            sender.sendMessage(ChatColor.GRAY + "当前是" + ChatColor.YELLOW + "人工审核"
                    + ChatColor.GRAY + "模式：上传的图要你 /" + label + " ok 之后才能贴");
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
        sender.sendMessage(ChatColor.RED + "你没有权限（需要 imgup.admin）");
        return false;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "ImgUp " + plugin.getDescription().getVersion()
                + ChatColor.GRAY + "  网站：" + ChatColor.WHITE + plugin.webConfig().base());
        sender.sendMessage(ChatColor.GRAY + "玩家：" + ChatColor.WHITE + "/" + label
                + " <上传码>" + ChatColor.GRAY + "  确认上传码（也可以用网页上的按钮）");
        if (plugin.isAdmin(sender)) {
            sender.sendMessage(ChatColor.GRAY + "站长：");
            sender.sendMessage(ChatColor.GRAY + "  /" + label + " list [页码]");
            sender.sendMessage(ChatColor.GRAY + "  /" + label + " ok <文件名>");
            sender.sendMessage(ChatColor.GRAY + "  /" + label + " no <文件名> [原因]");
            sender.sendMessage(ChatColor.GRAY + "  /" + label + " del <文件名>");
            sender.sendMessage(ChatColor.GRAY + "  /" + label + " info");
            sender.sendMessage(ChatColor.GRAY + "  /" + label + " reload");
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
