# TokenTrail 论坛服务

已部署到 SSH 别名 `mobile_back` 对应的 Ubuntu 22.04 服务器。API 为 `https://43.140.212.47/api/`，健康检查为 `https://43.140.212.47/forum-health`。App 的 `forumBaseUrl` 已指向该地址。

服务使用 FastAPI、SQLite WAL 与本地持久图片存储。论坛数据与原 `aibox_backend` 的数据库分开。

**账号（2026-02 改）：这个后端有自己的账号表，不再打那台机器上的账号服务。**
`accounts` + `account_sessions` 两张表，接口是 `/api/account/register|login|me|logout`，
密码用 `hashlib.scrypt` 存（每用户独立盐，`scrypt$n$r$p$salt$hash`），
会话 token 形如 `tt_app_<43 字符>`、有效期 30 天、库里只存 sha256。
原因见 [`docs/CONTRACTS.md`](../docs/CONTRACTS.md) §5：**身份只能有一个**，
而更早的时候 relay key 自带身份，导致「换个 key 就换个人」。改动之后
**用量、赛季余额、预算、智能体记账的归属列都是 `user_id`**。
（**2026-09-30 改**：relay key 连同中转一起删了，所以"身份只能有一个"从
"两个里选一个"变成了**字面意义上的只有一个**——账号 token。
App 现在同时提供登录和注册入口。）

原来的团队账号服务（`FORUM_AUTH_URL` 那套）**已经彻底删掉**（2026-02 收尾）：
`auth.py` 里的 `TeamAuth` 类、`Settings` 上那三个字段、`FORUM_AUTH_*` 三个环境变量
全部移除，健康检查里的 `authConfigured` 也去掉了——它那时报的是「外部账号服务配没配」，
而那个问题已经不存在。现在鉴权只有一条路：查本库的 `accounts` / `account_sessions`。
要回滚得从 git 历史里取回 `TeamAuth`，**不是**打开几行注释就能回去的。

## 模块清单（`tokentrail_forum/`）

| 模块 | 管什么 |
|---|---|
| `app.py` | FastAPI 应用、`Settings`（env 开关）、论坛路由、健康检查、新闻采集任务 |
| `account_routes.py` | 账号四个接口 `/api/account/*`，和记账那套**分开两个 router** |
| `accounts.py` | 账号与会话的存储（scrypt 密码、sha256 token） |
| `api_routes.py` | **服务端对 App 的 HTTP 层**：用量、预算、价目、赛季、智能体 |
| `usage_store.py` | **用量账本**的读写（`relay_usage` 表、按次/按天读回） |
| `usage.py` | 把各家的 usage 字段归一成四个桶（`split_usage`） |
| `seasons.py` / `budgets.py` / `pricing.py` / `summary.py` | 结算、预算、价目表、汇总与对比 |
| `agent.py` / `agent_tools.py` | 应用内智能体（独立账本 `agent_usage`）与它的五个只读工具 |
| `seed_pricing.py` | 内置价目种子（`FORUM_PRICING_SEED=1` 才装，幂等） |
| `store.py` / `auth.py` / `test_sessions.py` / `news_job.py` / `backup.py` | 论坛存储、鉴权抽象、免账号测试区、新闻与备份 |

**三个名字是 2026-09-30 改的**（中转删除带出来的）：
`relay.py` → **`usage.py`**（只剩 `split_usage`）、
`relay_routes.py` → **`api_routes.py`**（删掉 `/keys*` 与 `{path:path}` 兜底转发）、
`relay_store.py` → **`usage_store.py`**（删掉 `relay_keys` / `relay_secrets` 两张表）。
`relay.py` / `relay_routes.py` / `relay_store.py` **这三个文件已经不存在**，
在旧文档里看到它们时按上面对应关系读。表名 `relay_usage` **保留**——
改表名要写迁移、不改变行为，等下次真动 schema 时一起做。

## 服务端 API（记账 / 预算 / 价目 / 赛季 / 智能体）

