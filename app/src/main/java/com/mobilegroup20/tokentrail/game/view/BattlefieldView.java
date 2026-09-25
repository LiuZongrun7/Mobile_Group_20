package com.mobilegroup20.tokentrail.game.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import com.mobilegroup20.tokentrail.R;
import com.mobilegroup20.tokentrail.game.engine.Battlefield;
import com.mobilegroup20.tokentrail.game.engine.BoardGeometry;
import com.mobilegroup20.tokentrail.game.engine.Building;
import com.mobilegroup20.tokentrail.game.engine.BuildingStats;
import com.mobilegroup20.tokentrail.game.engine.BuildingType;
import com.mobilegroup20.tokentrail.game.engine.Cell;
import com.mobilegroup20.tokentrail.game.engine.Enemy;
import com.mobilegroup20.tokentrail.game.engine.EnemyType;
import com.mobilegroup20.tokentrail.game.engine.Projectile;
import com.mobilegroup20.tokentrail.game.engine.Terrain;
import com.mobilegroup20.tokentrail.game.engine.Viewport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 战场：把 {@link Battlefield} 画出来，并处理缩放、拖动、点一下放建筑。
 *
 * <h2>现在画的是纯色块，不是贴图</h2>
 * <p>这一版<b>故意不加载任何图片</b>：每个建筑画成一个纯色矩形，
 * 位置、占地、高度全部按真贴图将来会占的地方画。这样做的目的是
 * <b>在画图之前先把尺寸和锚点确认掉</b>——如果 2×2 的塔在这个画面上看着
 * 大小合适、站在格子上不陷不飘，那美术按 {@code docs/ART.md} §2.1 出图就一定对。
 *
 * <p>所以这个类里每一处坐标都不是随手写的，全部来自
 * {@link BoardGeometry}：占地用 {@code leftX/topY}，锚点用
 * {@code anchorX/anchorY}（占地的<b>底边中点</b>），贴图高度用
 * {@link BuildingType#spriteHeightCells()}。换成真贴图时，
 * 把 {@code drawBlock(...)} 换成 {@code canvas.drawBitmap(...)} 就行，
 * 坐标一个都不用改。
 *
 * <p>贴图来了之后，这个类里只有画法会变，几何和触摸逻辑不动。
 *
 * <h2>它铺满全屏，但"战场在哪儿"是外面告进来的</h2>
 * <p>这个 View 是 {@code match_parent} 的，因为那片地本来就该能糊满整屏：
 * 放大到 2 倍时 18 行地有 2898px，比整块屏幕（2844px）还高。只给它中间一条的话
 * 地形会被裁掉，放到多大都出不了那个框，屏幕上永远是"石头 / 绿地 / 石头"三段。
 *
 * <p>所以**它的边界不代表战场**。战场是界面上夹在 HUD 和按钮行中间的那个
 * 空占位（{@code @id/play_area}），由 {@link #setPlayArea(View)} 接进来：
 * 格子多大（{@link BoardGeometry#fit}）、相机怎么取景、触摸坐标怎么换算，
 * 全部以那个面板为准。画之前先 {@code translate(playLeft, playTop)} 把原点
 * 搬到面板左上角，之后才是相机那两句。
 *
 * <h2>操作</h2>
 * <ul>
 *   <li>两指捏合缩放（{@link Viewport#zoomAt}，焦点停在手指中间）</li>
 *   <li>单指拖动平移（手指压下去时如果先出现半透明的"预放位置"，
 *       那就是还没开始拖——拖过系统认定的滑动距离之后才变成平移）</li>
 *   <li>点建筑 → <b>查看</b>它（弹出详情，见 {@link Inspector}）；</li>
 *   <li>点空地 → 手上拿着东西就放下来，空着手就收起详情面板；</li>
 *   <li>挪动模式（{@link #startMoving}）下点空地 → 把那一座挪过去。
 *       <b>挪动不花钱</b>，所以这条路不经过 {@link BuildGate}。</li>
 * </ul>
 *
 * <h2>战斗这一层画什么</h2>
 *
 * <p>规则全在 {@link Battlefield#advance} 里，这个类只把"正在发生什么"画出来：
 * 敌人走动、被挡住、挨打（{@code drawSprites}），天上飞的子弹
 * （{@code drawProjectiles}），以及被打掉的血量（{@code drawBars}）。
 *
 * <p>帧由 {@link #onDraw} 自己排（{@code tick}）：场上有敌人<b>或者天上还有子弹</b>
 * 就一直要下一帧，都清空了或者这一局结束了就停。**没有一个常驻的定时器**——
 * 只在真的有东西需要动的时候才重画，这是这个页面上唯一一处会连续出帧的地方。
 */
public class BattlefieldView extends View {

    /** 点下去之后"预放位置"的颜色停留多久（毫秒）。 */
    private static final long REJECT_FLASH_MS = 700L;

    /** 一帧最多推进多少秒。掉帧时别让敌人瞬移。 */
    private static final float MAX_FRAME_SECONDS = 0.05f;

    /** 范围圈里面那层填充的不透明度。压得太实会把地上的格子和斜纹盖掉。 */
    private static final int RANGE_FILL_ALPHA = 46;

    /** 范围圈那道线的不透明度。比填充实，圈才看得出边界在哪儿。 */
    private static final int RANGE_LINE_ALPHA = 200;

    /** 手指下面那块预放位置的填充。填了才知道"要放的是这一块"。 */
    private static final int GHOST_FILL_ALPHA = 70;

    /** 放不下时那一闪的红框。比虚影实，闪一下要看得见。 */
    private static final int REJECT_FILL_ALPHA = 110;

    /**
     * "只描边、不填色"。两处用：正在查看的那座、以及挪动虚影正好落在它自己身上时。
     *
     * <p>理由见 {@code drawOverlay}——简单说就是<b>填色会盖住底下的东西</b>，
     * 而这两处底下都有不能盖的东西（一座塔、或者它自己的旧位置）。
     */
    private static final int NO_FILL = 0;

    /** 血条高度，单位格。比格子的十分之一略多，缩到最小也还看得见。 */
    private static final float BAR_HEIGHT_CELLS = 0.22f;

    /**
     * 血条宽度占占地的比例。
     *
     * <p>不铺满：条和条之间留一道缝，两座挨着的塔才分得清谁的条是谁的。
     */
    private static final float BAR_WIDTH_RATIO = 0.78f;

    /** 血条离贴图顶端多远，单位格。贴着画会像建筑自己的一部分。 */
    private static final float BAR_GAP_CELLS = 0.14f;

    /** 血量高于这个比例算"还很健康"，条是绿的。 */
    private static final float HP_HEALTHY = 0.6f;

    /** 高于这个（但不到 {@link #HP_HEALTHY}）算"见黄"，再低就是红的。 */
    private static final float HP_HURT = 0.3f;

    /**
     * 子弹画多大，单位格（直径）。
     *
     * <p>0.3 格在这台机器上是 26.6px：比敌人的身子（一格）明显小，又大到缩到
     * 最小倍数还看得见。<b>这是真贴图出来之前占位用的大小</b>，规格写进了
     * {@code ART.md}，换贴图时按那个尺寸画就行。
     */
    private static final float SHOT_CELLS = 0.3f;

    /**
     * 子弹的不透明度。默认不透明——它飞得快（9 格/秒），半透明的一颗
     * 在绿地上几乎看不见，而"看不看得见在打"是这一层唯一的职责。
     */
    private static final int SHOT_ALPHA = 255;

    /** 战场怎么造出来。界面在拿到真实尺寸之后才知道有多少格子，所以由外面提供。 */
    public interface SceneBuilder {
        /** 注意：每次控件尺寸变化（多窗口、折叠）都会被重新调用。 */
        Battlefield create(BoardGeometry board);
    }

    /**
     * 战况变了就叫一声，界面拿它刷 HUD 上的战况那几行。
     *
     * <p><b>只在真有变化的时候叫</b>（{@link #notifyStatus}），
     * 不是每帧叫一次：这个回调会改字、有时候还会弹卡片，
     * 一秒钟调六十遍的话，界面上那些数字会闪，而且一直在做无谓的布局。
     */
    public interface Listener {
        void onStatusChanged(BattleStatus status);
    }

    /**
     * 相机变了（平移、缩放、重新量尺寸）。
     *
     * <p>给整页的背景用：背景要跟着同一个相机动，整页才像一片连续的地。
     * 见 {@link MountainBackgroundView}。
     *
     * <p><b>只在相机真的变了的时候调</b>，不是每帧——敌人走动不改相机，
     * 那时背景不需要重画（每帧重画一整页会很费）。
     */
    public interface CameraListener {
        void onCameraChanged(Viewport viewport, BoardGeometry board);
    }

    /**
     * 买东西的关卡：落子之前先问它"这件买得起吗"。
     *
     * <p><b>为什么由外面接进来。</b>价格和余额是玩法侧的账（{@code ShopCatalog} +
     * 余额），这个 View 只该知道"点哪儿、占地几格"。让它自己去查价格，
     * 改一次平衡就要动绘制代码。
     *
     * <p><b>为什么要分成两个方法而不是"扣钱，扣不动返回 false"。</b>
     * 拖动时的虚影要在手指还没抬起来的时候就决定画绿还是画红，那时不能真的扣钱；
     * 而扣钱只该发生在真的放下去之后。所以问价和成交分开，中间隔着引擎的占地判定。
     */
    public interface BuildGate {
        /** 这件买得起吗。买不起时虚影画成红色（{@link #badColor}），点了也不放。 */
        boolean canAfford(BuildingType type);

        /**
         * 成交，真扣钱。**只在 {@link #canAfford} 为真、
         * 而且引擎那边真的放下去了之后调**，所以放不下时不会白花钱。
         */
        void spend(BuildingType type);

        /**
         * 这一下没成立。
         *
         * <p>{@code affordable} 分得清是两种不成里的哪一种：假 = 钱不够
         * （该提示去赚资源），真 = 那块地放不下（该提示换个地方）。
         * 两种混成一句"操作失败"的话，玩家会以为是按钮坏了。
         */
        void onBlocked(BuildingType type, boolean affordable);
    }

    /**
     * 点了一下：点到建筑 = <b>看它</b>，点到空地 = 放（如果有选东西）。
     *
     * <p><b>为什么要分这两件事。</b>以前不管点到什么都当成"放"，于是想看一眼
     * 已有的塔，结果在旁边又冒出来一座、还扣了钱。规则现在和部落冲突一样：
     * <b>点建筑永远是查看</b>，查看的入口同时也是升级的入口（详情面板）；
     * 只有点空地才是放。
     */
    public interface Inspector {
        /**
         * 点到了东西。
         *
         * @param building 点到的建筑；点的是空地时是 {@code null}
         *                  （传 null 而不是"不回调"，是为了让外面能借这一下
         *                  <b>收起</b>详情面板——不然点了空地面板还挂着）
         */
        void onInspected(@Nullable Building building);

        /**
         * 手上拿的东西变了。
         *
         * <p><b>两种来路都会报</b>：玩家从商店里选的，以及放下之后<b>自动收起来的</b>
         * （一件商品只放一次，见 {@link #handleTap}）。只报前一种的话，
         * 放下之后再打开商店，面板上还写着"Tower · in hand"。
         *
         * @param type 现在手上拿的；空手时是 {@code null}
         */
        void onSelectedChanged(@Nullable BuildingType type);

        /**
         * 挪到这一格挪不过去（压着别人、在通道或山区、出界）。
         *
         * <p><b>和 {@link BuildGate#onBlocked} 是两回事</b>，不要合并：那里还要
         * 区分"钱不够"和"地不平"，而挪动不花钱，只有"地不平"一种。
         * 留着一个专门的入口，是为了让界面能说清"是这一座挪不过去"，
         * 而不是借一句买不起的提示把玩家引到钱包上去。
         *
         * @param building 正在挪、但没挪成的那一座
         */
        void onMoveBlocked(Building building);
    }

    private SceneBuilder sceneBuilder;
    private Listener listener;
    private CameraListener cameraListener;
    private BuildGate buildGate;
    private Inspector inspector;

    private Battlefield battlefield;
    private BoardGeometry board;
    private Viewport viewport;

    /**
     * 当前选中的建筑；{@code null} = 只缩放拖动，不放东西。
     *
     * <p><b>初始是 {@code null}</b>，不是塔：现在"手上拿哪件"是从商店里选的
     * （见 {@code MainActivity.setUpShop}），开局没进过商店，手上就是空的。
     * 以前默认拿塔，是因为放置靠底下那排按钮切换——那排按钮已经删了。
     */
    private BuildingType selected;

    /**
     * 正在被查看的那座建筑；{@code null} = 没在看谁。
     *
     * <p>和 {@link #selected} 是两码事：那个是"手上拿着什么准备放"，
     * 这个是"正在看场上的哪一座"。可以同时成立——看着一座塔、手上还拎着另一座，
     * 点一下空地照样能放下去。
     */
    private Building inspected;

    /**
     * 正在被挪动的那一座；{@code null} = 没在挪。
     *
     * <p><b>和 {@link #selected} 互斥</b>：手上拎着一件要放新的、
     * 同时又在挪场上的一座，两个模式叠在一起，点一下空地该干什么是说不清的。
     * 所以 {@link #setSelected} 和 {@link #startMoving} 各自会把对方清掉。
     *
     * <p>和 {@link #inspected} 不同：那个是"看"，这个是"正在改它的位置"。
     * 进挪动模式时会先把 inspected 清掉（详情面板也关了），
     * 不然地上会同时亮着"选中高亮"和"挪动虚影"。
     */
    private Building moving;

    private final ScaleGestureDetector scaleDetector;
    private final int touchSlop;

    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private boolean pressed;
    private boolean dragging;

    /** 手指下面那块"预放位置"，没按着的时候是 null。 */
    private Cell ghostTopLeft;
    private boolean ghostOk;

    /** 放不下的时候闪一下的位置和时刻。 */
    private Cell rejectedTopLeft;
    private long rejectedAtMs;

    private long lastFrameMs;
    // 上一帧报出去的战况。-1 / null 是"还没报过"，所以第一帧一定会报一次。
    private int lastBuildings = -1;
    private int lastEnemies = -1;
    private int lastWaves = -1;
    private int lastLeaks = -1;
    private int lastCoreHp = -1;
    private Battlefield.Outcome lastOutcome;

    // 画笔。构造时一次性建好，onDraw 里不 new 对象。
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float density;

    private final int groundA;
    private final int groundB;
    private final int entryLane;
    private final int mountain;
    private final int mountainShade;
    private final int gridLine;
    private final int coreColor;
    private final int towerColor;
    private final int wallColor;

    /**
     * 三种敌人各自的颜色，下标是 {@link EnemyType#ordinal()}。
     *
     * <p>用数组而不是 {@code switch}：这是"颜色跟着种类走"，一一对应，
     * 加了第四种敌人只在这张表和 {@code colors.xml} 里各加一行；
     * 写成 switch 的话漏一个分支会掉进 default 悄悄变回杂兵色。
     */
    private final int[] enemyColors = new int[EnemyType.values().length];

    /** 和 {@link #enemyColors} 一一对应的"卡住了"版本，构造时算好，见 {@link #dim}。 */
    private final int[] enemyBlockedColors = new int[EnemyType.values().length];

    private final int okColor;
    private final int badColor;
    private final int rangeColor;
    private final int spriteLine;
    private final int hpTrack;
    private final int hpGood;
    private final int hpWarn;
    private final int hpLow;
    /** 子弹的颜色。用的还是 {@code game_beam} 那个"塔的火力"色，只是不画连线了。 */
    private final int shotColor;

    // ---- 战场面板：外面接进来的一块矩形 ----
    //
    // 这个 View 是**铺满全屏**的。为什么不只占中间那一条：地形得能滑到
    // HUD 和按钮底下——放大到 2 倍时那片地有 2898px 高，比整块屏幕（2844px）
    // 还高，本来就该糊满整屏。View 只有一条面板那么大的话，超出的部分会被裁掉，
    // 于是放到多大都出不了那个框，屏幕上永远是"石头 / 绿地 / 石头"三段。
    //
    // 但玩法坐标一律是相对**战场面板**的：格子多大、相机怎么取景、
    // "拖到边上"是什么意思，全按面板算。面板不是这个 View 的边界，
    // 而是界面上夹在 HUD 和按钮行中间的那个空占位（{@code @id/play_area}）——
    // 它自己不画任何东西，只负责回答"战场能用哪一块、在哪儿"。
    /** 界面上那块占位。为 null 时这个 View 不工作（布局预览里就是这种状态）。 */
    private View playArea;
    /** 面板左上角在<b>本 View 坐标系</b>里的位置。画之前要先平移这么多。 */
    private float playLeft;
    private float playTop;
    /** 面板尺寸。格子大小由它算（{@link BoardGeometry#fit}）。 */
    private int playWidth;
    private int playHeight;
    /** 复用的两个数组，{@code getLocationInWindow} 会往里写。 */
    private final int[] areaLocation = new int[2];
    private final int[] ownLocation = new int[2];

    public BattlefieldView(Context context) {
        this(context, null);
    }

    public BattlefieldView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        groundA = getContext().getColor(R.color.game_ground_a);
        groundB = getContext().getColor(R.color.game_ground_b);
        entryLane = getContext().getColor(R.color.game_entry_lane);
        mountain = getContext().getColor(R.color.game_mountain);
        mountainShade = getContext().getColor(R.color.game_mountain_shade);
        gridLine = getContext().getColor(R.color.game_grid_line);
        coreColor = getContext().getColor(R.color.game_core);
        towerColor = getContext().getColor(R.color.game_tower);
        wallColor = getContext().getColor(R.color.game_wall);
        enemyColors[EnemyType.GRUNT.ordinal()] = getContext().getColor(R.color.game_enemy_grunt);
        enemyColors[EnemyType.RUNNER.ordinal()] = getContext().getColor(R.color.game_enemy_runner);
        enemyColors[EnemyType.BRUTE.ordinal()] = getContext().getColor(R.color.game_enemy_brute);
        for (int i = 0; i < enemyColors.length; i++) {
            enemyBlockedColors[i] = dim(enemyColors[i]);
        }
        okColor = getContext().getColor(R.color.game_ok);
        badColor = getContext().getColor(R.color.game_bad);
        rangeColor = getContext().getColor(R.color.game_range);
        spriteLine = getContext().getColor(R.color.game_sprite_line);
        hpTrack = getContext().getColor(R.color.game_hp_track);
        hpGood = getContext().getColor(R.color.game_hp_good);
        hpWarn = getContext().getColor(R.color.game_hp_warn);
        hpLow = getContext().getColor(R.color.game_hp_low);
        shotColor = getContext().getColor(R.color.game_beam);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(spriteLine);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setColor(spriteLine);
        dashed.setPathEffect(new DashPathEffect(new float[]{5f * density, 4f * density}, 0f));

        label.setColor(getContext().getColor(R.color.game_label));
        label.setTextAlign(Paint.Align.CENTER);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (viewport == null) {
                            return false;
                        }
                        // 焦点缩放：两指中间那个点停在原地，地图不会从手底下溜走
                        viewport.zoomAt(detector.getFocusX(), detector.getFocusY(),
                                detector.getScaleFactor());
                        notifyCameraChanged();
                        invalidate();
                        return true;
                    }
                });
    }

    // ---- 外面接进来的东西 ----

    /**
     * 接上战场面板——界面上夹在 HUD 和按钮行中间的那个空占位
     * （{@code activity_main.xml} 里的 {@code @id/play_area}）。
     *
     * <p>通常在 {@code onCreate} 里调，那时还没布局，量不出东西；
     * 真正生效的是随后那一次 layout。这里挂的 layout 监听是<b>必须的</b>：
     * 面板挪位置（HUD 折行、状态栏高度变了）不会改变本 View 的尺寸
     * （它是铺满的），只靠 {@code onSizeChanged} 会漏掉。
     */
    public void setPlayArea(View area) {
        if (playArea == area) {
            return;
        }
        if (playArea != null) {
            playArea.removeOnLayoutChangeListener(playAreaLayoutListener);
        }
        playArea = area;
        if (area != null) {
            area.addOnLayoutChangeListener(playAreaLayoutListener);
        }
        measurePlayArea();
    }

    private final View.OnLayoutChangeListener playAreaLayoutListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    measurePlayArea();

    public void setSceneBuilder(SceneBuilder builder) {
        this.sceneBuilder = builder;
        rebuildSceneIfPossible();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 接上商店的账。不接 = 放什么都免费（测试和布局预览就是这种状态）。 */
    public void setBuildGate(BuildGate gate) {
        this.buildGate = gate;
        invalidate();
    }

    /** 这件现在买得起吗。没接账、或者是在挪核心（不要钱），一律算买得起。 */
    private boolean affordable(BuildingType type) {
        return buildGate == null || buildGate.canAfford(type);
    }

    /**
     * 接上整页背景，让它跟着同一个相机走。
     *
     * <p>接上的同时会把当前相机推一次：外面通常是在 {@code onCreate} 里调的，
     * 那时还没量尺寸（{@code viewport} 还是 null），推这一次等于什么都没做，
     * 真正生效的是第一次 {@code onSizeChanged}。
     */
    public void setCameraListener(CameraListener listener) {
        this.cameraListener = listener;
        notifyCameraChanged();
    }

    /**
     * 相机变了就叫一声。**凡是动了 {@code viewport} 的地方都要调这个**，
     * 漏掉一处，那一处的操作背景就不会跟着动——表现是"拖的时候石头不动，
     * 但捏的时候动"，很难查。
     */
    private void notifyCameraChanged() {
        if (cameraListener != null && viewport != null && board != null) {
            cameraListener.onCameraChanged(viewport, board);
        }
    }

    /**
     * 选中要放哪种建筑；传 {@code null} 变成"只看不放"。
     *
     * <p>值没变就不发通知：{@code handleTap} 每放下一座都会调这个收手，
     * 手上本来就是空的时候（比如点空地收面板）不该惊动外面。
     */
    public void setSelected(BuildingType type) {
        if (this.selected == type) {
            return;
        }
        this.selected = type;
        // 拎起一件要放新的，就不再是"正在挪场上那座"了——两个模式互斥，
        // 见 #moving 的注释。
        if (type != null) {
            this.moving = null;
        }
        // 自动收手（放下之后传 null）也走这条路，不另开一个静默入口：
        // 少一条路径就少一处会跟外面说岔的地方，而重建三行货架的开销可以忽略。
        if (inspector != null) {
            inspector.onSelectedChanged(type);
        }
        invalidate();
    }

    /**
     * 进入"挪动"模式：下一次点空地就把这一座挪过去。
     *
     * <p><b>挪动不花钱</b>，所以这条路完全不经过 {@link BuildGate}——
     * 那个关卡问的是"这件买得起吗"，而挪一座已有的建筑不产生任何开销
     * （{@code Battlefield.move}）。
     *
     * <p>进来的时候会把手上那件放下、把查看目标清掉：三个状态
     * （手上拿着什么 / 正在看什么 / 正在挪什么）同时亮两个，
     * 地上会叠出两套高亮，玩家也说不清点下去会发生什么。
     *
     * <p>传 {@code null} 等于取消挪动。
     */
    public void startMoving(Building building) {
        this.moving = building;
        if (building != null) {
            // 走 inspect(null) 而不是直接写字段：这样外面会收到"没在看谁了"，
            // 详情面板跟着关掉。直接写字段的话面板会挂在那儿，而地上已经
            // 换成挪动虚影了——两套高亮指着一座建筑，玩家说不清点下去会怎样。
            inspect(null);
            setSelected(null);
        }
        invalidate();
    }

    /** 正在挪的那一座，没在挪时是 {@code null}。 */
    public Building moving() {
        return moving;
    }

    /** 接上"点了什么 / 手上拿着什么"的回调。不接 = 纯看，不弹任何面板。 */
    public void setInspector(Inspector inspector) {
        this.inspector = inspector;
    }

    /** 正在查看的那座建筑，没在看谁时是 {@code null}。 */
    public Building inspected() {
        return inspected;
    }

    /**
     * 手上现在拿着哪种建筑，空手时是 {@code null}。
     *
     * <p>给商店用的：面板上要标出"这一件正拿在手上"，不然选了之后
     * 面板和平时长得一模一样，玩家会以为没选上、再点一次。
     */
    public BuildingType selected() {
        return selected;
    }

    /**
     * 外面（升级成功、建筑被拆）把查看目标换掉或撤掉。
     *
     * <p><b>要传建筑本身，不能只传坐标。</b>升级之后等级变了，
     * 高亮和范围圈都得按新的等级重画。
     */
    public void setInspected(Building building) {
        this.inspected = building;
        invalidate();
    }

    public Battlefield battlefield() {
        return battlefield;
    }

    /**
     * 发下一波。发出去就返回 {@code true}。
     *
     * <p><b>发不发得出去由引擎说了算</b>（{@code Battlefield.spawnWave}）：
     * 上一波还没清完、五波都发过了、或者这一局已经结束，都不发。
     * 这里只是"叫一下"并且把帧钟归零。
     *
     * <p>帧钟要归零：玩家可能在两波之间盯着屏幕想了半分钟，不归零的话
     * {@link #tick} 会拿这半分钟当一帧的 dt——虽然上面有
     * {@link #MAX_FRAME_SECONDS} 兜着不会真的瞬移，但第一帧的步长仍然该从零起算。
     *
     * @return 真发出去了返回 {@code true}
     */
    public boolean startWave(Random random) {
        if (battlefield == null || !battlefield.spawnWave(random)) {
            return false;
        }
        lastFrameMs = 0L;
        // 战况变了（波数 +1、场上多了几只），但它要等下一帧 onDraw 才会报出去。
        // 这一帧可能还得等一会儿才有（比如刚发完波玩家没碰屏幕），所以先主动推一次。
        notifyStatus();
        invalidate();
        return true;
    }

    /**
     * 重开一局：把这个场景<b>从头造一遍</b>。
     *
     * <p>走的是和开局同一条路（{@link #rebuildSceneIfPossible}），不是"把现有的清空"——
     * 后者只能回到"一片空地"，而开局那个演示场景（核心、两道墙、六座塔）
     * 是 {@link SceneBuilder} 决定的，只有让 builder 重跑一遍才回得到那个局面。
     * 顺带把相机拨回最右边、把详情面板收掉、把帧钟归零，全是重开该做的。
     */
    public void restart() {
        rebuildSceneIfPossible();
    }

    // ---- 尺寸与场景 ----

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 自己多大不重要（反正铺满），重要的是面板在哪儿、多大
        measurePlayArea();
    }

    /**
     * 量出战场面板的位置和大小，据此重建几何和相机。
     *
     * <p>面板的大小决定格子大小（{@link BoardGeometry#fit}），面板的位置决定
     * 画的时候要平移多少。**两者都会变**——HUD 折行、状态栏高度、手势条都能
     * 让面板挪位置，而这些都不会让本 View 的尺寸变化（它是铺满的）。
     * 所以除了 {@code onSizeChanged}，面板自己的 layout 变化也要触发这里。
     */
    private void measurePlayArea() {
        if (playArea == null || getWidth() <= 0) {
            return;
        }
        playArea.getLocationInWindow(areaLocation);
        getLocationInWindow(ownLocation);
        int left = areaLocation[0] - ownLocation[0];
        int top = areaLocation[1] - ownLocation[1];
        int w = playArea.getWidth();
        int h = playArea.getHeight();
        if (w <= 0 || h <= 0) {
            return;   // 还没布局完
        }

        boolean moved = left != playLeft || top != playTop;
        playLeft = left;
        playTop = top;
        playWidth = w;
        playHeight = h;

        BoardGeometry fitted = BoardGeometry.fit(w, h);
        // 尺寸没实质变化（比如只是差一个像素）就不重来，免得把摆好的东西清掉
        if (board != null && board.cols() == fitted.cols()
                && Math.abs(board.cellPx() - fitted.cellPx()) < 0.5f) {
            if (moved) {
                // 相机数值没变，但背景要按新的面板位置重画——借这个回调让它失效。
                notifyCameraChanged();
                invalidate();
            }
            return;
        }

        board = fitted;
        viewport = new Viewport(w, h, board.boardWidthPx(), board.contentHeightPx());
        rebuildSceneIfPossible();
    }

    private void rebuildSceneIfPossible() {
        if (board == null || sceneBuilder == null) {
            return;
        }
        battlefield = sceneBuilder.create(board);
        // 旧的一场已经没了，手里那些引用（正在看的、正在挪的）指的是上一批
        // Building：留着的话，下一次点击会拿着它去问新战场，然后红闪一下说
        // "挪不过去"——而玩家眼里那一座明明还在那儿（新场上的那一座是另一个对象）。
        // 顺手也把详情面板收掉：它讲的那座建筑已经不在了。
        inspect(null);
        startMoving(null);
        // 红闪也是指着一个坐标的，那个坐标在新战场上可能是别人的地盘
        rejectedTopLeft = null;
        lastBuildings = lastEnemies = lastWaves = lastLeaks = -1;
        lastCoreHp = -1;
        lastOutcome = null;
        lastFrameMs = 0L;
        // 开局取景贴最右边：核心和它右边的山区一起入画。为什么不跟核心走见 Viewport#showRightEdge
        if (viewport != null) {
            viewport.showRightEdge();
        }
        // 相机刚重建、又刚被 showRightEdge 挪过，所以通知放在最后——
        // 放在前面的话背景拿到的是"旧的取景 + 新的尺寸"，开局那一下会偏。
        notifyCameraChanged();
        // 新战场是"0 波、核心满血"，界面上的战况得跟着退回去。
        // 这一句同时会让结果卡片收起来（新的一局是 ONGOING）。
        notifyStatus();
        invalidate();
    }

    // ---- 触摸 ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (board == null || viewport == null) {
            return false;
        }

        // 这个 View 铺满全屏，而面板只是屏幕中间那一条、左上角还不在本 View 的
        // 原点上。玩法换算（相机、格子）全是相对面板的，所以先把事件平移成
        // **面板局部坐标**，后面所有 getX()/getY() 才是原来那套含义。
        MotionEvent local = MotionEvent.obtain(event);
        local.offsetLocation(-playLeft, -playTop);
        try {
            // 从面板外面按下去的（HUD 或按钮行那两条的空白处，事件会落到这一层）
            // 不归战场管——不然点一下 HUD 的空白，别处会冒出一座塔。
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && (local.getX() < 0f || local.getX() >= playWidth
                    || local.getY() < 0f || local.getY() >= playHeight)) {
                return false;
            }
            return handleTouch(local);
        } finally {
            local.recycle();
        }
    }

    /** 坐标已经是面板局部的，见 {@link #onTouchEvent}。 */
    private boolean handleTouch(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                pressed = true;
                dragging = false;
                updateGhost(downX, downY);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress()) {
                    // 双指缩放的时候不平移，两件事同时做会打架
                    pressed = false;
                    ghostTopLeft = null;
                    invalidate();
                    return true;
                }
                if (pressed && !dragging
                        && (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop)) {
                    dragging = true;
                    ghostTopLeft = null;   // 开始拖了就不是在选位置
                }
                if (dragging) {
                    viewport.panBy(event.getX() - lastX, event.getY() - lastY);
                    notifyCameraChanged();
                } else {
                    updateGhost(event.getX(), event.getY());
                }
                lastX = event.getX();
                lastY = event.getY();
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (!dragging && pressed && !scaleDetector.isInProgress()) {
                    handleTap(event.getX(), event.getY());
                }
                pressed = false;
                dragging = false;
                ghostTopLeft = null;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                dragging = false;
                ghostTopLeft = null;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * 现在这一下点击是"挪核心"还是"放新的"。
     *
     * <p>场上有核心时，再选核心工具点一下就是<b>挪</b>它——核心只有一座。
     * 判定和放新的共用一套占位规则，区别只是要不要把旧的那座让开。
     *
     * <p>注意这条老路（在商店里选 Core）和新的 {@link #moving} 是两套并存的挪法：
     * 这里是"选中核心这一类，点哪儿挪哪儿"，那里是"指定场上这一座，点哪儿挪哪儿"。
     * 后者对所有建筑都成立，前者只对核心成立。留着它是因为商店里那行文案
     * （"move the one you have"）已经把这条路讲给玩家了，删掉会让那句话落空。
     */
    private boolean movingCore() {
        return selected == BuildingType.CORE && battlefield != null
                && battlefield.core() != null;
    }

    /** 手指下面会放出哪块占地、放不放得下。 */
    private void updateGhost(float screenX, float screenY) {
        if (moving != null) {
            updateMoveGhost(screenX, screenY);
            return;
        }
        if (selected == null) {
            ghostTopLeft = null;
            return;
        }
        int col = board.colAt(viewport.toLayoutX(screenX));
        int row = board.rowAt(viewport.toLayoutY(screenY));
        Cell topLeft = board.topLeftFor(col, row, selected.cols, selected.rows);
        // 两种"放不下"画的是同一块红框：这里没空地，或者买不起。
        // 对玩家来说区别不大（都是"这一下不成立"），但只有真的按下去才提示是哪种。
        ghostOk = (battlefield == null || battlefield.canPlace(
                selected, topLeft.col, topLeft.row,
                movingCore() ? battlefield.core() : null))
                && (movingCore() || affordable(selected));
        ghostTopLeft = topLeft;
    }

    /**
     * 挪动时的虚影：位置用被挪那一座的占地大小算，判定要把它自己让开。
     *
     * <p><b>没有"买得起吗"这一问</b>——挪动不花钱（{@code Battlefield.move}）。
     * 判定这里只有"放不下"一种不成立。
     */
    private void updateMoveGhost(float screenX, float screenY) {
        int col = board.colAt(viewport.toLayoutX(screenX));
        int row = board.rowAt(viewport.toLayoutY(screenY));
        Cell topLeft = board.topLeftFor(col, row, moving.type.cols, moving.type.rows);
        ghostOk = battlefield == null || battlefield.canPlace(
                moving.type, topLeft.col, topLeft.row, moving);
        ghostTopLeft = topLeft;
    }

    /**
     * 这一下点击到底要干什么。<b>顺序是有讲究的，别调换。</b>
     *
     * <ol>
     *   <li><b>正在挪东西</b>的话走 {@link #handleMoveTap}，整条规则换一套；</li>
     *   <li><b>先看手指底下有没有建筑。</b>有就只是"查看"，到此为止——
     *       哪怕手上正拎着一座塔，也不在别人身上叠；</li>
     *   <li>没建筑、手上有东西 → 放。放成功了就<b>把手收起来</b>
     *       （一件商品放一次，想再放要回商店拿）；</li>
     *   <li>没建筑、手上也没东西 → 收面板。</li>
     * </ol>
     *
     * <p><b>为什么放完要自动收手。</b>不收的话手上一直拎着塔，之后每一次点空地
     * 都在花钱——想拖动地图看看，手指一放就少一座塔的钱。收起来之后
     * "点一下 = 一个动作"，和查看建筑是同一种手感。
     *
     * <p>手指点到的是建筑<b>占地范围内任一格</b>（{@code Battlefield.buildingAt}
     * 查的是整张占位表），所以 2×2 的塔点哪个角都算点到它。
     */
    private void handleTap(float screenX, float screenY) {
        if (battlefield == null) {
            return;
        }

        // 屏幕坐标 →（相机）→ 布局坐标 →（几何）→ 格子
        int col = board.colAt(viewport.toLayoutX(screenX));
        int row = board.rowAt(viewport.toLayoutY(screenY));

        if (moving != null) {
            handleMoveTap(col, row);
            return;
        }

        Building hit = battlefield.buildingAt(new Cell(col, row));

        if (hit != null) {
            inspect(hit);
            return;
        }

        if (selected == null) {
            inspect(null);
            return;
        }
        tryPlace(col, row);
    }

    /**
     * 挪动模式下点了一下。<b>这个模式只有两种出口：挪成功、或者点到一座建筑。</b>
     *
     * <ul>
     *   <li>点空地且放得下 → 挪过去，退出挪动模式；</li>
     *   <li>点空地但放不下（通道、山区、别人身上、出界）→ <b>红闪一下，留在挪动模式</b>。
     *       这里不能退出：想挪到墙边，手指点歪半格是常事，点歪一次就得回详情面板
     *       重新点一遍"Move"的话，这个功能没法用；</li>
     *   <li>点到建筑（包括<b>它自己现在待的地方</b>）→ 取消挪动，然后查看那一座。
     *       点自己 = "算了，就放这儿"，顺手把它的详情弹回来，符合"点建筑=查看"。</li>
     * </ul>
     */
    private void handleMoveTap(int col, int row) {
        Building hit = battlefield.buildingAt(new Cell(col, row));

        if (hit != null) {
            // 点到自己 = 取消；点到别人 = 换看别人。两种都是"这次挪动作废"。
            startMoving(null);
            inspect(hit);
            return;
        }

        Cell topLeft = board.topLeftFor(col, row, moving.type.cols, moving.type.rows);
        if (!battlefield.move(moving, topLeft.col, topLeft.row)) {
            rejectedTopLeft = topLeft;
            rejectedAtMs = System.currentTimeMillis();
            if (inspector != null) {
                inspector.onMoveBlocked(moving);
            }
            return;
        }

        // 挪完把详情弹回来：玩家的下一个动作十有八九是接着看它
        // （再挪一次、或者升级），而不是回商店。
        Building moved = moving;
        startMoving(null);
        inspect(moved);
    }

    /** 把查看目标换成这一座（{@code null} = 空地，收起面板）。 */
    private void inspect(Building building) {
        if (inspected == building) {
            return;
        }
        inspected = building;
        if (inspector != null) {
            inspector.onInspected(building);
        }
        invalidate();
    }

    private void tryPlace(int col, int row) {
        Cell topLeft = board.topLeftFor(col, row, selected.cols, selected.rows);

        boolean moving = movingCore();
        boolean paid = moving || affordable(selected);

        boolean ok = paid && (moving
                ? battlefield.moveCore(topLeft.col, topLeft.row)
                : battlefield.place(selected, topLeft.col, topLeft.row) != null);

        if (ok) {
            // 放不下不扣钱：先引擎判定、后成交，中间这一步就是为此分的。
            if (!moving && buildGate != null) {
                buildGate.spend(selected);
            }
            setSelected(null);
        } else {
            rejectedTopLeft = topLeft;
            rejectedAtMs = System.currentTimeMillis();
            if (!moving && buildGate != null) {
                buildGate.onBlocked(selected, paid);
            }
        }
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (battlefield == null || board == null || viewport == null) {
            return;
        }

        tick();
        notifyStatus();

        canvas.save();
        // ★ 这三句必须和 MountainBackgroundView.onDraw 里那三句一模一样。
        //   第一句是把原点搬到**战场面板的左上角**：面板夹在 HUD 和按钮行中间，
        //   不搬的话整片地会往上偏一整个 HUD 的高度。搬完之后画布原点就是
        //   面板左上角，后面两句才是"相机"。
        canvas.translate(playLeft, playTop);
        // 之后一律用布局坐标画：换算交给相机，几何交给 BoardGeometry
        canvas.translate(viewport.offsetX(), viewport.offsetY());
        canvas.scale(viewport.zoom(), viewport.zoom());

        // 注意这里**不画**上方那 1.5 格余量带。
        // 余地留是留着的（BoardGeometry.MAX_OVERHANG_CELLS，塔尖要伸进去），
        // 但**不归这个 View 画**：余量带是"不能放东西的地方"，按全页的规矩
        // 那就是山区，而整页的岩石底（MountainBackgroundView）已经用同一个
        // 相机把石头画在那儿了。这里再铺一块自己的颜色，等于在屏幕上多切一刀：
        // 面板上沿一条线、网格上沿又一条线，中间夹一条横带——一眼就是
        // "界面里嵌了一块游戏"，而不是一整片地。
        drawGround(canvas);
        drawGrid(canvas);
        drawRanges(canvas);
        drawSprites(canvas);
        // 子弹和血条画在建筑/敌人**上面**（下面那些都是地面上的东西）：
        // 这两个是"这一刻正在发生什么"的读数，被一座塔挡住就等于没画。
        drawProjectiles(canvas);
        drawBars(canvas);
        drawOverlay(canvas);

        canvas.restore();
    }

    /**
     * 推进一帧。场上有敌人、而且这一局还没结束，就继续要帧；否则停下（不白耗电）。
     *
     * <p>结束之后也不再要帧：{@code advance} 那时本来就什么都不做了，
     * 继续空转只会一直重画一张不会变的画面。
     */
    private void tick() {
        long now = android.os.SystemClock.uptimeMillis();

        // 第一帧只记时间不推进（dt 会是 0，advance 自己会忽略）。
        // 注意不能在这里 return：一旦 return 就漏掉了下面的"排下一帧"，
        // 结果是发完波只画一帧就停住，敌人永远停在画面外。
        if (lastFrameMs != 0L) {
            battlefield.advance(Math.min((now - lastFrameMs) / 1000f, MAX_FRAME_SECONDS));
        }
        lastFrameMs = now;

        // 天上还有子弹也要接着要帧。只判"场上还有没有敌人"的话，最后一波清空的
        // 那一刻画面就停了，而还在飞的那几发会**僵在半空**——它们得飞完、
        // 该结算的结算掉，这一波才算真的打完。
        boolean somethingMoving = !battlefield.enemies().isEmpty()
                || !battlefield.projectiles().isEmpty();
        if (!battlefield.decided() && somethingMoving) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * 地面：<b>三种地形三种画法</b>，让"这儿能不能放东西"不用点就知道。
     *
     * <p>颜色分工见 {@link Terrain}：可放置区是两格一换的绿格（能数格子）、
     * 左边敌人通道是土黄、右边山区是灰岩 + 斜纹。斜纹是特意加的——
     * 通道和山区同样不能建，只靠颜色区分不够稳（色弱、强光下都会糊），
     * 加一道纹路就变成"有没有斜纹"这个更硬的差别。
     *
     * <p>按"同地形的连续格"成段画，不是一格一格画：一帧只画几十个矩形，
     * 而不是 40×18 个。这条在低端机上是要紧的。
     */
    private void drawGround(Canvas canvas) {
        // 画笔是复用的，上一帧画半透明块留下的 alpha 会跟过来——每次都要显式设回去
        fill.setAlpha(255);
        float cell = board.cellPx();

        for (int row = 0; row < board.rows(); row++) {
            int col = 0;
            while (col < board.cols()) {
                Terrain terrain = battlefield.terrainAt(col, row);

                // 这一段有多长（同一行里连着多少格是同一种地形）
                int run = 1;
                while (col + run < board.cols()
                        && battlefield.terrainAt(col + run, row) == terrain) {
                    run++;
                }

                float top = board.topY(row);
                if (terrain == Terrain.BUILDABLE) {
                    // 可放置区：两格一换的浅色格，用来数格子
                    for (int i = 0; i < run; i++) {
                        fill.setColor((((row + col + i) & 1) == 0) ? groundA : groundB);
                        rect.set(board.leftX(col + i), top, board.leftX(col + i + 1), top + cell);
                        canvas.drawRect(rect, fill);
                    }
                } else {
                    fill.setColor(terrain == Terrain.ENEMY_LANE ? entryLane : mountain);
                    rect.set(board.leftX(col), top, board.leftX(col + run), top + cell);
                    canvas.drawRect(rect, fill);
                    if (terrain == Terrain.MOUNTAIN) {
                        drawRockHatch(canvas, col, run, row);
                    }
                }
                col += run;
            }
        }
    }

    /** 山区里那道斜纹。一格一道，方向统一，看着像岩面。 */
    private void drawRockHatch(Canvas canvas, int col, int run, int row) {
        stroke.setStrokeWidth(Math.max(1f, 1.2f * density));
        stroke.setColor(mountainShade);
        float cell = board.cellPx();
        float top = board.topY(row);
        float inset = cell * 0.22f;
        for (int i = 0; i < run; i++) {
            float left = board.leftX(col + i);
            canvas.drawLine(left + inset, top + cell - inset,
                    left + cell - inset, top + inset, stroke);
        }
    }

    private void drawGrid(Canvas canvas) {
        stroke.setStrokeWidth(Math.max(1f, 0.6f * density));
        stroke.setColor(gridLine);
        for (int col = 0; col <= board.cols(); col++) {
            canvas.drawLine(board.leftX(col), board.topY(0),
                    board.leftX(col), board.topY(board.rows()), stroke);
        }
        for (int row = 0; row <= board.rows(); row++) {
            canvas.drawLine(board.leftX(0), board.topY(row),
                    board.leftX(board.cols()), board.topY(row), stroke);
        }
    }

    /**
     * 建筑和敌人一起按"脚踩在哪"排序之后再画：越靠下的越后画，于是自然盖住上方的。
     *
     * <p>这就是正方形网格的好处——不需要按 {@code row + col} 之类的怪规则排序。
     */
    private void drawSprites(Canvas canvas) {
        List<Object> sprites = new ArrayList<>(battlefield.buildings().size()
                + battlefield.enemies().size());
        sprites.addAll(battlefield.buildings());
        sprites.addAll(battlefield.enemies());
        sprites.sort(Comparator.comparingDouble(this::anchorYOf));

        for (Object sprite : sprites) {
            if (sprite instanceof Building) {
                drawBuilding(canvas, (Building) sprite);
            } else {
                drawEnemy(canvas, (Enemy) sprite);
            }
        }
    }

    /** 这个东西"脚踩"的 y（布局坐标）——排序用，也是贴图锚点的纵向位置。 */
    private float anchorYOf(Object sprite) {
        if (sprite instanceof Building) {
            Building b = (Building) sprite;
            return board.anchorY(b.row(), b.type.rows);
        }
        Enemy e = (Enemy) sprite;
        // y 是身体中心，加半个身位就是脚底。跨行的时候 y 是小数，
        // 这里跟着连续走，所以换行的敌人不会在两行之间跳。
        return board.yAt(e.y + e.type.halfBodyCells());
    }

    private void drawBuilding(Canvas canvas, Building building) {
        int color;
        switch (building.type) {
            case CORE:
                color = coreColor;
                break;
            case WALL:
                color = wallColor;
                break;
            default:
                color = towerColor;
                break;
        }

        float left = board.leftX(building.col());
        float right = board.leftX(building.col() + building.type.cols);
        float bottom = board.anchorY(building.row(), building.type.rows);
        float top = bottom - building.type.spriteHeightCells() * board.cellPx();

        drawBlock(canvas, left, top, right, bottom,
                board.topY(building.row()), color, building.type.label);
    }

    /**
     * 一只敌人：<b>一块实心色块，大小和颜色都由种类决定</b>。
     *
     * <p><b>为什么不走 {@link #drawBlock}。</b>那个方法是给建筑写的，它身上带着两件
     * 建筑才有的东西：向上探出的那截画成半透明（"悬在空中"的塔尖）、占地顶边画一条
     * 虚线（"从这儿往下才是能放东西的格子"）。敌人既没有悬空的部分、也没有占地，
     * 套用之后每只敌人头上都顶着一条半透明的横带和一条虚线——看着像戴了帽子，
     * 而且那条虚线在敌人身上什么也不表示。
     *
     * <p><b>颜色和体格都在说玩法。</b>三种敌人一眼要能分出来：杂兵红、快兵黄
     * （细一圈）、重甲紫（比一格还宽）。玩家得在敌人进射程之前就知道
     * "这一只要不要多调一座塔过来"，而不是等血条掉得慢才发现。
     *
     * <p><b>卡住了（{@code blocked}）画成压暗的同色</b>，不换色：颜色本身在回答
     * "这是哪一种"——那正是敌人贴着你的墙、最需要看清的时候，不能因为"卡住了"
     * 就把种类信息丢掉。压暗一格就够说明"它停下来了"。
     */
    private void drawEnemy(Canvas canvas, Enemy enemy) {
        float cell = board.cellPx();
        float body = enemy.type.bodyCells * cell;
        float centreX = board.xAt(enemy.x);
        // 身体以 (x, y) 为中心画。y 是连续的行坐标，所以换行途中身体是平滑移过去的
        float centreY = board.yAt(enemy.y);
        float top = centreY - body / 2f;
        float bottom = centreY + body / 2f;

        int color = enemyColors[enemy.type.ordinal()];
        fill.setColor(enemy.blocked ? enemyBlockedColors[enemy.type.ordinal()] : color);
        fill.setAlpha(255);
        float radius = body * 0.22f;
        rect.set(centreX - body / 2f, top, centreX + body / 2f, bottom);
        canvas.drawRoundRect(rect, radius, radius, fill);

        stroke.setStrokeWidth(Math.max(1f, 0.8f * density));
        stroke.setColor(spriteLine);
        canvas.drawRoundRect(rect, radius, radius, stroke);
    }

    /**
     * 把颜色压暗到七成，用来表示"卡住了"。见 {@link #drawEnemy} 里为什么是压暗不是换色。
     *
     * <p>三种暗色在构造时算一次存进 {@link #enemyBlockedColors}：这个方法本来会出现在
     * 每帧、每只敌人的绘制路径上，而它算出来的东西一局里根本不会变。
     */
    private static int dim(int color) {
        int r = (int) (Color.red(color) * 0.7f);
        int g = (int) (Color.green(color) * 0.7f);
        int b = (int) (Color.blue(color) * 0.7f);
        return Color.rgb(r, g, b);
    }

    /**
     * 天上那些子弹：一颗一颗画出来。
     *
     * <p><b>为什么画它。</b>塔打的是子弹（{@code Projectile}），伤害要飞一段才落地，
     * 所以"这一发是从哪座塔打向哪一只"是玩家判断"哪座塔在干活、哪座塔白摆了"
     * 的唯一依据。从前那条"塔→目标"的连线说不了这件事：它只说"这一刻在打谁"，
     * 说不出"打出去的东西还没到"，于是敌人掉血看着像是凭空发生的。
     *
     * <p><b>只画引擎说在飞的那些</b>（{@code Battlefield.projectiles()}），
     * 位置直接读 {@code Projectile.x/y}——一套坐标，两边不用对齐。
     * 因此这里没有"猜子弹该在哪儿"的余地：引擎和画面读的是同一个数。
     *
     * <p>画在敌人和建筑<b>上面</b>：底下压着的正是它要打的那只敌人，
     * 画在下面的话子弹飞进敌人身子的那几帧就看不见了。
     *
     * <p><b>换成真贴图只动这里。</b>现在是"一个圆点"，真贴图来了就是把下面那个
     * {@code drawCircle} 换成 {@code drawBitmap}，圆心对准 {@code (x, y)}；
     * 尺寸见 {@link #SHOT_CELLS}（规格在 {@code ART.md}）。引擎那边一个字都不用改——
     * 它只知道"有一颗子弹在这个坐标上"。
     */
    private void drawProjectiles(Canvas canvas) {
        // 每帧都调，没子弹时直接走：这一层是唯一一处"通常什么也不画"的绘制
        if (battlefield.projectiles().isEmpty()) {
            return;
        }
        float radius = SHOT_CELLS * board.cellPx() / 2f;

        fill.setColor(shotColor);
        fill.setAlpha(SHOT_ALPHA);

        for (Projectile shot : battlefield.projectiles()) {
            canvas.drawCircle(board.xAt(shot.x), board.yAt(shot.y), radius, fill);
        }

        // 画笔是共用的，用完还回去——别让这一层的不透明度跟着后面画的东西
        fill.setAlpha(255);
    }

    /**
     * 血条：敌人的、建筑的，还有核心的。
     *
     * <p><b>只有被打过的才画</b>（{@link Building#damaged()} / 血量不满），
     * 核心除外——它<b>一直画</b>。
     *
     * <p>理由是屏幕上条太多了：满血的建筑有十几座，一波敌人有十几只，
     * 人手一条绿杠的话，这些条会连成一片横线，把地上的格子、敌人的位置全盖住。
     * 而"没被打过"本来就是默认状态，不需要每帧再说一遍。
     *
     * <p>核心反过来：它是这一局的命（掉了就直接结束），所以它还剩多少要随时看得见，
     * 哪怕满血——"核心满血"本身就是一条有用的信息（说明还没漏过怪）。
     *
     * <p><b>位置在贴图顶端的上方</b>，不是占地顶边。贴图是向上探出的
     * （{@code BuildingType.overhangCells}），压在贴图上等于给塔身糊一道横条；
     * 放在贴图上方，条和它标的那座建筑才是一组。
     */
    private void drawBars(Canvas canvas) {
        float cell = board.cellPx();
        float gap = BAR_GAP_CELLS * cell;

        for (Enemy enemy : battlefield.enemies()) {
            if (enemy.hpFraction() >= 1f) {
                continue;
            }
            // 宽度跟着体格走：大块头配一条一格宽的小条，看着像旁边那只的
            float body = enemy.type.bodyCells * cell;
            // 条压在头顶：身体上边缘 = 中心 - 半个身位，也就是 drawEnemy 里的 top
            float top = board.yAt(enemy.y) - body / 2f;
            drawBar(canvas, board.xAt(enemy.centreX()), top - gap,
                    body * BAR_WIDTH_RATIO, enemy.hpFraction());
        }

        for (Building building : battlefield.buildings()) {
            if (building.type != BuildingType.CORE && !building.damaged()) {
                continue;
            }
            float bottom = board.anchorY(building.row(), building.type.rows);
            float top = bottom - building.type.spriteHeightCells() * cell;
            drawBar(canvas, board.xAt(building.col() + building.type.cols / 2f), top - gap,
                    building.type.cols * cell * BAR_WIDTH_RATIO, building.hpFraction());
        }
    }

    /**
     * 一条血条。{@code bottomY} 是它底边的 y，条往上长。
     *
     * <p>宽度按占地来（见 {@link #drawBars}），槽是固定的深色，免得条短的时候
     * 分不清"这一格是空的"还是"这里根本没有条"。
     */
    private void drawBar(Canvas canvas, float centreX, float bottomY,
                         float width, float fraction) {
        float height = BAR_HEIGHT_CELLS * board.cellPx();
        float half = width / 2f;
        float top = bottomY - height;

        // 槽：颜色自带透明度，所以这里**不能**再调 setAlpha——那样会把它改掉
        fill.setColor(hpTrack);
        rect.set(centreX - half, top, centreX + half, bottomY);
        canvas.drawRect(rect, fill);

        float filled = Math.max(0f, Math.min(1f, fraction));
        if (filled > 0f) {
            fill.setColor(hpColor(filled));
            rect.set(centreX - half, top, centreX - half + width * filled, bottomY);
            canvas.drawRect(rect, fill);
        }

        // 描个边：血条底下是绿的草、灰的石头、还是深色的敌人，都有可能，
        // 不加边的话深色的那条槽在某些地形上会糊掉
        stroke.setStrokeWidth(Math.max(1f, 0.5f * density));
        stroke.setColor(spriteLine);
        rect.set(centreX - half, top, centreX + half, bottomY);
        canvas.drawRect(rect, stroke);
    }

    /** 血条那一段的颜色：绿 → 黄 → 红。分三档够了，渐变反而看不清还剩几成。 */
    private int hpColor(float fraction) {
        if (fraction > HP_HEALTHY) {
            return hpGood;
        }
        return fraction > HP_HURT ? hpWarn : hpLow;
    }

    /**
     * 画一个"贴图位"。
     *
     * <p>分两段：{@code footprintTop} 以下是<b>占地</b>（脚踩的那片地面，实心），
     * 以上到 {@code top} 是<b>向上探出的部分</b>（半透明 + 虚线）。
     * 这段半透明区域就是真贴图里塔尖会占的地方——美术出图时的高度按它来。
     */
    private void drawBlock(Canvas canvas, float left, float top, float right, float bottom,
                           float footprintTop, int color, @Nullable String text) {
        float radius = Math.min(board.cellPx(), (bottom - top)) * 0.12f;

        // 向上探出的部分：半透明，虚线标出它到哪儿为止
        fill.setColor(color);
        fill.setAlpha(70);
        rect.set(left, top, right, footprintTop);
        canvas.drawRoundRect(rect, radius, radius, fill);

        // 占地：实心
        fill.setColor(color);
        fill.setAlpha(255);
        rect.set(left, footprintTop, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, fill);

        // 整块贴图的轮廓
        stroke.setStrokeWidth(Math.max(1f, 0.8f * density));
        stroke.setColor(spriteLine);
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, stroke);

        // 占地顶边：这条线以上是"悬在空中"的部分，写实贴图里就是塔身和塔尖
        dashed.setStrokeWidth(Math.max(1f, 0.8f * density));
        canvas.drawLine(left, footprintTop, right, footprintTop, dashed);

        if (text != null && !text.isEmpty()) {
            // 文字按屏幕大小画：画布被缩放了 zoom 倍，所以这里先除掉
            label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f,
                    getResources().getDisplayMetrics()) / viewport.zoom());

            // 一格只有二十几 dp，而"Wall"这个单词比一格还宽，直接画会糊到隔壁格子上，
            // 把要看的格子边界盖掉。所以放不下就退成首字母，首字母也放不下就干脆不画——
            // 占位阶段方块颜色已经能分清谁是谁，标签只是辅助。
            float room = (right - left) - 2f * density;
            String shown = label.measureText(text) <= room ? text : text.substring(0, 1);
            if (label.measureText(shown) > room) {
                shown = null;
            }
            if (shown != null) {
                float centreY = footprintTop + (bottom - footprintTop) / 2f
                        - (label.descent() + label.ascent()) / 2f;
                canvas.drawText(shown, (left + right) / 2f, centreY, label);
            }
        }
    }

    /**
     * 攻击范围圈：<b>正在查看的那座塔</b>，以及手上那座塔的预放位置。
     *
     * <p><b>画在建筑底下</b>（在 {@link #drawSprites} 之前），不是盖在上面：
     * 这是"地面上的一块标记"，压在塔身上既糊住塔，半透明色块叠在实心方块上也显脏。
     *
     * <p>范围数值来自 {@link BuildingStats}，单位是格，这里乘格子大小换成像素——
     * 所以放大缩小时圈跟着地一起变，这才是对的：它标的是"打得到几格远"，
     * 不是"屏幕上多大一块"。
     */
    private void drawRanges(Canvas canvas) {
        if (inspected != null) {
            drawRangeCircle(canvas, inspected.type, inspected.col(), inspected.row(),
                    BuildingStats.rangeCells(inspected.type, inspected.level), rangeColor);
        }
        // 正在挪的那一座：**它现在待的地方**也画一圈，一直画到落下去为止。
        // 只画手指底下那块虚影是不够的——手指一抬，屏幕上就没有任何东西说明
        // "正在挪的是哪一座、它原来在哪儿"，玩家得自己记。两个圈一个说
        // "从这儿"（金）、一个说"到这儿"（绿/红），挪动的得失一眼看得出来。
        if (moving != null) {
            drawRangeCircle(canvas, moving.type, moving.col(), moving.row(),
                    BuildingStats.rangeCells(moving.type, moving.level), rangeColor);
        }
        // 手上拎着塔的时候也画一圈：不然"这座塔放这儿能打到哪条路"只能靠猜，
        // 而摆位是这游戏唯一的策略动作。
        BuildingType ghost = ghostType();
        if (ghostTopLeft != null && ghost != null) {
            drawRangeCircle(canvas, ghost, ghostTopLeft.col, ghostTopLeft.row,
                    BuildingStats.rangeCells(ghost, ghostLevel()), ghostOk ? okColor : badColor);
        }
    }

    /**
     * 手指底下那块虚影是哪一类建筑；既没拿东西也没在挪时是 {@code null}。
     *
     * <p>画虚影的三处（范围圈、占地轮廓、红闪）都得知道"现在这块虚影是谁"，
     * 而它有两个来路：{@link #selected}（要放新的）和 {@link #moving}（要挪旧的）。
     * 与其在三处各写一遍 {@code moving != null ? ... : ...}，不如收在这一个方法里。
     */
    @Nullable
    private BuildingType ghostType() {
        return moving != null ? moving.type : selected;
    }

    /**
     * 虚影该按几级算。
     *
     * <p>要放新的那座一定是 1 级（还没放下去），要挪的那座可能已经升过了——
     * 所以挪一座 3 级塔时圈是 3.5 格，不是 2.5。用 1 级算的话，玩家会以为
     * 挪一下就掉级了。
     */
    private int ghostLevel() {
        return moving != null ? moving.level : 1;
    }

    /**
     * 一个圈。{@code rangeCells <= 0}（这种建筑不会攻击）就什么都不画。
     *
     * <p>圆心取占地的<b>正中</b>，不是某一格的中心：2×2 的塔圆心落在四格交点上，
     * 取任何一格都会让圈整体偏半格，看着像是射程不对称。
     */
    private void drawRangeCircle(Canvas canvas, BuildingType type, int col, int row,
                                 double rangeCells, int color) {
        if (rangeCells <= 0) {
            return;
        }
        float radius = (float) rangeCells * board.cellPx();
        float centreX = board.xAt(col + type.cols / 2f);
        float centreY = board.yAt(row + type.rows / 2f);

        fill.setColor(color);
        fill.setAlpha(RANGE_FILL_ALPHA);
        canvas.drawCircle(centreX, centreY, radius, fill);

        stroke.setStrokeWidth(Math.max(1f, 1.6f * density));
        stroke.setColor(color);
        stroke.setAlpha(RANGE_LINE_ALPHA);
        canvas.drawCircle(centreX, centreY, radius, stroke);

        // 画笔是共用的，用完把 alpha 还回去：不还的话后面那些不显式设 alpha
        // 的描边（轮廓、网格）会跟着变淡。这个坑 drawGround 顶上就踩过一次。
        stroke.setAlpha(255);
    }

    /** 手指下面的预放位置，以及放不下时的红闪。 */
    private void drawOverlay(Canvas canvas) {
        // 正在查看的那座：**只描边、不填色**。填色和它底下那个范围圈会叠成两层
        // 半透明，把建筑本身糊成灰绿色（真机上撞到过，青色的塔看着像橄榄色）。
        // 而且贴图接上之后，任何一层填色都是盖在美术上的脏东西——
        // "选中"这件事让金边和一个圆去说就够了。
        if (inspected != null) {
            drawFootprintOutline(canvas, new Cell(inspected.col(), inspected.row()),
                    inspected.type, rangeColor, NO_FILL);
        }
        // 正在挪的那一座：脚下也一直亮着金边，理由同 drawRanges——
        // 手指抬着的时候得有东西告诉玩家"挪的是这一座"。
        if (moving != null) {
            drawFootprintOutline(canvas, new Cell(moving.col(), moving.row()),
                    moving.type, rangeColor, NO_FILL);
        }
        BuildingType ghost = ghostType();
        if (ghostTopLeft != null && ghost != null) {
            // 虚影盖住了它自己现在待的地方（挪动时点回原地就是这么回事），
            // 不填色才看得见底下那座建筑——填了等于把它涂掉，
            // 玩家会以为它已经没了。
            int fillAlpha = (moving != null && ghostTopLeft.equals(
                    new Cell(moving.col(), moving.row()))) ? NO_FILL : GHOST_FILL_ALPHA;
            drawFootprintOutline(canvas, ghostTopLeft, ghost,
                    ghostOk ? okColor : badColor, fillAlpha);
        }
        if (rejectedTopLeft != null && ghost != null) {
            long age = System.currentTimeMillis() - rejectedAtMs;
            if (age < REJECT_FLASH_MS) {
                drawFootprintOutline(canvas, rejectedTopLeft, ghost, badColor,
                        REJECT_FILL_ALPHA);
                postInvalidateOnAnimation();
            } else {
                rejectedTopLeft = null;
            }
        }
    }

    private void drawFootprintOutline(Canvas canvas, Cell topLeft, BuildingType type,
                                      int color, int fillAlpha) {
        if (fillAlpha > 0) {
            fill.setColor(color);
            fill.setAlpha(fillAlpha);
            rect.set(board.leftX(topLeft.col), board.topY(topLeft.row),
                    board.leftX(topLeft.col + type.cols), board.topY(topLeft.row + type.rows));
            canvas.drawRect(rect, fill);
        }

        rect.set(board.leftX(topLeft.col), board.topY(topLeft.row),
                board.leftX(topLeft.col + type.cols), board.topY(topLeft.row + type.rows));
        stroke.setStrokeWidth(Math.max(1f, 1.6f * density));
        stroke.setColor(color);
        canvas.drawRect(rect, stroke);
    }

    /**
     * 战况有变化才通知外面。
     *
     * <p>核心耐久比的是<b>取整之后</b>的数（{@link Math#round(float)}），
     * 和 {@link BattleStatus#coreHp} 对得上。拿那个 {@code float} 直接比的话，
     * 一只敌人在啃核心时每帧都差一点点，于是这个回调一秒钟会响六十次，
     * 每次都去 {@code setText} 一遍——而屏幕上那个数字根本就没变。
     * 场上那根血条用的是真值（{@code drawBars} 直接读），所以这里取整不丢东西。
     */
    private void notifyStatus() {
        if (listener == null || battlefield == null) {
            return;
        }
        int buildings = battlefield.buildings().size();
        int enemies = battlefield.enemies().size();
        int waves = battlefield.waves();
        int leaks = battlefield.leaks();
        Building core = battlefield.core();
        int coreHp = core == null ? 0 : Math.round(Math.max(0f, core.hp));
        Battlefield.Outcome outcome = battlefield.outcome();

        if (buildings == lastBuildings && enemies == lastEnemies
                && waves == lastWaves && leaks == lastLeaks
                && coreHp == lastCoreHp && outcome == lastOutcome) {
            return;
        }
        lastBuildings = buildings;
        lastEnemies = enemies;
        lastWaves = waves;
        lastLeaks = leaks;
        lastCoreHp = coreHp;
        lastOutcome = outcome;
        listener.onStatusChanged(BattleStatus.of(battlefield));
    }

    // ---- 给界面读的当前状态 ----

    public int columns() {
        return board == null ? 0 : board.cols();
    }

    public int rows() {
        return board == null ? 0 : board.rows();
    }

    /** 一格多少 dp。调试和"格子是不是太小了"的判断都用它。 */
    public float cellDp() {
        return board == null ? 0f : board.cellPx() / density;
    }

    public float zoom() {
        return viewport == null ? 1f : viewport.zoom();
    }
}
