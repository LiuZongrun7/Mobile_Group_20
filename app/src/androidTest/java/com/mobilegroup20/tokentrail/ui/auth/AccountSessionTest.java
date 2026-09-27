package com.mobilegroup20.tokentrail.ui.auth;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mobilegroup20.tokentrail.data.TeamAccountSession;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AccountSessionTest {
    @Test public void sessionIsEncryptedRestorableAndClearedOnSignOut() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        TeamAccountSession session = TeamAccountSession.get(context); session.clear();
        TeamAccountSession original = session;
        try {
            session.save("test-session-token-never-plaintext", "test-user-id", "Test Account");
            String stored = context.getSharedPreferences("account_session", Context.MODE_PRIVATE).getString("encrypted", "");
            assertFalse(stored.contains("test-session-token")); assertFalse(stored.contains("test-user-id"));
            Field singleton = TeamAccountSession.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, null);
            session = TeamAccountSession.get(context);
            assertEquals("test-session-token-never-plaintext", session.token());
            assertEquals("test-user-id", session.accountId());
            assertEquals("Test Account", session.accountName());
            session.clear(); assertNull(session.token()); assertNull(session.accountId());
            assertFalse(context.getSharedPreferences("account_session", Context.MODE_PRIVATE).contains("encrypted"));
        } finally {
            original.clear(); session.clear();
            com.mobilegroup20.tokentrail.data.RepositoryProvider.configureForum(com.mobilegroup20.tokentrail.BuildConfig.FORUM_BASE_URL, session);
        }
    }
}
