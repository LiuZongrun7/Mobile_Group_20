package com.mobilegroup20.tokentrail.game.engine;

import com.mobilegroup20.tokentrail.contract.model.ResourceType;

/**
 * 建筑"第几级是什么样"：血量、攻击范围、伤害、升到下一级要多少钱。
 *
 * <p><b>为什么升级价在这儿，不在 {@link ShopCatalog}。</b>货架卖的是"从无到有"
 * （{@code Item.type} 是一件商品），而升级是"某座已有的建筑涨一级"——同一种塔，
 * 当前几级决定下一级多少钱，货架上没有这一行。两个都花资源，但一个是商品目录、
 * 一个是单体属性，混在一起以后加"墙升 2 级"就会互相打架。
 *
 * <p><b>每一种建筑都有耐久，两种塔才有火力。</b>墙是"挡路的"、核心是"要守住的"，
 * 两者都不攻击，所以 {@link #damagePerShot}／{@link #fireIntervalSeconds}／
 * {@link #fireArc} 对它们一律返回 {@code null}，{@link #hasRange} 是给界面判断
 * "画不画那个圈"用的。但<b>耐久每种都有</b>（{@link #maxHp}），而且都跟着等级涨
 * ——墙升级涨的是"能多挨几秒"，核心升级涨的是"这一个月的上限有多高"。
 * 每种都能升级（{@link #upgradeCost}），因为每种都有"升级能换来什么"。
 *
 * <p><b>两种塔是两张表，不是一个表加个倍率。</b>大炮和弩车是反着配的
 * （远而重的一门炮 对 贴脸的连发弩，见下面各自的 {@code FireArc}），
 * 同一个倍率乘不出这种关系。加第三种塔的时候照做：再一张表，
 * 别想着"共用一张表、按类型乘个系数"——那正是把两种塔做成同一种的一种方式。
 *
 * <h2>射界是一个 {@link FireArc}，不是"一个半径"</h2>
 *
 * <p>从前射界只有"半径"这一个数，张角是全局常数——那时两种塔只是远近不同。
 * 2026-09-27 大炮改成<b>扇环</b>（有内径、外径、张角、溅射四个数）之后，
 * 一个半径说不清了，于是收进一个 {@link FireArc}。
 *
 * <p><b>这四个数必须一起传、一起读。</b>拆成四个并列的方法（{@code minRange}／
 * {@code maxRange}／{@code halfAngle}／{@code splash}）的话，任何一处漏读一个
 * 都不会报错：漏读内径，大炮就会去打贴脸的那只；漏读张角，它就变成一个二十格的
 * 正圆。两种都是静悄悄改变玩法的那种 bug。所以判定只走
 * {@link FireArc#catches}，取射界只有 {@link #fireArc} 一个入口。
 *
 * <h2>表是按级排的，不是一串 if</h2>
 *
 * <p>每一种的耐久都是 {@code {一级, 二级, 三级}}，下标就是"级数 - 1"。写成表是因为
 * 加一级、调一档的时候要能一眼看出<b>整列</b>——弩车的射程 3.0/3.5/4.0 和 dps
 * 13.1/18.8/26.4 摆在一起才看得出"升级涨得比线性多一点"，散在几个 switch 里就看不出来了。
 *
 * <p>纯 Java，不 import 任何 {@code android.*}：数值平衡要能在电脑上直接跑测试调，
 * 不用装模拟器。
 */
public final class BuildingStats {

    /** 最高几级。到了就不许再升——没有上限的话最后会变成"谁钱多谁赢"。 */
    public static final int MAX_LEVEL = 3;

    /**
     * 一种塔的<b>射界</b>：一个朝左的扇形，或者一个朝左的<b>扇环</b>（圆环的一段）。
     *
     * <p>四个数——内径、外径、半角、溅射——是<b>一件事的四个侧面</b>，所以绑在一起。
     * 拆成四个并列的 getter 的话，漏读一个不会报错，只会静悄悄地改玩法：
     * 漏掉内径，大炮会去打贴脸的那只（"最小射程"整个失效）；漏掉半角，
     * 那门炮变成一个二十格的正圆。这两种都要玩到才发现，所以这里不给拆的机会。
     *
     * <h2>内径 {@code > 0} 是什么意思</h2>
     *
     * <p>内径以内是<b>盲区</b>，不是"打得更准"：敌人贴到脸上，这门炮就是哑的。
     * 大炮正是这样一门炮（见 {@link #CANNON_INNER_CELLS}）——它换来的是二十格
     * 的射程。所以"大炮能不能自卫"这个问题的答案是<b>不能</b>，而且这是设计，
     * 不是漏配：要守贴脸的那几只，摆弩车或者砌墙。
     *
     * <h2>张角为什么记正切</h2>
     *
     * <p>判定拿正切当斜率比（{@link #inSector}），每帧每座塔每只敌人都要算一次，
     * {@code Math.atan2} 是不必要的开销。正切在构造时算好存下来。
     */
    public static final class FireArc {

