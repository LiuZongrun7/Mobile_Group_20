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
 * <h2>战场 80 × 36 格，格子大小是算出来的</h2>
 * <p><b>行列数都定死</b>（{@link #COLS} × {@link #ROWS}），一关的形状不因为换了台
 * 手机就变。格子大小只由<b>面板高度</b>和 {@link #SCREEN_ROWS} 决定：
 * {@code 面板高 / (SCREEN_ROWS + 上方余量)}。真机上 <b>15.8dp</b>。
 *
 * <p>2026-09-27 那天这里走过两步，两步要一起看才读得懂：
 * <ol>
 *   <li><b>先把战场从 40 × 18 翻倍到 80 × 36</b>（可建造区要 4 倍：原来 33 格宽
 *       摆几座塔就满了），同时让 {@link #SCREEN_ROWS} 停在 18，好让<b>屏幕上
 *       每样东西多大一点没变</b>，一张素材都不用重出。</li>
 *   <li><b>再把 {@link #SCREEN_ROWS} 提到 36</b>，把整块战场按回一屏——这一步
 *       是先压了界面（339dp → 约 200dp）才做得成的，见那个常数。</li>
 * </ol>
 *
 * <p><b>结果：纵向一屏看得全 36 行，横向仍然要拖</b>（80 格 ÷ 一屏 24 列 ≈ 3.4 屏宽）。
 * 上一版专门论证过"每一路都必须一眼看全：塔防里哪条路告急要随时能看见，
 * 漏看一路就等于漏掉整局"——第 2 步就是为了把这条<b>拿回来</b>，
 * 代价落在格子大小上（26.5dp → 15.8dp），见 {@link #SCREEN_ROWS}。
 *
 * <p>格子比 48dp 小（见 {@link #TAP_TARGET_DP}）。但塔是 2×2、核心 3×3，
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
 * （{@link #anchorX(int, int)} / {@link #anchorY(int, int)}）。建筑允许画出自己的
 * footprint，向上最多 {@link #MAX_OVERHANG_CELLS} 格、向左最多
 * {@link #MAX_OVERHANG_LEFT_CELLS} 格、向右最多 {@link #MAX_OVERHANG_RIGHT_CELLS} 格。
 * <b>只有向上那个要留余量</b>（{@link #boardTopPx()}，否则第 0 行的楼顶会被裁掉）；
 * 横向探出伸进的是隔壁的格子，而战场左右两边本来就还有地方。
 *
 * <p>绘制时<b>按行从上往下画</b>（先画第 0 行），后画的自然盖住先画的，
 * 越靠下的物体显示在越前面，和真实前后关系一致，不需要额外的排序。
 *
 * @see Cell
 * @see Viewport
 */
public final class BoardGeometry {

    /**
     * 战场高度，固定 36 格。<b>所有设备一样。</b>
     *
     * <p>关卡高度不能随屏幕变：屏幕高的手机能多放 3 行的话，"这关在小屏手机上更难"
     * 就成了没法解释的事。行数定死，格子大小跟着屏幕走。
     *
     * <p><b>2026-09-27 从 18 翻倍到 36。</b>原来 18 行是照着"一屏装完"定的，
     * 可那样可建造区只有 33 × 18 格，摆几座塔就满了。翻倍之后可建造区是原来的
     * 4 倍。翻倍当天纵向是要拖的（一屏只看得到 18 行），**当天晚些时候把界面压小、
     * 又把 {@link #SCREEN_ROWS} 提到 36，纵向才重新变回一屏看全**——见那个常数。
     */
    public static final int ROWS = 36;

    /**
     * 战场宽度，固定 80 格。<b>所有设备一样，而且比任何手机屏幕都宽得多。</b>
     *
     * <p><b>为什么宽度也定死，而不是像以前那样"排得下几列就几列"。</b>
     * 关卡形状是玩法的一部分：一关有多少路、敌人要走多久、能摆几道墙，
     * 这些不该因为换了台手机就变。宽度跟着屏幕走的那一版，窄屏 11 列、平板 15 列，
     * 同一关在两台机器上是两个难度。
     *
     * <p><b>代价是横向必须拖动。</b>真机上 36 行铺满一屏之后，一屏看得见约 24 列，
     * 80 格就是 3.4 屏宽，从这头看到那头要拖三下。数字比"18 行那版"小（那时一屏
     * 14 列、5.7 屏），是因为同样的面板宽度现在摊给了更小的格子——**横向拖动
     * 变少是格子变小的副产品，不是地图变小了**。
     *
     * <p>这一条能成立，靠的是<b>纵向一眼看全</b>兜底："横向是内容多少的问题，
     * 看不全只是要走两步，不会漏掉正在发生的事"——真正会漏事的是纵向
     * （敌人从 0～35 行里随机挑一行进场）。2026-09-27 战场翻倍的那半天里
     * 这个兜底一度没有了（一屏只看得到 18 行），当天晚些时候把界面压小、
     * {@link #SCREEN_ROWS} 提到 36 之后才补回来，见那个常数。
     *
     * <p><b>2026-09-27 从 40 翻倍到 80</b>，和 {@link #ROWS} 是同一次改动、
     * 同一个理由：可建造区太小。横向纵向一起翻倍是刻意的——只加长不加宽
     * 会变成一条走廊，塔的射界（贴脸的弩车四格、远处的大炮二十格）在 36 行的高
     * 战场上才有横竖两个方向的余地——而且大炮那条走廊是<b>竖着也有宽度</b>的：
     * 当时那十五度张角在十六格外只有四点三格高，掉到十八行就只剩两格，
     * 摆位没什么可挑的。
     *
     * <p><b>最后那半句当天晚些时候就不成立了</b>：大炮的张角改成了 30°
     * （{@code BuildingStats.CANNON_HALF_ANGLE_DEG}），十六格外的半宽从四点三格
     * 变成九点二格——一条走廊已经占不满 36 行的高战场。**但 36 行没跟着改回去**：
     * 它主要立在上面那条"纵向一眼看全"的兜底上，大炮的宽度只是当时的第二个理由。
     * 留这一段是为了让人知道当初为什么翻倍，不是因为它还算数。
     */
    public static final int COLS = 80;

    /**
     * 一屏纵向应该看得见几行——<b>格子大小是按这个数算的</b>，见 {@link #fit}。
     *
     * <p><b>2026-09-27 从 18 改成 36（= {@link #ROWS}），即"整块战场纵向一屏看全、
     * 不用上下拖"。</b>18 那一版是战场刚翻倍时定的：36 行摊进同一块面板会让屏幕上
     * 每样东西缩一半（那不是"地图变大"，是"地图变小外加看得更远"），所以宁可
     * 按 18 摊、让纵向拖。代价是一屏只看得到一半，敌人又在 0～35 行里随机挑一行
     * 进场，屏幕外的某一路被打穿玩家也看不见。
     *
     * <p><b>改成 36 靠的不是改这个数，是先腾出了高度</b>：界面（状态栏 + HUD +
     * 操作行 + 底部导航）从 339dp 压到 260dp，战场面板从 503dp 涨到 583dp，
     * 多出来的高度正好够把 37 格（36 行 + 上方 1 格余量）按进一屏。
     * <b>不压界面的话这个数改不动</b>——同样的 37 格摊在 503dp 上，格子只有 13.6dp。
     *
     * <p><b>代价是格子从 26.5dp 掉到 15.8dp</b>（见 {@link #TAP_TARGET_DP}）。
     * 现在<b>连 2×2 的塔都不到 48dp</b>（31.5dp），只有放大到
     * {@link Viewport#MAX_ZOOM} 倍之后才过线——这是拿"看得全"换来的，不是漏了。
     * 要回到 26dp，就得把 {@link #ROWS} 减回 18。
     *
     * <p>改这个值等于改所有东西在屏幕上的大小：调大 = 格子小、看得多；
     * 调小 = 格子大、看得少。它和 {@link #DESIGN_CELL_PX} 也有关——格子一旦
     * 在屏幕上超过 256px，贴图就要被拉伸了，见那个常数的说明。
     */
    public static final int SCREEN_ROWS = 36;

    /**
     * 单指可点区域的下限，48dp（Material 的建议值）。
     *
     * <p><b>2026-09-27 之后所有目标都达不到这个值</b>：真机上一格 15.8dp，
     * 塔 2×2 = <b>31.5dp</b>、核心 3×3 = <b>47.3dp</b>、城墙 1×1 = <b>15.8dp</b>。
     * 在这之前塔和核心是过线的（26.5dp 一格 → 53 / 80dp）。
     *
     * <p>掉下来的原因不是这个常量变了，是 {@link #SCREEN_ROWS} 从 18 提到 36
     * （一屏看全整块战场，格子减半）——见那个常数的说明。放大到
     * {@link Viewport#MAX_ZOOM} 倍之后是 31.5 / 63 / 94.7dp，塔和核心补回来了，
     * <b>1×1 的城墙仍然不够</b>。
     *
     * <p>所以这个常量现在的作用是<b>一把尺子</b>：它量出"看全"和"点得准"之间的
     * 差额，提醒不要在这个基础上再缩格子。真要同时要两头，得从别的地方想办法
     * （把墙的点击判定放大到相邻格、或者做一个"确认后落子"的步骤），
     * 而不是继续缩小格子。
     */
    public static final float TAP_TARGET_DP = 48f;

    /**
     * 贴图的<b>设计基准</b>：一格 = 256px。所有素材按这个画，运行时缩放。
     *
     * <p><b>为什么是 256 而不是「一格在屏幕上的实际大小」。</b>地图能放大到
     * {@link Viewport#MAX_ZOOM} 倍，所以按"放到最大时一格多少 px"来画就够——
     * <b>任何机型放到最大都是缩小显示，不会放大模糊</b>。
     *
     * <p><b>256 这个数是"战场 18 行那版"定的，现在偏大了。</b>那时一格在屏幕上
     * 约 89px、放到最大约 178px，256 留了两成余量。2026-09-27 把
     * {@link #SCREEN_ROWS} 提到 36 之后一格只剩约 53px，放到最大约 107px
     * （最省的机型只有 88px）——{@code spriteScale} 从 0.69 掉到 0.35，
     * 等于<b>每次绘制都扔掉一半以上的像素</b>。
     *
     * <p>照着 <b>128px/格</b> 重出一遍素材就能把这部分省下来（内存和包体减半，
     * 画面上几乎看不出差别，因为放到最大也只有 107px）。**这一步没做**——
     * 它是美术那边的一次重出，和"看全战场"是两件事，记在
     * {@code BoardGeometryTest.spritesAreNeverUpscaledOnAnyDevice} 里。
     */
    public static final float DESIGN_CELL_PX = 256f;

    /**
     * 贴图最多高出<b>自己 footprint 顶边</b>多少格。
     *
     * <p>现在最高的是 3×3 的核心：footprint 3 格 + 楼的顶探出 1 格 = 总高 4 格。
     * 战场上方因此留出这么多余量。改这个值要同时改美术那边的出图规格（ART.md §2.1）。
     *
     * <p>塔原来在这儿占 1.5（塔尖），2026-09-26 改成炮管<b>朝左</b>伸出去了
     * （{@link BuildingType#overhangLeftCells}），于是余量从 1.5 降到 1，
     * 让出来的半格还给了格子本身——格子大了约 2.6%。
     */
    public static final float MAX_OVERHANG_CELLS = 1.0f;

    /**
     * 贴图最多探出<b>自己 footprint 左边</b>多少格。
     *
     * <p>只有塔用（炮管朝左伸 1 格）。
     *
     * <p>和上面那个常数不一样，这个<b>不参与 {@link #fit} 的尺寸计算</b>：
     * 横向探出不会让任何东西被裁掉——最靠左能放塔的是可建区第一列
     * （{@code Battlefield.firstBuildableCol()}），减掉 1 格还在敌人通道里，
     * 离战场左边缘远得很。它存在只是为了给 {@code BoardGeometryTest}
     * 和美术规格一个能对齐的上限。
     */
    public static final float MAX_OVERHANG_LEFT_CELLS = 1.0f;

    /**
     * 贴图最多探出<b>自己 footprint 右边</b>多少格。
     *
     * <p>只有塔用，而且只有 0.3 格：炮尾比底座宽出来的那一点
     * （见 {@link BuildingType#TOWER}）。和左边那个一样不参与 {@link #fit}。
     *
     * <p>往右探比往左探更安全：最靠右能放建筑的是可建区最后一列，右边还隔着
     * 3 格山区，0.3 格伸过去连山那条边都碰不到。
     */
    public static final float MAX_OVERHANG_RIGHT_CELLS = 0.3f;

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
     * <p>格子大小只由<b>面板高度</b>决定：让「{@link #SCREEN_ROWS} 行 + 上方 1 格
     * 余量」正好装进面板高度。面板宽度不参与计算——战场比屏幕宽是常态，
     * 宽出来的部分靠拖动看。
     *
     * <p><b>除数是 {@link #SCREEN_ROWS} 而不是写死 {@link #ROWS}。</b>现在两者相等
     * （都是 36），所以这一步看起来是多余的——<b>但它俩是两件事，不能合并</b>：
     * {@code SCREEN_ROWS} 是"一屏想看几行"，{@code ROWS} 是"这关有多高"。
     * 战场翻倍那天它们不相等（18 vs 36），正是靠分开才做到了"地图变大、
     * 屏幕上的东西一点没变"；哪天要一屏只看一半、靠拖动看另一半，也还是靠它。
     *
     * <p>面板宽高比再极端也不会出现"格子大到一屏连 {@link #SCREEN_ROWS} 行
     * 都装不下"：装得下是硬约束，宁可横向留白也不裁行。
     *
     * @param panelWidthPx  战场面板的宽（px）。不参与格子大小的计算，
     *                      只用来算 {@link #visibleCols}（一屏看得见几列）
     * @param panelHeightPx 战场面板的高（px），<b>包含</b>上方那 1 格余量，
     *                      方法内部会扣掉
     * @throws IllegalArgumentException 任一参数不是正数
     */
    public static BoardGeometry fit(float panelWidthPx, float panelHeightPx) {
        if (panelWidthPx <= 0f || panelHeightPx <= 0f) {
            throw new IllegalArgumentException(
                    "面板尺寸必须是正数，收到 " + panelWidthPx + "x" + panelHeightPx);
        }
        return new BoardGeometry(COLS, panelHeightPx / (SCREEN_ROWS + MAX_OVERHANG_CELLS),
                panelWidthPx);
    }

    /**
     * 一屏横向看得见几列（含小数）。
     *
     * <p>给界面显示和"一屏够不够操作"的判断用。真机上约 24 列，
     * 而战场有 {@link #COLS} 列，所以要拖 {@code COLS / visibleCols} 屏（约 3.4 屏）。
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
     * <p><b>比面板宽几倍</b>（真机上一屏约 24 列，{@link #COLS} 是 80 列，
     * 即 3.4 屏），相机因此横向总是可拖的（见 {@link Viewport#maxPanX()}）。
     * 等于面板宽只在宽度 80 格左右的平板上才可能发生，手机上不存在。
     */
    public float boardWidthPx() {
        return cols * cellPx;
    }

    /** 战场高度（px）= {@link #ROWS} 格，现在 36 格。 */
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
     * 整个内容盒的高度（px）= 上方余量 + {@link #ROWS} 行，现在 1 + 36 = 37 格。
     *
     * <p>相机要知道这个值：内容盒比面板矮时居中，比面板高时才可以上下拖。
     * <b>2026-09-27 这天它两种都当过</b>：战场刚翻倍时 37 格比一屏的 19 格高，
     * 纵向要拖；当天晚些时候 {@link #SCREEN_ROWS} 提到 36，面板正好也是 37 格，
     * <b>两者相等——居中偏移为零，纵向拖不动</b>（{@link Viewport#maxPanY()} 返回 0）。
     * 相等是刻意的：多一格是留白，少一格就要拖。
     */
    public float contentHeightPx() {
        return boardHeightPx() + boardTopPx();
    }

    /**
     * 第 0 行<b>上边缘</b>的 y（px）。也是战场上方留的余量高度。
     *
     * <p>第 0 行放了最高的贴图（核心，{@link BuildingType#spriteHeightCells()} = 4 格）时，
     * 楼顶正好顶到布局坐标的 y=0，再往上就没有内容了。渲染时把内容盒整体对齐到面板即可。
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

    /**
     * 拖出来的这一排<b>该有几座</b>：从按下那一格到手指当前那一格，跨了几座。
     *
     * <p>"建一排"（手指拖过几格就建几座）和"挪一排"（手指拖几格，整排就走几格）
     * 算的是同一个数，所以放在这儿——纯整数运算，不碰触摸事件，能单测。
     *
     * <p>往下取整：没拖满一整座就不算，"往左拖"和"往右拖"一样长。
     * 手指跨了 2.9 座只算 2 座——第 3 座的格子还没被指到；
     * 多建一座比少建一座更难收场。
     *
     * <p>只管<b>长度</b>，不管方向：一排从哪头起由调用方取两端的
     * {@link Math#min} 决定，步长恒为一个占地。这样铺出来的排总是从左到右、
     * 从上到下，往哪个方向拖都是同一条。
     *
     * @param anchorIndex 按下那一格的列号（或行号）
     * @param fingerIndex 手指当前那一格的列号（或行号）
     * @param span        一座建筑在那一轴上占几格（塔 2、墙 1）
     * @return 座数，至少 1（手指没离开按下那格时就是按下那一座）
     */
    public static int runLength(int anchorIndex, int fingerIndex, int span) {
        if (span <= 0) {
            throw new IllegalArgumentException("占地跨度必须是正的，实际是 " + span);
        }
        return Math.abs(fingerIndex - anchorIndex) / span + 1;
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
