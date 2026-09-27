package com.mobilegroup20.tokentrail.game.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link Projectile} 的测试：一颗子弹自己"飞得对不对"。
 *
 * <p>战场上"什么时候开火、打谁、打中了扣谁的血"在 {@code BattlefieldTest} 里。
 * 这里只有子弹、一个发射者和一个目标，没有战场——所以这里的目标<b>不会自己动</b>，
 * 想让它动就手动推（战场上那一步是 {@code Battlefield.moveEnemies} 干的）。
 *
 * <p>盯的是这套模型唯一会崩的地方：<b>追不上目标</b>。
 * 追着打的子弹一旦比目标慢，伤害就永远不落地（子弹吊在屁股后面飞，
 * 塔看着在开火、敌人一滴血不掉）。所以"子弹比最快的敌人快"是
 * {@link BuildingStats#PROJECTILE_SPEED_CELLS_PER_SEC} 的硬要求，
 * 这里有一条测试专门盯着它。
 */
public class ProjectileTest {

    /**
     * 发射者。子弹从哪儿出膛是 {@code Battlefield} 传进来的坐标，和这个引用无关，
     * 所以随便造一座塔当"谁打的"就够了。
     */
    private static final Building TOWER = new Building(BuildingType.TOWER, 5, 3);

    /** 一根弩箭：没有溅射。 */
    private static Projectile shotAt(Enemy target, float fromX, float fromY) {
        return new Projectile(TOWER, target, fromX, fromY, 14f,
                BuildingStats.PROJECTILE_SPEED_CELLS_PER_SEC, 0f);
    }

    /** 一发炮弹：落地炸一格。 */
    private static Projectile shellAt(Enemy target, float fromX, float fromY) {
        return new Projectile(TOWER, target, fromX, fromY, 14f,
                BuildingStats.PROJECTILE_SPEED_CELLS_PER_SEC,
                BuildingStats.CANNON_SPLASH_CELLS);
    }

    /** 飞够时间才到，而且到了就停在瞄点上，不冲过头。 */
    @Test
    public void itArrivesAfterDistanceOverSpeedSeconds() {
        Enemy target = new Enemy(EnemyType.GRUNT, 4, 10.5f);
        Projectile shot = shotAt(target, 6f, 4.5f);   // 4.5 格远，9 格/秒 → 0.5 秒

        assertFalse("0.35 秒只飞了 3.15 格，还差得远", shot.step(0.35f));
        assertTrue("再飞 0.2 秒（1.8 格）就够了", shot.step(0.2f));

        assertEquals(target.centreX(), shot.x, 1e-4f);
        assertEquals(target.centreY(), shot.y, 1e-4f);
        assertTrue("目标还活着，这一发算数", shot.lands());
    }

    /** 一帧特别长的时候也只飞到瞄点，不会冲过去再回头——那在画面上是抖一下。 */
    @Test
    public void itStopsOnTheAimPointEvenWithAHugeStep() {
        Enemy target = new Enemy(EnemyType.GRUNT, 4, 10.5f);
        Projectile shot = shotAt(target, 6f, 4.5f);

        assertTrue("一帧十秒：够飞 90 格，但只该飞到目标那儿", shot.step(10f));

        assertEquals("落在瞄点上", 10.5f, shot.x, 1e-4f);
        assertEquals(4.5f, shot.y, 1e-4f);
    }

    /**
     * 目标拐弯了，子弹跟着拐——它是"追着打"，不是"朝一个方向飞出去"。
     *
     * <p>这条不能省：敌人现在会上下换行走 {@link PathField}，固定方向的子弹
     * 会在它换行的那几帧擦身而过。而"差一点点没打中"在塔防里没法玩——
     * 玩家控制不了，塔的伤害也就没法手算了。
     */
    @Test
    public void itChasesTheTargetInsteadOfFlyingInAStraightLine() {
        Enemy target = new Enemy(EnemyType.GRUNT, 0, 8.5f);   // 在第 0 行，左上方
        Projectile shot = shotAt(target, 6f, 4.5f);

        assertFalse(shot.step(0.1f));
        float afterFirstStep = shot.y;
        assertTrue("第一段朝第 0 行飞：y 该在变小", afterFirstStep < 4.5f);

        target.y = 12.5f;   // 目标换到下面那一行去了（战场上就是它拐弯了）
        assertFalse("还没到", shot.step(0.05f));
        assertTrue("瞄点换了方向，子弹也得改方向", shot.y > afterFirstStep);
    }

    /**
     * 目标半路死了：把剩下的路飞完，落在它倒下的地方，但不算数。
     *
     * <p>"当场消失"是不行的——一颗子弹在屏幕中间凭空不见看着像 bug；
     * 而"飞完但不扣血"正好是以后加溅射要的形状：结算的是<b>落点</b>周围有谁。
     */
    @Test
    public void itFliesOnToTheLastKnownSpotWhenTheTargetDiesOnTheWay() {
        Enemy target = new Enemy(EnemyType.GRUNT, 4, 10.5f);
        Projectile shot = shotAt(target, 6f, 4.5f);
        assertFalse(shot.step(0.35f));

        target.hp = 0f;   // 别的塔抢先打死了它
        float deathX = target.centreX();
        float deathY = target.centreY();

        assertFalse("还差一点点", shot.step(0.05f));
        assertTrue("还是飞到了", shot.step(0.5f));

        assertEquals("落点是它倒下的地方，不是别处", deathX, shot.aimX(), 1e-4f);
        assertEquals(deathY, shot.aimY(), 1e-4f);
        assertFalse("那儿已经没有活人了", shot.lands());
    }

    /**
     * <b>追得上最快的敌人。</b>
     *
     * <p>速度那条规矩的另一半（{@link BuildingStats#PROJECTILE_SPEED_CELLS_PER_SEC}
     * 里写了上半条）：快兵 1.6 格/秒，子弹 9 格/秒，所以它跑不掉。
     * 有人把子弹调慢、或者加一种更快的敌人时，这条会先响。
     */
    @Test
    public void itCatchesTheFastestEnemyEvenWhenItIsFleeing() {
        Enemy runner = new Enemy(EnemyType.RUNNER, 4, 20f);   // 背对着跑，起手就领先 14 格
        Projectile shot = shotAt(runner, 6f, 4.5f);

        boolean arrived = false;
        for (int frame = 0; frame < 300 && !arrived; frame++) {   // 最多 6 秒
            runner.x += EnemyType.RUNNER.speedCellsPerSec * 0.02f;
            arrived = shot.step(0.02f);
        }

        assertTrue("子弹追不上快兵的话，塔就永远打不死它", arrived);
        assertTrue(shot.lands());
    }
}
