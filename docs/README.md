# TokenTrail 文档索引

这个文件夹放**所有文字信息**：接口契约、分工、架构说明、交接须知。
写代码的时候要查的东西，先来这里。

| 文档 | 讲什么 | 什么时候看 |
| --- | --- | --- |
| [`CONTRACTS.md`](CONTRACTS.md) | 接口契约：命名口径、时间口径、存储、结算规则、三种资源、TBD 清单 | **动手前先看一遍**，改字段前再查一次 |
| [`FORUM_API.md`](FORUM_API.md) | 论坛 UI、后端接口、账号接入与英文 RSS 采集 | 论坛联调与后端实现之前 |
| [`TASKS.md`](TASKS.md) | 三个人的待办、验收标准、依赖谁 | 认领任务、判断「做完了没」 |
| [`ART.md`](ART.md) | 游戏贴图的规格：视角、光源、尺寸、命名、抠图流程、来源留痕 | **动手画图之前先看** |
| [`OPEN_SOURCE.md`](OPEN_SOURCE.md) | 第三方库的清单：许可证、出处、谁在用、怎么加新库 | **加依赖之前先看**，写报告的开源节时照抄 |
| [`DATA_SOURCES.md`](DATA_SOURCES.md) | 用量从哪来：三家 provider 各自要用户填什么、我们能拉到什么、官方还是私有接口 | **写 fetcher / 做填凭据界面之前先看** |
| 本文 | 架构、依赖方向、桩数据开关、怎么构建 | 刚接手时 |

交给老师的成品也在这里（英文，**不返工**）：

| 文件 | 说明 |
| --- | --- |
| `TokenTrail_Project_Outline.tex` / `.pdf` | 大纲源文件与成品，4 页 |
| `TokenTrail_Project_Outline_Slides.pptx` / `.pdf` | 幻灯片成品，15 页 |
| `outline-slides-src/` | 幻灯片的生成脚本与插图源码，见其中的 `README.md` |

两者都从**本文件夹**里构建（路径写死了相对位置，别单独挪走其中一个）：

```bash
# 大纲：跑两遍让 hyperref 的目录/链接对上
cd docs && PATH="/Library/TeX/texbin:$PATH" xelatex -interaction=nonstopmode TokenTrail_Project_Outline.tex
# 幻灯片：脚本自己找同目录的 fig_*.png，输出到上一级（就是本文件夹）
cd docs/outline-slides-src && python3 build_deck.py
```

**字体是按机器自动选的**，`.tex` 顶部有一条 `\IfFileExists` / `\IfFontExistsTF`
链，依次尝：微软雅黑（`msyh.ttc`，装了 Microsoft Word 才有）→ 微软雅黑（按名字，
Windows）→ `Helvetica Neue`（macOS）→ `Arial` → 留空用 LaTeX 默认。

**所以同一份 `.tex` 在两台机器上编出来字体不一样，页数都是 4 页。**
仓库里那份 PDF 是在这台 Mac 上编的（`Helvetica Neue`）；谁在 Windows 上编会得到
微软雅黑那版。两份都是对的，**别拿字体不一致当 bug 查**。

**改大纲时容易踩的三个坑**（都踩过）：

- 字体链**兜底那一支不能指回同一个字体**。上一版写成「找不到 `msyh.ttc` 就用
  `\setmainfont{Microsoft YaHei}`」——那是同一个字体，Mac 上照样没有，fontspec
  直接报错。**而且它照样输出 4 页、照样 BUILD SUCCESSFUL**，只是在 log 里留一句
  `LaTeX Font Warning`，正文全变成默认字体。坏的 PDF 就这么发出去了。
- 兜底**不要**写 `\setmainfont{Latin Modern Roman}`——fontspec 不把它当系统字体解析，
  编译不报错，但整篇静默丢掉所有字形（两万多个缺字）。宁可留空。
- `Helvetica Neue` **没有 `→` 这个字形**，会渲染成空框。箭头一律写
  `$\rightarrow$`，让它从数学字体里取（现在是 0 个缺字，说明写法是对的）。

