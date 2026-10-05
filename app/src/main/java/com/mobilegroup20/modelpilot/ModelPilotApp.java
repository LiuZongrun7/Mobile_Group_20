package com.mobilegroup20.modelpilot;

import android.app.Application;

import com.mobilegroup20.modelpilot.data.RepositoryProvider;

/**
 * 应用入口：初始化 Repository，并恢复（Keystore 加密的）账号登录会话。
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
 * <p>注册在 {@code AndroidManifest.xml} 的 {@code <application android:name=".ModelPilotApp">}。
 * 那一行删了的话，真实现会在第一次被用到时抛「还没初始化」，
 * 而不是静默退回桩数据——报错信息里写了怎么办。
 */
public class ModelPilotApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        RepositoryProvider.init(this);
        if (!BuildConfig.FORUM_BASE_URL.isEmpty()) {
            // 论坛和用量共用**同一个**账号会话（`AccountSession`）：它就是「当前是谁」
            // 的唯一来源。测试身份也在它里面（带 `tt_test_` 前缀、仅 Debug），
            // 所以这里不需要再判断一次用哪一支。
            com.mobilegroup20.modelpilot.data.AccountSession session =
                    com.mobilegroup20.modelpilot.data.AccountSession.get(this);
            RepositoryProvider.configureForum(session.forumBaseUrl(), session);
            confirmSessionStillValid(session);
        }
    }

    /**
     * 启动时确认一下本机存的那个会话**在服务端还认不认**。
     *
     * <p><b>为什么必须确认。</b>会话不是永久的，而且现在有两条路会让它在别的设备上失效：
     * 用户重设了密码（**服务端会把所有会话一起踢掉**，见 `SERVER_API.md`
     * 的"密码的找回与修改"），或者在另一台设备上点了退出。不确认的话，这一页会一直
     * 显示"已登录：xxx"，直到用户点进 Explore 被 401 打回来——而他已经在疑惑
     * "我到底是登着还是没登"。
     *
     * <p><b>三种结果要分开，尤其是最后一种。</b>
     * <ul>
     *   <li>200：什么都别动；</li>
     *   <li><b>401：明确失效，清掉本地会话</b>——这是唯一会清会话的情况；</li>
     *   <li>网络错/超时：<b>什么都不做</b>。离线不等于登录失效，因为一次断网就把
     *       用户的会话清掉，是那种最让人恼火的"自作聪明"（他下次联网还得重新登）。</li>
     * </ul>
     *
     * <p>**论坛测试身份不查**（`tt_test_`）：那种 token 只在 `/test-api` 那个挂载点认，
     * 拿它去打正式接口必然 401，查一次就等于开一次 App 就把测试身份清掉。
     */
    private void confirmSessionStillValid(
            com.mobilegroup20.modelpilot.data.AccountSession session) {
        if (!session.signedIn() || session.forumTest()
                || !BuildConfig.FORUM_BASE_URL.startsWith("https://")) {
            return;
        }
        final String token = session.token();
        if (token == null || token.isEmpty()) {
            return;
        }
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        com.mobilegroup20.modelpilot.data.remote.AccountApi api = new retrofit2.Retrofit.Builder()
                .baseUrl(BuildConfig.FORUM_BASE_URL).client(client)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build().create(com.mobilegroup20.modelpilot.data.remote.AccountApi.class);
        api.me("Bearer " + token).enqueue(new retrofit2.Callback<>() {
            @Override
            public void onResponse(retrofit2.Call<com.mobilegroup20.modelpilot.data.remote.AccountApi.Account> call,
                                   retrofit2.Response<com.mobilegroup20.modelpilot.data.remote.AccountApi.Account> response) {
                if (response.code() == 401) {
                    android.util.Log.i("ModelPilot", "服务端不认这个会话了，清掉本机那份");
                    session.clear();
                }
            }

            @Override
            public void onFailure(retrofit2.Call<com.mobilegroup20.modelpilot.data.remote.AccountApi.Account> call,
                                  Throwable error) {
                // 离线/超时：**不清会话**（见上面那段注释）。
                android.util.Log.i("ModelPilot", "会话确认没连上，保持原样：" + error);
            }
        });
    }
}
