package com.mobilegroup20.tokentrail.data;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import com.mobilegroup20.tokentrail.contract.model.SeasonState;
import com.mobilegroup20.tokentrail.data.repository.SeasonRepository;

/**
 * 游戏钱包的余额来源决策。<b>负责人：游戏侧（刘宗润）。</b>
 *
 * <p>从 `MainActivity` 里单独提出来，是因为这里有一个**产品取舍**需要被单独测到，
 * 而不是埋在一个 900 行的 Activity 里：
 *
 * <pre>
 * 服务端余额可用  → 用服务端的（权威，重装/换设备/多开都不会重复领）
 * 服务端不可用    → 回落到本地演示钱包，**并且标明是演示数据**
 * </pre>
 *
 * <p><b>判据是「有没有配置中转」，不是「余额是不是 0」。</b>
 * 一个启用了中转、但确实还没攒到资源的账号，余额**就该是 0**——
 * 那时候回落到演示钱包会给他一笔凭空来的资源，而且他看不出来。
 * 反过来，没启用中转的账号在服务端根本没有身份，余额必然查不到，
 * 这时回落是对的（否则演示时游戏直接不能玩）。
 *
 * <p><b>为什么需要「回落」这个选项。</b>服务端结算要求账号有用量数据；
 * 没启用中转的人服务端余额恒为 0。如果硬切到服务端，演示时没连服务器
 * 或者没配中转的人会发现什么都建不了。代价是界面上会有两个数字来源——
 * 所以 {@link Source} 必须传到界面上，让人分得清。
 */
public final class SeasonWallet {

    /** 当前余额是哪来的。**必须显示**，否则分不清真实结算和演示数据。 */
    public enum Source {
        /** 服务端结算出来的，权威。 */
        SERVER,
        /** 本地演示数据：没启用中转，服务端没有这个账号的身份。 */
        DEMO,
        /** 正在等服务端。 */
        LOADING
    }

    /** 余额 + 它的来源。 */
    public static final class Wallet {
        public final ResourceBalance balance;
        public final Source source;

        public Wallet(ResourceBalance balance, Source source) {
            this.balance = balance;
            this.source = source;
        }

        public boolean isDemo() {
            return source == Source.DEMO;
        }
    }

    /**
     * 身份的最小接口：**有没有账号、账号是谁**。
     *
     * <p>**故意不直接依赖 {@link RelayCredentials} 或 `AccountSession`**：那两个类的
     * 构造函数都要 `Context`（它们用 Keystore 解密），于是任何用到它的东西都只能在
     * 仪器测试里跑。而这里要测的恰恰是**决策逻辑**——「有账号走服务端、
     * 没有就走演示数据」——那是个纯判断，收窄成两个字符串就能在 JVM 单测里覆盖。
     *
     * <p>2026-02 之前这个接口叫「中转身份」，`uid` 是 relay key 的 sha256。
     * 现在身份是**账号**：没配中转的用户照样有账号、照样该看到自己的余额。
     */
    public interface Identity {
        boolean configured();
        String uid();
    }

    private final Identity credentials;
    private final SeasonRepository seasons;

    public SeasonWallet(Identity credentials, SeasonRepository seasons) {
        this.credentials = credentials;
        this.seasons = seasons;
    }

    /**
     * 用真的本机账号建一个。生产路径走这个。
     *
     * <p>看的是**账号**会话，不是 relay 配置：没配中转的用户也该看到自己的余额，
     * 那是「服务端为唯一数据源」的最小含义。
     */
    public static SeasonWallet forDevice(AccountSession session, SeasonRepository seasons) {
        return new SeasonWallet(new Identity() {
            @Override public boolean configured() { return session.signedIn(); }
            @Override public String uid() { return session.accountId(); }
        }, seasons);
    }

    /** 没登录时服务端不知道你是谁，只能走演示数据。 */
    public boolean relayConfigured() {
        return credentials.configured() && credentials.uid() != null;
    }

