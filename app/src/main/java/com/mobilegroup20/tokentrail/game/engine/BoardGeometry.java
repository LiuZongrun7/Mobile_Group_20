package com.mobilegroup20.tokentrail.game.engine;

/**
 * 战场的几何换算：格子坐标 ↔ <b>布局坐标</b>。
 *
 * <p><b>布局坐标不是屏幕坐标。</b>它是「不放大时每个东西该在哪」，原点在整个战场内容盒
 * 的左上角；从布局坐标到真正的屏幕像素还要过一层相机 {@link Viewport}（管缩放和拖动）。
 * 分开的好处是缩放不影响这一层的任何计算——地图放大两倍，格子还是那些格子。
 *
 * <p><b>这个类不 import 任何 {@code android.*}</b>，所以引擎能脱离模拟器跑单元测试，
 * 调参数不用装到手机上试。
 *
 * <h2>战场是 18 行，格子大小是算出来的</h2>
 * <p>关卡<b>固定 18 行高</b>（{@link #ROWS}），列数由屏幕形状决定。算法的顺序是：
 * <ol>
 *   <li>先求出「18 行 + 上方余量」正好装进面板高度的那条边长
 *       （{@code 面板高 / 19.5}）；</li>
 *   <li>再看向上取整能排几列——<b>宁可格子略小，也要保证 18 行全在屏幕上</b>；</li>
 *   <li>一格 = 面板宽 / 列数，所以战场横向永远铺满，右边不留缝。</li>
 * </ol>
 * 结果是手机上大约 <b>12 列 × 18 行、一格 32dp</b>：<b>整个战场一屏看得完，
 * 不用上下拖</b>。手机上算出来的 dp 大小很接近（30–33dp），因为手机的 dp 高度都差不多。
 *
 * <p>代价是格子比 48dp 小（见 {@link #TAP_TARGET_DP}）。但塔是 2×2、核心 3×3，
 * 真正的可点目标远大于一格，见 {@link #fits(int, int, int, int)}。
 *
 * <h2>四条规矩</h2>
 * <ol>
 *   <li><b>玩法逻辑只用格子坐标 {@link Cell}，不用像素。</b>像素只在这一层出现，
 *       而且只往渲染方向走。这样同一套玩法在任何屏幕上都成立，换设备不用改逻辑。</li>
 *   <li><b>格子是屏幕空间里的正方形，横平竖直</b>——不是等轴测菱形。所以点击
 *       换算就是一次除法，不需要解菱形方程，也没有绘制顺序问题。</li>
 *   <li><b>建筑占多格。</b>塔 2×2、核心 3×3、城墙 1×1，用
 *       {@link #fits(int, int, int, int)} 判断放不放得下，{@link #cellsOf} 拿它盖住的格子。</li>
 *   <li><b>行 0 在最上，列 0 在最左</b>（跟屏幕坐标一致，y 向下）。</li>
 * </ol>
 *
 * <h2>贴图怎么对齐</h2>
 * <p>素材按 <b>一格 = {@link #DESIGN_CELL_PX}px</b> 设计，运行时乘
 * {@link #spriteScale(float)} 缩放（记得把相机缩放传进去）。
 *
 * <p><b>锚点是「footprint 的底边中心」</b>，落在格子边界上而不是格子中心：
 * 一个 W×H 格的建筑，锚点在它占的那片矩形的底边中点
 * （{@link #anchorX(int, int)} / {@link #anchorY(int, int)}）。塔往上长，
 * 允许高过自己的 footprint，向上探出，最多 {@link #MAX_OVERHANG_CELLS} 格——
 * 所以战场上方留了同样宽的一条余量（{@link #boardTopPx()}），
 * 否则第 0 行的塔尖会被裁掉。
 *
 * <p>绘制时<b>按行从上往下画</b>（先画第 0 行），后画的自然盖住先画的，
 * 越靠下的物体显示在越前面，和真实前后关系一致，不需要额外的排序。
 *
 * @see Cell
 * @see Viewport
 */
public final class BoardGeometry {

    /**
     * 战场高度，固定 18 格。<b>所有设备一样。</b>
     *
     * <p>关卡高度不能随屏幕变：屏幕高的手机能多放 3 行的话，"这关在小屏手机上更难"
     * 就成了没法解释的事。行数定死，格子大小跟着屏幕走。
     */
    public static final int ROWS = 18;

