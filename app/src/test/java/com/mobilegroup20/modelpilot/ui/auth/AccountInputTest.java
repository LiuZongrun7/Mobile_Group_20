package com.mobilegroup20.modelpilot.ui.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.mobilegroup20.modelpilot.R;

import org.junit.Test;

/**
 * 账号表单的本地校验、服务端错误码翻译、重发冷却解析。
 *
 * <p>为什么值得单测：这些东西决定用户看到哪一句话、界面往哪一步走。
 * 手动把每种失败都试一遍要先把服务端配坏（MAIL_NOT_CONFIGURED）、连输 5 次验证码
 * （CODE_ATTEMPTS）、等 15 分钟（CODE_EXPIRED）——没人会做，所以只能靠这里。
 * 尤其是「503 不能说成注册成功」这条：它是**用户会被骗**的那一类错误。
 */
public class AccountInputTest {

    // ------------------------------------------------------------ 邮箱
    @Test public void a_plausible_email_passes() {
        assertEquals(0, AccountInput.emailProblem("a@b.co"));
        assertEquals(0, AccountInput.emailProblem("first.last+tag@sub.example.co.uk"));
        assertEquals(0, AccountInput.emailProblem("  learner@modelpilot.cn  ")); // 前后空格是粘贴带进来的
        assertEquals(0, AccountInput.emailProblem("user_name-01@school-edu.example.com"));
    }

