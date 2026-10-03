# ModelPilot 论坛服务

已部署到 SSH 别名 `mobile_back` 对应的 Ubuntu 22.04 服务器。API 为 `https://43.140.212.47/api/`，健康检查为 `https://43.140.212.47/forum-health`。App 的 `forumBaseUrl` 已指向该地址。

服务使用 FastAPI、SQLite WAL 与本地持久图片存储。论坛数据与原 `aibox_backend` 的数据库分开。

**账号（2026-02 改）：这个后端有自己的账号表，不再打那台机器上的账号服务。**
`accounts` + `account_sessions` 两张表，接口是 `/api/account/register|login|me|logout`，
密码用 `hashlib.scrypt` 存（每用户独立盐，`scrypt$n$r$p$salt$hash`），
会话 token 形如 `tt_app_<43 字符>`、有效期 30 天、库里只存 sha256。
原因见 [`docs/CONTRACTS.md`](../docs/CONTRACTS.md) §5：**身份只能有一个**，
而更早的时候 relay key 自带身份，导致「换个 key 就换个人」。
（**2026-09-30 改**：relay key 那一条通道也删了，所以"身份只能有一个"从
"两个里选一个"变成了**字面意义上的只有一个**——账号 token。
App 现在同时提供登录和注册入口。）

原来的团队账号服务（`MODELPILOT_AUTH_URL` 那套）**已经彻底删掉**（2026-02 收尾）：
`auth.py` 里的 `TeamAuth` 类、`Settings` 上那三个字段、`MODELPILOT_AUTH_*` 三个环境变量
全部移除，健康检查里的 `authConfigured` 也去掉了。
要回滚得从 git 历史里取回 `TeamAuth`，**不是**打开几行注释就能回去的。

## 模块清单（`modelpilot_forum/`）

| 模块 | 管什么 |
|---|---|
| `app.py` | FastAPI 应用、`Settings`（env）、`/health`、论坛与新闻路由、请求体上限、启动时清废弃表 |
| `account_routes.py` | 账号四个接口 `/api/account/*` |
| `accounts.py` | 账号与会话的存储（scrypt 密码、sha256 token） |
| `agent.py` | 应用内智能体：调上游模型、工具循环、它自己那本账（`agent_usage`） |
| `agent_routes.py` | 智能体两个接口 `/api/agent/ask|status`（**服务端唯一一组业务路由**） |
| `agent_tools.py` | 只读工具的定义、分发、执行（现在**只剩两个**：`getForumHighlights` / `getMyThreads`） |
| `schema.py` | 库的形状：`drop_obsolete_tables`（启动时删老库里的废弃表，幂等）+ 「一天」的口径（`today_in_financial_timezone` / `day_millis_range` / `month_bounds`） |
| `store.py` | 论坛存储：帖子 / 回复 / 点赞 / 图片 / 游标分页 / 限流 / 新闻只读连接 |
| `news_job.py` | RSS 新闻采集任务 |
| `test_sessions.py` | 免账号测试身份（`tt_test_`，只在隔离的 `/test-api` 服务里开） |
| `auth.py` | 鉴权抽象（生产永远是查本库账号的 `TableAuth`，`verifier` 只是测试注入点） |
| `backup.py` | 数据库一致性快照与图片备份 |
| `usage.py` | **只剩一个函数 `split_usage`**：把各家响应的 usage 归一成四个桶 |

### 2026-09-30 那一轮删掉/改名的（旧文档里的名字按这里对）

**删掉的模块**：`api_routes.py`（用量/预算/价目/赛季五组接口 + 兜底转发）、
`usage_store.py`（用量账本读写）、`budgets.py`、`pricing.py`、`seed_pricing.py`、
`summary.py`、`seasons.py`，以及运维脚本 `scripts/fix_legacy_tables.py`
（它的活现在归 `schema.drop_obsolete_tables`：启动时直接 `DROP TABLE IF EXISTS`，
幂等，不再有"先回填再删"那种顺序要求）。

**改名的文件**：`relay.py` → **`usage.py`**（只留下 `split_usage`，其余随转发删除）、
`relay_routes.py` → `api_routes.py` → **`agent_routes.py`**（现在只装智能体）。
在旧文档里看到这三个名字时按这个对应关系读。