        /** 内径（格），从占地中心算。{@code 0} = 没有盲区，扇形从圆心开始。 */
        public final double innerCells;

        /** 外径（格），从占地中心算。 */
        public final double outerCells;

        /** 半角（度），以<b>正左方</b>为中心上下各张开这么多。 */
        public final double halfAngleDeg;

        /** {@link #halfAngleDeg} 的正切，见类注释。 */
        public final double halfAngleTan;

        /** 落点周围几格之内一起挨打。{@code 0} = 只打中的那一只，没有溅射。 */
        public final float splashCells;

        private FireArc(double innerCells, double outerCells,
                        double halfAngleDeg, float splashCells) {
            // 射界是"朝左的一个喇叭口"，所以内径必然小于外径。反过来的话
            // {@link #catches} 恒为假——又是一种"塔一声不吭地不开火"。
            if (innerCells < 0.0 || outerCells <= innerCells) {
                throw new IllegalArgumentException(
                        "射界必须是 0 <= 内径 < 外径，实际 " + innerCells + " / " + outerCells);
            }
            // 判定是拿正切当斜率比的，所以这个数必须落在 (0, 90] 里：
            // 到了 90° 正切是"正无穷"（半个平面），再往上正切翻成负数，
            // 于是 {@code |dy| <= -dx * tan} 恒不成立——<b>射界会静悄悄地空掉</b>，
            // 表现为"所有塔都不开火"，排查起来离这个常数很远。这里直接炸掉。
            if (halfAngleDeg <= 0.0 || halfAngleDeg > 90.0) {
                throw new IllegalArgumentException(
                        "射界半角必须落在 (0, 90]，实际 " + halfAngleDeg
                                + "；超过 90° 正切变负，射界会变成空的");
            }
            if (splashCells < 0f) {
                throw new IllegalArgumentException("溅射半径不能是负数，实际 " + splashCells);
            }
            this.innerCells = innerCells;
            this.outerCells = outerCells;
            this.halfAngleDeg = halfAngleDeg;
            this.halfAngleTan = Math.tan(Math.toRadians(halfAngleDeg));
            this.splashCells = splashCells;
        }

        /**
         * 一个<b>点</b>在不在这个射界里（连续格子坐标，原点在塔心）。
         *
         * <p>{@code dx} 是"敌人在塔的哪一侧"（正数 = 右边），所以第一句就把右边的
         * 全部挡掉；剩下的按半角卡上下张角。
         *
         * <p><b>正左正右的边界</b>：{@code dx == 0}（敌人正在塔的正上/正下方）
         * 不算——扇形是"朝着左边"的，正侧面那一线不属于它。这一条窄得几乎碰不到，
         * 而且真碰到时塔会去打下一只，不会卡住。
         */
        public boolean inSector(double dx, double dy) {
            if (dx >= 0) {
                return false;
            }
            return Math.abs(dy) <= -dx * halfAngleTan;
        }

        /**
         * 一只<b>有身位</b>的敌人挨不挨得到：中心在 {@code dx,dy}，半身位
         * {@code halfBodyCells}。射界之外先不算。
         *
         * <p><b>判定是"碰到就算"，两条边都算。</b>外径那条和从前一样
         * （身子探进扇形里就挨打，理由见 {@code Battlefield.frontmostInRange}）；
         * 内径那条是它的镜像——身子已经探进盲区、但中心还在射程里的那只
         * <b>照样挨打</b>。不这样写的话会出现"大块头站在 10.1 格挨打、挪到 9.9 格
         * 反而安全"，在画面上的表现是塔忽然停火，而它面前的敌人没变位置。
         *
         * <p>身位是<b>逐只</b>算的，不是全局一个常数：重甲比杂兵宽，所以它能从
         * 更远处就开始挨打。这正好和它"厚"这件事配套。
         */
        public boolean catches(double dx, double dy, double halfBodyCells) {
            if (!inSector(dx, dy)) {
                return false;
            }
            double distance = Math.hypot(dx, dy);
            return distance - halfBodyCells <= outerCells
                    && distance + halfBodyCells >= innerCells;
        }
    }

