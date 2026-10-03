package com.mobilegroup20.modelpilot.data;

import android.content.Context;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.data.remote.HttpForumRepository;
import com.mobilegroup20.modelpilot.data.repository.ForumFeedRepository;
import com.mobilegroup20.modelpilot.data.repository.SessionProvider;

import androidx.room.Room;

import com.mobilegroup20.modelpilot.data.local.AppDatabase;
import com.mobilegroup20.modelpilot.data.local.BundledPricingSource;
import com.mobilegroup20.modelpilot.data.local.RoomUsageRepository;
import com.mobilegroup20.modelpilot.data.repository.ForumRepository;
import com.mobilegroup20.modelpilot.data.repository.UsageRepository;
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

    /**
     * 应用级的 Context，只用来建数据库。
     *
     * <p>传进来的必须是 Application 的，不能是 Activity 的——它存在静态字段里，
     * 存 Activity 就是把一个会销毁的对象钉住不放。{@code ModelPilotApp} 负责传对的这个。
     */
    private static Context appContext;
    private static AppDatabase database;
    private static PricingSource pricing;
    private static com.mobilegroup20.modelpilot.chat.ProviderRegistry providers;
    private static com.mobilegroup20.modelpilot.chat.CallLedger ledger;

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
                    .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                            AppDatabase.MIGRATION_3_4)
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
    /**
     * 对话库（项目 / 对话 / 消息 / 记忆）。
     *
     * <p>**这是新主线的本地存储**：key 在手机、调用从手机发出去，所以对话与记忆
     * 也全在本机（见 `docs/CHAT_ENGINE.md` §1）。服务端一张表都不加。
     */
    public static com.mobilegroup20.modelpilot.chat.local.ChatDao chats() {
        return database().chatDao();
    }

    /**
     * 六家 provider 的能力表（`ProviderRegistry.defaults()` + 用户在设置里改过的 base URL）。
     *
     * <p>**base URL 的覆盖在这里统一贴上去**，而不是让每个调用点自己去问
     * {@link ProviderKeys}：漏一处就会出现"设置里改了地址、某条路径还往老地址发"，
     * 而这种错很难发现（老地址一般也通，只是不生效）。
     *
     * <p>用户在设置里改完地址后要调 {@link #reloadProviders()}，否则拿到的还是旧的那份。
     */
    public static synchronized com.mobilegroup20.modelpilot.chat.ProviderRegistry providers() {
        if (providers == null) {
            com.mobilegroup20.modelpilot.chat.ProviderRegistry registry =
                    com.mobilegroup20.modelpilot.chat.ProviderRegistry.defaults();
            if (appContext != null) {
                for (com.mobilegroup20.modelpilot.chat.ProviderSpec spec : registry.providers()) {
                    String url = ProviderKeys.baseUrl(appContext, spec.providerId);
                    if (url != null && !url.isEmpty()) {
                        registry = registry.withBaseUrl(spec.providerId, url);
                    }
                }
            }
            providers = registry;
        }
        return providers;
    }

    /** 设置里改了 base URL、或存/删了 key 之后调它；下一次 {@link #providers()} 重新算。 */
    public static synchronized void reloadProviders() {
        providers = null;
    }

    /**
     * 本机账本的写入端（每次模型调用落一行，见 `docs/CHAT_ENGINE.md` §4）。
     *
     * <p>**算金额用的价目表和 `usage()` 是同一份**：两处各拿一份的话，
     * 同一次调用在账本里和 Insights 上可能按两版费率算出两个数。
     */
    public static synchronized com.mobilegroup20.modelpilot.chat.CallLedger ledger() {
        if (ledger == null) {
            ledger = new com.mobilegroup20.modelpilot.chat.CallLedger(pricing(),
                    database().usageCallDao());
        }
        return ledger;
    }

    public static synchronized UsageRepository usage() {        if (usage == null) {
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

    /** 测试要换实现时，用它把缓存清掉。 */
    public static synchronized void reset() {
        usage = null;
        forum = null;
        forumHttp = null;
    }

    private static void requireReady(Object impl, String what) {
        if (impl == null) {
            throw new UnsupportedOperationException(
                    what + " 还没有实现。要么把 USE_STUBS 打开用桩数据，要么先在 "
                            + "RepositoryProvider 里接上真实现。");
        }
    }
}