> **2026-09-30：原来的「API 中转」整块删除。** 它曾经让用户把自己上游的
> `URL + API key` 填进 App，服务端替他转发（cc-switch 指过来）并顺手记用量。
> 新方向（ModelPilot 大纲）是「**用户在 App 里提问 → 后端用我们自己的模型连接调用
> → 产生用量 → 记账**」，所以不再有转发，也不再有用户的 key 落在服务器上。
> ~~`relay.py`（转发 + SSRF 防护 + 流式解析）、`relay_routes.py` 的 `/keys*` 与
> `{path:path}` 兜底、`relay_store.py` 的 `relay_keys` / `relay_secrets` 两张表~~
> **全部删除**，对应改名见上面「模块清单」。
> **那条新路还没写**：现在替用户调模型的只有应用内智能体，而它记的是独立的
> `agent_usage`（不进日汇总、预算、结算）。

现在同一进程提供五组接口，契约见 [`../docs/SERVER_API.md`](../docs/SERVER_API.md)：

| 前缀 | 认证 | 干什么 |
|---|---|---|
| `/api/relay/usage*` | 账号 token | 读用量（按次 / 按天 / 按模型 / 对比） |
| `/api/relay/budgets/{month}` | 账号 token | 读/写预算上限（花销现算，从不存） |
| `/api/relay/pricing*` | 账号 token | 价目表（改价要留痕，按天查） |
| `/api/relay/season*` | 账号 token | 余额与结算（**游戏移出后暂时没有客户端消费者**，规则保留） |
| `/api/relay/agent/*` | 账号 token | 应用内智能体（另一个 router，另一个开关） |

**路径前缀里那个 `relay` 是历史字面量**：它在 App 的 `ServerApi`、部署脚本和文档里
都写死了，改它要同时改三处、还要重新部署。`FORUM_ENABLE_RELAY` 这个开关名同理——
它现在守的是记账那几组接口，不是转发。

它带来的取舍必须写清楚，别只看好处：

- **用户的真上游 key 落在服务器上**这件事**没有发生**（中转折在启用前就删掉了）。
  当时那段论证和风险判断留在 [`docs/DATA_SOURCES.md`](../docs/DATA_SOURCES.md) §5，
  **原文不删**——下一个想往服务端放第三方密钥的人应该先读它。
  现在服务端持有的是**我们自己的**模型 key（`FORUM_AGENT_KEY`），
  所以「堵住对公网开放的其它端口」这件事**照旧要做**。
- **不再需要解析账单文件**这一点仍然成立，而且更有意义了：后端自己调模型，
  响应里直接带 token 数——`cacheWrite` 这个桶 OpenAI 和 DeepSeek 的导出都拿不到，
  自己调才有。
- **不做协议转换**这条只对转发有意义，随转发一起没有了。

### 应用内智能体（`FORUM_ENABLE_AGENT`，和记账那组分开的开关）

同一个进程还提供 `/api/relay/agent/ask|status`：用户问一句，服务端用**我们自己的**
DeepSeek key 调模型，模型可以调 5 个只读工具（用量汇总、预算、成本对比、
论坛亮点、我发过的帖子）。契约见 [`../docs/SERVER_API.md`](../docs/SERVER_API.md)。

两条边界是结构性的，不是约定：

- **凭据是账号 token，也只可能是账号 token**（2026-09-30 改：原来还要强调"不是
  relay key"，现在 relay key 不存在了）。
- **账本分开**（`agent_usage` vs `relay_usage`）。智能体花的是我们的钱，
  如果算进用户的用量，「我这周怎么花了这么多」的答案就被问题本身污染了。
  两张表也不在一处，`agent_usage` 永远不进日汇总、预算和结算。

`FORUM_AGENT_KEY` 从 `/etc/tokentrail-forum-relay.env` 读（权限 `640 root:tokentrail`），
**不进数据库、不进日志、不进任何响应**；健康检查只报 `agentConfigured: true/false`。

