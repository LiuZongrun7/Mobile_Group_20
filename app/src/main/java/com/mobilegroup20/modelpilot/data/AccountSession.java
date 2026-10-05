package com.mobilegroup20.modelpilot.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.data.repository.SessionProvider;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * APP 账号的会话：`userId` + 会话 token。<b>负责人：刘宗润。</b>
 *
 * <p><b>它是全 App 的身份来源。</b>账号是身份，其它一切都是它下面的通道——
 * 用量、预算、智能体记账全都挂在 `userId` 上。所以这个类叫 `AccountSession`：
 * 账号不依赖任何一条用量通道，换通道不该换一个人。
 *
 * <p>2026-02 加邮箱注册之后，快照里多了 `email`/`emailVerified`（界面要显示「验证码发往
 * 哪个邮箱」）。但**身份仍然是 `userId`**：邮箱是可以换的，换邮箱不该换一个人。
 * 这两个字段只是顺路存下来的账号属性，缺了（老快照）也不影响登录状态。
 *
 * <p><b>它同时管「论坛测试身份」那一支。</b>测试身份走的是后端的
 * `/test-api/forum/test-session`（仅 Debug），拿到的是 `tt_test_` 前缀的 token，
 * 和真账号的 `tt_app_` 前缀一眼能分开。**两支共用一个存储、一个
 * `SessionProvider`**，否则「当前是谁」会有两个来源，而它们必然对不上。
 * 服务端也从来不混用：测试 token 只在 `/test-api` 那个挂载点里认。
 *
 * <p><b>用 Keystore 加密，不是明文存。</b>这里是**密码换来的会话**：
 * token 泄露等于别人能以你的身份发帖、读你的用量，而且用户为它敲过一个密码。
 * Keystore 的代价（换设备/恢复备份后解不开、得重新登录一次）在这里是可以接受的
 * ——重新登录本来就有密码。
 */
public final class AccountSession implements SessionProvider {
    private static final String FILE = "app_account_session";
    /**
     * Keystore 里的别名。**故意还留着旧包名 `tokentrail`**：改名的同时改它，
     * 已经装过 App 的机器上那份被加密的会话就解不开了（别名一换等于换了一把钥匙）。
     * 别名只是个字符串，和新包名不一致不影响任何东西。
     */
    private static final String KEY_ALIAS = "tokentrail-app-account";
    /** 服务端账号 token 的前缀，和 `accounts.TOKEN_PREFIX` 必须一致。 */
    public static final String TOKEN_PREFIX = "tt_app_";
    /** 论坛测试身份的 token 前缀，和 `test_sessions` 那边一致。 */
    private static final String TEST_PREFIX = "tt_test_";
    private static AccountSession instance;
    private final SharedPreferences preferences;
    private volatile Snapshot current;

    private static final class Snapshot {
        final String token, id, name;
        /**
         * 注册用的邮箱。测试身份没有邮箱，老版本存下来的快照里也没有这个字段，
         * 两种情况都读成空串——**不能因为它缺失就让整份会话解不开**（那等于把
         * 用户莫名其妙地登出）。
         */
        final String email;
        /** 邮箱是否已验证。老快照读成 false；服务端才是权威，它会拒掉未验证的登录。 */
        final boolean emailVerified;
        /** 是不是 Debug 的论坛测试身份。两支共用一个存储，靠这个字段分流。 */
        final boolean forumTest;
        Snapshot(String token, String id, String name, String email, boolean emailVerified,
                 boolean forumTest) {
            this.token = token; this.id = id; this.name = name;
            this.email = email; this.emailVerified = emailVerified; this.forumTest = forumTest;
        }
    }

    public static synchronized AccountSession get(Context context) {
        if (instance == null) instance = new AccountSession(context.getApplicationContext());
        return instance;
    }

