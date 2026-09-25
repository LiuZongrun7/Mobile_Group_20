package com.mobilegroup20.tokentrail.game.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 波次曲线：第几波来几只、什么搭配。
 *
 * <p><b>和 {@link EnemyType} 的分工</b>：那边管"一只多硬"，这边管"来几只、怎么搭"。
 * 调难度先动这里（换搭配），不够再动那边（改单只数值）——两者的区别是
 * 前者改的是"这一波的压力形状"，后者改的是整局的手感。
 *
 * <h2>为什么难度靠"换搭配"而不是"堆数量"</h2>
 *
 * <p>塔的 dps 是恒定的，所以二十只杂兵和四只杂兵对玩家其实是同一件事，
 * 多出来的只是排队时间——<b>排队不产生紧张感，只产生等待</b>。
 * 真正让玩家难受的是这三种压力叠在同一条路上：
 *
 * <ul>
 *   <li>第 1 波：纯杂兵。教"一条路一座塔就守得住"；</li>
 *   <li>第 2 波：杂兵翻倍。教"一条路一座塔不够，因为塔一次只打一只"；</li>
 *   <li>第 3 波：加<b>快兵</b>。教"射程里待的时间"这个概念——同样一座塔，
 *       打快兵的时间只有打杂兵的一半；</li>
 *   <li>第 4 波：加<b>重甲</b>。教"一座塔打不死它"，要么两座，要么升到 2 级；</li>
 *   <li>第 5 波：三种一起来，而且<b>重甲在前</b>。</li>
 * </ul>
 *
 * <p><b>顺序是刻意的：重甲在最前、快兵在最后。</b>重甲先到，塔的火力被它吸住；
 * 等塔腾出手来，快兵已经跑过半个战场了。反过来排（快兵先、重甲后）的话，
 * 快兵先被塔清掉，然后塔从容地打重甲——同一批敌人，难度差一倍。
 * {@link #composition} 里的排列顺序就是进场顺序，别随手打乱。
 *
 * <p>纯数据 + 纯函数，不碰 {@link Battlefield}（那个类反过来读这里的曲线）。
 */
public final class Waves {

    /**
     * 打到第几波算守住。
     *
     * <p>有头有尾才叫"一局"：没有终点的话，玩家唯一能做的事就是等自己输，
     * 而"守住五波"是可以赢的——赢了才有"这一季守住了"这句话可说，
     * 也才对得上赛季那条线。
     *
     * <p><b>5 是个演示用的数</b>，不是设计结论。真机上一波大约 30 秒，
     * 五波连打带摆大约三分钟，正好是一段能当场演示完的长度。
     */
    public static final int TOTAL_WAVES = 5;

    /** 第 1 波几只杂兵。后面按 {@link #composition} 里的表走。 */
    private static final int[][] CURVE = {
            // {杂兵, 快兵, 重甲}
            {4, 0, 0},    // 1：一条路一座塔就守得住
            {6, 0, 0},    // 2：塔一次只打一只，这里第一次感觉到
            {6, 3, 0},    // 3：快兵：射程里待的时间只有一半
            {8, 3, 2},    // 4：重甲：一座 1 级塔打不死
            {10, 4, 2},   // 5：三种一起上
    };

    private Waves() {
    }

    // 表比 TOTAL_WAVES 短的话，clamp 会把波数夹到一个没有行的下标上，
    // 于是"最后一波"直接抛数组越界——而那时候已经打了几分钟了。
    // 宁可类一加载就炸：改这两个数的人马上就知道自己漏了什么。
    static {
        if (CURVE.length != TOTAL_WAVES) {
            throw new IllegalStateException("波次表和 TOTAL_WAVES 对不上：表 " + CURVE.length
                    + " 行，TOTAL_WAVES 是 " + TOTAL_WAVES);
        }
    }

    /**
     * 第 {@code wave} 波来哪些敌人，<b>顺序就是进场顺序</b>。
     *
     * <p>波数越界（0、负数、超过 {@link #TOTAL_WAVES}）夹到范围内，
     * 不抛异常：这个数是从"发了几波"推出来的，为一个不该出现的值把整局搞崩
     * 不值得，而夹一下的结果（发最后那一波）也不算离谱。
     */
    public static List<EnemyType> composition(int wave) {
        int[] counts = CURVE[clamp(wave) - 1];
        List<EnemyType> lineup = new ArrayList<>();
        // 重甲在前、杂兵居中、快兵压尾。理由见类注释——这个顺序是难度的一半。
        add(lineup, EnemyType.BRUTE, counts[2]);
        add(lineup, EnemyType.GRUNT, counts[0]);
        add(lineup, EnemyType.RUNNER, counts[1]);
        return Collections.unmodifiableList(lineup);
    }

    /** 第 {@code wave} 波总共几只。 */
    public static int size(int wave) {
        return composition(wave).size();
    }

    /** 这一波是不是最后一波。界面用它决定按钮文案。 */
    public static boolean isLast(int wave) {
        return clamp(wave) >= TOTAL_WAVES;
    }

    /**
     * 这一波打完了没有——发到第几波、场上还有没有敌人。
     *
     * <p>留在这儿而不是 {@link Battlefield} 里：判断里那个"第几波"的边界
     * （{@link #TOTAL_WAVES}）是曲线的一部分，写两处迟早会不一致。
     */
    public static boolean allCleared(int wavesStarted, int enemiesLeft) {
        return wavesStarted >= TOTAL_WAVES && enemiesLeft <= 0;
    }

    private static void add(List<EnemyType> into, EnemyType type, int count) {
        for (int i = 0; i < count; i++) {
            into.add(type);
        }
    }

    private static int clamp(int wave) {
        if (wave < 1) {
            return 1;
        }
        return Math.min(wave, TOTAL_WAVES);
    }
}