**删掉的表**：`relay_usage`、`season_balances`、`season_settlements`、`budgets`、
`pricing_rates`（连同更早删的 `relay_keys` / `relay_secrets`）。
它们由 `schema.py` 的 `OBSOLETE_TABLES` 列表在启动时删掉——**生产库上真的会执行
`DROP TABLE`**，所以老库里那些没有代码读、但里面有数据的表不会一直留着。
`agent_usage` 是**唯一**还按账号记数的表。

## 服务端 API

现在只有三组，契约见 [`../docs/SERVER_API.md`](../docs/SERVER_API.md)：

| 前缀 | 认证 | 干什么 |
|---|---|---|
| `/api/account/*` | ——（登录接口自己） | 注册 / 登录 / 查我 / 退出。**永远注册，没有开关** |
| `/api/forum/*` | 账号 token（图片除外） | 论坛与新闻（见 `../docs/FORUM_API.md`） |
| `/api/agent/*` | 账号 token | 应用内智能体（**独立 router、独立开关**） |

外加一个不需要身份的 `GET /health`。

> **2026-09-30：整条记账链删除。** 它曾经是 `/api/relay/usage*`、`/budgets*`、
> `/pricing*`、`/season*` 四组接口，读的是服务端上的用量、预算上限、价目表和赛季余额。
> 删它的理由：**没有生产者，也没有消费者**。第一刀删掉中转之后，没有任何代码会往
> 用量表里写新记录；App 侧也早就不打这些接口了。**空转的接口比没有接口更糟**——
> 它会让人以为账已经在记了。Insights 要等「后端替用户调模型」那条路写出来之后
> **重新设计**，别照抄删掉的那套（那套是给"转发旁路抽取的用量"设计的）。
>
> ~~`relay.py`（转发 + SSRF 防护 + 流式解析）、`relay_routes.py` 的 `/keys*` 与
> `{path:path}` 兜底、`relay_store.py` 的 `relay_keys` / `relay_secrets` 两张表~~
> **更早那一刀已经删了**，对应改名见上面「模块清单」。

**URL 前缀里的历史字面量只剩 `/api/agent`**：它 2026-09-30 之前叫 `/api/relay/agent`，
记账接口删掉之后 `relay` 就没有任何意义了（前缀下只剩智能体），所以一起改掉。
`/api/relay/*` 整个前缀**不存在了**，打过去 404。

### 应用内智能体（`MODELPILOT_ENABLE_AGENT`）

`/api/agent/ask|status`：用户问一句，服务端用**我们自己的** DeepSeek key 调模型，
模型能调 **2 个**只读工具（`getForumHighlights`、`getMyThreads`）。
契约见 [`../docs/SERVER_API.md`](../docs/SERVER_API.md)。

三条边界是结构性的，不是约定：

- **凭据是账号 token，也只可能是账号 token**（2026-09-30 改：relay key 已经不存在了）。
- **账本分开**（`agent_usage`）。智能体花的是我们的钱，如果算进用户的用量，
  「我这周怎么花了这么多」的答案就被问题本身污染了。记账链删掉之后，
  服务端只剩这一本账，**新的记账链设计出来时这一条要照旧**。
- **`/status` 不报钱**：价目表随记账链删了，算不出金额就**不能说 0**，
  所以改成 `"costReporting": "unavailable"`，只报 token（`ownUsageThisMonth`）。

`MODELPILOT_AGENT_KEY` 从 `/etc/modelpilot-agent.env` 读（权限 `640 root:tokentrail`），
**不进数据库、不进日志、不进任何响应**；健康检查只报 `agentConfigured: true/false`。
没配 key 时 `/agent/ask` 返回 503 并说明原因，**不会拿别的东西凑一个答案**。

**默认关闭。** `MODELPILOT_ENABLE_AGENT` 不设或为 0 时 `/api/agent/*` 不注册（404）。
启用需要把 `deploy/modelpilot-agent.env.example` 装到 `/etc/modelpilot-agent.env`
（systemd 单元用 `EnvironmentFile=-` 引它，文件不存在也不影响启动）。

> **env 前缀 2026-09-30 从 `FORUM_*` 改成 `MODELPILOT_*`，老名字不再读。**
> 同一轮作废的还有 `MODELPILOT_ENABLE_RELAY`（守的接口删了）、
> `MODELPILOT_PRICING_SEED`（价目表删了）、以及 `MODELPILOT_RELAY_ALLOWED_HOSTS` /
> `_ALLOW_PRIVATE` / `_SELF_HOSTS` / `_REQUESTS_PER_MINUTE`（那是给"转发到用户自己的
> 上游"做 SSRF 防护的，校验代码随转发一起删了）。env 文件里留着不报错，但也不生效。
> **别以为填了白名单就更安全。**

