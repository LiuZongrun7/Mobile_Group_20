# TokenTrail 文档索引

这个文件夹放**所有文字信息**：接口契约、分工、架构说明、交接须知。
写代码的时候要查的东西，先来这里。

| 文档 | 讲什么 | 什么时候看 |
| --- | --- | --- |
| [`CONTRACTS.md`](CONTRACTS.md) | 接口契约：命名口径、时间口径、存储、结算规则、三种资源、TBD 清单 | **动手前先看一遍**，改字段前再查一次 |
| [`TASKS.md`](TASKS.md) | 三个人的待办、验收标准、依赖谁 | 认领任务、判断「做完了没」 |
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

**改大纲时容易踩的两个坑**（都踩过）：

- 这台 Mac 上字体走的是 `Helvetica Neue`，它**没有 `→` 这个字形**，会渲染成空框。
  箭头一律写 `$\rightarrow$`，让它从数学字体里取。
- 字体兜底那一支**不要**写 `\setmainfont{Latin Modern Roman}`——fontspec 不把它当
  系统字体解析，编译不会报错，但整篇会静默丢掉所有字形（两万多个缺字）。
  宁可留空。

验证：编译完看 log，`Overfull` / `Missing character` 都应该是 0，
末尾有 `Output written on ... (4 pages)`。页数上限是 4 页，加内容必须同时删内容。

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
│   ├── remote/        张莉（用量）、汪庭栋（论坛）：Firestore / HTTP
│   ├── importer/      张莉：日志解析
│   └── RepositoryProvider.java   ★ 全项目拿 Repository 的唯一入口
├── game/
│   ├── engine/        刘宗润：塔防逻辑。不 import 任何 android.*，能脱离手机跑单元测试
│   └── view/          刘宗润：把 engine 画到屏幕上
├── agent/             刘宗润：建议服务的客户端与工具分发
├── ui/                game / dashboard / forum / auth / common —— 三人共担
└── util/TimeUtils.java  时区与「天」的换算，唯一出处
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

- **`true`（现在）**：界面拿到的全是假数据。谁的真实现没写完，谁就不受影响。
- **`false`（交之前）**：改用真实现。**这时候要顺手把方法体里的 `new XxxImpl(...)`
  填上**，否则会抛 `UnsupportedOperationException`，报错信息里写了该去写哪个类。

改这一个布尔值，全 App 的界面都不用动——这就是上面那条「只认接口」换来的。

**提交之前必须改成 `false`**，否则演示时屏幕上全是编出来的数字。

桩数据里 `importCalls()` 会如实报告「全部被拒」（`rejected` 等于传入条数）：
桩没有真的存进去。这样谁误以为导入已经能用了，会立刻发现。

## 4. 构建与测试

```bash
./gradlew assembleDebug            # 编译，出 APK
./gradlew testDebugUnitTest        # 跑单元测试（现在 21 个，全过）
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
| 接口契约（`contract/`） | **完成**，字段定稿，21 个单元测试盯着 |
| 桩数据（`data/stub/`） | **完成**，可以照着做界面和玩法 |
| 界面骨架（`ui/`） | 只有模板 `MainActivity`（在根包，不在 `ui/` 下），`ui/` 里目前只有 `package-info.java`，等三人分头填 |
| 数据侧真实现（张莉） | 未开始，接口已定 |
| 论坛真实现（汪庭栋） | 未开始，接口已定 |
| 游戏与 agent（刘宗润） | engine / agent 逻辑未开始，接口已定 |

具体的下一步见 [`TASKS.md`](TASKS.md)。