    /**
     * 观察余额。
     *
     * <p>`fallback` 只在**没启用中转**时被用到；它是个供应商而不是一个现成对象，
     * 因为演示余额要按当月用量现算（见 `MainActivity.demoBalance`），
     * 而那个计算在服务端可用时根本不该发生。
     */
    public LiveData<Wallet> balance(java.util.function.Supplier<ResourceBalance> fallback) {
        MutableLiveData<Wallet> result = new MutableLiveData<>(new Wallet(new ResourceBalance(), Source.LOADING));
        if (!relayConfigured()) {
            result.setValue(new Wallet(fallback.get(), Source.DEMO));
            return result;
        }
        String uid = credentials.uid();
        // uid 在发起那一刻固定下来：`currentSeason` 内部也会比对身份，
        // 但这里再传一次是为了让「查的是谁」在调用点可见。
        //
        // **不做 `Transformations.map`**：`SeasonRepository.currentSeason` 的契约就是
        // 「失败时给一个空状态」，所以直接把 `SeasonState` 翻成 `Wallet` 就够了，
        // 多一层 map 只会多一个要维护的泛型。
        LiveData<SeasonState> season = seasons.currentSeason(uid);
        return Transformations.map(season, state -> {
            if (state == null) {
                return new Wallet(new ResourceBalance(), Source.LOADING);
            }
            ResourceBalance balance = state.balance == null ? new ResourceBalance() : state.balance;
            // 服务端答了就用服务端的，**包括它答的是 0**——那是「确实没攒到」，
            // 不是「查不到」。回落到演示数据会把这两种情况混成一种。
            return new Wallet(balance, Source.SERVER);
        });
    }

    /** 界面观察它来触发一次重新拉取；值本身没意义（每次取反以触发观察者）。 */
    private final MutableLiveData<Boolean> refreshRequested = new MutableLiveData<>();

    public LiveData<Boolean> refreshSignal() {
        return refreshRequested;
    }

    /**
     * 给 {@link SpendSync} 用的账本适配器。
     *
     * <p>没配中转时返回一个**什么都不做**的账本：服务端没有这个账号的身份，
     * 发过去只会 401，而且那种情况下本地余额本来就是全部，不需要对账。
     */
    public SpendSync.Ledger ledger() {
        if (!relayConfigured()) {
            return new SpendSync.Ledger() {
                @Override public LiveData<Boolean> spend(String uid, ResourceType type, long amount) {
                    return new MutableLiveData<>(Boolean.TRUE);
                }
                @Override public void refresh() {
                }
            };
        }
        String uid = credentials.uid();
        return new SpendSync.Ledger() {
            @Override public LiveData<Boolean> spend(String ignored, ResourceType type, long amount) {
                return seasons.spend(uid, type, amount);
            }
            @Override public void refresh() {
                // 只是通知界面重新拉一次。**这里不做任何本地加减**——
                // 失败可能意味着服务端已经扣了（响应丢了），自己加回去会双重记账。
                refreshRequested.setValue(!Boolean.TRUE.equals(refreshRequested.getValue()));
            }
        };
    }

    /**
     * 把一次消费同步到服务端。
     *
     * <p>返回 `true` 表示服务端也扣成功了。**界面在 `false` 时应该重新拉一次余额**
     * 而不是自己回滚：服务端可能已经扣了（响应丢了），自己回滚会造成
     * 「本地加回来、服务端已经扣了」的双重记账。
     *
     * <p>演示模式下不发请求，直接返回 false——服务端没有这个账号，
     * 发过去只会拿到 401。
     */
    public LiveData<Boolean> syncSpend(com.mobilegroup20.tokentrail.contract.model.ResourceType type,
                                       long amount) {
        MutableLiveData<Boolean> result = new MutableLiveData<>(Boolean.FALSE);
        if (!relayConfigured()) {
            result.setValue(Boolean.FALSE);
            return result;
        }
        return seasons.spend(credentials.uid(), type, amount);
    }
}
