package com.mobilegroup20.modelpilot.data.export;

/**
 * 导出的范围。设计稿第 28 张「导出数据：范围、JSON / CSV、下载」里的「范围」。
 *
 * <p><b>为什么两项是分开的、而不是一个「全部导出」。</b>这两样东西的性质不一样：
 * 对话是用户自己写下的内容（还给模型看过），用量只有数字、模型名和一句路由理由。
 * 想拿用量去对账、但不愿意把聊天记录交出去的人，必须能只选后者——否则他唯一的选择是
 * 要么全给、要么不给，而「不给」会让这个功能对他等于不存在。
 *
 * <p>所以导出范围是<b>用户选的</b>，我们不替他决定，也不在只选用量时顺手把对话也读出来
 * （读进内存这一步本身就不该发生，见 {@link DataExporter}）。
 */
public enum ExportScope {

    /** 对话：项目、对话、消息、记忆（压缩摘要）。 */
    CONVERSATIONS("conversations"),

    /** 本机账本：每一次真实的模型调用。 */
    USAGE("usage");

    /** 写进文件名的英文短名。中文名只出现在界面里，不进文件名——文件名要能被别的工具吃。 */
    public final String slug;

    ExportScope(String slug) {
        this.slug = slug;
    }
}
