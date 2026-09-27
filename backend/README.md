# TokenTrail 论坛服务

已部署到 SSH 别名 `mobile_back` 对应的 Ubuntu 22.04 服务器。API 为 `https://43.140.212.47/api/`，健康检查为 `https://43.140.212.47/forum-health`。App 的 `forumBaseUrl` 已指向该地址。

服务使用 FastAPI、SQLite WAL 与本地持久图片存储。论坛数据与原 `aibox_backend` 的数据库分开。

**账号（2026-02 改）：这个后端有自己的账号表，不再打那台机器上的账号服务。**
`accounts` + `account_sessions` 两张表，接口是 `/api/account/register|login|me|logout`，
密码用 `hashlib.scrypt` 存（每用户独立盐，`scrypt$n$r$p$salt$hash`），
会话 token 形如 `tt_app_<43 字符>`、有效期 30 天、库里只存 sha256。
原因见 [`docs/CONTRACTS.md`](../docs/CONTRACTS.md) §5：**身份只能有一个**，
而原来 relay key 自带身份，导致「换个 key 就换个人」。改动之后
**用量、赛季余额、预算、智能体记账的归属列都是 `user_id`**，relay key 只是挂在
账号下面的一条通道。App 现在同时提供登录和注册入口。

原来的团队账号服务（`FORUM_AUTH_URL` 那套）**已经彻底删掉**（2026-02 收尾）：
`auth.py` 里的 `TeamAuth` 类、`Settings` 上那三个字段、`FORUM_AUTH_*` 三个环境变量
全部移除，健康检查里的 `authConfigured` 也去掉了——它那时报的是「外部账号服务配没配」，
而那个问题已经不存在。现在鉴权只有一条路：查本库的 `accounts` / `account_sessions`。
要回滚得从 git 历史里取回 `TeamAuth`，**不是**打开几行注释就能回去的。

## API 中转服务（2026-09-27 新增）

论坛之外，同一进程还提供 **API 中转**：用户在 App 里填自己的 `上游 URL + 上游 API key`，
服务端给**当前登录的账号**发一个（或用户自定义一个）**relay key**；把 cc-switch 的供应商 base URL 指向
`https://43.140.212.47/api/relay`、key 填 relay key 之后，请求原样转发到上游，
**响应里的 usage 顺手落库**。接口契约见 [`../docs/RELAY_API.md`](../docs/RELAY_API.md)。

它带来的取舍必须写清楚，别只看好处：

- **用户的真上游 key 落在服务器上。** 这推翻了 `docs/DATA_SOURCES.md` §3 原来
  「凭据绝不上传服务端」那条决定，反转理由记在 `docs/DATA_SOURCES.md` §5。
  **启用之前必须先堵住服务器上对公网开放的其它端口**——现在 8000（账号后端）
  是 `0.0.0.0` 且 `ufw` 未启用，上面放全班 API key 的性质和只放论坛数据完全不同。
- **不再需要解析账单文件。** `cacheWrite` 这个桶原来 OpenAI 和 DeepSeek 都拿不到，
  代理路径下响应里就有；拿到的是逐次调用而不是时间桶，所以有会话信息。
- **不做协议转换。** 请求体和响应体原样穿过，上游格式由用户在 cc-switch 里自己选对。

### 应用内智能体（`FORUM_ENABLE_AGENT`，和中转分开的开关）

同一个进程还提供 `/api/relay/agent/ask|status`：用户问一句，服务端用**我们自己的**
DeepSeek key 调模型，模型可以调 5 个只读工具（用量汇总、预算、成本对比、
论坛亮点、我发过的帖子）。契约见 [`../docs/RELAY_API.md`](../docs/RELAY_API.md)。

两条边界是结构性的，不是约定：

- **凭据是账号 token，不是 relay key。** 用户可能压根没配过中转，但他一定登录过 App；
  而 relay key 只该出现在中转那条路上。所以智能体的路由和中转共用一个进程，
  但**鉴权是两套**。
- **账本分开**（`agent_usage` vs `relay_usage`）。智能体花的是我们的钱，
  如果算进用户的编码用量，「我这周怎么花了这么多」的答案就被问题本身污染了。
  两张表也不在一处，`agent_usage` 永远不进日汇总、预算和结算。

`FORUM_AGENT_KEY` 从 `/etc/tokentrail-forum-relay.env` 读（权限 `640 root:tokentrail`），
**不进数据库、不进日志、不进任何响应**；健康检查只报 `agentConfigured: true/false`。

**默认关闭。** `FORUM_ENABLE_RELAY` 不设或为 0 时 `/api/relay/*` 返回 404，行为和以前完全一样。
启用需要把 `deploy/forum-relay.env.example` 装到 `/etc/tokentrail-forum-relay.env`
（systemd 单元用 `EnvironmentFile=-` 引它，文件不存在也不影响启动），并且
**`FORUM_RELAY_ALLOWED_HOSTS` 必须填**，否则只剩「拒绝私网地址」那一层校验。

nginx 需要一条独立的 `/api/relay/` location，配置见 `deploy/nginx-https.conf`。
它的超时和缓冲区设置和论坛那一段**故意不同**：长上下文请求动辄几分钟，45s 会在正常
对话中途掐断连接；而且必须 `proxy_buffering off`，否则流式的打字机效果会消失。


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

(cd backend && /tmp/tokentrail-forum-venv/bin/python -m pytest -q tests)     # 49 个
/tmp/tokentrail-forum-venv/bin/python backend/scripts/check_relay_local.py  # 中转端到端
```

`check_relay_local.py` 自己起一个假上游和一个真实 uvicorn，走完
「注册 → 转发 → 记账 → 认证边界 → SSRF」全程。它给的两个环境变量
（`FORUM_RELAY_ALLOW_PRIVATE=1`、`FORUM_RELAY_SELF_HOSTS=`）**只为本地能跑**，
生产是反的。脚本里显式禁用了代理：macOS 的系统代理是系统级设置，`NO_PROXY`
对它无效，本机验收打 127.0.0.1 会被代理用 502 回掉，看起来像服务坏了。


`scripts/check_account_deployment.py` 是**账号这条线**的公网验收脚本（2026-02 新增）：
对着真正在跑的那台服务器走完注册 → 登录 → 读用量/赛季/预算 → 注册 relay key →
换一条 key 余额不断 → 问一句智能体，并检查**智能体只认账号 token**、
relay key 打过去是 401、回答里没有我们的 key。临时账号在结束时自动删掉
（删除走 SSH：账号没有删除接口，App 里也没有「注销」这个功能）。
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
