# 论坛首版：客户端与团队后端契约

## 当前交付状态

Android 已提供 News / Community 页面、最多 9 张图片的发帖草稿、帖子详情、点赞与评论、分页及失败重试。论坛后端已部署至 `https://43.140.212.47/api/`，账号是我们自己的（**邮箱注册 + 邮箱验证**，2026-10-05 起；不再借那台机器上其它项目的账号），真实双账号图文、点赞评论、幂等、重启持久化及退出会话联调已通过。运行目录、备份、RSS 和 HTTPS 续期见 [`backend/README.md`](../backend/README.md)。没有后端配置时客户端显示准备中，不回退到假帖子。论坛独立于其他模块的 `USE_STUBS` 开关。

新闻在服务端采集，只使用英文 AI 与科技 RSS；不做好友、关注、私信、视频、通知栏推送。媒体新闻单独建模，不能标成 `ForumPost.Source.OFFICIAL` 供 agent 当作官方证据。

## 接入账号与 API 地址

开发期可以直接点击调试 APK 中的“免账号测试”。测试会话使用独立 `/test-api/` 服务和独立帖子池，不需要账号；正式接口要求**登录 APP 账号**（2026-02 起就是 `/api/account/*` 那个账号，见下）。测试身份有效期 24 小时，新闻与正式服务共享只读新闻快照，帖子/图片/点赞/评论隔离。`POST /test-api/forum/test-session` 返回 `{token,accountId,displayName,expiresAtEpochMillis}`；`DELETE` 同一路径携带该 Bearer token 退出。服务端仅存 token 的 SHA-256，正式 `/api/` 拒绝该 token；客户端 Release 构建隐藏入口。部署细节见 backend/README.md。

**2026-09-27 起账号归我们自己**：注册、验证、登录、退出都打 `/api/account/*`（`POST register` / `POST verify` / `POST verify/resend` / `POST login` / `GET me` / `POST logout`），会话 token 由我们自己签发、库里只存 sha256。**不再调用那台机器上其它项目的 `/api/login`、`/api/users/me`**——这个 APP 里没有「团队」这回事，用户就是 APP 的用户。`posts.author_uid` 存的**就是**账号的 `user_id`（还是原来那个值，所以论坛数据一个字没改）。密码用 `hashlib.scrypt`（标准库，每用户一份随机盐），**不存明文、不存裸 sha256**。退出撤销当前会话；离线时仅保证本机退出。新闻和帖子均要求登录。

