package com.mobilegroup20.modelpilot.ui.auth;

import com.mobilegroup20.modelpilot.R;

import java.util.Locale;

/**
 * 账号界面里那些**不碰 Android 的纯逻辑**：邮箱/验证码格式、服务端错误码翻译、重发冷却的解析。
 * <b>负责人：刘宗润。</b>
 *
 * <p><b>为什么单独抽一个类：</b>这些判断决定了用户看到哪一句话、界面往哪一步走，而它们
 * 只有靠单测才测得全——没人会为了「MAIL_NOT_CONFIGURED 到底显示什么」去把服务端配错一遍，
 * 也没人会为了试 CODE_ATTEMPTS 连输 5 次验证码。这里的每个分支在
 * `app/src/test/.../ui/auth/AccountInputTest.java` 里都有一条对应用例。
 *
 * <p><b>为什么不放在 ViewModel 里：</b>ViewModel 的静态方法也能测，但它是
 * `AndroidViewModel`，构造函数要 Application，测试里一碰就得跑 Robolectric；
 * 这里的类是 `final` + 私有构造 + 全静态，纯 JVM 就能跑。
 *
 * <p><b>返回的是字符串资源 id 而不是字符串：</b>`R.string.*` 只是 int，引用它不需要
 * Android 运行时，JVM 单测里照样能断言是哪一个资源；返回字符串反而把文案复制出了
 * `forum_strings.xml`，两边一改就对不上。
 */
public final class AccountInput {

    /** 服务端错误码里出现过的、需要走「去验证邮箱」这一步的信号。 */
    public static final String ACTION_NONE = "NONE";
    /** 界面该切到验证码那一段（或者留在那儿）。 */
    public static final String ACTION_VERIFY = "VERIFY";
    /** 服务端限流了，只能等冷却结束（通常是 60 秒）。 */
    public static final String ACTION_WAIT = "WAIT";
    /** 手里的验证码已经废了，**现在**就该让用户重发（过期）。 */
    public static final String ACTION_RESEND_NOW = "RESEND_NOW";
    /** 也要重发，但冷却还没走完（试错次数用尽，服务器多半还在 60 秒窗口里）。 */
    public static final String ACTION_RESEND_WAIT = "RESEND_WAIT";

    /** 验证码长度。服务端定死 6 位（`account_routes.py` 的正则），本地跟着它，别自己发明。 */
    public static final int CODE_LENGTH = 6;