    // ---- 大炮（BuildingType.TOWER）：下标 = 级数 - 1 ----
    //
    // 2026-09-27 改成**远距离曲射**：一门射程二十格、但有十格盲区的炮。
    //
    //   内径 10 格、外径 20 格、半角 30°（= 整圈的 1/6）、溅射 1 格
    //
    // **不是"射程变长了"，是换了一种武器。** 从前的塔是 2.5 格贴脸直射，
    // 现在是一门摆在后方、只打远处一片走廊的炮：十格以内它一枪不放，
    // 贴到脸上的敌人它打不着（也正因为如此，它不该摆在前排）。
    // 换来的是二十格——地图上从可建区右头能打到通道口。
    //
    // 半角原本是 15°（一条更窄的走廊），同一天改成 30°：二十格处张开的半宽
    // 从 20×tan15° ≈ 5.4 格变成 20×tan30° ≈ 11.5 格，**一格宽的门变成一扇**。
    // 变宽之后它管的还是"一条走廊"，但那已经是通道那头的整段宽度了——
    // 摆位从"对准哪一条缝"退回"摆在这一侧就行"。这是拿"好摆"换"好瞄准"，
    // 换来换去都是这门炮的定位（远程覆盖，不是点名）。
    //
    // **三档同一个射界**：升级不改射程（也不改张角、不改溅射），
    // 升级只买到伤害和耐久。所以这个常量不在任何按级排的表里——它就没有"哪一级"
    // 这回事。想改回"升级加射程"的话，把它改成一张表，别在这儿加 if。

    /** 大炮的内径（格）。十格以内是盲区，见上面那段。 */
    public static final double CANNON_INNER_CELLS = 10.0;

    /** 大炮的外径（格）。 */
    public static final double CANNON_OUTER_CELLS = 20.0;

    /**
     * 大炮的半角（度）。<b>30° 是"整圈的 1/6"</b>：上下各 30° 就是 60° 的扇面，
     * 60 ÷ 360 = 1/6。
     *
     * <p>这个数是"往哪边摆"这件事的分量：十五度的时候炮口指的那条线差出去
     * 半行，二十格外的落点就偏出去两格多，摆位得对准某一条走廊；三十度把那个
     * 容差放大到四格多，于是"摆在这一侧"就够了，不用再对线。
     *
     * <p><b>再宽就变味了。</b>六十度（正负三十）是"一个方向"仍然读得出来的上限
     * ——画在场上还是一个朝左的喇叭口。到了九十度它就是半个平面，
     * 那时"塔朝左"这个设定只剩一条 dx &lt; 0 的判定，画面上看不出朝向，
     * 摆位也就没有意义了。所以 {@link FireArc} 的构造器把 90° 卡成硬上限。
     */
    public static final double CANNON_HALF_ANGLE_DEG = 30.0;

    /**
     * 大炮的溅射半径（格）：<b>落点一格之内的敌人一起吃这一发的伤害</b>。
     *
     * <p>和"打中的那一只"用的是同一个数（{@link #damagePerShot}），不衰减——
     * 一门二十格外的炮，如果还分"正中"和"擦边"，玩家在屏幕上看不出落点偏了多少，
     * 只会觉得"这一发怎么没伤害"。所以规则做得能一眼说清：
     * <b>炮弹落在谁脚边一格以内，谁就挨一整发</b>。
     *
     * <p>判定同样"碰到就算"（身子探进那一个格圆里就吃），和射界那两条边一个规矩。
     */
    public static final float CANNON_SPLASH_CELLS = 1.0f;

    /** 大炮的射界。<b>不随等级变</b>，所以只有一个。 */
    private static final FireArc CANNON_ARC = new FireArc(
            CANNON_INNER_CELLS, CANNON_OUTER_CELLS,
            CANNON_HALF_ANGLE_DEG, CANNON_SPLASH_CELLS);