nginx 需要一条独立的 `/api/agent/` location，配置见 `deploy/nginx-https.conf`。
它的超时和缓冲区设置和论坛那一段**故意不同**：一次问答要跑工具循环（可能好几轮模型调用），
论坛那段的 `45s` 会在正常问答中途掐断连接，而症状是「问着问着就断了」，
很容易被误判成网络问题。~~而且必须 `proxy_buffering off`，否则流式的打字机效果会消失~~
——**2026-09-30 改**：`proxy_buffering off` 是转发流式响应（SSE 透传）时必需的，
现在服务端返回的都是普通 JSON，不再需要它；nginx 那一段**还没改**（改部署要重新部署）。

## 免账号测试区

调试 APK 的论坛页面提供“免账号测试”按钮，不需要账号模块、用户名或密码。每台设备获得一个随机临时身份，有效期 24 小时，支持新闻、图文发布、点赞和评论；退出测试模式会撤销该临时身份。临时会话加密保存在手机上，重启 App 后仍进入测试区。身份到期后再次点击入口即可获得新测试身份。

测试接口为 `https://43.140.212.47/test-api/`，由 `modelpilot-forum-test.service` 在 `127.0.0.1:8011` 提供。数据位于 `/var/lib/modelpilot-forum-test`，配置为 `/etc/modelpilot-forum-test.env`，与正式帖子、图片、点赞和评论分开。两台设备的测试帖子在同一个测试池中。正式 `/api/` 接口拒绝测试 token，测试身份不会写入团队账号库。Release APK 隐藏免账号入口并拒绝恢复测试身份。

新闻采集任务将仅含新闻和采集状态的 SQLite 快照原子导出到 `/var/lib/modelpilot-forum/news-readonly.sqlite3`，测试服务只读该快照；它不读取正式帖子表。测试区不在正式数据备份中。关闭测试服务可运行 `sudo systemctl disable --now modelpilot-forum-test`，正式论坛继续运行。

首次启用：上传新版 backend 代码后，以 root 执行 `deploy/install-test-area.sh`。该脚本生成只读新闻快照、启动测试服务并检查、重载 Nginx。`scripts/check_test_area.py` 使用两份临时身份验证公网图文、点赞评论、新闻、测试服务重启持久化、退出撤销和正式接口拒绝测试 token；不调用团队注册/登录接口，并只清理自己创建的测试数据。

## 服务器目录与进程

| 项目 | 位置 |
|---|---|
| 代码 | `/opt/modelpilot/backend` |
| Python 环境 | `/opt/modelpilot/venv` |
| RSS 工具 | `/opt/modelpilot/tools/news` |
| 配置 | `/etc/modelpilot-forum.env`，root 可读；智能体另用 `/etc/modelpilot-agent.env` |
| 数据库 | `/var/lib/modelpilot-forum/modelpilot.sqlite3` |
| 图片 | `/var/lib/modelpilot-forum/media` |
| 本地备份 | `/var/lib/modelpilot-forum/backups` |
| 私有监听地址 | `127.0.0.1:8010` |
| 公网入口 | Nginx 443；80 仅用于证书验证和 HTTPS 跳转 |

`modelpilot-forum.service` 以独立的 `tokentrail` 系统用户运行，开机启动、异常自动重启，只允许写论坛数据目录。原有 8000 服务没有改动或重启。

> **服务名 / 路径 / 库文件名都是 2026-09-30 改的**：`tokentrail-forum.service` 等
> → `modelpilot-*.service`，`/opt/tokentrail` → `/opt/modelpilot`，
> `/var/lib/tokentrail-forum` → `/var/lib/modelpilot-forum`，
> `forum.sqlite3` → `modelpilot.sqlite3`，`/etc/tokentrail-forum.env` →
> `/etc/modelpilot-forum.env`、`/etc/tokentrail-forum-relay.env` → `/etc/modelpilot-agent.env`。
> **系统用户仍然叫 `tokentrail`**（没跟着改，改它要动属主和一大堆文件权限）。

## 定时任务

