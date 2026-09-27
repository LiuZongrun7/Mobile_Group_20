package com.mobilegroup20.tokentrail.ui.auth;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mobilegroup20.tokentrail.data.AccountSession;
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
            assertEquals(com.mobilegroup20.tokentrail.BuildConfig.FORUM_BASE_URL, session.forumBaseUrl());
        } finally {
            original.clear(); session.clear();
            com.mobilegroup20.tokentrail.data.RepositoryProvider.configureForum(com.mobilegroup20.tokentrail.BuildConfig.FORUM_BASE_URL, session);
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
            com.mobilegroup20.tokentrail.data.RepositoryProvider.configureForum(com.mobilegroup20.tokentrail.BuildConfig.FORUM_BASE_URL, session);
        }
    }
}