**默认关闭。** `FORUM_ENABLE_RELAY` 不设或为 0 时记账那几组 `/api/relay/*` 返回 404，
`FORUM_ENABLE_AGENT` 不设时智能体那两组同样不注册——**两个开关分开**：
「只想开智能体」的人不该被迫连记账一起开。
启用需要把 `deploy/forum-relay.env.example` 装到 `/etc/tokentrail-forum-relay.env`
（systemd 单元用 `EnvironmentFile=-` 引它，文件不存在也不影响启动）。
~~**`FORUM_RELAY_ALLOWED_HOSTS` 必须填**~~ —— **这个变量已经不起作用了（2026-09-30）**：
它是给"转发到用户自己的上游"做 SSRF 防护的，校验代码随 `relay.py` 一起删了。
同一批作废的还有 `FORUM_RELAY_ALLOW_PRIVATE` / `_SELF_HOSTS` / `_REQUESTS_PER_MINUTE`；
env 文件里留着不报错，但也不生效。**别以为填了白名单就更安全。**

nginx 需要一条独立的 `/api/relay/` location，配置见 `deploy/nginx-https.conf`。
它的超时和缓冲区设置和论坛那一段**故意不同**（长上下文请求动辄几分钟，45s 会在正常
对话中途掐断连接）。~~而且必须 `proxy_buffering off`，否则流式的打字机效果会消失~~
——**2026-09-30 改**：`proxy_buffering off` 是转发流式响应（SSE 透传）时必需的，
现在服务端返回的都是普通 JSON，不再需要它；nginx 那一段**还没改**（改部署要重新部署）。


## 免账号测试区

调试 APK 的论坛页面提供“免账号测试”按钮，不需要账号模块、用户名或密码。每台设备获得一个随机临时身份，有效期 24 小时，支持新闻、图文发布、点赞和评论；退出测试模式会撤销该临时身份。临时会话加密保存在手机上，重启 App 后仍进入测试区。身份到期后再次点击入口即可获得新测试身份。

测试接口为 `https://43.140.212.47/test-api/`，由 `tokentrail-forum-test.service` 在 `127.0.0.1:8011` 提供。数据位于 `/var/lib/tokentrail-forum-test`，配置为 `/etc/tokentrail-forum-test.env`，与正式帖子、图片、点赞和评论分开。两台设备的测试帖子在同一个测试池中。正式 `/api/` 接口拒绝测试 token，测试身份不会写入团队账号库。Release APK 隐藏免账号入口并拒绝恢复测试身份。

新闻采集任务将仅含新闻和采集状态的 SQLite 快照原子导出到 `/var/lib/tokentrail-forum/news-readonly.sqlite3`，测试服务只读该快照；它不读取正式帖子表。测试区不在正式数据备份中。关闭测试服务可运行 `sudo systemctl disable --now tokentrail-forum-test`，正式论坛继续运行。

首次启用：上传新版 backend 代码后，以 root 执行 `deploy/install-test-area.sh`。该脚本生成只读新闻快照、启动测试服务并检查、重载 Nginx。`scripts/check_test_area.py` 使用两份临时身份验证公网图文、点赞评论、新闻、测试服务重启持久化、退出撤销和正式接口拒绝测试 token；不调用团队注册/登录接口，并只清理自己创建的测试数据。

## 服务器目录与进程

| 项目 | 位置 |
|---|---|
| 代码 | `/opt/tokentrail/backend` |
| Python 环境 | `/opt/tokentrail/venv` |
| RSS 工具 | `/opt/tokentrail/tools/news` |
| 配置 | `/etc/tokentrail-forum.env`，root 可读 |
| 数据库 | `/var/lib/tokentrail-forum/forum.sqlite3` |
| 图片 | `/var/lib/tokentrail-forum/media` |
| 本地备份 | `/var/lib/tokentrail-forum/backups` |
| 私有监听地址 | `127.0.0.1:8010` |
| 公网入口 | Nginx 443；80 仅用于证书验证和 HTTPS 跳转 |

