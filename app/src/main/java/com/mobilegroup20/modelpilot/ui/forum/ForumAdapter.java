package com.mobilegroup20.modelpilot.ui.forum;

import android.content.Context;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.contract.model.*;
import com.mobilegroup20.modelpilot.databinding.ItemNewsBinding;
import java.util.*;

final class ForumAdapter extends RecyclerView.Adapter<ForumAdapter.Holder> {
    interface Actions {
        void detail(ForumPost post);
        void like(ForumPost post);
        boolean liking(String id);
        default void topic(TrendingTopic topic) { }
    }
    private List<?> items = Collections.emptyList();
    private final Actions actions;
    private boolean trending;
    ForumAdapter(Actions actions) { this.actions = actions; }
    void submit(List<?> items) {
        trending = !items.isEmpty() && items.get(0) instanceof ForumTrending;
        if (trending) {
            ForumTrending result = (ForumTrending) items.get(0);
            List<Object> rows = new ArrayList<>();
            rows.add(new Section(true, result)); rows.addAll(result.topics);
            rows.add(new Section(false, result)); rows.addAll(result.posts);
            this.items = rows;
        } else this.items = new ArrayList<>(items);
        notifyDataSetChanged();
    }
    public int getItemCount() { return items.size(); }
    @Override public int getItemViewType(int position) {
        Object item = items.get(position);
        return item instanceof NewsArticle ? 1 : item instanceof TrendingTopic ? 2 : item instanceof Section ? 3 : 0;
    }
    public Holder onCreateViewHolder(ViewGroup parent, int type) {
        if (type == 1) {
            return new Holder(ItemNewsBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false).getRoot());
        }
        LinearLayout host = ForumViews.column(parent.getContext());
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2); params.bottomMargin = ForumViews.dp(parent.getContext(), 12);
        host.setLayoutParams(params); return new Holder(host);
    }
    public void onBindViewHolder(Holder holder, int position) {
        Object item = items.get(position);
        if (item instanceof NewsArticle) {
            NewsArticle article = (NewsArticle) item;
            ItemNewsBinding row = ItemNewsBinding.bind(holder.itemView);
            row.newsTitle.setText(article.title);
            row.newsSource.setText(ForumViews.safe(article.sourceName) + " · " + ForumViews.date(article.publishedAtEpochMillis));
            row.newsThumbnail.setContentDescription(holder.itemView.getContext().getString(
                    R.string.news_thumbnail_description, article.title));
            Glide.with(row.newsThumbnail).load(article.imageUrl)
                    .placeholder(R.drawable.ic_news_placeholder).fallback(R.drawable.ic_news_placeholder)
                    .error(R.drawable.ic_news_placeholder).centerCrop().into(row.newsThumbnail);
            row.newsRow.setOnClickListener(v -> NewsReaderActivity.open(v.getContext(), article));
            return;
        }
        LinearLayout host = (LinearLayout) holder.itemView; host.removeAllViews();
        Context c = host.getContext(); LinearLayout content = ForumViews.column(c);
        if (item instanceof Section) {
            Section section = (Section) item;
            TextView heading = ForumViews.text(c, c.getString(section.news ? R.string.forum_trending_news : R.string.forum_trending_community), 20, 0xFF1D1B20);
            heading.setTypeface(null, android.graphics.Typeface.BOLD); content.addView(heading);
            content.addView(ForumViews.text(c, section.news
                    ? c.getString(R.string.forum_trending_news_basis, section.data.windowDays, ForumViews.date(section.data.asOfEpochMillis))
                    : c.getString(R.string.forum_trending_community_basis, section.data.windowDays), 12, 0xFF625B71));
            if (section.news ? section.data.topics.isEmpty() : section.data.posts.isEmpty())
                content.addView(ForumViews.text(c, c.getString(section.news ? R.string.forum_trending_no_topics : R.string.forum_trending_no_posts), 14, 0xFF625B71));
            host.addView(content); return;
        }
        if (item instanceof TrendingTopic) {
            TrendingTopic topic = (TrendingTopic) item;
            LinearLayout line = new LinearLayout(c); line.setGravity(Gravity.CENTER_VERTICAL);
            TextView rank = ForumViews.text(c, String.valueOf(topic.rank), 22, topic.rank <= 3 ? 0xFFB3261E : 0xFF6750A4);
            rank.setTypeface(null, android.graphics.Typeface.BOLD);
            line.addView(rank, new LinearLayout.LayoutParams(ForumViews.dp(c, 40), -2));
            LinearLayout labels = ForumViews.column(c);
            TextView name = ForumViews.text(c, topic.name, 18, 0xFF1D1B20); name.setTypeface(null, android.graphics.Typeface.BOLD);
            labels.addView(name);
            String articles = c.getResources().getQuantityString(R.plurals.forum_trending_articles, topic.articleCount, topic.articleCount);
            String sources = c.getResources().getQuantityString(R.plurals.forum_trending_sources, topic.sourceCount, topic.sourceCount);
            labels.addView(ForumViews.text(c, c.getString(R.string.forum_trending_topic_counts, articles, sources), 12, 0xFF625B71));
            line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            View card = ForumViews.card(c, line); card.setOnClickListener(v -> actions.topic(topic));
            card.setFocusable(true); host.addView(card); return;
        }
        ForumPost post = (ForumPost) item;
        if (trending) content.addView(ForumViews.text(c, c.getString(R.string.forum_trending_score, post.likeCount + 2 * post.commentCount), 12, 0xFFB3261E));
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
    static final class Holder extends RecyclerView.ViewHolder { Holder(View view) { super(view); } }
    private static final class Section {
        final boolean news;
        final ForumTrending data;
        Section(boolean news, ForumTrending data) { this.news = news; this.data = data; }
    }
}
