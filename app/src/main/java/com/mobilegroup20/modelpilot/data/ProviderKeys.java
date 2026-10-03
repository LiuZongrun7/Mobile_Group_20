package com.mobilegroup20.modelpilot.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.HttpUrl;
import org.json.JSONObject;

/**
 * 用户自己填的各家 API key：**一家一条，再加一个可改的 base URL**。
 *
 * <p><b>这些 key 是本 App 最敏感的东西，四条硬规矩：</b>
 *
 * <ol>
 *   <li><b>不上传。</b>服务端从头到尾没有它们的接口（`docs/CHAT_ENGINE.md` §1）——
 *       手机直连各家官方 API，我们不经手用户的任何一笔钱。</li>
 *   <li><b>不进日志、不进任何上报。</b>不要 `Log.d(key)`、不要把它塞进异常消息、
 *       不要把它当崩溃上报的附加字段。{@link Entry#toString()} 就是为这条写的：
 *       顺手 `println(entry)` 也不会漏。</li>
 *   <li><b>不落明文。</b>落盘的是 Keystore 里那把 AES key 加出来的密文，
 *       写法与 {@link AccountSession} 完全一致（同一套 AES/GCM、同一套"IV 拼在密文前"、
 *       同一个"别名不许跟着包名改"的规矩）。</li>
 *   <li><b>只有 App 读得到。</b>SharedPreferences 是 `MODE_PRIVATE`（别的 App 读不到），
 *       Keystore 的 key 不出安全硬件/系统密钥库。</li>
 * </ol>
 *
 * <p><b>为什么不是"一家一个文件"或者数据库</b>：key 是一小块"配了就有、删了就没"的
 * 设备状态，不需要查询、不需要迁移，和账号会话是同一类东西——所以照抄
 * {@code AccountSession} 的存法，而不是为它开一张 Room 表。<b>prefs 里连 providerId
 * 都是明文</b>（"你配了哪家"不是秘密，泄露它只是泄露你买了哪家），密文里只有 key 与 URL。
 *
 * <p><b>解不开就当没配过。</b>换设备、恢复备份之后 Keystore 里那把 key 不在了，
 * 密文永远解不开——这时把那条删掉、返回"没配"，让用户重填一次。留着它只会变成
 * 每次启动都报一个他自己修不了的错。这个代价和 {@code AccountSession} 那边是一样的：
 * key 在他自己的账号里，复制一次即可。
 */
public final class ProviderKeys {

    /** prefs 文件名。**不要改**：改了等于把用户已存的 key 全丢掉（它们解不回来了）。 */
    private static final String FILE = "app_provider_keys";
    /** 一家一条：`provider.<providerId>` → 密文。 */
    private static final String PREFIX = "provider.";
    /**
     * Keystore 别名。**故意还留着旧包名 `tokentrail`**，理由和
     * {@code AccountSession.KEY_ALIAS} 一模一样：别名一换等于换了一把钥匙，
     * 已经配过 key 的机器上那份密文就解不开了。它只是个字符串，和新包名不一致不影响任何事。
     *
     * <p><b>故意和账号会话用不同的别名</b>：两把钥匙互不影响——万一其中一份密文坏了，
     * 清掉它不会把另一份也带走（用同一个别名的话，"删掉重来"会同时废掉账号登录态）。
     */
    private static final String KEY_ALIAS = "tokentrail-app-provider-keys";
    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";

    /** 纯静态：这里没有任何实例状态可持有，调用方也不必记得"有没有 get 过"。 */
    private ProviderKeys() {
    }

