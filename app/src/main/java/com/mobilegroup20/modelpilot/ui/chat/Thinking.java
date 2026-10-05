package com.mobilegroup20.modelpilot.ui.chat;

import java.util.Locale;

/**
 * 「正在思考」那行的**纯逻辑**：点动画走到第几步、等了多久、该说哪句话。
 *
 * <p><b>为什么单独一个类：</b>动画和计时都要在界面里跑，但"第几步""几秒了"这两件事
 * 是算术，而算术能被单测钉住——真机上肉眼盯一个 500ms 跳一次的点，既看不准也复现不了。
 *
 * <p><b>为什么要有这一行东西：</b>从按下发送到第一个字回来，中间是落库、装上下文、
 * **压缩（一次真实的模型调用）**、选模型、建连接、等首字——慢的时候十几秒。
 * 这期间屏幕上什么都没有的话，用户没法判断"发出去了吗、是不是卡了"
 * （2026-10-05 用户原话："发东西我都不知道是否正常被接收到了"）。
 *
 * <p>等待时间**必须显示秒数**，不能只放一个转圈的图标：动画只说明"它还在动"，
 * 秒数才说明"它等多久了"——而后者才是用户判断"要不要按停止"的依据。
 */
public final class Thinking {

    /** 点动画一共几个形态（`.` / `..` / `...`）。 */
    public static final int DOT_PHASES = 3;

    /** 点跳一下的间隔（毫秒）。500ms 是"明显在动"和"闪得让人烦"之间的中间值。 */
    public static final long DOT_INTERVAL_MS = 500L;

    /** 一秒一跳：秒数只在前 60 秒有用，再往后就靠分钟了。 */
    public static final long CLOCK_INTERVAL_MS = 1000L;

    private Thinking() {
    }

    /** 第几步（0/1/2 循环）。负数（时钟回拨之类）也落在一个合法值上，不返回 -1。 */
    public static int dotStep(long elapsedMillis) {
        if (elapsedMillis <= 0L) {
            return 0;
        }
        return (int) ((elapsedMillis / DOT_INTERVAL_MS) % DOT_PHASES);
    }

    /** 那一步对应的点：`""`、`"."`、`".."`。**第一个形态是空的**——一上来就是三个点会显得已经卡了一会儿。 */
    public static String dots(long elapsedMillis) {
        int step = dotStep(elapsedMillis);
        StringBuilder out = new StringBuilder(DOT_PHASES - 1);
        for (int index = 0; index < step; index++) {
            out.append('.');
        }
        return out.toString();
    }

    /**
     * 等了多久，给人看的写法：`0s` / `7s` / `1m 05s` / `12m`。
     *
     * <p>超过一分钟就换成"几分几秒"：`73s` 这种数在人眼里要停一下才算得出来，
     * 而这一行存在的意义就是让人**一眼**看出等了多久。负数按 0 处理
     * （`SystemClock.uptimeMillis` 不会倒退，但这个函数不该因为参数脏就显示 `-3s`）。
     */
    public static String elapsed(long elapsedMillis) {
        long seconds = Math.max(0L, elapsedMillis) / 1000L;
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        return rest == 0L
                ? minutes + "m"
                : String.format(Locale.US, "%dm %02ds", minutes, rest);
    }
}
