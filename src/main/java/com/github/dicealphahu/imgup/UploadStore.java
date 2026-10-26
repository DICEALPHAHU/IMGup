package com.github.dicealphahu.imgup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
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
        /**
         * 玩家上传时那个文件本来的名字（不含扩展名）。
         *
         * 昵称默认就等于它，但玩家可以改昵称 —— 改了之后原名就只存在这里了。
         * 后台把它显示出来，方便认出「这张图是当初那个 xxx.png」。
         */
        public String originalName;

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
            out.originalName = originalName;
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
            out.put("original_name", originalName == null ? "" : originalName);
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

    /**
     * 按命名模板生成文件名。
     *
     * 模板写的是「文件名主体」，扩展名由调用方给、自动接在后面。
     * 生成完还是要过一遍 sanitizeNickname，因为模板里有玩家填的内容（昵称、原始文件名），
     * 那些可能带斜杠之类的字符。
     */
    private String makePatternName(String player, String nickname, String originalName,
                                   String extension, int maxNameLength,
                                   int maxNicknameLength, boolean strict)
            throws NameConflictException {
        String safeNickname = Texts.sanitizeNickname(nickname, maxNicknameLength);
        if (safeNickname.isEmpty()) {
            safeNickname = "image";
        }
        String safeOriginal = Texts.sanitizeNickname(
                originalName == null || originalName.isEmpty() ? safeNickname : originalName,
                maxNicknameLength);
        if (safeOriginal.isEmpty()) {
            safeOriginal = safeNickname;
        }

        // 扩展名和那个点也要占长度预算
        int budget = Math.max(1, maxNameLength - extension.length() - 1);

        String base = Texts.sanitizeNickname(
                Texts.applyNamePattern(namingPattern, player, safeNickname, safeOriginal,
                        System.currentTimeMillis()),
                budget);
        if (base.isEmpty()) {
            // 模板里全是不认识的变量时兜底，总得生成个名字
            base = Texts.sanitizeNickname(player + "-" + safeNickname, budget);
        }
        if (base.isEmpty()) {
            base = "image";
        }

        Set<String> taken = takenNames();
        String candidate = base + "." + extension;
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
            candidate = trimmed + tail + "." + extension;
            if (!taken.contains(candidate)) {
                reserved.add(candidate);
                return candidate;
            }
        }
        throw new NameConflictException("同名图片太多了，换一个昵称吧");
    }

    /**
     * 把超过时限还没人处理的待审图片挪到 rejected 目录。
     *
     * **只动 pending**。已发布的图已经在玩家的地图里用着了，挪走会让地图上的画变成空白，
     * 所以过期清理绝不能碰它们。
     *
     * @return 处理了几张
     */
    public int expirePending(long maxAgeMillis, String reason) {
        if (maxAgeMillis <= 0) {
            return 0;
        }
        List<String> expired = new ArrayList<>();
        lock.lock();
        try {
            long cutoff = System.currentTimeMillis() - maxAgeMillis;
            for (Item item : index.values()) {
                if (STATUS_PENDING.equals(item.status)
                        && item.createdAt > 0 && item.createdAt < cutoff) {
                    expired.add(item.name);
                }
            }
        } finally {
            lock.unlock();
        }
        int done = 0;
        for (String name : expired) {
            try {
                reject(name, reason);
                done++;
            } catch (IOException ignored) {
                // 单张失败不影响其他，下次还会再试
            }
        }
        return done;
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

    /** 是否用命名模板生成文件名（来自配置，reload 时可改）。 */
    private volatile boolean namingEnabled;
    /** 命名模板，支持 {player} {nickname} {original} {date} {time}。 */
    private volatile String namingPattern = "{player}-{nickname}";

    /** 启动时从磁盘补登记了多少条（0 表示索引和磁盘本来就一致）。 */
    public int recoveredTotal;
    /** 其中有多少是从 Images 数据目录补登记的、ImgUp 之前就存在的图片。 */
    public int recoveredPublished;

    public UploadStore(Path dataDir, Path imagesDir) throws IOException {
        this(dataDir, imagesDir, false, "{player}-{nickname}");
    }

    public UploadStore(Path dataDir, Path imagesDir, boolean namingEnabled,
                       String namingPattern) throws IOException {
        this.namingEnabled = namingEnabled;
        this.namingPattern = namingPattern == null || namingPattern.trim().isEmpty()
                ? "{player}-{nickname}" : namingPattern.trim();
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

    /**
     * /imgup reload 时更新命名模板。
     *
     * 只影响**之后**上传的文件；已经存在的文件名不会去改 —— 改了游戏里就找不到它们了。
     */
    public void setNaming(boolean enabled, String pattern) {
        this.namingEnabled = enabled;
        this.namingPattern = pattern == null || pattern.trim().isEmpty()
                ? "{player}-{nickname}" : pattern.trim();
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
                item.originalName = str(map.get("original_name"));
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
                if (item.originalName != null && !item.originalName.isEmpty()) {
                    map.put("original_name", item.originalName);
                }
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

    /**
     * 索引里没有、但磁盘上存在的文件补登记（比如上次写到一半崩了，索引丢了，
     * 或者这张图是 ImgUp 装上之前就已经在服务器里的）。
     *
     * 三个目录都要扫：
     * <ul>
     *   <li>pending / rejected —— 索引一旦丢失，这些图会变成谁也看不见、谁也删不掉的
     *       孤儿文件，永远占着磁盘；</li>
     *   <li>images —— Images 插件的数据目录，也就是游戏真正读取的地方。服主装 ImgUp
     *       之前就已经有的图、或者后来手动丢进去的图都在这里。不扫的话索引是空的，
     *       后台一张也列不出来。</li>
     * </ul>
     */
    private void recoverOrphans() {
        int pendingCount = recoverFrom(pendingDir, STATUS_PENDING);
        int rejectedCount = recoverFrom(rejectedDir, STATUS_REJECTED);
        int publishedCount = recoverFromImagesDir();
        int backfilled = backfillDimensions();
        this.recoveredPublished = publishedCount;
        this.recoveredTotal = pendingCount + rejectedCount + publishedCount;
        if (recoveredTotal > 0 || backfilled > 0) {
            try {
                save();
            } catch (RuntimeException e) {
                // 补登记失败不影响启动，索引下次还能再补
            }
        }
    }

    private int recoverFrom(Path dir, String status) {
        try (Stream<Path> stream = Files.list(dir)) {
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
                item.status = status;
                item.createdAt = modifiedTime(path);
                item.bytes = sizeOf(path);
                index.put(name, item);
            }
            return orphans.size();
        } catch (IOException ignored) {
            // 列不出来就算了，不影响正常上传
            return 0;
        }
    }

    /**
     * 扫 Images 数据目录，把没登记过的图片补成「已发布」。
     *
     * 这个目录里除了图片还有 config.yml、images.db、备份 zip，以及服主自己放的
     * 说明文件（有些人就是用空文件的名字当规则清单），所以必须按扩展名过滤。
     * {@link Texts#isSafeFileName} 正好只放行 png/jpg/jpeg，顺手把其他东西全挡掉。
     *
     * 这些文件躺在游戏实际读取的目录里，所以状态必然是「已发布」，不需要审核。
     * 玩家名和昵称只能从 &lt;玩家&gt;-&lt;图片名&gt; 反推，反推不出来就留空。
     */
    private int recoverFromImagesDir() {
        try (Stream<Path> stream = Files.list(imagesDir)) {
            List<Path> orphans = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !index.containsKey(n) && Texts.isSafeFileName(n, 180);
                    })
                    .toList();
            for (Path path : orphans) {
                String name = path.getFileName().toString();
                Item item = new Item();
                item.name = name;
                // 启用了命名模板就按模板拆，拆不出来再退回默认的 <玩家>-<图片名>
                java.util.Map<String, String> parsed = namingEnabled
                        ? Texts.parseNamePattern(namingPattern, Texts.defaultNickname(name))
                        : java.util.Map.of();
                String parsedPlayer = parsed.get("player");
                String parsedNickname = parsed.get("nickname");
                String parsedOriginal = parsed.get("original");
                item.player = parsedPlayer != null
                        ? parsedPlayer : Texts.playerFromFileName(name);
                item.nickname = parsedNickname != null
                        ? parsedNickname : Texts.nicknameFromFileName(name);
                // 老图没有独立的上传记录，「原名」就等于它的昵称
                item.originalName = parsedOriginal != null ? parsedOriginal : item.nickname;
                item.status = STATUS_PUBLISHED;
                item.createdAt = modifiedTime(path);
                item.bytes = sizeOf(path);
                int[] size = imageSize(path);
                if (size != null) {
                    item.width = size[0];
                    item.height = size[1];
                }
                index.put(name, item);
            }
            return orphans.size();
        } catch (IOException ignored) {
            // 列不出来就算了，不影响正常上传
            return 0;
        }
    }

    /**
     * 文件的修改时间，用来当补登记条目的创建时间。
     *
     * 全都记成「现在」的话，一百多张老图会挤在同一个时间点上，后台按时间排序就失去意义了。
     */
    private static long modifiedTime(Path path) {
        try {
            long millis = Files.getLastModifiedTime(path).toMillis();
            return millis > 0 ? millis : System.currentTimeMillis();
        } catch (IOException ignored) {
            return System.currentTimeMillis();
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0;
        }
    }

    /**
     * 给索引里已经有、但宽高是 0 的已发布条目补上尺寸。
     *
     * 为什么需要：早期版本补登记时没有读尺寸，索引里会留下一批 {@code 0x0} 的条目。
     * 这些条目已经进了索引，就不会再被 {@link #recoverFromImagesDir()} 扫到，
     * 后台会一直显示成没有尺寸。补一次写回索引，以后不用再补。
     *
     * 只读文件头，不解码像素；读不出来的（文件坏了）就跳过，下次启动再试，代价很小。
     */
    private int backfillDimensions() {
        int filled = 0;
        for (Item item : index.values()) {
            if (item.width > 0 && item.height > 0) {
                continue;
            }
            if (!STATUS_PUBLISHED.equals(item.status)) {
                continue;
            }
            int[] size = imageSize(imagesDir.resolve(item.name));
            if (size != null) {
                item.width = size[0];
                item.height = size[1];
                filled++;
            }
        }
        return filled;
    }

    /**
     * 只读图片头拿宽高，不解码像素。
     *
     * 补登记时给后台列表填宽高用的。用 ImageIO.read 的话要把每张图完整解码一遍，
     * 一百多张老图会让启动明显变慢，还得吃一遍峰值内存 —— 而这里只需要两个数字。
     * 读不出来就返回 null（文件坏了、扩展名对不上内容），前端会跳过不显示。
     */
    private static int[] imageSize(Path path) {
        try (javax.imageio.stream.ImageInputStream in =
                     javax.imageio.ImageIO.createImageInputStream(path.toFile())) {
            if (in == null) {
                return null;
            }
            Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 ? new int[]{width, height} : null;
            } finally {
                reader.dispose();
            }
        } catch (Exception ignored) {
            return null;
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
                               String originalName, int maxNameLength, int maxNicknameLength,
                               boolean strict) throws NameConflictException {
        if (namingEnabled) {
            return makePatternName(player, nickname, originalName, extension,
                    maxNameLength, maxNicknameLength, strict);
        }
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
    public Item store(String name, String player, String nickname, String originalName,
                      byte[] data, int width, int height, boolean autoPublish) throws IOException {
        lock.lock();
        try {
            Files.write(pendingDir.resolve(name), data);

            Item item = new Item();
            item.name = name;
            item.player = player;
            item.nickname = nickname;
            item.originalName = originalName == null ? "" : originalName;
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
        } catch (IOException | RuntimeException e) {
            // 落盘失败就把占位放掉，别让这个名字永远不可用。
            //
            // 注意这里必须连 RuntimeException 一起接住：save() 写索引失败时抛的是
            // IllegalStateException（它把 IOException 包了一层），只 catch IOException
            // 的话占位和残留文件都不会被清理 —— 磁盘一满，每失败一次就永久烧掉一个
            // 文件名变体（图.jpg、图-2.jpg、图-3.jpg……），待审目录里还堆着残file。
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