    /**
     * 每一发打掉多少血。
     *
     * <p>和 {@link #TOWER_FIRE_INTERVAL} <b>两个数一起才是火力</b>：除一下就是每秒伤害
     * （24÷1.6 = 15.0、31÷1.5 = 20.7、38÷1.35 = 28.1）。拆成两个是因为塔现在打的是子弹
     * （见 {@link Projectile}），能调的就不只是"多疼"、还有"多密"。
     *
     * <p><b>2026-09-27 把这两个数一起挪了，而且方向相反。</b>原来是 14/18/21 点、
     * 1.0/0.9/0.75 秒。改成一发重一倍半、节奏慢六成（见
     * {@link #TOWER_FIRE_INTERVAL}），而<b>每秒伤害几乎没动</b>——这是有意的：
     * 同一天张角从 30° 变成 60°（{@link #CANNON_HALF_ANGLE_DEG}），
     * 覆盖面积白白翻了一倍。再把 DPS 一起抬上去，这门炮就变成"又远又宽又疼"，
     * 弩车和墙都没有存在的理由了。所以这次换的只有手感：
     * <b>更少、更重、更看得清</b>，账面上还是那门炮。
     *
     * <p>新数字和 {@link EnemyType} 之间的关系，一条条写在这儿，改任何一边都要回来看：
     *
     * <ul>
     *   <li>一级 24 点，<b>打不死快兵</b>（28 血，还剩 4 点）——"来不及打"这件事还在；</li>
     *   <li>二级 31 点<b>刚好一发带走快兵</b>（这就是升级买到的东西）；</li>
     *   <li>三级 38 点也还轻于弩车<b>任何一级</b>（34/45/58），
     *       "弩车单发更重"这条分工没被吃掉。</li>
     * </ul>
     *
     * <p>射界是 {@link #CANNON_ARC} 之后，"在射界里待多久"变成一个和摆位强相关的量。
     * 只算一条：敌人<b>正对着炮口那条线</b>走过来，十格深的走廊以杂兵的 0.9 格/秒
     * 要走 11 秒，一级炮 15 dps 打满约 165 点——<b>够打死杂兵（45）三回</b>，
     * 重甲（160）也扛不住一次全程正对。偏离轴线越远待得越短，但在六十度的张角下
     * 这个"越远"要偏出去十一格（二十格处张角的半宽是 20×tan30° ≈ 11.5 格）
     * 才开始打折——也就是说<b>通道那一头几乎没有死角了</b>。
     *
     * <p><b>所以这几个数还是待配平的，不是配平过的</b>，而且这次比上次更甚：
     * 张角翻倍之后"一门炮守住一条走廊"已经接近"守住整条通道"。
     * 要动它们之前先跑 {@code DemoSceneProbeTest}（把 {@code @Ignore} 摘掉），
     * 那是这个仓库里唯一能量"守住几成"的仪器。
     *
     * <p><b>实际打出来的伤害比上面算的低</b>，两个原因，都是有意的：
     * ① 子弹要飞一会儿才到（{@link #PROJECTILE_SPEED_CELLS_PER_SEC}），
     * "打了"和"掉血"之间隔着一个看得见的过程；② 目标可能在半路被别的塔打死——
     * 大炮这一发照样会炸（见 {@link #CANNON_SPLASH_CELLS}），但其余塔已经在天上的
     * 那几发就打空了，所以"好几座塔一起糊一只"仍然不是无损的最优解。
     *
     * <p><b>改动其中一个就得回头看另一个</b>，所以 {@code BattlefieldTest} 里有测试
     * 专门盯着"射界换了几何之后，进得来和进不来的那几只分别是谁"。
     */
    private static final double[] TOWER_DAMAGE_PER_SHOT = {24.0, 31.0, 38.0};

    /**
     * 两发之间隔几秒。下标 = 级数 - 1，和 {@link #TOWER_DAMAGE_PER_SHOT} 一一对应。
     *
     * <p>三档都取"一秒半上下"是有原因的：这个数同时是<b>画面上能看见的节奏</b>。
     * 装填要长得数得出来——"再来一发就死"这句话得让玩家说得出口；
     * 配成 0.15 秒一发的话，屏幕上会变成一条连续的虚线，
     * 那和加弹道之前那条照射的连线就没区别了。
     *
     * <p><b>原来是 1.0/0.9/0.75，慢下来的理由在
     * {@link #TOWER_DAMAGE_PER_SHOT} 那段</b>：单发变重之后，节奏必须跟着变慢，
     * 不然每秒伤害就跟着涨，而张角那笔账（翻倍）已经先花掉了。
     *
     * <p><b>慢射速有个别的塔没有的副作用：浪费少。</b>大炮射程二十格，炮弹要飞两秒多
     * （{@link #PROJECTILE_SPEED_CELLS_PER_SEC}），天上常驻着上一发；装填越慢、
     * 同时在天上的越少，目标被别的塔抢先打死时打空的那一发也越少。
     */
    private static final double[] TOWER_FIRE_INTERVAL = {1.6, 1.5, 1.35};

