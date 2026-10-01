package com.mobilegroup20.modelpilot.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.mobilegroup20.modelpilot.BuildConfig;
import org.json.JSONObject;

/**
 * 中转凭据的本机存储。<b>负责人：汪庭栋。</b>
 *
 * <p><b>存哪两样东西：</b>
 * <ul>
 *   <li><b>relay key</b> —— 我们发的身份凭据。它就是 `UsageCall.uid` 的来源，
 *       也是调所有接口的凭据。**必须留一份在手机上**，否则 App 读不到自己的用量。</li>
 *   <li><b>上游 URL</b> —— 只是显示用（设置页回显）。上游密钥**不存手机**：
 *       它注册时上行一次就留在服务端了。</li>
 * </ul>
 *
 * <p><b>明文存，不加密——这是有意的取舍，不是漏做。</b>
 * 2026-09-27 改成明文，跟 `docs/DATA_SOURCES.md` §3 那条已经记录在案的取舍保持一致：
 * 「课程项目把力气花在体验上……不该为了防一个本地攻击面多引一个库、多一层失败可能」。
 *
 * <p>改之前用的是 Android Keystore（和 {@link TeamAccountSession} 同一套）。
 * 当时多写那一层的理由是「relay key 是**我们发的登录凭据**，泄露等于别人能读你的
 * 用量、还能借你的上游 key 转发」。那个风险判断**没有变**，变的是结论：
 * 它和同一台设备上那份 provider 凭据的攻击面完全一样，而 Keystore 带来一个实际代价
 * ——**换设备或恢复备份后解不开，用户得重新填一次**。两处凭据用两种存法，
 * 也让人以为其中一个更值钱，其实不是。
 *
 * <p><b>代价写清楚</b>：任何能读这个 App 私有目录的东西（root、adb backup、
 * 取证工具）就能拿到 relay key，然后用它读用量、并借用户的上游 key 发请求。
 * 开发阶段没有真实用户数据，所以现在换格式不需要迁移。
 */
public final class RelayCredentials {
    private static final String FILE = "relay_credentials";
    private static RelayCredentials instance;
    private final SharedPreferences preferences;
    private volatile Snapshot current;

    private static final class Snapshot {
        final String relayKey, upstreamUrl;
        Snapshot(String relayKey, String upstreamUrl) {
            this.relayKey = relayKey; this.upstreamUrl = upstreamUrl;
        }
    }

    public static synchronized RelayCredentials get(Context context) {
        if (instance == null) instance = new RelayCredentials(context.getApplicationContext());
        return instance;
    }

    private RelayCredentials(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String stored = preferences.getString("value", null);
        if (stored == null) return;
        try {
            JSONObject value = new JSONObject(stored);
            // 换了服务器地址就当这份凭据无效：它属于旧的那台机器，
            // 拿它去新机器只会 401。
            if (!BuildConfig.FORUM_BASE_URL.equals(value.getString("origin"))) {
                throw new IllegalStateException("Relay endpoint changed");
            }
            String relayKey = value.getString("relayKey");
            // 前缀校验不是形式：relay key（tt_）和账号 token（tt_app_）必须是
            // 一眼能分开的东西，
            // 存错了会让请求带着错的凭据、却看起来像「服务器坏了」。
            if (!relayKey.startsWith("tt_")) throw new IllegalStateException("Not a relay key");
            current = new Snapshot(relayKey, value.optString("upstreamUrl", ""));
        } catch (Exception ignored) {
            // 数据坏了、或者是从「加密那版」升上来的旧记录（键名是 `encrypted`，
            // 解不出来）。**当作没注册过**，让用户重新填一次——
            // 开发阶段没有真实用户，这个代价是零。
            preferences.edit().clear().apply();
        }
    }

    /** 注册成功后存下来。上游密钥不进这个方法——它不该到手机上。 */
    public synchronized void save(String relayKey, String upstreamUrl) {
        if (relayKey == null || !relayKey.startsWith("tt_"))
            throw new IllegalArgumentException("Invalid relay key");
        String stored;
        try {
            stored = new JSONObject().put("origin", BuildConfig.FORUM_BASE_URL)
                    .put("relayKey", relayKey)
                    .put("upstreamUrl", upstreamUrl == null ? "" : upstreamUrl).toString();
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("Could not encode relay credentials", impossible);
        }
        if (!preferences.edit().putString("value", stored).commit())
            throw new IllegalStateException("Could not save relay credentials");
        current = new Snapshot(relayKey, upstreamUrl == null ? "" : upstreamUrl);
    }

    public synchronized void clear() {
        current = null; preferences.edit().clear().apply();
    }

    /** relay key。没注册过返回 null。 */
    public String relayKey() { Snapshot value = current; return value == null ? null : value.relayKey; }

    /**
     * uid —— relay key 的 sha256 十六进制，**和服务端的口径必须一致**。
     *
     * <p>这是 `UsageCall.uid` 和 `agent_usage.user_id` 的来源。
     */
    public String uid() {
        String value = relayKey();
        return value == null ? null : digestOf(value);
    }

    /** relay key → uid。和 `relay_store.key_hash` 同口径：sha256 的十六进制小写。 */
    public static String digestOf(String relayKey) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(relayKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) text.append(String.format("%02x", value));
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境坏了，不是可以继续的情况。
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 设置页回显用。 */
    public String upstreamUrl() { Snapshot value = current; return value == null ? "" : value.upstreamUrl; }

    public boolean configured() { return current != null; }

    /** 转发接口和用量接口的 Authorization 头。没注册过返回 null，调用方据此提示。 */
    public String authorization() {
        String value = relayKey();
        return value == null ? null : "Bearer " + value;
    }
}
