package com.huhumc.imgup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 上传记录的存放与状态流转。
 *
 * 目录布局（dataFolder 就是 plugins/ImgUp/）：
 *   plugins/ImgUp/pending/     待审核的图（还没进 Images 目录）
 *   plugins/ImgUp/rejected/    被拒绝的图（留个底，方便回查）
 *   plugins/ImgUp/uploads.json 索引：文件名 -> 元信息
 *
 * 通过审核后文件被**移动**到图片目录（plugins/Images/），和 Images 插件同一个
 * 文件系统，所以是原子 rename。
 *
 * 索引只用于审核和统计；游戏能不能用某张图只取决于文件在不在 Images 目录里，
 * 所以即使索引丢了也不影响已经发布的图。
 */
public final class UploadStore {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_REJECTED = "rejected";

    /** 一条上传记录。字段都是可变的，因为状态会流转。 */
    public static final class Item {
        public String name;
        public String player;
        public String nickname;
        public String status;
        public long bytes;
        public int width;
        public int height;
        public long createdAt;
        public String rejectedReason;

        public Item() {
        }

        Item copy() {
            Item out = new Item();
            out.name = name;
            out.player = player;
            out.nickname = nickname;
            out.status = status;
            out.bytes = bytes;
            out.width = width;
            out.height = height;
            out.createdAt = createdAt;
            out.rejectedReason = rejectedReason;
            return out;
        }

