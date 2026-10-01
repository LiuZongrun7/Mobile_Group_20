package com.mobilegroup20.modelpilot.ui.forum;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.contract.model.*;

public final class PostDetailDialog extends DialogFragment {
    private PostDetailViewModel model;
    private LinearLayout postContent, replies;
    private TextView status;
    private EditText comment;
    private MaterialButton send, more, retry, like;
    public static PostDetailDialog create(String id) {
        PostDetailDialog dialog = new PostDetailDialog(); Bundle args = new Bundle(); args.putString("id", id); dialog.setArguments(args); return dialog;
    }
    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state) { return new androidx.activity.ComponentDialog(requireContext(), R.style.ForumTheme); }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        Context c = requireDialog().getContext(); LinearLayout root = ForumViews.column(c);
        root.setBackgroundColor(0xFFFFFBFE); root.setPadding(ForumViews.dp(c, 16), ForumViews.dp(c, 16), ForumViews.dp(c, 16), ForumViews.dp(c, 16));
        ForumViews.insetDialog(root);
        MaterialButton back = ForumViews.button(c, getString(R.string.forum_back)); back.setOnClickListener(v -> dismiss()); root.addView(back);
        ScrollView scroll = new ScrollView(c); LinearLayout content = ForumViews.column(c); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        postContent = ForumViews.column(c); content.addView(postContent);
        like = ForumViews.button(c, getString(R.string.forum_like, 0)); content.addView(like);
        content.addView(ForumViews.text(c, getString(R.string.forum_comments_heading), 18, 0xFF1D1B20));
        replies = ForumViews.column(c); content.addView(replies);
        status = ForumViews.text(c, getString(R.string.forum_loading), 14, 0xFF625B71); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(status);
        retry = ForumViews.button(c, getString(R.string.forum_retry)); content.addView(retry);
        more = ForumViews.button(c, getString(R.string.forum_load_more)); content.addView(more);
        LinearLayout input = new LinearLayout(c); input.setGravity(Gravity.CENTER_VERTICAL);
        comment = new EditText(c); comment.setHint(R.string.forum_comment_hint); comment.setMaxLines(4);
        comment.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.addView(comment, new LinearLayout.LayoutParams(0, -2, 1));
        send = ForumViews.button(c, getString(R.string.forum_send)); input.addView(send); root.addView(input);
        return root;
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(this).get(PostDetailViewModel.class);
        comment.setText(ForumViews.safe(model.saved.get("body")));
        comment.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { model.saved.set("body", s.toString()); }
            public void afterTextChanged(Editable text) { }
        });
        model.requests().observe(getViewLifecycleOwner(), ignored -> { });
        model.post.observe(getViewLifecycleOwner(), post -> {
            postContent.removeAllViews(); like.setVisibility(post == null ? View.GONE : View.VISIBLE);
            if (post == null) return;
            Context c = postContent.getContext(); postContent.addView(ForumViews.text(c, ForumViews.author(c, post) + " · " + ForumViews.date(post.createdAtEpochMillis), 12, 0xFF625B71));
            if (post.title != null && !post.title.isEmpty()) postContent.addView(ForumViews.text(c, post.title, 22, 0xFF1D1B20));
            postContent.addView(ForumViews.text(c, ForumViews.safe(post.body), 16, 0xFF49454F)); ForumViews.images(postContent, post);
            like.setText(getString(post.likedByMe ? R.string.forum_liked : R.string.forum_like, post.likeCount));
            new ViewModelProvider(requireParentFragment()).get(ForumViewModel.class).replace(post);
        });
        model.comments.observe(getViewLifecycleOwner(), values -> {
            replies.removeAllViews(); Context c = replies.getContext();
            if (values.isEmpty()) replies.addView(ForumViews.text(c, getString(R.string.forum_no_comments), 14, 0xFF625B71));
            for (ForumReply reply : values) {
                LinearLayout item = ForumViews.column(c);
                item.addView(ForumViews.text(c, ForumViews.safe(reply.authorName) + " · " + ForumViews.date(reply.createdAtEpochMillis), 12, 0xFF625B71));
                item.addView(ForumViews.text(c, reply.body, 15, 0xFF1D1B20)); replies.addView(ForumViews.card(c, item));
            }
            renderStatus();
        });
        model.error.observe(getViewLifecycleOwner(), ignored -> renderStatus());
        model.loading.observe(getViewLifecycleOwner(), ignored -> renderStatus());
        model.liking.observe(getViewLifecycleOwner(), busy -> like.setEnabled(!busy));
        model.sending.observe(getViewLifecycleOwner(), busy -> { send.setEnabled(!busy); comment.setEnabled(!busy); setCancelable(!busy); });
        model.sent.observe(getViewLifecycleOwner(), count -> { if (count > 0 && "".equals(model.saved.get("body"))) comment.setText(""); });
        send.setOnClickListener(v -> model.send(comment.getText().toString()));
        like.setOnClickListener(v -> model.like());
        more.setOnClickListener(v -> model.loadComments(false)); retry.setOnClickListener(v -> model.refresh());
        model.open(requireArguments().getString("id"));
    }
    private void renderStatus() {
        if (model == null) return;
        boolean loading = Boolean.TRUE.equals(model.loading.getValue()); String error = model.error.getValue();
        status.setText(error != null ? ForumViews.error(requireContext(), error) : loading ? getString(R.string.forum_loading) : "");
        retry.setVisibility(error != null && !loading ? View.VISIBLE : View.GONE);
        more.setVisibility(model.hasMore() && !loading ? View.VISIBLE : View.GONE);
    }
    @Override public void onResume() { super.onResume(); if (model != null) model.open(requireArguments().getString("id")); }
    @Override public void onStart() {
        super.onStart(); Window window = requireDialog().getWindow();
        if (window != null) { window.setLayout(-1, -1); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false); }
    }
}
