package com.mobilegroup20.tokentrail.game.engine;

/**
 * 相机：把「布局坐标」映射到「屏幕坐标」，带缩放和拖动。
 *
 * <p><b>不 import 任何 {@code android.*}</b>，所以缩放的边界规则、焦点缩放、
 * 拖动限制都能脱离模拟器跑测试——这些恰恰是手指操作里最容易写错、
 * 又最难在真机上反复试的部分。
 *
 * <h2>两个坐标系</h2>
 * <pre>
 *   布局坐标 ──Viewport──► 屏幕坐标 ──► Canvas
 *   （BoardGeometry 给的）   （真正画在哪）
 * </pre>
 * <ul>
 *   <li><b>布局坐标</b>：{@link BoardGeometry} 算出来的那一套，也就是「不放大时
 *       每个东西该在哪」。它的原点在战场内容盒（上方余量 + {@code ROWS} 行，
 *       现在 1 + 36 = 37 格高）的左上角。</li>
 *   <li><b>屏幕坐标</b>：画布上的真实像素。手指事件给的也是这个。</li>
 * </ul>
 * <p>{@code zoom == 1} 时两个坐标系重合，{@link #toScreenX} 是恒等映射——
 * <b>不放大时画面和以前一模一样</b>，一条线都不差。
 *
 * <h2>三条规则</h2>
 * <ol>
 *   <li><b>缩放下限是 1，也就是「整个战场刚好装进面板」。</b>不能再缩小了：
 *       战场就是整个世界，缩出去只会看到空白。</li>
 *   <li><b>内容比面板大时拖不出空白。</b>能拖的范围正好等于「内容超出面板的部分」的一半，
 *       所以拖到头画面的边缘和内容边缘对齐，不多不少（{@link #panBy} 里夹取）。
 *       内容比面板小时此项不适用，只能居中。</li>
 *   <li><b>就着手指缩放。</b>两指中间那个点在缩放前后停在原地
 *       （{@link #zoomAt}），而不是围绕屏幕中心缩放——后者用起来会
 *       「地图从手底下溜走」。</li>
 * </ol>
 *
 * <h2>贴图跟着放大</h2>
 * <p>渲染时素材的缩放系数是 {@code board.spriteScale(viewport.zoom())}。
 * 放到最大时贴图会被放大到接近 1:1，所以素材要按放大后的尺寸设计——
 * 见 {@link BoardGeometry#DESIGN_CELL_PX} 和 {@code docs/ART.md}。
 */
public final class Viewport {

    /**
     * 缩放下限：1 = 一屏正好看得见 {@link BoardGeometry#SCREEN_ROWS} 行
     * （= {@code 面板高 / 一格}），横向则是 {@code 面板宽 / 一格} 列。
     *
     * <p>{@link BoardGeometry#fit} 保证了这个值下一屏装得下
     * {@code SCREEN_ROWS} 行。战场有 {@link BoardGeometry#COLS} 列 ×
     * {@link BoardGeometry#ROWS} 行：<b>横向比屏幕宽得多，纵向现在正好一屏</b>
     * （{@code SCREEN_ROWS == ROWS}），所以 1 倍时<b>只有横向要拖，
     * 纵向拖不动</b>——见 {@code BoardGeometry.COLS} 的说明。
     *
     * <p><b>2026-09-27 这条改过两次意思，两次都只是跟着 {@code SCREEN_ROWS} 走。</b>
     * 战场翻倍之前是"纵向正好一屏"；刚翻倍那半天变成"纵向也看得见一半、要拖"；
     * 当天晚些时候 {@code SCREEN_ROWS} 提到 36，又变回"纵向一屏看全"。
     * 这个值本身<b>从头到尾没有变，也不该变</b>：它守的是"格子不小于
     * {@code fit()} 算出来的那个尺寸"。再往下缩就是"用更小的格子换更远的视野"，
     * 而现在 {@link BoardGeometry#TAP_TARGET_DP} 的余量已经用完了。
     */
    public static final float MIN_ZOOM = 1f;

