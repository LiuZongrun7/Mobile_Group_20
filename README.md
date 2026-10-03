# ModelPilot

一个面向日常任务的多模型 AI 助手 Android 项目，原名 TokenTrail。

产品设计围绕三个核心能力：跨模型上下文管理、按每次请求选择模型的 Auto / 手动模式，以及任务关联的用量与费用分析。项目下可以组织多个对话；新闻和论坛是附加功能（塔防游戏 2026-09-30 整块移出，见 `../TokenTrail_Game/`）。

## 项目资料入口

| 内容 | 入口 |
| --- | --- |
| 最新项目大纲（英文，4 页） | [Outline PDF](docs/modelpilot-outline/ModelPilot_Project_Outline.pdf) |
| 大纲 LaTeX 源文件与编译方法 | [Outline 目录](docs/modelpilot-outline/README.md) |
| 前端设计稿与页面图册 | [设计稿目录](docs/modelpilot-ui/README.md) · [完整图册](docs/modelpilot-ui/GALLERY.md) |
| 页面与交互规范 | [DESIGN.md](docs/modelpilot-ui/DESIGN.md) |
| Insights 统计口径与接入说明 | [INSIGHTS.md](docs/modelpilot-ui/INSIGHTS.md) |
| 既有 Android / 后端开发资料 | [文档索引](docs/README.md) |

![ModelPilot 核心页面总览](docs/modelpilot-ui/overview.png)

## 查看前端设计稿

直接在 GitHub 打开 [完整图册](docs/modelpilot-ui/GALLERY.md)，即可查看 29 张主设计图；[设计稿目录](docs/modelpilot-ui/README.md) 还列出了长图与异常状态补充图。需要原尺寸时，打开对应 PNG 图片。

本次提交的是静态设计稿与设计说明，不包含 HTML / CSS / JavaScript 原型、依赖或截图脚本，不需要启动服务。图中的回复、用量和费用是设计样例，不代表新方案已全部实现。

## 服务端现在提供什么

服务端（`backend/`）只有五组给 App 用的接口：**账号**（`/api/account/*`）、**用量**（`/api/relay/usage*`）、**预算**（`/api/relay/budgets/{month}`）、**价目**（`/api/relay/pricing*`）、**赛季结算**（`/api/relay/season*`）和**应用内智能体**（`/api/relay/agent/*`），另有论坛与新闻。契约见 [docs/SERVER_API.md](docs/SERVER_API.md)。

> **原来的「API 中转」已整块删除（2026-09-30）。** 那一层是「用户把自己上游的 API key 填进来、我们把 cc-switch 的请求替他转发上去并顺手记用量」——新方向（ModelPilot 大纲）改成**用户在 App 里提问、后端用我们自己的模型连接调用**，所以转发这条路没有了：**用户不再需要交任何 key 给我们**，relay key（`tt_`）这种凭据不再签发也不再认，身份只有账号 token（`tt_app_`）一种。**「用户提问 → 后端调模型 → 记账」那条新路还没写**，所以用量接口现在读到的可能是一张空表。URL 前缀里的 `relay`（`/api/relay/*`）和 env 变量名（`FORUM_ENABLE_RELAY` 等）是**历史遗留字面量**，改它们要同时动 App、部署脚本和文档，先留着。

## 目录结构

```text
app/                         Android 应用代码
backend/                     后端代码
docs/
  modelpilot-outline/        最新大纲：PDF、LaTeX、配图
  modelpilot-ui/             前端设计稿：PNG、图册、设计说明
  README.md                  文档与既有开发资料索引
tools/                       现有项目工具
```

`docs/modelpilot-outline/` 与 `docs/modelpilot-ui/` 是当前方案的正式入口。旧 TokenTrail 文档保留供历史对照，Android 包名和已有工程结构暂不随产品更名调整。设计图中的模型标志、配图出处及第三方许可证见 [素材来源](docs/modelpilot-ui/SOURCES.md) 和 [licenses](docs/modelpilot-ui/licenses/)。
