package com.mobilegroup20.tokentrail.game.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 战场：把 {@link Battlefield} 画出来，并处理缩放、拖动、点一下放建筑。
 *
 * <h2>先画色块定尺寸，再一张张换成贴图</h2>
 * <p>这个类最早<b>一个图片都不加载</b>：每个东西画成一个纯色矩形，
 * 位置、占地、高度全部按真贴图将来会占的地方画。目的是
 * <b>在画图之前先把尺寸和锚点确认掉</b>——如果 2×2 的塔在这个画面上看着
 * 大小合适、站在格子上不陷不飘，那美术按 {@code docs/ART.md} §2.1 出图就一定对。
 *
 * <p>所以这个类里每一处坐标都不是随手写的，全部来自
 * {@link BoardGeometry}：占地用 {@code leftX/topY}，锚点用
 * {@code anchorX/anchorY}（占地的<b>底边中点</b>），贴图高度用
 * {@link BuildingType#spriteHeightCells()}。换真贴图时确实只动了画法：
 * {@link #drawBuilding} 里 {@code drawBitmap} 铺的就是 {@code drawBlock} 原来
 * 那个矩形，几何和触摸逻辑一行没改。
 *
 * <p><b>贴图是一张张补上来的</b>：建筑走 {@link BuildingSprites}（两种塔和核心各三级、
 * 城墙一张），敌人走 {@link EnemySprites}（三种敌人各三种动作）。
 * 四种建筑的图 2026-09-27 齐了，所以 {@link #drawBlock} 那条色块路现在没有建筑会走到
 * ——**留着**，它是"新加一种建筑、图还没画"时的兜底，也是四种建筑当初一路换过来的路。
 *
 * <h2>它铺满全屏，但"战场在哪儿"是外面告进来的</h2>
 * <p>这个 View 是 {@code match_parent} 的，因为那片地本来就该能糊满整屏：
 * 真机上 36 行地不放大就有 36 × 53px ≈ 1915px，放大到 2 倍是 3830px，
 * 比整块屏幕（2844px）高出一截。只给它中间一条的话地形会被裁掉，
 * 放到多大都出不了那个框，屏幕上永远是"石头 / 绿地 / 石头"三段。
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

    /** 射界扇形里面那层填充的不透明度。压得太实会把地上的格子和草纹盖掉。 */
    private static final int RANGE_FILL_ALPHA = 46;

    /** 射界扇形那道线的不透明度。比填充实，才看得出射界从哪儿到哪儿。 */
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

    /**
     * 拖在手上的那座建筑<b>离地多高</b>，单位格。
     *
     * <p>不是装饰，是为了让手指别把它盖住：手指按在屏幕上，指肚那一块正好是
     * 建筑要落下去的地方，不抬起来的话玩家全程只看得见自己的手。
     * 抬起来之后地上那块绿/红的占地才是"落在哪儿"，悬在上面的贴图是"拿的是谁"
     * ——和部落冲突里拎起一座建筑的样子是同一件事。
     *
     * <p>抬 1 格：再多就离那块占地太远，看着像两件东西；再少则盖不住一个指肚。
     */
    private static final float CARRY_LIFT_CELLS = 1.0f;

    /**
     * 敌人走多远算迈了一步，单位格。走路的两个姿势按<b>走过的距离</b>翻，不按时间。
     *
     * <p>按时间翻的话三种兵共用一个步频：快兵（1.6 格/秒）和重甲（0.55 格/秒）
     * 迈腿一样快，快兵看起来在"飘"——脚不动、人平移，是最典型的廉价感。
     * 按距离翻就不用管速度了：走得快自然迈得勤，<b>而且脚不打滑</b>。
     *
     * <p>0.7 格一步：杂兵（0.9 格/秒）约 1.3 步/秒，和真人走路差不多；
     * 重甲 0.8 步/秒，看着就沉。
     *
     * <p><b>距离用的是 {@link Enemy#travelled}（引擎记的路程），不是 {@code enemy.x}。</b>
     * 拿 {@code x} 除的时候，往右走看着一切正常——直到敌人<b>竖着换行</b>：
     * 那几格 {@code x} 一动不动，整只敌人平移着上去，腿一下都不迈。
     * 而敌人拐弯是常事（{@link PathField}），这是引擎唯一一处改坐标的地方记的账，
     * 斜着走和竖着走都算得准。
     */
    private static final float ENEMY_STRIDE_CELLS = 0.7f;

    /**
     * 啃建筑时抡一下键盘要多久（毫秒），以及这一下里"挥出去"占几成。
     *
     * <p>被挡住的时候引擎只给得出"卡住了"（{@code Enemy.blocked}），给不出
     * "啃到第几口了"——伤害是每帧连续扣的，没有"这一口"这个时刻。所以挥击这个动作
     * <b>是本机时钟驱动的装饰</b>，不来自引擎。走路那个不一样，它挂在位移上
     * （见 {@link #ENEMY_STRIDE_CELLS}），一分钱时钟都不用花。
     *
     * <p>剩下那四成显示站姿，读作"抡起来之前的收势"。一直显示挥击的话，
     * 那只敌人会<b>举着键盘僵在半空</b>——它一啃就是好几秒，看着像画面卡住了。
     */
    private static final long ENEMY_SWING_PERIOD_MS = 620L;

    /** 见 {@link #ENEMY_SWING_PERIOD_MS}。 */
    private static final float ENEMY_SWING_ATTACK_SHARE = 0.62f;

    /**
     * 敌人脚下那个椭圆的宽和高，占身位的比例。
     *
     * <p><b>它不是装饰，是三种敌人唯一的区分手段。</b>三张贴图是同一个人，
     * 只靠体格分（三种差 1.56 倍）不够——一只重甲单独出现在屏幕边上时，
     * 玩家认不出它，而"这一只要不要多调一座塔过来"正是要在它进射程之前就看清的事
     * （见 {@code EnemyType} 的类注释和 {@code colors.xml} 里那三条敌人色）。
     *
     * <p>画成脚下的一圈、而不是给贴图染色：贴图是几百种颜色的像素画，
     * 染一遍会糊成一团。脚下那圈既保住了画，又顺手当了接地阴影——
     * 没有影子的人看着是浮在草地上的。
     *
     * <p>宽 0.82 格（体格 1.0 时）：比人的肩略窄、比两只脚的外沿略宽，
     * 所以读作"脚踩在地上"而不是"人站在一个圈里"。高只有 0.27 格，
     * 压扁了才像躺在地上的椭圆，不压扁就成了一个竖着的靶子。
     */
    private static final float ENEMY_MARK_WIDTH = 0.82f;

    /** 见 {@link #ENEMY_MARK_WIDTH}。 */
    private static final float ENEMY_MARK_HEIGHT = 0.27f;

    /**
     * 椭圆那圈边的粗细，单位 dp（乘 {@link #density}，最小 1.5px）。
     *
     * <p>比建筑轮廓（0.8dp）粗：那是"描个边看得清"，这是<b>唯一的种类信号</b>，
     * 细了在缩到最小倍数时就断成一圈虚线了。环的颜色是种类色，
     * 里面的填色三种共用一条深色（{@code game_enemy_shadow}）——
     * 填色也分三种的话，脚下就成了一块实心的大色斑，比人还显眼。
     */
    private static final float ENEMY_MARK_RING_DP = 1.1f;

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

    /**
     * 炮口火焰亮多久（秒），从这一发打出去算起，期间线性淡出。
     *
     * <p>射速间隔是 1.0 / 0.9 / 0.75 秒（{@code BuildingStats}），取 0.12 秒就是
     * "每 8 发里有 1 发的时间在亮"——六座塔一起打的时候屏幕上总有火，
     * 但不会糊成一片。<b>比一个间隔短是硬要求</b>：长了这座塔会一直在亮，
     * 火焰就从"这一刻在开火"变成了塔身上的第二张贴图。
     */
    private static final float FLASH_SECONDS = 0.12f;

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
        /**
         * 这几件买得起吗。买不起时虚影画成红色（{@link #badColor}），点了也不放。
         *
         * @param count 这一次要买几座，至少 1。**一次建一排时问的是总价**，
         *              不是单价——资源不通兑，"五座墙的总价"和"一座墙的价"
         *              在余额够不够上差得远。拖动中的虚影就是拿它逐座试的：
         *              加到哪一座买不起了，那一段就画红。
         */
        boolean canAfford(BuildingType type, int count);

        /**
         * 成交，真扣钱。
         *
         * <p>**只在 {@link #canAfford} 为真、而且引擎那边真的放下去了之后调**，
         * 所以放不下时不会白花钱。
         *
         * @param count 这一次真放下去了几座。建一排时一次扣总价，
         *              不是每座扣一次——中间某一座被引擎挡下来时，
         *              扣的数目要对得上真放下去的那几座。
         */
        void spend(BuildingType type, int count);

        /**
         * 这一下没成立。
         *
         * <p>{@code affordable} 分得清是两种不成里的哪一种：假 = 钱不够
         * （该提示去赚资源），真 = 那块地放不下（该提示换个地方）。
         * 两种混成一句"操作失败"的话，玩家会以为是按钮坏了。
         */
        void onBlocked(BuildingType type, boolean affordable);

        /** 只买一座。单座那条老路（点一下就放）走这个，不用到处写 {@code , 1}。 */
        default boolean canAfford(BuildingType type) {
            return canAfford(type, 1);
        }

        /** 同上，成交一座。 */
        default void spend(BuildingType type) {
            spend(type, 1);
        }
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
         * 点到了东西，或者选中的范围变了。
         *
         * @param building 选中的<b>第一座</b>——面板拿它当锚点：讲哪一类、
         *                  几级、射界多大。点的是空地时是 {@code null}
         *                  （传 null 而不是"不回调"，是为了让外面能借这一下
         *                  <b>收起</b>详情面板——不然点了空地面板还挂着）
         * @param selection 一起选中的那一整排（{@code Battlefield.lineOf} 的结果）。
         *                  只选了一座时就是 {@code [building]}，没选任何东西时是空表。
         *                  外面靠它决定"整排"那个按钮亮不亮、一起升级的总价是多少。
         *                  <b>锚点一定在表里、而且是第一座</b>，所以外面不用再排一遍。
         */
        void onInspected(@Nullable Building building, List<Building> selection);

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

        /**
         * 打起来了，这一下不给改建筑。
         *
         * <p><b>为什么单开一个入口，不并进上面那两个。</b>那两条说的都是"这次操作
         * 本身没成立"（钱不够 / 地不平），玩家换件东西、换个位置就能成；
         * 这一条是<b>现在整个不让动</b>——把塔拖到哪儿都一样，唯一能做的就是等这一波
         * 打完。混成同一句话的话，玩家会一直换位置试，而问题根本不在位置上。
         *
         * <p><b>默认空实现</b>：这条规矩是界面的事（见
         * {@link Battlefield#canReworkBuildings}），没接的调用方自然也就不需要提示。
         */
        default void onReworkBlocked() {
        }
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
     * 和 {@link #inspected} 一起被选中的那一整排；空表 = 没选谁。
     *
     * <p>{@link #inspected} 是这一排的<b>第一座</b>（面板拿它当锚点），
     * 这个表是"这一下要作用在谁身上"。只选一座时就是 {@code [inspected]}。
     *
     * <p><b>为什么锚点和整排要分成两个字段。</b>分开之后"选一座"和"选一排"
     * 走的是同一条路（{@code [b]} 和 {@code [b, c, d]}），升级和挪动都只读
     * {@code selection}，不用在每处再判一次"这回选的是几个"。
     */
    private List<Building> selection = Collections.emptyList();

    /**
     * 正在被挪动的那些建筑；空表 = 没在挪。
     *
     * <p>是一整排而不是一座：{"选中整排 → 一起拖动"} 走的就是这里。
     * 单座挪动是这个表只有一个元素时的特例。
     *
     * <p><b>和 {@link #selected} 互斥</b>：手上拎着一件要放新的、
     * 同时又在挪场上的一座，两个模式叠在一起，点一下空地该干什么是说不清的。
     * 所以 {@link #setSelected} 和 {@link #startMoving} 各自会把对方清掉。
     *
     * <p>和 {@link #inspected} 不同：那个是"看"，这个是"正在改它的位置"。
     * 进挪动模式时会先把 inspected 清掉（详情面板也关了），
     * 不然地上会同时亮着"选中高亮"和"挪动虚影"。
     */
    private List<Building> movingGroup = Collections.emptyList();

    private final ScaleGestureDetector scaleDetector;
    private final int touchSlop;

    /**
     * 这一下拖动的含义，<b>按下那一刻定死</b>，之后不再改。
     *
     * <p>三个模式对应三种"拖"：
     * <ul>
     *   <li>{@link #PAN} 拖地图——空着手在空地上拖，或者从别人的建筑上拖（那多半
     *       只是想挪一下视野，不该把人家挪走）；</li>
     *   <li>{@link #BUILD} 拖出一排要建的——手上拎着一件、从空地上按下去；</li>
     *   <li>{@link #RELOCATE} 拖着一排要走——正按在<b>已经选中的</b>那一排上。</li>
     * </ul>
     *
     * <p><b>为什么按下时就定死、而不是拖过一格再判。</b>判晚了的话，
     * 手指先动一格才决定"这是挪动"，那一格就已经被当成平移吃掉了——
     * 建筑会先跳一下再跟着手走。
     */
    private enum Drag { PAN, BUILD, RELOCATE }

    private Drag drag = Drag.PAN;

    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private boolean pressed;
    private boolean dragging;

    /**
     * 这一下是双指在挪地图。
     *
     * <p><b>为什么要有这条路。</b>手上拎着一件东西时，单指拖动被"建一排"占掉了
     * （见 {@link Drag}），于是没有别的手势能挪地图——想一边拿着货一边把画面
     * 挪到要放的地方去就做不到。双指拖动补上这一个缺口，而且它对所有模式
     * 都成立：挪一排挪到一半也能双指把画面挪开看看。
     *
     * <p>一旦立起来就<b>一直管到最后一根手指抬走</b>：中途松开一根之后
     * 剩下那根手指该算"继续拖地图"还是"重新开始建一排"是说不清的，
     * 索性把这一下整个当拖动地图。
     */
    private boolean multiTouch;
    private float focusX;
    private float focusY;

    /**
     * 按下那一刻第一座建筑的左上角，<b>整条拖动过程中不再变</b>。
     *
     * <p>拖出来的一排是从这儿起算的，不是从手指当前那一格。手指每动一下都重新
     * 吸附的话，"往左拖"会让第一座跟着手指一路左移，永远只有一座——
     * 而玩家看到的明明是手指划过了一整条。
     */
    private Cell pressTopLeft;

    /** 按下那一刻的格子，判"往哪个方向拖、拖了几座"用。 */
    private int pressCol;
    private int pressRow;

    /**
     * 手上拎着的那一批现在偏离原地几格——<b>拖动中它同时是"画在哪儿"和"落到哪儿"</b>。
     *
     * <p>这个位移是<b>从按下那一格算起的手指行程</b>（{@code 当前格 - pressCol}），
     * 不是"把占地吸附到手指底下那一格"。差别在按下去的一瞬间：
     * 一座 2×2 的塔，手指压在它<b>右半边</b>的话，吸附式的算法当场算出向右一格，
     * 于是手指还没动，塔就先跳一格；而且抓左边和抓右边松手落点还不一样。
     * 按行程算的话按下的那一帧位移必然是 0——塔纹丝不动地待在原地等着，
     * 手指挪几格它就走几格，抓着哪儿都一样。这就是"建筑跟着手指走"该有的样子。
     */
    private int carryDCol;
    private int carryDRow;

    /**
     * 手指下面那几块"预放位置"的左上角，<b>头一块在最前面</b>；空表 = 没按着。
     *
     * <p>是一个表而不是"头一块 + 步长"，因为挪动那一支的几块<b>不一定等距</b>：
     * 一排墙之间是挨着的，但{@code Battlefield.lineOf} 返回的是"相接的那一串"，
     * 结构上不保证等距（以后要是允许一排里跳着选就不成立了）。建一排那一支
     * 确实是等距的，填表的时候顺手算出来就是了，不必为它多留一个字段。
     *
     * <p>复用同一个表、每帧 clear 再填，不在拖动过程中反复 new。
     */
    private final List<Cell> ghostCells = new ArrayList<>();

    /**
     * {@link #ghostCells} 里<b>前几块</b>是成立的。
     *
     * <p>建一排时手指可能拖过一段放不下的地（通道、山区、别人身上）或者钱不够了，
     * 那一段就画红。所以"成不成立"不是一个是非，是一个前缀长度：
     * 前 {@code ghostOkRun} 块绿、剩下的红。
     */
    private int ghostOkRun;

    /** 头一块成不成立。等同于 {@code ghostOkRun > 0}。 */
    private boolean ghostOk;

    /** 放不下的时候闪一下的位置和时刻。 */
    private Cell rejectedTopLeft;
    private long rejectedAtMs;

    /**
     * 闪的那一下是<b>哪一类建筑</b>放不下。
     *
     * <p>不能像从前那样等画的时候再问 {@link #ghostType()}：那个来路是
     * "此刻手上拿着什么"，而红闪要亮 0.7 秒（{@link #REJECT_FLASH_MS}）——
     * 这半秒里玩家完全可能已经撒手了，那时手上空空，一问就是 {@code null}，
     * 红闪跟着不画。失败最需要被看见的恰恰是松手那一下。
     * 所以判失败的那一刻就把类型记下来，画的时候不再问别人。
     */
    private BuildingType rejectedType;

    private long lastFrameMs;

    /**
     * 这一帧的时刻，给<b>纯装饰</b>的动画用（现在只有啃建筑的挥击，见
     * {@link #ENEMY_SWING_PERIOD_MS}）。{@link #tick} 每帧写一次。
     *
     * <p>和 {@link #lastFrameMs} 分开：那个是"上一帧是几点"，用来算 dt，<b>不能被读歪</b>
     * ——它是引擎推进的依据。这个只是"现在几点"，读它的地方要是改了它也顶多让动画错一拍。
     */
    private long frameMs;
    // 上一帧报出去的战况。-1 / null 是"还没报过"，所以第一帧一定会报一次。
    private int lastBuildings = -1;
    private int lastEnemies = -1;
    private int lastWaves = -1;
    private int lastLeaks = -1;
    private int lastCoreHp = -1;
    private Battlefield.Outcome lastOutcome;

    /** 山区岩石贴图。和整页背景共用一张，见 {@link RockTexture}。 */
    private final RockTexture rock;

    /** 地面草地贴图。可放置区和敌人通道铺的是同一张，见 {@link GrassTexture}。 */
    private final GrassTexture grass;

    // ---- 地形交界上的过渡带（见 drawSoftEdges）----
    //
    // 三条毯子，**全都铺在"不能放东西"的那一侧**：
    //   可建区（绿）往外铺到石头上、通道（土黄）往外铺到石头上、
    //   可建区（绿）往左铺进通道里。
    //
    // **可放置区里一像素都不能被别的地形染到。** 那是玩家的地，
    // "看着不像能放"和"不能放"在他眼里是一回事——上一版是石头往里啃，
    // 啃掉的那半格灰绿落在可放置区里，等于每块地的右缘都含糊着半格。

    /** 过渡带有多宽（格）。毯子从地形边界往外铺这么深，alpha 在这段里降到 0。 */
    private static final float EDGE_FADE_CELLS = 0.35f;

    /**
     * 边界线的起伏幅度（格）。<b>这是"不像拿尺子切的"那一半</b>，别设成 0。
     * 它和 {@link #EDGE_FADE_CELLS} 是一个量级是有意的：毯子铺出去的深浅一变，
     * 那圈渐隐的**厚度也跟着变**——厚度均匀的渐隐看着就是一圈雾。
     */
    private static final float EDGE_WOBBLE_CELLS = 0.20f;

    /**
     * 过渡带分几层叠出来。层数越多越平滑，代价是每帧多几次
     * {@code drawPath}——5 层是"看不出台阶"和"不心疼"之间的折中。
     */
    private static final int EDGE_STEPS = 5;

    /**
     * 每层毯子的 alpha。<b>不是等分的</b>：外面那几层要弱一些，
     * 否则过渡带的最后一档（只剩最外面一层时）会从 0.45 直接掉到 0，
     * 在石头上留下一圈能看出来的边。
     * 叠起来之后各档约为 0.88 / 0.79 / 0.62 / 0.41 / 0.18 / 0，是条顺的曲线。
     *
     * <p><b>贴边那一档别调到 255。</b>铺出去的第一圈如果和可建区的草一模一样，
     * 就等于把"能建的范围"往石头里推了十几像素——那是误导，比硬边还糟。
     * 留着这一成多的石头透着，那圈才读得出是"草长到石头上"。
     */
    private static final int[] EDGE_ALPHAS = {115, 115, 89, 71, 46};

    /** 边界线每格采几个点。1 个太方，2 个够顺。 */
    private static final float EDGE_SAMPLES_PER_CELL = 2f;

    /** 每条边一个种子，各边的起伏才不会互相关联。 */
    private static final int SEED_TOP = 17;
    private static final int SEED_RIGHT = 91;
    private static final int SEED_BOTTOM = 233;
    private static final int SEED_LEFT = 407;
    private static final int SEED_LANE = 613;

    /** 绿毯和绿晕共用的一支笔（{@link GrassTexture#buildable()} 的五档副本）。 */
    private final Paint[] grassPaints = new Paint[EDGE_STEPS];

    /** 可建区往外铺到石头上的绿毯。见 {@link #buildBleedFrame}。 */
    private final Path[] edgePaths = new Path[EDGE_STEPS];

    /** 通道里那条绿晕：可建区往左铺进通道。见 {@link #buildFadeFrame}。 */
    private final Path[] fadePaths = new Path[EDGE_STEPS];

    /** 土黄那支笔（{@link GrassTexture#lane()} 的五档副本），只有通道那条毯子用它。 */
    private final Paint[] lanePaints = new Paint[EDGE_STEPS];

    /** 通道往外铺到石头上的土黄毯。见 {@link #buildBleedFrame}。 */
    private final Path[] lanePaths = new Path[EDGE_STEPS];

    /** 路径是按这个格子尺寸和列数建的，两者没变就不用重建。 */
    private float edgeCellPx = -1f;
    private int edgeCols = -1;

    // 画笔。构造时一次性建好，onDraw 里不 new 对象。
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 建筑贴图。没有贴图的建筑退回 {@link #drawBlock} 的色块（现在四种都有图，走不到）。 */
    private final BuildingSprites sprites;

    /** 敌人贴图。三种动作各一张，三种兵共用——见 {@link EnemySprites}。 */
    private final EnemySprites enemySprites;

    private final RectF rect = new RectF();

    /** 画射界外弧用的外接矩形。单独一个，不和 {@link #rect} 抢。 */
    private final RectF arcRect = new RectF();

    /**
     * 画射界<b>内弧</b>用的外接矩形——就是扇环中间那块空白。
     *
     * <p>单独一个而不是把 {@link #arcRect} 用两遍：{@link Path#arcTo} 要的是两个
     * 同时存在的矩形（外弧走完接着走内弧），复用会把刚设好的外圈尺寸覆盖掉。
     */
    private final RectF innerRect = new RectF();

    /**
     * 扇环那一条路径（外弧 + 两条侧边 + 内弧）。
     *
     * <p>复用同一个对象、每帧 {@code reset()}：射界每帧都要重画（缩放、拖动都在改
     * 圆心和格子大小），每帧新建一个 {@link Path} 是白给 GC 找活干。
     */
    private final Path arcPath = new Path();

    private final float density;

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

    /** 敌人脚下那块深色接地影的底。上面再描一圈种类色，见 {@link #ENEMY_MARK_WIDTH}。 */
    private final int enemyShadow;
    private final int hpTrack;
    private final int hpGood;
    private final int hpWarn;
    private final int hpLow;
    /** 子弹的颜色。用的还是 {@code game_beam} 那个"塔的火力"色，只是不画连线了。 */
    private final int shotColor;

    // ---- 战场面板：外面接进来的一块矩形 ----
    //
    // 这个 View 是**铺满全屏**的。为什么不只占中间那一条：地形得能滑到
    // HUD 和按钮底下——放大到 2 倍时那片地有 3830px 高，比整块屏幕（2844px）
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

        gridLine = getContext().getColor(R.color.game_grid_line);
        rock = new RockTexture(context);
        grass = new GrassTexture(context);
        for (int i = 0; i < EDGE_STEPS; i++) {
            // 复制而不是改 grass.buildable() / lane()：那两支笔还要拿去铺整片地，
            // 动它们的 alpha 会把可建区和通道一起弄成半透明。
            // 复制出来的笔和原笔共用同一个 shader，所以 setCellPx 一改两边都跟上。
            grassPaints[i] = new Paint(grass.buildable());
            grassPaints[i].setAlpha(EDGE_ALPHAS[i]);
            lanePaints[i] = new Paint(grass.lane());
            lanePaints[i].setAlpha(EDGE_ALPHAS[i]);
            edgePaths[i] = new Path();
            fadePaths[i] = new Path();
            lanePaths[i] = new Path();
        }
        sprites = new BuildingSprites(context);
        enemySprites = new EnemySprites(context);
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
        enemyShadow = getContext().getColor(R.color.game_enemy_shadow);
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

    /** 这一件现在买得起吗。没接账、或者是在挪核心（不要钱），一律算买得起。 */
    private boolean affordable(BuildingType type) {
        return affordable(type, 1);
    }

    /** 一次买 {@code count} 座买得起吗。建一排的虚影就是拿它逐座试到哪一座为止。 */
    private boolean affordable(BuildingType type, int count) {
        return buildGate == null || buildGate.canAfford(type, count);
    }

    /**
     * 打起来了，建筑这一摊先锁上——放新的、挪、升级都不给。
     *
     * <p><b>规矩不在这一层，在这一个方法里。</b>它把
     * {@link Battlefield#canReworkBuildings} 那句问话包一下，主要是为了
     * {@code battlefield == null} 那一支：场景还没建好时手上有空指针，
     * 而"没有战场"当然不是"打起来了"——不包的话，开局那一瞬间会被判成锁定。
     * 视图这边每一处要问"现在能不能动建筑"都走它，别各写各的。
     */
    private boolean reworkLocked() {
        return battlefield != null && !battlefield.canReworkBuildings();
    }

    /**
     * 挡下这一下，并且<b>说一句</b>。
     *
     * <p>悄悄不响应是最坏的一种：玩家会以为是手指没点到、或者游戏卡了，
     * 于是反复试。和操作条上那三个框"能点、点了说清为什么"是同一套手感。
     */
    private void refuseRework() {
        if (inspector != null) {
            inspector.onReworkBlocked();
        }
    }

    /** 手上这件要建一排的话，相邻两座跨几列。 */
    private int buildStepCol() {
        return selected == null ? 0 : selected.cols;
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
        // 见 #movingGroup 的注释。
        if (type != null) {
            this.movingGroup = Collections.emptyList();
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
        startMovingGroup(building == null
                ? Collections.emptyList()
                : Collections.singletonList(building));
    }

    /**
     * 把<b>当前选中的那一整排</b>一起拖走。详情面板上的 Move 走这条。
     *
     * <p>"选一排 → 一起拖动"的另一半就在这里：选好了范围（{@link #selectLine}），
     * 再进挪动模式，之后不管点还是拖，动的都是这一整排
     * （{@code Battlefield.moveAll}）。
     *
     * <p>没选中任何东西时什么都不做——不留一个"在挪空气"的状态。
     */
    public void startMovingSelection() {
        if (selection.isEmpty()) {
            return;
        }
        if (reworkLocked()) {
            // 入口是详情面板上那个 Move（界面上已经没有了，见 MainActivity 里
            // 那段注释），留着这一问是防止将来谁把它接回来时漏掉这道锁——
            // 挪动模式一旦进去，之后每一拖都直通引擎的 moveAll。
            refuseRework();
            return;
        }
        startMovingGroup(selection);
    }

    /**
     * 进入整排挪动模式。
     *
     * <p><b>为什么要复制一份。</b>{@link #selection} 会在进模式时被清掉
     * （走 {@link #inspect} 收面板），而正在挪的那一排得留着。不复制的话
     * 挪动模式一进去就是空表。
     */
    private void startMovingGroup(List<Building> group) {
        this.movingGroup = group.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(group);
        if (!movingGroup.isEmpty()) {
            // 走 inspect(null) 而不是直接写字段：这样外面会收到"没在看谁了"，
            // 悬浮操作条跟着收起来。直接写字段的话条子会挂在那儿，而地上已经
            // 换成挪动虚影了——两套高亮指着一座建筑，玩家说不清点下去会怎样。
            inspect(null);
            setSelected(null);
        }
        invalidate();
    }

    /** 正在挪的那一座（整排的<b>第一座</b>），没在挪时是 {@code null}。 */
    public Building moving() {
        return movingGroup.isEmpty() ? null : movingGroup.get(0);
    }

    /** 正在挪的那一整排，没在挪时是空表。 */
    public List<Building> movingGroup() {
        return Collections.unmodifiableList(movingGroup);
    }

    /** 现在选中的那一整排，没选谁时是空表。外面拿它算整排升级的总价。 */
    public List<Building> selection() {
        return Collections.unmodifiableList(selection);
    }

    /**
     * 正在查看的那一座（锚点）在<b>战场面板坐标系</b>里的外接矩形；
     * 没在看谁、或者相机还没建好时是 {@code null}。
     *
     * <p>给悬浮操作条定位用。它和面板（{@code @id/play_area}）是同一个父容器，
     * 所以这里给的坐标它能直接用——也就是画布上那一套
     * （{@code viewport.toScreenX/Y}），不用再减去 {@code playLeft/playTop}。
     *
     * <p><b>只算锚点那一座，不算整个选区的外接矩形。</b>选了一整列塔时那个框
     * 又高又窄，条子会被顶到屏幕边上去；而玩家的手指刚从其中一座上抬起来，
     * 条子落在他刚才点的地方才顺。
     */
    @Nullable
    public RectF inspectedBounds() {
        if (inspected == null || board == null || viewport == null) {
            return null;
        }
        // 手指上拎着它的时候，条子跟着它走：**条子在哪儿 = 建筑在哪儿**，
        // 这是这个条子唯一说得通的位置。不跟的话，拖动时那座塔已经悬在
        // 半空了，而"i / 升级 / 整排"还挂在一格空地上——玩家会以为操作的是
        // 那块空地。抬手之后下面那几行又会重新算一遍，条子自然落回建筑底下。
        boolean lifted = carrying() && relocatingGroup().contains(inspected);
        int dCol = lifted ? carryDCol : 0;
        int dRow = lifted ? carryDRow : 0;
        float left = board.leftX(inspected.col() + dCol);
        float top = board.topY(inspected.row() + dRow);
        float right = board.leftX(inspected.col() + dCol + inspected.type.cols);
        float bottom = board.topY(inspected.row() + dRow + inspected.type.rows);
        // 竖着也跟着抬一格（{@link #CARRY_LIFT_CELLS}）：只挪横竖不给抬的话，
        // 拖动时贴图会从条子底下穿过去，条子看着像黏在地上的影子。
        float lift = lifted ? CARRY_LIFT_CELLS * board.cellPx() : 0f;
        return new RectF(viewport.toScreenX(left), viewport.toScreenY(top) - lift,
                viewport.toScreenX(right), viewport.toScreenY(bottom) - lift);
    }

    /**
     * 把选中的范围扩成这一座<b>所在的那一整排</b>。
     *
     * <p>入口是悬浮操作条上那个"整排"按钮（{@code @id/bar_line}），
     * 不是再点一次场上的建筑：条子出现的同时手指已经抬起来了，
     * "再点一下同一座"这一下没有被谁接住。
     *
     * <p>没选中任何东西时什么都不做。选出来的那一排一定<b>以当前的锚点打头</b>
     * ——{@code Battlefield.lineOf} 是按坐标顺序给的，锚点可能落在中间
     * （点一列墙的第四座时就是），而外面拿 {@code get(0)} 当"这一排是哪一类、
     * 几级"的出处。
     */
    public void selectLine() {
        if (inspected == null || battlefield == null) {
            return;
        }
        setSelection(battlefield.lineOf(inspected), inspected);
    }

    /** 缩回只选一座。 */
    public void selectSingle() {
        if (inspected != null) {
            setSelection(Collections.singletonList(inspected), inspected);
        }
    }

    /**
     * 这一排（或这一座）的数值变了——升级回血、被打掉血。
     *
     * <p>高亮和射界圈要按新的等级重画，所以得让这一帧失效。**不能走
     * {@link #setInspected}**：那个会把整排缩成一座，一起升级完一排墙之后
     * 地上只剩一道金边，玩家会以为刚才只升了一座。
     */
    public void refreshSelection() {
        invalidate();
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
     * 高亮和射界扇形都得按新的等级重画。
     */
    public void setInspected(Building building) {
        setSelection(building == null
                        ? Collections.emptyList()
                        : Collections.singletonList(building),
                building);
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
        rock.setCellPx(fitted.cellPx());
        grass.setCellPx(fitted.cellPx());
        rebuildEdges();
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
        rejectedType = null;
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
                beginDrag(downX, downY);
                updateGhost(downX, downY);
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指下来了：从这一帧起改拖地图。
                // 手上那块虚影要收掉——它指的是"单指松开后会落在哪儿"，
                // 而这一下已经不会落在任何地方了。
                multiTouch = true;
                pressed = false;
                dragging = false;
                ghostCells.clear();
                readFocus(event, -1);
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // 松开一根：重心要按**剩下的**那几根重算一遍。
                // 不重算的话，下一帧的重心会从"两根手指中间"直接跳到剩下那根上，
                // 那一跳会被当成一次平移，画面猛地窜一下。
                //
                // **这一下不结束拖动**：剩一根手指也还是"拖地图"，
                // 一直到最后一根抬走（见 #multiTouch）。
                readFocus(event, event.getActionIndex());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (multiTouch) {
                    // 双指拖 = 平移，同时也喂给了 scaleDetector（在最上面），
                    // 所以捏合缩放和整体挪动是同时生效的——两根手指一边张开
                    // 一边整体挪，玩家要的就是"放大这一块"。
                    panByFocus(event);
                    return true;
                }
                if (pressed && !dragging
                        && (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop)) {
                    dragging = true;
                    // 拖地图才把虚影收起来。**建一排 / 挪一排的时候不能收**——
                    // 那两个模式的虚影是要跟着手指走的，正是这一拖的结果。
                    if (drag == Drag.PAN) {
                        ghostCells.clear();
                    }
                }
                if (dragging && drag == Drag.PAN) {
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
                if (multiTouch) {
                    multiTouch = false;
                    endDrag();
                    invalidate();
                    return true;
                }
                if (dragging && drag != Drag.PAN) {
                    // 先按抬手的位置再算一遍：最后一帧之后手指可能还动了几个像素，
                    // 那一小段在拖动里就是一整格。用上一帧算出来的块数会少建一座。
                    updateGhost(event.getX(), event.getY());
                    commitDrag(event.getX(), event.getY());
                } else if (!dragging && pressed && !scaleDetector.isInProgress()) {
                    handleTap(event.getX(), event.getY());
                }
                endDrag();
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                multiTouch = false;
                endDrag();
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * 记下多指的<b>重心</b>。
     *
     * <p>平移量按重心算，不按某一根手指：捏合时手指各自在动，
     * 重心才是"这一下想把画面挪去哪儿"。
     *
     * @param skipIndex 这一次要跳过的指针（松开的那一根），没有就传 -1
     */
    private void readFocus(MotionEvent event, int skipIndex) {
        float fx = 0f;
        float fy = 0f;
        int count = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == skipIndex) {
                continue;
            }
            fx += event.getX(i);
            fy += event.getY(i);
            count++;
        }
        if (count == 0) {
            return;   // 一根都不剩了：保持旧值，下一次 MOVE 会重新读
        }
        focusX = fx / count;
        focusY = fy / count;
    }

    private void panByFocus(MotionEvent event) {
        float previousX = focusX;
        float previousY = focusY;
        readFocus(event, -1);
        viewport.panBy(focusX - previousX, focusY - previousY);
        notifyCameraChanged();
        invalidate();
    }

    /**
     * 按下那一刻定这一拖是什么意思。三种，见 {@link Drag}。
     *
     * <p><b>顺序有讲究，五条分支的先后就是优先级，别调换。</b>
     *
     * <ol>
     *   <li><b>打起来了就一律拖地图</b>（{@link #reworkLocked}），下面那四条
     *       一条都不看。它排在最前面是因为要盖过第一条——挪动模式是上一波留下的
     *       状态，正在打的时候顺着它拖下去，手指一抬就走到了引擎的
     *       {@code moveAll}，那正是要挡住的事。</li>
     *   <li><b>正在挪东西</b>排在剩下那几条的最前面：进了挪动模式之后，手指落在哪儿都是
     *       "把它挪到那儿"，按在建筑上也不例外（否则一列塔挪到一半、手指压在
     *       别的塔上，模式就悄悄变成了拖地图）。</li>
     *   <li><b>手上拎着东西</b>排第二，而且<b>盖过</b>底下的建筑命中判定。
     *       以前它排在命中判定后面，结果是：手里拿着塔、手指落点正好压到
     *       已有的建筑上时，这一拖就掉进了"拖地图"——玩家想铺一排墙，
     *       屏幕却开始滑。按在建筑上本来就放不下，虚影会整条画红，
     *       比"屏幕莫名其妙滑走"清楚得多。</li>
     *   <li><b>点到已选中的那一座</b>才是拖它走。少了这一条，想平移视野时
     *       手指正好落在塔上，塔就被拖走了——而挪动是"放得下就生效"的，
     *       玩家会发现阵型莫名其妙变了样。</li>
     *   <li>其余一律是拖地图。</li>
     * </ol>
     */
    private void beginDrag(float screenX, float screenY) {
        pressTopLeft = null;
        if (board == null || viewport == null) {
            drag = Drag.PAN;
            return;
        }
        pressCol = board.colAt(viewport.toLayoutX(screenX));
        pressRow = board.rowAt(viewport.toLayoutY(screenY));

        Building hit = battlefield == null
                ? null
                : battlefield.buildingAt(new Cell(pressCol, pressRow));

        if (reworkLocked()) {
            // 打起来了：这一拖<b>只能是拖地图</b>，两个改建筑的模式一个都不进。
            //
            // **不是把整个手势作废**——拖视野、点建筑看数值都得照常，
            // 不然打起来之后这一屏就剩一个"开始波次"能按了。所以这里只是
            // 不进那两个模式，落到最后那个 else 上去。
            //
            // 排在最前面还有个原因：挪动模式（movingGroup）是上一波留下的状态，
            // 这时候拖着它是走到了引擎那边的 moveAll——正是要挡住的那件事。
            drag = Drag.PAN;
        } else if (!movingGroup.isEmpty()) {
            drag = Drag.RELOCATE;
        } else if (selected != null) {
            drag = Drag.BUILD;
        } else if (hit != null && selection.contains(hit)) {
            drag = Drag.RELOCATE;
        } else {
            drag = Drag.PAN;
        }

        if (drag == Drag.BUILD && !movingCore()) {
            // 整排从按下这一座起算，之后手指怎么动都不改这个头
            pressTopLeft = board.topLeftFor(pressCol, pressRow,
                    selected.cols, selected.rows);
        }
    }

    /** 这一拖结束，把所有"按着才有"的状态收干净。 */
    private void endDrag() {
        pressed = false;
        dragging = false;
        drag = Drag.PAN;
        boolean wasCarried = carryDCol != 0 || carryDRow != 0;
        carryDCol = 0;
        carryDRow = 0;
        pressTopLeft = null;
        ghostCells.clear();
        ghostOkRun = 0;
        ghostOk = false;
        // 拎过东西的话再叫一声：操作条是按"建筑 + 拎着的位移"定位的，
        // 位移刚被清成 0，不重算它就停在上一次跨格时那个位置上了。
        // （挪成功的话下面还会走一遍"重新选中"，那次会再叫一声——重算是幂等的，
        // 多叫一次只是白算一遍，比漏叫一次好。）
        if (wasCarried) {
            notifyCameraChanged();
        }
    }

    /**
     * 手指抬起来了，把这一拖落下去。
     *
     * <p><b>点一下是"建一座 / 挪过去"，拖一下是"建一排 / 挪一排"</b>——
     * 两者的区别只在于这一拖有没有超过触摸阈值（{@link #dragging}），
     * 没超过就走 {@link #handleTap}，和以前完全一样。
     *
     * <p><b>挪动那一支不再复用 {@link #handleMoveTap}（2026-09-27 改）。</b>
     * 点一下是"放到这一格"，那一支按手指的格子吸附；拖一下是"拎着走了几格"，
     * 落点必须是虚影停住的那一格（{@link #carryDCol}）。
     * 两者在手指抬起的那一帧会算出<b>不同的格子</b>——抓着一座 2×2 塔的右半边
     * 往右拖三格，吸附式说"再往右一格"，而虚影一路显示的是"右三格"。
     * 混用的话，玩家会眼睁睁看着塔在松手的一瞬间自己跳一格。
     */
    private void commitDrag(float screenX, float screenY) {
        if (board == null || viewport == null || battlefield == null) {
            return;
        }
        if (drag == Drag.RELOCATE) {
            commitMoveDrag();
            return;
        }
        if (drag == Drag.BUILD) {
            // 整张表都递给它：断开之后那几块（画红的那些）由 buildLine
            // 自己在第一次失败处停下来，不在这儿先截一遍——
            // 两处各截一次，迟早会截出不一样的长度。
            buildLine(ghostCells.size());
        }
    }

    /**
     * 拖着的那一批落下去。<b>落点就是虚影停住的那一格</b>（{@link #carryDCol}）。
     *
     * <p>和 {@link #handleMoveTap}（点一下）的分工是清楚的：那个回答"放到这一格"，
     * 这个回答"拎着走了几格"。所以这里<b>不看手指最后压在哪一格</b>——
     * 手指压着的那一格跟建筑该去哪一格是两回事，抓着一座塔的上半截往右拖，
     * 手指最后停在建筑原来的位置上，塔却该往右三格。
     *
     * <p><b>也因此没有"点到别人身上就作废"那一支。</b>那是 {@code handleMoveTap}
     * 的规矩（手指点着一座建筑 = 想看它），而拖动的手指点在哪儿不说明任何事。
     * 拖着撞上别人由 {@code Battlefield.canMoveAll} 判——它会红闪一下、
     * 说一句"放不下"，而<b>整排放不下就整排都不动</b>，不会拖过去三座留下两座。
     */
    private void commitMoveDrag() {
        List<Building> group = relocatingGroup();
        Building anchor = group.isEmpty() ? null : group.get(0);
        if (anchor == null) {
            return;
        }
        if (reworkLocked()) {
            // 按住拖到一半、敌人进场了。**松手什么都不该发生**——建筑留在原地，
            // 也不红闪（玩家没做错什么，是这一波开打了），更不落下去。
            //
            // movingGroup 那一支顺手退掉，和 handleMoveTap 同一个理由：
            // 留着一个已经不能用的挪动模式，打完那一波之后它会突然生效。
            if (!movingGroup.isEmpty()) {
                startMoving(null);
            }
            refuseRework();
            return;
        }
        if (carryDCol == 0 && carryDRow == 0) {
            // 拎起来又放回原地：和"点一下它自己"是同一件事——什么也不改，
            // 接着看它。**不能当成一次失败的挪动**去红闪，玩家没做错什么。
            startMoving(null);
            inspect(anchor);
            return;
        }
        if (!battlefield.moveAll(group, carryDCol, carryDRow)) {
            // 别动 startMoving：留在挪动模式里，手指点歪一格是常事，
            // 一次没放好就得回详情面板重点一遍 Move 的话这个功能没法用
            // （和 #handleMoveTap 里那条一样的理由）。红闪的位置是虚影头一块
            // ——那才是玩家眼里"放不下的那一块"，不是手指底下那一格。
            rejectedTopLeft = ghostCells.isEmpty()
                    ? new Cell(anchor.col(), anchor.row()) : ghostCells.get(0);
            rejectedType = anchor.type;
            rejectedAtMs = System.currentTimeMillis();
            if (inspector != null) {
                inspector.onMoveBlocked(anchor);
            }
            return;
        }
        // 挪完把选中还回去：玩家的下一个动作十有八九是接着看它
        // （再挪一次、或者升级）。理由同 handleMoveTap。
        startMoving(null);
        forceSelection(group, anchor);
    }

    /**
     * 现在这一下点击是"挪核心"还是"放新的"。
     *
     * <p>场上有核心时，再选核心工具点一下就是<b>挪</b>它——核心只有一座。
     * 判定和放新的共用一套占位规则，区别只是要不要把旧的那座让开。
     *
     * <p>注意这条老路（在商店里选 Core）和新的 {@link #movingGroup} 是两套并存的挪法：
     * 这里是"选中核心这一类，点哪儿挪哪儿"，那里是"指定场上这一座，点哪儿挪哪儿"。
     * 后者对所有建筑都成立，前者只对核心成立。留着它是因为商店里那行文案
     * （"move the one you have"）已经把这条路讲给玩家了，删掉会让那句话落空。
     */
    private boolean movingCore() {
        return selected == BuildingType.CORE && battlefield != null
                && battlefield.core() != null;
    }

    /**
     * 手指下面会放出什么：<b>从按下那一座起、朝手指那边铺出去的一排</b>。
     *
     * <p>点一下（手指没离开那一格）时排长就是 1，和以前一模一样。
     * 拖出去才有第二座、第三座。
     *
     * <p><b>一排的长短按"跨了几个占地"算，不是按"跨了几格"。</b>2×2 的塔
     * 拖过一格还是 2×2 那一座（多出来的一格被它自己盖住了），拖过两格才是两座。
     * 算法在 {@link BoardGeometry#runLength}，不在这儿。
     *
     * <p><b>横还是竖由拖的方向定。</b>两个方向都跨了格子时取跨度大的那个——
     * 斜着拖必然同时满足两轴，玩家想铺的显然是更明显的那条线。
     */
    private void updateGhost(float screenX, float screenY) {
        ghostCells.clear();
        ghostOkRun = 0;
        ghostOk = false;

        // 打起来了就一块虚影都不画。
        //
        // **这一问不是多余的**，尽管 beginDrag 那边已经拦过一道：那一问只在
        // ACTION_DOWN 那一下问。玩家按住一座塔不放、另一根手指去点"开始波次"，
        // 敌人就在这一拖进行到一半时进场了（引擎那边 tick 照跑），
        // 而 drag 还是按下时定下的 RELOCATE。少了这一问，画面上会继续跟着手指
        // 拖出一块落点虚影，看着像"还能放"——松手却什么都不发生。
        if (reworkLocked()) {
            return;
        }

        // 挪动的虚影认的是**这一拖是干什么的**（drag），不是"有没有在挪动模式里"：
        // 现在"拖一座已选中的建筑"根本不进那个模式，手上直接就是 RELOCATE。
        if (drag == Drag.RELOCATE) {
            updateMoveGhost(screenX, screenY);
            return;
        }
        if (selected == null || battlefield == null) {
            return;
        }
        int col = board.colAt(viewport.toLayoutX(screenX));
        int row = board.rowAt(viewport.toLayoutY(screenY));

        // 挪核心是"就那么一座"，铺不成排——铺出来的第二座也没地方放
        if (movingCore()) {
            Cell only = board.topLeftFor(col, row, selected.cols, selected.rows);
            ghostCells.add(only);
            ghostOk = battlefield.canPlace(selected, only.col, only.row,
                    battlefield.core());
            ghostOkRun = ghostOk ? 1 : 0;
            return;
        }

        Cell head = pressTopLeft != null
                ? pressTopLeft
                : board.topLeftFor(col, row, selected.cols, selected.rows);
        int dCol = col - pressCol;
        int dRow = row - pressRow;
        boolean vertical = Math.abs(dRow) > Math.abs(dCol);
        int span = vertical ? selected.rows : selected.cols;
        int run = BoardGeometry.runLength(vertical ? pressRow : pressCol,
                vertical ? row : col, span);

        // 往"小"的那头拖时，这一排是**从手指那头起算**的：头一座要退回去，
        // 不然铺出来的会顶在按下那一座的下面，手指划过的地反而空着。
        int back = (vertical ? dRow < 0 : dCol < 0) ? (run - 1) * span : 0;
        int startCol = vertical ? head.col : head.col - back;
        int startRow = vertical ? head.row - back : head.row;

        // 前几座成立：一段一段往下试，第一段不成立就停。
        // 判定只认**这一座**的位置，所以前面几座建起来不会影响后面几座
        // （占地互不重叠），画出来的前缀和真建出来的前缀必然一致。
        for (int i = 0; i < run; i++) {
            Cell cell = new Cell(startCol + (vertical ? 0 : i * span),
                    startRow + (vertical ? i * span : 0));
            ghostCells.add(cell);
            // 两种"放不下"画的是同一块红框：这里没空地，或者买不起。
            // 对玩家来说区别不大（都是"这一下不成立"），但只有真的按下去才提示是哪种。
            if (!battlefield.canPlace(selected, cell.col, cell.row)
                    || !affordable(selected, i + 1)) {
                break;
            }
            ghostOkRun = i + 1;
        }
        // 断开之后剩下的那几块也要画出来（画红），不然玩家看不出"本来能铺多长"，
        // 只看到虚影短了一截，还以为是手指没拖够。
        for (int i = ghostOkRun + 1; i < run; i++) {
            ghostCells.add(new Cell(startCol + (vertical ? 0 : i * span),
                    startRow + (vertical ? i * span : 0)));
        }
        ghostOk = ghostOkRun > 0;
    }

    /**
     * 挪动时的虚影：位置用被挪那一座的占地大小算，判定要把它自己和<b>整排</b>让开。
     *
     * <p><b>没有"买得起吗"这一问</b>——挪动不花钱（{@code Battlefield.moveAll}）。
     * 判定这里只有"放不下"一种不成立。
     *
     * <p>整排一起挪时判定走的是 {@code Battlefield.canMoveAll}（试算），
     * 不是逐座问 {@code canPlace}：一排墙往右挪一格，新格子有一半正是这一排里
     * 邻座的旧格子，逐座问的话每一座都会被"自己人"挡住，整体平移永远画红。
     */
    private void updateMoveGhost(float screenX, float screenY) {
        List<Building> group = relocatingGroup();
        Building anchor = group.isEmpty() ? null : group.get(0);
        if (anchor == null) {
            return;
        }
        // 位移按**手指走了几格**算，不按"手指现在在哪一格"，理由见 #carryDCol。
        int dCol = board.colAt(viewport.toLayoutX(screenX)) - pressCol;
        int dRow = board.rowAt(viewport.toLayoutY(screenY)) - pressRow;
        boolean moved = dCol != carryDCol || dRow != carryDRow;
        carryDCol = dCol;
        carryDRow = dRow;
        // **整排的每一座都画。**只画头一座的话，一列塔挪动时地上只出现一块虚影，
        // 玩家看不出整排会落到哪儿——而"整排会不会压到那边那座墙"正是他在犹豫的事。
        for (Building building : group) {
            ghostCells.add(new Cell(building.col() + dCol, building.row() + dRow));
        }
        ghostOk = battlefield == null || battlefield.canMoveAll(group, dCol, dRow);
        ghostOkRun = ghostOk ? ghostCells.size() : 0;

        // 挪过了一格就叫一声相机——**相机其实没动**，动的是建筑相对格子的位置，
        // 而"凡是跟着格子走的东西"都得重算一遍，悬浮操作条就是其中之一
        // （它挂在同一个回调上，见 MainActivity 里那个 setCameraListener）。
        // 之所以借这个回调而不是新开一条：两者要重算的东西完全一样
        // （相机 × 格子 → 屏幕），分成两条迟早会有一处漏掉某条路径。
        //
        // **只在跨格的时候叫**，不是每个 MOVE 都叫：手指在屏幕上是一格一格
        // 挪过去的，同一格里那几十个像素的事件重算出来是同一个位置。
        if (moved) {
            notifyCameraChanged();
        }
    }

    /**
     * 这一拖（或这一下）要挪的是哪一批。
     *
     * <p><b>有两个来路，所以收在这一个方法里。</b>一条是 {@link #movingGroup}
     * （旧的"先按 Move 再点目标"那条路，入口 {@link #startMovingSelection}），
     * 另一条是 {@link #selection}——就是现在这条：选中了一座或一排，
     * 手指按在它身上直接拖。
     *
     * <p>选中的那批优先于 {@code movingGroup} 吗？不。进了挪动模式之后
     * 屏幕上已经没有"选中"这回事了（{@code startMovingGroup} 会把它清掉），
     * 两个同时非空是不可能的状态；这里只是按"谁有值就用谁"取。
     */
    private List<Building> relocatingGroup() {
        return movingGroup.isEmpty() ? selection : movingGroup;
    }

    /**
     * <b>建筑是不是已经"拿在手上"了</b>——正拖着那一排走，而不是单纯按着。
     *
     * <p>区别在手指动没动（{@link #dragging}）。按下去还没动的这一小段里，
     * 建筑该稳稳待在原地：一按就浮起来的话，想查看一座建筑（点一下）
     * 会先看到它蹦一下，而点一下和拖一下在手指抬起来之前是分不出来的。
     * 手指一旦真的动了，它就从地里被拎起来——那也正是玩家按下拖动时想要的手感。
     *
     * <p><b>还要求"没在打仗"</b>（{@link #reworkLocked}）。这一问是给
     * "按住拖到一半、敌人进场了"那一瞬准备的：锁上之后手上那批得<b>立刻回到
     * 地上</b>——它是靠这个方法的返回值整体决定画不画在半空的
     * （贴图、血条、射界、炮口火光、还有操作条的位置，全走它）。
     * 不在这儿统一挡，就得在五个地方各判一次，迟早漏一处，
     * 而漏掉的那处会留下一座浮在空中的塔。
     */
    private boolean carrying() {
        return drag == Drag.RELOCATE && dragging && !reworkLocked();
    }

    /**
     * 手上拎着的那一批的<b>头一座</b>；没在拎时是 {@code null}。
     *
     * <p>它是"虚影按哪一类建筑画、按几级算"的来路，也是排序和高亮的锚点。
     */
    private Building carriedAnchor() {
        List<Building> group = relocatingGroup();
        return group.isEmpty() ? null : group.get(0);
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

        if (!movingGroup.isEmpty()) {
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
        List<Building> group = relocatingGroup();
        Building anchor = group.isEmpty() ? null : group.get(0);
        if (anchor == null) {
            return;
        }
        if (reworkLocked()) {
            // 挪动模式是上一波开打之前留下的状态（这条路的入口是个旧按钮，
            // 见 startMovingSelection）。打起来之后点空地不再挪，但**照样退出模式**：
            // 不退的话这个模式会一直挂在那儿，等这一波清完，下一次点空地
            // 会莫名其妙把它挪过去——玩家早忘了自己开过这个模式。
            startMoving(null);
            refuseRework();
            return;
        }
        Building hit = battlefield.buildingAt(new Cell(col, row));

        if (hit != null) {
            // 点到自己 = 取消；点到别人 = 换看别人。两种都是"这次挪动作废"。
            // **落到手指抬起那一下就是"拖着走、落回原地"**：手指按在它身上、
            // 没怎么动就松开了，落点自然还是它自己——这时不该有任何事发生，
            // 走的正是这一支（inspect 回去的还是同一座，等于没动）。
            startMoving(null);
            inspect(hit);
            return;
        }

        Cell topLeft = board.topLeftFor(col, row, anchor.type.cols, anchor.type.rows);
        // 整排一个位移：单座时这就是 move，多座时是原子的一起挪
        // （Battlefield.moveAll 的注释里写了为什么不能逐座挪）。
        if (!battlefield.moveAll(group,
                topLeft.col - anchor.col(), topLeft.row - anchor.row())) {
            rejectedTopLeft = topLeft;
            rejectedType = anchor.type;
            rejectedAtMs = System.currentTimeMillis();
            if (inspector != null) {
                inspector.onMoveBlocked(anchor);
            }
            return;
        }

        // 挪完把选中还回去：玩家的下一个动作十有八九是接着看它
        // （再挪一次、或者升级），而不是回商店。
        // **整排还是整排地选着**：刚摆好的一列，接着多半就是整排升级。
        // 走 force 那一支，理由见它的注释：这一批的位置变了、批次没变。
        startMoving(null);
        forceSelection(group, anchor);
    }

    /** 把查看目标换成这一座（{@code null} = 空地，收起面板）。选中的范围缩回它一座。 */
    private void inspect(Building building) {
        setSelection(building == null
                        ? Collections.emptyList()
                        : Collections.singletonList(building),
                building);
    }

    /**
     * 换掉选中的那一排。**选中的范围只有这一个写入口**，
     * 所以"外面收到的那张表"和"地上画的那些金边"不可能说岔。
     *
     * <p>值没变就不发通知：{@code handleTap} 点空地收面板时每一下都会走到这儿，
     * 本来就没选谁的时候不该惊动外面（会白白重画一次详情面板）。
     */
    private void setSelection(List<Building> group, Building anchor) {
        if (inspected == anchor && selection.equals(group)) {
            return;
        }
        applySelection(group, anchor);
    }

    /**
     * 和 {@link #setSelection} 做同一件事，但<b>不做"没变就不通知"那个短路</b>。
     *
     * <p>给"刚挪完"用：那一批建筑<b>位置变了、批次没变</b>——{@code selection}
     * 里还是原来那几个对象，{@code equals} 判下来一模一样，于是 {@code setSelection}
     * 会直接返回、外面收不到通知。可外面必须收到：悬浮操作条是贴在建筑旁边的，
     * 不重新推一次它就留在旧位置，而那座建筑已经挪到别处去了。
     */
    private void forceSelection(List<Building> group, Building anchor) {
        applySelection(group, anchor);
    }

    private void applySelection(List<Building> group, Building anchor) {
        inspected = anchor;
        // 锚点打头。lineOf 是按坐标给的，点一列墙的第四座时锚点在中间，
        // 而外面拿 get(0) 当"这一排是哪一类、几级"的出处。
        List<Building> ordered = new ArrayList<>(group.size());
        if (anchor != null) {
            ordered.add(anchor);
        }
        for (Building building : group) {
            if (building != anchor) {
                ordered.add(building);
            }
        }
        selection = ordered;
        if (inspector != null) {
            inspector.onInspected(anchor, selection());
        }
        invalidate();
    }

    private void tryPlace(int col, int row) {
        if (reworkLocked()) {
            refuseRework();
            return;
        }
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
            rejectedType = selected;
            rejectedAtMs = System.currentTimeMillis();
            if (!moving && buildGate != null) {
                buildGate.onBlocked(selected, paid);
            }
        }
    }

    /**
     * 一次建一排：把 {@link #ghostCells} 里前 {@code count} 块都建出来。
     *
     * <p><b>能建几座建几座，不回头。</b>手指拖过一段放不下的地（通道、山区、
     * 别人身上）或者钱只够前面几座时，前面那一段照建，从第一座不成立的
     * 地方断开——和虚影画出来的那截绿边一模一样。整排回滚的话，
     * 拖过一条通道就得重新拖一次，而玩家看不出是为什么。
     *
     * <p><b>扣的是一座一座问出来的总价，不是"单价 × 座数"。</b>资源不通兑，
     * 而且真实扣款必须和 {@link BuildGate#canAfford} 问过的那个数对得上，
     * 否则虚影画绿了、钱却扣不动。
     *
     * <p>放完<b>自动收手</b>，和 {@link #tryPlace} 一个道理：不收的话，
     * 之后每点一下空地都在花钱。
     */
    private void buildLine(int count) {
        if (selected == null || battlefield == null || ghostCells.isEmpty()
                || count <= 0) {
            return;
        }
        if (reworkLocked()) {
            // 整排这一支也要挡，而且挡在**最前面**：下面那个循环走的是
            // battlefield.place，绕过了 tryPlace，只有 movingCore 那一支
            // 才会拐进 tryPlace 里那道闸。虚影这时候是空的（updateGhost 没画），
            // 所以实际上到不了这儿——留着是为了将来虚影那条路改动了，
            // 这里不至于默默变成一条能建一排的缝。
            refuseRework();
            return;
        }
        Cell head = ghostCells.get(0);
        if (movingCore()) {
            // 核心是"挪那一座"，没有整排这一说
            tryPlace(head.col, head.row);
            return;
        }

        int placed = 0;
        for (int i = 0; i < count && i < ghostCells.size(); i++) {
            if (!affordable(selected, placed + 1)) {
                break;   // 钱只够前面这几座
            }
            Cell cell = ghostCells.get(i);
            if (battlefield.place(selected, cell.col, cell.row) == null) {
                break;   // 这块地放不下，前面的照建
            }
            placed++;
        }

        if (placed == 0) {
            rejectedTopLeft = head;
            rejectedType = selected;
            rejectedAtMs = System.currentTimeMillis();
            if (buildGate != null) {
                buildGate.onBlocked(selected, affordable(selected));
            }
            return;
        }
        if (buildGate != null) {
            buildGate.spend(selected, placed);
        }
        setSelected(null);
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

        // 注意这里**不画**上方那 1 格余量带。
        // 余地留是留着的（BoardGeometry.MAX_OVERHANG_CELLS，核心楼顶要伸进去），
        // 但**不归这个 View 画**：余量带是"不能放东西的地方"，按全页的规矩
        // 那就是山区，而整页的岩石底（MountainBackgroundView）已经用同一个
        // 相机把石头画在那儿了。这里再铺一块自己的颜色，等于在屏幕上多切一刀：
        // 面板上沿一条线、网格上沿又一条线，中间夹一条横带——一眼就是
        // "界面里嵌了一块游戏"，而不是一整片地。
        drawGround(canvas);
        // 地形交界上的三条过渡带，画在格线**下面**：它们铺的是"这儿是草 / 这儿是土"，
        // 格线是量格子的尺子，两回事——尺子压在毯子上才清楚。
        // （毯子全部落在可放置区**外面**，所以这里也不存在"把可建区染花"的问题，
        //   见那三条路径各自的注释。）
        drawSoftEdges(canvas);
        // 格线只在"手上拿着东西"的时候画，见 drawGrid。
        drawGrid(canvas);
        drawRanges(canvas);
        drawSprites(canvas);
        drawMuzzleFlashes(canvas);
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
        frameMs = now;

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
     * <p>分工见 {@link Terrain}：可放置区是两格一换的绿格（能数格子）、
     * 左边敌人通道是土黄、右边山区是灰岩。
     *
     * <p><b>三块地现在都铺贴图</b>，但铺法不一样，这正是它们还能一眼分开的原因：
     * 可放置区和通道铺的是同一张 {@code tile_grass.png}（{@link GrassTexture}），
     * 靠乘法系数压成绿 / 土色；山区是另一张图（{@link RockTexture}），
     * 而且和整页背景共用，所以它跟屏幕边缘连成一片，读起来是"地到这儿就没了"。
     *
     * <p><b>"能建 / 不能建"靠颜色说，不靠棋盘格。</b>
     * 贴图到位之前可放置区是一格一换的深浅两色，实拍草地把那一档盖掉了
     * （真机量过，理由写在 {@link GrassTexture} 的类注释里）。
     * 数格子交给格线，而格线**平时不画**——只在手上拿着东西的时候出现，
     * 见 {@link #drawGrid}。
     *
     * <p>按"同地形的连续格"成段画，不是一格一格画：一帧只画几十个矩形，
     * 而不是 80×36 个。这条在低端机上是要紧的——去掉棋盘格之后，
     * 可放置区也从"逐格 2376 个矩形"回到了"每行一段"。
     * （战场翻倍又给这条加了一倍的分量：2880 格逐格画就是每帧 2880 个矩形，
     * 而实际画出来的只有 {@code 3 × rows()} 个左右。）
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
                    rect.set(board.leftX(col), top, board.leftX(col + run), top + cell);
                    canvas.drawRect(rect, grass.buildable());
                } else if (terrain == Terrain.ENEMY_LANE) {
                    rect.set(board.leftX(col), top, board.leftX(col + run), top + cell);
                    canvas.drawRect(rect, grass.lane());
                } else {
                    // 山区铺岩石贴图。贴图钉在布局坐标上，所以逐行逐段分开画
                    // 也不会在接缝处断开——相邻两段拿到的是同一张图的同一块。
                    //
                    // 用的笔和整页背景是同一个对象、同一个颜色，所以这条山和
                    // 它左边的草地之外、它右边的屏幕边缘都是连续的一片石头。
                    rect.set(board.leftX(col), top, board.leftX(col + run), top + cell);
                    canvas.drawRect(rect, rock.paint());
                }
                col += run;
            }
        }
    }

    /**
     * 网格线。<b>只在"手上正拿着东西"的时候画</b>——要放新的（{@link #selected}）
     * 或者要挪旧的（{@link #movingGroup}），其余时候一根都不画。
     *
     * <p>这是 2026-09-27 改的：原来满屏格线是常驻的，理由是"可放置区那格一换的
     * 深浅棋盘格被实拍草地盖掉了，数格子得有东西管"。棋盘格确实不能用了，
     * 但常驻格线也把整片地变成了坐标纸。现在数格子这件事只在**真的要数的时候**
     * 才发生，而那个时刻正好就是摆位——见方法里那两句注释。
     */
    private void drawGrid(Canvas canvas) {
        // **平时一根都不画。** 满屏格线会把草地变成一张坐标纸——玩家要看的是
        // 地形和建筑，不是稿纸。而**摆位是唯一需要数格子的时刻**，那一刻再画出来，
        // 它就从"背景纹理"变成了"操作提示"：线一冒出来，玩家立刻知道
        // "现在点哪儿就是放哪儿"。所以这个开关不是为了省性能，是让同一个东西
        // 只在它有用的时候出现。
        //
        // 反过来，手上有东西的时候不画才糟：占地是 2×2 还是 3×3、
        // 这一格离通道还有几格，全靠这几根线说。
        if (!placing()) {
            return;
        }

        stroke.setStrokeWidth(Math.max(1f, 0.6f * density));
        stroke.setColor(gridLine);

        // **山区那几列不画格线（哪种模式都不画）。** 格线的意思是"这些是格子"，
        // 山不是格子——画上去等于说"这里也能放东西"。以前山是浅色，20% 的黑线
        // 压上去看不太出来；现在山和整页背景是同一个色，那排格线就成了
        // 浮在石头上的笼子。左边敌人通道照样画：敌人是踩着格子走的。
        int gridCols = board.cols() - Battlefield.MOUNTAIN_COLS;
        for (int col = 0; col <= gridCols; col++) {
            canvas.drawLine(board.leftX(col), board.topY(0),
                    board.leftX(col), board.topY(board.rows()), stroke);
        }
        for (int row = 0; row <= board.rows(); row++) {
            canvas.drawLine(board.leftX(0), board.topY(row),
                    board.leftX(gridCols), board.topY(row), stroke);
        }
    }

    /**
     * 手上是不是正拿着东西：要放新的、或者要挪旧的。
     *
     * <p>这两个状态互斥（见 {@link #movingGroup} 的注释），所以判一个 {@code || } 就够。
     * 收在一块儿是因为"什么时候算在摆位"这件事现在有两处要看
     * （{@link #drawGrid}，以及以后任何"只在摆位时出现"的东西），
     * 分开写迟早会岔开。
     */
    private boolean placing() {
        // 拖着一座已选中的建筑走也算：那一下和"放新的"一样是在对格子，
        // 玩家同样需要那几根线来对齐。
        return selected != null || !movingGroup.isEmpty() || drag == Drag.RELOCATE;
    }

    /** 草地那一片最右边那条线的列号（右边全是山区，见 {@code Battlefield.MOUNTAIN_COLS}）。 */
    private int grassCols() {
        return board.cols() - Battlefield.MOUNTAIN_COLS;
    }

    /**
     * 地形交界上的三条过渡带：<b>让相邻的两层地形互相"长"过去一点，
     * 而不是拿尺子切一刀</b>。
     *
     * <p>{@link #drawGround} 是逐格铺贴图，铺出来的边界天生是笔直的：
     * 上方一行、左右各一列，四个角是四个直角，而且两边明度差一大截，
     * 于是屏幕上就是一条又直又硬的接缝。这一层单独补它。
     *
     * <h2>三条毯子，全都铺在"不能放东西"的那一侧</h2>
     * <table>
     *   <tr><td>可建区的绿</td><td>→ 往右、往上、往下铺到石头上</td>
     *       <td>{@link #buildBleedFrame}，{@link #edgePaths}</td></tr>
     *   <tr><td>通道的土黄</td><td>→ 往左、往上、往下铺到石头上</td>
     *       <td>{@link #buildBleedFrame}，{@link #lanePaths}</td></tr>
     *   <tr><td>可建区的绿</td><td>→ 往左铺进通道里</td>
     *       <td>{@link #buildFadeFrame}，{@link #fadePaths}</td></tr>
     * </table>
     * <p><b>一条都不许铺进可放置区。</b>那是玩家的地，铺进去他就分不清
     * "这一格到底能不能放"——看着不像能放，在他眼里就是不能放。
     * 所以三条毯子的内沿全都压在地形边界上，一像素都不往里让。
     *
     * <h2>两层一起做，缺一层都不像</h2>
     * <ol>
     *   <li><b>起伏</b>（{@link #EDGE_WOBBLE_CELLS}）：边界不再是一条直线。
     *       用整数格上的哈希噪声、格内平滑插值，各边各一个种子。
     *       太规律的波浪看着像装饰，所以两个频率叠一起。</li>
     *   <li><b>渐隐</b>（{@link #EDGE_FADE_CELLS}）：不是"到这儿为止"，
     *       而是越往外越淡，一直淡到没有。这样交界处是一条过渡带，
     *       不是一条线。</li>
     * </ol>
     * <p>光有起伏还是硬的（只是不直了），光有渐隐也还是假的（一条直的晕开带），
     * 两个一起才是"草长到石头缝里、石头埋进草里"。
     *
     * <p>每条毯子都分 {@link #EDGE_STEPS} 层画，从近到远、alpha 逐层减弱
     * （见 {@link #EDGE_ALPHAS}），叠出来的效果就是"越靠边界越实、越往外越透"。
     * 层与层之间铺出去的距离不一样，所以那圈渐隐的**厚度**自己也带着起伏——
     * 厚度均匀的渐隐看着就是一圈雾。
     *
     * <p><b>路径是缓存起来的</b>（{@link #rebuildEdges}）：地形永不改变，
     * 变的只有格子大小。所以每帧只是十几次 {@code drawPath}，
     * 不重新算那几百个点。
     *
     * <p>三条路径<b>互不重叠</b>（一条在可建区外、一条在通道外、一条在通道里），
     * 所以这里按层号混着画和一条一条画完是一个结果，不用讲究顺序。
     */
    private void drawSoftEdges(Canvas canvas) {
        if (edgePaths[0].isEmpty()) {
            return;
        }
        for (int i = 0; i < EDGE_STEPS; i++) {
            canvas.drawPath(edgePaths[i], grassPaints[i]);
            canvas.drawPath(fadePaths[i], grassPaints[i]);
            canvas.drawPath(lanePaths[i], lanePaints[i]);
        }
    }

    /** 格子尺寸或列数变了就重建过渡带的路径。地形本身不变，所以只认这两个。 */
    private void rebuildEdges() {
        if (board == null) {
            return;
        }
        if (Math.abs(board.cellPx() - edgeCellPx) < 0.01f && board.cols() == edgeCols) {
            return;
        }
        edgeCellPx = board.cellPx();
        edgeCols = board.cols();

        float cell = board.cellPx();
        float amp = EDGE_WOBBLE_CELLS * cell;
        float top = board.topY(0);
        float bottom = board.topY(board.rows());
        float laneRight = board.leftX(Battlefield.ENEMY_LANE_COLS);

        for (int i = 0; i < EDGE_STEPS; i++) {
            // 第 i 层铺得比第 i−1 层远一点点。加 amp 是为了让"起伏最小"的那一处
            // 也仍然铺在边界外面——外沿要是缩回地形里面去，和内圈一交叉，
            // EVEN_ODD 会在里面挖出一个洞，看着像地上破了一块。
            float depth = EDGE_FADE_CELLS * (i + 1) / EDGE_STEPS * cell + amp;

            // 可建区：左边界那条挨着通道，不归它铺（那条是 fadePaths 的事）。
            buildBleedFrame(edgePaths[i],
                    laneRight, top, board.leftX(grassCols()), bottom,
                    false, true, depth, amp,
                    SEED_TOP, SEED_RIGHT, SEED_BOTTOM, SEED_LEFT);
            // 通道：右边界那条挨着可建区，同理不铺。
            buildBleedFrame(lanePaths[i],
                    board.leftX(0), top, laneRight, bottom,
                    true, false, depth, amp,
                    SEED_TOP, SEED_RIGHT, SEED_BOTTOM, SEED_LEFT);
            buildFadeFrame(fadePaths[i], laneRight, depth, amp);
        }
    }

    /**
     * 一条<b>往外铺</b>的过渡带：从矩形边界起，往外 {@code depth} 像素之内填
     * <b>矩形里面那层地形</b>，外沿起伏、alpha 越往外越淡。
     *
     * <p><b>内沿就是矩形边界本身，一像素都不往里让。</b>做法是
     * {@link Path.FillType#EVEN_ODD}：外圈一条起伏的线，内圈是那个精确的矩形，
     * 挖掉之后剩下的正好是外面那一圈。不外铺的那两边（可建区的左边、通道的右边）
     * 法线给 0，走的就正是矩形边——那两边一点毯子都不产生。
     *
     * <p>外圈是<b>首尾相接的一整圈</b>（上 → 右 → 下 → 左，{@code close()} 收口），
     * 不是四条独立的线：{@link #appendWobblySide} 把每一头的起伏收成 0，
     * 所以两个角上正好接得住。
     *
     * @param l,t,r,b     地形矩形的四条边（布局坐标）
     * @param bleedLeft   左边要不要铺（不铺的话那条边走精确的矩形边）
     * @param bleedRight  右边要不要铺
     * @param depth       往外铺多少像素（一定 &gt; 起伏幅度，理由见调用处）
     * @param amp         边界起伏的幅度（像素）
     */
    private void buildBleedFrame(Path path, float l, float t, float r, float b,
                                 boolean bleedLeft, boolean bleedRight,
                                 float depth, float amp,
                                 int seedTop, int seedRight, int seedBottom, int seedLeft) {
        float spanX = (r - l) / board.cellPx();
        float spanY = (b - t) / board.cellPx();

        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);

        appendWobblySide(path, true,
                l, t, r, t, 0f, -1f, depth, amp, spanX, seedTop);
        appendWobblySide(path, false,
                r, t, r, b,
                bleedRight ? 1f : 0f, 0f, bleedRight ? depth : 0f, bleedRight ? amp : 0f,
                spanY, seedRight);
        appendWobblySide(path, false,
                r, b, l, b, 0f, 1f, depth, amp, spanX, seedBottom);
        appendWobblySide(path, false,
                l, b, l, t,
                bleedLeft ? -1f : 0f, 0f, bleedLeft ? depth : 0f, bleedLeft ? amp : 0f,
                spanY, seedLeft);
        path.close();

        // 内圈：精确的地形边界，挖掉。
        path.addRect(l, t, r, b, Path.Direction.CW);
    }

    /**
     * 通道里那条绿晕：可建区的草往左铺进通道，{@code depth} 像素之外淡干净。
     *
     * <p>通道和可建区之间那条线也是拿尺子切的（同 {@link #buildBleedFrame} 的理由）。
     * 这条线两边是<b>同一张贴图</b>，变的只是颜色系数，所以这里晕开的是颜色、
     * 用可建区那支绿笔就够，不需要另做一支。
     *
     * <p>方向只能是<b>往通道那边</b>：反过来的话可建区最左边那半格会发黄，
     * 看着就像"这一列也不能放"。
     *
     * @param boundary 通道和可建区的那条分界（通道的右边缘）
     */
    private void buildFadeFrame(Path path, float boundary, float depth, float amp) {
        float top = board.topY(0);
        float bottom = board.topY(board.rows());

        path.reset();
        path.setFillType(Path.FillType.WINDING);
        path.moveTo(boundary, top);
        // 左边那条起伏，右边那条就是分界本身。起伏的两头收成 0，
        // 所以上下两头正好落回 boundary - depth 上，不会跟通道的上下边缘打架。
        appendWobblySide(path, false,
                boundary - depth, top, boundary - depth, bottom,
                -1f, 0f, 0f, amp, board.rows(), SEED_LANE);
        path.lineTo(boundary, bottom);
        path.close();
    }

    /**
     * 沿一条边采样，每个点再沿法线方向偏移 {@code base + 起伏}，写进路径。
     *
     * <p><b>起伏的两头收成 0</b>（那条 {@code sin(πt)}），所以四个角仍然是尖的、
     * 四条边首尾正好接上——不然角上会裂开一道口子，或者两根线交叉出去。
     */
    private void appendWobblySide(Path path, boolean start,
                                  float xa, float ya, float xb, float yb,
                                  float nx, float ny, float base, float amp,
                                  float cells, int seed) {
        int samples = Math.max(2, Math.round(cells * EDGE_SAMPLES_PER_CELL));
        for (int i = 0; i <= samples; i++) {
            float t = i / (float) samples;
            float d = base + amp * (float) Math.sin(Math.PI * t) * wobble(t * cells, seed);
            float x = xa + (xb - xa) * t + nx * d;
            float y = ya + (yb - ya) * t + ny * d;
            if (start && i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
    }

    /**
     * 交界线的起伏：整数格上取噪声、格内平滑插值，两个频率叠一起。值域约 [-1, 1]。
     *
     * <p><b>两个频率是必要的</b>：只留低频，边界变成一条圆滑的波浪，一眼就是画出来的；
     * 只留高频，变成一圈毛刺。低频给"大块进退"、高频给"小牙"，才像石头。
     */
    private static float wobble(float t, int seed) {
        return 0.55f * smoothNoise(t, seed) + 0.45f * smoothNoise(t * 3.7f, seed + 977);
    }

    private static float smoothNoise(float t, int seed) {
        int i = (int) Math.floor(t);
        float f = t - i;
        float s = f * f * (3f - 2f * f);
        return hashNoise(i, seed) * (1f - s) + hashNoise(i + 1, seed) * s;
    }

    /**
     * 一个稳定的伪随机数。**不能用 {@link Random}**：那样每帧都不同，
     * 边界会自己抖起来。这里只要"同一个格子永远给同一个值"。
     */
    private static float hashNoise(int i, int seed) {
        int h = i * 374761393 + seed * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xffff) / 32767.5f - 1f;
    }

    /**
     * 建筑和敌人一起按"脚踩在哪"排序之后再画：越靠下的越后画，于是自然盖住上方的。
     *
     * <p>这就是正方形网格的好处——不需要按 {@code row + col} 之类的怪规则排序。
     */
    private void drawSprites(Canvas canvas) {
        List<Building> carried = carrying() ? relocatingGroup() : Collections.emptyList();
        List<Object> sprites = new ArrayList<>(battlefield.buildings().size()
                + battlefield.enemies().size());
        // 手上拎着的那几座**不参加这一轮**：它们已经离地了，画在原位等于画两座。
        for (Building building : battlefield.buildings()) {
            if (!carried.contains(building)) {
                sprites.add(building);
            }
        }
        sprites.addAll(battlefield.enemies());
        sprites.sort(Comparator.comparingDouble(this::anchorYOf));

        for (Object sprite : sprites) {
            if (sprite instanceof Building) {
                drawBuilding(canvas, (Building) sprite);
            } else {
                drawEnemy(canvas, (Enemy) sprite);
            }
        }

        // 拎在手上的最后画，**盖在所有人上面**：按脚底排序的话，
        // 往南拖过一座墙时它会被那座墙挡住半截——而它此刻离地一格，
        // 屏幕上没有任何东西该挡在它前面。
        for (Building building : carried) {
            canvas.save();
            canvas.translate(carryDCol * board.cellPx(),
                    carryDRow * board.cellPx() - CARRY_LIFT_CELLS * board.cellPx());
            drawBuilding(canvas, building);
            canvas.restore();
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

        // 贴图可以比占地大：往左探的是炮管（塔），往右探的是炮尾（塔），
        // 往上探的是楼顶（核心）。**锚点仍然是占地的底边中点**，探出去的那截
        // 只是画面上的溢出，所以左/右/上各自往外让，只有下边不让。
        float footprintLeft = board.leftX(building.col());
        float left = footprintLeft - building.type.overhangLeftCells * board.cellPx();
        float right = board.leftX(building.col() + building.type.cols)
                + building.type.overhangRightCells * board.cellPx();
        float bottom = board.anchorY(building.row(), building.type.rows);
        float top = bottom - building.type.spriteHeightCells() * board.cellPx();

        Bitmap sprite = sprites.get(building.type, building.level);
        if (sprite != null) {
            // 贴图刚好铺满上面那个矩形，不做任何偏移：图里的构图（炮管伸在左边、
            // 底座踩在下沿）就是按这个矩形画的，见 docs/ART.md §2.1。
            // 比例在 BuildingSprites 构造时校过，所以这里是等比缩放，不会拉变形。
            rect.set(left, top, right, bottom);
            canvas.drawBitmap(sprite, null, rect, sprites.paint());
            return;
        }

        // 兜底：这一种建筑还没有图。四种建筑现在都画完了，所以暂时走不到这里。
        drawBlock(canvas, left, top, right, bottom,
                footprintLeft, board.topY(building.row()), color, building.type.label);
    }

    /**
     * 开火那一下的炮口火焰：<b>盖在塔贴图上的一层</b>，只画炮口那一块。
     *
     * <p><b>怎么知道"刚开了一炮"。</b>装填计时是"开火时加满、之后每帧减"
     * （{@code Battlefield.fireTowers} 里那段注释解释了为什么不是"开火时赋值"），
     * 所以<b>离上一发过去多久 = 射速间隔 - 还剩多少装填</b>。塔在空闲时会把装填夹回 0，
     * 这个差就等于一整个间隔，比 {@link #FLASH_SECONDS} 大，正好不画——
     * <b>引擎一个字都不用改</b>，它已经把这个数摆在外面了（{@code Building#cooldownSeconds}）。
     *
     * <p><b>位置锚在贴图左沿，不是占地。</b>先算出这座塔贴图的 out 边
     * （占地左边再往外 {@code overhangLeftCells} 格），再让火焰那个窗口
     * （{@link BuildingSprites#FlashWindow}）从左沿往两边摊开——窗口的左右沿
     * 是相对贴图左沿量的，所以两种塔各写各的数就行，这里不用分类讨论。
     * 纵向用的就是塔贴图的 top/bottom——火焰图和塔贴图一样高。
     *
     * <p><b>画在所有建筑和敌人之上</b>（在 {@link #drawSprites} 之后）：火焰是光，
     * 被谁挡住都不对。子弹仍然画在火焰上面，因为那是"已经飞出去了"的东西。
     */
    private void drawMuzzleFlashes(Canvas canvas) {
        Paint paint = sprites.flashPaint();
        float cell = board.cellPx();

        for (Building building : battlefield.buildings()) {
            Bitmap flash = sprites.flash(building.type, building.level);
            if (flash == null) {
                continue;
            }
            double interval = BuildingStats.fireIntervalSeconds(building.type, building.level);
            if (interval <= 0.0) {
                continue;   // 墙和核心：没有"开火"这回事
            }
            float sinceShot = (float) (interval - building.cooldownSeconds);
            if (sinceShot < 0f || sinceShot > FLASH_SECONDS) {
                continue;
            }

            BuildingSprites.FlashWindow window = BuildingSprites.flashWindow(building.type);
            if (window == null) {
                continue;
            }

            // 拎在手上的那座：炮口也得跟着走。不跟的话，拖着一座还在开火的塔，
            // 那一闪会亮在它<b>原来</b>待的那一格上——火从一块空地上冒出来。
            // （引擎那边它确实还在原位开火，这是另一件事，见 #commitMoveDrag。）
            boolean lifted = carrying() && relocatingGroup().contains(building);
            float spriteLeft = board.leftX(building.col() + (lifted ? carryDCol : 0))
                    - building.type.overhangLeftCells * cell;
            float bottom = board.anchorY(building.row() + (lifted ? carryDRow : 0),
                    building.type.rows)
                    - (lifted ? CARRY_LIFT_CELLS * cell : 0f);
            rect.set(spriteLeft + window.leftCells * cell,
                    bottom - building.type.spriteHeightCells() * cell,
                    spriteLeft + window.rightCells * cell,
                    bottom);
            // 越接近"刚开火"越不透明，然后淡出：比亮够时间直接消失少一顿
            paint.setAlpha((int) (255f * (1f - sinceShot / FLASH_SECONDS)));
            canvas.drawBitmap(flash, null, rect, paint);
        }

        // 笔是共用的（它自己一支，见 BuildingSprites.flashPaint），用完还回去
        paint.setAlpha(255);
    }

    /**
     * 一只敌人：<b>一个人，站在他自己那圈颜色上</b>。
     *
     * <p><b>锚点是脚底，不是中心。</b>人画在 (x, y) 上方、两只脚踩在
     * {@code y + 半身位} 那一行——和建筑一样是"底边对齐地面"（{@code drawBuilding}
     * 的 bottom 也是占地底边）。<b>这一点是贴图能成立的前提</b>：
     * {@code art/cut_enemy.py} 把原图裁到鞋底那一行为止，所以贴图的底边就是脚底，
     * 这儿不用再记一个"脚在画布往下百分之几"的偏移量。
     *
     * <p><b>贴图比碰撞盒大得多</b>：体格 1.0 的那只，碰撞盒是一格见方，
     * 画出来的人有一格二高、画布一格三见方。碰撞盒是"脚踩的那块地"，
     * 人当然比地高——两者本来就该是两个数，见 {@link EnemySprites}。
     *
     * <p><b>为什么不走 {@link #drawBlock}。</b>那个方法是给建筑写的，它身上带着
     * 两件建筑才有的东西：探出占地的那截画成半透明、占地边缘画一条虚线
     * （"从这儿往下才是能放东西的格子"）。敌人既没有悬空的部分、也没有占地，
     * 套用之后每只敌人身上都横着一条半透明的带子和一条虚线，
     * 而那条虚线在敌人身上什么也不表示。
     *
     * <p><b>三种兵靠什么分。</b>贴图是同一个人（三种动作），所以分不出来的那部分
     * 由脚下那圈颜色补——<b>那圈颜色是玩法信息，不是装饰</b>，
     * 理由写在 {@link #ENEMY_MARK_RING_DP} 上（**为什么不给贴图整体染色**那条）。
     * 体格也在说同一件事：
     * 快兵细一圈、重甲比一格还宽，和那圈颜色互相印证。
     */
    private void drawEnemy(Canvas canvas, Enemy enemy) {
        float cell = board.cellPx();
        float centreX = board.xAt(enemy.x);
        // 脚底那一行。y 是身体中心的连续行坐标，加半个身位就是脚，
        // 换行途中它是平滑移过去的，所以敌人不会在两行之间跳。
        float feetY = board.yAt(enemy.y + enemy.type.halfBodyCells());
        float size = EnemySprites.CANVAS_CELLS_PER_BODY * enemy.type.bodyCells * cell;
        float left = centreX - size / 2f;

        // 先画脚下那圈，再画人：圈是"踩在地上"的，压在腿后面才对。
        float body = enemy.type.bodyCells * cell;
        rect.set(centreX - body * ENEMY_MARK_WIDTH / 2f,
                feetY - body * ENEMY_MARK_HEIGHT / 2f,
                centreX + body * ENEMY_MARK_WIDTH / 2f,
                feetY + body * ENEMY_MARK_HEIGHT / 2f);
        // 底：三种共用的一块深色接地影（自带透明度，不用也不能再调 setAlpha）
        fill.setColor(enemyShadow);
        canvas.drawOval(rect, fill);
        // 边：这一种的颜色。卡住了用压暗的那版，见 ENEMY_MARK_RING_DP
        stroke.setStrokeWidth(Math.max(1.5f, ENEMY_MARK_RING_DP * density));
        stroke.setColor(enemy.blocked
                ? enemyBlockedColors[enemy.type.ordinal()]
                : enemyColors[enemy.type.ordinal()]);
        canvas.drawOval(rect, stroke);

        rect.set(left, feetY - size, left + size, feetY);
        canvas.drawBitmap(spriteFor(enemy), null, rect, enemySprites.paint());
    }

    /**
     * 这一只这一刻该画哪个姿势。
     *
     * <p><b>卡住了 = 在啃东西</b>（{@code Enemy.blocked} 就等于"正在拆身边最近的那座"，
     * 见那个字段的注释），所以"卡住"和"挥击"是同一件事，不用另外判。
     *
     * <p>两种状态各有一个两帧的小循环，帧都是从<b>引擎已经摆在外面的数</b>里取的
     * ——一个是 {@code enemy.x}，一个是本机时钟，见下面两段。
     */
    private Bitmap spriteFor(Enemy enemy) {
        if (enemy.blocked) {
            // 挥击和收势交替。用本机时钟而不是引擎里的数：引擎只给得出"卡住了"，
            // 给不出"啃到第几口了"（伤害是每帧连续扣的），理由见 ENEMY_SWING_PERIOD_MS。
            long phase = frameMs % ENEMY_SWING_PERIOD_MS;
            return phase < ENEMY_SWING_PERIOD_MS * ENEMY_SWING_ATTACK_SHARE
                    ? enemySprites.attack()
                    : enemySprites.stand();
        }
        // 走路的两个姿势按走过几格翻，不按时间——三种兵速度差三倍，
        // 按时间翻会让快兵"脚不动人平移"，理由见 ENEMY_STRIDE_CELLS。
        // 用的是路程 enemy.travelled 不是 enemy.x：竖着换行时 x 不动，看 x 就不迈腿了。
        int step = (int) Math.floor(enemy.travelled / ENEMY_STRIDE_CELLS);
        return (step & 1) == 0 ? enemySprites.walk() : enemySprites.stand();
    }

    /**
     * 把颜色压暗到七成，用来表示"卡住了"。**压暗而不是换第四个颜色**，理由写在
     * {@code colors.xml} 里 {@code game_enemy_grunt} 那一段的最后一条：
     * 换色就把"这是哪一种"丢了，而贴着墙啃的时候恰恰最需要看清种类。
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
            // 条压在头顶：头顶 = 脚底往上一个贴图那么高，也就是 drawEnemy 里的 rect.top。
            // **不能再用"身体中心 ± 半个身位"**了——那是色块时代的算法，
            // 现在贴图比碰撞盒高得多，那样算出来的条会横在人的胸口上。
            float feetY = board.yAt(enemy.y + enemy.type.halfBodyCells());
            float top = feetY - EnemySprites.CANVAS_CELLS_PER_BODY * enemy.type.bodyCells * cell;
            drawBar(canvas, board.xAt(enemy.centreX()), top - gap,
                    body * BAR_WIDTH_RATIO, enemy.hpFraction());
        }

        List<Building> carried = carrying() ? relocatingGroup() : Collections.emptyList();
        for (Building building : battlefield.buildings()) {
            if (building.type != BuildingType.CORE && !building.damaged()) {
                continue;
            }
            // 手上拎着的那座，条跟着贴图一起离地：条留在原处的话，
            // 屏幕上会有一条血槽孤零零飘在被拖空的那一格上。
            boolean lifted = carried.contains(building);
            canvas.save();
            if (lifted) {
                canvas.translate(carryDCol * cell,
                        carryDRow * cell - CARRY_LIFT_CELLS * cell);
            }
            float bottom = board.anchorY(building.row(), building.type.rows);
            float top = bottom - building.type.spriteHeightCells() * cell;
            drawBar(canvas, board.xAt(building.col() + building.type.cols / 2f), top - gap,
                    building.type.cols * cell * BAR_WIDTH_RATIO, building.hpFraction());
            canvas.restore();
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
     * <p>{@code footprintLeft}／{@code footprintTop} 围出来的是<b>占地</b>
     * （脚踩的那片地面，实心），框外到 {@code left}／{@code top} 的是
     * <b>探出占地的部分</b>（半透明 + 虚线）。这块半透明区域就是真贴图会占的地方
     * ——美术出图时的高度按 {@code top} 来、宽度按 {@code left} 来。
     * 现在探出的只有两种：核心往<b>上</b>（楼顶）、塔往<b>左</b>（炮管）。
     */
    private void drawBlock(Canvas canvas, float left, float top, float right, float bottom,
                           float footprintLeft, float footprintTop,
                           int color, @Nullable String text) {
        float radius = Math.min(board.cellPx(), Math.min(bottom - top, right - left)) * 0.12f;

        // 探出占地的部分：半透明，虚线标出它到哪儿为止。
        // 分两块画（上面一块、左边一块），两块**互不重叠**——左边那块从 top 起、
        // 上面那块从 footprintLeft 起，所以将来真出现"既往上又往左"的建筑，
        // 也不会在同一处叠出两层 alpha 变成一块深色。
        fill.setColor(color);
        fill.setAlpha(70);
        if (footprintTop > top) {
            rect.set(footprintLeft, top, right, footprintTop);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
        if (footprintLeft > left) {
            rect.set(left, top, footprintLeft, bottom);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }

        // 占地：实心
        fill.setColor(color);
        fill.setAlpha(255);
        rect.set(footprintLeft, footprintTop, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, fill);

        // 整块贴图的轮廓
        stroke.setStrokeWidth(Math.max(1f, 0.8f * density));
        stroke.setColor(spriteLine);
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, stroke);

        // 占地边界：线以外是"悬在空中"的部分，写实贴图里就是炮管和楼顶
        dashed.setStrokeWidth(Math.max(1f, 0.8f * density));
        canvas.drawLine(footprintLeft, footprintTop, right, footprintTop, dashed);
        if (footprintLeft > left) {
            canvas.drawLine(footprintLeft, footprintTop, footprintLeft, bottom, dashed);
        }

        if (text != null && !text.isEmpty()) {
            // 文字按屏幕大小画：画布被缩放了 zoom 倍，所以这里先除掉
            label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f,
                    getResources().getDisplayMetrics()) / viewport.zoom());

            // 一格只有二十几 dp，而"Wall"这个单词比一格还宽，直接画会糊到隔壁格子上，
            // 把要看的格子边界盖掉。所以放不下就退成首字母，首字母也放不下就干脆不画——
            // 占位阶段方块颜色已经能分清谁是谁，标签只是辅助。
            // 文字放在**占地**里，不是整块贴图里——探出去的那截是炮管，
            // 名字写在炮管上就跑到隔壁格子去了。
            float room = (right - footprintLeft) - 2f * density;
            String shown = label.measureText(text) <= room ? text : text.substring(0, 1);
            if (label.measureText(shown) > room) {
                shown = null;
            }
            if (shown != null) {
                float centreY = footprintTop + (bottom - footprintTop) / 2f
                        - (label.descent() + label.ascent()) / 2f;
                canvas.drawText(shown, (footprintLeft + right) / 2f, centreY, label);
            }
        }
    }

    /**
     * 攻击射界：<b>正在查看的那座塔</b>，以及手上那座塔的预放位置。
     *
     * <p><b>画在建筑底下</b>（在 {@link #drawSprites} 之前），不是盖在上面：
     * 这是"地面上的一块标记"，压在塔身上既糊住塔，半透明色块叠在实心方块上也显脏。
     *
     * <p>范围数值来自 {@link BuildingStats}，单位是格，这里乘格子大小换成像素——
     * 所以放大缩小时圈跟着地一起变，这才是对的：它标的是"打得到几格远"，
     * 不是"屏幕上多大一块"。
     */
    private void drawRanges(Canvas canvas) {
        // 手上拎着的那一批：**跟着手指走**，每一座都在它现在的位置上画一圈。
        //
        // 从前这里画的是"原位上金色一圈 + 落点绿/红一圈"，那是为了留住
        // "挪的是哪一座、它原来在哪儿"这条信息。可建筑本身现在也跟着走了，
        // 原位是一块空地——在那儿画圈等于指着一块空地画射界。所以挪动的那一批
        // 只剩一个圈，就是它此刻待的地方；金圈那条信息由贴图自己说着
        // （它就在圈的正上方悬着）。
        //
        // **每一座都画**，不是只画头一座：一列塔挪动时，几个扇形并排铺开
        // 才看得出"这条走廊拼起来盖住没有"，而那正是决定往左还是往右挪一格
        // 的那件事。整批同色——挪动是原子的，要么整排过去要么整排不过去。
        if (carrying()) {
            for (Building building : relocatingGroup()) {
                drawFireArc(canvas, building.type,
                        building.col() + carryDCol, building.row() + carryDRow,
                        BuildingStats.fireArc(building.type, building.level),
                        ghostOk ? okColor : badColor);
            }
            return;
        }

        // 选中的那一整排**每一座都画**。只画锚点那一座的话，选中一列塔之后
        // 地上只有一圈扇形，玩家以为只选中了一座——而"整排"这件事正是
        // 这一圈圈并排铺开才看得出来的。
        for (Building building : selection) {
            drawFireArc(canvas, building.type, building.col(), building.row(),
                    BuildingStats.fireArc(building.type, building.level), rangeColor);
        }
        // 正在挪的那些（详情面板那条路，还没按下拖动时）：**它们现在待的地方**
        // 也画一圈，一直画到落下去为止。只画手指底下那块虚影是不够的——
        // 手指一抬，屏幕上就没有任何东西说明"正在挪的是哪一座、它原来在哪儿"，
        // 玩家得自己记。两个圈一个说"从这儿"（金）、一个说"到这儿"（绿/红）。
        for (Building building : movingGroup) {
            drawFireArc(canvas, building.type, building.col(), building.row(),
                    BuildingStats.fireArc(building.type, building.level), rangeColor);
        }
        // 手上拎着塔的时候也画一圈：不然"这座塔放这儿能打到哪条路"只能靠猜，
        // 而摆位是这游戏唯一的策略动作。**大炮尤其要靠它**——它那个扇环
        // 又长又窄，玩家不看见那十格的盲区，就会把它摆到前排去。
        //
        // **一整排只画头一座。**一列六座塔就是六个扇环叠在一起，叠到后来
        // 整片地都是半透明的黄，反而看不出每一座自己管哪一段——而"这几座
        // 塔的射界拼起来盖住没有"是玩家真正想看的，那个靠看地面上的
        // 覆盖范围比看线条清楚。
        BuildingType ghost = ghostType();
        if (!ghostCells.isEmpty() && ghost != null) {
            Cell head = ghostCells.get(0);
            drawFireArc(canvas, ghost, head.col, head.row,
                    BuildingStats.fireArc(ghost, ghostLevel()),
                    ghostOk ? okColor : badColor);
        }
    }

    /**
     * 手指底下那块虚影是哪一类建筑；既没拿东西也没在挪时是 {@code null}。
     *
     * <p>画虚影的三处（射界扇形、占地轮廓、红闪）都得知道"现在这块虚影是谁"，
     * 而它有<b>三个</b>来路：{@link #selected}（要放新的）、{@link #movingGroup}
     * （从详情面板按 Move 那条路）、以及{@link #selection}（直接拖场上已选中的那一排）。
     * 与其在三处各写一遍，不如收在这一个方法里。
     *
     * <p><b>第三个来路是 2026-09-27 补上的，补之前它是个哑巴。</b>
     * 那时"直接拖动已选中的建筑"刚做出来，可这一问只认 {@code movingGroup}——
     * 而那条路上 {@code movingGroup} 是空的、{@code selected} 也是空的
     * （{@code selected} 是商店里挑的那一类，不是场上选中的那一座），
     * 于是两个都取不到，虚影和射界扇形<b>一块都不画</b>。表现就是：
     * 按住一座塔拖，屏幕上什么也不动，手指一松塔才凭空跳过去。
     * 换句话说，拖动其实一直是通的，只是全程没有任何东西跟着手指走。
     */
    @Nullable
    private BuildingType ghostType() {
        Building anchor = carrying() ? carriedAnchor() : moving();
        return anchor != null ? anchor.type : selected;
    }

    /**
     * 虚影该按几级算。
     *
     * <p>要放新的那座一定是 1 级（还没放下去），要挪的那座可能已经升过了——
     * 所以挪一座 3 级弩车时画到 4.0 格，不是 3.0。用 1 级算的话，玩家会以为
     * 挪一下就掉级了。
     *
     * <p>（大炮那边看不出差别：它的射界三级一模一样，压根不读等级。）
     */
    private int ghostLevel() {
        Building anchor = carrying() ? carriedAnchor() : moving();
        return anchor != null ? anchor.level : 1;
    }

    /**
     * 一个<b>射界</b>：要么是朝左的扇形（弩车），要么是朝左的<b>扇环</b>（大炮）。
     * {@code arc == null}（这种建筑不会攻击）就什么都不画。
     *
     * <p>圆心取占地的<b>正中</b>，不是某一格的中心：2×2 的塔圆心落在四格交点上，
     * 取任何一格都会让射界整体偏半格，看着像是两边不对称。
     *
     * <p><b>形状完全由 {@link BuildingStats.FireArc} 说了算</b>——半径、张角、
     * 内径都是那一个对象里的数，和 {@code Battlefield} 判定用的是同一份。
     * 所以"画出来的"和"打得到的"不会说岔；这里只负责把四个数变成像素。
     *
     * <p><b>内径那段空白必须画出来，不能省。</b>大炮那个环中间是空的（十格盲区），
     * 画成一个实心扇形就等于告诉玩家"贴脸也打得到"——而那正是这门炮最容易
     * 摆错的地方。留着那块空白，玩家一眼看得出"别把它摆到前线去"。
     */
    private void drawFireArc(Canvas canvas, BuildingType type, int col, int row,
                             BuildingStats.FireArc arc, int color) {
        if (arc == null) {
            return;
        }
        float cell = board.cellPx();
        float inner = (float) arc.innerCells * cell;
        float outer = (float) arc.outerCells * cell;
        float centreX = board.xAt(col + type.cols / 2f);
        float centreY = board.yAt(row + type.rows / 2f);

        // 朝左。Android 的角度是**三点钟方向为 0、顺时针为正**，
        // 所以正左方是 180°；半角从 FireArc 拿，和判定用的是同一个数。
        float start = (float) (180.0 - arc.halfAngleDeg);
        float sweep = (float) (2.0 * arc.halfAngleDeg);

        fill.setColor(color);
        fill.setAlpha(RANGE_FILL_ALPHA);
        stroke.setStrokeWidth(Math.max(1f, 1.6f * density));
        stroke.setColor(color);
        stroke.setAlpha(RANGE_LINE_ALPHA);

        if (inner <= 0f) {
            // 没有盲区：useCenter=true 画出来的是一个**扇形**（两条半径 + 一段弧），
            // 不是弓形——要的就是"这是一个朝左的喇叭口"这个形状本身。
            arcRect.set(centreX - outer, centreY - outer, centreX + outer, centreY + outer);
            canvas.drawArc(arcRect, start, sweep, true, fill);
            canvas.drawArc(arcRect, start, sweep, true, stroke);
        } else {
            // 有盲区：一条路径，外弧 → 内弧 → 收口，中间那块自然空着。
            // 外弧走完当前点停在外沿的终止角上，arcTo 从那儿连一条线到内沿的
            // 同一个角——那就是扇环的侧边；再反着走一圈内弧，close() 收回来。
            arcRect.set(centreX - outer, centreY - outer, centreX + outer, centreY + outer);
            innerRect.set(centreX - inner, centreY - inner,
                    centreX + inner, centreY + inner);
            arcPath.reset();
            arcPath.addArc(arcRect, start, sweep);
            arcPath.arcTo(innerRect, start + sweep, -sweep);
            arcPath.close();
            canvas.drawPath(arcPath, fill);
            canvas.drawPath(arcPath, stroke);
        }

        // 画笔是共用的，用完把 alpha 还回去：不还的话后面那些不显式设 alpha
        // 的描边（轮廓、网格）会跟着变淡。这个坑 drawGround 顶上就踩过一次。
        stroke.setAlpha(255);
    }

    /** 手指下面的预放位置，以及放不下时的红闪。 */
    private void drawOverlay(Canvas canvas) {
        // 正在查看的那座：**只描边、不填色**。填色和它底下那个射界扇形会叠成两层
        // 半透明，把建筑本身糊成灰绿色（真机上撞到过，青色的塔看着像橄榄色）。
        // 而且贴图接上之后，任何一层填色都是盖在美术上的脏东西——
        // "选中"这件事让金边和一个圆去说就够了。
        // 手上拎着的那一批，原位上**不再描金边**：建筑已经跟着手指走了，
        // 原位是一块空地，那儿亮一圈金边等于说"这里还有一座塔"。
        // 它现在的落点是下面那块绿/红，而"拎的是谁"由悬在上面的贴图自己说。
        boolean lifted = carrying();
        if (!lifted) {
            for (Building building : selection) {
                drawFootprintOutline(canvas, new Cell(building.col(), building.row()),
                        building.type, rangeColor, NO_FILL);
            }
            // 正在挪的那些：脚下也一直亮着金边，理由同 drawRanges——
            // 手指抬着的时候得有东西告诉玩家"挪的是这一排"。
            for (Building building : movingGroup) {
                drawFootprintOutline(canvas, new Cell(building.col(), building.row()),
                        building.type, rangeColor, NO_FILL);
            }
        }
        BuildingType ghost = ghostType();
        if (!ghostCells.isEmpty() && ghost != null) {
            // 一排虚影**每座单画**：整排共用一个矩形的话，一列墙会画成一大块
            // （中间那些缝看不见），玩家分不出这是"五座墙"还是"一块五格长的东西"。
            for (int i = 0; i < ghostCells.size(); i++) {
                Cell cell = ghostCells.get(i);
                // 虚影盖住了它自己现在待的地方（想把它放回原位就是这么回事），
                // 不填色才看得见底下那座建筑——填了等于把它涂掉，
                // 玩家会以为它已经没了。
                //
                // **拎在手上时不适用**：那座建筑此刻不在格子里，而是浮在这块
                // 虚影上方一格。填色填的是地上那块"落点"，正好该填满——
                // 它就是部落冲突里建筑底下那一圈绿/红。
                boolean overItself = false;
                if (!lifted) {
                    for (Building building : relocatingGroup()) {
                        if (cell.col == building.col() && cell.row == building.row()) {
                            overItself = true;
                            break;
                        }
                    }
                }
                drawFootprintOutline(canvas, cell, ghost,
                        i < ghostOkRun ? okColor : badColor,
                        overItself ? NO_FILL : GHOST_FILL_ALPHA);
            }
        }
        // 用的是失败那一刻记下的类型，不是此刻手上的东西，理由见 #rejectedType。
        if (rejectedTopLeft != null && rejectedType != null) {
            long age = System.currentTimeMillis() - rejectedAtMs;
            if (age < REJECT_FLASH_MS) {
                drawFootprintOutline(canvas, rejectedTopLeft, rejectedType, badColor,
                        REJECT_FILL_ALPHA);
                postInvalidateOnAnimation();
            } else {
                rejectedTopLeft = null;
                rejectedType = null;
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
