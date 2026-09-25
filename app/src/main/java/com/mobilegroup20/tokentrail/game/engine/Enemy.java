package com.mobilegroup20.tokentrail.game.engine;

import java.util.Locale;

/**
 * 一个正在往右走的敌人。
 *
 * <p>位置用<b>连续格子坐标</b>（{@code x} 可以是小数、可以是负数）：
 * {@code x = 4.5} 表示它正站在第 4 格的中心，{@code x = -1.2} 表示还没进场。
 * <b>不要存像素</b>——那样换个屏幕敌人就跑到别的地方了（见
 * {@link BoardGeometry} 的"四条规矩"）。
 *
 * <p>{@code lane} 是它出场的那一行。**出场之后它会换行**：敌人要走到核心跟前，
 * 而核心只在其中几行上，所以路上它会上下走（见 {@link PathField}）。
 * 现在的位置是连续坐标 {@link #x}／{@link #y}，行号由 {@link #lane()} 算出来。
 *
 * <h2>血量是"这一只还剩多少"，不是"这种敌人有多厚"</h2>
 *
 * <p>满血在 {@link EnemyType#maxHp}（那是种类的属性），这里的 {@link #hp}
 * 是它被打过之后剩下的。两件事分开，是因为"这只还剩 12 点血"这种话
 * 只有 {@code hp} 说得出来，而"这种敌人一共 45 点"永远该问类型。
 */
public final class Enemy {

    /** 哪一种敌人：决定满血、速度、咬人有多疼。 */
    public final EnemyType type;

    /** 连续格子坐标的 x。<b>可写</b>：{@link Battlefield#advance} 每帧推它。 */
    public float x;

    /**
     * 连续格子坐标的 y——身体中心的<b>行</b>坐标。第 r 行的中心是 {@code r + 0.5}。
     *
     * <p><b>可写，而且会变。</b>敌人不是只往右走的：它要走到核心跟前，
     * 所以路上会上下换行（见 {@link PathField}）。写成 {@code final int lane}
     * 的时候，"走到一半正在换行"这件事没法表示——要么瞬移一整格，
     * 要么把行数四舍五入，两种都会让画面上看到跳。
     *
     * <p>和 {@link #x} 是同一套坐标、同一个约定（都是身体中心，都可以是小数、
     * 也可以是负数）。<b>不要存像素</b>，理由见 {@link BoardGeometry} 的"四条规矩"。
     */
    public float y;

    /**
     * 还剩多少血。<b>可写</b>：{@link Battlefield#advance} 里被塔扣。
     *
     * <p>掉到 0 以下不用自己处理——清场那一步统一把不喘气的移走，
     * 在那儿之前 {@link #alive()} 已经是 {@code false}，塔和敌人都不会再理它。
     */
    public float hp;

    /**
     * 是不是被挡住了。<b>现在等于"正在拆身边最近的那一座"</b>——挡路的东西
     * 一定是建筑，所以停下来就一定是开始啃；啃的是谁见
     * {@code Battlefield.nearestBite}（就近咬，不分种类，墙除外）。
     *
     * <p>判断逻辑在 {@link Battlefield#advance} 里；渲染用它把身体画成
     * <b>压暗的同色</b>（{@code BattlefieldView.drawEnemy}），读作"卡住了"。
     *
     * <p><b>刻意不换颜色</b>：颜色在回答"这是哪一种敌人"，而敌人贴着你的墙
     * 啃的那几秒恰恰是最需要看清种类的时候。
     */
    public boolean blocked;

    /**
     * 有没有走到过核心跟前。
     *
     * <p>只为一件事：漏怪计数要<b>只记一次</b>。敌人摸到核心之后会一直站在那儿啃
     * （它是"停下来打"，不是"摸一下就消失"），每帧都数一下的话，
     * 一只敌人能数出几百个"漏了"。
     */
    public boolean reachedCore;

    public Enemy(EnemyType type, int lane, float x) {
        this.type = type;
        this.x = x;
        this.y = lane + 0.5f;   // 行号 → 那一行的中心
        this.hp = type.maxHp;
    }

    /**
     * 它现在压在哪一行（身体中心所在的那一格）。
     *
     * <p><b>是算出来的，不是存的。</b>存一份"当前行"就多一个要和 {@link #y}
     * 保持同步的东西，而上一次同步没跟上就是"画在第 5 行、判定按第 4 行"这种 bug。
     * 需要行号的地方（占位表、地形分区）都从这儿拿。
     */
    public int lane() {
        return (int) Math.floor(y);
    }

    /** 满血是多少。 */
    public float maxHp() {
        return type.maxHp;
    }

    /** 还活着吗。0 血算死。 */
    public boolean alive() {
        return hp > 0f;
    }

    /** 剩几成血，0–1。<b>已经死了返回 0</b>，不会返回负数——血条按它画。 */
    public float hpFraction() {
        float max = type.maxHp;
        if (max <= 0f) {
            return 0f;
        }
        float fraction = hp / max;
        if (fraction < 0f) {
            return 0f;
        }
        return Math.min(fraction, 1f);
    }

    /** 它现在压在哪一列（还没进场时是负数）。 */
    public int col() {
        return (int) Math.floor(x);
    }

    /** 它现在压在哪一格。 */
    public Cell cell() {
        return new Cell(col(), lane());
    }

    /**
     * 它身体的中心在哪，<b>连续格子坐标</b>。
     *
     * <p>{@code x}／{@code y} 本来就是中心（{@code x = 4.5} 站在第 4 格正中），
     * 塔算距离用的就是它，和 {@code Building.centre()} 是同一套坐标，
     * 别一个用格一个用像素。
     */
    public float centreX() {
        return x;
    }

    /** 见 {@link #centreX()}。 */
    public float centreY() {
        return y;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%s{cell=%s x=%.2f y=%.2f hp=%.0f/%.0f%s}",
                type.label, cell(), x, y, hp, type.maxHp, blocked ? ", blocked" : "");
    }
}
