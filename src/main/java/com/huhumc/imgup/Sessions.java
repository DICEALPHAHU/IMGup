package com.huhumc.imgup;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 浏览器会话与一次性上传码。
 *
 * 和以前走 RCON 的版本比，这里简单得多：网页和插件现在是同一个 JVM，
 * 玩家在游戏里敲 /imgup 之后，会话状态**当场**就更新了，不需要轮询、
 * 不需要 shared_secret、也不会有「网站和插件对不上」的问题。
 *
 * 全部状态在内存里，重启即清空 —— 上传码本来就只有几分钟寿命，
 * 这个取舍是刻意的（不落盘就没有残留文件要清理）。
 */
public final class Sessions {

    /** 去掉 I / O / 0 / 1，避免玩家抄错。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-HJ-NP-Z2-9]{8}$");

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 一次浏览器会话。 */
    public static final class Session {
        public final String id;
        public final long createdAt;
        public volatile long lastSeen;
        /** 当前这次会话正在等待确认的码（没有则为空）。 */
        public volatile String pendingCode = "";
        /** 确认后的玩家名；还没确认为 null。 */
        public volatile String player;
        public volatile long verifiedAt;

        Session(String id) {
            this.id = id;
            long now = System.currentTimeMillis();
            this.createdAt = now;
            this.lastSeen = now;
        }

        public boolean verified() {
            return player != null && !player.isEmpty();
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 等待玩家确认的码：code -> 会话 id。 */
    private final Map<String, String> codes = new ConcurrentHashMap<>();

    private final long sessionTtlMillis;
    private final long codeTtlMillis;

    public Sessions(long sessionTtlMillis, long codeTtlMillis) {
        this.sessionTtlMillis = sessionTtlMillis;
        this.codeTtlMillis = codeTtlMillis;
    }

    // ------------------------------------------------------------ 会话

    public Session create() {
        prune();
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        Session session = new Session(hex(raw));
        sessions.put(session.id, session);
        return session;
    }

    public Session get(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        Session session = sessions.get(id);
        if (session == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - session.lastSeen > sessionTtlMillis) {
            sessions.remove(id);
            return null;
        }
        session.lastSeen = now;
        return session;
    }

    /** 登出 / 重新开始。 */
    public void drop(String id) {
        if (id == null) {
            return;
        }
        Session session = sessions.remove(id);
        if (session != null && session.pendingCode != null) {
            codes.remove(session.pendingCode);
        }
    }

    // ---------------------------------------------------------- 上传码

    /** 为某个会话生成一个新码，并让旧码立刻失效。 */
    public String newCode(Session session) {
        prune();
        String previous = session.pendingCode;
        if (previous != null && !previous.isEmpty()) {
            codes.remove(previous);
        }
        String code;
        do {
            code = randomCode();
        } while (codes.putIfAbsent(code, session.id) != null);
        session.pendingCode = code;
        return code;
    }

    public boolean isValidCode(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    /** 归一化玩家输入的码：去空格/连字符、转大写。 */
    public static String normalizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT)
                .replace("-", "").replace(" ", "").replace("_", "");
    }

    /**
     * 玩家在游戏里确认一个码。
     *
     * @return 结果，调用方据此给玩家回话
     */
    public ConfirmResult confirm(String rawCode, String playerName) {
        String code = normalizeCode(rawCode);
        if (!CODE_PATTERN.matcher(code).matches()) {
            return ConfirmResult.BAD_FORMAT;
        }
        prune();
        String sessionId = codes.remove(code);
        if (sessionId == null) {
            return ConfirmResult.UNKNOWN;
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            // 会话已经过期，码也就没意义了
            return ConfirmResult.UNKNOWN;
        }
        session.player = playerName;
        session.verifiedAt = System.currentTimeMillis();
        session.pendingCode = "";
        return ConfirmResult.OK;
    }

    public enum ConfirmResult {
        /** 确认成功。 */
        OK,
        /** 码格式不对。 */
        BAD_FORMAT,
        /** 码不存在、已过期，或对应的浏览器会话已经关掉。 */
        UNKNOWN
    }

    /** 这个码是否还在等确认（给 /imgup status 之类用）。 */
    public boolean isPending(String code) {
        return codes.containsKey(normalizeCode(code));
    }

    public int pendingCodeCount() {
        prune();
        return codes.size();
    }

    public int sessionCount() {
        prune();
        return sessions.size();
    }

    // ------------------------------------------------------------ 清理

    private void prune() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Session session = it.next().getValue();
            if (now - session.lastSeen > sessionTtlMillis) {
                it.remove();
                if (session.pendingCode != null && !session.pendingCode.isEmpty()) {
                    codes.remove(session.pendingCode);
                }
            }
        }
        // 码自己也有寿命，避免会话还活着但码早就该失效的情况
        codes.entrySet().removeIf(entry -> {
            Session session = sessions.get(entry.getValue());
            if (session == null) {
                return true;
            }
            return now - session.createdAt > Math.max(codeTtlMillis, 60_000L)
                    && !entry.getKey().equals(session.pendingCode);
        });
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String hex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0x0F, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString();
    }
}