验证：编译完看 log，**`Overfull` / `Missing character` / `cannot be found` 三个都应该是 0**
（`grep -c` 一下最快），末尾有 `Output written on ... (4 pages)`。
页数上限是 4 页，加内容必须同时删内容。字体宽度不同会让某一行从「正好放下」
变成「溢出 17pt」，`\emergencystretch=3em` 就是为这个留的余量——它只在本来要溢出的
行上生效，不改别的地方。

---

## 1. 代码在哪

```
app/src/main/java/com/mobilegroup20/tokentrail/
├── contract/          接口契约。纯 Java，不 import 任何 android.* —— 三人共担，改动要打招呼
│   ├── model/         数据类：UsageCall、DailyUsage、PricingRate、SeasonState、ForumPost …
│   └── tool/          agent 的五个只读工具：入参和返回值
├── data/
│   ├── repository/    ★ 五个接口。屏幕和逻辑只认这些，不认实现
│   ├── stub/          桩实现（编好的假数据），真实现没写完时顶上
│   ├── local/         张莉：Room
│   ├── remote/        张莉（用量）、汪庭栋（论坛）：服务端 HTTP 接口
│   ├── importer/      张莉：日志解析
│   └── RepositoryProvider.java   ★ 全项目拿 Repository 的唯一入口
├── game/
│   ├── engine/        刘宗润：塔防逻辑与相机。不 import 任何 android.*，能脱离手机跑单元测试。
│   │                  `Cost` 是全项目唯一的"一笔开销"（货架价和升级价共用），
│   │                  `BuildingStats` 管每级的数值，`ShopCatalog` 只管货架价
│   └── view/          刘宗润：把 engine 画到屏幕上（战场 + 整页的岩石底）
├── agent/             刘宗润：建议服务的客户端与工具分发
├── ui/                game / dashboard / forum / auth / common —— 三人共担
└── util/                TimeUtils：时区与「天」的换算，唯一出处
                         TokenFormat：token 怎么写短、以及"屏幕上的数字要能相加"
```

每个包都有一份 `package-info.java`，写着**这个包归谁、里面该放什么**。
新加类之前先看那一条。

## 2. 依赖方向只有一个

这句话的意思是：**箭头是单向的，环不起来。**

```
  ui/  ──►  RepositoryProvider  ──►  data/repository/ 的接口  ──►  实现类
  game/view/                                                      (local/ remote/ stub/)
  agent/
        ◄──────────── 只往回传 contract/model 里的数据类 ────────────
```

三条规矩：

1. **界面只认接口。** 写 `UsageRepository r = RepositoryProvider.usage();`，
   绝不写 `new RoomUsageRepository(...)`。也不要在界面里 import
   `data.local.*` 或别人的实现类。
2. **实现类不许反向 import 界面。** 谁写了 `import ...ui.` 谁就破了这个方向。
3. **共享的只有 `contract/`。** 两个人之间要传数据，就把数据类放进 `contract/model/`，
   不要互相 import 对方包里的类。

**为什么要这么严。** 三个人的模块要能同时开工。如果界面直接 new 实现，那么
「张莉的实现还没写完」就等于「游戏界面编译不过」——三个人就得排队。有了这层接口，
游戏侧今天就能对着桩数据把玩法做完，等真实现接上，界面一行都不用改。

### 想验证有没有写歪

```bash
# 应该只列出 data/stub 和 data/RepositoryProvider
grep -rn "import com.mobilegroup20.tokentrail.data.local" app/src/main/java/com/mobilegroup20/tokentrail/ui/

# 应该什么都不输出（实现类不许反向依赖界面）
grep -rn "import com.mobilegroup20.tokentrail.ui" app/src/main/java/com/mobilegroup20/tokentrail/data/

# 应该什么都不输出（engine 必须能脱离 Android 跑测试）
grep -rn "import android\." app/src/main/java/com/mobilegroup20/tokentrail/game/engine/
```

## 3. 桩数据与 `USE_STUBS` 开关