    /**
     * 子弹飞多快，格/秒。所有塔、所有等级一样。
     *
     * <p>9 格/秒是"看得见在飞、但不用等"的那一档：弩车最远的射程（4.0 格）从出膛到
     * 命中大约 0.44 秒，二十几帧。再快就等于瞬移（又变回一条连线），
     * 再慢玩家会觉得"这座塔反应好迟钝"。
     *
     * <p><b>大炮那二十格是个例外，而且没为它调过。</b>从外沿（20 格）打进来要飞
     * 20 ÷ 9 ≈ 2.2 秒，比它一级的装填间隔（1.6 秒）还长——也就是说总有一发在
     * 天上，而炮弹认的是"出膛那一刻的目标在哪"，命中比开火晚一拍多。
     * 这不是错（"打了"和"掉血"之间本来就该隔着一个看得见的过程），
     * 但它是"大炮看起来反应慢"的唯一来路。装填刚刚从 1.0 秒放慢到 1.6 秒，
     * 天上常驻的发数从两三发降到一发出头，这一拍反而更清楚了。
     * 真嫌慢的话该调的是这门炮的出膛速度，不是这个全局常数——调了会把弩车一起改掉。
     *
     * <p><b>必须比敌人快得多</b>：最快的快兵 1.6 格/秒，子弹比它快五倍多，
     * 所以追得上。追不上就会出现"子弹吊在敌人屁股后面飞、伤害永远不落地"——
     * 那是 {@link Projectile} 这套"追着打"的模型唯一会崩掉的方式。
     */
    public static final float PROJECTILE_SPEED_CELLS_PER_SEC = 9.0f;

    /**
     * 塔的耐久。比墙薄——它不该同时又是输出又是沙包。
     *
     * <p><b>升级涨血涨得比墙少</b>（+30%/+23% 对墙的 +47%/+45%）：大炮升级买的是
     * 火力（<b>射界不跟着涨</b>，见 {@link #CANNON_ARC}），血是捎带的。要是升级同时
     * 又变得很耐揍，"塔和墙"这对分工就糊了——玩家会发现铺一座高级塔比"塔 + 墙"划算，
     * 墙就没人砌了。
     *
     * <p>而且大炮<b>没法自卫</b>（十格盲区）：它挨打的时候只能指望旁边的弩车和墙，
     * 所以那点耐久是真的只是"多撑几秒等别人来救"。
     */
    private static final float[] TOWER_HP = {100f, 130f, 160f};

    // ---- 弩车：下标 = 级数 - 1 ----
    //
    // 和箭塔（大炮）是**一对反着来的数**，写在两张表里是为了让这个对比一眼看得见：
    //
    //            射界                      单发      间隔           DPS
    //   大炮   10~20 格，±30°，溅射 1 格   24/31/38   1.6/1.5/1.35   15.0/20.7/28.1
    //   弩车   0~3.0/3.5/4.0 格，±60°      34/45/58   2.6/2.4/2.2    13.1/18.8/26.4
    //
    // **两种塔的分工是"远近"，不是"高低"。** 大炮打二十格、但十格以内是哑的；
    // 弩车只打四格，但那四格以内没有盲区，谁来都打得着。所以它们不是替代关系：
    // 大炮管走廊，弩车管门口，中间那段（大炮的十格盲区、弩车的四格之外）
    // 得靠摆位把两者的射界接起来。
    //
    // **弩车单发更重、DPS 更低**，换来的是"贴脸也能打"和"一发一个"。
    // 这个交换在玩法上是成立的，不是凑数：大炮是"等它走进走廊再打"，
    // 弩车是"一发一个"——一级弩车 34 点一发带走快兵（28 血），二级 45 点一发
    // 带走杂兵（45 血）；而一级大炮 24 点打这两样都要两发。
    // **大炮的单发在 2026-09-27 提过一次**（14 → 24），所以那条差距从"一倍半"
    // 缩到"一级 34 对 24"——**弩车仍然档档更重，但没有从前那么悬殊了**，
    // 这条分工靠的是 DPS 那半边（弩车低）撑着，见 {@code CostTest} 里那条
    // 逐级比两张表的测试。慢射速还有个副作用是**浪费少**：
    // 大炮天上常驻一发、目标死了就打空那一发，弩车两秒才一发，落空的少。
    //
    // 代价写在价格上（{@link ShopCatalog}）。**这两个价格是弩车更贵**
    // （45 对 25），而那是"两种塔都是 2.5~3.5 格直射"那个版本的账——
    // 大炮换成二十格扇环之后这笔账没重算过，见下面 {@link #BALLISTA_RANGE}
    // 那段的注。

    /**
     * 弩车的射程（外径）。
     *
     * <p><b>"比箭塔远半格"这句话在 2026-09-27 之后不成立了</b>（大炮现在是二十格），
     * 留在这里是因为它记着这三个数为什么是 3.0/3.5/4.0：当初弩车和大炮都是贴脸直射，
     * 它靠这半格能摆在更后面。现在它和大炮比的是<b>有没有盲区</b>，不是远近。
     */
    private static final double[] BALLISTA_RANGE = {3.0, 3.5, 4.0};

