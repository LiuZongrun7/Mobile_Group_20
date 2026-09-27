package com.mobilegroup20.tokentrail.game.engine;

/**
 * 建筑种类和它的<b>占地</b>。
 *
 * <p>占地是这一版玩法的核心参数：塔占 2×2、核心占 3×3、城墙占 1×1。
 * 占地同时决定三件事——能不能放下（{@link BoardGeometry#fits}）、
 * 贴图多大（见 {@code docs/ART.md} §2.1）、以及锚点在哪（占地矩形的底边中点）。
 *
 * <p><b>贴图可以比占地大，往三个方向探出</b>：向上（{@link #overhangUpCells}）、
 * 向左（{@link #overhangLeftCells}）、向右（{@link #overhangRightCells}）。
 * 三个方向都是同一个意思——<b>锚点不动，探出的那截是画面上的溢出，不占格子</b>。
 *
 * <p>现在是"塔往左右探、核心往上探"：塔的炮管朝左伸出去（敌人从左边来，
 * 塔一律朝左，见 {@link BuildingStats#AIM_HALF_ANGLE_DEG}），炮尾在右边露一点；
 * 核心是往上长的楼。
 *
 * <p><b>这里故意没有价格字段。</b>价格是随时要调的平衡参数，不是建筑自身的性质
 * （见 {@code docs/CONTRACTS.md} §8）：它住在 {@link ShopCatalog}，那里一件商品
 * 可以说"吃两种资源"、也可以说"免费"。改价格只动那个文件。
 */
public enum BuildingType {

    /** 核心：被攻破就输。放一个，占 3×3，往上是楼的顶。 */
    CORE("Core", 3, 3, 1.0f, 0f, 0f),

    /**
     * 箭塔：占 2×2。<b>射得快、每发轻</b>，见 {@link BuildingStats}。
     *
     * <p><b>炮管往左探出 1 格、炮尾往右探出 0.3 格</b>（塔一律朝左打，见
     * {@link BuildingStats#AIM_HALF_ANGLE_DEG}）。向上不探——塔顶没有塔尖。
     *
     * <p>右边那 0.3 格不是"顺手多给一点"：炮塔的<b>底座比身子窄</b>，
     * 炮尾（后座那截粗圆柱）会从底座的右边探出去。贴图里就是这样的，
     * 见 {@code art/cut_tower.py} 量的数：炮管在左边占 1.03 格、
     * 炮尾在右边占 0.28 格、中间踩地的底座正好 2 格。
     * 不把右边这 0.3 格算进贴图的话，要么炮尾被切掉，要么整个底座
     * 往左偏小半格——后者在摆塔的时候看得出来（虚框和塔身对不上）。
     */
    TOWER("Tower", 2, 2, 0f, 1.0f, 0.3f),

    /**
     * 弩车：占 2×2。<b>射得慢、每发重</b>，射程比箭塔远半格，见 {@link BuildingStats}。
     *
     * <p>和箭塔<b>占地一样、朝向一样</b>（也是一律朝左），只有三个探出的格数不同：
     * 弩弓往左探 0.9 格、弩尾往右探 0.2 格，向上不探。
     *
     * <p>为什么和箭塔不是同一组数：箭塔的炮管是从底座左边伸出去的一根管子，
     * 弩车是<b>一张横着的弓</b>——弓臂几乎和底座一样宽，探出去的和压在底座上的
     * 比例完全不同。这正是"占地和探出分开"存在的理由：两者占地都是 2×2，
     * 贴图却不是一个形状。数来自 {@code art/cut_ballista.py} 量的踩地那一段。
     */
    BALLISTA("Ballista", 2, 2, 0f, 0.9f, 0.2f),

    /** 城墙：占 1×1，横竖都用同一张贴图。挡路用。 */
    WALL("Wall", 1, 1, 0.5f, 0f, 0f);

    /** 界面和日志里显示的名字。 */
    public final String label;

    /** 占几列。 */
    public final int cols;

    /** 占几行。 */
    public final int rows;

    /**
     * 贴图向上探出占地顶边多少格——<b>美术参数，不是玩法参数</b>。
     *
     * <p>核心最高（占 3 格 + 顶上探出 1 格 = 总高 4 格），城墙 1.5 格，塔不探。
     * 出图尺寸就是 {@code (rows + overhangUpCells) × 256} 像素高，见
     * {@code docs/ART.md} §2.1。
     *
     * <p>任何一个都不能超过 {@link BoardGeometry#MAX_OVERHANG_CELLS}，
     * 否则最上面一行的贴图会被裁掉——{@code BoardGeometryTest} 里有测试盯着。
     */
    public final float overhangUpCells;

    /**
     * 贴图向<b>左</b>探出占地左边多少格——<b>美术参数，不是玩法参数</b>。
     *
     * <p>只有两种塔用（箭塔是炮管、弩车是弩弓）：武器从占地左边伸出去，
     * 指着一律朝左的射界（{@link BuildingStats#AIM_HALF_ANGLE_DEG}）。
     * 伸出去的那一截在画面上会盖到左边的格子上，但<b>不占格子</b>：
     * 能不能放、挡不挡路、敌人往哪儿走，全都只看
     * {@link #cols}×{@link #rows} 那块占地。
     *
     * <p>出图尺寸是 {@code (cols + overhangLeftCells + overhangRightCells) × 256}
     * 像素宽。上限见 {@link BoardGeometry#MAX_OVERHANG_LEFT_CELLS}。
     */
    public final float overhangLeftCells;

    /**
     * 贴图向<b>右</b>探出占地右边多少格——同样是美术参数。
     *
     * <p>只有两种塔用，而且都很小（0.2～0.3 格）：炮尾／弩尾比底座宽出来的那一点
     * （见 {@link #TOWER} 和 {@link #BALLISTA}）。这个数比左边的探出小一个量级，
     * 但少了它塔身就会和占地虚框对不齐。
     *
     * <p>上限见 {@link BoardGeometry#MAX_OVERHANG_RIGHT_CELLS}。
     */
    public final float overhangRightCells;

    BuildingType(String label, int cols, int rows,
                 float overhangUpCells, float overhangLeftCells, float overhangRightCells) {
        this.label = label;
        this.cols = cols;
        this.rows = rows;
        this.overhangUpCells = overhangUpCells;
        this.overhangLeftCells = overhangLeftCells;
        this.overhangRightCells = overhangRightCells;
    }

    /** 占几个格子。 */
    public int cellCount() {
        return cols * rows;
    }

    /** 贴图高度，单位「格」= 占地行数 + 向上探出的部分。 */
    public float spriteHeightCells() {
        return rows + overhangUpCells;
    }

    /** 贴图宽度，单位「格」= 占地列数 + 向左探出的部分 + 向右探出的部分。 */
    public float spriteWidthCells() {
        return cols + overhangLeftCells + overhangRightCells;
    }
}