    /**
     * 邮箱形状。**这不是 RFC 全集**，只拦「一眼就不是邮箱」的输入：
     * 服务端才是权威，本地校验太严会把合法地址（`a+b@x.co.uk`、带点带横线的域名）拦在门外，
     * 而那个错误用户根本没法绕过。
     */
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)+$");

    /** 6 位数字。服务端只认数字，带字母的输入连发都不用发。 */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^\\d{6}$");

    private AccountInput() { }

    /**
     * 邮箱本地校验。返回 0 表示看起来没问题，否则是要显示的字符串资源 id。
     *
     * <p>比正则多查三件事，都是抄错地址的典型形状：总长超 254（SMTP 上限，服务端会拒）、
     * 连续两个点（`a..b@x.com`）、顶级域只有 1 个字符（`a@b.c`——真实顶级域没有 1 个字母的）。
     */
    public static int emailProblem(String email) {
        String value = email == null ? "" : email.trim();
        if (value.isEmpty()) return R.string.account_email_empty;
        if (value.length() > 254 || !EMAIL.matcher(value).matches() || value.contains(".."))
            return R.string.account_email_invalid;
        int at = value.lastIndexOf('@');
        String local = value.substring(0, at), domain = value.substring(at + 1);
        // `.a@x.com` / `a.@x.com`：正则允许点出现在首尾，但那不是邮箱。
        if (local.startsWith(".") || local.endsWith(".")) return R.string.account_email_invalid;
        String top = domain.substring(domain.lastIndexOf('.') + 1);
        if (top.length() < 2) return R.string.account_email_invalid;
        for (int i = 0; i < top.length(); i++)
            if (!Character.isLetter(top.charAt(i))) return R.string.account_email_invalid;
        return 0;
    }

    /**
     * 验证码本地校验（6 位数字）。
     *
     * <p>先把所有空白删掉再数位数：验证码是从邮件里**复制粘贴**过来的，粘贴进来常常带
     * 空格或换行（"123 456"）。为这个形状让用户重打一遍很蠢，而服务端那边本来也只认数字。
     */
    public static int codeProblem(String code) {
        String value = code == null ? "" : code.replaceAll("\\s", "");
        if (value.isEmpty()) return R.string.account_code_empty;
        if (!CODE.matcher(value).matches()) return R.string.account_code_format;
        return 0;
    }

    /** 用户名的本地规则。返回 0 / 字符串资源 id。 */
    public static int usernameProblem(String username) {
        String value = username == null ? "" : username.trim();
        if (value.isEmpty()) return R.string.account_username_empty;
        // 只拦长度：服务端对「哪些字符算合法昵称」的规则我们不知道，本地猜一个更严的
        // 只会把合法用户名挡在门外，而那种错误用户没法自己解决。
        if (value.length() > 64) return R.string.account_username_long;
        return 0;
    }

    /** 注册密码的本地规则：必填 + 至少 8 位（`account_new` 里就是这么写的）。 */
    public static int passwordProblem(String password) {
        if (password == null || password.isEmpty()) return R.string.account_password_empty;
        if (password.length() < 8) return R.string.account_password_short;
        return 0;
    }

    /** 注册表单整体校验。按「邮箱 → 用户名 → 密码」的顺序报第一个不合格的。 */
    public static int registrationProblem(String email, String username, String password) {
        int problem = emailProblem(email);
        if (problem != 0) return problem;
        problem = usernameProblem(username);
        if (problem != 0) return problem;
        return passwordProblem(password);
    }

    /**
     * 登录表单整体校验。
     *
     * <p>登录的标识符**邮箱或用户名都行**：填了邮箱就用邮箱，否则用用户名
     * （服务端的 `identifier` 就是这个意思）。标识符一个都没填、或者密码没填，
     * 都会拦下来——但两句提示不一样，用户才知道该补哪一个框。
     *
     * <p>**故意不校验密码长度**：长度规则是注册那边的（服务端定 ≥8），拿它拦登录会在
     * 老账号或者将来改规则时把拿着正确密码的用户挡在门外，而他看到的是一句
     * 「密码至少 8 位」——完全指错方向。密码对不对由服务端的 401 说了算。
     */
    public static int loginProblem(String email, String username, String password) {
        boolean noEmail = email == null || email.trim().isEmpty();
        boolean noUsername = username == null || username.trim().isEmpty();
        // **两个框都空的这一句是 `account_empty`**：仪器测试点「登录」时断言的就是它，
        // 也是用户第一次打开对话框（什么都还没填）最该看到的那句话。
        if (noEmail && noUsername) return R.string.account_empty;
        if (password == null || password.isEmpty()) return R.string.account_password_empty;
        // 邮箱那栏非空就一定会被当成标识符发出去，所以它的形状要在这里先拦一道。
        // （只填了用户名时不校验邮箱——那时候邮箱框是空的。）
        if (!noEmail) return emailProblem(email);
        return 0;
    }

    /**
     * 登录时真正发出去的标识符：填了邮箱优先用邮箱，否则用用户名。
     *
     * <p>和 {@link #loginProblem} 必须用同一套优先级，否则会出现「校验的是邮箱、
     * 发出去的是用户名」这种两边对不上的情况。
     */
    public static String loginIdentifier(String email, String username) {
        String value = email == null ? "" : email.trim();
        return value.isEmpty() ? (username == null ? "" : username.trim()) : value;
    }

    /**
     * 一次失败响应（HTTP 状态码 + 错误体里的 `code`）翻译成「说哪句话 + 下一步做什么」。
     *
     * <p>`code` 先认：服务端把同一类失败拆得很细（`EMAIL_TAKEN` 和 `USERNAME_TAKEN` 都是 409，
     * 但用户要做的事完全不同——一个换邮箱一个换昵称），只看状态码就必然把它们混成一句
     * 「出错了」。`code` 认不出来时**才**退回状态码，而退回的依据是每个状态码在契约里
     * 唯一的那个含义（429 限流、401 凭证错、403 只有「邮箱没验证」一种）。
     *
     * <p>`httpStatus <= 0` 表示请求根本没到服务端（连接失败），单独给网络文案。
     *
     * @param httpStatus 服务端返回的状态码；连接层失败传 0
     * @param code       错误体里的 `code`，没有就传 null
     */
    public static Failure failure(int httpStatus, String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        switch (value) {
            case "EMAIL_TAKEN": return new Failure(R.string.account_email_taken, ACTION_NONE);
            case "USERNAME_TAKEN": return new Failure(R.string.account_taken, ACTION_NONE);
            case "EMAIL_INVALID": return new Failure(R.string.account_email_invalid, ACTION_NONE);
            case "EMAIL_REQUIRED": return new Failure(R.string.account_email_empty, ACTION_NONE);
            case "USERNAME_INVALID": return new Failure(R.string.account_username_invalid, ACTION_NONE);
            case "PASSWORD_INVALID": return new Failure(R.string.account_password_short, ACTION_NONE);
            // 服务端**有意**不区分「码错了」和「邮箱不存在」，文案也不能自作聪明地猜其中一个。
            case "CODE_INVALID": return new Failure(R.string.account_code_invalid, ACTION_NONE);
            case "CODE_EXPIRED": return new Failure(R.string.account_code_expired, ACTION_RESEND_NOW);
            case "CODE_ATTEMPTS": return new Failure(R.string.account_code_attempts, ACTION_RESEND_WAIT);
            case "EMAIL_UNVERIFIED": return new Failure(R.string.account_unverified, ACTION_VERIFY);
            case "MAIL_NOT_CONFIGURED":
                return new Failure(R.string.account_mail_not_configured, ACTION_NONE);
            // 配了发信服务、但这一封没发出去（网络抖、授权码过期）。服务端**已经回滚**
            // 了账号，所以这句话同样要说"没注册成功"。
            case "MAIL_FAILED": return new Failure(R.string.account_mail_failed, ACTION_NONE);
            case "RATE_LIMIT": return new Failure(R.string.account_rate_limit, ACTION_WAIT);
            case "CREDENTIALS": return new Failure(R.string.account_credentials_error, ACTION_NONE);
            default: break; // 认不出来，落到下面的状态码兜底
        }
        if (httpStatus <= 0) return new Failure(R.string.forum_network_error, ACTION_NONE);
        if (httpStatus == 429) return new Failure(R.string.account_rate_limit, ACTION_WAIT);
        if (httpStatus == 401) return new Failure(R.string.account_credentials_error, ACTION_NONE);
        // 契约里 403 只有 EMAIL_UNVERIFIED 一种；错误体读不出来时按它处理，比「出错了」有用得多。
        if (httpStatus == 403) return new Failure(R.string.account_unverified, ACTION_VERIFY);
        if (httpStatus == 400) return new Failure(R.string.account_request_rejected, ACTION_NONE);
        return new Failure(R.string.forum_request_error, ACTION_NONE);
    }

    /**
     * 解析 `Retry-After` 头，得到「还要等多少毫秒」。服务端限流时会给它（契约里是 60 秒）。
     *
     * <p>只认秒数形式。它也可能是 HTTP 日期（RFC 7231 允许两种），但那种要先跟本地时钟
     * 对时——手机时钟偏几分钟就会算出一个负数或者几小时。解析不出来就退回 60 秒，
     * 这是服务端的实际窗口，宁可多等一会儿也不要让用户点了立刻再撞一次 429。
     */
    public static long retryAfterMillis(String header) {
        long fallback = 60_000L;
        if (header == null) return fallback;
        String value = header.trim();
        if (value.isEmpty() || value.length() > 9) return fallback; // 日期串比 9 个字符长得多
        for (int i = 0; i < value.length(); i++)
            if (!Character.isDigit(value.charAt(i))) return fallback;
        try {
            long seconds = Long.parseLong(value);
            return seconds <= 0 ? fallback : seconds * 1000L;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 一次失败的翻译结果：`message` 是要显示的资源 id，`action` 是 {@code ACTION_*} 之一。 */
    public static final class Failure {
        public final int message;
        public final String action;

        public Failure(int message, String action) {
            this.message = message; this.action = action;
        }
    }
}
