package com.mobilegroup20.modelpilot.ui.chat;

import android.content.Context;

import androidx.annotation.ColorInt;

import com.mobilegroup20.modelpilot.R;

/**
 * 项目文件夹的颜色：**按 `ProjectEntity.colorIndex` 取一组固定色**（设计稿里
 * `Campus event` 是紫的、`COMP3011` 是绿的）。
 *
 * <p>为什么库里存下标而不是色值：色值属于**界面**。存了 `#7C5CD6` 之后，
 * 加深色模式、换主题、或者以后让用户自己挑颜色，都要去改每一行数据；
 * 存下标，颜色表在资源里，改一处就够。
 *
 * <p>取值用取模：项目数超过色板长度时**循环使用**。撞色是可能的，
 * 但比"第 9 个项目没有颜色"好——后者看起来像坏数据。
 */
public final class ProjectColors {

    private static final int[] SLOTS = {
            R.color.project_folder_0,
            R.color.project_folder_1,
            R.color.project_folder_2,
            R.color.project_folder_3,
            R.color.project_folder_4,
            R.color.project_folder_5,
    };

    private ProjectColors() {
    }

    @ColorInt
    public static int folder(Context context, int colorIndex) {
        int index = colorIndex % SLOTS.length;
        if (index < 0) {
            // 负数下标会直接抛 ArrayIndexOutOfBounds。数据是本地写的，正常不会负，
            // 但这里的代价只有一行，不值得为它赌一个崩溃。
            index += SLOTS.length;
        }
        return context.getColor(SLOTS[index]);
    }
}