    /**
     * 弩车的半角（度）。宽（正负六十度，几乎是整个左半边），和它贴脸直射的定位配套：
     * 它要能打到门口任何一个方向来的敌人，所以张角不能窄。
     */
    private static final double BALLISTA_HALF_ANGLE_DEG = 60.0;

    /**
     * 弩车三个等级的射界。<b>内径恒为 0</b>（贴脸也打，这是它和大炮的根本区别）、
     * <b>没有溅射</b>（一根弩箭，打中谁是谁），只有外径跟着等级涨。
     *
     * <p>从 {@link #BALLISTA_RANGE} 推出来而不是再写一张表：同一个数字写在两处，
     * 改一处漏一处就会变成"画出来的圈"和"打得到的范围"不一样，而那种不一致
     * 在画面上完全看不出来。所以这里只允许有一处真值。
     */
    private static final FireArc[] BALLISTA_ARCS;

    static {
        BALLISTA_ARCS = new FireArc[BALLISTA_RANGE.length];
        for (int i = 0; i < BALLISTA_ARCS.length; i++) {
            BALLISTA_ARCS[i] = new FireArc(
                    0.0, BALLISTA_RANGE[i], BALLISTA_HALF_ANGLE_DEG, 0f);
        }
    }

    /** 弩车的单发伤害。三级 58 两发带走重甲（160 血）。 */
    private static final double[] BALLISTA_DAMAGE_PER_SHOT = {34.0, 45.0, 58.0};

    /**
     * 弩车两发之间隔几秒，比箭塔长一倍多——这是"重弩"这个手感本身。
     *
     * <p>三档都在两秒上下：上弦的时间要长得<b>看得见</b>，不然"一发重"这件事
     * 在屏幕上读不出来（玩家只会觉得"这座塔打得慢"）。
     */
    private static final double[] BALLISTA_FIRE_INTERVAL = {2.6, 2.4, 2.2};

    /**
     * 弩车的耐久。比箭塔还薄一档。
     *
     * <p>理由和塔比墙薄是同一个（见 {@link #TOWER_HP}），只是更极端：
     * 弩车射程远，本来就该摆在箭塔后面，挨打的机会更少；真被摸到了，
     * 掉得比箭塔快才对——不然"射程远"就成了纯粹的白拿。
     */
    private static final float[] BALLISTA_HP = {80f, 105f, 130f};

    /**
     * 墙的耐久。下标 = 级数 - 1。
     *
     * <p>比塔厚（150 对 100）而且便宜得多（5 个 INPUT 对 25 CACHE + 10 OUTPUT），
     * 因为墙<b>只会站着挨打</b>：一面墙的全部价值就是替后面的塔多争取几秒。
     * 一只杂兵 12 dps 要啃 12.5 秒，够一座塔打死它两三回了——前提是那面墙
     * 在塔的火力范围里，不在的话墙就只是延迟一下。
     *
     * <p><b>墙升级是纯买时间</b>，所以涨幅给得大：一面墙升到 2 级多挨 5.8 秒、
     * 3 级再多挨 8.3 秒，而价格只是 INPUT（最富裕的那种资源，见 {@link ShopCatalog}）。
     * 这是"资源没处花"时最顺手的出口——也正因为如此，涨价涨得比涨血快
     * （5 → 6 → 12），不然铺满三级墙会变成唯一解。
     */
    private static final float[] WALL_HP = {150f, 220f, 320f};

    /**
     * 核心的耐久。下标 = 级数 - 1。
     *
     * <p>600 点是个"来得及反应"的数：一只杂兵 12 dps 要啃 50 秒，
     * 但五只一起啃就是 8 秒——所以漏了怪是有救的（塔会把站在核心跟前啃的
     * 那些打死，它们是停着不动的活靶子），漏了一群就直接结束。
     * <b>这个数决定"漏怪"这件事有多可怕</b>，调它之前先想清楚要哪种紧张感。
     *
     * <p><b>核心升级是和塔抢钱</b>（都吃 CACHE + OUTPUT，见 {@link #upgradeCost}）：
     * 加厚核心是"承认自己会漏"，再摆一座塔是"争取不漏"。这个取舍是故意的——
     * 如果核心升级不要 OUTPUT，玩家就没有理由不去点它。
     */
    private static final float[] CORE_HP = {600f, 900f, 1300f};

