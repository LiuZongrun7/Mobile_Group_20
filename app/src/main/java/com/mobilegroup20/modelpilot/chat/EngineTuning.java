package com.mobilegroup20.modelpilot.chat;

import android.content.Context;
import android.content.SharedPreferences;

import com.mobilegroup20.modelpilot.BuildConfig;

import java.util.Locale;

/**
 * 上下文引擎的两个"调试旋钮"：**给回答留多少位置**（{@link ContextEngine#RESERVE_FOR_OUTPUT}）
 * 和**提前多少开始压缩**（{@link ContextEngine#COMPRESS_HEADROOM}）。
 *
 * <p><b>为什么需要它。</b>真机上根本试不出压缩：阈值是"最小的板 × 0.8 − 预留 − 提前量"，
 * 配 DeepSeek（128K）时约 <b>88K tokens ≈ 30 万字符</b>——正常对话到不了这个量，
 * 于是"压缩"这条路径只有单测覆盖，没有一次真实运行。答辩前想演示它，
 * 就必须能把这个数临时调小。
 *
 * <p><b>只在 debug 包里生效</b>（{@link BuildConfig#DEBUG}）：release 包一律返回默认值，
 * 免得"演示时调小了忘改回来"变成线上行为——那种错误表现为"对话莫名其妙被压缩"，
 * 而日志里什么都看不出来。设置入口也只在 debug 的「我的」页出现。
 *
 * <p>存在 SharedPreferences 里而不是编译期常量：改一次就要重装包的话，
 * 演示现场（或者对方手机上）根本没法调。**它不是用户设置**，所以不进设置页的正式条目。
 */
public final class EngineTuning {

    private static final String FILE = "app_engine_tuning";
    private static final String KEY_RESERVE = "reserve_for_output";
    private static final String KEY_HEADROOM = "compress_headroom";

    /** 预设：真实值 → 一步步调小到"随便聊两句就会触发"。 */
    public static final int[] HEADROOM_PRESETS = {ContextEngine.COMPRESS_HEADROOM, 40_000, 8_000, 2_000, 500};
    public static final int[] RESERVE_PRESETS = {ContextEngine.RESERVE_FOR_OUTPUT, 2_048, 512};

    private EngineTuning() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 给回答留的位置。debug 里可调，release 恒为默认值。 */
    public static int reserveForOutput(Context context) {
        if (!BuildConfig.DEBUG) {
            return ContextEngine.RESERVE_FOR_OUTPUT;
        }
        return prefs(context).getInt(KEY_RESERVE, ContextEngine.RESERVE_FOR_OUTPUT);
    }

    /** 压缩提前量。debug 里可调，release 恒为默认值。 */
    public static int compressHeadroom(Context context) {
        if (!BuildConfig.DEBUG) {
            return ContextEngine.COMPRESS_HEADROOM;
        }
        return prefs(context).getInt(KEY_HEADROOM, ContextEngine.COMPRESS_HEADROOM);
    }

    public static void set(Context context, int reserveForOutput, int compressHeadroom) {
        prefs(context).edit()
                .putInt(KEY_RESERVE, reserveForOutput)
                .putInt(KEY_HEADROOM, compressHeadroom)
                .apply();
    }

    public static void reset(Context context) {
        prefs(context).edit().remove(KEY_RESERVE).remove(KEY_HEADROOM).apply();
    }

    public static boolean isDefault(Context context) {
        return reserveForOutput(context) == ContextEngine.RESERVE_FOR_OUTPUT
                && compressHeadroom(context) == ContextEngine.COMPRESS_HEADROOM;
    }

    /** 「我的」页那颗按钮上显示的字。 */
    public static String describe(Context context) {
        if (isDefault(context)) {
            return "压缩阈值：默认（提前 "
                    + ContextEngine.COMPRESS_HEADROOM / 1000 + "K，预留 "
                    + ContextEngine.RESERVE_FOR_OUTPUT / 1000 + "K）";
        }
        return String.format(Locale.US, "压缩阈值：提前 %d，预留 %d（非默认）",
                compressHeadroom(context), reserveForOutput(context));
    }
}
