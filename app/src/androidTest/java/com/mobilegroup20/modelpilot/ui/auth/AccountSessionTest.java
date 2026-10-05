package com.mobilegroup20.modelpilot.ui.auth;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mobilegroup20.modelpilot.data.AccountSession;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AccountSessionTest {
    /** 账号 token 的前缀，和 `AccountSession.TOKEN_PREFIX` 一致。 */
    private static final String TOKEN = "tt_app_";
    @Test public void testSessionRestoresItsSeparateEndpointAndNormalLoginLeavesTestMode() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AccountSession session = AccountSession.get(context); session.clear();
        AccountSession original = session;
        try {
            session.saveForumTest("tt_test_temporary-fixture", "test_fixture", "Tester Fixture");
            assertTrue(session.forumTest()); assertTrue(session.forumBaseUrl().endsWith("/test-api/"));
            Field singleton = AccountSession.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, null);
            session = AccountSession.get(context);
            assertTrue(session.forumTest()); assertTrue(session.forumBaseUrl().endsWith("/test-api/"));
            session.save(TOKEN + "-normal", "u_" + "42".repeat(16), "Alice");
            assertFalse(session.forumTest());
            assertEquals(com.mobilegroup20.modelpilot.BuildConfig.FORUM_BASE_URL, session.forumBaseUrl());
        } finally {
            original.clear(); session.clear();
            com.mobilegroup20.modelpilot.data.RepositoryProvider.configureForum(com.mobilegroup20.modelpilot.BuildConfig.FORUM_BASE_URL, session);
        }
    }
    @Test public void emailAndVerificationRideAlongWithTheSession() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AccountSession session = AccountSession.get(context); session.clear();
        AccountSession original = session;
        try {
            // 邮箱和验证状态是 2026-02 加邮箱注册时补进快照的：界面要靠它们拼
            // 「验证码已发往 <邮箱>」，所以存下去、重新解出来都必须在。
            session.save(TOKEN + "-with-email", "u_" + "9".repeat(16), "Alice", "alice@example.com", true);
            assertEquals("alice@example.com", session.accountEmail());
            assertTrue(session.emailVerified());
            Field singleton = AccountSession.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, null);
            session = AccountSession.get(context);
            assertEquals("alice@example.com", session.accountEmail());
            assertTrue(session.emailVerified());
            // 老调用（3 参、没有邮箱）：**不能崩**，读出来是空邮箱。
            // 空邮箱就是「不知道」，不能拿 false 去假装「没验证过」。
            session.save(TOKEN + "-no-email", "u_" + "8".repeat(16), "Bob");
            assertEquals("", session.accountEmail());
            assertFalse(session.emailVerified());
            assertEquals("Bob", session.accountName());
        } finally {
            original.clear(); session.clear();
            com.mobilegroup20.modelpilot.data.RepositoryProvider.configureForum(com.mobilegroup20.modelpilot.BuildConfig.FORUM_BASE_URL, session);
        }
    }
    /**
     * 从旧版 App 升级上来的机器：磁盘上那份加密快照里**没有** `email` / `emailVerified`
     * 两个键（它们是加邮箱注册时才有的）。
     *
     * <p>这里用同一把 Keystore 钥匙手写一份那种快照。如果读取代码用了
     * `getString`/`getBoolean`，缺键会抛 JSONException，被构造函数那个 catch 吃掉，
     * 结果是把整份会话丢掉 = **所有老用户升级后都被静默登出**。
     * 用 `optString`/`optBoolean` 才不会，这个用例就是那句话的证明。
     */
    @Test public void aLegacySnapshotWithoutEmailFieldsStillRestores() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AccountSession session = AccountSession.get(context); session.clear();
        AccountSession original = session;
        try {
            java.lang.reflect.Method key = AccountSession.class.getDeclaredMethod("key");
            key.setAccessible(true);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, (java.security.Key) key.invoke(session));
            String legacy = new org.json.JSONObject()
                    .put("origin", com.mobilegroup20.modelpilot.BuildConfig.FORUM_BASE_URL)
                    .put("token", TOKEN + "-legacy").put("id", "u_" + "1".repeat(16))
                    .put("name", "Legacy").toString();
            String stored = android.util.Base64.encodeToString(cipher.getIV(), android.util.Base64.NO_WRAP) + ":"
                    + android.util.Base64.encodeToString(
                            cipher.doFinal(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            android.util.Base64.NO_WRAP);
            assertTrue(context.getSharedPreferences("app_account_session", Context.MODE_PRIVATE)
                    .edit().putString("encrypted", stored).commit());
            Field singleton = AccountSession.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, null);
            session = AccountSession.get(context);
            // 会话本身照样能用（这是关键：不能因为多了两个字段就把人登出），
            // 读不到的字段就是空 / false。
            assertEquals(TOKEN + "-legacy", session.token());
            assertEquals("Legacy", session.accountName());
            assertEquals("", session.accountEmail());
            assertFalse(session.emailVerified());
            assertTrue(session.signedIn());
        } finally {
            original.clear(); session.clear();
            com.mobilegroup20.modelpilot.data.RepositoryProvider.configureForum(com.mobilegroup20.modelpilot.BuildConfig.FORUM_BASE_URL, session);
        }
    }
    @Test public void sessionIsEncryptedRestorableAndClearedOnSignOut() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AccountSession session = AccountSession.get(context); session.clear();
        AccountSession original = session;
        try {
            session.save(TOKEN + "-never-plaintext", "u_" + "7".repeat(16), "Test Account");
            String stored = context.getSharedPreferences("app_account_session", Context.MODE_PRIVATE).getString("encrypted", "");
            assertFalse(stored.contains(TOKEN)); assertFalse(stored.contains("7".repeat(16)));
            Field singleton = AccountSession.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, null);
            session = AccountSession.get(context);
            assertEquals(TOKEN + "-never-plaintext", session.token());
            assertEquals("u_" + "7".repeat(16), session.accountId());
            assertEquals("Test Account", session.accountName());
            session.clear(); assertNull(session.token()); assertNull(session.accountId());
            assertFalse(context.getSharedPreferences("app_account_session", Context.MODE_PRIVATE).contains("encrypted"));
        } finally {
            original.clear(); session.clear();
            com.mobilegroup20.modelpilot.data.RepositoryProvider.configureForum(com.mobilegroup20.modelpilot.BuildConfig.FORUM_BASE_URL, session);
        }
    }
}
