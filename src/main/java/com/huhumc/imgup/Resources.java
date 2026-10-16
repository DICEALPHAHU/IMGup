package com.huhumc.imgup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 读 jar 里打包的网页资源（web/*.html、web/*.css、web/*.js）。
 *
 * 这些文件直接打进 jar，所以插件是自包含的：复制一个 jar 进 plugins/ 就能用，
 * 不需要在旁边放任何目录、也不需要外部 web 服务器。
 */
public final class Resources {

    private Resources() {
    }

    /** 读成字节；不存在返回 null（而不是抛异常，调用方按 404 处理）。 */
    public static byte[] read(String path) {
        try (InputStream in = Resources.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** 读成 UTF-8 文本；不存在返回 null。 */
    public static String readText(String path) {
        byte[] data = read(path);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }
}
