package com.mobilegroup20.tokentrail;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import com.mobilegroup20.tokentrail.contract.model.TokenBundle;
import com.mobilegroup20.tokentrail.data.RepositoryProvider;
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
import com.mobilegroup20.tokentrail.util.TokenFormat;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Random;

/**
 * 主界面：上面是 HUD，中间是战场，下面是操作行和底部导航。
 *
 * <p><b>这一版的重点是战场。</b>建筑和敌人画的是纯色块，不是贴图——先把格子的
 * 大小、建筑占几格、贴图锚点对不对这些问题用色块确认掉，再去画图。换真贴图时只动
 * {@code BattlefieldView.drawBlock}，这里一行都不用改。规格见 {@code docs/ART.md}。
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

    /** 资源换算率：每 100 万 token 换 100 个。和 {@code ResourceType} 注释里写的一致。 */
    private static final long TOKENS_PER_UNIT = 1_000_000L / 100L;

    /**
     * 预览期用的账号。真正的登录还没做，等接了自建账号服务再换成
     * 登录态里那个 uid（服务端从会话 token 解出来的那个，不是客户端传的）。
     */
    private static final String DEMO_UID = "demo-user";


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

        setUpBattlefield();
        setUpShop();
        setUpWaveButton();
        setUpBottomNav();
        showSeasonHeader();
        showHud();
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
        binding.battlefield.setCameraListener(binding.background::setCamera);

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
            public boolean canAfford(BuildingType type) {
                ShopCatalog.Item item = ShopCatalog.find(type);
                // 查不到就放行：宁可免费，也不要因为货架上没有就把玩家卡死在那里。
                return item == null || item.affordable(wallet);
            }

            @Override
            public void spend(BuildingType type) {
                ShopCatalog.Item item = ShopCatalog.find(type);
                if (item == null) {
                    return;
                }
                item.charge(wallet);
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

        // 点建筑 = 看它，不是接着放。这一层把"看了谁"翻成详情面板。
        binding.battlefield.setInspector(new BattlefieldView.Inspector() {
            @Override
            public void onInspected(Building building) {
                if (building == null) {
                    if (buildingDialog != null) {
                        buildingDialog.dismiss();
                    }
                    return;
                }
                showBuilding(building);
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
        });

        // 战况：核心耐久、漏了几只、打没打完。控件自己只在**真有变化**时叫
        // （见 BattlefieldView.notifyStatus），所以这里可以放心地每次都重画
        // 那几个字，不用在别处再判一次。
        binding.battlefield.setListener(this::renderBattleStatus);
    }

    /**
     * 摆一个演示场景。战场有 40 格宽，屏幕一次只看得到十几格，所以场景是
     * <b>从右往左</b>铺开的——开局镜头贴最右边（核心 + 山区入画），往左拖会依次
     * 看到塔、城墙、敌人通道：
     *
     * <pre>
     * 列 0    4                                    34  37   39
     *   [敌人通道][     可建区（两道城墙 + 六座塔）     ][核心][山区]
     * </pre>
     *
     * <ul>
     *   <li>核心 3×3 紧挨着右边山区、上下居中。贴着山区摆是有意的：
     *       核心后面就是石头，没有"背后还能再放东西"的错觉；</li>
     *   <li>两道 1×1 城墙竖着砌（第 10 列和第 22 列），各留几个缺口——
     *       敌人撞墙停住和从缺口漏过去两种表现同屏可见；</li>
     *   <li>塔 2×2 分两处：第 30 列四座守核心，第 14 列两座在两道墙之间；</li>
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
        place(field, BuildingType.CORE,
                cols - Battlefield.MOUNTAIN_COLS - BuildingType.CORE.cols,
                (rows - BuildingType.CORE.rows) / 2);

        // 两道城墙，各留缺口。缺口错开，敌人到了第二道墙才会被分流。
        wallColumn(field, board, 10, 2, 3, 7, 8, 14, 15);
        wallColumn(field, board, 22, 4, 5, 11, 12);

        // 塔：四座守核心（第 30 列），两座在两道墙之间（第 14 列）
        for (int row : new int[]{1, 5, 9, 13}) {
            place(field, BuildingType.TOWER, 30, row);
        }
        for (int row : new int[]{3, 11}) {
            place(field, BuildingType.TOWER, 14, row);
        }

        // 故意留一座 2 级的（桩数据不是"全新开局"）：一级塔和二级塔的范围圈并排
        // 在屏幕上，升级到底改了什么都看得见。全是 1 级的话，详情面板上
        // "Level 1" 那个数字看起来就只是个装饰。
        Building veteran = field.buildingAt(new Cell(14, 3));
        if (veteran != null) {
            field.upgrade(veteran);
        }

        return field;
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

    /** 放得下才放。放不下不是错误（关卡坐标可能越界），跳过就行。 */
    private void place(Battlefield field, BuildingType type, int col, int row) {
        if (field.canPlace(type, col, row)) {
            field.place(type, col, row);
        }
    }

    /**
     * 把量出来的格子尺寸写到战场左上角。
     *
     * <p>这行小字是给「现在这版尺寸对不对」这个问题用的：战场一共多少格、一格多少 dp、
     * 一屏看得见几格。最后那个数是关键——它和 40 的比值就是"要拖几屏"。
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

    // ==================== 建筑详情 ====================

    /**
     * 开建筑详情。点场上任意一座建筑都会走到这儿（见 {@code BattlefieldView.handleTap}）。
     *
     * <p>弹窗只建一次、之后重用，同 {@link #showShop}：每次都重建会把内容重新 inflate
     * 一遍，而且弹出的动画会打断正在看的那个数字。
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
            buildingBinding.bldUpgrade.setOnClickListener(v -> upgradeInspected());
            buildingBinding.bldMove.setOnClickListener(v -> moveInspected());

            // 撤高亮挂在 **dismiss** 上，不是挂在 Close 按钮上。
            // 面板有三种关法：Close 按钮、往下滑、点面板外面——挂按钮上的话，
            // 后两种关完地上那个金圈还亮着，看着像"还选着它"，
            // 但已经没有任何面板能操作它了。
            buildingDialog.setOnDismissListener(d -> {
                inspected = null;
                binding.battlefield.setInspected(null);
            });
        }

        renderBuildingSheet();
        buildingDialog.show();
    }

    /** 关掉详情。收拾战场那一半由 dismiss 监听器干，这里只负责关窗。 */
    private void closeBuilding() {
        if (buildingDialog != null) {
            buildingDialog.dismiss();
        }
    }

    /**
     * 把这一屏的字填一遍。升级前后都要调，所以数值全从 {@code building} 现取，不留缓存。
     */
    private void renderBuildingSheet() {
        if (buildingBinding == null || inspected == null) {
            return;
        }
        BuildingType type = inspected.type;
        int level = inspected.level;

        buildingBinding.bldName.setText(getString(R.string.bld_title, type.label, level));
        buildingBinding.bldSize.setText(getString(R.string.bld_size, type.cols, type.rows));

        // 耐久：三种建筑都有，所以这一行不藏。数字和战场上那条血条同源
        // （Building.hp / maxHp），刚放下的时候是满的——写出来玩家才知道
        // "原来墙是有血的"，不然被打之前它看起来和装饰没区别。
        buildingBinding.bldHp.setText(getString(R.string.bld_hp_label) + " "
                + getString(R.string.bld_hp_value, inspected.hp, inspected.maxHp()));

        // 范围那一行：不攻击的建筑整行 GONE。写"Attack range 0"会被读成"能打但打不着"。
        boolean ranged = BuildingStats.hasRange(type);
        buildingBinding.bldRangeRow.setVisibility(ranged ? View.VISIBLE : View.GONE);
        if (ranged) {
            buildingBinding.bldRange.setText(getString(R.string.bld_range_label) + " "
                    + getString(R.string.bld_range_value,
                    BuildingStats.rangeCells(type, level)));
        }

        Cost upgrade = BuildingStats.upgradeCost(type, level);
        if (upgrade == null) {
            // 现在 null 只有一个意思：到顶了。三种建筑都能升（墙和核心涨耐久），
            // 所以这里不再有"这个版本还不能升"那一支——那个分支连同它的文案
            // 一起删了，留着就是一句永远显示不出来的话。
            buildingBinding.bldUpgradeTitle.setVisibility(View.VISIBLE);
            buildingBinding.bldUpgradeTitle.setText(getString(R.string.bld_at_max, level));
            buildingBinding.bldUpgradeCost.setVisibility(View.GONE);

            // 留一个点不动的按钮："Max level"本身就是信息，比把按钮藏掉清楚
            buildingBinding.bldUpgrade.setVisibility(View.VISIBLE);
            buildingBinding.bldUpgrade.setText(R.string.bld_max_btn);
            buildingBinding.bldUpgrade.setEnabled(false);
            buildingBinding.bldUpgrade.setAlpha(1f);
            return;
        }

        buildingBinding.bldUpgrade.setVisibility(View.VISIBLE);
        buildingBinding.bldUpgradeTitle.setVisibility(View.VISIBLE);
        buildingBinding.bldUpgradeCost.setVisibility(View.VISIBLE);
        buildingBinding.bldUpgradeTitle.setText(getString(R.string.bld_upgrade_to, level + 1));
        buildingBinding.bldUpgradeCost.setText(costText(upgrade));

        // 买不起的时候压暗，但仍然能点：点下去会说清是钱不够，
        // 和商店里"买不起的货也能点"是同一套手感。
        buildingBinding.bldUpgrade.setEnabled(true);
        buildingBinding.bldUpgrade.setText(R.string.bld_upgrade_btn);
        buildingBinding.bldUpgrade.setAlpha(upgrade.affordable(wallet) ? 1f : 0.45f);
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
        Cost upgrade = BuildingStats.upgradeCost(inspected.type, inspected.level);
        if (upgrade == null) {
            return;   // 到顶了或者不能升：按钮是暗的，正常点不到这儿
        }
        if (!upgrade.affordable(wallet)) {
            Toast.makeText(this, R.string.bld_need_more, Toast.LENGTH_SHORT).show();
            return;
        }

        Battlefield field = binding.battlefield.battlefield();
        if (field == null || !field.upgrade(inspected)) {
            // 面板还开着、建筑却没了：多半是这一局被重开过。
            Toast.makeText(this, R.string.bld_gone, Toast.LENGTH_SHORT).show();
            closeBuilding();
            return;
        }

        upgrade.charge(wallet);
        renderBuildingSheet();
        // 范围圈跟着新等级重画：二级塔的圈比一级大一圈，这是升级最直观的回报。
        binding.battlefield.setInspected(inspected);
        binding.battlefield.invalidate();
        Toast.makeText(this,
                getString(R.string.bld_upgraded, inspected.type.label, inspected.level),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 开始挪正在看的这一座。
     *
     * <p><b>不花钱，所以这里没有"先问价、后成交"那一套</b>——那是升级和买新的
     * 才要的（钱会变）。挪动只改坐标：等级、已经花掉的钱都带得走
     * （{@code Battlefield.move}）。
     *
     * <p><b>面板要关掉，而且不是这里主动关的。</b>{@code startMoving} 内部会
     * 走一次"没在看谁了"（{@code inspect(null)}），这一层收到之后把面板关掉，
     * 于是战场上的高亮和这个窗是一起收拾的。绕开这条回调、自己 dismiss 的话，
     * 战场那边会以为还在看着那一座，地上留一圈金边。
     *
     * <p>关掉之后屏幕上就只剩战场了，所以补一句提示告诉玩家下一步该干什么——
     * 不然点完 Move 面板一消失，看着像是点了个"关掉"。
     */
    private void moveInspected() {
        Building building = inspected;
        if (building == null) {
            return;
        }
        binding.battlefield.startMoving(building);
        Toast.makeText(this, getString(R.string.bld_move_hint, building.type.label),
                Toast.LENGTH_SHORT).show();
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
     * <p>顺带把钱包播一次种。**只播一次**：{@code LiveData} 在界面重建、
     * 切账号时会再发一遍，每发一遍就重新播种的话，刚花掉的资源会原样退回来
     * ——买一座塔，转个屏又有了。
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
                ResourceBalance seeded = demoBalance(bundle);
                wallet.input = seeded.input;
                wallet.cache = seeded.cache;
                wallet.output = seeded.output;
                renderShop();
            }
        });
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
     * 切到某一页。<b>四个板块里只有"游戏"那一页是真的</b>，另外三个共用
     * {@code @id/empty_page} 那个空壳，文案现填。
     *
     * <p>要动的东西有三处，缺一处就露馅：
     *
     * <ol>
     *   <li>{@code game_page} 和 {@code empty_page} 二选一——这一层管的是界面
     *       （HUD、操作行、空壳上的那句话）；</li>
     *   <li><b>世界层（{@code battlefield}）也要跟着藏。</b>它是铺满全屏的一层、
     *       画在界面层下面，而空壳是透明的（底该是同一片岩石），不藏的话
     *       统计页上会透出一整个战场；</li>
     *   <li>岩石底（{@code background}）<b>不藏</b>：它是这个 App 的地，
     *       四页站在同一片地上，这也和"界面层只加东西、不加底"那条对得上。</li>
     * </ol>
     *
     * <p><b>藏战场用 {@code INVISIBLE} 而不是 {@code GONE}。</b>{@code GONE} 会让它
     * 量不出尺寸，格子几何和相机就得在切回来的时候整个重算（中间那一帧还是 0×0 的，
     * 取景会跳一下）。{@code INVISIBLE} 照样参与布局、只是不画——顺带把它那套
     * 逐帧重绘也停了（见 {@code BattlefieldView} 的帧闸门）。
     *
     * <p><b>切走了就等于暂停——这是白捡的，不是设计出来的。</b>整场战斗的推进
     * （{@code Battlefield.advance}）挂在 {@code BattlefieldView.onDraw} 里
     * （{@code tick}），而 {@code INVISIBLE} 的视图不会有 {@code onDraw}：
     * 敌人、子弹、波次计时器全冻在原地，回来看接着打。
     *
     * <p>回来时也<b>不会"补帧"</b>：攒下的那几十秒被 {@code MAX_FRAME_SECONDS}
     * （0.05 秒）一刀切掉，第一帧最多走 0.05 秒。所以来回切多少次，
     * 战局都是干净的，不会一回到游戏就发现敌人已经走过半个屏幕。
     *
     * <p>好处是玩到一半去看统计不会输。但要知道它<b>不是</b>一个"暂停功能"：
     * 没有暂停的图标、也不能在暂停时操作。真要做暂停，得从引擎那一层停
     * （别再喂 dt），而不是靠"这一页看不见"这件事——那是碰巧。
     */
    private void showTab(int itemId, CharSequence title) {
        boolean game = itemId == R.id.nav_game;
        binding.gamePage.setVisibility(game ? View.VISIBLE : View.GONE);
        binding.emptyPage.setVisibility(game ? View.GONE : View.VISIBLE);
        binding.battlefield.setVisibility(game ? View.VISIBLE : View.INVISIBLE);
        if (!game) {
            binding.emptyLabel.setText(getString(R.string.nav_not_built, title));
        }
    }
}
