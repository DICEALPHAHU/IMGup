package com.github.dicealphahu.imgup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 去 Images 插件的数据库里查「这张图被摆在世界哪个位置」。
 *
 * <h2>为什么要查这个</h2>
 *
 * Images 把图片连同它被放置的位置一起存进 {@code images.db}。删掉一张正在被使用的图之后，
 * 那块地图仍然存在于世界里，但 Images 再去渲染时就找不到对应的记录，于是开始刷报错。
 * 所以在删除之前得先看一眼。
 *
 * <h2>数据长什么样</h2>
 *
 * 表只有一个：{@code custom_images(id INTEGER PRIMARY KEY, data BLOB)}。
 * {@code data} 是 Java 序列化过的 {@code com.andavin.images.image.CustomImage}：
 *
 * <pre>
 *   CustomImage:        id, location, creator, imageName, direction, sections
 *   CustomImageSection: pixels, direction, rotation, location, frameId, mapId, shown
 * </pre>
 *
 * 位置不在顶层，而是每个 section 各存一份（一张大图会跨多个地图，每个地图一格）。
 * 顶层那个 {@code location} 是原图起点。
 *
 * <h2>为什么用反射</h2>
 *
 * ImgUp 不想依赖 Images 的编译期 API —— 那会把两个插件的版本绑死。这里的做法是：
 * 用 Images 自己的类加载器把它的类加载进来反序列化，再按字段名反射取值。
 * 字段名对不上（对方改了实现）时就返回 {@code available=false}，界面上如实说「查不了」，
 * 绝不会因为查不到就当成「没被使用」而放行删除。
 */
public final class ImageUsage {

    /** 一处放置点。 */
    public static final class Place {
        public final String world;
        public final double x;
        public final double y;
        public final double z;
        public final int mapId;

        Place(String world, double x, double y, double z, int mapId) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.mapId = mapId;
        }

        /** 给人看的一行，例如 {@code shencunshijie (12, 65, -30)}。 */
        public String describe() {
            return world + " (" + Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z) + ")";
        }
    }

    /** 查询结果。{@code available=false} 表示查不了，不代表没被使用。 */
    public static final class Result {
        public final boolean available;
        public final String unavailableReason;
        public final List<Place> places;

        private Result(boolean available, String reason, List<Place> places) {
            this.available = available;
            this.unavailableReason = reason;
            this.places = places;
        }

        public boolean inUse() {
            return !places.isEmpty();
        }

        static Result unavailable(String reason) {
            return new Result(false, reason, List.of());
        }

        static Result of(List<Place> places) {
            return new Result(true, "", places);
        }
    }

    private static final String IMAGE_CLASS = "com.andavin.images.image.CustomImage";

    private ImageUsage() {
    }

    /**
     * 查这张图被放在哪里。
     *
     * @param imagesDir     Images 插件的数据目录（{@code images.db} 就在里面）
     * @param imageName     图片文件名，例如 {@code Steve-海报.png}
     * @param imagesLoader  Images 插件的类加载器；为 null 表示没装 Images
     * @param log           日志，可为 null
     */
    public static Result find(Path imagesDir, String imageName,
                              ClassLoader imagesLoader, java.util.logging.Logger log) {
        if (imageName == null || imageName.isEmpty()) {
            return Result.unavailable("没有文件名");
        }
        if (imagesLoader == null) {
            return Result.unavailable("没装 Images 插件");
        }
        Path db = imagesDir == null ? null : imagesDir.resolve("images.db");
        if (db == null || !Files.isRegularFile(db)) {
            // 可能是 MYSQL 或 FILE 模式，那种情况这里读不到，如实说
            return Result.unavailable("找不到 images.db（如果 Images 用的是 MYSQL，这里查不了）");
        }

        try {
            // Paper 自带 sqlite-jdbc，插件能直接用；没带就退化成「查不了」
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            return Result.unavailable("服务端没有自带 SQLite 驱动");
        }

        Class<?> imageClass;
        try {
            imageClass = Class.forName(IMAGE_CLASS, false, imagesLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return Result.unavailable("加载不到 Images 的图片类（对方可能改了内部实现）");
        }

        // 图片名在 blob 里是 modified-UTF-8 的字符串，先用字节做个粗筛，
        // 免得把一百多个 blob 全反序列化一遍 —— 每个都带着完整像素，很吃内存。
        byte[] needle = imageName.getBytes(StandardCharsets.UTF_8);

        List<Place> places = new ArrayList<>();
        String url = "jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro";
        try (Connection con = DriverManager.getConnection(url);
             PreparedStatement ps = con.prepareStatement(
                     "SELECT data FROM custom_images")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] data = rs.getBytes(1);
                    if (data == null || !contains(data, needle)) {
                        continue;
                    }
                    Object image = deserialize(data, imageClass, imagesLoader);
                    if (image == null) {
                        continue;
                    }
                    if (!imageName.equals(str(read(image, "imageName")))) {
                        continue;   // 字节碰巧相同，不是这张图
                    }
                    collect(image, places);
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.log(java.util.logging.Level.WARNING, "查询图片使用情况失败", e);
            }
            return Result.unavailable("读取 images.db 失败：" + e.getClass().getSimpleName());
        }
        return Result.of(places);
    }

    /** 从反序列化出来的 CustomImage 里把位置都收集起来（顶层 + 每个 section）。 */
    private static void collect(Object image, List<Place> out) {
        addLocation(read(image, "location"), -1, out);
        Object sections = read(image, "sections");
        if (!(sections instanceof Map<?, ?> map)) {
            return;
        }
        for (Object section : map.values()) {
            if (section == null) {
                continue;
            }
            Object mapIdField = read(section, "mapId");
            int mapId = mapIdField instanceof Number n ? n.intValue() : -1;
            addLocation(read(section, "location"), mapId, out);
        }
    }

    private static void addLocation(Object location, int mapId, List<Place> out) {
        if (!(location instanceof org.bukkit.Location loc) || loc.getWorld() == null) {
            return;
        }
        // 同一个位置可能被顶层和 section 各记一次，去重
        String world = loc.getWorld().getName();
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        for (Place p : out) {
            if (p.world.equals(world) && p.x == x && p.y == y && p.z == z) {
                return;
            }
        }
        out.add(new Place(world, x, y, z, mapId));
    }

    private static Object read(Object target, String fieldName) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // 往父类找
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value instanceof String s ? s : "";
    }

    /**
     * 用 Images 自己的类加载器反序列化。
     *
     * 必须在服务端跑：对方的 readObject 会用 {@code Bukkit.getWorld(世界名)} 把位置还原出来，
     * 那也是我们唯一能拿到世界名的途径。
     */
    private static Object deserialize(byte[] data, Class<?> imageClass, ClassLoader loader) {
        try {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data)) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass desc)
                        throws IOException, ClassNotFoundException {
                    try {
                        return Class.forName(desc.getName(), false, loader);
                    } catch (ClassNotFoundException e) {
                        return super.resolveClass(desc);
                    }
                }
            };
            Object value = in.readObject();
            return imageClass.isInstance(value) ? value : null;
        } catch (IOException | ClassNotFoundException | RuntimeException | LinkageError e) {
            // 对方版本变了、或者世界已经不存在，都算「这一条读不出来」，跳过即可
            return null;
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