**注册要邮箱，而且验证过才能登录**（2026-10-05 加）：填 `email` + `username`（论坛里显示的昵称）+ 密码 → 服务端发一封 6 位验证码的邮件 → 客户端在同一个对话框里进第二段填码 → 验证通过后自动用刚填的密码登录。没验证的账号登录返回 `403 EMAIL_UNVERIFIED`，客户端据此跳到验证码那一屏。错误码、限流和"发信失败就回滚账号"那几条见 [`SERVER_API.md`](SERVER_API.md#账号apiaccount)；邮箱那几列是后加的，**加邮箱之前建的老账号继续用用户名登录**（判据是建号时间）。

**密码**：忘了密码可以用注册邮箱收一条重置码改（`/api/account/password/forgot` + `reset`，
改完所有设备都要重新登录）；已经登录的可以在账号对话框里改（`password/change`，只踢其它设备）。
注册码和重置码是两种码，不能互相顶用。

`gradle.properties` 已配置公开地址 `forumBaseUrl=https://43.140.212.47/api/`。账号模块**已经接好了**：`data/AccountSession` 实现 `SessionProvider`，通过下面的接入点把会话交给论坛。

账号模块在建立/恢复会话后调用 `RepositoryProvider.configureForum("https://your-api.example/api/", sessionProvider)`。`SessionProvider.token()` 和 `accountId()` 必须每次返回当前会话；退出登录时返回 null。会话提供者需要能安全地被 HTTP 回调读取。更换 API 或 provider 时再调用 configureForum。客户端不在 Gradle 或仓库保存 token，不创建第二套账号体系。

也可在本机未提交的 Gradle properties 设置 `forumBaseUrl=https://your-api.example/api/`；这个值仅指定公开 API 地址，仍须接入账号模块的 SessionProvider。生产 API 必须 HTTPS。App 仅通过后端接口读写，不直连数据库。

### 登录“无法连接”的排查

2026-10-07 排查确认了两处独立问题：服务端证书过期（续期配置的旧路径，修复记录见
[`backend/README.md`](../backend/README.md#https-连接失败排查2026-10-07)），以及测试模拟器的
`10.0.2.2:7897` 代理在 TLS 握手时关闭连接。证书恢复后，电脑直连成功并不意味着
模拟器通过代理也能连上。

先查看 `adb logcat -d -s Account:W` 中的异常和
`adb shell settings get global http_proxy`。本次同一健康检查直连返回 200，经过代理
返回 TLS 错误；记录原代理值后，用 `adb shell settings put global http_proxy :0`
临时切换模拟器为直连，再从 App 重试。需要恢复代理时，将 `http_proxy` 设回原值。
不要通过禁用证书校验或改用 HTTP 解决连接问题。

连接失败与账号错误需分别判断：`SSLHandshakeException` 且服务器没有请求记录，
先查证书和代理；服务器已收到登录请求并返回 401，则查登录信息和注册流程。
点击“注册”进入表单不等于账号已创建，应确认注册接口成功、收到验证码并完成邮箱验证。

全部请求携带 `Authorization: Bearer <session-token>`，新闻和帖子均要求登录。作者 UID、显示名、时间、官方标记和计数从服务端会话/数据计算，不能信任客户端传入字段。论坛帖子及评论跨账号公共可读；`me` 查询只是作者筛选；用量和预算仍是账号私有数据。

## 新闻列表与 App 内阅读（2026-10-07）

App 界面文案统一使用英文，包括搜索、Trending、新闻阅读、账号和免账号测试提示。
新闻原文与用户发布的内容保留其原始语言，不自动翻译。

News 列表使用左侧标题、来源和日期，右侧单张圆角缩略图的横向行布局。
缩略图来自这篇文章的 RSS 图片、完整 RSS 内容或文章页的 Open Graph / Twitter
预览图片；没有图片或加载失败时显示中性占位图，不用其他新闻的照片代替。
采集任务每次最多补查 30 篇、最多 5 个并发，失败结果缓存一天，已补取的图片复用。
网页预览只允许访问已配置的新闻出版商，限制超时和读取大小，不采集正文。

点击新闻打开 App 内 `NewsReaderActivity`，完整原文由出版商网页直接呈现，
普通 HTTPS 链接继续在阅读页打开，返回按钮回到新闻列表，不启动外部浏览器。
原文访问失败时展示明确标为“Publisher summary”的 RSS 摘要和重试入口；摘要不代表全文。
不向出版商发送论坛会话，不暴露 JavaScript 桥接，证书错误取消加载。
正式账号和免账号测试共用这一阅读界面。

## 搜索与 Trending（2026-10-07）

Explore 顶部搜索框通过服务端查询完整数据，News 和 Community 共用关键词，
各自保存分页和滚动位置。点击搜索或键盘搜索提交，清空按钮恢复默认列表。
切换关键词重置游标并拒绝旧请求的迟到结果；搜索无结果有独立提示。

`GET forum/news?q=...` 搜索标题、RSS 摘要和来源，
`GET forum/posts?q=...` 搜索标题、正文和作者昵称；不搜索图片内容或评论。
关键词最长 100 字符，去掉首尾空白、合并连续空白，按大小写不敏感的字面子串匹配
（SQLite lower 的大小写折叠覆盖 ASCII；中文可直接匹配）。百分号和下划线也按字面匹配。
结果仍按最新排序，每页最多 20 条。游标与规范化后的关键词绑定，跨关键词复用返回 400。
省略 `q` 或提交空白字符串与原列表行为相同。

`GET forum/trending` 返回 `{windowDays:7,asOfEpochMillis,topics:[...],posts:[...]}`。
topics 字段为 name、query、rank、articleCount、sourceCount、latestPublishedAtEpochMillis。
新闻热词从近 7 天标题提取，排除常见无意义词，对 agent/agents 等合并词形，
至少有两篇报道才入榜，按报道数、来源数、最新报道时间、关键词排序，最多 10 项。
该统计表示报道频次，不表示浏览量。点击热词进入 News 关键词搜索，结果范围为全部保留的新闻。

热门讨论只包含近 7 天发布、至少有一个点赞或评论的帖子，
热度为点赞数 + 评论数 × 2（计入这些帖子的现有全部互动），同分按发布时间、ID 倒序，
最多 10 条。没有互动时显示空态，不生成测试热度。UI 显示统计依据，并支持下拉刷新。
点赞或详情更新同步现有榜单并重新排序；完整榜单以刷新后的服务端数据为准。

以上接口沿用论坛鉴权；免账号测试区从只读快照读取新闻，搜索和 Trending 的社区部分
只读取隔离测试库。正式账号仍访问正式论坛。

## JSON 模型

所有时间为 UTC epoch 毫秒。列表响应均为 `{"items": [...], "nextCursor": "opaque-or-null"}`，每页最多 20 条。游标必须与排序键绑定，末页返回 null；列表不能缺少 items，空列表返回 []。

帖子示例：

```json
{
  "id": "post-123",
  "title": "A small AI project",
  "body": "Here is what I built.",
  "source": "COMMUNITY",
  "authorUid": "user-123",
  "authorName": "Alex",
  "createdAtEpochMillis": 1790409600000,
  "images": [{"id": "image-123", "url": "https://media.example/image-123.jpg"}],
  "likeCount": 3,
  "likedByMe": false,
  "commentCount": 2,
  "helpfulCount": 0,
  "viewCount": 12,
  "rankScore": 0.8,
  "modelTag": null
}
```

评论使用现有 `ForumReply`：id、postId、body、authorUid、authorName、official、createdAtEpochMillis、helpfulCount。文字评论不附图片。所有帖子的 images 都返回数组，无图为 []。图片地址必须能在其他设备访问，不能返回 content://、本地文件或短期到期后无法刷新的地址。

新闻模型：id、title、summary（可为 null）、sourceName、originalUrl、imageUrl（可为 null）、category（AI / Technology）、publishedAtEpochMillis。summary 为清理 HTML 后的 RSS 原摘要，不自动生成或翻译。

## UI 接口

| 路径（相对 base URL） | 行为 |
|---|---|
| `GET forum/posts?cursor=...&limit=20` | 共享帖子池，`createdAtEpochMillis DESC, id DESC` |
| `GET forum/posts/{id}` | 完整帖子，含当前用户 likedByMe |
| `GET forum/news?cursor=...&limit=20` | 独立新闻表，`publishedAtEpochMillis DESC, id DESC` |
| `POST forum/images` | multipart 字段 image，返回 `{id,url}` |
| `POST forum/posts` | JSON `{title,body,imageIds}`，返回完整帖子 |
| `PUT forum/posts/{id}/like` | 设置当前用户已点赞，返回完整帖子 |
| `DELETE forum/posts/{id}/like` | 设置当前用户未点赞，返回完整帖子；不是 204 |
| `GET forum/posts/{id}/replies?cursor=...&limit=20` | `createdAtEpochMillis ASC, id ASC` |
| `POST forum/posts/{id}/replies` | JSON `{body}`，返回完整评论 |

发布标题可空；正文非空或至少一张图片；单帖最多 9 张。上传先完成，发布仅引用当前账号已上传的 imageIds。服务端检查图片实际格式和大小（每张最多 10 MB），持久保存。发布事务检查图片归属和可用性，并原子创建帖子。未被帖子引用的图片按后端维护任务清理，不能把它们暴露在公共池。

发帖和评论携带 `Idempotency-Key`。以 `(uid, endpoint, key)` 唯一存储成功结果；同 key、同内容重试返回原结果，同 key、不同内容返回 409。记录与写操作处于同一事务，避免「已入库但响应丢失」后重复发布。客户端只在内容变化时更换 key，失败保留草稿和已上传图片 ID。

点赞表使用 `(postId, uid)` 唯一约束；PUT/DELETE 重试无额外副作用。commentCount、likeCount 按真实记录更新，不能接收客户端自报计数。每次返回帖子时计算当前会话的 likedByMe。

成功返回 200 或 201 与上述 JSON。错误至少使用 400（输入非法）、401（会话过期）、403（图片归属/写权限不符）、404（帖子不存在）、409（幂等 key 冲突）、413（图片过大）、429（限流）、5xx（服务不可用）。错误体可为 `{code,message}`；客户端按 HTTP 状态显示反馈，不把错误视为空列表。

## 保留的 agent 兼容接口

现有 ForumRepository 的 Java 签名保持不变，真实 HTTP 实现共用同一个会话：

| 路径 | 返回 |
|---|---|
| `GET forum/official?limit=...` | `List<ForumPost>`，只包含真实官方帖子，可为空 |
| `GET forum/hot?since=...&limit=...` | `List<ForumPost>`，按后端 rankScore 降序，同分按 ID |
| `GET forum/me/posts` | `List<ForumPost>`，当前会话作者的公共帖子 |
| `GET forum/highlights?modelFilter=...&since=...&limit=...` | `ForumHighlights`，官方帖与社区帖，不包含新闻 |
| `GET forum/me/threads?since=...&limit=...` | `MyThreads`，当前会话帖子和评论 |

这些接口是团队既有 agent 的兼容面，不能把它们换成新闻接口；没有官方内容时返回空集合。`since` 使用已有 yyyy-MM-dd 契约。热度由后端统一计算，UI 社区列表仍按最新发布排序。客户端错误在这些旧 LiveData 接口中返回 null；agent 必须视为无可用证据，不能生成虚构帖子。

## RSS 采集工具

`tools/news/rss_collector.py` 是 Python 3.10+ 标准库实现，新闻源配置位于同目录 sources.json。后端部署时每小时运行一次：

```bash
python3 tools/news/rss_collector.py --output /srv/tokentrail/news/staging.json
```

输出含 items、collectedAtEpochMillis、sourceErrors，供团队后端导入新闻表；**它不是 App 直接读取的分页 API**。新闻主键采用规范化原文 URL 的 SHA-256。导入以 id upsert，再由 `GET forum/news` 提供登录检查和游标分页。

采集并发读取 5 个固定英文来源、清理 HTML、剔除跟踪参数并保留有意义的查询参数、去重并按时间排序，默认保留近 30 天。无日期/非法链接条目跳过，未来异常日期不呈现为新新闻。单来源失败保留其已有内容，其余来源继续更新；输出用原子替换避免写到一半破坏缓存。所有来源失败时仍保留旧数据并返回退出码 1。后端监控 sourceErrors 和采集时间，避免定时任务重叠运行。

## 联调与验收

客户端验证：`./gradlew assembleDebug testDebugUnitTest connectedDebugAndroidTest`；采集工具验证：`python3 -m unittest discover -s tools/news -p 'test_*.py'`。

后端验收使用两个真实账号及不同设备/重启：A 发布图文，B 读到并点赞评论，A 刷新看到正确计数；重复 key 不重复发布；切换账号不残留上一账号点赞状态；会话过期、图片失败和来源超时可恢复。部署脚本已用两个临时真实账号通过公网 HTTPS 验证共享图文、点赞评论、幂等、服务重启持久化和注销后令牌拒绝；测试数据及账号已清理。模拟器覆盖页面、草稿恢复和账号会话；不同实体手机的最终演示仍需安装 APK 后操作。
