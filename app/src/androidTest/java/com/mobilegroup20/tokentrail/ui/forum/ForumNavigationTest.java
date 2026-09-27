package com.mobilegroup20.tokentrail.ui.forum;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mobilegroup20.tokentrail.MainActivity;
import com.mobilegroup20.tokentrail.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class ForumNavigationTest {
    @Test public void forumTabsSurviveGameNavigationAndActivityRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.nav_forum)).perform(click());
            onView(withText(R.string.forum_news)).check(matches(isDisplayed()));
            onView(withId(R.id.new_post)).check(matches(isDisplayed()));
            onView(withText(R.string.forum_community)).perform(click());
            onView(withId(R.id.nav_game)).perform(click());
            onView(withId(R.id.game_page)).check(matches(isDisplayed()));
            onView(withId(R.id.nav_forum)).perform(click());
            onView(withId(R.id.forum_page)).check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                ForumFragment fragment = (ForumFragment) activity.getSupportFragmentManager().findFragmentByTag("forum");
                org.junit.Assert.assertEquals(1, new androidx.lifecycle.ViewModelProvider(fragment).get(ForumViewModel.class).selectedTab);
            });
            scenario.recreate();
            onView(withId(R.id.forum_page)).check(matches(isDisplayed()));
        }
    }
    @Test public void composerValidatesAndRetainsTextAcrossRecreationAndFailedPublish() {
        com.mobilegroup20.tokentrail.data.AccountSession session = com.mobilegroup20.tokentrail.data.AccountSession.get(androidx.test.core.app.ApplicationProvider.getApplicationContext());
        try { session.save("tt_app_instrumentation-invalid-session", "u_" + "9".repeat(16), "Test Account"); }
        catch (Exception error) { throw new AssertionError(error); }
        com.mobilegroup20.tokentrail.data.RepositoryProvider.configureForum("", session);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.nav_forum)).perform(click());
            onView(withId(R.id.new_post)).perform(click());
            onView(withText(R.string.forum_publish)).perform(click());
            scenario.onActivity(activity -> {
                androidx.fragment.app.Fragment forum = activity.getSupportFragmentManager().findFragmentByTag("forum");
                androidx.fragment.app.Fragment compose = forum.getChildFragmentManager().findFragmentByTag("compose");
                ComposePostViewModel vm = new androidx.lifecycle.ViewModelProvider(compose).get(ComposePostViewModel.class);
                org.junit.Assert.assertEquals("Composer validation state; lifecycle=" + compose.getViewLifecycleOwner().getLifecycle().getCurrentState(), "EMPTY_POST", vm.error.getValue());
            });
            onView(withText(R.string.forum_empty_post)).check(matches(isDisplayed()));
            onView(withHint(R.string.forum_body_hint)).perform(typeText("A retained AI project draft."), closeSoftKeyboard());
            scenario.recreate();
            onView(withHint(R.string.forum_body_hint)).check(matches(withText("A retained AI project draft.")));
            onView(withText(R.string.forum_publish)).perform(click());
            onView(withText(R.string.forum_not_ready)).check(matches(isDisplayed()));
            onView(withHint(R.string.forum_body_hint)).check(matches(withText("A retained AI project draft.")));
            onView(withText(R.string.forum_back)).perform(click());
            onView(withText(R.string.forum_keep_editing)).perform(click());
            onView(withHint(R.string.forum_body_hint)).check(matches(isDisplayed()));
        } finally {
            session.clear();
            com.mobilegroup20.tokentrail.data.RepositoryProvider.configureForum(com.mobilegroup20.tokentrail.BuildConfig.FORUM_BASE_URL, session);
        }
    }
}