`tokentrail-forum.service` 以独立的 `tokentrail` 系统用户运行，开机启动、异常自动重启，只允许写论坛数据目录。原有 8000 服务没有改动或重启。

## 定时任务

- `tokentrail-news.timer`：每小时采集英文 AI / Technology RSS，按 URL 主键 upsert 入库，保留近 30 天；单个来源失败保留其已有文章，健康检查报告失败来源。服务器网络可能限制 Hugging Face 或导致 Google AI 超时。
- `tokentrail-certbot.timer`：每天检查两次 IP 证书续期，成功后检查并重载 Nginx。IP 证书采用 Let's Encrypt shortlived profile，不能停用续期任务。[官方配置说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot/)
- `tokentrail-backup.timer`：每日 04:00 左右备份数据库一致性快照和已发布图片，保留最近 7 份。备份位于同一台服务器；服务器磁盘丢失时不能依靠它恢复，正式运营应另行复制到异地存储。

未发布的上传图片保留 7 天后清理；已发布图片不由该任务删除。没有使用好友/关注系统。接口、分页、幂等与错误约定见 `../docs/FORUM_API.md`。

## 常用维护命令

```bash
ssh mobile_back
sudo systemctl status tokentrail-forum --no-pager
sudo journalctl -u tokentrail-forum -n 50 --no-pager
sudo journalctl -u tokentrail-news -n 20 --no-pager
systemctl list-timers 'tokentrail-*' --no-pager
curl --fail https://43.140.212.47/forum-health

# 手动采集 / 备份
sudo systemctl start tokentrail-news
sudo systemctl start tokentrail-backup

# 证书续期演练，不替换生产证书
sudo /opt/tokentrail/certbot-venv/bin/certbot renew \
  --cert-name tokentrail-ip --dry-run --no-random-sleep-on-renew \
  --run-deploy-hooks --deploy-hook '/usr/sbin/nginx -t && /usr/bin/systemctl reload nginx'
```

更新代码只覆盖 `/opt/tokentrail/backend` 和 `/opt/tokentrail/tools/news`，随后重启 `tokentrail-forum`。不要覆盖 `/var/lib/tokentrail-forum`。更新 Nginx 前运行 `nginx -t`。`install-server.sh` 为首次安装脚本；它安装 HTTP 配置，HTTPS 启用使用 `enable-https.sh`，日常更新无需重复执行首次安装。

备份恢复：先停止论坛和新闻任务，保存当前数据目录，再将选定备份的 `forum.sqlite3` 和 `media/` 恢复到数据目录，移走旧的 `forum.sqlite3-wal`、`forum.sqlite3-shm`，修复属主为 `tokentrail:tokentrail`，最后启动服务并检查健康状态。恢复会回到备份时刻，不能直接覆盖运行中的数据库。

## 本地验证

Python 3.10+（本机是 3.14，也可）：

```bash
# 在仓库根目录执行。pytest 必须在 backend/ 里跑（它按 tokentrail_forum 包导入），
# 而端到端脚本必须在根目录跑（它按 backend/scripts/... 的路径找文件）。
python3 -m venv /tmp/tokentrail-forum-venv
/tmp/tokentrail-forum-venv/bin/pip install -r backend/requirements-test.txt

(cd backend && /tmp/tokentrail-forum-venv/bin/python -m pytest -q tests)     # 192 个
```

### `scripts/check_relay_local.py` 已删除（2026-09-30）

它原来自己起一个假上游和一个真实 uvicorn，走完「注册 → 转发 → 记账 → 认证边界 →
SSRF」全程。**它验的主链路（转发）整块删了**，所以脚本跟着删——留着的话它会在第一步
就断，而这类"跑不起来的验收脚本"最容易被当成环境问题忽略（`check_deployment.py`
就是这么烂了两年的，见下）。它给的两个环境变量
（`FORUM_RELAY_ALLOW_PRIVATE=1`、`FORUM_RELAY_SELF_HOSTS=`）**也一起作废**：
那两个校验点已经不在代码里了。

