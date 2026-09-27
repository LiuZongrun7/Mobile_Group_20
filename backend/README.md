# TokenTrail 论坛服务

已部署到 SSH 别名 `mobile_back` 对应的 Ubuntu 22.04 服务器。API 为 `https://43.140.212.47/api/`，健康检查为 `https://43.140.212.47/forum-health`。App 的 `forumBaseUrl` 已指向该地址。

服务使用 FastAPI、SQLite WAL 与本地持久图片存储。论坛数据与原 `aibox_backend` 的数据库分开；账号通过原后端的 `/api/users/me` 验证，App 的登录和退出仍调用原来的 `/api/login`、`/api/logout`。没有新的账号数据库、共享测试 token 或关闭证书校验的代码。App 提供已有账号的登录入口，不提供注册入口。

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

Python 3.10+：

```bash
python3 -m venv /tmp/tokentrail-forum-venv
/tmp/tokentrail-forum-venv/bin/pip install -r backend/requirements-test.txt
cd backend
/tmp/tokentrail-forum-venv/bin/python -m pytest -q tests
```

`scripts/check_deployment.py` 是显式的服务器联调脚本：通过原后端注册两个临时账号，使用公网 HTTPS 检查图文共享、点赞评论、幂等、重启持久性、新闻和退出后 token 撤销，并清理自己创建的帖子、图片及账号。它会重启论坛进程，运行前应避开用户使用时段；不会重启原账号后端。正常测试中没有密码/token 写入仓库或输出到日志。

实现参考：[FastAPI 文件上传](https://fastapi.tiangolo.com/tutorial/request-forms-and-files/)、[HTTPX 超时](https://www.python-httpx.org/advanced/timeouts/)。
