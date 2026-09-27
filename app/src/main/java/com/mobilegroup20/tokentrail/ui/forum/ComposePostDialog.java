package com.mobilegroup20.tokentrail.ui.forum;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.activity.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.*;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.tokentrail.R;

public final class ComposePostDialog extends DialogFragment {
    private ComposePostViewModel model;
    private EditText title, body;
    private LinearLayout previews;
    private TextView status;
    private MaterialButton publish, addPhotos, back;
    private final ActivityResultLauncher<PickVisualMediaRequest> picker = registerForActivityResult(
            new ActivityResultContracts.PickMultipleVisualMedia(9), uris -> model.addPhotos(uris));
    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state) {
        ComponentDialog dialog = new ComponentDialog(requireContext(), R.style.ForumTheme);
        dialog.setCanceledOnTouchOutside(false);
        dialog.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            public void handleOnBackPressed() { closeDraft(); }
        });
        return dialog;
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        Context c = requireDialog().getContext();
        LinearLayout root = ForumViews.column(c); root.setBackgroundColor(0xFFFFFBFE);
        root.setPadding(ForumViews.dp(c, 16), ForumViews.dp(c, 16), ForumViews.dp(c, 16), ForumViews.dp(c, 16));
        ForumViews.insetDialog(root);
        LinearLayout toolbar = new LinearLayout(c);
        back = ForumViews.button(c, getString(R.string.forum_back)); toolbar.addView(back);
        publish = ForumViews.button(c, getString(R.string.forum_publish)); toolbar.addView(publish, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(toolbar);
        ScrollView scroll = new ScrollView(c); LinearLayout fields = ForumViews.column(c); scroll.addView(fields); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        title = new EditText(c); title.setHint(R.string.forum_title_hint); title.setSingleLine(); fields.addView(title);
        body = new EditText(c); body.setHint(R.string.forum_body_hint); body.setGravity(Gravity.TOP);
        body.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.setMinLines(7); fields.addView(body, new LinearLayout.LayoutParams(-1, -2));
        addPhotos = ForumViews.button(c, getString(R.string.forum_add_photos)); fields.addView(addPhotos);
        fields.addView(ForumViews.text(c, getString(R.string.forum_photos_limit), 12, 0xFF625B71));
        previews = ForumViews.column(c); fields.addView(previews);
        status = ForumViews.text(c, "", 14, 0xFF6750A4); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); fields.addView(status);
        return root;
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(this).get(ComposePostViewModel.class);
        title.setText(ForumViews.safe(model.saved.get("title"))); body.setText(ForumViews.safe(model.saved.get("body")));
        watchText(title, "title"); watchText(body, "body");
        back.setOnClickListener(v -> closeDraft());
        addPhotos.setOnClickListener(v -> picker.launch(new PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        publish.setOnClickListener(v -> model.publish(title.getText().toString(), body.getText().toString()));
        model.requests().observe(getViewLifecycleOwner(), ignored -> { });
        model.photos.observe(getViewLifecycleOwner(), values -> renderPhotos());
        model.error.observe(getViewLifecycleOwner(), error -> { if (error != null) status.setText(ForumViews.error(requireContext(), error)); });
        model.progress.observe(getViewLifecycleOwner(), progress -> {
            if (progress == null) return;
            if ("publish".equals(progress)) status.setText(R.string.forum_publishing);
            else { String[] counts = progress.split("/"); status.setText(getString(R.string.forum_uploading, Integer.parseInt(counts[0]), Integer.parseInt(counts[1]))); }
        });
        model.busy.observe(getViewLifecycleOwner(), busy -> {
            title.setEnabled(!busy); body.setEnabled(!busy); publish.setEnabled(!busy); back.setEnabled(!busy); addPhotos.setEnabled(!busy && model.photos.getValue().size() < 9);
            renderPhotos();
        });
        model.published.observe(getViewLifecycleOwner(), post -> {
            if (post == null) return;
            ForumViewModel parent = new ViewModelProvider(requireParentFragment()).get(ForumViewModel.class);
            parent.published(post);
            getParentFragmentManager().setFragmentResult("published", new Bundle()); dismiss();
        });
    }
    private void watchText(EditText field, String key) {
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { model.saved.set(key, s.toString()); }
            public void afterTextChanged(Editable text) { }
        });
    }
    private void renderPhotos() {
        previews.removeAllViews(); Context c = previews.getContext(); int index = 0;
        for (String uri : model.photos.getValue()) {
            LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView image = new ImageView(c); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(image, new LinearLayout.LayoutParams(ForumViews.dp(c, 96), ForumViews.dp(c, 96)));
            Glide.with(image).load(android.net.Uri.parse(uri)).error(android.R.drawable.ic_menu_report_image).into(image);
            int position = ++index;
            image.setContentDescription(getString(R.string.forum_photo, position, model.photos.getValue().size()));
            image.setOnClickListener(v -> ForumViews.fullscreen(c, android.net.Uri.parse(uri), position, model.photos.getValue().size()));
            MaterialButton remove = ForumViews.button(c, getString(R.string.forum_remove_photo, position));
            remove.setEnabled(!Boolean.TRUE.equals(model.busy.getValue())); remove.setOnClickListener(v -> model.removePhoto(uri)); row.addView(remove);
            previews.addView(row);
        }
        addPhotos.setEnabled(!Boolean.TRUE.equals(model.busy.getValue()) && model.photos.getValue().size() < 9);
    }
    private void closeDraft() {
        if (model == null || Boolean.TRUE.equals(model.busy.getValue())) return;
        if (title.getText().length() == 0 && body.getText().length() == 0 && model.photos.getValue().isEmpty()) { dismiss(); return; }
        new MaterialAlertDialogBuilder(requireDialog().getContext()).setTitle(R.string.forum_discard_title).setMessage(R.string.forum_discard_body)
                .setNegativeButton(R.string.forum_keep_editing, null).setPositiveButton(R.string.forum_discard, (d, w) -> dismiss()).show();
    }
    @Override public void onStart() {
        super.onStart(); Window window = requireDialog().getWindow();
        if (window != null) {
            window.setLayout(-1, -1); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
        }
    }
}
