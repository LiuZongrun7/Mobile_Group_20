# ModelPilot：报告所选示例的实际复用（2026-10-09）

## 选择依据与复用边界

当前 Outline 的实现章节明确列出 **Pydantic AI chat app example**，旧逐页 PPT 方案也把它列为 chosen small code starting point。该示例是 Python/FastAPI 加网页代码；ModelPilot 主聊天是 Java/XML Android，手机直连 Provider。因此本次做的是**部分算法和数据格式的 Java 适配**，不是把整个 Python App 当成 Android 基础工程，也没有新增中转服务器。

上游仓库：https://github.com/pydantic/pydantic-ai
官方示例：https://ai.pydantic.dev/examples/chat-app/
固定源码：https://github.com/pydantic/pydantic-ai/blob/36529f3a8ebc5a5675129fe6262223b9da0815ec/examples/pydantic_ai_examples/chat_app.py
许可证：MIT。原文、未改动 Python 快照、版本及校验值位于 `third_party/pydantic-ai-chat/`；许可证也随 APK 的 assets 分发。网页 TypeScript 只作为已阅读的算法参考，不声称其已固定到上述提交。

## 上游代码与本项目的对应关系

| 上游位置 | 适配到的代码 | 实际用途与修改 |
| --- | --- | --- |
| `chat_app.py::to_chat_message`、`GET /chat/`：以 role / timestamp / content 产生逐行 JSON | `data/importer/PydanticChatImport.java`、`ExportFileReader.java` | 支持从所选示例导入文本历史。将 user/model 转为 USER/ASSISTANT，保留文字和时间，通过既有预览、合并策略和 Room 保存链路恢复，之后可在同一对话继续聊天。新增严格 UTF-8、大小、时间、角色和整文件校验。 |
| `chat_app.ts::addMessages`：按稳定时间标识更新同一消息，而不是把累计片段当新轮次 | `PydanticChatImport.java` | 移植为 LinkedHashMap 的最后快照覆盖规则，改为“角色 + 标准化完整时间”，防止不同角色撞时间；增加命名空间哈希，重复导入保持稳定身份。 |
| `chat_app.py` 的 `stream_output(debounce_by=0.01)` 和累计回复快照 | `chat/StreamSnapshotBuffer.java`、`ChatConversationViewModel.java` | 适配成线程安全累计缓冲区，Android 每 32ms 最多安排一次待处理 UI 更新。结束保存完整末尾，取消时使旧排队快照失效。Provider 的流式协议解析仍使用本项目代码。 |

这里没有复用上游的 SQLite 表、浏览器 DOM、具体模型选择、Logfire 或后端聊天接口。现有多 Provider Adapter、Room、记忆压缩和用量账本仍是本项目实现。32ms 是本项目 UI 设置，不是上游参数；尚未做真机帧率基准，不能宣传为已测得某个百分比的提速。

## 一起推进的核心功能：项目指令

现有 `ProjectEntity.instructions` 已有数据库字段，却没有 UI 编辑和实际发送链路。本次补齐：

1. Chat 项目标题旁的设置图标打开指令编辑框；4096 字符上限，支持保存、清空和失败重试。
2. DAO 将指令写入已有 Room 列；不需要升级数据库版本。
3. 每次发送根据**当前对话实际所属项目**重新读取指令，ContextEngine 以 system 指令加入两类 Provider 格式。
4. 指令计入发送前 Token 估算和上下文容量检查，编辑/清空使渲染缓存失效。
5. 指令独立于可压缩消息，换模型和压缩历史时保留。它是用户明确写下的共同要求，不会自动共享项目内所有对话的历史。

这一功能属于本项目新增能力，不算 Pydantic AI 代码复用成果。

## 可导入的数据与限制

接受示例 `GET /chat/` 返回的 UTF-8 NDJSON：一行一个 `{ "role": "user" 或 "model", "timestamp": "ISO 时间", "content": "文本" }`。真实响应可保存为 `.ndjson` 或 `.txt`；仓库提供**虚构演示数据** `docs/samples/pydantic-chat.ndjson`。选择文件时只读取用户明确选中的文件，导入本身不上传数据；用户继续发送时，正常聊天规则会把保留的历史发给选定 Provider。

Android 导入入口不直接接受上游 SQLite 的原始 `new_messages_json`、任意模型历史 JSON、工具调用或附件。2026-10-09 第二轮新增离线 bridge，可将上游 SQLite 的纯文本历史转换为此 NDJSON，详见 [完整技术说明](OPEN_SOURCE_TECHNICAL_REPORT.md)。格式不含模型身份、Token 和费用，因此不生成历史用量账单，也不会把导入文字当作 system 指令。导入只是可选的历史迁移能力；普通用户日常使用不需要运行上游示例服务器。

同一角色/完整时间的重复记录取文件中的最终快照。默认 ADD_ONLY 会跳过本机已有的整条对话；需要追加新历史时可明确选择 REPLACE，既有合并规则保留本机同 ID 消息和本机独有消息，不保证用新的快照覆盖已保存的文字。导入后的聊天按毫秒存时间；原始微秒用于身份区分，不是界面时间精度。

## 验证和手机验收

自动验证覆盖格式接入、合并链路、重复导入、累计快照、角色/时间碰撞、乱码与坏行、无伪造用量、并发流缓冲、取消/结束、跨模型指令保留、缓存失效和压缩后保留指令。检查命令：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

最终执行结果：**310 项 Android 单元测试通过，0 失败、0 错误、0 跳过**；其中本次新增 22 项。`assembleDebug` 和 `lintDebug` 成功，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。lint 仍存在工程既有警告，成功不代表零警告。

手机/模拟器需补充以下验收，本次没有连接设备，也没有调用付费 API：

1. 将 `docs/samples/pydantic-chat.ndjson` 复制到手机，在 Me → Data & memory → Import data 中选择文件，确认预览为 1 个对话、4 条消息、0 个用量记录。
2. 确认导入，在 Chat 打开 `Pydantic · …`，配置 Provider key 后追问“刚才活动有几位来宾？”验证历史参与下一次请求。重复导入默认不增加副本。
3. 新建项目，点击标题旁设置，保存“用中文回答；保留原文数字”。在项目下不同对话及手动切换模型后验证要求仍传入；清空后下次请求不再附带。
4. 使用长回复观察流式显示，停止后确认没有排队文字重新冒出；断网后查看失败提示。转屏检查指令草稿和回复状态。
5. 保持其他项目、散聊的指令隔离，并验证已有导出 JSON / ZIP 导入仍能使用。

## 后续增强（同日第二轮）

已新增 `tools/pydantic_chat_bridge.py`（直接改编上游 Database），单条聊天 NDJSON 导出，以及主聊天 Room 事务历史/回复边界。第一轮记录的 310 项测试是当时结果；第二轮当前 318 项 Android 单测及 10 项 bridge 测试通过。5 项真实 Room 测试已在 Pixel_6 模拟器（Android 17/API 37）执行通过；实体手机 UI/付费 API 仍待验收。详细来源映射、12 项评分证据、接口与成员协调见 [技术报告](OPEN_SOURCE_TECHNICAL_REPORT.md)、[团队计划](TEAM_DELIVERY_PLAN.md)。
