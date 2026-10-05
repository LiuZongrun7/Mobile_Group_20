package com.mobilegroup20.modelpilot.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 没发出去的草稿：**按对话各存一份**（大纲 §4-1 "draft recovery"）。
 *
 * <p>为什么需要它：转屏由 Android 自己保（EditText 的 view state），但**进程被杀掉之后
 * 就没了**——而"接了个电话回来，打了一半的话不见了"正是发生在那一刻。
 *
 * <p>存的是纯文本、明文（它就是用户自己打的一句话，和 key 不是一类东西），
 * 但**发出去之后就删掉**：留着上一条已发送的文本，下次进来会看到一个框里
 * 又出现刚发过的话，用户会以为没发出去而再按一次发送。
 *
 * <p>上限 {@link #MAX_CHARS}：正常草稿就是几句话；不设上限的话，
 * 有人往输入框里粘一整篇文档，SharedPreferences 会一直带着它。
 */
public final class Drafts {

    private static final String FILE = "app_drafts";
    private static final String PREFIX = "draft.";
    private static final int MAX_CHARS = 20_000;

    private Drafts() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 存草稿。空文本 = 删掉那一条（不留一堆空串）。 */
    public static void save(Context context, String key, String text) {
        if (key == null || key.isEmpty()) {
            return;
        }
        String value = text == null ? "" : text;
        if (value.length() > MAX_CHARS) {
            value = value.substring(0, MAX_CHARS);
        }
        if (value.trim().isEmpty()) {
            prefs(context).edit().remove(PREFIX + key).apply();
        } else {
            prefs(context).edit().putString(PREFIX + key, value).apply();
        }
    }

    /** 取草稿；没有就返回空串（界面直接 setText 即可）。 */
    public static String get(Context context, String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String value = prefs(context).getString(PREFIX + key, "");
        return value == null ? "" : value;
    }

    /** 发送成功之后清掉（理由见类注释）。 */
    public static void clear(Context context, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        prefs(context).edit().remove(PREFIX + key).apply();
    }
}