    /**
     * 缩放上限。
     *
     * <p><b>这个值和 {@link BoardGeometry#DESIGN_CELL_PX} 是一对。</b>
     * 素材按 256px/格 设计，放到最大时任何机型上贴图都只是缩小显示
     * （{@code spriteScale} 约 0.34–0.48，不放大时 0.17–0.22），所以不会糊。
     * 改其中一个就要重算另一个，否则放到最大时贴图会被放大。
     *
     * <p><b>2026-09-27 {@code SCREEN_ROWS} 提到 36 之后，这个上限的意味变了。</b>
     * 以前"1 倍"就是一屏 18 行、格子 26.5dp，放大是为了看清细节；现在 1 倍是
     * 36 行全在、格子 15.8dp，<b>放大 2 倍（31.5dp）反而变成了"点得准"的唯一办法</b>
     * ——见 {@link BoardGeometry#TAP_TARGET_DP}。上限本身没动。
     */
    public static final float MAX_ZOOM = 2f;

    private final float panelWidthPx;
    private final float panelHeightPx;
    private final float contentWidthPx;
    private final float contentHeightPx;

    private float zoom = MIN_ZOOM;
    private float panX;
    private float panY;

    /** 内容和面板一样大的情况（不放大时正好铺满，拖不动）。 */
    public Viewport(float panelWidthPx, float panelHeightPx) {
        this(panelWidthPx, panelHeightPx, panelWidthPx, panelHeightPx);
    }

    /**
     * @param panelWidthPx    可视区域（战场面板控件）的宽
     * @param panelHeightPx   可视区域的高
     * @param contentWidthPx  要被观察的内容宽，传 {@code board.boardWidthPx()}
     * @param contentHeightPx 内容高，传 {@code board.contentHeightPx()}
     */
    public Viewport(float panelWidthPx, float panelHeightPx,
                    float contentWidthPx, float contentHeightPx) {
        if (panelWidthPx <= 0f || panelHeightPx <= 0f
                || contentWidthPx <= 0f || contentHeightPx <= 0f) {
            throw new IllegalArgumentException(
                    "面板和内容的尺寸必须是正数，收到面板 " + panelWidthPx + "x" + panelHeightPx
                            + "、内容 " + contentWidthPx + "x" + contentHeightPx);
        }
        this.panelWidthPx = panelWidthPx;
        this.panelHeightPx = panelHeightPx;
        this.contentWidthPx = contentWidthPx;
        this.contentHeightPx = contentHeightPx;
    }

    // ---- 状态 ----

    public float zoom() {
        return zoom;
    }

    public float panX() {
        return panX;
    }

    public float panY() {
        return panY;
    }

    public float panelWidthPx() {
        return panelWidthPx;
    }

    public float panelHeightPx() {
        return panelHeightPx;
    }

    /** 回到默认取景（不放大、居中）。切关卡、重开一局时调用。 */
    public void reset() {
        zoom = MIN_ZOOM;
        panX = 0f;
        panY = 0f;
    }

    /**
     * 镜头移到最右边：内容的右边缘和面板右边缘对齐（纵向不变）。
     *
     * <p><b>这是开局取景。</b>战场有 {@link BoardGeometry#COLS} 列、约 3.4 屏宽，
     * 所以"一开局看到哪一段"是要选的。选最右边而不是"对准核心"：
     * <ul>
     *   <li>核心<b>可以挪</b>（{@code Battlefield.moveCore}），镜头跟着核心跑的话，
     *       玩家每挪一次核心画面就自己跳一下，那是很糟的手感；</li>
     *   <li>开局要做的事是"在核心周围布防"，而核心摆在靠右、紧挨着山区——
     *       镜头贴右边缘正好把<b>核心 + 右边山区</b>一起框进来，玩家一眼看到
     *       自己能用的地到哪儿为止；</li>
     *   <li>往左拖是"去看敌人从哪儿来"，这个方向符合直觉（读图从左往右，
     *       但当前战况在右，敌人从左边压过来时往左拖就是"迎上去"）。</li>
     * </ul>
     *
     * <p>没有内容超出面板时（内容比面板窄）拖不动，这个调用等于居中，不会出错。
     */
    public void showRightEdge() {
        // 看最右边 = panX 取到负的最大值。拖到头的定义见 maxPanX()
        panX = -maxPanX();
        clampPan();
    }

    /** 直接设置缩放倍数，会夹到 [{@link #MIN_ZOOM}, {@link #MAX_ZOOM}]，并重新限制拖动范围。 */
    public void setZoom(float zoom) {
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        clampPan();
    }

