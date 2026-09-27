package com.mobilegroup20.tokentrail;

import android.app.Application;

import com.mobilegroup20.tokentrail.data.RepositoryProvider;

/**
 * 应用入口：初始化 Repository，并恢复团队后端的加密登录会话。
 *
 * <p><b>为什么需要一个 Application 子类。</b>{@code RepositoryProvider} 是个静态入口，
 * 拿不到 Context，而建 Room 数据库必须有 Context。有三条路：
 * <ul>
 *   <li>每个 Activity 自己调一次初始化——<b>不要</b>，漏一个页面就是在运行时崩，
 *       而且崩的位置离原因很远；</li>
 *   <li>让 {@code RepositoryProvider} 自己存一个静态 Context——能work，但那个 Context
 *       必须由外面给，还是回到同一个问题；</li>
 *   <li><b>就这里。</b>Application 一定在第一个 Activity 之前构造，
 *       初始化只发生一次，而且不可能被漏掉。</li>
 * </ul>
 *
 * <p>传的是 Application 的 Context，不是 Activity 的——它和进程同生命周期，
 * 不存在泄漏。存在静态字段里是安全的，别改成传 Activity。
 *
 * <p>注册在 {@code AndroidManifest.xml} 的 {@code <application android:name=".TokenTrailApp">}。
 * 那一行删了的话，真实现会在第一次被用到时抛「还没初始化」，
 * 而不是静默退回桩数据——报错信息里写了怎么办。
 */
public class TokenTrailApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        RepositoryProvider.init(this);
        if (!BuildConfig.FORUM_BASE_URL.isEmpty()) {
            // 论坛和用量共用**同一个**账号会话（`AccountSession`）：它就是「当前是谁」
            // 的唯一来源。测试身份也在它里面（带 `tt_test_` 前缀、仅 Debug），
            // 所以这里不需要再判断一次用哪一支。
            com.mobilegroup20.tokentrail.data.AccountSession session =
                    com.mobilegroup20.tokentrail.data.AccountSession.get(this);
            RepositoryProvider.configureForum(session.forumBaseUrl(), session);
        }
    }
}
