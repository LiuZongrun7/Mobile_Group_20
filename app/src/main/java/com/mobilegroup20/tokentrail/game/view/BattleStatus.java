package com.mobilegroup20.tokentrail.game.view;

import com.mobilegroup20.tokentrail.game.engine.Battlefield;
import com.mobilegroup20.tokentrail.game.engine.Building;
import com.mobilegroup20.tokentrail.game.engine.BuildingStats;
import com.mobilegroup20.tokentrail.game.engine.BuildingType;
import com.mobilegroup20.tokentrail.game.engine.Waves;

/**
 * 战况快照：某一刻"该让玩家知道的几个数"。
 *
 * <p><b>为什么要有这么一个类，而不是把回调写成七个参数。</b>
 * 界面要知道的是"这一局现在什么情况"这一件事，而它由七个数字拼出来
 * （几波了、还剩几只、漏了几只、核心还有多少血、打赢了没有……）。
 * 摊成七个参数的话，每加一个数（比如以后要显示"这一波是什么搭配"）
 * 就要改所有实现方的签名；而且调用方拿到七个裸数字，很容易把
 * "第几波"和"还剩几波"接反——那种错编译器不会说话。
 *
 * <p><b>是个值对象，不是活的。</b>它只是"某一刻"的抄本，{@link Battlefield}
 * 继续往前跑不会让它变。界面把它存下来慢慢渲染是安全的；反过来，
 * 想拿新数字就得重新 {@link #of} 一份。<b>不要缓存它当状态用</b>——
 * 这和 {@code Building.target} 是同一条规矩：抄本可以留着看，判断得问原件。
 *
 * <p>纯数据、没有 android 依赖，所以它的 {@link #of} 能在单元测试里直接跑。
 */
public final class BattleStatus {

    /** 场上还剩几座建筑（含核心）。 */
    public final int buildings;

    /** 场上还剩几只敌人。<b>含还没进场的那部分</b>——它们已经在战场上了，
     *  只是 {@code x} 还是负的，所以"还剩几只"和玩家看到的画面会对得上。 */
    public final int enemies;

    /** 已经发到第几波。0 = 一波都没发。 */
    public final int waves;

    /** 累计漏了几只（摸到核心的 + 从右边走出去的）。 */
    public final int leaks;

    /** 核心还剩多少耐久。核心已经没了时是 0。 */
    public final float coreHp;

    /**
     * 核心的满耐久。<b>跟着核心的等级走</b>——升级核心之后 HUD 上那行应该是
     * "600/900"，不是"600/600"。
     *
     * <p><b>核心没了的时候仍然是满值，不是 0。</b>两个都写 0 的话，
     * 界面上那行会从 "180/600" 跳成 "0/0"——数字看着像出了错，
     * 而不是"核心被打没了"。分母留着，"0/600" 才是句人话。
     */
    public final float coreMaxHp;

    /** 这一局是输是赢，还是还在打。 */
    public final Battlefield.Outcome outcome;

    public BattleStatus(int buildings, int enemies, int waves, int leaks,
                        float coreHp, float coreMaxHp, Battlefield.Outcome outcome) {
        this.buildings = buildings;
        this.enemies = enemies;
        this.waves = waves;
        this.leaks = leaks;
        this.coreHp = coreHp;
        this.coreMaxHp = coreMaxHp;
        this.outcome = outcome == null ? Battlefield.Outcome.ONGOING : outcome;
    }

    /**
     * 读一份当前战况。
     *
     * <p>核心的耐久在这里取整——血条按 {@code 480/600} 显示，需要的是"还剩多少点"
     * 这个整数；引擎里那个 {@code float} 是给连续伤害用的，直接把
     * {@code 479.99998} 端到界面上会变成一串没意义的数字。
     */
    public static BattleStatus of(Battlefield field) {
        Building core = field.core();
        float hp = core == null ? 0f : Math.max(0f, core.hp);
        // 核心没了（输掉那一帧之后）就问不到它的等级了，退回一级的满值：
        // 那正是它被打掉时的分母，所以 "0/600" 和打掉之前的 "180/600" 是同一个基准。
        float maxHp = core == null
                ? BuildingStats.maxHp(BuildingType.CORE, 1)
                : core.maxHp();
        return new BattleStatus(
                field.buildings().size(),
                field.enemies().size(),
                field.waves(),
                field.leaks(),
                Math.round(hp),
                maxHp,
                field.outcome());
    }

    /** 一共几波。界面上写 "Wave 2/5" 用的就是它。 */
    public int totalWaves() {
        return Waves.TOTAL_WAVES;
    }

    /** 还有没有下一波可发。发完了（或者这一局已经结束）界面就该换按钮文案。 */
    public boolean moreWavesToCome() {
        return waves < Waves.TOTAL_WAVES && outcome == Battlefield.Outcome.ONGOING;
    }

    /** 现在是不是有一波正在场上。有的话"开始波次"这个按钮按下去没用。 */
    public boolean waveRunning() {
        return enemies > 0;
    }

    /** 这一局结束了没有（赢了或输了）。 */
    public boolean decided() {
        return outcome != Battlefield.Outcome.ONGOING;
    }

    /** 还剩几成核心耐久，0–1。0/0 时返回 0，不会除出个 NaN。 */
    public float coreFraction() {
        if (coreMaxHp <= 0f) {
            return 0f;
        }
        float fraction = coreHp / coreMaxHp;
        if (fraction < 0f) {
            return 0f;
        }
        return Math.min(fraction, 1f);
    }

    @Override
    public String toString() {
        return "BattleStatus{wave=" + waves + "/" + Waves.TOTAL_WAVES
                + ", enemies=" + enemies + ", leaks=" + leaks
                + ", core=" + (int) coreHp + "/" + (int) coreMaxHp
                + ", outcome=" + outcome + "}";
    }
}
