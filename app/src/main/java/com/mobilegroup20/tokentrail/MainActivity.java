package com.mobilegroup20.tokentrail;

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
import com.mobilegroup20.tokentrail.ui.forum.ForumFragment;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import com.mobilegroup20.tokentrail.contract.model.Provider;
import com.mobilegroup20.tokentrail.contract.model.TokenBundle;
import com.mobilegroup20.tokentrail.data.Money;
import com.mobilegroup20.tokentrail.data.RepositoryProvider;
import com.mobilegroup20.tokentrail.data.RelayCredentials;
import com.mobilegroup20.tokentrail.data.SeasonWallet;
import com.mobilegroup20.tokentrail.data.SpendSync;
import com.mobilegroup20.tokentrail.databinding.ActivityMainBinding;
import com.mobilegroup20.tokentrail.ui.dashboard.DashboardUsage;
import com.mobilegroup20.tokentrail.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.mobilegroup20.tokentrail.util.TokenFormat;

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
 * {@code ../TokenTrail_Game/} 里，要翻旧账或搬回来去那边找。
 *
 * <p>剩下的四个板块是<b>统计 / 论坛 / 我的</b>（游戏去掉后底部导航只有三项）：
 * 统计读的是仓库里的按天用量，论坛是独立 Fragment，「我的」页挂着账号与中转设置。
 *
 * <p>{@link #wallet} 和 {@link SeasonWallet} 这套<b>留着没删</b>：它是"服务端结算余额"
 * 的通道，游戏只是它原来的消费者。现在余额由 {@link #seedWallet()} 拉一次并
 * {@link #applyWallet 落到钱包里}，界面上暂时没有地方显示——ModelPilot 那版
 * Insights / 预算页要用的时候直接接上即可。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TokenTrail";

    /** 资源换算率：每 100 万 token 换 100 个。和 {@code ResourceType} 注释里写的一致。 */
    private static final long TOKENS_PER_UNIT = 1_000_000L / 100L;

    /**
     * 预览期用的账号。真正的登录还没做，等接了自建账号服务再换成
     * 登录态里那个 uid（服务端从会话 token 解出来的那个，不是客户端传的）。
     */
    private static final String DEMO_UID = "demo-user";

    /** 操作条离面板边缘至少留这么远。贴着边的条子那几个按钮点不着。 */

    /**
     * 操作条和选中那座建筑之间留的空。
     *
     * <p>不能是 0：紧贴着建筑顶边的话，条子的下边缘和建筑上边缘糊成一条线，
     * 看着像建筑自己长出来一块。留一点，它才读得出是"浮在旁边的一张卡"。
     */

    private ActivityMainBinding binding;

    /** 随机源只建一次：每次发波都 new 一个的话，同一毫秒内连点会发出一样的波。 */

    /**
     * 演示用的钱包。开局全 0，等月度 token 到了再按换算率播一次种。
     *
     * <p><b>它是个快照，不是真余额。</b>真余额在 {@code SeasonRepository}（按天结算、
     * 以服务端为准），那个类还没实现。所以这里扣掉的钱一关应用就没了。
     * 之所以还是接上，是因为"买不起就别放"这条规则要在真余额到位之前就能验。
     *
     * <p>初值给 0 而不是 null：数据还没到时什么东西都买不起，比"什么都免费"安全
     * ——后者会在数据晚到的那几百毫秒里白送一堆塔。
     */
    private final ResourceBalance wallet = new ResourceBalance();
    /**
     * 钱包播过种没有。
     *
     * <p><b>必须只播一次。</b>{@code monthTokens} 返回的是 {@code LiveData}，
     * 界面重建、账号切换都会再发一遍；每发一遍就重新播种的话，
     * 刚花掉的资源会原样退回来——买一座塔，转个屏又有了。
     */
    private boolean walletSeeded;

    /**
     * 余额来源的决策者：启用中转就用服务端的结算结果，否则回落到演示换算。
     *
     * <p>决策逻辑本身在 {@link SeasonWallet} 里（可单测）。这里只负责把它的结果
     * 搬到界面上，并把「这个数字是哪来的」显示出来。
     */
    private SeasonWallet seasonWallet;

    /** 花钱时的「本地乐观扣 + 服务端对账」编排。规则和理由都在那个类里。 */
    private SpendSync spendSync;

    /** 商店弹窗。开着的时候买了东西要就地刷新余额和"买得起吗"，所以留着引用。 */

    /**
     * 建筑详情弹窗。
     *
     * <p><b>和商店分成两个弹窗，不合成一个。</b>它们回答的是两个问题
     * （"能买什么" / "这一座现在什么样"），而且随时可能一个盖着另一个：
     * 手里拎着塔、点一下已有的塔看它——合用一个的话这一下就得二选一，
     * 要么看不到详情，要么手上那件被扔掉。
     */

    /**
     * 详情面板现在讲的是哪一座。
     *
     * <p>升级成功、或者面板要重画的时候靠它找回去。{@link Building} 是引擎那边
     * 的可变对象（{@code level} 就在它身上），所以升级完不用换引用，
     * 同一个对象重画一遍就是新数值。
     */

    /**
     * 和 {@link #inspected} 一起选中的那一整排；空表 = 只选了一座。
     *
     * <p><b>只有这一份。</b>面板上的标题、总价、"整排升级"按钮、Move 按钮
     * 全读它，不给每一处各存一份——存两份的话，选完一整排之后总价和按钮
     * 会各说各的。
     *
     * <p>由 {@code BattlefieldView.Inspector.onInspected} 推进来，
     * 所以它和<b>地上画着的那些金边</b>是同一个来源，不可能说岔。
     */

    /**
     * 操作条量出来的宽高，以及它还算不算数。
     *
     * <p>位置每帧都要算（拖地图时建筑在屏幕上一直动），但**尺寸不用每帧量**：
     * 它只在 {@link #renderBuildingBar} 改了字之后才变，那边会把
     * {@link #barMeasured} 打回 {@code false}。
     */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 让内容铺到状态栏和手势条底下：整页岩石底（activity_main.xml 的第 0 层）
        // 要一直铺到屏幕最上/最下边，界面才像浮在场景里而不是被两条系统栏夹着。
        // 让开系统栏的活由布局里那句 fitsSystemWindows 干（它给界面层加 padding）。
        // 配套的是 styles.xml 里那两条透明的 statusBarColor / navigationBarColor，
        // 少任何一句，系统栏后面都会露出旧主题色。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 身份是**账号**（`AccountSession`），不是 relay 配置：没配中转的用户
        // 也该看到自己的余额。中转只影响「用量怎么进来」。
        seasonWallet = SeasonWallet.forDevice(
                com.mobilegroup20.tokentrail.data.AccountSession.get(this),
                RepositoryProvider.season());
        spendSync = new SpendSync(seasonWallet.ledger());
        // 服务端说「这笔没扣成」时，账本只发一个信号，由界面重新拉一次真值——
        // **不在账本里做本地加减**，理由见 SpendSync 的类注释。
        seasonWallet.refreshSignal().observe(this, ignored -> refreshWalletFromServer());
        setUpBottomNav();
        binding.accountButton.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag("account") == null)
                new com.mobilegroup20.tokentrail.ui.auth.AccountDialog().show(getSupportFragmentManager(), "account");
        });
        getSupportFragmentManager().setFragmentResultListener("accountChanged", this, (key, result) -> {
            MenuItem selected = binding.bottomNav.getMenu().findItem(binding.bottomNav.getSelectedItemId());
            showTab(selected.getItemId(), selected.getTitle());
        });
        if (savedInstanceState != null) binding.bottomNav.setSelectedItemId(savedInstanceState.getInt("selectedTab", R.id.nav_dashboard));
        seedWallet();
        showDashboard();
        setUpDashboardActions();
    }

    /**
     * 钱包初值：中转启用时用服务端结算出来的余额，没启用才回落到本地演示换算。
     *
     * <p>这段原来长在 HUD 里（`showHud`），HUD 跟着游戏页一起搬走了，所以挪出来单独一个
     * 入口——**余额的来源那一行必须留着**：一个数字分不清是真实结算还是现算的演示值，
     * 评审时没人敢信它。
     */
    private void seedWallet() {
        RepositoryProvider.usage().monthTokens(DEMO_UID, TimeUtils.currentMonth())
                .observe(this, bundle -> {
                    if (bundle == null || walletSeeded) {
                        return;
                    }
                    walletSeeded = true;
                    // 回落值**按需算**（供应商形式）：服务端可用时这个换算根本不该发生。
                    seasonWallet.balance(() -> demoBalance(bundle)).observe(this, this::applyWallet);
                });
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
     * 服务端的账号身份：**账号的 `userId`**，不是 relay key 的 sha256。
     *
     * <p>2026-02 改。以前这里是 `RelayCredentials.uid()`，于是没配中转就没身份、
     * 换个 key 余额就断。现在账号是身份，中转只是其中一条通道。
     * 没登录时返回 null，账本那边会走空实现。
     */
    private String uid() {
        return com.mobilegroup20.tokentrail.data.AccountSession.get(this).accountId();
    }

    /**
     * 重新拉一次服务端余额，覆盖本地显示。
     *
     * <p>**只有服务端模式才覆盖。** 演示模式下这里拿回来的会是回落值
     * （按本月 token 现算的那份），覆盖上去会把刚花掉的资源原样退回来——
     * 买一座塔，一次同步失败就白送一座。
     */
    private void refreshWalletFromServer() {
        if (!seasonWallet.relayConfigured()) {
            return;
        }
        seasonWallet.balance(() -> wallet).observe(this, value -> {
            if (value != null && value.source == SeasonWallet.Source.SERVER) {
                applyWallet(value);
            }
        });
    }

    /**
     * 把余额写进本地钱包。
     *
     * <p>游戏还在的时候这里还会顺手把来源那一行写到 HUD 上（`DEMO` = 按本月 token
     * 现算的演示值，没经过服务端结算，重装就没了）。HUD 跟着游戏页搬走了，
     * 现在只更新数值；等 Insights / 预算页接上时，**那一行要一起搬回来**——
     * 一个分不清来源的余额数字，评审时没人敢用。
     */
    private void applyWallet(SeasonWallet.Wallet value) {
        if (value == null) {
            return;
        }
        wallet.input = value.balance.input;
        wallet.cache = value.balance.cache;
        wallet.output = value.balance.output;
    }

    /**
     * 临时换算：输入、缓存（读+写合并）、输出三条线各算各的，互不通兑。
     *
     * <p>缓存读和写在这里合并成一种资源，是因为 {@code ResourceType} 只有三种；
     * 但代价值得注意——写缓存比读缓存贵得多（$3.75/1M 对 $0.30/1M），
     * 真要平衡的时候得分开定价。HUD 上那四个 token 桶就是分开显示的。
     *
     * <p>这只是钱包的<b>初值</b>：真结算按月结算改成按天推进、以服务端为准之后，
     * 这个换算整个删掉，余额从 {@code SeasonRepository} 来。
     */
    private ResourceBalance demoBalance(TokenBundle month) {
        return new ResourceBalance(
                month.input / TOKENS_PER_UNIT,
                (month.cacheRead + month.cacheWrite) / TOKENS_PER_UNIT,
                month.output / TOKENS_PER_UNIT);
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
            com.mobilegroup20.tokentrail.data.AccountSession session = com.mobilegroup20.tokentrail.data.AccountSession.get(this);
            boolean relay = com.mobilegroup20.tokentrail.data.RelayCredentials.get(this).configured();
            // 「我的」页：账号状态 + 中转状态。中转设置挂在同一个标签上（点标题进设置），
            // 这样不用新建页面。
            binding.emptyLabel.setText(account
                    ? getString(session.signedIn()
                            ? session.forumTest() ? R.string.forum_test_identity : R.string.account_signed_in
                            : R.string.my_page_signed_out, session.signedIn() ? session.accountName() : "")
                            + "\n\n" + getString(relay ? R.string.my_page_relay_on : R.string.my_page_relay_off)
                    : getString(R.string.nav_not_built, title));
            binding.emptyLabel.setClickable(account);
            binding.emptyLabel.setOnClickListener(account ? v -> openRelaySetup() : null);
        }
    }
    /** 打开中转设置。同一个 tag 只留一个实例，避免连点叠出多个对话框。 */
    private void openRelaySetup() {
        androidx.fragment.app.FragmentManager manager = getSupportFragmentManager();
        if (manager.findFragmentByTag("relay") == null)
            new com.mobilegroup20.tokentrail.ui.auth.RelaySetupDialog().show(manager, "relay");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("selectedTab", binding.bottomNav.getSelectedItemId());
        super.onSaveInstanceState(state);
    }
}
