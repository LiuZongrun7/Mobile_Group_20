package com.mobilegroup20.modelpilot.ui.auth;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.MainActivity;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.data.AccountSession;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class AccountDialogTest {
    @Test public void loginValidatesAndRetainsUsernameButNotPasswordOnRecreation() {
        Assume.assumeFalse(BuildConfig.FORUM_BASE_URL.isEmpty());
        AccountSession session = AccountSession.get(ApplicationProvider.getApplicationContext());
        session.clear(); RepositoryProvider.configureForum(BuildConfig.FORUM_BASE_URL, session);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.nav_profile)).perform(click());
            onView(withId(R.id.account_button)).perform(click());
            onView(withText(R.string.account_sign_in)).perform(click());
            onView(withText(R.string.account_empty)).check(matches(isDisplayed()));
            onView(withHint(R.string.account_username)).perform(typeText("rotation-test-user"));
            onView(withHint(R.string.account_password)).perform(typeText("temporary-password"), closeSoftKeyboard());
            scenario.recreate();
            onView(withHint(R.string.account_username)).check(matches(withText("rotation-test-user")));
            onView(withHint(R.string.account_password)).check(matches(withText("")));
            onView(withText(android.R.string.cancel)).perform(click());
        } finally { session.clear(); }
    }
}