    /**
     * 存一家的 key（以及它在设置里改过的 base URL）。
     *
     * <p>`apiKey` 是空的直接拒绝：**"配了这一家"的判据就是有一条非空 key**，
     * 存一条空 key 会让它出现在 {@link #configuredProviders} 里、被 Auto 当成可用，
     * 然后在真正发消息时才 401。
     *
     * <p>`baseUrl` 允许 null/空 = "没改过，用注册表里的默认值"（绝大多数情况就是没改）。
     * 非空时必须是 http(s) 地址——**在这里拦住**，而不是等用户在聊天页里收到一句
     * 莫名其妙的网络错误：那时候他不会想到是自己地址少打了 `https://`。
     */
    public static void save(Context context, String providerId, String apiKey, String baseUrl)
            throws Exception {
        String id = requireProviderId(providerId);
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("API key must not be empty");
        }
        String url = normalizeBaseUrl(baseUrl);
        JSONObject value = new JSONObject()
                .put("provider", id)
                .put("key", key)
                .put("baseUrl", url);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        // GCM 的 IV 必须跟着密文一起存（它不必保密，但每次加密都得换一个）。
        // 拼成 "iv:密文" 就够，别再引一个字段来存它——少一个能写错的地方。
        String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!preferences(context).edit().putString(PREFIX + id, stored).commit()) {
            // commit 返回 false = 没写进磁盘。用 commit 而不是 apply 就是因为要这个答案：
            // 用户以为存好了、下次打开却还要重填，是最让人不信任设置页的一种错。
            throw new IllegalStateException("Could not save provider key");
        }
    }

    /**
     * 读出某一家的 key；没配过、或那份密文已经解不开（换过设备），返回 null。
     *
     * <p>返回的字符串**一律不要进日志、不要进异常消息、不要进上报**（见类注释）。
     */
    public static String apiKey(Context context, String providerId) {
        Entry entry = read(context, providerId);
        return entry == null ? null : entry.apiKey;
    }

    /**
     * 这一家该往哪儿发：用户在设置里改过就用他改的，没改过就用
     * {@link ProviderRegistry} 里的默认值；providerId 不认识且没存过则返回 null。
     *
     * <p>调用方拿到非 null 的地址后，用 {@code spec.withBaseUrl(...)} 覆盖注册表里那条即可
     * （发送时的路径拼接见 {@code data/remote/ProviderClient}）。
     */
    public static String baseUrl(Context context, String providerId) {
        Entry entry = read(context, providerId);
        if (entry != null && !entry.baseUrl.isEmpty()) {
            return entry.baseUrl;
        }
        ProviderSpec spec = ProviderRegistry.defaults().provider(providerId);
        return spec == null ? null : spec.baseUrl;
    }

    /**
     * 配了 key 的 providerId（**这就是"用户启用了哪几家"的唯一判据**）。
     *
     * <p>上下文引擎的木桶阈值、Auto 的候选集合、模型选择弹层里哪些家能点，
     * 全都只该看这个列表——不要各处再判断一遍"key 在不在"。
     *
     * <p>返回顺序固定（按 providerId 排序）：同一份配置两次跑出不同顺序的话，
     * 日志对比和测试断言都会变得不可信。
     */
    public static List<String> configuredProviders(Context context) {
        // 先拷一份 keySet：下面 read() 在解不开时会顺手删掉那条记录，
        // 直接遍历 getAll() 的视图是在"边遍历边改"（虽然 SharedPreferences 返回的是快照，
        // 但这一条太依赖实现细节了，不值得赌）。
        List<String> names = new ArrayList<>(preferences(context).getAll().keySet());
        List<String> configured = new ArrayList<>();
        for (String name : names) {
            if (!name.startsWith(PREFIX)) {
                continue;
            }
            String id = name.substring(PREFIX.length());
            if (read(context, id) != null) {
                configured.add(id);
            }
        }
        Collections.sort(configured);
        return Collections.unmodifiableList(configured);
    }

    /** 删掉这一家（用户清空 key、或不再用它）。只动这一家，别家的 key 不受影响。 */
    public static void clear(Context context, String providerId) {
        preferences(context).edit().remove(PREFIX + requireProviderId(providerId)).apply();
    }

    // ---- 压缩模型 ------------------------------------------------------

    /**
     * 用户选的"压缩上下文用哪个模型"。
     *
     * <p><b>为什么和 key 存在一起</b>：它是同一个设置页上的一个选择，用户的心智是
     * "我在这儿配了能用的模型"——分成两份配置很容易出现"填了 key 却没选压缩模型"，
     * 而那个组合的表现是"压缩悄悄没发生"。
     *
     * <p>**它不是秘密**（就是一个 providerId + modelId），所以明文存：
     * 加密要过 Keystore，解不开时还得决定"当没选过"还是"报错"，为一个非敏感值不值得。
     * key 与 base URL 仍然加密（见类注释那四条）。
     */
    private static final String COMPRESSION_PROVIDER = "compression.provider";
    private static final String COMPRESSION_MODEL = "compression.model";

    /** 存压缩模型。providerId 为空 = 取消选择（回到"用 Auto 挑"）。 */
    public static void saveCompressionModel(Context context, String providerId, String modelId) {
        android.content.SharedPreferences.Editor editor = preferences(context).edit();
        if (providerId == null || providerId.isEmpty() || modelId == null || modelId.isEmpty()) {
            editor.remove(COMPRESSION_PROVIDER).remove(COMPRESSION_MODEL);
        } else {
            editor.putString(COMPRESSION_PROVIDER, providerId).putString(COMPRESSION_MODEL, modelId);
        }
        editor.apply();
    }

    /** 用户选的压缩模型；没选过（或那一家已经被删掉 key）返回 null = 用 Auto 挑。 */
    public static String[] compressionModel(Context context) {
        android.content.SharedPreferences prefs = preferences(context);
        String providerId = prefs.getString(COMPRESSION_PROVIDER, null);
        String modelId = prefs.getString(COMPRESSION_MODEL, null);
        if (providerId == null || modelId == null) {
            return null;
        }
        // **那一家已经没有 key 了就当没选过**：否则压缩会在"没有可用模型"上失败，
        // 而用户以为自己选好了（他确实选过，只是后来把 key 删了）。
        if (!configuredProviders(context).contains(providerId)) {
            return null;
        }
        return new String[] {providerId, modelId};
    }

    /** 解出来的一家：key 与 URL。**别给它加一个会打印 key 的 toString**，见类注释第 2 条。 */
    private static final class Entry {
        final String apiKey;
        final String baseUrl;

        Entry(String apiKey, String baseUrl) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
        }

        @Override public String toString() {
            return "ProviderKeys.Entry(key=" + (apiKey.isEmpty() ? "<none>" : "<hidden>")
                    + ", baseUrl=" + baseUrl + ")";
        }
    }

    /** 解不开就是 null，并把那条坏记录删掉（见类注释最后一段）。 */
    private static Entry read(Context context, String providerId) {
        String id = requireProviderId(providerId);
        SharedPreferences preferences = preferences(context);
        String stored = preferences.getString(PREFIX + id, null);
        if (stored == null) {
            return null;
        }
        try {
            String[] parts = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            JSONObject value = new JSONObject(new String(
                    cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
            // 密文里记着自己属于哪一家：一条记录被改名/挪到别家名下时，
            // 与其把 A 家的 key 发给 B 家，不如当作没配过。和 AccountSession 里那句
            // "换了服务器地址就当这份会话无效" 是同一个判断。
            if (!id.equals(value.getString("provider"))) {
                throw new IllegalStateException("Provider key entry mismatch");
            }
            String key = value.getString("key");
            if (key.isEmpty()) {
                throw new IllegalStateException("Empty provider key");
            }
            return new Entry(key, value.optString("baseUrl", ""));
        } catch (Exception ignored) {
            // 注意：这里**不能**把异常往上抛、也不能把 stored/异常内容写进日志——
            // 密文本身虽然解不出明文，但"哪一家没配成"和堆栈都可能被用户截图带走。
            preferences.edit().remove(PREFIX + id).apply();
            return null;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String requireProviderId(String providerId) {
        String id = providerId == null ? "" : providerId.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Provider id must not be empty");
        }
        return id;
    }

    /**
     * 空 = 用注册表默认值；非空必须是 http(s)，且去掉末尾的斜杠。
     *
     * <p><b>存的是"到资源路径之前"那一段</b>（`https://api.deepseek.com`），
     * 具体路径由发送方拼（`<baseUrl>/chat/completions`）。末尾的 `/` 留着会拼出 `//`，
     * 有些网关会因此 404——所以在入口就统一去掉。
     */
    private static String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty()) {
            return "";
        }
        // 只认 http(s)：别的 scheme（file:、content:）连不上，而在这里报错
        // 用户还看得见是哪个输入框写错了。scheme 大小写不敏感，判断时按小写比。
        String scheme = url.toLowerCase(Locale.ROOT);
        if (!scheme.startsWith(HTTP) && !scheme.startsWith(HTTPS)) {
            throw new IllegalArgumentException("Base URL must start with http:// or https://");
        }
        if (HttpUrl.parse(url) == null) {
            throw new IllegalArgumentException("Base URL is not a valid URL");
        }
        return url;
    }

    /** 与 {@link AccountSession} 同一套：别名的 key 不存在就现生成一把 AES-256。 */
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
