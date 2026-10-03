package com.mobilegroup20.modelpilot.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import java.util.Arrays;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * {@link ProviderKeys} 的测试。**它只能是 instrumented 的，JVM 单测做不到。**
 *
 * <p><b>为什么</b>：这段代码的全部内容都建立在 AndroidKeyStore 上——
 * {@code KeyStore.getInstance("AndroidKeyStore")} 是**系统里的一个 KeyStore 实现**，
 * JVM 上根本没有这个 provider。而 unit test 里那个 android.jar 是**桩**（stub），
 * `android.security.keystore` 下的类一调就抛 "Stub!"/"not mocked"。
 * 换句话说，在 JVM 上测它等于测了一个假对象：真机上"key 能不能生成、密文长什么样、
 * 换个进程还能不能读回来"这些**恰恰是这段代码存在的理由**，一条都验不到。
 *
 * <p>所以这几条用例覆盖的就是那几件事：
 * ① 落盘的东西里**没有明文 key**（这是整段加密的意义）；
 * ② 读回来和存进去的是同一份（重启进程/重新构造也一样）；
 * ③ 删一家不动另一家（一家一条的真正含义）；
 * ④ 没配过 / 清掉之后，`configuredProviders` 里就没有它。
 *
 * <p><b>本机没有设备，所以这份测试没有跑过</b>，只做了编译验证
 * （`:app:compileDebugAndroidTestJavaWithJavac`）。有设备时用
 * `./gradlew connectedDebugAndroidTest` 跑。这也是这一块**唯一的**验证途径——
 * 编译能过不代表 Keystore 那段逻辑对，这一点必须写清楚，不能假装验过了。
 */
@RunWith(AndroidJUnit4.class)
public class ProviderKeysTest {

    private static final String FILE = "app_provider_keys";
    private static final String DEEPSEEK_KEY = "sk-deepseek-never-plaintext-0001";
    private static final String KIMI_KEY = "sk-kimi-never-plaintext-0002";
    private static final String PROXY = "https://relay.example.com/v1";

    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // 每个用例从"一台没配过 key 的手机"开始：上一轮留下的记录会让"没配过就该是空"
        // 这类断言失去意义。
        preferences().edit().clear().commit();
    }

    @After public void tearDown() {
        preferences().edit().clear().commit();
    }

    @Test public void keysAreEncryptedAtRestAndReadBackUnchanged() throws Exception {
        ProviderKeys.save(context, ProviderRegistry.DEEPSEEK, DEEPSEEK_KEY, null);

        // 落盘的整份 prefs 里不许出现明文 key（密文里也不会巧合地出现它）。
        for (Map.Entry<String, ?> entry : preferences().getAll().entrySet()) {
            String stored = String.valueOf(entry.getValue());
            assertFalse("明文 key 进了 " + entry.getKey(), stored.contains(DEEPSEEK_KEY));
            assertFalse(stored.contains("deepseek-never-plaintext"));
        }

        assertEquals(DEEPSEEK_KEY, ProviderKeys.apiKey(context, ProviderRegistry.DEEPSEEK));
        // 没改过 base URL = 用注册表里的默认值，而不是 null（发送方拿到的必须是个能用的地址）。
        assertEquals(ProviderRegistry.defaults().provider(ProviderRegistry.DEEPSEEK).baseUrl,
                ProviderKeys.baseUrl(context, ProviderRegistry.DEEPSEEK));
        assertEquals(Arrays.asList(ProviderRegistry.DEEPSEEK),
                ProviderKeys.configuredProviders(context));
    }

    @Test public void oneProviderPerEntrySoClearingOneLeavesTheOther() throws Exception {
        ProviderKeys.save(context, ProviderRegistry.DEEPSEEK, DEEPSEEK_KEY, null);
        // 自建反代：用户改过的地址要原样留下（末尾斜杠在存的时候就统一去掉）。
        ProviderKeys.save(context, ProviderRegistry.KIMI, KIMI_KEY, PROXY + "/");

        assertEquals(PROXY, ProviderKeys.baseUrl(context, ProviderRegistry.KIMI));
        assertEquals("按 providerId 排序，顺序要稳定",
                Arrays.asList(ProviderRegistry.DEEPSEEK, ProviderRegistry.KIMI),
                ProviderKeys.configuredProviders(context));

        ProviderKeys.clear(context, ProviderRegistry.DEEPSEEK);
        assertNull(ProviderKeys.apiKey(context, ProviderRegistry.DEEPSEEK));
        assertEquals(KIMI_KEY, ProviderKeys.apiKey(context, ProviderRegistry.KIMI));
        assertEquals(PROXY, ProviderKeys.baseUrl(context, ProviderRegistry.KIMI));
        assertEquals(Arrays.asList(ProviderRegistry.KIMI), ProviderKeys.configuredProviders(context));
    }

    @Test public void emptyKeysAndBadBaseUrlsAreRejectedBeforeAnythingIsStored() {
        try {
            // 空 key = 没配过。存下去会让这家出现在 configuredProviders 里、
            // 被 Auto 当成可用，然后在真正发消息时才 401。
            ProviderKeys.save(context, ProviderRegistry.GLM, "   ", null);
            fail("空 key 应该被拒绝");
        } catch (IllegalArgumentException expected) {
            // 异常消息里不回显用户填进来的东西（对空白也一视同仁，key 就更不用说了）。
            assertFalse(expected.getMessage().contains("   "));
        } catch (Exception unexpected) {
            fail("空 key 应该报 IllegalArgumentException，实际是 " + unexpected);
        }
        assertEquals(0, ProviderKeys.configuredProviders(context).size());

        try {
            // 少了 scheme 的地址如果放进去，报错会出现在聊天页而不是设置页。
            ProviderKeys.save(context, ProviderRegistry.GLM, "sk-glm-0003", "api.bigmodel.cn");
            fail("不是 http(s) 的地址应该被拒绝");
        } catch (IllegalArgumentException expected) {
            assertEquals("被拒绝的保存不该留下半条记录", 0, preferences().getAll().size());
        } catch (Exception unexpected) {
            fail("坏地址应该报 IllegalArgumentException，实际是 " + unexpected);
        }
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
