# ModelPilot

一个面向日常任务的多模型 AI 助手 Android 项目，原名 TokenTrail。

产品设计围绕三个核心能力：跨模型上下文管理、按每次请求选择模型的 Auto / 手动模式，以及任务关联的用量与费用分析。项目下可以组织多个对话；新闻和论坛是附加功能（塔防游戏 2026-09-30 整块移出，见 `../TokenTrail_Game/`）。

2026-10-09 新增：所选 Pydantic AI 示例的文本历史导入、流式更新合并，以及项目指令的编辑、保存与跨模型发送。复用范围、来源和验收步骤见 [复用与进度说明](docs/PYDANTIC_REUSE.md)。

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

服务端（`backend/`）现在只有三样东西：**账号**（`/api/account/*`）、**论坛与新闻**（`/api/forum/*`）、**应用内智能体**（`/api/agent/ask|status`），外加一个不需要身份的 `GET /health`。契约见 [docs/SERVER_API.md](docs/SERVER_API.md)。

当前 Android 主对话链路已改为**手机直连用户配置的 Provider API**：API key 留在本机，对话、摘要记忆和用量账本使用本地 Room 保存；登录账号用于论坛，不自动云同步聊天数据。`/api/agent/*` 仍是可选的服务端论坛助手，和手机主聊天是两条不同的链路。原 relay、服务端预算/价目/赛季接口已删除。

截至 2026-10-08，Auto 已支持任务能力、上下文容量、本次输入输出费用估算，以及成本/上下文/平台偏好；手动选择不会被自动替换。剩余预算硬限制、质量/速度学习和完整工具工作流仍未实现。实现范围、测试和真机验收步骤见 [本次进度说明](docs/PROGRESS_2026-10-08.md)。

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
