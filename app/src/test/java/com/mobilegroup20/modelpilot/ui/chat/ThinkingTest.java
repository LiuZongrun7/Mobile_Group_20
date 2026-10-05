package com.mobilegroup20.modelpilot.ui.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 「正在思考」那行的算术：点走到第几步、等了多久写成什么。
 *
 * <p>为什么值得单测：它是**唯一**能证明"等待期界面确实在动"的东西。真机上肉眼盯一个
 * 500ms 跳一次的点，既看不准也复现不了（转屏、切后台、慢一帧都会让它看起来不一样），
 * 而这两个函数是纯的，跑一遍就知道下一秒会显示什么。
 *
 * <p>另一半理由是文案：等 73 秒写成 `73s` 时人要停一下才算得出"一分多钟"，
 * 而这一行存在的意义正是让人**一眼**看出等了多久。分成 / 秒的边界就在这里钉住。
 */
public class ThinkingTest {

    @Test
    public void theFirstDotPhaseIsEmpty() {
        // 一上来就是三个点的话，看起来像"已经卡了一会儿"——所以第一个形态是空的。
        assertEquals("", Thinking.dots(0L));
        assertEquals("", Thinking.dots(499L));
    }

    @Test
    public void dotsCycleThroughThreePhasesAndWrapAround() {
        assertEquals(".", Thinking.dots(500L));
        assertEquals("..", Thinking.dots(1000L));
        // 第三个形态是"两个点"之后回到空（点点点最容易画成三个点然后卡住不动）
        assertEquals("", Thinking.dots(1500L));
        assertEquals(".", Thinking.dots(2000L));
    }

    @Test
    public void aNegativeElapsedTimeDoesNotLeakIntoTheUi() {
        // SystemClock.uptimeMillis 不会倒退，但这个函数不该因为参数脏就显示 "-3s"。
        assertEquals("", Thinking.dots(-1000L));
        assertEquals("0s", Thinking.elapsed(-1000L));
        assertEquals(0, Thinking.dotStep(-1L));
    }

    @Test
    public void elapsedStaysInSecondsForTheFirstMinute() {
        assertEquals("0s", Thinking.elapsed(0L));
        assertEquals("7s", Thinking.elapsed(7_400L));
        assertEquals("59s", Thinking.elapsed(59_900L));
    }

    @Test
    public void elapsedSwitchesToMinutesAndSecondsAfterThat() {
        assertEquals("1m", Thinking.elapsed(60_000L));
        assertEquals("1m 05s", Thinking.elapsed(65_000L));
        assertEquals("1m 13s", Thinking.elapsed(73_000L));
        assertEquals("12m", Thinking.elapsed(12 * 60_000L));
        assertEquals("12m 34s", Thinking.elapsed(754_000L));
    }
}
