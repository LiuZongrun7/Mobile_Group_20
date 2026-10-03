package com.mobilegroup20.modelpilot;

import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import android.content.res.ColorStateList;
import com.mobilegroup20.modelpilot.ui.forum.ForumFragment;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.TokenBundle;
import com.mobilegroup20.modelpilot.data.Money;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.databinding.ActivityMainBinding;
import com.mobilegroup20.modelpilot.ui.dashboard.DashboardUsage;
import com.mobilegroup20.modelpilot.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.mobilegroup20.modelpilot.util.TokenFormat;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Random;

/**
 * 主界面：上面是内容区，下面是底部导航。
 *
 * <p><b>塔防游戏那一版已经整块移出本工程</b>（2026-09-30，产品方向改成 ModelPilot）。
 * 战场、商店、建筑面板、波次按钮、以及它们用的 {@code game.engine} / {@code game.view}、
 * 贴图、{@code art/} 抠图脚本和 {@code docs/ART.md} 都放在与本工程同级的
 * {@code ../ModelPilot_Game/} 里，要翻旧账或搬回来去那边找。
 *
 * <p>剩下的四个板块是<b>统计 / 论坛 / 我的</b>（游戏去掉后底部导航只有三项）：
 * 统计读的是仓库里的按天用量，论坛是独立 Fragment，「我的」页挂着账号状态。

 * <p>2026-09-30 按新大纲（ModelPilot）收过一遍：塔防、赛季/资源余额、以及那套
 * 用量问答助手都整块删了——见提交信息。现在这一页只做统计，账号在「我的」。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ModelPilot";

    /**
     * 预览期用的账号。真正的登录还没做，等接了自建账号服务再换成
     * 登录态里那个 uid（服务端从会话 token 解出来的那个，不是客户端传的）。
     */
    private static final String DEMO_UID = "demo-user";

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 让内容铺到状态栏和手势条底下：页面底色要一直铺到屏幕最上/最下边，
        // 而不是被两条系统栏夹在中间。
        // 让开系统栏的活由布局里那句 fitsSystemWindows 干（它给界面层加 padding）。
        // 配套的是 styles.xml 里那两条透明的 statusBarColor / navigationBarColor，
        // 少任何一句，系统栏后面都会露出旧主题色。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 身份是**账号**（`AccountSession`）：用量和预算都挂在账号上，
        // 登录了就都读得到自己的那一份。
        setUpBottomNav();
        binding.accountButton.setOnClickListener(v -> openAccount());
        getSupportFragmentManager().setFragmentResultListener("accountChanged", this, (key, result) -> {
            MenuItem selected = binding.bottomNav.getMenu().findItem(binding.bottomNav.getSelectedItemId());
            showTab(selected.getItemId(), selected.getTitle());
        });
        if (savedInstanceState != null) binding.bottomNav.setSelectedItemId(savedInstanceState.getInt("selectedTab", R.id.nav_dashboard));
        showDashboard();
        setUpDashboardActions();
    }
    /** Dashboard reads the same per-day rows as the repository's usage analytics. */
    private void showDashboard() {
        String month = TimeUtils.currentMonth();
        String from = TimeUtils.firstDayOfMonth(month);
        String to = TimeUtils.clampToYesterday(TimeUtils.lastDayOfMonth(month));
        LocalDate now = LocalDate.now(TimeUtils.ZONE);
        binding.dashboardMonthLabel.setText(now.getMonth().getDisplayName(TextStyle.SHORT, Locale.US)
                + " " + now.getYear());
        if (to == null || to.compareTo(from) < 0) {
            renderDashboard(DashboardUsage.calculate(Collections.emptyList(), from, to), from, to);
            return;
        }
        RepositoryProvider.usage().dailyUsageIn(DEMO_UID, from, to).observe(this,
                rows -> renderDashboard(DashboardUsage.calculate(rows, from, to), from, to));
    }

    private void renderDashboard(DashboardUsage.Summary summary, String from, String to) {
        NumberFormat number = NumberFormat.getIntegerInstance(Locale.US);
        binding.dashboardMonthTokens.setText(number.format(summary.month.tokens));
        binding.dashboardTokenDelta.setText(RepositoryProvider.USE_STUBS
                ? "Sample usage (preview)" : "From recorded usage");
        if (summary.month.tokens == 0 || summary.month.rowsWithoutPrice > 0
                && summary.month.knownCostMicros == 0) {
            binding.dashboardCnySpend.setText(summary.month.tokens == 0 ? "¥0.00" : "—");
        } else {
            binding.dashboardCnySpend.setText(Money.formatCny(summary.month.knownCostMicros));
        }
        binding.dashboardSpendDelta.setText(summary.month.rowsWithoutPrice > 0
                ? summary.month.rowsWithoutPrice + " records lack pricing"
                : RepositoryProvider.USE_STUBS ? "Sample costs (preview)"
                : "CNY estimate · exchange rate unverified");
        binding.dashboardBudgetPercent.setText("—");
        binding.dashboardBudgetText.setText("Budget not configured");
        binding.dashboardRunway.setText("Available after wallet setup");
        int percent = summary.completedDays == 0 ? 0
                : Math.round(summary.daysWithRecords * 100f / summary.completedDays);
        binding.dashboardMissingPercent.setText(percent + "%");
        binding.dashboardCoverageNote.setText(summary.daysWithRecords + " of "
                + summary.completedDays + " completed days have records");
        binding.dashboardCoverageFill.post(() -> {
            View parent = (View) binding.dashboardCoverageFill.getParent();
            binding.dashboardCoverageFill.getLayoutParams().width =
                    Math.round(parent.getWidth() * percent / 100f);
            binding.dashboardCoverageFill.requestLayout();
        });
        renderDashboardProviders(summary);
        renderDashboardTrend(summary, from, to);
    }

    private void renderDashboardProviders(DashboardUsage.Summary summary) {
        TextView[] rows = {
                binding.dashboardProviderOpenai,
                binding.dashboardProviderMimo,
                binding.dashboardProviderDeepseek
        };
        Provider[] providers = {Provider.OPENAI, Provider.MIMO, Provider.DEEPSEEK};
        NumberFormat tokenFormat = NumberFormat.getIntegerInstance(Locale.US);
        for (int i = 0; i < rows.length; i++) {
            DashboardUsage.Totals totals = summary.providers.get(providers[i]);
            long tokens = totals == null ? 0 : totals.tokens;
            int percent = summary.month.tokens == 0 ? 0
                    : (int) Math.round(tokens * 100.0 / summary.month.tokens);
            String cost = totals == null || totals.tokens == 0 ? "" : totals.rowsWithoutPrice > 0
                    ? " · price incomplete" : " · " + Money.formatCny(totals.knownCostMicros);
            rows[i].setText(providers[i].displayName + "  " + percent + "%\n"
                    + tokenFormat.format(tokens) + " tokens" + cost);
        }
    }

    private void renderDashboardTrend(DashboardUsage.Summary summary, String from, String to) {
        binding.dashboardTrendBars.removeAllViews();
        if (to == null || to.compareTo(from) < 0) {
            binding.dashboardTrendRange.setText("No completed days this month");
            return;
        }
        String first = TimeUtils.plusDays(to, -13);
        if (first.compareTo(from) < 0) first = from;
        List<String> days = TimeUtils.daysBetween(first, to);
        binding.dashboardTrendRange.setText(TimeUtils.formatForDisplay(first) + " – "
                + TimeUtils.formatForDisplay(to));
        long max = 0;
        for (String day : days) max = Math.max(max, summary.tokensByDay.getOrDefault(day, 0L));
        for (String day : days) {
            long tokens = summary.tokensByDay.getOrDefault(day, 0L);
            View bar = new View(this);
            bar.setBackgroundColor(tokens == 0 ? 0xFFE5ECF8 : 0xFF1976D2);
            bar.setContentDescription(TimeUtils.formatForDisplay(day) + ": " + tokens + " tokens");
            int height = max == 0 ? 3 : Math.max(3, Math.round(126f * tokens / max));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(height), 1f);
            params.setMargins(dp(3), 0, dp(3), 0);
            binding.dashboardTrendBars.addView(bar, params);
        }
    }

    private void setUpDashboardActions() {
        binding.dashboardPriceCard.setOnClickListener(v -> Toast.makeText(this,
                "Price Center: model prices and official top-up links", Toast.LENGTH_SHORT).show());
        binding.dashboardPriceIcon.setOnClickListener(v -> binding.dashboardPriceCard.performClick());
        binding.dashboardAiCard.setOnClickListener(v -> Toast.makeText(this,
                "AI Assistant will explain usage changes from your records", Toast.LENGTH_SHORT).show());
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    /**
     * 服务端的账号身份：**账号的 `userId`**。
     *
     * <p>没登录时返回 null，账本那边会走空实现。
     */
    private String uid() {
        return com.mobilegroup20.modelpilot.data.AccountSession.get(this).accountId();
    }
    // ==================== 底部导航 ====================

    /**
     * 底部四个板块：统计 / 游戏 / 论坛 / 我的。
     *
     * <p><b>打开 App 落在哪一页，跟着 {@code menu/bottom_nav.xml} 里的顺序走</b>
     * ——菜单第一项是"统计"，所以落地页就是统计。想换主页就去换那个文件里的顺序，
     * 这里一个字都不用改。
     */
    private void setUpBottomNav() {
        binding.bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId(), item.getTitle());
            return true;
        });
        // 菜单第一项在 listener 挂上之前就已经是选中态了，那一次不会回调过来
        // ——落地页得自己刷一遍，不然会停在布局文件里那个初始可见性上，
        // 和导航栏亮着的标签对不上。
        MenuItem landing = binding.bottomNav.getMenu().getItem(0);
        showTab(landing.getItemId(), landing.getTitle());
    }

    /**
     * 统计 / 论坛 / 我的之间切换。论坛使用独立 Fragment 和浅色系统栏。
     * 论坛 Fragment 留在 FragmentManager 中，保留各自的游标与滚动位置。
     *
     * <p>游戏页搬走之后这里只剩浅色一套系统栏配色（原来游戏页要深色岩石底，
     * 才需要 `lightPage` 这个开关）。
     */
    private void showTab(int itemId, CharSequence title) {
        boolean dashboard = itemId == R.id.nav_dashboard;
        boolean forum = itemId == R.id.nav_forum;
        boolean account = itemId == R.id.nav_profile && !BuildConfig.FORUM_BASE_URL.isEmpty();

        binding.accountButton.setVisibility(account ? View.VISIBLE : View.GONE);
        binding.dashboardPage.setVisibility(dashboard ? View.VISIBLE : View.GONE);
        binding.emptyPage.setVisibility(forum || dashboard ? View.GONE : View.VISIBLE);
        binding.forumPage.setVisibility(forum ? View.VISIBLE : View.GONE);
        binding.getRoot().setBackgroundColor(0xFFFFFBFE);
        ColorStateList navColors = ContextCompat.getColorStateList(this, R.color.forum_nav_item);
        binding.bottomNav.setItemIconTintList(navColors);
        binding.bottomNav.setItemTextColor(navColors);
        binding.bottomNav.setBackgroundColor(0xFFFFFFFF);
        binding.bottomNav.setItemActiveIndicatorColor(ColorStateList.valueOf(0xFFEAF3FF));
        WindowCompat.getInsetsController(getWindow(), binding.getRoot()).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), binding.getRoot()).setAppearanceLightNavigationBars(true);
        getWindow().setNavigationBarColor(android.os.Build.VERSION.SDK_INT < 27 ? Color.BLACK : Color.TRANSPARENT);
        Fragment forumFragment = getSupportFragmentManager().findFragmentByTag("forum");
        if (forumFragment == null && forum) {
            getSupportFragmentManager().beginTransaction().add(R.id.forum_page, new ForumFragment(), "forum").commitNow();
        } else if (forumFragment != null) {
            androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
            if (forum) tx.show(forumFragment); else tx.hide(forumFragment);
            tx.setMaxLifecycle(forumFragment, forum ? Lifecycle.State.RESUMED : Lifecycle.State.STARTED).commitNow();
        }
        if (!forum && !dashboard) {
            com.mobilegroup20.modelpilot.data.AccountSession session = com.mobilegroup20.modelpilot.data.AccountSession.get(this);
            // 「我的」页只有账号状态：登录/未登录 + 账号名 +（Debug 的）论坛测试身份。
            // 点标题开账号弹窗，和右上角那个按钮是同一个入口。
            binding.emptyLabel.setText(account
                    ? getString(session.signedIn()
                            ? session.forumTest() ? R.string.forum_test_identity : R.string.account_signed_in
                            : R.string.my_page_signed_out, session.signedIn() ? session.accountName() : "")
                    : getString(R.string.nav_not_built, title));
            binding.emptyLabel.setClickable(account);
            binding.emptyLabel.setOnClickListener(account ? v -> openAccount() : null);
        }
    }
    /** 打开账号弹窗（登录/注册/退出）。同一个 tag 只留一个实例，避免连点叠出多个对话框。 */
    private void openAccount() {
        androidx.fragment.app.FragmentManager manager = getSupportFragmentManager();
        if (manager.findFragmentByTag("account") == null)
            new com.mobilegroup20.modelpilot.ui.auth.AccountDialog().show(manager, "account");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("selectedTab", binding.bottomNav.getSelectedItemId());
        super.onSaveInstanceState(state);
    }
}