`data/stub/` 下面的三个类是**编好的假数据**，不是空的占位符：三家的模型、
每一天的用量、像样的论坛帖子和回复、一个月度上限 20 美元 / 已花 13 美元的预算。
数字是按固定公式算出来的，同一天跑两次结果一样（用随机数的话界面每次刷新都在跳，
分不清是自己写错了还是数据在变），方便截图和录屏。

开关在 `data/RepositoryProvider.java` 第 33 行：

```java
public static final boolean USE_STUBS = true;
```

- **`true`（现在）**：用量与预算仍使用桩数据；论坛独立使用真实后端。
- **`false`（交之前）**：改用真实现。**这时候要顺手把方法体里的 `new XxxImpl(...)`
  填上**，否则会抛 `UnsupportedOperationException`，报错信息里写了该去写哪个类。

改这一个布尔值，全 App 的界面都不用动——这就是上面那条「只认接口」换来的。

**提交之前必须改成 `false`**，否则演示时屏幕上全是编出来的数字。

**论坛是例外**：新论坛与 agent 论坛接口已经统一走 HttpForumRepository，不受 USE_STUBS 控制；TeamAccountSession 已接入现有服务器账号，论坛和“我的”均有登录入口。未配置后端时显示准备中，不返回假帖子。

桩数据里 `importCalls()` 会如实报告「全部被拒」（`rejected` 等于传入条数）：
桩没有真的存进去。这样谁误以为导入已经能用了，会立刻发现。

## 4. 构建与测试

```bash
./gradlew assembleDebug            # 编译，出 APK
./gradlew testDebugUnitTest        # 跑单元测试（现在 191 个，全过）
./gradlew connectedDebugAndroidTest # 需要连真机/模拟器，目前只有模板测试
```

构建报 `Connection refused` 之类的网络错时，先 `./gradlew --stop` 再重试：
这台机器上代理软件退出后，老 Gradle 守护进程还会留着旧代理设置。

**离线也能跑。** `contract/` 和 `game/engine/` 都是纯 Java，不需要模拟器：

```bash
./gradlew testDebugUnitTest --tests '*ContractMathTest*'
```

## 5. 现在到哪一步了

