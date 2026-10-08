package com.mobilegroup20.modelpilot.ui.forum;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.contract.model.NewsArticle;

/** Read the publisher page, with a visible RSS summary when loading fails. No app credentials enter WebView. */
public final class NewsReaderActivity extends AppCompatActivity {
    private WebView web;
    private TextView status;
    private String url;
    public static void open(Context context, NewsArticle article) {
        context.startActivity(new Intent(context, NewsReaderActivity.class)
                .putExtra("title", article.title).putExtra("summary", article.summary)
                .putExtra("url", article.originalUrl));
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        url = getIntent().getStringExtra("url");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        MaterialButton back = new MaterialButton(this);
        back.setText(getString(R.string.news_reader_title) + " · ←");
        back.setOnClickListener(v -> finish()); root.addView(back);
        TextView title = new TextView(this); title.setText(getIntent().getStringExtra("title"));
        title.setTextSize(20); root.addView(title);
        status = new TextView(this); root.addView(status);
        TextView summary = new TextView(this);
        String text = getIntent().getStringExtra("summary");
        summary.setText(getString(R.string.news_summary_label) + "\n"
                + (text == null || text.isEmpty() ? getString(R.string.news_no_summary) : text));
        summary.setMaxLines(6); root.addView(summary);
        MaterialButton retry = new MaterialButton(this); retry.setText(R.string.news_retry_original);
        retry.setOnClickListener(v -> load()); root.addView(retry);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(false);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !safeUrl(request.getUrl().toString());
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) status.setText(R.string.news_load_failed);
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                      android.webkit.WebResourceResponse error) {
                if (request.isForMainFrame()) status.setText(R.string.news_load_failed);
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); status.setText(R.string.news_certificate_error);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        load();
    }
    private void load() {
        if (!safeUrl(url)) { status.setText(R.string.news_load_failed); return; }
        status.setText(""); web.loadUrl(url);
    }
    static boolean safeUrl(String value) {
        if (value == null) return false;
        try {
            java.net.URI uri = new java.net.URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (java.net.URISyntaxException invalid) { return false; }
    }
    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
