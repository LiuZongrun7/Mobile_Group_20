# TokenTrail 时期的提交资料（归档）

**这里的东西不是当前方案。** 产品方向在 2026-09-30 改成了 **ModelPilot**，
当前大纲与设计稿在上一级：[`../modelpilot-outline/`](../modelpilot-outline/README.md)、
[`../modelpilot-ui/`](../modelpilot-ui/README.md)，索引见 [`../README.md`](../README.md)。
提交给老师的那一份以 **ModelPilot** 为准。

## 为什么会有两份

两件事是**并行**做的，隔了三天：

| 时间 | 谁 | 提交 | 做了什么 |
| --- | --- | --- | --- |
| 2026-09-27 | 张莉（ZhangLihaha） | `284ee44` 更新report和ppt | 当时还叫 TokenTrail：英文 4 页大纲转成 Word（`.docx` + 重新导出的 `.pdf`）、18 页 PPT 逐页方案（`TokenTrail_PPT_Detailed_Plan_zh.md`）、18 页成品 `新版ppt.pdf`（封面写的是 TokenTrail / "Mobile AI API usage, CNY cost and wallet runway tracker"），并**删掉了**旧幻灯片源 `TokenTrail_Project_Outline_Slides.pptx` 与旧大纲 `.tex` |
| 2026-09-30 | 哈里（Harry-magic） | `f7f0f44` outline and UI design | 产品方向改名 **ModelPilot**：新的 4 页 outline（`.tex` + `.pdf`）、整套 `modelpilot-ui/` 设计稿，并把 `docs/README.md` 改成"ModelPilot 是当前方案、TokenTrail 是历史" |

改名那一笔**没有删这批文件**（只在索引里标成"历史对照"），于是同一个文件夹里
躺着两份 4 页大纲、两个产品名。2026-09-30 收进这个目录，就是为了让"当前"和"历史"
一眼分得开。

## 这里有什么

| 文件 | 是什么 | 备注 |
| --- | --- | --- |
| `TokenTrail_Project_Outline.docx` | **旧版正式大纲**（英文，4 页） | 由 LaTeX 版转成 Word；原 `.tex` 已在 `284ee44` 删除 |
| `TokenTrail_Project_Outline.pdf` | 同一份大纲的 PDF | 4 页 |
| `TokenTrail_PPT_Detailed_Plan_zh.md` | 18 页 PPT 的逐页方案（中文） | 每页标题、正文、配图、备注都写全了；通篇 TokenTrail |
| `新版ppt.pdf` | 18 页成品 PPT（36.6MB） | **封面写的是 TokenTrail**；仓库里**没有可编辑源**（`.pptx` 没进来） |
| `outline-slides-src/` | 更早那版 15 页幻灯片的生成脚本（`build_deck.py`、`fig_*.tex/png`） | 它的输入 `.tex` 和输出 `.pptx` 都已被删，**这条链现在是断的** |
| `TokenTrail_Project_Outline.aux/.log/.out` | 当年编 LaTeX 留下的中间文件 | `.gitignore` 里本来就忽略 `*.aux/*.log/*.out`，留在这里只是不舍得删 |

## 要接着用这份 PPT 的话

- **它和当前大纲不是一个名字**：老师对着 `../modelpilot-outline/` 看会读成两个产品。
  要么按 ModelPilot 重做封面与标题（需要原始 `.pptx`，仓库里没有，找张莉要），
  要么在 PPT 里加一页说明改名。
- 那份逐页方案里的 **Slide 15 是「tower-defence game」**——游戏已经在 2026-09-30
  整块移出本工程（见 `../../../TokenTrail_Game/`），这一页要去掉或换掉。

---

### 这批文件原本的说明（照搬过来，一字未改）

旧版 TokenTrail 提交资料也保留在这里（英文，供历史对照）：

| 文件 | 说明 |
| --- | --- |
| `TokenTrail_Project_Outline.tex` / `.pdf` | 大纲源文件与成品，4 页 |
| `TokenTrail_Project_Outline_Slides.pptx` / `.pdf` | 幻灯片成品，15 页 |
| `outline-slides-src/` | 幻灯片的生成脚本与插图源码，见其中的 `README.md` |

以下编译说明仅针对旧版资料，不适用于上方的 ModelPilot Outline：

```bash
# 大纲：跑两遍让 hyperref 的目录/链接对上
cd docs/archive-tokentrail && PATH="/Library/TeX/texbin:$PATH" xelatex -interaction=nonstopmode TokenTrail_Project_Outline.tex
# 幻灯片：脚本自己找同目录的 fig_*.png，输出到上一级（就是 archive-tokentrail/）
cd docs/archive-tokentrail/outline-slides-src && python3 build_deck.py
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