- `modelpilot-news.timer`：每小时采集英文 AI / Technology RSS，按 URL 主键 upsert 入库，保留近 30 天；单个来源失败保留其已有文章，健康检查报告失败来源。服务器网络可能限制 Hugging Face 或导致 Google AI 超时。
- `modelpilot-certbot.timer`：每天检查两次 IP 证书续期，成功后检查并重载 Nginx。IP 证书采用 Let's Encrypt shortlived profile，不能停用续期任务。[官方配置说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot/)
- `modelpilot-backup.timer`：每日 04:00 左右备份数据库一致性快照和已发布图片，保留最近 7 份。备份位于同一台服务器；服务器磁盘丢失时不能依靠它恢复，正式运营应另行复制到异地存储。

未发布的上传图片保留 7 天后清理；已发布图片不由该任务删除。没有使用好友/关注系统。接口、分页、幂等与错误约定见 `../docs/FORUM_API.md`。

## 常用维护命令

```bash
ssh mobile_back
sudo systemctl status modelpilot-forum --no-pager
sudo journalctl -u modelpilot-forum -n 50 --no-pager
sudo journalctl -u modelpilot-news -n 20 --no-pager
systemctl list-timers 'modelpilot-*' --no-pager
curl --fail https://43.140.212.47/forum-health

# 手动采集 / 备份
sudo systemctl start modelpilot-news
sudo systemctl start modelpilot-backup

# 证书续期演练，不替换生产证书
sudo /opt/modelpilot/certbot-venv/bin/certbot renew \
  --cert-name tokentrail-ip --dry-run --no-random-sleep-on-renew \
  --run-deploy-hooks --deploy-hook '/usr/sbin/nginx -t && /usr/bin/systemctl reload nginx'
```

更新代码只覆盖 `/opt/modelpilot/backend` 和 `/opt/modelpilot/tools/news`，随后重启 `modelpilot-forum`。不要覆盖 `/var/lib/modelpilot-forum`。更新 Nginx 前运行 `nginx -t`。`install-server.sh` 为首次安装脚本；它安装 HTTP 配置，HTTPS 启用使用 `enable-https.sh`，日常更新无需重复执行首次安装。

备份恢复：先停止论坛和新闻任务，保存当前数据目录，再将选定备份的 `modelpilot.sqlite3` 和 `media/` 恢复到数据目录，移走旧的 `modelpilot.sqlite3-wal`、`modelpilot.sqlite3-shm`，修复属主为 `tokentrail:tokentrail`，最后启动服务并检查健康状态。恢复会回到备份时刻，不能直接覆盖运行中的数据库。

## 本地验证

Python 3.10+（本机是 3.14，也可）：

```bash
# 在仓库根目录执行。pytest 必须在 backend/ 里跑（它按 modelpilot_forum 包导入），
# 而端到端脚本必须在根目录跑（它按 backend/scripts/... 的路径找文件）。
python3 -m venv /tmp/modelpilot-forum-venv
/tmp/modelpilot-forum-venv/bin/pip install -r backend/requirements-test.txt

(cd backend && /tmp/modelpilot-forum-venv/bin/python -m pytest -q tests)     # 76 个
```

测试文件与功能的对应：`test_accounts.py`（账号与会话）、`test_forum.py`（帖子 / 图片 /
幂等 / 分页）、`test_official.py`（官方帖 = 新闻）、`test_agent.py`（智能体、工具循环、
限流、独立账本）、`test_schema.py`（废弃表真的被删掉、「一天」的口径）、
`test_test_sessions.py`（测试身份）。

### `scripts/check_relay_local.py` 已删除（2026-09-30）

它原来自己起一个假上游和一个真实 uvicorn，走完「注册 → 转发 → 记账 → 认证边界 →
SSRF」全程。**它验的主链路（转发）整块删了**，所以脚本跟着删——留着的话它会在第一步
就断，而这类"跑不起来的验收脚本"最容易被当成环境问题忽略（`check_deployment.py`
就是这么烂了两年的，见下）。

它原来给的两个开关（`MODELPILOT_RELAY_ALLOW_PRIVATE=1`、`MODELPILOT_RELAY_SELF_HOSTS=`）
**也一起作废**：那两个校验点已经不在代码里了。