        public Map<String, Object> toJson(WebConfig config) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("player", player);
            out.put("nickname", nickname == null ? "" : nickname);
            out.put("status", status);
            out.put("bytes", bytes);
            out.put("width", width);
            out.put("height", height);
            out.put("created_at", createdAt);
            if (rejectedReason != null && !rejectedReason.isEmpty()) {
                out.put("rejected_reason", rejectedReason);
            }
            // 只有已发布的图才有公开地址；待审核的走后台预览
            if (STATUS_PUBLISHED.equals(status)) {
                out.put("url", config.uploadUrl(name));
            } else if (STATUS_PENDING.equals(status)) {
                out.put("url", "/preview/" + Texts.encodePathSegment(name));
            } else {
                out.put("url", "");
            }
            return out;
        }
    }

    /** 文件名生成失败（同名太多）时抛出。 */
    public static final class NameConflictException extends Exception {
        public NameConflictException(String message) {
            super(message);
        }
    }

    private final Path dataDir;
    private final Path pendingDir;
    private final Path rejectedDir;
    private final Path indexFile;

    /** 图片最终目录，也就是 Images 插件的 getDataFolder()。 */
    private final Path imagesDir;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Item> index = new LinkedHashMap<>();
    private final Set<String> reserved = ConcurrentHashMap.newKeySet();

    public UploadStore(Path dataDir, Path imagesDir) throws IOException {
        this.dataDir = dataDir;
        this.pendingDir = dataDir.resolve("pending");
        this.rejectedDir = dataDir.resolve("rejected");
        this.indexFile = dataDir.resolve("uploads.json");
        this.imagesDir = imagesDir;

        Files.createDirectories(pendingDir);
        Files.createDirectories(rejectedDir);
        Files.createDirectories(imagesDir);
        load();
        recoverOrphans();
    }

    public Path imagesDir() {
        return imagesDir;
    }

    // ------------------------------------------------------------ 读写

    private void load() {
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        try {
            String text = Files.readString(indexFile, StandardCharsets.UTF_8);
            Map<String, Object> root = Json.parseObject(text);
            Object items = root.get("items");
            if (!(items instanceof List<?> list)) {
                return;
            }
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                Item item = new Item();
                item.name = str(map.get("name"));
                item.player = str(map.get("player"));
                item.nickname = str(map.get("nickname"));
                item.status = str(map.get("status"));
                item.bytes = num(map.get("bytes"));
                item.width = (int) num(map.get("width"));
                item.height = (int) num(map.get("height"));
                item.createdAt = num(map.get("created_at"));
                item.rejectedReason = str(map.get("rejected_reason"));
                if (!item.name.isEmpty()) {
                    index.put(item.name, item);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 索引坏了只影响审核列表，已经发布的图不受影响，所以不阻断启动
            index.clear();
        }
    }

    private void save() {
        lock.lock();
        try {
            List<Object> items = new ArrayList<>();
            for (Item item : index.values()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("name", item.name);
                map.put("player", item.player);
                map.put("nickname", item.nickname);
                map.put("status", item.status);
                map.put("bytes", item.bytes);
                map.put("width", item.width);
                map.put("height", item.height);
                map.put("created_at", item.createdAt);
                if (item.rejectedReason != null) {
                    map.put("rejected_reason", item.rejectedReason);
                }
                items.add(map);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", 1);
            root.put("items", items);
            Files.createDirectories(dataDir);
            Path tmp = indexFile.resolveSibling("uploads.json.tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 记在调用方的日志里更合适，这里只是尽力而为
            throw new IllegalStateException("写入索引失败：" + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /** 索引里没有、但 pending 目录里存在的文件（比如上次写到一半崩了）补登记。 */
    private void recoverOrphans() {
        try (Stream<Path> stream = Files.list(pendingDir)) {
            List<Path> orphans = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !index.containsKey(p.getFileName().toString()))
                    .toList();
            for (Path path : orphans) {
                String name = path.getFileName().toString();
                if (!Texts.isSafeFileName(name, 180)) {
                    continue;
                }
                Item item = new Item();
                item.name = name;
                item.player = "";
                item.nickname = name;
                item.status = STATUS_PENDING;
                item.createdAt = System.currentTimeMillis();
                try {
                    item.bytes = Files.size(path);
                } catch (IOException ignored) {
                    item.bytes = 0;
                }
                index.put(name, item);
            }
            if (!orphans.isEmpty()) {
                save();
            }
        } catch (IOException ignored) {
            // 列不出来就算了，不影响正常上传
        }
    }

    // -------------------------------------------------------- 查询

    public Item get(String name) {
        lock.lock();
        try {
            Item item = index.get(name);
            return item == null ? null : item.copy();
        } finally {
            lock.unlock();
        }
    }

    public List<Item> byPlayer(String player) {
        lock.lock();
        try {
            List<Item> out = new ArrayList<>();
            for (Item item : index.values()) {
                if (item.player != null && item.player.equals(player)) {
                    out.add(item.copy());
                }
            }
            out.sort(Comparator.comparingLong((Item i) -> i.createdAt).reversed());
            return out;
        } finally {
            lock.unlock();
        }
    }

    public List<Item> byStatus(String status) {
        lock.lock();
        try {
            List<Item> out = new ArrayList<>();
            for (Item item : index.values()) {
                if (status == null || status.isEmpty() || status.equals(item.status)) {
                    out.add(item.copy());
                }
            }
            out.sort(Comparator.comparingLong((Item i) -> i.createdAt).reversed());
            return out;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Integer> counts() {
        lock.lock();
        try {
            int pending = 0;
            int published = 0;
            int rejected = 0;
            Set<String> players = ConcurrentHashMap.newKeySet();
            for (Item item : index.values()) {
                switch (item.status == null ? "" : item.status) {
                    case STATUS_PENDING -> pending++;
                    case STATUS_PUBLISHED -> published++;
                    case STATUS_REJECTED -> rejected++;
                    default -> {
                    }
                }
                if (item.player != null && !item.player.isEmpty()) {
                    players.add(item.player);
                }
            }
            Map<String, Integer> out = new LinkedHashMap<>();
            out.put("pending", pending);
            out.put("published", published);
            out.put("rejected", rejected);
            out.put("players", players.size());
            out.put("total", index.size());
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** 某个玩家最近 24 小时上传了多少张。 */
    public int uploadedToday(String player) {
        long since = System.currentTimeMillis() - 24L * 3600_000L;
        lock.lock();
        try {
            int count = 0;
            for (Item item : index.values()) {
                if (player.equals(item.player) && item.createdAt >= since) {
                    count++;
                }
            }
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** 当前所有被占用的文件名（索引 + 图片目录里已有的 + 正在生成中的）。 */
    private Set<String> takenNames() {
        Set<String> taken = ConcurrentHashMap.newKeySet();
        taken.addAll(index.keySet());
        taken.addAll(reserved);
        try (Stream<Path> stream = Files.list(imagesDir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> taken.add(p.getFileName().toString()));
        } catch (IOException ignored) {
            // 目录读不到时至少还有索引里的名字
        }
        return taken;
    }

    // -------------------------------------------------------- 命名

    /**
     * 生成 &lt;玩家&gt;-&lt;昵称&gt;.&lt;ext&gt;，重名加 -2 / -3。
     *
     * 用数字后缀而不是随机串，是因为玩家要在游戏里手输这个名字 ——
     * 随机串虽然绝不冲突，但根本没法输入。
     *
     * @param strict true 时重名直接抛 NameConflictException（改名场景：
     *               玩家主动指定的名字，悄悄改成 -2 会让他困惑）
     */
    public String makeFileName(String player, String nickname, String extension,
                               int maxNameLength, int maxNicknameLength, boolean strict)
            throws NameConflictException {
        int budget = maxNameLength - player.length() - 1 - extension.length() - 1;
        budget = Math.max(1, Math.min(budget, maxNicknameLength));

        String base = Texts.sanitizeNickname(nickname, budget);
        if (base.isEmpty()) {
            base = "image";
        }
        Set<String> taken = takenNames();

        String candidate = player + "-" + base + "." + extension;
        if (!taken.contains(candidate)) {
            reserved.add(candidate);
            return candidate;
        }
        if (strict) {
            throw new NameConflictException("已经有叫「" + base + "」的图片了，换一个昵称吧");
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String tail = "-" + suffix;
            String trimmed = Texts.sanitizeNickname(base, Math.max(1, budget - tail.length()));
            if (trimmed.isEmpty()) {
                trimmed = "image";
            }
            candidate = player + "-" + trimmed + tail + "." + extension;
            if (!taken.contains(candidate)) {
                reserved.add(candidate);
                return candidate;
            }
        }
        throw new NameConflictException("同名图片太多了，换一个昵称吧");
    }

    /** 改名时用：昵称是否已经被同一玩家的其它图片占用（含 -2 变体）。 */
    public boolean nicknameTaken(String player, String nickname, int maxNicknameLength,
                                 String exceptName) {
        String clean = Texts.sanitizeNickname(nickname, maxNicknameLength);
        if (clean.isEmpty()) {
            return false;
        }
        String prefix = player + "-" + clean;
        lock.lock();
        try {
            for (String existing : index.keySet()) {
                if (existing.equals(exceptName)) {
                    continue;
                }
                String stem = existing;
                int dot = stem.lastIndexOf('.');
                if (dot > 0) {
                    stem = stem.substring(0, dot);
                }
                if (stem.equals(prefix) || stem.matches(java.util.regex.Pattern.quote(prefix) + "-\\d+")) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------- 状态流转

    /** 落盘一张待审核（或直接发布）的图，返回索引条目。 */
    public Item store(String name, String player, String nickname, byte[] data,
                      int width, int height, boolean autoPublish) throws IOException {
        lock.lock();
        try {
            Files.write(pendingDir.resolve(name), data);

            Item item = new Item();
            item.name = name;
            item.player = player;
            item.nickname = nickname;
            item.status = autoPublish ? STATUS_PUBLISHED : STATUS_PENDING;
            item.bytes = data.length;
            item.width = width;
            item.height = height;
            item.createdAt = System.currentTimeMillis();

            if (autoPublish) {
                move(pendingDir.resolve(name), imagesDir.resolve(name));
            }
            index.put(name, item);
            save();
            reserved.remove(name);
            return item.copy();
        } catch (IOException e) {
            // 落盘失败就把占位放掉，别让这个名字永远不可用
            reserved.remove(name);
            try {
                Files.deleteIfExists(pendingDir.resolve(name));
            } catch (IOException ignored) {
                // 尽力清理
            }
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /** 改名：同目录内搬移，保持状态不变。 */
    public Item rename(String oldName, String newName, String nickname) throws IOException {
        lock.lock();
        try {
            Item item = index.get(oldName);
            if (item == null) {
                throw new IOException("找不到这个记录");
            }
            if (oldName.equals(newName)) {
                item.nickname = nickname;
                save();
                return item.copy();
            }
            Path from = pathOf(item);
            Path to = from.resolveSibling(newName);
            if (Files.exists(to)) {
                throw new IOException("目标文件名已被占用");
            }
            if (!Files.isRegularFile(from)) {
                throw new IOException("原文件已丢失");
            }
            move(from, to);
            index.remove(oldName);
            item.name = newName;
            item.nickname = nickname;
            index.put(newName, item);
            save();
            reserved.remove(newName);
            return item.copy();
        } finally {
            lock.unlock();
        }
    }

    /** 通过审核：把文件移进图片目录。 */
    public Item approve(String name) throws IOException {
        lock.lock();
        try {
            Item item = index.get(name);
            if (item == null) {
                throw new IOException("找不到这个记录");
            }
            if (STATUS_PUBLISHED.equals(item.status)) {
                return item.copy();
            }
            Path from = pendingDir.resolve(name);
            Path to = imagesDir.resolve(name);
            if (!Files.isRegularFile(from)) {
                // 可能已经手动放进去了，那就只改状态
                if (Files.isRegularFile(to)) {
                    item.status = STATUS_PUBLISHED;
                    save();
                    return item.copy();
                }
                throw new IOException("文件已丢失");
            }
            move(from, to);
            item.status = STATUS_PUBLISHED;
            save();
            return item.copy();
        } finally {
            lock.unlock();
        }
    }

    /** 拒绝：移到 rejected 目录留底。 */
    public Item reject(String name, String reason) throws IOException {
        lock.lock();
        try {
            Item item = index.get(name);
            if (item == null) {
                throw new IOException("找不到这个记录");
            }
            Path from = pathOf(item);
            Path to = rejectedDir.resolve(name);
            if (Files.isRegularFile(from) && !from.equals(to)) {
                move(from, to);
            }
            item.status = STATUS_REJECTED;
            item.rejectedReason = reason == null ? "" : reason;
            save();
            return item.copy();
        } finally {
            lock.unlock();
        }
    }

    /** 彻底删除：索引 + 文件。 */
    public boolean delete(String name) {
        lock.lock();
        try {
            Item item = index.remove(name);
            reserved.remove(name);
            boolean removed = false;
            for (Path candidate : new Path[]{
                    imagesDir.resolve(name), pendingDir.resolve(name), rejectedDir.resolve(name)}) {
                try {
                    removed |= Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // 尽力删除，删不掉也让索引先干净
                }
            }
            if (item != null) {
                save();
            }
            return item != null || removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 找到一条记录的物理路径。已发布的在图片目录，其余按状态找。
     * 返回 null 表示文件已经不在了。
     */
    public Path pathOf(Item item) {
        if (item == null || item.name == null) {
            return pendingDir;
        }
        return switch (item.status == null ? "" : item.status) {
            case STATUS_PUBLISHED -> imagesDir.resolve(item.name);
            case STATUS_REJECTED -> rejectedDir.resolve(item.name);
            default -> pendingDir.resolve(item.name);
        };
    }

    /** 公开可访问的图：只有已发布的才算。 */
    public Path publicPath(String name) {
        if (!Texts.isSafeFileName(name, 180)) {
            return null;
        }
        Path path = imagesDir.resolve(name);
        return Files.isRegularFile(path) ? path : null;
    }

    /**
     * 跨文件系统也安全的移动。
     *
     * pending 目录和 Images 目录通常在同一个盘上，但插件目录可能被单独挂载，
     * 所以先试原子 rename，失败再退化成「复制 + 删源」。
     */
    private static void move(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException e) {
            // 跨设备：复制后删源，并且只在复制成功后才删
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(from);
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
