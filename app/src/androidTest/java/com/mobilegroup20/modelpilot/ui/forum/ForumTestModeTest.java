package com.mobilegroup20.modelpilot.ui.forum;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.MainActivity;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.data.AccountSession;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ForumTestModeTest {
    @Test public void testModeBadgeAndComposerSurviveRecreationAndExitReturnsToNormalForum() throws Exception {
        AccountSession session = AccountSession.get(ApplicationProvider.getApplicationContext()); session.clear();
        session.saveForumTest("tt_test_instrumentation-only", "test_instrumentation", "Tester Fixture");
        RepositoryProvider.configureForum("", session); // No live request needed for this UI test.
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.nav_forum)).perform(click());
            onView(withId(R.id.forum_test_badge)).check(matches(isDisplayed()));
            onView(withId(R.id.new_post)).perform(click());
            onView(withHint(R.string.forum_body_hint)).check(matches(isDisplayed()));
            onView(withText(R.string.forum_back)).perform(click());
            scenario.recreate();
            onView(withId(R.id.forum_test_badge)).check(matches(isDisplayed()));
            onView(withId(R.id.nav_profile)).perform(click());
            onView(withId(R.id.account_button)).perform(click());
            onView(withText(R.string.forum_test_leave)).perform(click());
            assertFalse(session.signedIn()); assertFalse(session.forumTest());
            onView(withId(R.id.nav_forum)).perform(click());
            onView(withId(R.id.forum_test_enter)).check(matches(isDisplayed()));
            onView(withId(R.id.forum_test_badge)).check(matches(withEffectiveVisibility(Visibility.GONE)));
        } finally { session.clear(); RepositoryProvider.configureForum(BuildConfig.FORUM_BASE_URL, session); }
    }
}