| 部分 | 状态 |
| --- | --- |
| 接口契约（`contract/`） | **完成**，字段定稿，12 个单元测试盯着计价和结算口径 |
| 战场几何与相机（`game/engine/`） | **完成**：`BoardGeometry` 算格子和像素的换算、占地判定（24 个测试），`Viewport` 管缩放拖动和取景（23 个测试），`Battlefield` 管占位、地形分区（通道/可放置/山区）、挪动（核心和任意建筑）和整场战斗（推进、开火、挡路、清场、胜负判定） |
| 战斗（`game/engine/Battlefield` + `PathField` + `Waves` + `EnemyType`） | **能在真机上跑**：五波敌人、三种兵、塔打子弹（`Projectile`：每发多少 + 几秒一发，追着目标飞）、敌人自己找路、敌人啃建筑、核心被打掉就结束、漏怪计数、结果卡片。挑目标：塔打**最靠前**的那只（按寻路表算，不按 x），敌人去**最近的建筑**、咬**挨着最近**的那座——**塔和核心优先级相同**，核心不是天然终点。**墙是唯一的例外**：寻路表上只有墙贵（贵过任何绕路），所以敌人绕着墙走、只有封死了才拆；塔和核心是表上的目标、不参与价钱。真机验过：发波、行走、被墙挡住、墙被拆掉、子弹在飞（真机连拍 12 帧抓到 3 帧）、血条、`Core 600/600 · N leaked`。**还没做的**：敌人之间不互相挡（汇到缺口会叠成一坨）、没有音效、**演示场面的配平**（敌人现在会主动拆塔，20 个种子里 0 个守得住、六座塔全被打光，见 [`TASKS.md`](TASKS.md) 那节） |
| 战场纯色块预览（`game/view/`） | **能在真机上跑**：40×18 的战场，三区地形分色、摆放、挪核心、缩放、横向拖动、发波、战斗（子弹画成白点）、建筑详情都通了；贴图待画（换真贴图只动 `drawBlock` / `drawEnemy` / `drawProjectiles` 三处） |
| 商店与资源（`game/engine/ShopCatalog`） | **能在真机上跑**：箭塔 / 城墙 / 核心三件货，塔吃 CACHE + OUTPUT、墙吃 INPUT、核心免费（只挪）；买不起的落子会拦下并提示。**钱包是月度快照的临时值**，真余额等 `SeasonRepository` |
| 建筑详情与升级（`game/engine/BuildingStats`） | **能在真机上跑**：点建筑只看不放，弹出等级 / 占地 / **耐久** / 攻击范围 / 升级价 / **Move**；**三种建筑都能升到 3 级**——塔升火力（范围 2.5 → 3.0 → 3.5 格），墙和核心升耐久（墙 150/220/320、核心 600/900/1300），升完按新等级回满；任意建筑都能**免费挪**（等级和已花的钱都带得走）。范围圈和真实判定是同一个数（判定另加敌人半个身位） |
| 整页分层（`activity_main.xml`） | **三层**：第 0 层 `MountainBackgroundView` 岩石底、第 0.5 层 `BattlefieldView` 世界层，**两个都铺满全屏**，第 1 层界面浮在最上面（HUD/按钮/底部导航用 glass 半透明）。两层**共用同一个相机**，拖动/缩放时地上的石头和格子一起动。界面层**只加东西、不加底**：中间那个 `@id/play_area` 是空占位，只负责回答"战场是哪一块、在哪儿"（格子大小由它算）。世界层铺满是必须的——2× 时那片地（2898px）比屏幕（2844px）还高，只给中间一条的话会被裁掉，放多大都出不了框。游戏页是**固定美术、不跟随系统深浅色**，所以没有 `values-night/`。规格见 `ART.md` §5 |
| 底部导航与落地页（`menu/bottom_nav.xml` + `MainActivity.showTab`） | **骨架通了**：统计 / 游戏 / 论坛 / 我的。**打开 App 落在"统计"**——落地页就是菜单里的第一项，没有第二个开关，换顺序就是换主页。**游戏与论坛已有页面**，统计与我的共用一个空壳（`@id/empty_page`），上面写一句 `Xxx is not built yet`（一片空白分不清"还没做"和"崩了"）。切页时世界层一起 `INVISIBLE`（它铺满全屏，不藏会从空壳下面透出来），**顺带把战斗冻住了**——推进挂在 `onDraw` 上，看不见就不出帧，回来也不补帧。四个坑见 [`TASKS.md`](TASKS.md) |
| 桩数据（`data/stub/`） | **完成**，可以照着做界面和玩法 |
| 界面骨架（`ui/`） | 根包里的 `MainActivity` 已经是**真的**（战场页 + 底部导航 + 商店/详情弹窗，见上两行），但 `ui/` 里还只有 `package-info.java`，等三人分头填 |
| 数据侧真实现 | **进行中**。`data/local` 已经落地：三张表 + 转换器 + DAO + 滚汇总（`DailyRollup`，11 个测试）+ `RoomUsageRepository`，Room 的建表语句导出在 `app/schemas/`。**还没有**：`data/remote` 的 fetcher、`data/importer` 的解析器、`BudgetRepository`（见 `TASKS.md` 的待办）。**价目表是空的**——见 `DATA_SOURCES.md`/`BundledPricingSource`，没录价的模型成本显示成「不可计算」而不是 0。依赖：Room 2.8.5 + MPAndroidChart v3.1.0，清单见 [`OPEN_SOURCE.md`](OPEN_SOURCE.md) |
| 论坛（汪庭栋） | News / Community、图文发布、点赞评论、已有账号登录与 HTTP 适配已实现；后端已在现有服务器运行，见 FORUM_API.md 和 backend/README.md |
| 游戏与 agent（刘宗润） | 战场几何已定；波次、放塔、塔的数值、agent 逻辑未开始 |

具体的下一步见 [`TASKS.md`](TASKS.md)。