    /**
     * 战场宽度，固定 40 格。<b>所有设备一样，而且比任何手机屏幕都宽。</b>
     *
     * <p><b>为什么宽度也定死，而不是像以前那样"排得下几列就几列"。</b>
     * 关卡形状是玩法的一部分：一关有多少路、敌人要走多久、能摆几道墙，
     * 这些不该因为换了台手机就变。宽度跟着屏幕走的那一版，窄屏 11 列、平板 15 列，
     * 同一关在两台机器上是两个难度。
     *
     * <p><b>代价是横向必须拖动。</b>手机一屏约 16 列，40 格就是 2.5 屏宽，
     * "整个战场一眼看全"这条性质没有了。换来的是纵向不用拖（18 行永远整屏装得下），
     * 以及地图够长、摆得开——敌人要走过 4 格敌人通道再进可建区，
     * 中间有 30 格可以布阵，核心右边还留 3 格余量。
     *
     * <p><b>纵向整屏 + 横向要拖，是权衡后的选择，不是漏了。</b>18 条路必须一眼看全：
     * 塔防里"哪条路告急"是要随时能看见的，漏看一路就等于漏掉整局。
     * 横向则是"内容多少"的问题，看不全只是要走两步，不会漏掉正在发生的事
     * ——前提是镜头位置对（开局贴最右边缘，见 {@code BattlefieldView}）。
     *
     * <p>格子大小仍然只由<b>屏幕高度</b>决定（见 {@link #fit}），
     * 因为纵向装得下是硬约束。所以手机上一格约 23dp，40 格横着要拖 2.5 屏。
     */
    public static final int COLS = 40;

    /**
     * 单指可点区域的下限，48dp（Material 的建议值）。
     *
     * <p><b>一格达不到这个值</b>：真机上算出来约 23dp。但那是「一格」，不是
     * 「一个可点目标」——塔占 2×2（约 47dp）、核心占 3×3（约 70dp）。
     * 唯一明显低于 48dp 的目标是 1×1 的城墙，放大到 2 倍时约 47dp，勉强够用。
     * 界面上放的建筑越少越大，这个问题就越小。
     */
    public static final float TAP_TARGET_DP = 48f;

    /**
     * 贴图的<b>设计基准</b>：一格 = 256px。所有素材按这个画，运行时缩放。
     *
     * <p><b>为什么是 256 而不是「一格在屏幕上的实际大小」（约 78px）。</b>
     * 地图能放大到 {@link Viewport#MAX_ZOOM} 倍，放到最大时一格约 156px，
     * 所以素材按 256px 画就够——<b>任何机型放到最大都是缩小显示，不会放大模糊</b>
     * （{@code spriteScale} 最大约 0.6；要让素材被放大，得有一格 64dp 以上的机器，
     * 那需要一块 1250dp 高的屏幕，手机上不存在）。
     *
     * <p>按 78px 画的话，不放大时看着没问题，一放大就全虚了。代价是不放大时
     * 贴图被缩到三分之一——缩小不损失画质，只是多占一点内存和安装包体积。
     */
    public static final float DESIGN_CELL_PX = 256f;

    /**
     * 贴图最多高出<b>自己 footprint 顶边</b>多少格。
     *
     * <p>最高的素材是 2×2 的箭塔：footprint 2 格 + 塔尖探出 1.5 格 = 总高 3.5 格。
     * 战场上方因此留出这么多余量。改这个值要同时改美术那边的出图规格（ART.md §2.1）。
     */
    public static final float MAX_OVERHANG_CELLS = 1.5f;

    private final int cols;
    private final float cellPx;

    /** 面板宽度，只用来算 {@link #visibleCols()}。 */
    private final float panelWidthPx;

    private BoardGeometry(int cols, float cellPx, float panelWidthPx) {
        this.cols = cols;
        this.cellPx = cellPx;
        this.panelWidthPx = panelWidthPx;
    }

    /**
     * 按可用面板的尺寸算出一套战场几何。
     *
     * <p><b>只有格子大小是算出来的，行列数是定死的</b>（{@link #COLS} × {@link #ROWS}）。
     *
     * <p>格子大小只由<b>面板高度</b>决定：让「18 行 + 上方 1.5 格余量」正好装进面板高度。
     * 面板宽度不参与计算——战场比屏幕宽是常态，宽出来的部分靠拖动看。
     *
     * <p>面板宽高比再极端也不会出现"格子大到装不下 18 行"：装得下是硬约束，
     * 宁可横向留白也不裁行。
     *
     * @param panelWidthPx  战场面板的宽（px）。不参与格子大小的计算，
     *                      只用来算 {@link #visibleCols}（一屏看得见几列）
     * @param panelHeightPx 战场面板的高（px），<b>包含</b>上方那 1.5 格余量，
     *                      方法内部会扣掉
     * @throws IllegalArgumentException 任一参数不是正数
     */
    public static BoardGeometry fit(float panelWidthPx, float panelHeightPx) {
        if (panelWidthPx <= 0f || panelHeightPx <= 0f) {
            throw new IllegalArgumentException(
                    "面板尺寸必须是正数，收到 " + panelWidthPx + "x" + panelHeightPx);
        }
        return new BoardGeometry(COLS, panelHeightPx / (ROWS + MAX_OVERHANG_CELLS),
                panelWidthPx);
    }

