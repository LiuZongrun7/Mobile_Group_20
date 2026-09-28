package com.mobilegroup20.tokentrail;

import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import android.content.res.ColorStateList;
import com.mobilegroup20.tokentrail.ui.forum.ForumFragment;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import com.mobilegroup20.tokentrail.contract.model.TokenBundle;
import com.mobilegroup20.tokentrail.data.RepositoryProvider;
import com.mobilegroup20.tokentrail.data.RelayCredentials;
import com.mobilegroup20.tokentrail.data.SeasonWallet;
import com.mobilegroup20.tokentrail.data.SpendSync;
import com.mobilegroup20.tokentrail.databinding.ActivityMainBinding;
import com.mobilegroup20.tokentrail.databinding.ItemShopBinding;
import com.mobilegroup20.tokentrail.databinding.SheetBuildingBinding;
import com.mobilegroup20.tokentrail.databinding.SheetShopBinding;
import com.mobilegroup20.tokentrail.game.engine.Battlefield;
import com.mobilegroup20.tokentrail.game.engine.BoardGeometry;
import com.mobilegroup20.tokentrail.game.engine.Building;
import com.mobilegroup20.tokentrail.game.engine.BuildingStats;
import com.mobilegroup20.tokentrail.game.engine.BuildingType;
import com.mobilegroup20.tokentrail.game.engine.Cell;
import com.mobilegroup20.tokentrail.game.engine.Cost;
import com.mobilegroup20.tokentrail.game.engine.ShopCatalog;
import com.mobilegroup20.tokentrail.game.engine.Waves;
import com.mobilegroup20.tokentrail.game.view.BattleStatus;
import com.mobilegroup20.tokentrail.game.view.BattlefieldView;
import com.mobilegroup20.tokentrail.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.mobilegroup20.tokentrail.util.TokenFormat;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Random;

