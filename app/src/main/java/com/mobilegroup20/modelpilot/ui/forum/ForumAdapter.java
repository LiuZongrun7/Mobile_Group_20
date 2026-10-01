package com.mobilegroup20.modelpilot.ui.forum;

import android.content.Context;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.contract.model.*;
import java.util.*;

final class ForumAdapter extends RecyclerView.Adapter<ForumAdapter.Holder> {
    interface Actions {
        void detail(ForumPost post);
        void like(ForumPost post);
        boolean liking(String id);
    }
    private List<?> items = Collections.emptyList();
    private final Actions actions;
    ForumAdapter(Actions actions) { this.actions = actions; }
    void submit(List<?> items) { this.items = new ArrayList<>(items); notifyDataSetChanged(); }
    public int getItemCount() { return items.size(); }
    public Holder onCreateViewHolder(ViewGroup parent, int type) {
        LinearLayout host = ForumViews.column(parent.getContext());
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2); params.bottomMargin = ForumViews.dp(parent.getContext(), 12);
        host.setLayoutParams(params); return new Holder(host);
    }
    public void onBindViewHolder(Holder holder, int position) {
        LinearLayout host = (LinearLayout) holder.itemView; host.removeAllViews();
        Context c = host.getContext(); LinearLayout content = ForumViews.column(c);
        Object item = items.get(position);
        if (item instanceof NewsArticle) {
            NewsArticle article = (NewsArticle) item;
            content.addView(ForumViews.text(c, ForumViews.safe(article.sourceName) + " · " + ForumViews.date(article.publishedAtEpochMillis), 12, 0xFF625B71));
            TextView title = ForumViews.text(c, article.title, 20, 0xFF1D1B20); title.setTypeface(null, android.graphics.Typeface.BOLD); content.addView(title);
            if (article.imageUrl != null && !article.imageUrl.isEmpty()) {
                ImageView image = new ImageView(c); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setContentDescription(article.title); content.addView(image, new LinearLayout.LayoutParams(-1, ForumViews.dp(c, 168)));
                Glide.with(image).load(article.imageUrl).error(android.R.drawable.ic_menu_report_image).into(image);
            }
            if (article.summary != null && !article.summary.isEmpty()) {
                TextView summary = ForumViews.text(c, article.summary, 14, 0xFF49454F); summary.setMaxLines(4); summary.setEllipsize(android.text.TextUtils.TruncateAt.END); content.addView(summary);
            }
            content.addView(ForumViews.text(c, c.getString(R.string.forum_read_original), 13, 0xFF6750A4));
            View card = ForumViews.card(c, content); card.setOnClickListener(v -> ForumViews.openOriginal(c, article.originalUrl)); host.addView(card);
        } else {
            ForumPost post = (ForumPost) item;
            content.addView(ForumViews.text(c, ForumViews.author(c, post) + " · " + ForumViews.date(post.createdAtEpochMillis), 12, 0xFF625B71));
            if (post.source == ForumPost.Source.OFFICIAL) content.addView(ForumViews.text(c, c.getString(R.string.forum_official), 12, 0xFF6750A4));
            if (post.title != null && !post.title.isEmpty()) { TextView title = ForumViews.text(c, post.title, 19, 0xFF1D1B20); title.setTypeface(null, android.graphics.Typeface.BOLD); content.addView(title); }
            TextView body = ForumViews.text(c, ForumViews.safe(post.body), 15, 0xFF49454F); body.setMaxLines(5); body.setEllipsize(android.text.TextUtils.TruncateAt.END); content.addView(body);
            ForumViews.images(content, post);
            LinearLayout buttons = new LinearLayout(c);
            MaterialButton like = ForumViews.button(c, c.getString(post.likedByMe ? R.string.forum_liked : R.string.forum_like, post.likeCount));
            like.setEnabled(!actions.liking(post.id)); like.setOnClickListener(v -> actions.like(post));
            buttons.addView(like, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton comments = ForumViews.button(c, c.getResources().getQuantityString(R.plurals.forum_comments, (int) Math.min(Integer.MAX_VALUE, post.commentCount), post.commentCount));
            comments.setOnClickListener(v -> actions.detail(post)); buttons.addView(comments, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(buttons);
            View card = ForumViews.card(c, content); card.setOnClickListener(v -> actions.detail(post)); host.addView(card);
        }
    }
    static final class Holder extends RecyclerView.ViewHolder { Holder(View view) { super(view); } }
}