**它的覆盖面没有丢**：账号、论坛、智能体三条线现在由 pytest（上面那批）
和下面的公网验收脚本盯着。~~记账那条线的 pytest（`test_usage.py` / `test_summary.py` /
`test_budgets.py` / `test_pricing.py` / `test_seasons.py`）~~ 随记账链一起删了。

### `scripts/fix_legacy_tables.py` 已删除（2026-09-30）

它是一次性维护脚本，处理「老形状的表」——`CREATE TABLE IF NOT EXISTS` 改不动已存在的表，
`agent_usage` 和 `users` 这两张不在归属列改名那条迁移里，所以要单独修；它带两条守卫
（形状不对才动、数据没价值才丢）。**现在没有"改形状"这回事了**：废弃的表一律直接
`DROP`，由 `schema.drop_obsolete_tables` 在每次启动时幂等地做（`OBSOLETE_TABLES`
就是那份名单）。要恢复它得从 git 历史里取。

### 公网验收脚本（`scripts/check_account_deployment.py`）—— **现在唯一的端到端验收**

> **2026-09-30 改：脚本本体的请求已经跟上记账链删除，但模块开头注释还没跟上。**
> 它的**模块开头注释**仍然写着「注册 → 读用量/赛季/预算 → **注册 relay key** →
> 换一条 key 余额不断 → 问一句智能体」，代码里那几步**已经没有对应的请求**了
> （`/keys*` 和 `/api/relay/*` 在服务端都不存在，打过去是 404）。
> **修注释要动 `.py`，这次没动**，记在这里免得下次照着注释以为那条链路验过。

**它实际做的事**（2026-02 新增，2026-09-30 复核）：

1. 注册 → 登录 → 查我 → 退出，顺带验错误码（用户名重复 409、密码错 401、没凭据 401）；
2. **只凭账号 token 就能读自己的东西**——`uid` 等于 `userId`，这是「身份是账号」的最小含义；
3. 真的问一句智能体（花我们自己的 DeepSeek key），检查回答里没有 key、
   工具结果里没有别人的数字、它自己的用量记进了独立账本（`agent_usage`）；
4. 论坛这条链路：图文上传（并校验取回的字节**完全一致**）、两个真账号共享同一个
   帖子池、发帖与评论的幂等、点赞两次只算一次、作者视角 `likedByMe=false`、
   取消点赞归零、新闻只含两类。

临时账号在结束时自动删掉（删除走 SSH：账号没有删除接口，App 里也没有「注销」这个功能）。
清理只碰 `agent_usage` / `account_sessions` / `images` / `idempotency` / `write_events`
这几张表——用量、预算、价目、赛季那几张**已经不存在了**，
对着它们写 `DELETE` 会直接报 `no such column`，而那一刻正好是清理阶段。

`MODELPILOT_ENABLE_AGENT` 没开的服务器上智能体那一段会**明确说跳过了**，不是静默通过。

### `scripts/check_deployment.py` 已删除（2026-02）

它原来负责**论坛这条线的公网验收**：图文上传、两个账号共享帖子池、图片跨账号字节一致、
发帖与点赞的幂等、重启持久性、退出撤销。它坏在账号搬家上——它注册账号、拿 token 都是
打团队那台机器的账号服务（`http://127.0.0.1:8000`），而鉴权早就只认我们自己的
`accounts` 表了，所以它从搬家那一刻起就断在第 4 步（用团队 token 读论坛拿到 401），
而**没有任何东西发现它坏了**：它要 root、要 `systemctl restart`、只能在服务器上跑，
不在 pytest 里，也不在 CI 里。

**它的覆盖面一条都没丢，全部并进了 `scripts/check_account_deployment.py` 第 4 段**：
图文上传（并校验取回的字节**完全一致**）、两个真账号共享同一个帖子池、发帖与评论的
幂等、点赞两次只算一次、作者视角 `likedByMe=false`、取消点赞归零、新闻只含两类。
用我们自己的账号之后，脚本里**再没有一处碰别人的服务**。

那条「重启持久性」没有跟着搬：这个脚本**故意不重启生产服务**（用户可能在用），
重启之后数据还在这件事由 pytest 覆盖——那里本来就会反复新建 app
（`tests/test_forum.py::test_persistence_across_restart`）。

实现参考：[FastAPI 文件上传](https://fastapi.tiangolo.com/tutorial/request-forms-and-files/)、[HTTPX 超时](https://www.python-httpx.org/advanced/timeouts/)。
