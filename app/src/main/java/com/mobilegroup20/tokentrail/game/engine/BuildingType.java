package com.mobilegroup20.tokentrail.game.engine;

/**
 * 建筑种类和它的<b>占地</b>。
 *
 * <p>占地是这一版玩法的核心参数：塔占 2×2、核心占 3×3、城墙占 1×1。
 * 占地同时决定三件事——能不能放下（{@link BoardGeometry#fits}）、
 * 贴图多宽（占地格数 × 256px，见 {@code docs/ART.md} §2.1）、
 * 以及锚点在哪（占地矩形的底边中点）。
 *
 * <p><b>这里故意没有价格字段。</b>价格是随时要调的平衡参数，不是建筑自身的性质
 * （见 {@code docs/CONTRACTS.md} §8）：它住在 {@link ShopCatalog}，那里一件商品
 * 可以说"吃两种资源"、也可以说"免费"。改价格只动那个文件。
 */
public enum BuildingType {

    /** 核心：被攻破就输。放一个，占 3×3。 */
    CORE("Core", 3, 3, 1.0f),

    /** 防御塔：占 2×2。目前只有一种塔。 */
    TOWER("Tower", 2, 2, 1.5f),

    /** 城墙：占 1×1，横竖都用同一张贴图。挡路用。 */
    WALL("Wall", 1, 1, 0.5f);

    /** 界面和日志里显示的名字。 */
    public final String label;

    /** 占几列。 */
    public final int cols;

    /** 占几行。 */
    public final int rows;

    /**
     * 贴图向上探出占地顶边多少格——<b>美术参数，不是玩法参数</b>。
     *
     * <p>塔最高（占 2 格 + 塔尖探出 1.5 格 = 总高 3.5 格），核心次之
     * （3 + 1 = 4 格），城墙最矮（1 + 0.5 = 1.5 格）。
     * 出图尺寸就是 {@code (rows + overhangCells) × 256} 像素高，见
     * {@code docs/ART.md} §2.1。
     *
     * <p>任何一个都不能超过 {@link BoardGeometry#MAX_OVERHANG_CELLS}，
     * 否则最上面一行的贴图会被裁掉——{@code BoardGeometryTest} 里有测试盯着。
     */
    public final float overhangCells;

    BuildingType(String label, int cols, int rows, float overhangCells) {
        this.label = label;
        this.cols = cols;
        this.rows = rows;
        this.overhangCells = overhangCells;
    }

    /** 占几个格子。 */
    public int cellCount() {
        return cols * rows;
    }

    /** 贴图高度，单位「格」= 占地格数 + 向上探出的部分。 */
    public float spriteHeightCells() {
        return rows + overhangCells;
    }
}
