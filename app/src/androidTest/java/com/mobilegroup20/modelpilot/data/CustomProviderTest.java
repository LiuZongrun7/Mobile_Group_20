package com.mobilegroup20.modelpilot.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.mobilegroup20.modelpilot.chat.CallLedger;
import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.chat.UsageRecorder;
import com.mobilegroup20.modelpilot.contract.model.Provider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

/**
 * 自定义端点在真机上的往返：**存的能不能读回来、并进注册表没有、账本认不认它**。
 *
 * <p>为什么是仪器测试而不是界面点按：这条链路的每一段都依赖 Android
 * （Keystore 加密、SharedPreferences、Room 类型转换），在 JVM 单测里都是"没实现"的。
 * 而界面那几屏（选择框、表单）已经用截图确认过渲染，真正容易错的是数据这一段。
 *
 * <p>它同时钉住三件之前踩过的事：
 * <ol>
 *   <li>自定义端点的 key 与定义**一起**存取（`ProviderKeys.saveCustom`）；</li>
 *   <li>并进注册表之后，它的上下文上限**要参与木桶效应**——只在发送那一处特殊处理的话，
 *       会出现"能选它，但压缩阈值没把它算进去"，那正是切过去就超长的成因；</li>
 *   <li>账本里记成 `CUSTOM`（`Provider` 枚举认不出 `custom-*` 的 id，不映射就整行不记）。</li>
 * </ol>
 */
@RunWith(AndroidJUnit4.class)
public class CustomProviderTest {

    private static final String ID = "custom-itest01";

    private final Context context = ApplicationProvider.getApplicationContext();

    @After
    public void cleanUp() {
        ProviderKeys.clear(context, ID);
        RepositoryProvider.reloadProviders();
    }

    @Test
    public void a_custom_endpoint_round_trips_and_takes_part_in_the_barrel_effect() throws Exception {
        ProviderKeys.saveCustom(context, ID, "Mock upstream", "http://127.0.0.1:8080/v1", "dummy",
                ProviderSpec.Adapter.OPENAI_COMPATIBLE, Arrays.asList("deepseek-chat", "extra-model"),
                4096, true);

        assertTrue("配了 key 就该出现在 configured 里",
                ProviderKeys.configuredProviders(context).contains(ID));
        assertEquals("dummy", ProviderKeys.apiKey(context, ID));
        assertEquals("http://127.0.0.1:8080/v1", ProviderKeys.baseUrl(context, ID));

        RepositoryProvider.reloadProviders();
        ProviderSpec spec = RepositoryProvider.providers().provider(ID);
        assertNotNull("自定义端点要并进同一个注册表", spec);
        assertEquals("Mock upstream", spec.displayName);
        assertEquals(2, spec.models.size());
        assertEquals(ProviderSpec.Adapter.OPENAI_COMPATIBLE, spec.adapter);
        assertTrue("stream_options 开关要存下来（认这个字段的家才有 usage 可记账）", spec.streamUsage);

        // **能力按文本处理**：没验证过的端点不能声称自己能看图，否则 Auto 会在图片任务上
        // 挑一个根本发不出去的家。
        ModelSpec model = RepositoryProvider.providers().model(ID, "deepseek-chat");
        assertTrue(model.text);
        assertTrue("不能声称支持图片", !model.vision);

        // 木桶效应：4096 是当前最小的一块板，压缩阈值必须跟着它走。
        assertEquals(4096, RepositoryProvider.providers()
                .smallestContextLimit(Collections.singletonList(ID)));

        // 账本：`Provider` 枚举里没有 custom-xxx，不映射就整行不记（GLM/Kimi 那几家踩过）。
        assertEquals(Provider.CUSTOM, UsageRecorder.providerOf(ID));
        assertNull("内置的六家不受影响", UsageRecorder.providerOf("DEEPSEEK") == Provider.CUSTOM
                ? Provider.CUSTOM : null);
    }

    @Test
    public void an_endpoint_without_a_context_limit_is_rejected() {
        boolean rejected = false;
        try {
            ProviderKeys.saveCustom(context, ID, "No limit", "http://127.0.0.1:8080/v1", "dummy",
                    ProviderSpec.Adapter.OPENAI_COMPATIBLE, Arrays.asList("m"), 0, false);
        } catch (Exception expected) {
            rejected = true;
        }
        // 上限为 0 会让压缩阈值算成 0 = 从不压缩，而表现是"聊到一半上游报超长"——
        // 完全看不出跟设置有关，所以在入口就拒掉。
        assertTrue("上下文上限必填，0 不许存进去", rejected);
        assertNull(ProviderKeys.apiKey(context, ID));
    }

    @Test
    public void clearing_the_key_removes_the_endpoint_entirely() throws Exception {
        ProviderKeys.saveCustom(context, ID, "Mock upstream", "http://127.0.0.1:8080/v1", "dummy",
                ProviderSpec.Adapter.ANTHROPIC, Arrays.asList("claude-x"), 200_000, false);
        RepositoryProvider.reloadProviders();
        ProviderSpec saved = RepositoryProvider.providers().provider(ID);
        assertNotNull(saved);
        assertEquals("格式要在存的时候保住（选 Anthropic 就得是 Anthropic）",
                ProviderSpec.Adapter.ANTHROPIC, saved.adapter);

        ProviderKeys.clear(context, ID);
        RepositoryProvider.reloadProviders();
        assertNull("删 key 就等于删这家，不留配不上的幽灵提供商",
                RepositoryProvider.providers().provider(ID));
        assertTrue("也不该再出现在 configured 里",
                !ProviderKeys.configuredProviders(context).contains(ID));
        assertTrue("前缀是账本认出它的依据", CallLedger.isCustom(ID));
    }
}