    /**
     * 墙在寻路表里算多贵的一格。
     *
     * <p><b>比整块战场所有格子加起来还大</b>（{@code 80×36 = 2880}）：这样
     * "绕多远都比拆近"，墙于是成了<b>漏斗</b>——敌人绕着走，玩家砌墙是为了
     * 把敌人赶到塔底下，而不是为了挡死。只有真的封死、无路可绕时，表上的箭头
     * 才会指进墙里，敌人才动手拆。
     *
     * <p><b>2026-09-27 战场翻倍之后，这里的余量只剩 1.4 倍</b>（原来 720 对 4096
     * 是 5.7 倍）。绕路最贵就是"把整块战场走一遍"，2880 格 × 每格至少 1 = 2880，
     * 还没顶到 4096，所以规矩没破——但**战场再大一倍就不够了**。
     * 真到那天要么涨这个数，要么把它改成按 {@code cols()*rows()} 算出来的。
     *
     * <p><b>塔和核心不需要这个数</b>，因为它们是寻路表的<b>目标</b>
     * （{@code Battlefield.isTarget}）：目标那几格的价钱恒为 0，踩进去的价
     * 根本不参与计算。所以这里是"墙专属"的一个数，不再是一张按类型分的表
     * ——那版里给塔的那一档（曾经是 20、后来是 1）是<b>死代码</b>：
     * 塔进了目标集合之后，那个值没有任何一处读得到。
     *
     * <p>和 {@code nearestBite} 那条"墙是例外"是配套的，不是重复：那条管的是
     * "敌人停下来之后咬谁"，这条管的是"敌人会不会走到需要停下来的地方"。
     * 两个都要对上，才会出现"绕着墙走、冲着塔去"。
     */
    public static final int WALL_PATH_COST = 4096;

    private BuildingStats() {
    }

    /**
     * 这一级满血多少。<b>每种建筑都走这张表</b>（墙和核心以前是一个定值，
     * 现在也按级涨了——见 {@link #WALL_HP}／{@link #CORE_HP}）。
     *
     * <p>等级越界按最近的一级算，理由同 {@link #fireArc}。
     */
    public static float maxHp(BuildingType type, int level) {
        switch (type) {
            case CORE:
                return CORE_HP[clamp(level) - 1];
            case WALL:
                return WALL_HP[clamp(level) - 1];
            case BALLISTA:
                return BALLISTA_HP[clamp(level) - 1];
            default:
                return TOWER_HP[clamp(level) - 1];
        }
    }

    /**
     * 这一级每一发打掉多少血。<b>0 = 不会攻击</b>。
     *
     * <p>这是塔真正"配"的那个数：{@code Battlefield} 出膛时把它抄进
     * {@link Projectile#damage}，之后就算这座塔被拆了，天上的那几发也还是这个伤害。
     */
    public static double damagePerShot(BuildingType type, int level) {
        if (!hasRange(type)) {
            return 0.0;
        }
        return (type == BuildingType.BALLISTA ? BALLISTA_DAMAGE_PER_SHOT : TOWER_DAMAGE_PER_SHOT)
                [clamp(level) - 1];
    }

    /**
     * 这一级两发之间隔几秒。<b>0 = 不会攻击</b>。
     *
     * <p>和 {@link #damagePerShot} 一起被 {@code Battlefield} 用：
     * 装填计时器每帧减 {@code dt}，减到 0 以下就吐一发、再把间隔加回去。
     *
     * <p>等级越界按最近的一级算，理由见 {@link #fireArc}。
     */
    public static double fireIntervalSeconds(BuildingType type, int level) {
        if (!hasRange(type)) {
            return 0.0;
        }
        return (type == BuildingType.BALLISTA ? BALLISTA_FIRE_INTERVAL : TOWER_FIRE_INTERVAL)
                [clamp(level) - 1];
    }

    /**
     * 这种建筑这一级的<b>射界</b>；不会攻击的返回 {@code null}。
     *
     * <p><b>取射界只有这一个入口</b>，理由见 {@link FireArc} 的类注释：内径、外径、
     * 张角、溅射是一个整体的四个侧面，分散着取就一定会有人漏取一个。
     * 所以旧版那个 {@code rangeCells}（只给一个外径）<b>删掉了</b>——
     * 留着它就会有地方拿它当"打得到几格远"用，而那对大炮是错的。
     *
     * <p>单位是<b>格</b>不是像素——格子大小随屏幕和缩放变，格数不变，
     * 绘图那边乘 {@code BoardGeometry.cellPx()}。这个数同时是画面上那个扇环的半径，
     * 所以它和"打不打得到"必须对得上：两边都走 {@link FireArc#catches}。
     *
     * <p><b>大炮的射界不随等级变</b>（{@link #CANNON_ARC} 只有一个，升级不改射程），
     * 弩车的随等级变（{@link #BALLISTA_ARCS}）。
     *
     * <p>等级越界（0、负数、超过 {@link #MAX_LEVEL}）按最近的一级算，不抛异常：
     * 这个方法在每一帧的绘制路径上，为一个不该出现的等级把整局搞崩不值得。
     * 大炮那一支干脆不读等级，所以越界对它连影响都没有。
     */
    public static FireArc fireArc(BuildingType type, int level) {
        switch (type) {
            case TOWER:
                return CANNON_ARC;
            case BALLISTA:
                return BALLISTA_ARCS[clamp(level) - 1];
            default:
                return null;   // 墙和核心不攻击
        }
    }