    private AccountSession(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String stored = preferences.getString("encrypted", null);
        if (stored == null) return;
        try {
            String[] parts = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            JSONObject value = new JSONObject(new String(
                    cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
            // 换了服务器地址就当这份会话无效：它属于旧的那台机器，拿去新机器只会 401。
            if (!BuildConfig.FORUM_BASE_URL.equals(value.getString("origin")))
                throw new IllegalStateException("Account endpoint changed");
            String token = value.getString("token"), id = value.getString("id");
            String name = value.optString("name", "");
            // 这两个字段是加邮箱注册时补上的：**老快照（2026-02 之前存的那份）
            // 里没有它们**，optXxx 缺省值让那种快照照样能用，只是没有邮箱信息。
            // 用 getString/getBoolean 会抛 JSONException，被下面那个 catch 吃掉，
            // 于是升级 App 的所有老用户会被静默登出——这正是要避免的。
            String email = value.optString("email", "");
            boolean emailVerified = value.optBoolean("emailVerified", false);
            boolean forumTest = value.optBoolean("forumTest", false);
            // 测试身份只在 Debug 里认，而且必须带 `tt_test_` 前缀——
            // 一份 Release 包里混进测试身份就等于绕过了登录。
            if (forumTest && (!BuildConfig.DEBUG || !token.startsWith(TEST_PREFIX)))
                throw new IllegalStateException("Test sessions are debug only");
            if (!forumTest && !token.startsWith(TOKEN_PREFIX))
                throw new IllegalStateException("Not an app account session");
            if (id.isEmpty()) throw new IllegalStateException("Empty account id");
            current = new Snapshot(token, id, name, email, emailVerified, forumTest);
        } catch (Exception ignored) {
            // 解不开（换设备、恢复备份、或者数据坏了）就当作没登录过。
            // 用户重新登一次即可——他有密码。
            preferences.edit().remove("encrypted").apply();
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    /**
     * 登录成功后存下来。传进来的必须是完整的服务端响应，缺字段直接拒绝。
     *
     * <p>`email`/`emailVerified` 一起存：界面要显示「验证码发往哪个邮箱」，而且下次启动时
     * 不用再问服务端就能知道这个账号验证到哪一步了。传 null 邮箱会被写成空串（不知道），
     * **不要在这里编一个假的**——不知道就是不知道。
     */
    public synchronized void save(String token, String userId, String username,
                                  String email, boolean emailVerified) throws Exception {
        if (token == null || !token.startsWith(TOKEN_PREFIX))
            throw new IllegalArgumentException("Invalid account token");
        if (userId == null || userId.isEmpty())
            throw new IllegalArgumentException("Account response is incomplete");
        String storedEmail = email == null ? "" : email;
        JSONObject value = new JSONObject().put("origin", BuildConfig.FORUM_BASE_URL)
                .put("token", token).put("id", userId)
                .put("name", username == null ? "" : username)
                .put("email", storedEmail).put("emailVerified", emailVerified);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!preferences.edit().putString("encrypted", stored).commit())
            throw new IllegalStateException("Could not save account session");
        current = new Snapshot(token, userId, username == null ? "" : username,
                storedEmail, emailVerified, false);
    }

    /**
     * 不带邮箱的重载：把邮箱当成「不知道」。
     *
     * <p>留给仪器测试（`AccountSessionTest` 用的就是这个签名）和只要 token 的调用方。
     * 存下来的就是空邮箱 + `emailVerified=false`，所以读取方**要靠空邮箱**判断
     * 「这是不知道」：光看 `emailVerified` 分不出「没验证」和「老快照没有这个字段」。
     */
    public synchronized void save(String token, String userId, String username) throws Exception {
        save(token, userId, username, null, false);
    }

    /**
     * 存一个论坛测试身份（Debug only）。
     *
     * <p>它的 `userId` 是 `test_` 开头的一串，**和真账号不在一个空间**，
     * 所以它只能看论坛，读不到任何人的用量——服务端那边也是这么拦的。
     */
    public synchronized void saveForumTest(String token, String userId, String displayName) throws Exception {
        if (!BuildConfig.DEBUG || token == null || !token.startsWith(TEST_PREFIX)
                || userId == null || !userId.startsWith("test_"))
            throw new IllegalArgumentException("Invalid test session");
        JSONObject value = new JSONObject().put("origin", BuildConfig.FORUM_BASE_URL)
                .put("token", token).put("id", userId).put("forumTest", true)
                .put("name", displayName == null ? "" : displayName);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!preferences.edit().putString("encrypted", stored).commit())
            throw new IllegalStateException("Could not save test session");
        current = new Snapshot(token, userId, displayName == null ? "" : displayName,
                "", false, true);
    }

    public synchronized void clear() {
        current = null; preferences.edit().remove("encrypted").apply();
    }

    @Override public String token() { Snapshot value = current; return value == null ? null : value.token; }

    /** 账号的 `userId`。**用量、预算和智能体都按它记账。** */
    @Override public String accountId() { Snapshot value = current; return value == null ? null : value.id; }

    public String accountName() { Snapshot value = current; return value == null ? null : value.name; }

    /**
     * 账号邮箱。没登录、或者是一份没有这个字段的老快照，都返回空串。
     *
     * <p>返回空串而不是 null：调用方基本都要拿它拼「验证码已发往 %s」，null 会在
     * 拼接时变成字面量 "null" 显示给用户。
     */
    public String accountEmail() { Snapshot value = current; return value == null ? "" : value.email; }

    /** 邮箱是否已验证。没登录或老快照都是 false（服务端才是权威）。 */
    public boolean emailVerified() { Snapshot value = current; return value != null && value.emailVerified; }

    public boolean signedIn() { return current != null; }

    /** 当前是不是 Debug 的论坛测试身份。 */
    public boolean forumTest() { Snapshot value = current; return value != null && value.forumTest; }

    /** 论坛接口的基地址：测试身份走 `/test-api/` 那个挂载点，真账号走正常前缀。 */
    public String forumBaseUrl() {
        return forumTest() ? testBaseUrl() : BuildConfig.FORUM_BASE_URL;
    }

    /** 测试挂载点的地址。Debug 之外没人该调它。 */
    public static String testBaseUrl() {
        return okhttp3.HttpUrl.get(BuildConfig.FORUM_BASE_URL).newBuilder()
                .encodedPath("/test-api/").build().toString();
    }

    /** `Authorization` 头。没登录返回 null，调用方据此提示。 */
    public String authorization() {
        String value = token();
        return value == null ? null : "Bearer " + value;
    }
}