/**
 * 主界面：上面是 HUD，中间是战场，下面是操作行和底部导航。
 *
 * <p><b>这一版的重点是战场。</b>建筑和敌人都已经是真贴图（四种建筑全画完了，
 * 只剩子弹还是代码画的白点）——路是先把格子大小、建筑占几格、贴图锚点用色块
 * 确认掉，再一张张换图的，所以换图时只动了 {@code BattlefieldView} 里的画法，
 * 这一层一行都没改。规格见 {@code docs/ART.md}。
 *
 * <p>战斗的规则全在 {@code game.engine}（{@code Battlefield.advance}：塔开火 →
 * 敌人走或者啃 → 清场），这一层只负责把战况讲给玩家听：战场左上角那个胶囊写
 * 核心耐久和漏怪数，发波按钮上写"正在打第几波"，打完了顶一张结果卡片出来
 * （重开一局的入口就在卡片上，也是那同一个按钮）。
 *
 * <p>HUD 上四个 token 桶是仓库里读出来的真数字（桩数据）；<b>商店里的余额不是</b>：
 * 它是按换算率从本月 token 现算的临时值，正式的结算属于 {@code SeasonRepository}
 * （按天推进、以服务端为准），那个类还没写。所以现在买东西是"从一个月度快照里扣"，
 * 关掉应用就回到原样——只用来验玩法，别当真余额。见 {@link #wallet}。
 *
 * <p>屏幕上只剩两个操作按钮：<b>商店</b>和<b>开始波次</b>。选建筑搬进了商店弹窗
 * （{@code layout/sheet_shop.xml}）——要花钱买的东西，价格和余额该和它在一起；
 * 而且底部每多一格，战场上的格子就小一档。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TokenTrail";

    /** 资源换算率：每 100 万 token 换 100 个。和 {@code ResourceType} 注释里写的一致。 */
    private static final long TOKENS_PER_UNIT = 1_000_000L / 100L;

    /**
     * 预览期用的账号。真正的登录还没做，等接了自建账号服务再换成
     * 登录态里那个 uid（服务端从会话 token 解出来的那个，不是客户端传的）。
     */
    private static final String DEMO_UID = "demo-user";

    /** 操作条离面板边缘至少留这么远。贴着边的条子那几个按钮点不着。 */
    private static final float BAR_MARGIN_DP = 6f;

    /**
     * 操作条和选中那座建筑之间留的空。
     *
     * <p>不能是 0：紧贴着建筑顶边的话，条子的下边缘和建筑上边缘糊成一条线，
     * 看着像建筑自己长出来一块。留一点，它才读得出是"浮在旁边的一张卡"。
     */
    private static final float BAR_GAP_DP = 8f;

    private ActivityMainBinding binding;

    /** 随机源只建一次：每次发波都 new 一个的话，同一毫秒内连点会发出一样的波。 */
    private final Random random = new Random();

    /**
     * 演示用的钱包。开局全 0，等月度 token 到了再按换算率播一次种。
     *
     * <p><b>它是个快照，不是真余额。</b>真余额在 {@code SeasonRepository}（按天结算、
     * 以服务端为准），那个类还没实现。所以这里扣掉的钱一关应用就没了。
     * 之所以还是接上，是因为"买不起就别放"这条规则要在真余额到位之前就能验。
     *
     * <p>初值给 0 而不是 null：数据还没到时什么东西都买不起，比"什么都免费"安全
     * ——后者会在数据晚到的那几百毫秒里白送一堆塔。
     */
    private final ResourceBalance wallet = new ResourceBalance();
    /**
     * 钱包播过种没有。
     *
     * <p><b>必须只播一次。</b>{@code monthTokens} 返回的是 {@code LiveData}，
     * 界面重建、账号切换都会再发一遍；每发一遍就重新播种的话，
     * 刚花掉的资源会原样退回来——买一座塔，转个屏又有了。
     */
    private boolean walletSeeded;

    /**
     * 余额来源的决策者：启用中转就用服务端的结算结果，否则回落到演示换算。
     *
     * <p>决策逻辑本身在 {@link SeasonWallet} 里（可单测）。这里只负责把它的结果
     * 搬到界面上，并把「这个数字是哪来的」显示出来。
     */
    private SeasonWallet seasonWallet;

    /** 花钱时的「本地乐观扣 + 服务端对账」编排。规则和理由都在那个类里。 */
    private SpendSync spendSync;

    /** 商店弹窗。开着的时候买了东西要就地刷新余额和"买得起吗"，所以留着引用。 */
    private BottomSheetDialog shopDialog;
    private SheetShopBinding shopBinding;

    /**
     * 建筑详情弹窗。
     *
     * <p><b>和商店分成两个弹窗，不合成一个。</b>它们回答的是两个问题
     * （"能买什么" / "这一座现在什么样"），而且随时可能一个盖着另一个：
     * 手里拎着塔、点一下已有的塔看它——合用一个的话这一下就得二选一，
     * 要么看不到详情，要么手上那件被扔掉。
     */
    private BottomSheetDialog buildingDialog;
    private SheetBuildingBinding buildingBinding;

    /**
     * 详情面板现在讲的是哪一座。
     *
     * <p>升级成功、或者面板要重画的时候靠它找回去。{@link Building} 是引擎那边
     * 的可变对象（{@code level} 就在它身上），所以升级完不用换引用，
     * 同一个对象重画一遍就是新数值。
     */
    private Building inspected;

    /**
     * 和 {@link #inspected} 一起选中的那一整排；空表 = 只选了一座。
     *
     * <p><b>只有这一份。</b>面板上的标题、总价、"整排升级"按钮、Move 按钮
     * 全读它，不给每一处各存一份——存两份的话，选完一整排之后总价和按钮
     * 会各说各的。
     *
     * <p>由 {@code BattlefieldView.Inspector.onInspected} 推进来，
     * 所以它和<b>地上画着的那些金边</b>是同一个来源，不可能说岔。
     */
    private List<Building> selection = Collections.emptyList();

    /**
     * 操作条量出来的宽高，以及它还算不算数。
     *
     * <p>位置每帧都要算（拖地图时建筑在屏幕上一直动），但**尺寸不用每帧量**：
     * 它只在 {@link #renderBuildingBar} 改了字之后才变，那边会把
     * {@link #barMeasured} 打回 {@code false}。
     */
    private boolean barMeasured;
    private int barWidth;
    private int barHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 让内容铺到状态栏和手势条底下：整页岩石底（activity_main.xml 的第 0 层）
        // 要一直铺到屏幕最上/最下边，界面才像浮在场景里而不是被两条系统栏夹着。
        // 让开系统栏的活由布局里那句 fitsSystemWindows 干（它给界面层加 padding）。
        // 配套的是 styles.xml 里那两条透明的 statusBarColor / navigationBarColor，
        // 少任何一句，系统栏后面都会露出旧主题色。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 身份是**账号**（`AccountSession`），不是 relay 配置：没配中转的用户
        // 也该看到自己的余额。中转只影响「用量怎么进来」。
        seasonWallet = SeasonWallet.forDevice(
                com.mobilegroup20.tokentrail.data.AccountSession.get(this),
                RepositoryProvider.season());
        spendSync = new SpendSync(seasonWallet.ledger());
        // 服务端说「这笔没扣成」时，账本只发一个信号，由界面重新拉一次真值——
        // **不在账本里做本地加减**，理由见 SpendSync 的类注释。
        seasonWallet.refreshSignal().observe(this, ignored -> refreshWalletFromServer());
        setUpBattlefield();
        setUpBuildingBar();
        setUpShop();
        setUpWaveButton();
        setUpBottomNav();
        binding.accountButton.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag("account") == null)
                new com.mobilegroup20.tokentrail.ui.auth.AccountDialog().show(getSupportFragmentManager(), "account");
        });
        getSupportFragmentManager().setFragmentResultListener("accountChanged", this, (key, result) -> {
            MenuItem selected = binding.bottomNav.getMenu().findItem(binding.bottomNav.getSelectedItemId());
            showTab(selected.getItemId(), selected.getTitle());
        });
        if (savedInstanceState != null) binding.bottomNav.setSelectedItemId(savedInstanceState.getInt("selectedTab", R.id.nav_dashboard));
        showSeasonHeader();
        showHud();
        showDashboard();
        setUpDashboardActions();
    }

    // ==================== 战场 ====================

    private void setUpBattlefield() {
        // 整页的岩石底跟着战场相机一起动：拖地图时上下两条石头也一起滚，
        // 整页才像一片连续的地，而不是"界面里嵌了个游戏"。
        // 接的是同一个 Viewport 和 BoardGeometry，所以缩放倍数和平移位置天然一致，
        // 不需要额外同步——两边本来就是同一个相机拍同一片地。
        // 见 game/view/MountainBackgroundView 的类注释。
        //
        // 注意两边接的都是 **play_area 那个空占位**，不是 battlefield 控件本身。
        // 战场现在是铺满全屏的（地要能滑到 HUD 底下），它自己已经不表示
        // "战场在屏幕上的哪一块"了；面板是界面上夹在 HUD 和按钮行中间的那一格。
        binding.background.setPanel(binding.playArea);
        binding.battlefield.setPlayArea(binding.playArea);
        // 岩石底和悬浮操作条都得跟着相机走：拖动地图时前者要一起滚，后者要
        // 一直贴在选中那一座旁边。两个都接在这个回调上——分开接的话总有一处
        // 会漏掉某条改相机的路（捏合、拖地图、开局贴右边）。
        binding.battlefield.setCameraListener((viewport, board) -> {
            binding.background.setCamera(viewport, board);
            placeBuildingBar();
        });
        // 面板自己的位置变了（HUD 折行、状态栏高度变化）条子也得重算：
        // 它算的是面板局部坐标，面板一挪，同一个坐标指的就是别的地方了。
        binding.playArea.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> placeBuildingBar());

        // 场地由外面提供而不是由控件自己造：控件只有量完尺寸才知道有多少格子，
        // 而且换关卡/读存档时场地要能整个换掉。
        binding.battlefield.setSceneBuilder(board -> {
            Battlefield field = buildDemoScene(board);
            // 尺寸要等布局完成才知道，而此刻正在 measure/layout 里，所以推到下一轮再回填那行小字。
            binding.battlefield.post(() -> showGridInfo(board));
            return field;
        });

        // 买东西的账接到战场上：落子之前先问价，成交之后才扣钱。
        // 两个方法的分工见 BattlefieldView.BuildGate 的注释——之所以要"先问后扣"，
        // 是因为拖动中的虚影要在手指还没抬起来时就知道该画绿还是画红。
        binding.battlefield.setBuildGate(new BattlefieldView.BuildGate() {

            @Override
            public boolean canAfford(BuildingType type, int count) {
                ShopCatalog.Item item = ShopCatalog.find(type);
                // 查不到就放行：宁可免费，也不要因为货架上没有就把玩家卡死在那里。
                // 一次建一排时问的是**总价**（Cost.times），不是"单价买得起就行"——
                // 资源不通兑，五座墙的总价和一座墙的价在余额够不够上差得远。
                return item == null || item.price().times(count).affordable(wallet);
            }

            @Override
            public void spend(BuildingType type, int count) {
                ShopCatalog.Item item = ShopCatalog.find(type);
                if (item == null) {
                    return;
                }
                // 一次扣**真放下去的那几座**的总价。扣款用的是和上面问价
                // 同一个 times(count)，两边的数必须一模一样——不一致就是
                // 虚影画绿了、钱却扣不动。
                charge(item.price().times(count));
                // 余额变了，HUD 上没有它，但商店开着的话那一屏得跟着变。
                renderShop();
            }

            @Override
            public void onBlocked(BuildingType type, boolean affordable) {
                Toast.makeText(MainActivity.this,
                        affordable ? getString(R.string.shop_no_room, type.label)
                                : getString(R.string.shop_need_more, type.label),
                        Toast.LENGTH_SHORT).show();
            }
        });

        // 点建筑 = 看它，不是接着放。这一层把"看了谁"翻成场上那条悬浮操作条。
        //
        // **不再直接开底部弹窗。** 弹窗是模态的，它一开战场就收不到触摸，
        // "点一下建筑直接拖着它走"这条手感就没了（手指按在建筑上，事件却落在
        // 窗上，玩家看到的是屏幕在滑）。现在点一下只冒出那条**非模态**的操作条，
        // 底下那个战场还是活的——想拖就拖，想看数值再点条子上的 Details。
        binding.battlefield.setInspector(new BattlefieldView.Inspector() {
            @Override
            public void onInspected(Building building, List<Building> selection) {
                // 选中的范围收在这一个字段里（不是每处各存一份），
                // 操作条和"整排升级"读的都是它。
                MainActivity.this.selection = selection;
                // 讲的已经不是原来那一座了（换了目标、或者什么都没选），
                // 那张铺开的数值表就过期了，收掉。**不能等它自己 dismiss**：
                // 它是模态的，赖在屏幕上会挡住下面那条操作条。
                if (building != inspected) {
                    closeBuilding();
                }
                inspected = building;
                if (building == null) {
                    hideBuildingBar();
                    return;
                }
                renderBuildingBar();
                placeBuildingBar();
            }

            @Override
            public void onSelectedChanged(BuildingType type) {
                // 商店正开着的话，把"手上拿着什么"那点状态跟着刷新——
                // 引擎放下一座之后会自动收手，商店不能还标着 "Tower · in hand"。
                renderShop();
            }

            @Override
            public void onMoveBlocked(Building building) {
                // 挪不过去只有"地不平"一种（挪动不花钱，没有钱不够这一说），
                // 所以这句提示不带钱包的事——带上了玩家会跑去赚资源，
                // 而问题其实在位置。
                Toast.makeText(MainActivity.this,
                        getString(R.string.bld_move_blocked, building.type.label),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onReworkBlocked() {
                // 放不下 / 钱不够 / 打起来了，三句话分得清清楚楚。
                // **这正是没把它并进 BuildGate.onBlocked 的原因**：那一句带着
                // "换个地方"或"去赚资源"的言外之意，而这里换哪儿、赚多少都没用，
                // 唯一能等的是这一波打完。
                Toast.makeText(MainActivity.this,
                        R.string.bld_in_combat, Toast.LENGTH_SHORT).show();
            }
        });

        // 战况：核心耐久、漏了几只、打没打完。控件自己只在**真有变化**时叫
        // （见 BattlefieldView.notifyStatus），所以这里可以放心地每次都重画
        // 那几个字，不用在别处再判一次。
        binding.battlefield.setListener(this::renderBattleStatus);
    }

    /**
     * 摆一个演示场景。战场有 80 格宽、36 行高，<b>36 行全在一屏里</b>，
     * 横向只看得到约 24 列，所以场景是<b>围着核心铺开</b>的——开局镜头贴最右边
     * （核心 + 山区入画），往左拖会依次看到塔、城墙、敌人通道（约 3.4 屏走完）。
     *
     * <p><b>2026-09-27 起，下面所有坐标都是相对核心的偏移量，不是绝对列号。</b>
     * 那天战场从 40×18 翻倍到 80×36，核心从第 33 列挪到了第 71 列，而原来那串
     * 写死的列号（墙 10 / 22、塔 14 / 30、弩车 32）留在了战场最左边——开局看到的是
     * 一座孤零零的核心，演示的几十座建筑在五屏之外。按偏移量写就不怕这个：
     * 核心搬到哪儿，场面跟到哪儿。纵向同理：36 行现在全在一屏里，塔和弩车
     * 摆在核心上下 ±8 行以内，是为了开局一眼就能看全整个布防。
     *
     * <ul>
     *   <li>核心 3×3 紧挨着右边山区、上下居中。贴着山区摆是有意的：
     *       核心后面就是石头，没有"背后还能再放东西"的错觉；</li>
     *   <li>两道 1×1 城墙竖着砌（核心左边 23 列和 11 列），各留几个缺口——
     *       敌人撞墙停住和从缺口漏过去两种表现同屏可见。墙是<b>整列砌满</b>的
     *       （36 行 = 一屏高），缺口摊在整列上；</li>
     *   <li>塔 2×2 分两处：核心左边 3 列四座守着核心，左边 19 列两座在两道墙之间；</li>
     *   <li>最左 {@code ENEMY_LANE_COLS} 格和最右 {@code MOUNTAIN_COLS} 格空着——
     *       那是敌人通道和山区，{@code canPlace} 自己会拦住。</li>
     * </ul>
     *
     * <p>核心是<b>可以挪</b>的（{@code Battlefield.moveCore}），这里摆的只是开局位置，
     * 玩家选中"Core 3×3"再点一下别处就把它挪走了。
     *
     * <p>每一处都先问 {@code canPlace} 再放：世界尺寸虽然定死了，但关卡一改
     * 这些坐标就可能越界，那样场景会静默地少几座建筑。问一句，放不下就跳过。
     */
    private Battlefield buildDemoScene(BoardGeometry board) {
        Battlefield field = new Battlefield(board);
        int cols = board.cols();
        int rows = board.rows();

        // 核心：贴紧右边山区（再往右就是 MOUNTAIN_COLS 格石头），上下居中
        int coreCol = cols - Battlefield.MOUNTAIN_COLS - BuildingType.CORE.cols;
        int coreRow = (rows - BuildingType.CORE.rows) / 2;
        place(field, BuildingType.CORE, coreCol, coreRow);

        // 场面上的五条列线，全部由核心推出来（理由见上面那段 javadoc）
        int guardCol = coreCol - 3;       // 守核心的箭塔（2 格宽，右沿挨着核心）
        int ballistaCol = coreCol - 1;    // 弩车：塔群后面、紧挨核心，和核心共用一列
        int wallBackCol = coreCol - 11;   // 第二道墙
        int midCol = coreCol - 19;        // 两道墙之间的塔
        int wallFrontCol = coreCol - 23;  // 第一道墙

        // 两道城墙，各留缺口。缺口错开，敌人到了第二道墙才会被分流。
        wallColumn(field, board, wallFrontCol, 4, 6, 14, 16, 28, 30);
        wallColumn(field, board, wallBackCol, 8, 10, 22, 24);

        // 塔：四座守核心，两座在两道墙之间
        for (int row : new int[]{coreRow - 6, coreRow - 2, coreRow + 2, coreRow + 6}) {
            place(field, BuildingType.TOWER, guardCol, row);
        }
        for (int row : new int[]{coreRow - 4, coreRow + 4}) {
            place(field, BuildingType.TOWER, midCol, row);
        }

        // 弩车：三座一排，摆在塔群**后面**（紧挨核心那一列）。
        //
        // 为什么不是摆在塔群前面：弩车薄（80/105/130，比箭塔还少一档），射界又只朝
        // 左边那 60°，摆在敌人必经的那条路上就是站在最前面挨打——炮台还没轮上开火
        // 就先没了。实测（原来第 26 列那版，见 TASKS.md）三座弩车全场只打出 12 发，
        // 两座在敌人手里活不过一分钟；挪到塔后面同一套场面是 54 发，三座里两座
        // 活到最后。这也正是 BuildingStats 里写的"弩车射程远，本来就该摆在箭塔后面"。
        //
        // 这一列是"紧挨核心"：弩车右边那一格正好压在核心的左沿上，左边一列是
        // 守核心的箭塔，弩车的尖正好从塔的缝隙里伸出去。
        //
        // **行只能取核心上面那三档**：这一列和核心共用一列（见上），行再撞上核心
        // 那三行就放不下——而 `place` 是**静默跳过**的，少一座不会报错。
        // 原来第 9 行那座就是这么没的（它和核心的最后一行撞了），
        // 连累下面那个二级升级也一起落空。
        for (int row : new int[]{coreRow - 6, coreRow - 4, coreRow - 2}) {
            place(field, BuildingType.BALLISTA, ballistaCol, row);
        }

        // 故意留升级过的（桩数据不是"全新开局"）：一级塔和二级塔的范围圈并排
        // 在屏幕上，升级到底改了什么都看得见。全是 1 级的话，详情面板上
        // "Level 1" 那个数字看起来就只是个装饰。
        //
        // 弩车那一排也是**一级、二级、三级各一座**，摆成一条：
        // 三级的弩车只有在这儿才看得到，而且三张贴图并排最容易看出"升级换的是外观"。
        // 这不是平衡过的开局，是个展示台——真关卡配平在 TASKS.md 里还挂着。
        Building veteran = field.buildingAt(new Cell(midCol, coreRow - 4));
        if (veteran != null) {
            field.upgrade(veteran);
        }
        upgradeToLevel(field, new Cell(ballistaCol, coreRow - 4), 2);
        upgradeToLevel(field, new Cell(ballistaCol, coreRow - 2), 3);

        return field;
    }

    /**
     * 把 (col, row) 那座建筑一路升到 {@code level} 级。
     *
     * <p>开场摆的是"已经打了几波"的局面，所以要能直接摆出高级建筑。
     * {@code upgrade} 在引擎里是不收钱的（花钱的是界面上点升级那一下），
     * 所以这里可以直接连着调。升一级回满血，所以顺序无所谓。
     */
    private void upgradeToLevel(Battlefield field, Cell at, int level) {
        Building building = field.buildingAt(at);
        while (building != null && building.level < level && field.upgrade(building)) {
            // 条件里已经在升级了，循环体留空
        }
    }

    /** 在某一列砌满城墙，{@code gaps} 里的行留空当缺口。 */
    private void wallColumn(Battlefield field, BoardGeometry board, int col, int... gaps) {
        for (int row = 0; row < board.rows(); row++) {
            boolean isGap = false;
            for (int gap : gaps) {
                if (row == gap) {
                    isGap = true;
                    break;
                }
            }
            if (!isGap) {
                place(field, BuildingType.WALL, col, row);
            }
        }
    }

    /**
     * 放得下才放。放不下不是错误（关卡坐标可能越界），跳过就行。
     *
     * <p><b>跳过要留痕。</b>这个"放不下就跳过"本意是防越界，但它一样会吞掉
     * 真正的摆位错误，而且吞得毫无声响：2026-09-27 那次才发现，演示场景里有一路
     * 弩车和核心撞了一格，于是三座只放上两座，连带那座的两级升级一起落空——
     * 谁都没看出来。所以这里跳过时记一条日志，跑一次 logcat 就能看见。
     */
    private void place(Battlefield field, BuildingType type, int col, int row) {
        if (field.canPlace(type, col, row)) {
            field.place(type, col, row);
        } else {
            Log.w(TAG, "演示场景：" + type + " 放不下 (" + col + ", " + row + ")，跳过");
        }
    }

    /**
     * 把量出来的格子尺寸写到战场左上角。
     *
     * <p>这行小字是给「现在这版尺寸对不对」这个问题用的：战场一共多少格、一格多少 dp、
     * 一屏看得见几格。最后那个数是关键——它和 {@code BoardGeometry.COLS} 的比值
     * 就是"横向要拖几屏"（现在 80 格、一屏约 24 列，是 3.4 屏）。
     * 纵向现在是 36 行全在一屏，所以那个比值只对横向有意义。
     * 第二行的三段是敌人通道 / 可放置区 / 山区各占几列，就是背景图的划分。
     * 正式版可以删。
     */
    private void showGridInfo(BoardGeometry board) {
        Battlefield field = binding.battlefield.battlefield();
        if (field == null) {
            return;
        }
        binding.gridInfo.setText(getString(R.string.game_grid_info,
                binding.battlefield.columns(),
                binding.battlefield.rows(),
                binding.battlefield.cellDp(),
                board.visibleCols(),
                Battlefield.ENEMY_LANE_COLS,
                field.lastBuildableCol() - field.firstBuildableCol() + 1,
                Battlefield.MOUNTAIN_COLS));
    }

    // ==================== 商店 ====================

    private void setUpShop() {
        binding.shop.setOnClickListener(v -> showShop());
    }

    /**
     * 开商店。
     *
     * <p>弹窗只建一次，之后重用：每次重建都要把货架重新 inflate 一遍，
     * 而且滚动位置会跳回顶部。
     */
    private void showShop() {
        if (shopDialog == null) {
            shopDialog = new BottomSheetDialog(this);
            shopBinding = SheetShopBinding.inflate(getLayoutInflater());
            shopDialog.setContentView(shopBinding.getRoot());

            // 弹窗容器默认用主题的 surface（浅色模式下是一块白的），设成透明，
            // 让内容自己那层 bg_sheet（岩石色 + 上面两个圆角）说话。
            // 不设的话圆角外面会露出一圈浅色，在深色战场上非常扎眼。
            View sheet = shopDialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT);
            }

            // 把手上那件放回去。没有它的话，选中之后就没法取消了
            // ——底下那排"选塔/选墙/选核心/只看"已经删掉了。
            shopBinding.shopLookOnly.setOnClickListener(v -> {
                binding.battlefield.setSelected(null);
                shopDialog.dismiss();
            });
        }
        renderShop();
        shopDialog.show();
    }

    /**
     * 把余额和货架刷一遍。开商店时刷，每买一件也刷（见 {@code BuildGate.spend}）。
     *
     * <p>货架是**照着 {@code ShopCatalog} 生成的**，不写死在 XML 里：加一种建筑、
     * 改一个价格，只动那个纯 Java 类，界面和布局一行都不用改。
     */
    private void renderShop() {
        if (shopBinding == null) {
            return;   // 商店还没开过，没东西可刷
        }

        shopBinding.shopBalInput.setText(getString(R.string.shop_balance_part,
                ResourceType.INPUT.displayName, wallet.input));
        shopBinding.shopBalCache.setText(getString(R.string.shop_balance_part,
                ResourceType.CACHE.displayName, wallet.cache));
        shopBinding.shopBalOutput.setText(getString(R.string.shop_balance_part,
                ResourceType.OUTPUT.displayName, wallet.output));

        LinearLayout list = shopBinding.shopList;
        list.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        BuildingType held = binding.battlefield.selected();
        for (ShopCatalog.Item item : ShopCatalog.items()) {
            ItemShopBinding row = ItemShopBinding.inflate(inflater, list, false);

            row.itemName.setText(item.type.label);
            // 副标题只写占地，不写"共几格"：那是算出来的，且要处理单复数，
            // 而这台机器上 plurals 用不了（见 strings.xml 里 shop_size 那段）。
            String note = item.type == BuildingType.CORE
                    ? getString(R.string.shop_size_core, item.type.cols, item.type.rows)
                    : getString(R.string.shop_size, item.type.cols, item.type.rows);
            // 正拿在手上的那件标一下。不标的话，选完再看商店和没选一样，
            // 玩家会以为没点上、再点一次。
            row.itemNote.setText(item.type == held
                    ? getString(R.string.shop_in_hand, note) : note);
            row.itemCost.setText(costText(item.price()));

            // 买不起的压暗一档，而不是禁掉：还能点开看一眼价格，
            // 点下去会提示缺哪种资源。整行藏起来的话玩家不知道自己缺什么。
            row.getRoot().setAlpha(item.affordable(wallet) ? 1f : 0.45f);
            row.getRoot().setOnClickListener(v -> pick(item));
            list.addView(row.getRoot());
        }
    }

    /** 价格写成 {@code Cache 25 + Output 10}，不要钱的那件写 {@code Free}。 */
    private String costText(Cost cost) {
        if (cost.free()) {
            return getString(R.string.shop_cost_free);
        }
        String out = "";
        for (ResourceType resource : ResourceType.values()) {
            long amount = cost.amount(resource);
            if (amount <= 0) {
                continue;
            }
            String part = getString(R.string.shop_cost_part, resource.displayName, amount);
            out = out.isEmpty() ? part : getString(R.string.shop_cost_join, out, part);
        }
        return out;
    }

    /**
     * 选一件拿在手上。
     *
     * <p><b>这里不扣钱。</b>扣钱在真的落下去那一刻（{@code BuildGate.spend}），
     * 否则选中之后没找到地方放，钱就已经没了。
     *
     * <p>选中"核心"时提示语要换：场上已经有一座了，这时点一下是<b>挪</b>不是放第二座
     * （{@code Battlefield.moveCore}），不说清楚玩家会以为买到了第二座。
     */
    private void pick(ShopCatalog.Item item) {
        // 打起来了就先别拿：拿在手上也放不下去（BattlefieldView 那边每一处放都
        // 会被挡），而"手里拎着一件点哪儿都没反应"是所有状态里最难懂的一种。
        // 在这里挡掉，商店关上、什么都没发生，比拿到手上再挨一句提示干净。
        if (inCombat()) {
            Toast.makeText(this, R.string.bld_in_combat, Toast.LENGTH_SHORT).show();
            return;
        }
        // 手上拿了新东西，场上那条选中就撤掉。留着的话会有两个"当前"：
        // 条子还写着一座塔的等级和价钱，而手指底下的虚影已经是刚买的墙了。
        // 而且 BattlefieldView.beginDrag 里"手上有东西"是压过"点到已选中的那座"
        // 的，拖起来会变成铺一排墙——正是玩家想要的，但那一下不该从一个
        // 还亮着旧选中金边的画面上开始。
        binding.battlefield.setInspected(null);
        binding.battlefield.setSelected(item.type);

        Battlefield field = binding.battlefield.battlefield();
        boolean movingCore = item.type == BuildingType.CORE && field != null && field.core() != null;
        Toast.makeText(this, getString(
                        movingCore ? R.string.shop_picked_core : R.string.shop_picked,
                        item.type.label),
                Toast.LENGTH_SHORT).show();

        if (shopDialog != null) {
            shopDialog.dismiss();
        }
    }

    // ==================== 选中建筑的操作条 ====================

    /**
     * 把操作条上的三个按钮接上。开一次就够，和它是哪个建筑无关。
     *
     * <p><b>"整排"是个开关</b>：没选整排时按一下扩成整排，已经选了整排时按一下
     * 缩回一座。两个方向都走 {@code BattlefieldView} 的那两个入口，回来时
     * {@code onInspected} 会把新的范围推回来、条子重画一遍——这里不自己改
     * {@link #selection}，免得条子和地上那些金边说岔。
     */
    private void setUpBuildingBar() {
        binding.barInfo.setOnClickListener(v -> {
            if (inspected != null) {
                showBuilding(inspected);
            }
        });
        binding.barUpgrade.setOnClickListener(v -> upgradeInspected());
        binding.barLine.setOnClickListener(v -> {
            if (selection.size() > 1) {
                binding.battlefield.selectSingle();
            } else {
                binding.battlefield.selectLine();
            }
        });
    }

    /**
     * 那三个方框上的图标和内容说明。
     *
     * <p><b>没有文字，所以 {@code contentDescription} 不是可选项。</b>图标按钮不给
     * 这个的话，读屏软件念出来三个框全是"按钮"——而这三个动词（看数值 / 花钱升级 /
     * 换成整排）恰恰是这一屏的全部操作。价钱也顺带念出来（{@link #costBox}），
     * 那是屏幕上唯一看不见它的地方。
     */
    private void renderBuildingBarIcons(List<Building> group, Cost total, int upgradable) {
        binding.barUpgrade.setContentDescription(upgradable == 0
                ? getString(R.string.bld_max_btn)
                : group.size() == 1
                        ? getString(R.string.bld_bar_upgrade, costText(total))
                        : getString(R.string.bld_bar_upgrade_all, upgradable, costText(total)));
    }

    /**
     * 那三个方框现在该是什么样。升级前后、选中范围变化之后都要调。
     *
     * <p><b>和底部那张数值表共用 {@link #selectedBuildings()} 一个来源</b>：
     * 分成两套渲染的话，方框这边按"整排 4 座"算价、底下那张表却按一座算，
     * 迟早会说岔。
     *
     * <p><b>升级那个框买不起时压暗，但不禁用</b>：点下去会说清是钱不够，
     * 和商店里"买不起的货也能点"是同一套手感。**到顶了也一样能点**——
     * 从前它是禁用的，换了图标之后"禁用"变成了"按下去什么都不发生"，
     * 而那和"我没点到"在屏幕上是同一件事。现在改成点一下弹一句 Max level。
     *
     * <p><b>三个框的尺寸固定，所以这里不用把 {@link #barMeasured} 打回 false</b>
     * ——它只在文字版那会儿需要（"Upgrade 45"和"Upgrade 120"不一样宽）。
     * 图标版唯一的例外是整排那个框会 GONE，那会改宽度，所以那一处还是要失效。
     */
    private void renderBuildingBar() {
        if (inspected == null) {
            return;
        }
        List<Building> group = selectedBuildings();

        Cost total = upgradeTotal(group);
        int upgradable = upgradableCount(group);
        binding.barUpgrade.setAlpha(upgradable == 0 || total.affordable(wallet) ? 1f : 0.45f);
        renderBuildingBarIcons(group, total, upgradable);

        // 这一排只有它自己时整格 GONE：按下去只会选中它自己，
        // 一个点了没反应的框比没有框更让人困惑。
        Battlefield field = binding.battlefield.battlefield();
        int lineSize = field == null ? 1 : field.lineOf(inspected).size();
        boolean wholeRowSelected = selection.size() > 1;
        int wasVisible = binding.barLine.getVisibility();
        binding.barLine.setVisibility(lineSize > 1 ? View.VISIBLE : View.GONE);
        if (binding.barLine.getVisibility() != wasVisible) {
            barMeasured = false;   // 少一个框，条子窄一截
        }
        if (lineSize > 1) {
            // **图标跟着状态换**：整排时是三根竖条，缩回一座时是中间那一根。
            // 两个 drawable 的条宽和位置是对齐的，所以换的时候画面不跳。
            binding.barLine.setImageResource(wholeRowSelected
                    ? R.drawable.ic_bar_single
                    : R.drawable.ic_bar_row);
            binding.barLine.setContentDescription(getString(wholeRowSelected
                    ? R.string.bld_bar_line_clear
                    : R.string.bld_bar_line, lineSize));
        }

        // **收起来的状态在这里翻回可见。** 少了这一句，条子一旦 GONE 就再也
        // 出不来——placeBuildingBar 见它不是 VISIBLE 就直接返回，谁都不会去点亮它。
        binding.buildingBar.setVisibility(View.VISIBLE);
    }

    /** 条子收起来。没选中任何东西时走这条。 */
    private void hideBuildingBar() {
        binding.buildingBar.setVisibility(View.GONE);
    }

    /**
     * 把那一排小方框摆到选中那座建筑的<b>下面</b>。
     *
     * <p><b>锚点是那一座建筑，不是整个选区。</b>选了一整列塔时那个外接矩形又高
     * 又窄，条子会被顶到屏幕边上去；而玩家的手指刚从其中一座上抬起来，
     * 条子落在他刚点的地方才顺。
     *
     * <p><b>为什么在下面。</b>部落冲突那套就是"选中什么，操作就贴在它下沿"，
     * 而这里还有个更实际的理由：手指点在建筑上，建筑<b>上面</b>那一片
     * 才是玩家正在看的地（敌人的来路、射界画出来的扇环都在左边和上边），
     * 框子压在下面挡掉的东西最少。上面放不下时才翻上去。
     * 两个方向都要夹回面板里——建筑贴着屏幕边时，不夹的话框子有一半在屏幕外，
     * 那三个就点不着了。
     *
     * <p><b>这个方法挂在相机回调上，拖地图时每一帧都会进来</b>，所以位置必须
     * 便宜：宽高量一次就记住（图标版是固定尺寸，见
     * {@link #renderBuildingBar} 里那段），而 {@code inspectedBounds()} 那边
     * 只做两次乘加。整条路径上没有文本测量，也没有重新布局。
     */
    private void placeBuildingBar() {
        if (inspected == null || binding.buildingBar.getVisibility() != View.VISIBLE) {
            return;
        }
        RectF bounds = binding.battlefield.inspectedBounds();
        if (bounds == null) {
            return;   // 相机还没建好（第一次布局之前）
        }
        int areaWidth = binding.playArea.getWidth();
        int areaHeight = binding.playArea.getHeight();
        if (areaWidth <= 0 || areaHeight <= 0) {
            return;
        }
        float margin = getResources().getDisplayMetrics().density * BAR_MARGIN_DP;
        float gap = getResources().getDisplayMetrics().density * BAR_GAP_DP;

        // **量一次就够，量完记住。** 这个方法是挂在相机回调上的，拖地图时
        // 每一帧都会进来一次；每次都 measure 的话，一次平移就是几十趟测量。
        // 尺寸只在两处会变：整排那个框显示/隐藏（renderBuildingBar 里会把
        // barMeasured 打回 false），以及主题字号变了重建 Activity——后者
        // 整个 binding 都是新的，barMeasured 自然从 false 开始。
        if (!barMeasured) {
            binding.buildingBar.measure(
                    View.MeasureSpec.makeMeasureSpec(
                            Math.max(0, areaWidth - (int) (2 * margin)),
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            barWidth = binding.buildingBar.getMeasuredWidth();
            barHeight = binding.buildingBar.getMeasuredHeight();
            barMeasured = true;
        }
        int width = barWidth;
        int height = barHeight;

        float x = bounds.centerX() - width / 2f;
        float y = bounds.bottom + gap;              // 贴着建筑下沿，见上面那段
        if (y + height > areaHeight - margin) {
            y = bounds.top - gap - height;          // 下面放不下：翻到建筑上面
        }
        binding.buildingBar.setX(clamp(x, margin, areaWidth - width - margin));
        binding.buildingBar.setY(clamp(y, margin, areaHeight - height - margin));
        binding.buildingBar.setVisibility(View.VISIBLE);
    }

    private static float clamp(float value, float low, float high) {
        // high < low 时（条子比面板还宽）取 low，至少让左边缘对齐——
        // 返回一个负数会把条子推到屏幕外面去。
        return Math.max(low, Math.min(value, Math.max(low, high)));
    }

    // ==================== 建筑详情 ====================

    /**
     * 开建筑详情。**只有操作条上那个 Details 按钮会走到这儿。**
     *
     * <p>这一屏是"读数"用的，不再有升级 / 挪动 / 整排三个按钮——它们全在
     * 场上那条操作条上。分开的理由见 {@code activity_main.xml} 里
     * {@code @id/building_bar} 那段：弹窗是模态的，把动词放在它里面，
     * "拖着建筑走"就做不出来了。
     *
     * <p>弹窗只建一次、之后重用，同 {@link #showShop}：每次都重建会把内容重新
     * inflate 一遍，而且弹出的动画会打断正在看的那个数字。
     */
    private void showBuilding(Building building) {
        inspected = building;

        if (buildingDialog == null) {
            buildingDialog = new BottomSheetDialog(this);
            buildingBinding = SheetBuildingBinding.inflate(getLayoutInflater());
            buildingDialog.setContentView(buildingBinding.getRoot());

            // 同商店：容器设透明，让内容自己那层 bg_sheet 说话。
            View sheet = buildingDialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT);
            }

            buildingBinding.bldClose.setOnClickListener(v -> buildingDialog.dismiss());
        }

        renderBuildingSheet();
        buildingDialog.show();
    }

    /**
     * 关掉那张数值表。
     *
     * <p><b>这里没有"关掉就把选中撤掉"那一条了。</b>撤退这件事现在归
     * {@code BattlefieldView}：点一下空地（{@code handleTap} 里"没建筑、
     * 手上也没东西"那一支）才会"没在看谁了"，条子和金边一起收。
     * 挂在 dismiss 上的话，看完数值随手一关，场上那条操作条和金光会跟着
     * 一起消失——而玩家下一步多半正是想升级或者拖着它走。
     */
    private void closeBuilding() {
        if (buildingDialog != null) {
            buildingDialog.dismiss();
        }
    }

    /**
     * 把这一屏的字填一遍。升级前后都要调，所以数值全从选中的那些建筑现取，不留缓存。
     *
     * <p><b>选中一座和选中一整排走的是同一条路</b>：只有 {@link #selection}
     * 一个来源，{@code count == 1} 时那几处文案退回单座原来的说法。
     * 分成两套渲染的话，"一排"那条路上的标题、总价、按钮迟早会和
     * "一座"那条路对不上。
     */
    private void renderBuildingSheet() {
        if (buildingBinding == null || inspected == null) {
            return;
        }
        BuildingType type = inspected.type;
        List<Building> group = selectedBuildings();
        int count = group.size();

        buildingBinding.bldName.setText(selectionTitle(group));
        buildingBinding.bldSize.setText(getString(R.string.bld_size, type.cols, type.rows));

        // 耐久：三种建筑都有，所以这一行不藏。数字和战场上那条血条同源
        // （Building.hp / maxHp），刚放下的时候是满的——写出来玩家才知道
        // "原来墙是有血的"，不然被打之前它看起来和装饰没区别。
        // **一排时求和**：玩家看着一列墙问的是"这条防线一共还能挨几下"，
        // 报其中一座的血量答的是另一个问题。
        float hp = 0f;
        float maxHp = 0f;
        for (Building building : group) {
            hp += building.hp;
            maxHp += building.maxHp();
        }
        buildingBinding.bldHp.setText(getString(R.string.bld_hp_label) + " "
                + getString(R.string.bld_hp_value, hp, maxHp));

        // 射界那一行：不攻击的建筑整行 GONE。写"Attack range 0"会被读成"能打但打不着"。
        // **一排取等级最高的那一座**：射程随等级涨，报最低的那座会把这一排
        // 真正的覆盖范围说小（一列弩车里升过级的那座是打得更远的）。
        Building widest = inspected;
        for (Building building : group) {
            if (building.level > widest.level) {
                widest = building;
            }
        }
        BuildingStats.FireArc arc = BuildingStats.fireArc(type, widest.level);
        buildingBinding.bldRangeRow.setVisibility(arc != null ? View.VISIBLE : View.GONE);
        if (arc != null) {
            // 有盲区的报成一段区间（大炮的"10 – 20"），没有盲区的只报外径
            // （弩车的"3.0"）。只报外径会把大炮那十格盲区藏起来，
            // 而"能不能打贴脸的"恰好是这两种塔唯一的分工，见 BuildingStats。
            String range = arc.innerCells > 0.0
                    ? getString(R.string.bld_range_band, arc.innerCells, arc.outerCells)
                    : getString(R.string.bld_range_value, arc.outerCells);
            buildingBinding.bldRange.setText(getString(R.string.bld_range_label) + " " + range);
        }

        // 升级价。**这一行是从场上的操作条搬过来的**（那三个方框只剩图标），
        // 所以它读的是同一对方法（upgradeTotal / upgradableCount），
        // 和条子上那个框按下真会扣的钱是同一个数。
        // 到顶了写"Max level"而不是整行藏起来：藏起来的话玩家分不清
        // 是"没有下一级"还是"这一屏不显示价钱"。
        int upgradable = upgradableCount(group);
        buildingBinding.bldCost.setText(upgradable == 0
                ? getString(R.string.bld_cost_label) + " " + getString(R.string.bld_max_btn)
                : getString(R.string.bld_cost_label) + " " + costText(upgradeTotal(group)));
    }

    /**
     * 标题那一句。**操作条和底部数值表共用它**，两处写的是同一件事。
     *
     * <p>一座时报"等级"，一排时报"几座 + 等级"；一排里等级不一样时报成一段
     * 区间——一排墙里有一座升过级了，只报其中一座的等级会让玩家以为升级白升了。
     */
    private CharSequence selectionTitle(List<Building> group) {
        BuildingType type = inspected.type;
        if (group.size() == 1) {
            return getString(R.string.bld_title, type.label, inspected.level);
        }
        int lowest = inspected.level;
        int highest = inspected.level;
        for (Building building : group) {
            lowest = Math.min(lowest, building.level);
            highest = Math.max(highest, building.level);
        }
        return lowest == highest
                ? getString(R.string.bld_title_line, type.label, group.size(), lowest)
                : getString(R.string.bld_title_line_mixed, type.label, group.size(),
                        lowest, highest);
    }

    /**
     * 这一屏现在管着哪几座：选了整排就是整排，只选了一座就是那一个。
     *
     * <p>空的时候退回 {@code [inspected]}，所以调用方永远拿到一张非空的表，
     * 不用在每处再判一次。
     */
    private List<Building> selectedBuildings() {
        if (selection.isEmpty()) {
            return Collections.singletonList(inspected);
        }
        return selection;
    }

    /**
     * 这一排升一次的总价：<b>逐座加起来</b>（{@code Cost.plus(Cost)}），
     * 不是单价乘座数——一排里等级不一样时单价根本不同。
     *
     * <p>到顶的那几座跳过：它们没得升，这不是失败。
     */
    private Cost upgradeTotal(List<Building> group) {
        Cost total = Cost.FREE;
        for (Building building : group) {
            Cost step = BuildingStats.upgradeCost(building.type, building.level);
            if (step != null) {
                total = total.plus(step);
            }
        }
        return total;
    }

    /** 这一排里有几座真会升上去。到顶的不算，所以它可能小于 {@code group.size()}。 */
    private int upgradableCount(List<Building> group) {
        int count = 0;
        for (Building building : group) {
            if (BuildingStats.upgradeCost(building.type, building.level) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 升级正在看的这一座。
     *
     * <p><b>顺序和放建筑一样：先问价、后成交。</b>引擎那边（{@code Battlefield.upgrade}）
     * 只管涨不涨得动，钱是这一层的事——反过来的话，升到顶级之后再点一下，
     * 钱会先被扣掉而等级没动。
     */
    private void upgradeInspected() {
        if (inspected == null) {
            return;
        }
        // 打起来了不给升。**排在问价前面**：这一下不成的原因是"现在不让动"，
        // 不是"钱不够"——反过来先问价的话，钱包正好不够的玩家会收到
        // "去赚资源"，等他赚回来这一波早打完了，而那句话从头到尾都是错的。
        if (inCombat()) {
            Toast.makeText(this, R.string.bld_in_combat, Toast.LENGTH_SHORT).show();
            return;
        }
        List<Building> group = selectedBuildings();

        // 先问价。和操作条上那个按钮**读的是同一对方法**（upgradeTotal /
        // upgradableCount），所以"按钮上写的价"和"这里真的要扣的价"
        // 不可能对不上——分开各算一遍迟早会算岔。
        Cost upgrade = upgradeTotal(group);
        int upgradable = upgradableCount(group);
        if (upgradable == 0) {
            // 全到顶了。**说一句再走。** 从前方框是禁用的、点不到这儿；
            // 换成图标之后禁用等于"按下去什么都不发生"，而那和"我没点到"
            // 在屏幕上是同一件事——三个框里挑一个按下去没反应，玩家会去怀疑是框坏了。
            Toast.makeText(this, R.string.bld_max_btn, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!upgrade.affordable(wallet)) {
            // **整排是全部或全不。**只够升其中几座时一座都不升：
            // 升了三座、剩两座没升，玩家自己都记不清哪几座动了——
            // 而这一排在他眼里本来是同一样东西。
            Toast.makeText(this, upgradable == 1
                            ? getString(R.string.bld_need_more)
                            : getString(R.string.bld_upgrade_all_need_more, upgradable),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Battlefield field = binding.battlefield.battlefield();
        if (field == null) {
            return;
        }
        // 逐座升，**按真升上去的那几座收钱**。这样"面板还开着、建筑却没了"
        // （这一局被重开过）时不会为一排已经不在场上的建筑扣款。
        // 价钱在 field.upgrade 之前取：那个方法会把 level 加一，
        // 取晚了就是按新等级问价。
        Cost spent = Cost.FREE;
        int done = 0;
        for (Building building : group) {
            Cost step = BuildingStats.upgradeCost(building.type, building.level);
            if (step == null) {
                continue;
            }
            if (field.upgrade(building)) {
                spent = spent.plus(step);
                done++;
            }
        }

        if (done == 0) {
            // 一座都没升上去：讲的那一批已经不在场上了（这一局重开过）。
            // 连条子一起收掉——留着的话它指着的是上一批 Building。
            Toast.makeText(this, R.string.bld_gone, Toast.LENGTH_SHORT).show();
            closeBuilding();
            binding.battlefield.setInspected(null);
            return;
        }

        charge(spent);
        // 两处都要重画：条子上写着"整排 4 座 / 总价多少"，数值表上写着等级和耐久。
        // 数值表没开着的时候 buildingBinding 还是 null，那边自己会返回。
        renderBuildingBar();
        renderBuildingSheet();
        // 范围圈跟着新等级重画：二级塔的圈比一级大一圈，这是升级最直观的回报。
        // **走 refreshSelection 而不是 setInspected**：后者会把整排缩成一座，
        // 一起升完一排墙之后地上只剩一道金边，玩家会以为刚才只升了一座。
        binding.battlefield.refreshSelection();
        Toast.makeText(this, done == 1
                        ? getString(R.string.bld_upgraded, inspected.type.label, inspected.level)
                        : getString(R.string.bld_upgraded_all, done),
                Toast.LENGTH_SHORT).show();
    }

    // 这里原来有个 moveInspected()——面板上那个 Move 按钮的落点。现在没有了：
    // **挪动就是"选中的那座，直接拖"**（BattlefieldView 的 Drag.RELOCATE），
    // 不用先按一个按钮再点目标位置。少了这一道，摆位从"两步"变成"一步"，
    // 而且和部落冲突是同一套手感。
    //
    // 整排一起挪也还在：条子上按过"整排"之后再拖，动的就是那一整排
    // （Battlefield.moveAll）——入口在 BattlefieldView.beginDrag，
    // 它认的是 selection，不认按钮。

    /**
     * 现在是不是正在打——<b>场上有敌人</b>。
     *
     * <p><b>就一句问话，转发给引擎。</b>规矩本身（为什么是"场上有敌人"而不是
     * "这一局没结束"）写在 {@link Battlefield#canReworkBuildings} 那边，
     * 这里不重说一遍：两边各写一份判断，迟早会有一边先改。
     *
     * <p>战场还没建好时算"没在打"：开局那一瞬间不该把玩家的操作全挡掉。
     */
    private boolean inCombat() {
        Battlefield field = binding.battlefield.battlefield();
        return field != null && !field.canReworkBuildings();
    }

    private void setUpWaveButton() {
        binding.startWave.setOnClickListener(v -> onWaveButton());
        // 结果卡片上那个按钮和"开始波次"打完时是同一个动作（重开一局），
        // 两个入口通到同一个地方——玩家点哪个都行，不用猜。
        binding.resultAgain.setOnClickListener(v -> binding.battlefield.restart());
    }

    /**
     * 按了"开始波次"。<b>这一个按钮在三种状态下管三件事</b>，文案跟着变
     * （见 {@link #renderBattleStatus}）：
     *
     * <ul>
     *   <li>这一局还在打 → 发下一波；</li>
     *   <li>这一局打完了（守住或失守）→ 重开一局。这时它是屏幕上最显眼的按钮，
     *       结果卡片又正好盖在战场中央，两处说同一件事比多长一个按钮强；</li>
     *   <li>上一波还没清完 → 什么都不做，但<b>说清为什么</b>。
     *       悄悄不响应的话，玩家会以为按钮坏了或者游戏卡了。</li>
     * </ul>
     */
    private void onWaveButton() {
        Battlefield field = binding.battlefield.battlefield();
        if (field == null) {
            return;
        }
        if (field.decided()) {
            binding.battlefield.restart();
            return;
        }
        if (!field.enemies().isEmpty()) {
            Toast.makeText(this, R.string.game_wave_pending, Toast.LENGTH_SHORT).show();
            return;
        }
        // 发不发得出去最终还是引擎说了算（五波发完了就不发），这里只是"叫一下"。
        binding.battlefield.startWave(random);
    }

    // ==================== 战斗 ====================

    /**
     * 战况变了：刷新战况胶囊、发波按钮的文案、以及结果卡片。
     *
     * <p>三处说的都是同一份 {@link BattleStatus}，所以它们不会互相矛盾
     * ——比如按钮写着"这一局结束了"而卡片还写着"Wave 3/5"。
     *
     * <p><b>输入框里那个数只有这里在改</b>：战场上那根核心血条是
     * {@code BattlefieldView} 自己按真值画的（每一帧都新），这个胶囊是按
     * 取整后的值填的（变了才填一次）。两者差不到一点血，看不出来。
     */
    private void renderBattleStatus(BattleStatus status) {
        binding.battleStatus.setText(getString(R.string.game_status,
                (int) status.coreHp, (int) status.coreMaxHp, status.leaks));

        if (status.decided()) {
            binding.startWave.setText(R.string.game_play_again);
        } else if (status.waveRunning()) {
            // 一波在场上时按钮按下去没用，所以它这会儿不是"开始"而是"读数"。
            // 按不动的时候也该是有信息量的。
            binding.startWave.setText(getString(R.string.game_wave_running,
                    status.waves, status.totalWaves(), status.enemies));
        } else if (Waves.isLast(status.waves + 1)) {
            // 下一波就是最后一波。提前说一声，玩家可以先把资源花掉再按下去。
            binding.startWave.setText(R.string.game_start_last_wave);
        } else {
            binding.startWave.setText(R.string.game_start_wave);
        }

        renderResult(status);
    }

    /**
     * 结果卡片：这一局打完了就把它顶上来，否则收掉。
     *
     * <p>卡片是<b>幂等的重画</b>（和 {@code renderBuildingSheet} 一样，
     * 需要时随时可以再调一遍），所以重开一局之后不用特意去关它——
     * 重开会推一份 {@code ONGOING} 的新战况过来，这条路自己就把它收掉了。
     */
    private void renderResult(BattleStatus status) {
        if (!status.decided()) {
            binding.resultCard.setVisibility(View.GONE);
            return;
        }
        boolean defended = status.outcome == Battlefield.Outcome.DEFENDED;
        binding.resultTitle.setText(defended
                ? R.string.game_result_defended : R.string.game_result_overrun);
        binding.resultNote.setText(getString(R.string.game_result_note,
                status.waves, status.totalWaves(), status.leaks));
        binding.resultCard.setVisibility(View.VISIBLE);
    }

    // ==================== HUD ====================

    /**
     * 赛季那行。数据来自日历，不是编的：赛季名就用当前月份，剩余天数按本月还剩几天算。
     *
     * <p>真正的赛季（开始/结束时间、结算进度）在 {@code SeasonRepository} 里，还没实现。
     */
    private void showSeasonHeader() {
        LocalDate today = LocalDate.now(TimeUtils.ZONE);
        String month = today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + today.getYear();

        binding.hudSeason.setText(getString(R.string.hud_season, month));
        binding.hudDays.setText(getString(R.string.hud_days_left,
                today.lengthOfMonth() - today.getDayOfMonth()));
        binding.hudBar.setProgress(today.getDayOfMonth() * 100 / today.lengthOfMonth());
    }

    /**
     * HUD：本月 token 总量 + 三个桶。
     *
     * <p>三个桶和三种资源一一对应（{@code CONTRACTS.md} §8 那张表），加起来正好
     * 等于上面那个大数字（{@code total()}）。所以商店里的余额就是用这三格除以
     * {@link #TOKENS_PER_UNIT}——两处说的是同一件事，只是单位不同。
     *
     * <p><b>三个是"显示"的合并，不是数据的合并。</b>数据里 token 还是四个桶
     * （{@link TokenBundle}），因为账单上四个单价差十几倍（读缓存 $0.30/1M
     * 对写缓存 $3.75/1M），混在一起就永远对不上账。合并发生在这一层，
     * 而且只合并这两个缓存桶。**不能反过来把缓存并进 {@code input}**：
     * 那显示的是"输入总量"，而 {@code input} 这个字段本身只是其中
     * <b>没命中缓存也没写进缓存</b>的那一段（见 {@code TokenBundle.input} 的注释），
     * 资源正是从这一段换来的——两个口径一混，商店里的余额就对不上号了。
     *
     * <p>HUD 上的 token 数仍然是本地按 {@code monthTokens} 显示（那是「本月花了多少」，
     * 和余额是两件事）。**钱包余额改由 {@link SeasonWallet} 决定**：启用了中转就用
     * 服务端结算出来的值，没启用才回落到本地演示换算——并且把来源写到界面上，
     * 因为一个数字如果分不清是真实结算还是编的，演示时就没人敢信它。
     */
    private void showHud() {
        String month = TimeUtils.currentMonth();
        RepositoryProvider.usage().monthTokens(DEMO_UID, month).observe(this, bundle -> {
            if (bundle == null) {
                return;
            }
            // 先各自按显示精度取整，大数字用这三个整数相加——**不能直接写
            // bundle.total()**。三个桶各自四舍五入再相加，和"先加再四舍五入"
            // 能差出整整一格（真机上出现过 4.73M+3.25M+871.0K 加起来是 8.85M、
            // 大数字却写 8.84M）。理由和边界见 util/TokenFormat 的类注释。
            long input = TokenFormat.roundForDisplay(bundle.input);
            // 两个缓存桶在这里相加，是显示层的合并，见方法注释。
            long cache = TokenFormat.roundForDisplay(bundle.cacheRead + bundle.cacheWrite);
            long output = TokenFormat.roundForDisplay(bundle.output);

            binding.tokInput.setText(TokenFormat.format(input));
            binding.tokCache.setText(TokenFormat.format(cache));
            binding.tokOutput.setText(TokenFormat.format(output));
            binding.hudTotal.setText(TokenFormat.format(input + cache + output));

            if (!walletSeeded) {
                walletSeeded = true;
                // 回落值**按需算**（供应商形式）：服务端可用时这个换算根本不该发生。
                seasonWallet.balance(() -> demoBalance(bundle)).observe(this, this::applyWallet);
            }
        });
    }

    /** Dashboard 首页：把当前 demo usage 转成用户能直接理解的 token、人民币花费和预算风险。 */
    private void showDashboard() {
        RepositoryProvider.usage().monthTokens(DEMO_UID, TimeUtils.currentMonth()).observe(this, bundle -> {
            if (bundle == null) {
                return;
            }
            long input = TokenFormat.roundForDisplay(bundle.input);
            long cache = TokenFormat.roundForDisplay(bundle.cacheRead + bundle.cacheWrite);
            long output = TokenFormat.roundForDisplay(bundle.output);
            long total = input + cache + output;
            double spend = total / 1_000_000.0 * 8.72;
            double budget = Math.max(140.0, spend / 0.62);
            int budgetPct = (int) Math.min(99, Math.round(spend * 100.0 / budget));

            binding.dashboardMonthTokens.setText(NumberFormat.getIntegerInstance(Locale.US).format(total));
            binding.dashboardTokenDelta.setText("↑ 18% from last week");
            binding.dashboardCnySpend.setText(String.format(Locale.US, "¥%.2f", spend));
            binding.dashboardSpendDelta.setText("≈ ¥8.72 / 1M tokens");
            binding.dashboardBudgetPercent.setText(budgetPct + "%");
            binding.dashboardBudgetText.setText(String.format(Locale.US, "¥%.2f / ¥%.2f", spend, budget));
            binding.dashboardRunway.setText("53 days runway");
            binding.dashboardMissingPercent.setText("6%");

            renderDashboardProviders(total, spend);
            renderDashboardTrend();
        });
    }

    private void renderDashboardProviders(long total, double spend) {
        double[] ratios = {0.52, 0.24, 0.15, 0.09};
        TextView[] rows = {
                binding.dashboardProviderOpenai,
                binding.dashboardProviderClaude,
                binding.dashboardProviderGemini,
                binding.dashboardProviderOther
        };
        String[] names = {"OpenAI", "Claude", "Gemini", "Others"};
        NumberFormat tokenFormat = NumberFormat.getIntegerInstance(Locale.US);
        for (int i = 0; i < rows.length; i++) {
            long tokens = Math.round(total * ratios[i]);
            double providerSpend = spend * ratios[i];
            rows[i].setText(String.format(Locale.US, "%s  %.0f%%\n%s tokens · ¥%.2f",
                    names[i], ratios[i] * 100, tokenFormat.format(tokens), providerSpend));
        }
    }

    private void renderDashboardTrend() {
        if (binding.dashboardTrendBars.getChildCount() > 0) {
            return;
        }
        int[] values = {55, 70, 44, 86, 58, 108, 72, 96, 132, 88, 116, 74, 104, 154};
        for (int value : values) {
            View bar = new View(this);
            bar.setBackgroundColor(0xFF1976D2);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(value), 1f);
            params.setMargins(dp(3), 0, dp(3), 0);
            binding.dashboardTrendBars.addView(bar, params);
        }
    }

    private void setUpDashboardActions() {
        binding.dashboardPriceCard.setOnClickListener(v -> Toast.makeText(this,
                "Price Center: model prices and official top-up links", Toast.LENGTH_SHORT).show());
        binding.dashboardPriceIcon.setOnClickListener(v -> binding.dashboardPriceCard.performClick());
        binding.dashboardAiCard.setOnClickListener(v -> Toast.makeText(this,
                "AI Assistant will explain usage changes from your records", Toast.LENGTH_SHORT).show());
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    /**
     * 唯一的花钱入口：先本地乐观扣，再交给 {@link SpendSync} 去服务端对账。
     *
     * <p><b>为什么先本地再同步（乐观）。</b>等一次网络往返再落塔，手感会明显发涩；
     * 而且 `CONTRACTS.md` 的定位就是「客户端只读 + 乐观显示」。
     *
     * <p><b>同步失败为什么不自己回滚。</b>失败可能是「服务端拒了」（余额没动），
     * 也可能是「服务端扣了但响应丢了」。自己把资源加回去，在后一种情况下会造成
     * **双重记账**——本地白拿一份。所以那里改成重新拉一次服务端的真值，
     * 那条规则连同它的理由都在 `SpendSync` 里（可单测）。
     *
     * <p>演示模式（没配中转）不发请求：`SeasonWallet.ledger()` 会返回一个空账本。
     */
    private void charge(Cost cost) {
        long[] before = { wallet.input, wallet.cache, wallet.output };
        cost.charge(wallet);
        spendSync.sync(uid(), new SpendSync.Charge()
                .add(ResourceType.INPUT, wallet.input - before[0])
                .add(ResourceType.CACHE, wallet.cache - before[1])
                .add(ResourceType.OUTPUT, wallet.output - before[2]));
    }

    /**
     * 商店买东西的花钱入口。和 {@link #charge(Cost)} 分开是因为
     * {@link ShopCatalog.Item} 不是 `Cost`（它的价格字段是私有的），
     * 但它能用 {@link ShopCatalog.Item#costOf} 报出每种资源要多少。
     *
     * <p>用「本应收多少」而不是「余额差了多少」：走到这里之前 `affordable` 已经
     * 判过了，所以两者相等；而 `costOf` 是货架自己的口径，不会因为别处改了钱包
     * 就对不上。将来把 `Item` 的价格暴露出来之后，这两个重载可以合成一个。
     */
    private void charge(ShopCatalog.Item item) {
        item.charge(wallet);
        SpendSync.Charge charge = new SpendSync.Charge();
        for (ResourceType type : ResourceType.values()) {
            charge.add(type, item.costOf(type));
        }
        spendSync.sync(uid(), charge);
    }

    /**
     * 服务端的账号身份：**账号的 `userId`**，不是 relay key 的 sha256。
     *
     * <p>2026-02 改。以前这里是 `RelayCredentials.uid()`，于是没配中转就没身份、
     * 换个 key 余额就断。现在账号是身份，中转只是其中一条通道。
     * 没登录时返回 null，账本那边会走空实现。
     */
    private String uid() {
        return com.mobilegroup20.tokentrail.data.AccountSession.get(this).accountId();
    }

    /**
     * 重新拉一次服务端余额，覆盖本地显示。
     *
     * <p>**只有服务端模式才覆盖。** 演示模式下这里拿回来的会是回落值
     * （按本月 token 现算的那份），覆盖上去会把刚花掉的资源原样退回来——
     * 买一座塔，一次同步失败就白送一座。
     */
    private void refreshWalletFromServer() {
        if (!seasonWallet.relayConfigured()) {
            return;
        }
        seasonWallet.balance(() -> wallet).observe(this, value -> {
            if (value != null && value.source == SeasonWallet.Source.SERVER) {
                applyWallet(value);
            }
        });
    }

    /**
     * 把余额和它的来源落到界面上。
     *
     * <p>来源那一行不是装饰：`DEMO` 意味着这个数字是按本月 token 现算的演示值，
     * 没有经过服务端结算，重装就没了。评审时被问到「这个余额哪来的」，
     * 答案必须在屏幕上，不在某人的记忆里。
     */
    private void applyWallet(SeasonWallet.Wallet value) {
        if (value == null) {
            return;
        }
        wallet.input = value.balance.input;
        wallet.cache = value.balance.cache;
        wallet.output = value.balance.output;
        if (binding.balanceSource != null) {
            binding.balanceSource.setText(switch (value.source) {
                case SERVER -> getString(R.string.wallet_source_server);
                case DEMO -> getString(R.string.wallet_source_demo);
                case LOADING -> getString(R.string.wallet_source_loading);
            });
        }
        renderShop();
    }

    /**
     * 临时换算：输入、缓存（读+写合并）、输出三条线各算各的，互不通兑。
     *
     * <p>缓存读和写在这里合并成一种资源，是因为 {@code ResourceType} 只有三种；
     * 但代价值得注意——写缓存比读缓存贵得多（$3.75/1M 对 $0.30/1M），
     * 真要平衡的时候得分开定价。HUD 上那四个 token 桶就是分开显示的。
     *
     * <p>这只是钱包的<b>初值</b>：真结算按月结算改成按天推进、以服务端为准之后，
     * 这个换算整个删掉，余额从 {@code SeasonRepository} 来。
     */
    private ResourceBalance demoBalance(TokenBundle month) {
        return new ResourceBalance(
                month.input / TOKENS_PER_UNIT,
                (month.cacheRead + month.cacheWrite) / TOKENS_PER_UNIT,
                month.output / TOKENS_PER_UNIT);
    }

    // ==================== 底部导航 ====================

    /**
     * 底部四个板块：统计 / 游戏 / 论坛 / 我的。
     *
     * <p><b>打开 App 落在哪一页，跟着 {@code menu/bottom_nav.xml} 里的顺序走</b>
     * ——菜单第一项是"统计"，所以落地页就是统计。想换主页就去换那个文件里的顺序，
     * 这里一个字都不用改。
     */
    private void setUpBottomNav() {
        binding.bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId(), item.getTitle());
            return true;
        });
        // 菜单第一项在 listener 挂上之前就已经是选中态了，那一次不会回调过来
        // ——落地页得自己刷一遍，不然会停在布局文件里那个"游戏页 VISIBLE"的
        // 初始状态上，和导航栏亮着的标签对不上。
        MenuItem landing = binding.bottomNav.getMenu().getItem(0);
        showTab(landing.getItemId(), landing.getTitle());
    }

    /**
     * 游戏、论坛与统计/我的占位页之间切换。论坛使用独立 Fragment 和浅色系统栏；
     * 游戏保留岩石底、浅色导航图标。隐藏战场时使用 INVISIBLE，保留格子尺寸。
     * 战斗推进挂在 onDraw 上，所以切走时自然暂停，回来也不会补帧。
     * 论坛 Fragment 留在 FragmentManager 中，保留两个标签各自的游标与滚动位置。
     */
    private void showTab(int itemId, CharSequence title) {
        boolean dashboard = itemId == R.id.nav_dashboard;
        boolean game = itemId == R.id.nav_game;
        boolean forum = itemId == R.id.nav_forum;
        boolean account = itemId == R.id.nav_profile && !BuildConfig.FORUM_BASE_URL.isEmpty();
        boolean lightPage = !game;

        binding.accountButton.setVisibility(account ? View.VISIBLE : View.GONE);
        binding.dashboardPage.setVisibility(dashboard ? View.VISIBLE : View.GONE);
        binding.gamePage.setVisibility(game ? View.VISIBLE : View.GONE);
        binding.emptyPage.setVisibility(game || forum || dashboard ? View.GONE : View.VISIBLE);
        binding.forumPage.setVisibility(forum ? View.VISIBLE : View.GONE);
        binding.battlefield.setVisibility(game ? View.VISIBLE : View.INVISIBLE);
        binding.background.setVisibility(game ? View.VISIBLE : View.INVISIBLE);
        binding.getRoot().setBackgroundColor(lightPage ? 0xFFFFFBFE : Color.TRANSPARENT);
        ColorStateList navColors = ContextCompat.getColorStateList(this, lightPage ? R.color.forum_nav_item : R.color.nav_item);
        binding.bottomNav.setItemIconTintList(navColors);
        binding.bottomNav.setItemTextColor(navColors);
        binding.bottomNav.setBackgroundColor(lightPage ? 0xFFFFFFFF : Color.TRANSPARENT);
        binding.bottomNav.setItemActiveIndicatorColor(ColorStateList.valueOf(lightPage ? 0xFFEAF3FF : 0x40FFFFFF));
        WindowCompat.getInsetsController(getWindow(), binding.getRoot()).setAppearanceLightStatusBars(lightPage);
        WindowCompat.getInsetsController(getWindow(), binding.getRoot()).setAppearanceLightNavigationBars(lightPage);
        getWindow().setNavigationBarColor(lightPage && android.os.Build.VERSION.SDK_INT < 27 ? Color.BLACK : Color.TRANSPARENT);
        Fragment forumFragment = getSupportFragmentManager().findFragmentByTag("forum");
        if (forumFragment == null && forum) {
            getSupportFragmentManager().beginTransaction().add(R.id.forum_page, new ForumFragment(), "forum").commitNow();
        } else if (forumFragment != null) {
            androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
            if (forum) tx.show(forumFragment); else tx.hide(forumFragment);
            tx.setMaxLifecycle(forumFragment, forum ? Lifecycle.State.RESUMED : Lifecycle.State.STARTED).commitNow();
        }
        if (!game && !forum && !dashboard) {
            com.mobilegroup20.tokentrail.data.AccountSession session = com.mobilegroup20.tokentrail.data.AccountSession.get(this);
            boolean relay = com.mobilegroup20.tokentrail.data.RelayCredentials.get(this).configured();
            // 「我的」页：账号状态 + 中转状态。中转设置挂在同一个标签上（点标题进设置），
            // 这样不用新建页面，也不会和第 0 层的岩石底打架（见 ART.md §5）。
            binding.emptyLabel.setText(account
                    ? getString(session.signedIn()
                            ? session.forumTest() ? R.string.forum_test_identity : R.string.account_signed_in
                            : R.string.my_page_signed_out, session.signedIn() ? session.accountName() : "")
                            + "\n\n" + getString(relay ? R.string.my_page_relay_on : R.string.my_page_relay_off)
                    : getString(R.string.nav_not_built, title));
            binding.emptyLabel.setClickable(account);
            binding.emptyLabel.setOnClickListener(account ? v -> openRelaySetup() : null);
        }
    }
    /** 打开中转设置。同一个 tag 只留一个实例，避免连点叠出多个对话框。 */
    private void openRelaySetup() {
        androidx.fragment.app.FragmentManager manager = getSupportFragmentManager();
        if (manager.findFragmentByTag("relay") == null)
            new com.mobilegroup20.tokentrail.ui.auth.RelaySetupDialog().show(manager, "relay");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("selectedTab", binding.bottomNav.getSelectedItemId());
        super.onSaveInstanceState(state);
    }
}
