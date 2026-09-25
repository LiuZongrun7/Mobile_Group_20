package com.mobilegroup20.tokentrail.data;

import android.content.Context;

import androidx.room.Room;

import com.mobilegroup20.tokentrail.data.local.AppDatabase;
import com.mobilegroup20.tokentrail.data.local.BundledPricingSource;
import com.mobilegroup20.tokentrail.data.local.RoomUsageRepository;
import com.mobilegroup20.tokentrail.data.repository.AdviceRepository;
import com.mobilegroup20.tokentrail.data.repository.BudgetRepository;
import com.mobilegroup20.tokentrail.data.repository.ForumRepository;
import com.mobilegroup20.tokentrail.data.repository.SeasonRepository;
import com.mobilegroup20.tokentrail.data.repository.UsageRepository;
import com.mobilegroup20.tokentrail.data.stub.StubBudgetRepository;
import com.mobilegroup20.tokentrail.data.stub.StubForumRepository;
import com.mobilegroup20.tokentrail.data.stub.StubUsageRepository;

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
    private static BudgetRepository budget;

    /**
     * 应用级的 Context，只用来建数据库。
     *
     * <p>传进来的必须是 Application 的，不能是 Activity 的——它存在静态字段里，
     * 存 Activity 就是把一个会销毁的对象钉住不放。{@code TokenTrailApp} 负责传对的这个。
     */
    private static Context appContext;
    private static AppDatabase database;
    private static PricingSource pricing;

    private RepositoryProvider() {
    }

    /**
     * 由 {@code TokenTrailApp.onCreate} 调用，全项目只调这一次。
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
                        "RepositoryProvider 还没初始化。正常情况下 TokenTrailApp.onCreate "
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

    /** 用量数据。桩 → {@code com.mobilegroup20.tokentrail.data.local.RoomUsageRepository}。 */
    public static synchronized UsageRepository usage() {
        if (usage == null) {
            usage = USE_STUBS
                    ? new StubUsageRepository()
                    : new RoomUsageRepository(database(), pricing());
            requireReady(usage, "UsageRepository（数据侧）");
        }
        return usage;
    }

    /** 论坛数据。桩 → {@code com.mobilegroup20.tokentrail.data.remote.FirestoreForumRepository}。 */
    public static synchronized ForumRepository forum() {
        if (forum == null) {
            forum = USE_STUBS ? new StubForumRepository() : null;
            requireReady(forum, "ForumRepository（论坛侧，汪庭栋）");
        }
        return forum;
    }

    /** 预算数据。桩 → {@code com.mobilegroup20.tokentrail.data.local.RoomBudgetRepository}。 */
    public static synchronized BudgetRepository budget() {
        if (budget == null) {
            budget = USE_STUBS ? new StubBudgetRepository() : null;
            requireReady(budget, "BudgetRepository（数据侧，张莉）");
        }
        return budget;
    }

    /**
     * 赛季与结算（游戏侧，刘宗润）——<b>还没实现</b>。
     *
     * <p>这里故意抛异常而不是返回 null：谁先用到它，谁就会在第一次运行时拿到一句
     * 说清楚该去写哪个类的报错，而不是一个莫名其妙的空指针。
     */
    public static SeasonRepository season() {
        throw new UnsupportedOperationException(
                "SeasonRepository 还没实现。实现类放在 com.mobilegroup20.tokentrail.data.local，"
                        + "然后在这里返回。结算的四条规则见 SeasonState 的类注释。");
    }

    /** 建议 agent 的调用口（游戏侧，刘宗润）——<b>还没实现</b>。 */
    public static AdviceRepository advice() {
        throw new UnsupportedOperationException(
                "AdviceRepository 还没实现。实现类放在 com.mobilegroup20.tokentrail.data.remote，"
                        + "负责取 Firebase ID token 并请求 Java 建议服务。");
    }

    /** 测试要换实现时，用它把缓存清掉。 */
    public static synchronized void reset() {
        usage = null;
        forum = null;
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
