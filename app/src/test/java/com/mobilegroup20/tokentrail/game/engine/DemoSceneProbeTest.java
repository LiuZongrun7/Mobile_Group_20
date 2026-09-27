package com.mobilegroup20.tokentrail.game.engine;

import org.junit.Ignore;
import org.junit.Test;

import java.util.Locale;
import java.util.Random;

/**
 * <b>配平用的仪器，不是回归测试</b>（所以 {@link Ignore} 挂着：它只打印、不断言，
 * 混在测试数里只会让"179 个全过"这句话变虚）。
 *
 * <p>它把 {@code MainActivity.buildDemoScene} 那个演示场面原样搬到电脑上，
 * 用固定 dt 和<b>一串固定种子</b>跑完五波，打印每局的结果。存在的理由是
 * <b>真机量一次要点五分钟，而且每局只给一个样本</b>——进哪一行是随机的，
 * 所以从前那张"演示场面"的表里每一行其实都是一个样本。
 * 现在的演示场面**一个种子都守不住（0/20）**，见 {@code docs/TASKS.md}：
 * 这个场面是给人看美术和摆位的，不是配平过的开局。
 *
 * <p><b>改动 {@link Waves}、{@link EnemyType}、{@link BuildingStats}
 * 或者演示场面之后，把下面那行 {@code @Ignore} 删掉跑一遍</b>，
 * 看守住的比例有没有变。想让它变成真的回归测试，就把最后那句汇总打印
 * 换成一个"至少守住 N/20"的 {@code assertTrue}——但得先配平到守得住。
 */
@Ignore("配平工具：只打印不断言，要量的时候把这行删掉")
public class DemoSceneProbeTest {

    private static final float DT = 1f / 60f;

    /** 和 {@code MainActivity.buildDemoScene} 一模一样（改了那边记得改这边）。 */
    private static Battlefield demoScene() {
        Battlefield field = new Battlefield(BoardGeometry.fit(387f * 2.625f, 643f * 2.625f));
        int cols = field.board().cols();
        int rows = field.board().rows();

        field.place(BuildingType.CORE,
                cols - Battlefield.MOUNTAIN_COLS - BuildingType.CORE.cols,
                (rows - BuildingType.CORE.rows) / 2);
        wallColumn(field, 10, 2, 3, 7, 8, 14, 15);
        wallColumn(field, 22, 4, 5, 11, 12);
        for (int row : new int[]{1, 5, 9, 13}) {
            field.place(BuildingType.TOWER, 30, row);
        }
        for (int row : new int[]{3, 11}) {
            field.place(BuildingType.TOWER, 14, row);
        }
        // 弩车在塔群**后面**（第 32 列），一级/二级/三级各一座。摆法变了要连
        // 上面那两个 upgrade 的坐标一起改，不然升级会静默落空。
        for (int row : new int[]{5, 9, 13}) {
            field.place(BuildingType.BALLISTA, 32, row);
        }
        Building veteran = field.buildingAt(new Cell(14, 3));
        if (veteran != null) {
            field.upgrade(veteran);
        }
        upgradeToLevel(field, new Cell(32, 9), 2);
        upgradeToLevel(field, new Cell(32, 13), 3);
        return field;
    }

    private static void upgradeToLevel(Battlefield field, Cell at, int level) {
        Building building = field.buildingAt(at);
        while (building != null && building.level < level && field.upgrade(building)) {
            // 条件里已经在升级了，循环体留空
        }
    }

    private static void wallColumn(Battlefield field, int col, int... gaps) {
        outer:
        for (int row = 0; row < field.board().rows(); row++) {
            for (int gap : gaps) {
                if (gap == row) {
                    continue outer;
                }
            }
            field.place(BuildingType.WALL, col, row);
        }
    }

    /** 还剩几座**会开火的**。墙和核心不算——它们死光了也照样能输。 */
    private static int towersLeft(Battlefield field) {
        int n = 0;
        for (Building b : field.buildings()) {
            if (BuildingStats.hasRange(b.type)) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void fiveWavesAcrossSeeds() {
        int seeds = 20;
        int defended = 0;
        int[] diedOnWave = new int[Waves.TOTAL_WAVES + 2];

        for (int seed = 1; seed <= seeds; seed++) {
            Battlefield field = demoScene();
            int towersAtStart = towersLeft(field);
            Random random = new Random(seed);

            int wave = 0;
            double seconds = 0;
            while (!field.decided() && wave < Waves.TOTAL_WAVES) {
                if (!field.spawnWave(random)) {
                    break;
                }
                wave++;
                while (!field.enemies().isEmpty() && !field.decided()) {
                    field.advance(DT);
                    seconds += DT;
                }
            }

            if (field.outcome() == Battlefield.Outcome.DEFENDED) {
                defended++;
                diedOnWave[0]++;
            } else {
                diedOnWave[Math.max(1, wave)]++;
            }
            System.out.println(String.format(Locale.US,
                    "PROBE seed=%2d 结果=%-8s 打完 %d 波 漏=%d 塔弩=%d/%d 时长=%.0fs",
                    seed, field.outcome(), wave, field.leaks(),
                    towersLeft(field), towersAtStart, seconds));
        }

        System.out.println(String.format(Locale.US,
                "PROBE 汇总: %d/%d 守住；崩在第 k 波的次数 = %s",
                defended, seeds, java.util.Arrays.toString(diedOnWave)));
    }
}