    /**
     * 以屏幕上某一点为中心缩放——手指在哪儿捏，哪儿就停在原地。
     *
     * @param screenX  焦点（一般取两指中点）的屏幕 x
     * @param screenY  焦点的屏幕 y
     * @param factor   缩放倍数，&gt;1 放大，&lt;1 缩小
     */
    public void zoomAt(float screenX, float screenY, float factor) {
        if (factor <= 0f) {
            throw new IllegalArgumentException("缩放倍数必须是正数，收到 " + factor);
        }

        // 先记下焦点对应的布局坐标——缩放后要让它回到同一个屏幕位置
        float layoutX = toLayoutX(screenX);
        float layoutY = toLayoutY(screenY);

        setZoom(zoom * factor);

        // 反解出新的 pan，让焦点在屏幕上原地不动：
        //     目标  screenX = layoutX × zoom + 居中偏移 + panX
        //     ⇒    panX = screenX − layoutX × zoom − 居中偏移
        //
        // ★ 这里**只能减居中偏移，不能减 offsetX()**。offsetX() 里含 panX，
        //   而 panX 正是这一步要求的东西——用 offsetX() 等于把**上一个** pan
        //   也减掉一次，解出来的值是"正确值 − 旧 pan"。表现是：放大、拖到边上、
        //   再捏一次，画面会先「啪」地跳回中间才开始缩放。
        //   pan 为 0 时旧值是 0，所以这个错在"没拖过就捏"的情况下看不出来。
        panX = screenX - layoutX * zoom - centredOffsetX();
        panY = screenY - layoutY * zoom - centredOffsetY();
        clampPan();
    }

    /** 按住拖动。手指往右拖，画面跟着右移。没放大时拖不动（能拖的范围是 0）。 */
    public void panBy(float dx, float dy) {
        panX += dx;
        panY += dy;
        clampPan();
    }

    // ---- 换算 ----

    public float toScreenX(float layoutX) {
        return layoutX * zoom + offsetX();
    }

    public float toScreenY(float layoutY) {
        return layoutY * zoom + offsetY();
    }

    public float toLayoutX(float screenX) {
        return (screenX - offsetX()) / zoom;
    }

    public float toLayoutY(float screenY) {
        return (screenY - offsetY()) / zoom;
    }

    /** 布局坐标下一段长度，在屏幕上有多长。 */
    public float scale() {
        return zoom;
    }

    /**
     * 当前最多能横向拖多远（单边）。<b>正好等于「内容超出面板的部分」的一半</b>，
     * 所以拖到头时画面的边缘和内容边缘对齐，不多不少。
     *
     * <p>内容不比面板大时是 0：没得拖，只能居中。
     */
    public float maxPanX() {
        return Math.max(0f, (contentWidthPx * zoom - panelWidthPx) / 2f);
    }

    public float maxPanY() {
        return Math.max(0f, (contentHeightPx * zoom - panelHeightPx) / 2f);
    }

    /**
     * 把内容摆到面板正中的偏移。<b>内容是「居中 + 平移」</b>——
     * 内容比面板小时（缩放没放大、或内容本身比面板矮）自然居中；
     * 比面板大时就靠 {@link #panX} 决定看哪一块。
     *
     * <p>渲染时最省事的用法是直接拿它做画布变换，之后一律用布局坐标画：
     * <pre>
     *   canvas.translate(v.offsetX(), v.offsetY());
     *   canvas.scale(v.zoom(), v.zoom());
     *   // 下面全按 BoardGeometry 给的坐标画，不用自己换算
     * </pre>
     */
    public float offsetX() {
        return centredOffsetX() + panX;
    }

    public float offsetY() {
        return centredOffsetY() + panY;
    }

    /**
     * 内容居中时的偏移，<b>不含 {@link #panX}</b>。
     *
     * <p>单拎出这两个方法是为了 {@link #zoomAt}：它要反解新 pan，而解的时候
     * 新 pan 还没算出来，所以只能用"不含 pan 的那一半"。写成一个方法，
     * 就不会有人再去 {@code offsetX()} 里把 pan 拿两遍。
     */
    private float centredOffsetX() {
        return (panelWidthPx - contentWidthPx * zoom) / 2f;
    }

    private float centredOffsetY() {
        return (panelHeightPx - contentHeightPx * zoom) / 2f;
    }

    private void clampPan() {
        float maxX = maxPanX();
        float maxY = maxPanY();
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
    }

    @Override
    public String toString() {
        return "Viewport{zoom=" + zoom + ", pan=(" + panX + "," + panY + ")}";
    }
}
