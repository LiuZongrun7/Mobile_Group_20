package com.mobilegroup20.tokentrail.ui.forum;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.mobilegroup20.tokentrail.R;
import com.mobilegroup20.tokentrail.contract.model.*;
import java.text.DateFormat;
import java.util.Date;

/** Small shared presentation helpers. Repository implementations never depend on this class. */
final class ForumViews {
    static int dp(Context context, int size) { return Math.round(size * context.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static void insetDialog(View root) {
        int left = root.getPaddingLeft(), top = root.getPaddingTop();
        int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.ime());
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }
    static TextView text(Context c, String content, int size, int color) {
        TextView text = new TextView(c); text.setText(content); text.setTextSize(size); text.setTextColor(color);
        text.setPadding(0, dp(c, 4), 0, dp(c, 4)); return text;
    }
    static MaterialButton button(Context c, String label) {
        MaterialButton button = new MaterialButton(new androidx.appcompat.view.ContextThemeWrapper(c, R.style.ForumButtonTheme));
        button.setText(label); button.setMinWidth(0); return button;
    }
    static String date(long epoch) { return epoch > 0 ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(epoch)) : ""; }
    static String safe(String text) { return text == null ? "" : text; }
    static String author(Context c, ForumPost p) { return TextUtils.isEmpty(p.authorName) ? c.getString(R.string.forum_unknown_author) : p.authorName; }
    static MaterialCardView card(Context c, LinearLayout content) {
        MaterialCardView card = new MaterialCardView(c); card.setRadius(dp(c, 18));
        card.setCardBackgroundColor(Color.WHITE); card.setStrokeColor(0xFFE7E0EC); card.setStrokeWidth(dp(c, 1)); card.setCardElevation(0);
        content.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        card.addView(content);
        return card;
    }
    static void images(LinearLayout parent, ForumPost post) {
        if (post.images == null || post.images.isEmpty()) return;
        Context c = parent.getContext();
        int total = Math.min(9, post.images.size());
        for (int start = 0; start < total; start += 3) {
            LinearLayout row = new LinearLayout(c);
            for (int i = start; i < Math.min(start + 3, total); i++) {
                ForumImage image = post.images.get(i);
                int index = i;
                ImageView thumbnail = new ImageView(c); thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackgroundColor(0xFFF0EBF4); thumbnail.setContentDescription(c.getString(R.string.forum_photo, i + 1, total));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(c, total == 1 ? 220 : 108), 1);
                params.setMargins(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2)); row.addView(thumbnail, params);
                Glide.with(thumbnail).load(image.url).error(android.R.drawable.ic_menu_report_image).into(thumbnail);
                thumbnail.setOnClickListener(v -> fullscreen(c, image.url, index + 1, total));
            }
            parent.addView(row);
        }
    }
    static void fullscreen(Context c, Object image, int index, int total) {
        ImageView view = new ImageView(c); view.setBackgroundColor(Color.BLACK); view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setContentDescription(c.getString(R.string.forum_photo, index, total));
        AlertDialog dialog = new AlertDialog.Builder(c).setView(view).create();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        Glide.with(view).load(image).error(android.R.drawable.ic_menu_report_image).into(view);
        view.setOnClickListener(v -> dialog.dismiss());
    }
    static void openOriginal(Context c, String url) {
        try {
            Uri uri = Uri.parse(safe(url));
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
                Toast.makeText(c, R.string.forum_bad_link, Toast.LENGTH_SHORT).show(); return;
            }
            c.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) { Toast.makeText(c, R.string.forum_no_browser, Toast.LENGTH_SHORT).show(); }
    }
    static String error(Context c, String code) {
        int message;
        if ("NOT_CONFIGURED".equals(code)) message = R.string.forum_not_ready;
        else if ("UNAUTHORIZED".equals(code)) message = R.string.forum_sign_in;
        else if ("SESSION_CHANGED".equals(code)) message = R.string.forum_session_changed;
        else if ("NETWORK".equals(code)) message = R.string.forum_network_error;
        else if ("NOT_FOUND".equals(code)) message = R.string.forum_not_found;
        else if ("EMPTY_POST".equals(code)) message = R.string.forum_empty_post;
        else if ("EMPTY_REPLY".equals(code)) message = R.string.forum_empty_reply;
        else if ("INVALID_IMAGE".equals(code) || "IMAGE_TOO_LARGE".equals(code)) message = R.string.forum_bad_image;
        else if ("PHOTO_READ".equals(code)) message = R.string.forum_photo_read_error;
        else message = R.string.forum_request_error;
        return c.getString(message);
    }
}