**记账这条线现在只有 pytest 覆盖**（`tests/test_usage.py`、`test_summary.py`、
`test_budgets.py`、`test_pricing.py`、`test_seasons.py`、`test_agent.py`）。


### 公网验收脚本（`scripts/check_account_deployment.py`）

> **2026-09-30 改：脚本本身还没跟上中转删除，README 这里按现状写。**
> 它的**模块开头注释**仍然写着「注册 → 读用量/赛季/预算 → **注册 relay key** →
> 换一条 key 余额不断 → 问一句智能体」，但代码里那两步**已经没有对应的请求**了
> （`/keys*` 在服务端不存在，打过去是 404）。同理，第 3 段那句
> 「**旧格式的 relay key** 打智能体是 401」现在是**恒真**的——`tt_` 前缀的凭据
> 一律不认，验不出"智能体挑凭据"这件事。**修脚本要动 `.py`，这次没动**，
> 记在这里免得下次照着注释以为那条链路验过。

**它实际做的事**（2026-02 新增，2026-09-30 复核）：

1. 注册 → 登录 → 查我 → 退出，顺带验错误码（用户名重复 409、密码错 401、没凭据 401）；
2. **只凭账号 token 就能读自己的账**：赛季状态、用量汇总、预算，三处的 `uid` 都等于
   `userId`——这是「身份是账号」的最小含义；
3. ~~注册 relay key、换一条 key 余额不断~~ **（已不存在）**；
4. 真的问一句智能体（花我们自己的 DeepSeek key），检查回答里没有 key、
   工具结果里没有别人的数字、它自己的用量记进了独立账本；
5. 论坛这条链路：图文上传（并校验取回的字节**完全一致**）、两个真账号共享同一个
   帖子池、发帖与评论的幂等、点赞两次只算一次、作者视角 `likedByMe=false`、
   取消点赞归零、新闻只含两类。

临时账号在结束时自动删掉（删除走 SSH：账号没有删除接口，App 里也没有「注销」这个功能）。

`scripts/fix_legacy_tables.py` 是一次性维护脚本，处理「老形状的表」——
`CREATE TABLE IF NOT EXISTS` 改不动已存在的表，`agent_usage` 和 `users` 这两张
不在归属列改名那条迁移里，所以要单独修；它带两条守卫（形状不对才动、
数据没价值才丢），不满足就什么都不做并打印出来。

### `scripts/check_deployment.py` 已删除（2026-02）

它原来负责**论坛这条线的公网验收**：图文上传、两个账号共享帖子池、图片跨账号字节一致、
发帖与点赞的幂等、重启持久性、退出撤销。它坏在账号搬家上——它注册账号、拿 token 都是
打团队那台机器的账号服务（`http://127.0.0.1:8000`），而鉴权早就只认我们自己的
`accounts` 表了，所以它从搬家那一刻起就断在第 4 步（用团队 token 读论坛拿到 401），
而**没有任何东西发现它坏了**：它要 root、要 `systemctl restart`、只能在服务器上跑，
不在 pytest 里，也不在 CI 里。

**它的覆盖面一条都没丢，全部并进了 `scripts/check_account_deployment.py` 第 7 段**：
图文上传（并校验取回的字节**完全一致**）、两个真账号共享同一个帖子池、发帖与评论的
幂等、点赞两次只算一次、作者视角 `likedByMe=false`、取消点赞归零、新闻只含两类。
用我们自己的账号之后，脚本里**再没有一处碰别人的服务**。

那条「重启持久性」没有跟着搬：这个脚本**故意不重启生产服务**（用户可能在用），
重启之后数据还在这件事由 pytest 覆盖——那里本来就会反复新建 app
（`tests/test_forum.py::test_persistence_across_restart`）。

实现参考：[FastAPI 文件上传](https://fastapi.tiangolo.com/tutorial/request-forms-and-files/)、[HTTPX 超时](https://www.python-httpx.org/advanced/timeouts/)。