    /**
     * 这种建筑会不会攻击。决定详情面板里画不画那个射界。
     *
     * <p><b>两种塔都会攻击，但射界完全不同</b>（{@link #CANNON_ARC} 与
     * {@link #BALLISTA_ARCS}）——引擎里没有任何"朝向"状态，"多一种塔"在这条判定上
     * 只多一个分支；张角、内径这些差别全在 {@link #fireArc} 里，判定那边一行不用改。
     */
    public static boolean hasRange(BuildingType type) {
        return type == BuildingType.TOWER || type == BuildingType.BALLISTA;
    }

    /**
     * 这一级还能不能再升。
     *
     * <p>和 {@link #upgradeCost} 是同一件事的两种问法：这里只想知道"按钮该不该亮"，
     * 那里想知道"要多少钱"。判断只有一处，两者不会说岔。
     */
    public static boolean canUpgrade(BuildingType type, int level) {
        return upgradeCost(type, level) != null;
    }

    /**
     * 从这一级升到下一级要花多少；到顶了返回 {@code null}。
     *
     * <p><b>每种建筑都能升</b>，因为每种都有"升级换来什么"：两种塔换火力射程、
     * 墙换耐久、核心换耐久（见 {@link #maxHp}）。所以这个方法的 {@code null}
     * 现在只有一个意思——<b>到顶了</b>。界面上因此只需要一个"fully upgraded"
     * 的说法，不用再解释"这种建筑这个版本还不能升"。
     *
     * <p><b>返回 {@code null} 而不是 {@link Cost#FREE}</b>：免费是"能升，不要钱"，
     * 这里是"根本没有这一档"。混成一个值的话，满级建筑的升级按钮会亮着、
     * 点下去不要钱也不升级。
     *
     * <p><b>吃哪种资源是跟着建造价走的</b>（{@link ShopCatalog} 里那套分工）：
     * 塔吃 CACHE + OUTPUT，所以塔升级也吃这两样；墙吃 INPUT，所以墙升级只吃 INPUT。
     * 三种资源互不通兑，所以"这座建筑吃哪种"必须前后一致——不然会出现
     * "造得起、升不起"这种说不清的局面。
     *
     * <p>数值来路：新建一座塔是 CACHE 25 + OUTPUT 10。升级比新建便宜一点
     * （省下的是"地盘"），但第二级涨幅明显——不然铺满一级塔再慢慢升就成了唯一解。
     * 核心升级<b>故意和塔抢同一种资源</b>，理由见 {@link #CORE_HP}。
     * <b>这几个数都是待调的</b>，真机玩两局再改。
     */
    public static Cost upgradeCost(BuildingType type, int level) {
        if (level < 1 || level >= MAX_LEVEL) {
            return null;
        }
        boolean firstStep = level == 1;
        switch (type) {
            case WALL:
                // 只吃 INPUT：和建造价同一种资源，理由见上面那段
                return firstStep
                        ? Cost.of(ResourceType.INPUT, 6)
                        : Cost.of(ResourceType.INPUT, 12);
            case CORE:
                // 和塔抢 CACHE + OUTPUT，但比塔贵：加厚核心是"承认自己会漏"
                return firstStep
                        ? Cost.of(ResourceType.CACHE, 30).plus(ResourceType.OUTPUT, 12)
                        : Cost.of(ResourceType.CACHE, 50).plus(ResourceType.OUTPUT, 20);
            case BALLISTA:
                // 吃和建造价同一种资源（CACHE + OUTPUT），只是贵一档——
                // 建造价贵一倍，升级价也只贵不便宜，不然"先铺便宜的箭塔再升"就绕过了弩车的定价
                return firstStep
                        ? Cost.of(ResourceType.CACHE, 36).plus(ResourceType.OUTPUT, 14)
                        : Cost.of(ResourceType.CACHE, 62).plus(ResourceType.OUTPUT, 24);
            default:
                return firstStep
                        ? Cost.of(ResourceType.CACHE, 20).plus(ResourceType.OUTPUT, 8)
                        : Cost.of(ResourceType.CACHE, 35).plus(ResourceType.OUTPUT, 15);
        }
    }

    private static int clamp(int level) {
        if (level < 1) {
            return 1;
        }
        return Math.min(level, MAX_LEVEL);
    }
}
