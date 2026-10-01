package com.mobilegroup20.modelpilot.data;

import android.content.Context;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.data.remote.HttpBudgetRepository;
import com.mobilegroup20.modelpilot.data.remote.HttpForumRepository;
import com.mobilegroup20.modelpilot.data.repository.ForumFeedRepository;
import com.mobilegroup20.modelpilot.data.repository.SessionProvider;

import androidx.room.Room;

import com.mobilegroup20.modelpilot.data.local.AppDatabase;
import com.mobilegroup20.modelpilot.data.local.BundledPricingSource;
import com.mobilegroup20.modelpilot.data.local.RoomUsageRepository;
import com.mobilegroup20.modelpilot.data.repository.BudgetRepository;
import com.mobilegroup20.modelpilot.data.repository.ForumRepository;
import com.mobilegroup20.modelpilot.data.repository.UsageRepository;
import com.mobilegroup20.modelpilot.data.stub.StubBudgetRepository;
import com.mobilegroup20.modelpilot.data.stub.StubUsageRepository;

/**
 * 全项目获取 Repository 的唯一入口。
 *
 * <p><b>为什么要有这个类。</b>三个人的模块要能各自开工，就必须有一处决定
 * 「现在用谁的实现」。如果每个 Activity 自己 new，那么真实现接好的那天要改遍
 * 所有界面，而且很容易漏一处，出现「有的页面是真的、有的是假的」这种最难查的问题。
 *
 * <p>换成真实现的时候只动这个文件：把 {@link #USE_STUBS} 关掉，把对应的
 * {@code new XxxImpl(...)} 填进方法体。界面一行都不用改，因为它们只认接口。
 *
 * <p>没有用依赖注入框架：这个项目的对象数量很少，一个静态入口更直白，
 * 也省掉一套注解和初始化代码。等真的需要按测试场景替换时再换 Hilt 不迟。
 */
public final class RepositoryProvider {

    /**
     * 是否使用桩数据。
     *
     * <p>开发期开着，让游戏和 agent 有数据可算；<b>提交之前必须关掉</b>，
     * 否则演示时看到的是编出来的数字。
     */
    public static final boolean USE_STUBS = true;

    private static UsageRepository usage;
    private static ForumRepository forum;
    private static HttpForumRepository forumHttp;
    private static String forumBaseUrl = BuildConfig.FORUM_BASE_URL;
    private static volatile SessionProvider forumSession = SessionProvider.SIGNED_OUT;
    private static BudgetRepository budget;

    /**
     * 应用级的 Context，只用来建数据库。
     *
     * <p>传进来的必须是 Application 的，不能是 Activity 的——它存在静态字段里，
     * 存 Activity 就是把一个会销毁的对象钉住不放。{@code ModelPilotApp} 负责传对的这个。
     */
    private static Context appContext;
    private static AppDatabase database;
    private static PricingSource pricing;

    private RepositoryProvider() {
    }

