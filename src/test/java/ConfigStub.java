import com.huhumc.imgup.WebConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试用的配置读取器：用一个 Map 冒充 config.yml。
 *
 * 这样 {@link WebConfig} 的解析逻辑（默认值、范围钳制、网站元素读取）
 * 都能在没有 Minecraft 服务端的情况下验证。
 */
public final class ConfigStub implements WebConfig.Source {

    private final Map<String, Object> values = new LinkedHashMap<>();

    public ConfigStub set(String path, Object value) {
        values.put(path, value);
        return this;
    }

    @Override
    public boolean getBoolean(String path, boolean fallback) {
        Object value = values.get(path);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    @Override
    public String getString(String path, String fallback) {
        Object value = values.get(path);
        return value == null ? fallback : String.valueOf(value);
    }

    @Override
    public int getInt(String path, int fallback) {
        Object value = values.get(path);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public long getLong(String path, long fallback) {
        Object value = values.get(path);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
