package com.mobilegroup20.tokentrail.game.engine;

import java.util.Locale;

/**
 * 一颗正在飞的子弹。
 *
 * <p>塔不再是"持续照射"（{@code Battlefield} 从前每帧直接扣 {@code dps * dt}），
 * 而是<b>每隔几秒吐一颗</b>：伤害从"按帧扣"变成"打中了才扣"。这一颗就是那件事的载体——
 * 它在哪儿、飞多快、打谁，都在这里。
 *
 * <h2>它是"追着打"的，不是"朝一个方向飞出去"</h2>
 *
 * <p>每一帧重新读一次目标的当前位置（{@link #step}），所以敌人拐弯、换行、
 * 加速都甩不掉它。<b>这不是偷懒，是为了让"打不打得到"变成一个能算的问题</b>：
 * 追着打的话，命中只取决于"子弹比敌人快"，而子弹 9 格/秒、最快的快兵 1.6 格/秒，
 * 所以<b>出膛就等于会命中</b>（目标先死除外）——塔的伤害因此还是可以用
 * "每发多少 × 打了几发"手算，不会变成"看运气"。
 *
 * <p>真按固定方向飞、靠碰撞判定的话，敌人正在换行时就会有擦身而过的一发，
 * 而"差一点点没打中"在塔防里既不有趣（玩家没法控制）也说不清（同样的局面
 * 帧率不同结果不同）。
 *
 * <h2>目标半路死了怎么办：把剩下的路飞完，然后消失，不掉血</h2>
 *
 * <p>不"当场消失"是因为那看着像 bug（一颗子弹在屏幕中间凭空不见）；飞完剩下的路
 * 是"打到那个地方"，只是那儿已经没有活人了。
 *
 * <p>这也正是<b>以后加溅射/减速的位置</b>：现在结算的是"落点上那个目标还在不在"，
 * 要加范围伤害就是"落点周围有谁"。所以 {@link #aimX()}／{@link #aimY()} 这两个
 * "打到哪儿"的坐标是公开的，不是内部细节。
 *
 * <p>纯 Java，不 import 任何 {@code android.*}：子弹飞得对不对要能在电脑上验，
 * 不用装模拟器。渲染读 {@link #x}／{@link #y} 自己换算成像素，见
 * {@code BattlefieldView.drawProjectiles}。
 */
public final class Projectile {

    /**
     * 谁打的。
     *
     * <p>现在只用来看（"这一发是哪座塔的"），但一滴血都不该由它决定——
     * 塔被打掉之后天上还会飞着它最后那几发，那是合理的，不该跟着消失。
     */
    public final Building owner;

    /**
     * 出膛时瞄的那一只。
     *
     * <p><b>会中途死掉。</b>它死了之后这一发不再改瞄点，把剩下的路飞完，
     * 但结算时不扣任何人的血（见类注释）。所以读它之前一律要先问
     * {@link Enemy#alive()}，不能假定它还在场上。
     */
    public final Enemy target;

    /** 命中扣多少血。出膛时就定死了：塔在半路上被拆掉也不影响已经在天上的这一发。 */
    public final float damage;

    /** 飞多快，格/秒。见 {@link BuildingStats#PROJECTILE_SPEED_CELLS_PER_SEC}。 */
    public final float speedCellsPerSec;

    /**
     * 现在在哪儿，<b>连续格子坐标</b>（和 {@link Enemy#x} 同一套，身体中心）。
     *
     * <p>可写：{@link #step} 每帧推它。渲染读它换算成像素——
     * <b>不要在这里存像素</b>，理由见 {@link BoardGeometry} 的"四条规矩"。
     */
    public float x;

    /** 见 {@link #x}。 */
    public float y;

    /** 目标最后已知的位置。目标死了之后就朝这儿把剩下的路飞完。 */
    private float aimX;
    private float aimY;

    /**
     * 造一发。只有 {@link Battlefield} 该调——
     * 子弹是"塔开火"这件事的结果，而开火的时机由 {@code Battlefield} 的帧循环说了算。
     */
    Projectile(Building owner, Enemy target, float x, float y,
               float damage, float speedCellsPerSec) {
        this.owner = owner;
        this.target = target;
        this.x = x;
        this.y = y;
        this.damage = damage;
        this.speedCellsPerSec = speedCellsPerSec;
        this.aimX = target != null ? target.centreX() : x;
        this.aimY = target != null ? target.centreY() : y;
    }

    /**
     * 追一帧。
     *
     * <p>流程就两句：<b>先看目标还在不在</b>（在就更新瞄点，不在就沿用最后那个），
     * 然后朝瞄点飞这一帧能飞的距离。
     *
     * @param dtSeconds 这一帧多少秒
     * @return <b>飞到了</b>返回 {@code true}（调用方该结算并把它移走了）；
     *         还在路上返回 {@code false}
     */
    boolean step(float dtSeconds) {
        if (target != null && target.alive()) {
            aimX = target.centreX();
            aimY = target.centreY();
        }

        float dx = aimX - x;
        float dy = aimY - y;
        float distance = (float) Math.hypot(dx, dy);
        float budget = speedCellsPerSec * dtSeconds;

        // 这一帧的路够走到瞄点：贴上去，报告"到了"。
        // 多出来的那点路不留给下一发——它会把子弹带着越过目标再回头，看着像抖。
        if (distance <= budget || distance < 1e-4f) {
            x = aimX;
            y = aimY;
            return true;
        }

        x += dx / distance * budget;
        y += dy / distance * budget;
        return false;
    }

    /**
     * 打到哪儿（连续格子坐标）。
     *
     * <p>目标活着的时候是它现在的位置，死了之后是它最后那个位置——
     * 也就是"这一发的落点"。加溅射时从这里往外找。
     */
    public float aimX() {
        return aimX;
    }

    /** 见 {@link #aimX()}。 */
    public float aimY() {
        return aimY;
    }

    /**
     * 这一发还算数吗——落点上那个目标是不是还喘气。
     *
     * <p>调用方（{@code Battlefield.moveProjectiles}）靠它决定"到了之后扣不扣血"。
     * 写成方法而不是让调用方自己拼 {@code target != null && target.alive()}：
     * 这个判断在结算那条路上只该有一份。
     */
    boolean lands() {
        return target != null && target.alive();
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%s 的一发(%.1f 伤, 从 %.2f,%.2f 打向 %s)",
                owner == null ? "?" : owner.type.label, damage, x, y,
                aimX + "," + aimY);
    }
}