    /**
     * 一屏横向看得见几列（含小数）。
     *
     * <p>给界面显示和"一屏够不够操作"的判断用。手机上约 16 列，
     * 而战场有 {@link #COLS} 列，所以要拖 {@code COLS / visibleCols} 屏。
     */
    public float visibleCols() {
        return panelWidthPx / cellPx;
    }

    // ---- 尺寸 ----

    public int cols() {
        return cols;
    }

    /** 战场行数，永远是 {@link #ROWS}。 */
    public int rows() {
        return ROWS;
    }

    /** 一格边长（px）。 */
    public float cellPx() {
        return cellPx;
    }

    /**
     * 战场宽度（px）= {@link #COLS} 格。
     *
     * <p><b>通常比面板宽 2 倍以上</b>，相机因此横向总是可拖的（见
     * {@link Viewport#maxPanX()}）。等于面板宽只在宽度为 40 格左右的平板上发生。
     */
    public float boardWidthPx() {
        return cols * cellPx;
    }

    /** 战场高度（px），18 格。 */
    public float boardHeightPx() {
        return ROWS * cellPx;
    }

    /**
     * 整个内容盒的宽度（px）。和 {@link #boardWidthPx()} 一样——
     * 横向没有留白，第 0 列就是内容的最左边。
     */
    public float contentWidthPx() {
        return boardWidthPx();
    }

    /**
     * 整个内容盒的高度（px）= 上方余量 + 18 行。
     *
     * <p>相机要知道这个值：内容盒比面板矮时居中，比面板高时才可以上下拖。
     */
    public float contentHeightPx() {
        return boardHeightPx() + boardTopPx();
    }

    /**
     * 第 0 行<b>上边缘</b>的 y（px）。也是战场上方留的余量高度。
     *
     * <p>第 0 行放了最高的贴图（3.5 格高）时，塔尖正好顶到布局坐标的 y=0，
     * 再往上就没有内容了。渲染时把内容盒整体对齐到面板即可。
     */
    public float boardTopPx() {
        return MAX_OVERHANG_CELLS * cellPx;
    }

    /**
     * 贴图缩放系数：素材按 {@link #DESIGN_CELL_PX} 设计，乘这个值就是实际像素。
     *
     * <p><b>要把相机的缩放一起乘进来</b>：
     * {@code board.spriteScale(viewport.zoom())}。只传 1 的话贴图不会跟着地图放大。
     *
     * <p>任何机型上这个值都 ≤ 1（约 0.23–0.6），也就是说素材只会被缩小，
     * 不会被放大——放大才会糊。
     *
     * @param zoom 当前缩放倍数，取 {@link Viewport#zoom()}
     */
    public float spriteScale(float zoom) {
        return cellPx * zoom / DESIGN_CELL_PX;
    }

    // ---- 格子 ↔ 布局坐标 ----

    /** 格子是不是在战场内。越界的格子不能放东西。 */
    public boolean contains(int col, int row) {
        return col >= 0 && col < cols && row >= 0 && row < ROWS;
    }

