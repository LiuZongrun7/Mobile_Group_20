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
 *
 * <p>2026-10 加了「忘记密码」和「修改密码」两条通道之后，这里多了两类东西：
 * 那两张表单的整体校验（{@link #resetPasswordProblem} / {@link #passwordChangeProblem}），
 * 以及 {@link Failure} 里的**语境**（{@link Failure#OP_PASSWORD_CHANGE} 等）。语境是必要的：
 * 同一个 `CREDENTIALS` 在登录时说「账号或密码不对」、在改密码时说「当前密码不对」，
 * 两句话不能混——混了之后正在登录的用户会以为自己的账号出了问题。
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

    /**
     * 服务端接受的密码上限。
     *
     * <p>契约里写死了「新密码 8~200 位」。之所以把这个数抄到本地：超长密码发出去必然
     * 换来一句 `PASSWORD_INVALID`，而用户看到的是「密码至少 8 位」——他明明填了 201 位，
     * 这句话把他指向完全相反的方向（去加长密码）。所以本地先拦下来，并且**说上限**。
     */
    public static final int PASSWORD_MAX_LENGTH = 200;

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

    /** 注册密码的本地规则：必填 + 8~200 位（`account_new` 里就是这么写的，上限来自契约）。 */
    public static int passwordProblem(String password) {
        if (password == null || password.isEmpty()) return R.string.account_password_empty;
        if (password.length() < 8) return R.string.account_password_short;
        // 上限这一条**必须单独一句话**：说「至少 8 位」会把一个填了 201 位的用户指向
        // 完全相反的方向（他会去加长）。上限是契约里的硬约束，本地拦掉省一次必然失败的往返。
        if (password.length() > PASSWORD_MAX_LENGTH) return R.string.account_password_long;
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

    // ------------------------------------------------------ 改密码 / 重设密码的表单校验

    /**
     * 已登录时「修改密码」表单的校验：当前密码必填（**不查长度**，它是老密码）、
     * 新密码走注册那套规则、两次新密码必须一致。
     *
     * <p>顺序是「当前密码 → 新密码 → 确认」：用户是从上往下填的，先说最上面那个没填的，
     * 他不用回头找。返回 0 表示可以发请求。
     */
    public static int passwordChangeProblem(String currentPassword, String newPassword, String confirm) {
        if (currentPassword == null || currentPassword.isEmpty())
            return R.string.account_current_password_empty;
        int problem = passwordProblem(newPassword);
        if (problem != 0) return problem;
        if (!newPassword.equals(confirm)) return R.string.account_password_mismatch;
        return 0;
    }

    /**
     * 忘记密码表单的校验：验证码（复用注册那套）+ 新密码 + 两次一致。
     *
     * <p>和 {@link #passwordChangeProblem} 分开是因为**这里没有「当前密码」这个框**：
     * 用户就是不知道当前密码才来的，多一个必填框会让他以为「还是得先想起旧密码」。
     *
     * <p>返回的对象里带一个 `emailMissing`：邮箱为空是**唯一一个用户没法在本地修好**的
     * 失败（他得先回去填），界面要据此换掉那句「验证码已发往 …」——不能显示一个空地址，
     * 也不能让用户以为码发出去了。
     */
    public static ResetProblem resetPasswordProblem(String email, String code,
                                                    String newPassword, String confirm) {
        if (email == null || email.trim().isEmpty())
            return new ResetProblem(R.string.account_email_empty, true);
        int problem = codeProblem(code);
        if (problem != 0) return new ResetProblem(problem, false);
        problem = passwordProblem(newPassword);
        if (problem != 0) return new ResetProblem(problem, false);
        // 两次不一致是**唯一一个能在本地判出来**的重设失败。其他都得问服务端：
        // 码对不对、这个邮箱存不存在，服务端有意不告诉我们（防枚举）。
        if (!newPassword.equals(confirm)) return new ResetProblem(R.string.account_password_mismatch, false);
        return new ResetProblem(0, false);
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
     * <p><b>这个两参版本固定按「登录/注册」语境翻译。</b>改密码失败时用
     * {@link Failure#passwordChange(int, String)}：同一个 `CREDENTIALS`（或者说同一个光秃秃的
     * 401）在两个语境下要说两句不同的话，而这两句话**不能混**——
     * 登录时是「账号或密码不对」，改密码时是「当前密码不对」（账号明明是对的）。
     *
     * @param httpStatus 服务端返回的状态码；连接层失败传 0
     * @param code       错误体里的 `code`，没有就传 null
     */
    public static Failure failure(int httpStatus, String code) {
        return failureFor(Failure.OP_SIGN_IN, httpStatus, code);
    }

    /**
     * 真正干活的那一份。`operation` 只影响**一个**分支：`CREDENTIALS` 和
     * 「401 但错误体没解析出来」——因为 `CREDENTIALS` 这个 code 同时被登录和改密码用，
     * 而用户要做的事正好相反（登录那边是「这个账号不对」，改密码这边是「账号是对的，
     * 只是当前密码打错了」）。
     *
     * <p>故意**不做**「一个 code 一张表、每个调用方各查一次」的结构：那样每加一个错误码
     * 都要在 N 个地方补一遍，漏掉的那个地方就会退化成笼统的「出错了」——这正是这个类
     * 存在的理由。
     */
    private static Failure failureFor(String operation, int httpStatus, String code) {
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
            // 发信失败的两条：注册那边账号会被回滚，忘记密码那边没有任何账号被动过，
            // 所以文案也不一样（一个必须说「没注册成功」，一个说「没发出去」）。
            case "MAIL_NOT_CONFIGURED":
                return new Failure(mailNotConfigured(operation), ACTION_NONE);
            case "MAIL_FAILED": return new Failure(mailFailed(operation), ACTION_NONE);
            case "RATE_LIMIT": return new Failure(R.string.account_rate_limit, ACTION_WAIT);
            case "CREDENTIALS": return new Failure(credentials(operation), ACTION_NONE);
            default: break; // 认不出来，落到下面的状态码兜底
        }
        if (httpStatus <= 0) return new Failure(R.string.forum_network_error, ACTION_NONE);
        if (httpStatus == 429) return new Failure(R.string.account_rate_limit, ACTION_WAIT);
        // 401 没有 code：登录那边按「凭证不对」兜底；改密码那边**不能**这么说——
        // 用户刚刚还在用这个账号，说「账号或密码不对」会让他以为账号出了问题。
        // 契约里改密码的 401 只有两种：当前密码不对、会话过期（这个 App 自己会发现）。
        if (httpStatus == 401) return new Failure(credentials(operation), ACTION_NONE);
        // 契约里 403 只有 EMAIL_UNVERIFIED 一种；错误体读不出来时按它处理，比「出错了」有用得多。
        if (httpStatus == 403) return new Failure(R.string.account_unverified, ACTION_VERIFY);
        if (httpStatus == 400) return new Failure(R.string.account_request_rejected, ACTION_NONE);
        return new Failure(R.string.forum_request_error, ACTION_NONE);
    }

    /**
     * 「凭证不对」这句话按语境分流。
     *
     * <p>同一个 `CREDENTIALS`（以及光秃秃的 401）在两个语境下是两件事：
     * 登录时用户可能连账号都打错了（说「邮箱/用户名或密码不对」是对的）；改密码时账号
     * 一定是对的——他正登录着——只有当前密码那栏错了，说成「账号或密码不对」等于
     * 让用户怀疑自己的账号被人改了。
     */
    private static int credentials(String operation) {
        return Failure.OP_PASSWORD_CHANGE.equals(operation)
                ? R.string.account_current_password_wrong : R.string.account_credentials_error;
    }

    /** 503 MAIL_NOT_CONFIGURED 在两个语境下的两句文案，理由见 {@link #failureFor}。 */
    private static int mailNotConfigured(String operation) {
        return Failure.OP_FORGOT_PASSWORD.equals(operation)
                ? R.string.account_reset_mail_not_configured : R.string.account_mail_not_configured;
    }

    /** 503 MAIL_FAILED，同上。 */
    private static int mailFailed(String operation) {
        return Failure.OP_FORGOT_PASSWORD.equals(operation)
                ? R.string.account_reset_mail_failed : R.string.account_mail_failed;
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
        /**
         * 语境：登录/注册/验证码那套老流程。
         *
         * <p>传它等于「按原来那句话翻译」——所以老调用方换不换都一样，行为保持不变。
         */
        public static final String OP_SIGN_IN = "SIGN_IN";
        /**
         * 语境：已登录时「修改密码」。
         *
         * <p>和 {@link #OP_SIGN_IN} 的唯一差别在 `CREDENTIALS` / 光秃秃的 401：
         * 那里要说「当前密码不对，别的设备**还登录着**」（用户才知道他的账号没被人动过）。
         */
        public static final String OP_PASSWORD_CHANGE = "PASSWORD_CHANGE";
        /**
         * 语境：忘记密码（`password/forgot`）。
         *
         * <p>只影响 503 那两句：注册失败必须说「账号没注册成功」（服务端回滚了账号），
         * 忘记密码失败时**没有任何账号被动过**，说「没注册成功」会让一个只是忘了密码的
         * 用户以为账号没了。
         */
        public static final String OP_FORGOT_PASSWORD = "FORGOT_PASSWORD";

        public final int message;
        public final String action;

        public Failure(int message, String action) {
            this.message = message; this.action = action;
        }

        /** 登录/注册语境的失败（等价于 {@link AccountInput#failure(int, String)}）。 */
        public static Failure signIn(int httpStatus, String code) {
            return failureFor(OP_SIGN_IN, httpStatus, code);
        }

        /**
         * 已登录时改密码失败的翻译。
         *
         * <p>单独一个入口是为了让**调用点自己说清语境**：`AccountViewModel` 里两处
         * `failure(...)` 长得一样、只差一个方法名，谁抄错了都会编译得过，但用户会看到
         * 「账号或密码不对」——而他明明正登录着。有了这个名字，抄错至少是显眼的。
         */
        public static Failure passwordChange(int httpStatus, String code) {
            return failureFor(OP_PASSWORD_CHANGE, httpStatus, code);
        }
    }

    /**
     * 忘记密码表单的校验结果：`message` 是理由（0 = 通过），`askEmail` 表示
     * 「问题出在没邮箱」——这时界面得把用户送回表单段去填，而不是让他对着
     * 一个发不出去的「重设密码」按钮发呆。
     */
    public static final class ResetProblem {
        public final int message;
        public final boolean askEmail;

        ResetProblem(int message, boolean askEmail) {
            this.message = message; this.askEmail = askEmail;
        }
    }
}