    @Test public void an_empty_email_is_told_apart_from_a_malformed_one() {
        // 两句文案不一样：「没填」和「填错了」对用户来说要做的事不一样。
        assertEquals(R.string.account_email_empty, AccountInput.emailProblem(""));
        assertEquals(R.string.account_email_empty, AccountInput.emailProblem("   "));
        assertEquals(R.string.account_email_empty, AccountInput.emailProblem(null));
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem("abc"));
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem("a@b"));      // 没有顶级域
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem("a@b.c"));    // 顶级域只有 1 个字母
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem("a b@x.com")); // 中间有空格
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem("@x.com"));
        // 前后空格会被 trim 掉，所以「 a@x.com 」是合法的（粘贴进来的地址常带空格）；
        // 但中间的空格不行，见上面那条。
        assertEquals(0, AccountInput.emailProblem(" a@x.com "));
    }

    @Test public void the_classic_typos_are_caught() {
        // 连续两个点、点开头点结尾：正则放它过去，但服务端一定会拒，本地先挡掉省一次往返。
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem("a..b@x.com"));
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem(".a@x.com"));
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem("a.@x.com"));
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem("a@x..com"));
        assertEquals(R.string.account_email_invalid, AccountInput.emailProblem("a@x.1com")); // 顶级域里没有数字
        StringBuilder longAddress = new StringBuilder("a");
        for (int i = 0; i < 260; i++) longAddress.append('b');
        assertEquals(R.string.account_email_invalid,
                AccountInput.emailProblem(longAddress + "@x.com"));
    }

    // ------------------------------------------------------------ 验证码
    @Test public void a_six_digit_code_is_the_only_accepted_shape() {
        assertEquals(0, AccountInput.codeProblem("123456"));
        assertEquals(0, AccountInput.codeProblem("000000")); // 全是 0 也是合法验证码
        // 从邮件里复制粘贴常常带空格/换行，这不是用户的错，不该让他重打一遍。
        assertEquals(0, AccountInput.codeProblem(" 123 456 "));
        assertEquals(0, AccountInput.codeProblem("123\n456"));
        assertEquals(R.string.account_code_format, AccountInput.codeProblem("12345"));
        assertEquals(R.string.account_code_format, AccountInput.codeProblem("1234567"));
        assertEquals(R.string.account_code_format, AccountInput.codeProblem("12345a"));
        assertEquals(R.string.account_code_empty, AccountInput.codeProblem(""));
        assertEquals(R.string.account_code_empty, AccountInput.codeProblem("   "));
        assertEquals(R.string.account_code_empty, AccountInput.codeProblem(null));
    }

    // ------------------------------------------------------------ 表单整体
    @Test public void registration_reports_the_first_bad_field_in_a_useful_order() {
        // 三样都空：先说邮箱（用户是从上往下填的），不能笼统说「请填写」。
        assertEquals(R.string.account_email_empty,
                AccountInput.registrationProblem("", "", ""));
        assertEquals(R.string.account_username_empty,
                AccountInput.registrationProblem("a@b.co", "  ", "password1"));
        assertEquals(R.string.account_password_empty,
                AccountInput.registrationProblem("a@b.co", "nick", ""));
        assertEquals(R.string.account_password_short,
                AccountInput.registrationProblem("a@b.co", "nick", "short"));
        assertEquals(0, AccountInput.registrationProblem("a@b.co", "nick", "password1"));
        assertEquals(R.string.account_username_long,
                AccountInput.registrationProblem("a@b.co", "n".repeat(65), "password1"));
    }

    @Test public void login_accepts_email_or_username_but_always_needs_a_password() {
        // **全空时是 account_empty**：仪器测试点「登录」断言的就是这一句。
        assertEquals(R.string.account_empty, AccountInput.loginProblem("", "", ""));
        assertEquals(R.string.account_empty, AccountInput.loginProblem("  ", "  ", "  "));
        // 只填了用户名（老用户习惯用昵称登录）也放行。
        assertEquals(0, AccountInput.loginProblem("", "nick", "password1"));
        // 只填了邮箱。
        assertEquals(0, AccountInput.loginProblem("a@b.co", "", "password1"));
        // 标识符有、密码空：这时候不该说「请填写邮箱和密码」（邮箱明明填了）。
        assertEquals(R.string.account_password_empty, AccountInput.loginProblem("a@b.co", "", ""));
        // 邮箱那栏非空就一定会被当成标识符发出去，形状要在本地拦住。
        assertEquals(R.string.account_email_invalid, AccountInput.loginProblem("not-an-email", "", "password1"));
        // 登录**不查密码长度**：长度是注册那边的规则，拿它拦登录会把拿着正确密码的
        // 老用户挡在门外，而且提示还是「密码至少 8 位」，指错方向。
        assertEquals(0, AccountInput.loginProblem("", "nick", "short"));
    }

    @Test public void the_identifier_prefers_the_email_field() {
        assertEquals("a@b.co", AccountInput.loginIdentifier("a@b.co", "nick"));
        assertEquals("nick", AccountInput.loginIdentifier("", "nick"));
        assertEquals("nick", AccountInput.loginIdentifier("   ", " nick "));
        assertEquals("a@b.co", AccountInput.loginIdentifier(" a@b.co ", "nick"));
    }

    // ------------------------------------------------------------ 错误码 → 文案 + 下一步
    @Test public void taken_email_and_taken_username_say_different_things() {
        // 两个都是 409，但用户要做的事完全不同：一个换邮箱，一个换昵称。
        // 只看状态码必然把它们混成同一句，这正是这个映射存在的理由。
        AccountInput.Failure email = AccountInput.failure(409, "EMAIL_TAKEN");
        AccountInput.Failure username = AccountInput.failure(409, "USERNAME_TAKEN");
        assertEquals(R.string.account_email_taken, email.message);
        assertEquals(R.string.account_taken, username.message);
        assertNotEquals(email.message, username.message);
        assertEquals(AccountInput.ACTION_NONE, email.action);
    }

    @Test public void validation_codes_map_to_their_own_sentences() {
        assertEquals(R.string.account_email_invalid, AccountInput.failure(400, "EMAIL_INVALID").message);
        assertEquals(R.string.account_email_empty, AccountInput.failure(400, "EMAIL_REQUIRED").message);
        assertEquals(R.string.account_username_invalid, AccountInput.failure(400, "USERNAME_INVALID").message);
        assertEquals(R.string.account_password_short, AccountInput.failure(400, "PASSWORD_INVALID").message);
        assertEquals(R.string.account_credentials_error, AccountInput.failure(401, "CREDENTIALS").message);
    }

    @Test public void code_failures_tell_the_user_what_to_do_next() {
        // 码错了：留在原地重填，不需要重发。
        AccountInput.Failure wrong = AccountInput.failure(400, "CODE_INVALID");
        assertEquals(R.string.account_code_invalid, wrong.message);
        assertEquals(AccountInput.ACTION_NONE, wrong.action);
        // 过期：手里的码已经废了，**立刻**允许重发（服务端那边早就过了 60 秒窗口）。
        AccountInput.Failure expired = AccountInput.failure(400, "CODE_EXPIRED");
        assertEquals(R.string.account_code_expired, expired.message);
        assertEquals(AccountInput.ACTION_RESEND_NOW, expired.action);
        // 试错太多：也要重发，但刚才那封多半还在 60 秒窗口里，冷却留着。
        AccountInput.Failure attempts = AccountInput.failure(429, "CODE_ATTEMPTS");
        assertEquals(R.string.account_code_attempts, attempts.message);
        assertEquals(AccountInput.ACTION_RESEND_WAIT, attempts.action);
    }

    @Test public void email_unverified_sends_the_user_to_the_code_stage() {
        AccountInput.Failure failure = AccountInput.failure(403, "EMAIL_UNVERIFIED");
        assertEquals(R.string.account_unverified, failure.message);
        assertEquals(AccountInput.ACTION_VERIFY, failure.action);
    }

    @Test public void mail_not_configured_must_not_look_like_a_success() {
        // 503 这一条最要命：服务端没配发信服务，**账号根本没建**。
        // 文案必须是这一句（说清「没注册成功」），不能被兜底成「注册成功」或者「稍后再试」。
        AccountInput.Failure failure = AccountInput.failure(503, "MAIL_NOT_CONFIGURED");
        assertEquals(R.string.account_mail_not_configured, failure.message);
        assertEquals(AccountInput.ACTION_NONE, failure.action);
    }

    @Test public void a_failed_send_also_must_not_look_like_a_success() {
        // 503 MAIL_FAILED：发信服务配了、但这一封没发出去。服务端**已经把账号回滚**了，
        // 所以它和 MAIL_NOT_CONFIGURED 一样必须说"没注册成功"——但两句不一样：
        // 一个是"服务器没这能力"，一个是"这次没送出去"，用户能做的事不同。
        AccountInput.Failure failure = AccountInput.failure(503, "MAIL_FAILED");
        assertEquals(R.string.account_mail_failed, failure.message);
        assertEquals(AccountInput.ACTION_NONE, failure.action);
        assertNotEquals(R.string.account_mail_not_configured, failure.message);
    }

    @Test public void rate_limit_is_its_own_sentence_and_means_wait() {
        assertEquals(R.string.account_rate_limit, AccountInput.failure(429, "RATE_LIMIT").message);
        assertEquals(AccountInput.ACTION_WAIT, AccountInput.failure(429, "RATE_LIMIT").action);
        // 错误体没解析出来（网关把 429 包成了别的形状）时按状态码兜底，仍然是「太频繁」。
        assertEquals(R.string.account_rate_limit, AccountInput.failure(429, null).message);
    }

    @Test public void a_missing_or_unknown_code_falls_back_on_the_http_status() {
        // 契约里每个状态码只有一种含义，所以按状态码兜底不会说谎。
        assertEquals(R.string.account_credentials_error, AccountInput.failure(401, null).message);
        assertEquals(AccountInput.ACTION_VERIFY, AccountInput.failure(403, null).action);
        assertEquals(R.string.account_request_rejected, AccountInput.failure(400, null).message);
        assertEquals(R.string.forum_request_error, AccountInput.failure(500, null).message);
        assertEquals(R.string.forum_request_error, AccountInput.failure(503, "SOMETHING_NEW").message);
        // 请求根本没到服务端：网络文案，不是「服务器出错了」——两者要做的事不一样。
        assertEquals(R.string.forum_network_error, AccountInput.failure(0, null).message);
        assertEquals(AccountInput.ACTION_NONE, AccountInput.failure(0, null).action);
    }

    @Test public void an_unrecognized_failure_never_comes_back_empty() {
        // 「不许把所有失败都显示成同一句出错了」的反面：每一句都得有出处。
        int[] statuses = {400, 401, 403, 404, 409, 422, 429, 500, 502, 503, 0};
        for (int status : statuses)
            assertTrue("status " + status + " 必须有一句话",
                    AccountInput.failure(status, null).message != 0);
    }

    // ------------------------------------------------------------ Retry-After
    @Test public void retry_after_reads_seconds_and_falls_back_safely() {
        assertEquals(60_000L, AccountInput.retryAfterMillis("60"));
        assertEquals(120_000L, AccountInput.retryAfterMillis(" 120 "));
        // 解析不出来就用服务端的实际窗口（60 秒）：宁可多等一会儿，
        // 也不要让用户点了立刻再撞一次 429。
        assertEquals(60_000L, AccountInput.retryAfterMillis(null));
        assertEquals(60_000L, AccountInput.retryAfterMillis(""));
        assertEquals(60_000L, AccountInput.retryAfterMillis("0"));
        assertEquals(60_000L, AccountInput.retryAfterMillis("-5"));
        assertEquals(60_000L, AccountInput.retryAfterMillis("soon"));
        // HTTP 日期形式（RFC 允许）不做解析：那要跟本机时钟对时，手机时间偏几分钟就会算出
        // 一个负数或者几小时，不如老老实实退回 60 秒。
        assertEquals(60_000L, AccountInput.retryAfterMillis("Wed, 21 Oct 2026 07:28:00 GMT"));
    }
}