    /**
     * 一个「左上角在 (col,row)、占 w 列 h 行」的建筑放不放得下。
     *
     * <p>只管边界，不管有没有和别人重叠——占位表在玩法那边（同一个格子不能放两座塔
     * 是玩法规则，不是几何规则）。
     *
     * @param w 宽，占几列（塔 2、核心 3、城墙 1）
     * @param h 高，占几行
     */
    public boolean fits(int col, int row, int w, int h) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        return col >= 0 && row >= 0 && col + w <= cols && row + h <= ROWS;
    }

    /**
     * 一个 footprint 盖住的所有格子（左上角在 (col,row)）。
     *
     * <p>用在「放下之前先检查这一片是不是空的」和「拆掉时释放这些格子」。
     * 调用前先用 {@link #fits} 确认放得下，否则返回的格子会有一部分在战场外。
     *
     * @return 长度 {@code w*h} 的数组，行优先
     */
    public Cell[] cellsOf(int col, int row, int w, int h) {
        Cell[] cells = new Cell[w * h];
        int i = 0;
        for (int dr = 0; dr < h; dr++) {
            for (int dc = 0; dc < w; dc++) {
                cells[i++] = new Cell(col + dc, row + dr);
            }
        }
        return cells;
    }

    /** 格子的左边缘 x（px）。画地面 tile 用这个。 */
    public float leftX(int col) {
        return col * cellPx;
    }

    /** 格子的上边缘 y（px）。 */
    public float topY(int row) {
        return boardTopPx() + row * cellPx;
    }

    /**
     * <b>连续</b>格子坐标 → 布局坐标的 x。位置是小数时用这个。
     *
     * <p>敌人走在格子中间，位置是 {@code 4.7} 这种小数，所以不能只提供整数版本。
     * 第 4 格的中心就是 {@code xAt(4.5f)}。
     */
    public float xAt(float cellX) {
        return cellX * cellPx;
    }

    /** <b>连续</b>格子坐标 → 布局坐标的 y。规则同 {@link #xAt}。 */
    public float yAt(float cellY) {
        return boardTopPx() + cellY * cellPx;
    }

    /** 格子中心的 x（px）。1×1 的贴图水平中点对齐这里。 */
    public float centerX(int col) {
        return leftX(col) + cellPx / 2f;
    }

    /** 格子中心的 y（px）。1×1 的贴图底部中心对齐这里。 */
    public float centerY(int row) {
        return topY(row) + cellPx / 2f;
    }

    /**
     * footprint 的<b>底边中点</b>的 x（px）——贴图锚点的横向位置。
     *
     * <p>占 1 格时就是 {@link #centerX}；占 2 格时两格交界线。
     */
    public float anchorX(int col, int w) {
        return leftX(col) + w * cellPx / 2f;
    }

    /**
     * footprint 的<b>底边</b>的 y（px）——贴图锚点的纵向位置。
     *
     * <p>注意不是格子中心：建筑是从它占的那片地面的<b>前沿</b>往上长的，
     * 锚在底边才能和地面 tile 对齐。占 1 格时比 {@link #centerY} 低半格。
     */
    public float anchorY(int row, int h) {
        return topY(row) + h * cellPx;
    }

    /**
     * 把「手指点的那一格」换算成一块放得下的占地左上角。
     *
     * <p>玩家想的是"我要把塔放<b>这儿</b>"，所以占地要尽量以点中的格子为中心：
     * 3×3 时点中的那一格就是正中那格；2×2 没有正中心，就取点中的格子当左上角。
     * 然后<b>夹进战场</b>——贴着边点的时候，整块占地要被推回场内，
     * 而不是判定成"放不下"（点屏幕右下角想放核心，是合理操作）。
     *
     * <p>越界的输入不会抛异常，会得到一块贴着边的合法占地。
     */
    public Cell topLeftFor(int col, int row, int w, int h) {
        int tlCol = col - (w - 1) / 2;
        int tlRow = row - (h - 1) / 2;
        return new Cell(
                Math.max(0, Math.min(cols - w, tlCol)),
                Math.max(0, Math.min(ROWS - h, tlRow)));
    }

    /**
     * 布局坐标落在哪一列。<b>不夹取</b>：点到了战场外面会返回负数或超出
     * {@link #cols()} 的值，调用方自己用 {@link #contains} 判断。
     * 这样"点在战场外"这件事不会被悄悄当成"点了最边上那格"。
     */
    public int colAt(float x) {
        return (int) Math.floor(x / cellPx);
    }

    /** 布局坐标落在哪一行。规则同 {@link #colAt}。 */
    public int rowAt(float y) {
        return (int) Math.floor((y - boardTopPx()) / cellPx);
    }

    /** 布局坐标落在哪个格子。<b>不夹取</b>，规则同 {@link #colAt}。 */
    public Cell cellAt(float x, float y) {
        return new Cell(colAt(x), rowAt(y));
    }

    /**
     * 离这个点最近的格子，<b>夹在战场范围内</b>。
     *
     * <p>用在拖拽放置上：手指稍微拖出边界一点，应该吸附到最边上那格，
     * 而不是判定成「非法放置」。
     */
    public Cell nearestCell(float x, float y) {
        int col = Math.max(0, Math.min(cols - 1, colAt(x)));
        int row = Math.max(0, Math.min(ROWS - 1, rowAt(y)));
        return new Cell(col, row);
    }

    @Override
    public String toString() {
        return "BoardGeometry{" + cols + "x" + ROWS
                + ", cell=" + cellPx + "px"
                + ", content=" + boardWidthPx() + "x" + contentHeightPx()
                + ", visibleCols=" + visibleCols()
                + ", spriteScale=" + spriteScale(1f) + "}";
    }
}
