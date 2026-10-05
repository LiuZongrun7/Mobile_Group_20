"""发信：现在只有一个用途——把注册验证码送到用户邮箱。

## 为什么用标准库 `smtplib`，不引第三方

要发的只有一种邮件：几行纯文本 + 一个 6 位数字。`smtplib` + `email.message` 是
标准库，够用；引一个邮件框架等于给这台服务器多装一串依赖，而它们每一个都会坏。
（同一个服务器上另一个项目 `pokeragent` 是手写 SMTP 协议的，它连 `smtplib` 都没有——
那是 Node 没有内置 SMTP 的缘故，我们这边没这个问题。）

## 配置从哪来

`MODELPILOT_SMTP_HOST/PORT/USER/PASS/FROM`（见 `deploy/modelpilot.env.example`）。
**授权码只在服务器 env 里**：不进仓库、不进数据库、不进日志、不进任何响应。
健康检查只报「配没配」（`mailConfigured`），永远不报值。

## 没配发信时怎么办：**不假装成功**

`send_code` 抛 `MailNotConfigured`，路由把它变成 **503 `MAIL_NOT_CONFIGURED`**，
并且**不创建账号**。反过来（先建账号、发信失败再说）会留下一个谁也验证不了、
却占着那个邮箱的账号——用户重试只会看到"邮箱已被注册"，而他什么都没做成。

## 开发回显（`MODELPILOT_MAIL_DEV_ECHO`）

调试时不想真发信，可以让服务把验证码**直接回给客户端**——但这等于没有邮箱验证，
所以它**只在隔离的测试区服务上生效**（`MODELPILOT_ENABLE_TEST_SESSIONS=1`）。
正式服务上就算 env 里写了 `1`，`Settings` 也会把它按掉并记一条警告：
这条判据必须写在配置解析那一层，写在业务代码里迟早会有人绕过去。

## 端口为什么默认 994

163 的标准 SMTPS 端口是 465，但**这台机器上 465 在 TLS 握手阶段就被断开**
（同一个服务器上 `pokeragent` 实测过：465 连不上、994 能连能登录能投递），
而 163 在 994 上同样接受 SMTP over SSL。换邮件服务商时这个端口要一起改。
"""
from dataclasses import dataclass
from email.message import EmailMessage
from email.utils import formataddr, formatdate
import logging
import smtplib
import ssl

log = logging.getLogger("modelpilot.mail")

DEFAULT_PORT = 994
DEFAULT_HOST = "smtp.163.com"
SENDER_NAME = "ModelPilot"

# 两种用途的邮件内容**必须分开写**，不能共用一句"你的验证码是"：
# 收到一封"重设密码"的邮件的人，第一反应应该是"是不是有人在动我的号"，
# 而邮件里必须能回答这个问题（"不是你就忽略，密码不会被改"）。
# 一封不说自己在干什么的验证码邮件，看起来更像钓鱼。
TEMPLATES = {
    "register": ("【ModelPilot】注册验证码", """你的 ModelPilot 注册验证码是：

    {code}

{minutes} 分钟内有效，验证一次之后这条验证码就作废了。

如果这不是你本人的操作，忽略这封邮件就行 —— 没有这个验证码，
那个邮箱不会被注册，也不会有任何人能用它登录。

（本邮件由系统自动发出，请勿回复。）
"""),
    "reset": ("【ModelPilot】重设密码验证码", """有人在 ModelPilot 上用这个邮箱申请重设密码。验证码是：

    {code}

{minutes} 分钟内有效，用一次就作废。

**如果这不是你本人的操作，忽略这封邮件就行**：没有这个验证码，你的密码不会变，
账号也不会被任何人拿走。收到不止一封这种邮件时，建议顺手改一下密码。

（本邮件由系统自动发出，请勿回复。）
"""),
}


class MailNotConfigured(RuntimeError):
    """没配发信服务。调用方要**如实告诉用户**，不要吞掉。"""


def mask(address):
    """`someone@163.com` → `so***@163.com`。

    **日志里只出现这个**：邮箱是用户的个人信息，而验证码更是等于一次登录机会。
    排障要知道"发给谁了"，不需要知道完整地址。
    """
    text = (address or "").strip()
    if "@" not in text:
        return "***"
    local, _, domain = text.partition("@")
    if len(local) <= 2:
        return "*" * len(local) + "@" + domain
    return local[:2] + "***@" + domain


@dataclass(frozen=True)
class Mailer:
    host: str = ""
    port: int = DEFAULT_PORT
    user: str = ""
    password: str = ""
    sender: str = ""
    # 只回显不真发。**只有测试区服务能开到它**（判据在 `Settings`，见模块注释）。
    dev_echo: bool = False

    @property
    def configured(self):
        return bool(self.host and self.user and self.password)

    @property
    def from_address(self):
        return self.sender or self.user

    def send_code(self, email, code, minutes, purpose="register"):
        """把验证码发到 `email`。**发不出去要抛，不许静默返回成功。**

        `purpose` 决定邮件正文（注册 / 重设密码），见 {@link TEMPLATES}：
        认不出来的用途退回注册那份，**不编一封内容不明的邮件**。
        """
        if self.dev_echo:
            # 测试区：不真发，但要说清楚"这条码是怎么来的"，排障时一眼能看懂。
            log.warning("[mail] DEV_ECHO 开启，未真实发送：%s（验证码不回显进日志）", mask(email))
            return
        if not self.configured:
            raise MailNotConfigured("Mail service is not configured on this server")
        subject, body = TEMPLATES.get(purpose, TEMPLATES["register"])
        message = EmailMessage()
        message["From"] = formataddr((SENDER_NAME, self.from_address))
        message["To"] = email
        message["Subject"] = subject
        message["Date"] = formatdate(localtime=True)
        # 纯文本，不放假 HTML 版：一封"点这里"的富文本邮件看起来更像钓鱼，
        # 而我们只需要用户抄 6 个数字。
        message.set_content(body.format(code=code, minutes=minutes))
        context = ssl.create_default_context()
        try:
            with smtplib.SMTP_SSL(self.host, self.port, timeout=25, context=context) as server:
                server.login(self.user, self.password)
                server.send_message(message)
        except (OSError, smtplib.SMTPException) as failure:
            # **只记邮件服务商的原始报错**（它有排障价值），绝不记验证码。
            log.error("[mail] 发送失败 → %s：%s", mask(email), failure)
            raise
        log.info("[mail] 验证码已发往 %s", mask(email))
