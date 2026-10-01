package com.mobilegroup20.modelpilot.ui.forum;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.tabs.TabLayout;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.contract.model.ForumPost;
import com.mobilegroup20.modelpilot.databinding.FragmentForumBinding;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.ui.auth.AccountDialog;
import com.mobilegroup20.modelpilot.data.AccountSession;

public final class ForumFragment extends Fragment implements ForumAdapter.Actions {
    private FragmentForumBinding binding;
    private ForumViewModel model;
    private ForumAdapter adapter;
    private LinearLayoutManager layout;
    private int displayedTab = -1;
    private boolean syncingTab;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        binding = FragmentForumBinding.inflate(inflater, parent, false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        model = new ViewModelProvider(this).get(ForumViewModel.class);
        layout = new LinearLayoutManager(requireContext()); binding.forumList.setLayoutManager(layout);
        adapter = new ForumAdapter(this); binding.forumList.setAdapter(adapter);
        binding.forumTabs.addTab(binding.forumTabs.newTab().setText(R.string.forum_news));
        binding.forumTabs.addTab(binding.forumTabs.newTab().setText(R.string.forum_community));
        binding.forumTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            public void onTabSelected(TabLayout.Tab tab) {
                if (syncingTab) return;
                if (displayedTab >= 0) model.scrollStates[displayedTab] = layout.onSaveInstanceState();
                model.selectedTab = tab.getPosition(); render(); model.ensureLoaded();
            }
            public void onTabUnselected(TabLayout.Tab tab) { }
            public void onTabReselected(TabLayout.Tab tab) { }
        });
        binding.newPost.setOnClickListener(v -> {
            if (!BuildConfig.FORUM_BASE_URL.isEmpty() && !AccountSession.get(requireContext()).signedIn()) showAccount();
            else new ComposePostDialog().show(getChildFragmentManager(), "compose");
        });
        binding.forumRefresh.setOnRefreshListener(() -> model.load(true));
        binding.forumTestEnter.setOnClickListener(v -> {
            if (getChildFragmentManager().findFragmentByTag("account") == null)
                AccountDialog.forForumTest().show(getChildFragmentManager(), "account");
        });
        binding.forumRetry.setOnClickListener(v -> {
            if ("UNAUTHORIZED".equals(active().error) && !BuildConfig.FORUM_BASE_URL.isEmpty()) showAccount();
            else model.load(active().error != null || active().items.isEmpty());
        });
        getChildFragmentManager().setFragmentResultListener("accountChanged", getViewLifecycleOwner(), (key, result) -> {
            model.syncSession(); model.load(true);
        });
        model.requests().observe(getViewLifecycleOwner(), ignored -> { });
        model.changes.observe(getViewLifecycleOwner(), ignored -> render());
        getChildFragmentManager().setFragmentResultListener("published", getViewLifecycleOwner(), (key, result) -> {
            // Composer already updates this parent's ViewModel; this event just scrolls the view.
            binding.forumList.scrollToPosition(0);
        });
        render(); model.syncSession();
    }
    private ForumViewModel.Feed<?> active() { return model.selectedTab == 0 ? model.news : model.posts; }
    private void render() {
        if (binding == null) return;
        boolean changed = displayedTab != model.selectedTab;
        displayedTab = model.selectedTab;
        TabLayout.Tab tab = binding.forumTabs.getTabAt(model.selectedTab);
        if (tab != null && !tab.isSelected()) { syncingTab = true; tab.select(); syncingTab = false; }
        ForumViewModel.Feed<?> feed = active(); adapter.submit(feed.items);
        binding.forumTestBadge.setVisibility(AccountSession.get(requireContext()).forumTest() ? View.VISIBLE : View.GONE);
        binding.forumTestEnter.setVisibility(BuildConfig.DEBUG && !BuildConfig.FORUM_BASE_URL.isEmpty() && "UNAUTHORIZED".equals(feed.error) && !feed.loading ? View.VISIBLE : View.GONE);
        boolean empty = feed.items.isEmpty();
        binding.forumRefresh.setVisibility(empty ? View.GONE : View.VISIBLE);
        android.widget.LinearLayout.LayoutParams statusParams = (android.widget.LinearLayout.LayoutParams) binding.forumStatusPanel.getLayoutParams();
        statusParams.height = empty ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        statusParams.weight = empty ? 1 : 0;
        binding.forumStatusPanel.setLayoutParams(statusParams);
        if (changed) layout.onRestoreInstanceState(model.scrollStates[displayedTab]);
        binding.forumRefresh.setRefreshing(feed.loading);
        String status = feed.error != null ? ForumViews.error(requireContext(), feed.error) : feed.loading ? getString(R.string.forum_loading) : feed.items.isEmpty() ? getString(model.selectedTab == 0 ? R.string.forum_no_news : R.string.forum_no_posts) : "";
        binding.forumStatus.setText(status);
        boolean more = feed.cursor != null;
        binding.forumRetry.setText("UNAUTHORIZED".equals(feed.error) && !BuildConfig.FORUM_BASE_URL.isEmpty() ? R.string.account_sign_in : feed.error != null ? R.string.forum_retry : empty ? R.string.forum_refresh_action : R.string.forum_load_more);
        binding.forumRetry.setVisibility(!feed.loading && (feed.error != null || more || empty) ? View.VISIBLE : View.GONE);
        binding.forumStatusPanel.setVisibility(!status.isEmpty() || more ? View.VISIBLE : View.GONE);
    }
    private void showAccount() {
        if ("UNAUTHORIZED".equals(active().error)) {
            // 401 说明这个会话服务端已经不认了：本地清掉，让用户重新登录。
            AccountSession.get(requireContext()).clear();
            AccountSession session = AccountSession.get(requireContext());
            com.mobilegroup20.modelpilot.data.RepositoryProvider.configureForum(session.forumBaseUrl(), session);
            model.syncSession();
        }
        if (getChildFragmentManager().findFragmentByTag("account") == null)
            new AccountDialog().show(getChildFragmentManager(), "account");
    }
    @Override public void onResume() { super.onResume(); if (model != null) model.syncSession(); }
    public void detail(ForumPost post) { PostDetailDialog.create(post.id).show(getChildFragmentManager(), "detail"); }
    public void like(ForumPost post) { model.toggleLike(post); }
    public boolean liking(String id) { return model.isLiking(id); }
    @Override public void onDestroyView() {
        if (model != null && displayedTab >= 0) model.scrollStates[displayedTab] = layout.onSaveInstanceState();
        binding.forumList.setAdapter(null); binding = null; super.onDestroyView();
    }
}
