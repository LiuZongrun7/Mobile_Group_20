package com.mobilegroup20.modelpilot.ui.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.mobilegroup20.modelpilot.R;
import org.junit.Test;

/**
 * 中转注册的**本地校验**测试。<b>负责人：汪庭栋。</b>
 *
 * <p>这一层和服务端那套是两套独立规则（见 {@link RelaySetupViewModel} 的类注释），
 * 所以两边都要测。这里测本地这层，主要是防「本地放过了、服务端才拒」和反过来的错配。
 */
public class RelaySetupViewModelTest {

    @Test public void relayKeyMayBeLeftEmptyForTheServerToGenerate() {
        // 留空是有意义的输入，不是错误——服务端会生成一个。
        assertEquals(0, RelaySetupViewModel.validateRelayKey(""));
        assertEquals(0, RelaySetupViewModel.validateRelayKey(null));
    }

    @Test public void relayKeyMustCarryThePrefixThatSeparatesIdentities() {
        // tt_ 前缀是 relay 身份和团队账号 token 的分界，缺了必须挡住。
        assertEquals(R.string.relay_key_prefix, RelaySetupViewModel.validateRelayKey("sk-looks-like-a-provider-key-1234"));
        assertEquals(R.string.relay_key_prefix, RelaySetupViewModel.validateRelayKey("relay_key_without_prefix_1234"));
    }

    @Test public void shortCustomKeysAreRejectedBecauseTheyAreLoginCredentials() {
        // 23 个字符（差一个）要被拒，24 个通过——和服务端 relay_store.MIN_CUSTOM_LENGTH 对齐。
        assertEquals(R.string.relay_key_short, RelaySetupViewModel.validateRelayKey("tt_" + "a".repeat(20)));
        assertEquals(0, RelaySetupViewModel.validateRelayKey("tt_" + "a".repeat(21)));
    }

    @Test public void relayKeyMustNotContainWhitespace() {
        // 两个用例都要**足够长**，否则会先被长度规则挡住，测不到空格这条
        // （踩过：`tt_tab\there_0123456789` 只有 23 字符，断言拿到的是「太短」）。
        assertEquals(R.string.relay_key_space, RelaySetupViewModel.validateRelayKey("tt_has a space in it 0123456789"));
        assertEquals(R.string.relay_key_space, RelaySetupViewModel.validateRelayKey("tt_tab\there_0123456789012"));
        assertEquals(R.string.relay_key_space, RelaySetupViewModel.validateRelayKey("tt_line\nbreak_0123456789012"));
    }

    @Test public void upstreamUrlRequiresHttpsExceptOnLoopback() {
        assertEquals(R.string.relay_url_empty, RelaySetupViewModel.validateUpstreamUrl(""));
        assertEquals(R.string.relay_url_empty, RelaySetupViewModel.validateUpstreamUrl("   "));
        assertEquals(R.string.relay_url_empty, RelaySetupViewModel.validateUpstreamUrl(null));
        // 明文 http 只对本机放行：本地假上游没有证书，这不是「允许不安全」。
        assertEquals(R.string.relay_url_https, RelaySetupViewModel.validateUpstreamUrl("http://api.deepseek.com"));
        assertEquals(R.string.relay_url_https, RelaySetupViewModel.validateUpstreamUrl("api.deepseek.com"));
        assertEquals(0, RelaySetupViewModel.validateUpstreamUrl("https://api.deepseek.com"));
        assertEquals(0, RelaySetupViewModel.validateUpstreamUrl("https://open.bigmodel.cn/api/coding/paas/v4"));
        assertEquals(0, RelaySetupViewModel.validateUpstreamUrl("http://127.0.0.1:9099"));
        assertEquals(0, RelaySetupViewModel.validateUpstreamUrl("http://localhost:9099"));
    }

    @Test public void localRulesAndServerRulesAgreeOnTheBoundary() {
        // 这条挡的是最容易出的错：两边长度下限写的不一样，
        // 于是「本地通过、服务端 400」或者反过来，用户看到的是一个说不清的失败。
        String atBoundary = "tt_" + "x".repeat(21);          // 24 字符
        String belowBoundary = "tt_" + "x".repeat(20);       // 23 字符
        assertEquals(0, RelaySetupViewModel.validateRelayKey(atBoundary));
        assertNotEquals(0, RelaySetupViewModel.validateRelayKey(belowBoundary));
    }
}