    /**
     * 由 {@code ModelPilotApp.onCreate} 调用，全项目只调这一次。
     *
     * <p>重复调用是安全的（只留第一个），因为 Application 只会建一次，
     * 而测试里可能会手动调。
     */
    public static synchronized void init(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("init 需要一个 Context");
        }
        appContext = context.getApplicationContext();
    }

    /**
     * 本地数据库，懒建。
     *
     * <p>没初始化就抛异常而不是返回 null：报错信息要说清楚<b>怎么办</b>，
     * 而不是让调用方在远处收到一个空指针。
     */
    private static synchronized AppDatabase database() {
        if (database == null) {
            if (appContext == null) {
                throw new IllegalStateException(
                        "RepositoryProvider 还没初始化。正常情况下 ModelPilotApp.onCreate "
                                + "会调 init()；如果你是从测试或别的地方进来，先调 "
                                + "RepositoryProvider.init(context)。");
            }
            database = Room.databaseBuilder(appContext, AppDatabase.class, AppDatabase.NAME)
                    // 每加一版表结构，这里就要多挂一个迁移。漏挂的后果是运行时抛
                    // IllegalStateException（"A migration from 1 to 2 was required but
                    // not found"），装过旧版的设备直接打不开——比静默改坏数据好，
                    // 但不如在装机前就发现。
                    .addMigrations(AppDatabase.MIGRATION_1_2)
                    // 不加 fallbackToDestructiveMigration：那会在版本号对不上时
                    // 静默删掉全部原始记录，而那张表是「删了就没了」的唯一事实来源。
                    // 改了表结构就老老实实写迁移，见 docs/CONTRACTS.md §5。
                    .build();
        }
        return database;
    }

    /**
     * 打包的价目表。
     *
     * <p><b>现在这张表是空的</b>，也就是说凡是查不到价的模型，成本会留成
     * 「不可计算」而不是 0。要让它有数，得照
     * {@code BundledPricingSource} 类注释里的格式，从各家官网定价页
     * 把单价和链接一起抄进来——别猜数字。
     */
    private static synchronized PricingSource pricing() {
        if (pricing == null) {
            pricing = new BundledPricingSource();
        }
        return pricing;
    }

    /** 用量数据。桩 → {@code com.mobilegroup20.modelpilot.data.local.RoomUsageRepository}。 */
    public static synchronized UsageRepository usage() {
        if (usage == null) {
            usage = USE_STUBS
                    ? new StubUsageRepository()
                    : new RoomUsageRepository(database(), pricing());
            requireReady(usage, "UsageRepository（数据侧）");
        }
        return usage;
    }

    /** Existing agent contract now uses the same HTTP service as the forum UI. */
    public static synchronized ForumRepository forum() {
        if (forum == null) {
            forum = forumHttp();
        }
        return forum;
    }

    /** Called by the team's account module with its live session provider. No second login. */
    public static synchronized void configureForum(String baseUrl, SessionProvider session) {
        if (session == null) throw new IllegalArgumentException("SessionProvider is required");
        if (baseUrl != null && !baseUrl.isEmpty() && !baseUrl.startsWith("https://"))
            throw new IllegalArgumentException("Forum API must use HTTPS");
        forumBaseUrl = baseUrl;
        forumSession = session;
        forumHttp = null;
        forum = null;
    }

    public static synchronized ForumFeedRepository forumFeed() { return forumHttp(); }

    private static HttpForumRepository forumHttp() {
        if (forumHttp == null) forumHttp = new HttpForumRepository(forumBaseUrl, new SessionProvider() {
            public String token() { return forumSession.token(); }
            public String accountId() { return forumSession.accountId(); }
        });
        return forumHttp;
    }

    /**
     * 预算数据。桩 → 服务端（{@code HttpBudgetRepository}）。
     *
     * <p>**上限存在服务端**，和余额、结算一样（`CONTRACTS.md` §5）。原来这里
     * `USE_STUBS=false` 时返回 null，靠 `requireReady` 抛一句「还没实现」——
     * 现在有真实现了。花销仍然算不出来（没有价目表），那件事由
     * `BudgetStatus.coverage` 和后端的 `pricingAvailable` 表达，不在这里伪装。
     */
    public static synchronized BudgetRepository budget() {
        if (budget == null) {
            budget = USE_STUBS ? new StubBudgetRepository()
                    : new HttpBudgetRepository(forumBaseUrl, relaySession());
            requireReady(budget, "BudgetRepository（数据侧，张莉）");
        }
        return budget;
    }
    /**
     * 用量/赛季/预算这套的身份：**账号会话**，不是 relay key。
     *
     * <p>2026-02 改。原来这里用 relay key，`accountId()` 是它的 sha256——
     * 于是「换个 key 就换个人」，而且没配中转的用户读不到自己的用量。
     * 现在服务端把这两条路都解到**账号的 `user_id`**（`relay_store.owner_of`），
     * 所以手机上只要账号 token 就够；relay key 只用来转发
     * （{@code /api/relay/v1/...}，见 {@link #relayKeyForForwarding()}）。
     *
     * <p>做成一个 {@code SessionProvider} 而不是直接把 token 传进仓储，是为了复用
     * 仓储里那套「中途换了身份就把结果丢掉」的判断——换账号后回来的旧响应
     * 不能被显示成新账号的余额。
     */
    private static SessionProvider relaySession() {
        return new SessionProvider() {
            @Override public String token() {
                return appContext == null ? null : AccountSession.get(appContext).token();
            }
            @Override public String accountId() {
                return appContext == null ? null : AccountSession.get(appContext).accountId();
            }
        };
    }

    /**
     * 转发用的 relay key（`/api/relay/v1/...` 的 Authorization）。
     *
     * <p><b>这是 relay key 在 App 侧唯一的用处。</b>用量、赛季、预算都不用它——
     * 那些走 {@link #relaySession()} 的账号 token。没配中转时返回 null。
     */
    public static String relayKeyForForwarding() {
        return appContext == null ? null : RelayCredentials.get(appContext).relayKey();
    }
    /** 测试要换实现时，用它把缓存清掉。 */
    public static synchronized void reset() {
        usage = null;
        forum = null;
        forumHttp = null;
        budget = null;
    }

    private static void requireReady(Object impl, String what) {
        if (impl == null) {
            throw new UnsupportedOperationException(
                    what + " 还没有实现。要么把 USE_STUBS 打开用桩数据，要么先在 "
                            + "RepositoryProvider 里接上真实现。");
        }
    }
}
