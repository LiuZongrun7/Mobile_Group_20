package com.mobilegroup20.modelpilot.data.importer;

/**
 * 本机上已经有同一条对话时怎么办。**这是导入里唯一需要用户拍板的事**，
 * 其余的一切（怎么解析、怎么去重）都不该问。
 *
 * <p>两种都实现、由用户选，而不是我们替他挑一个：
 * <ul>
 *   <li>{@link #ADD_ONLY}（默认）：<b>只加本机没有的</b>。手机上的对话是活的
 *       （可能刚聊过、刚改过标题、刚编辑过摘要），文件里的是过去的存档，
 *       所以"本机优先"是唯一不会让用户白干一场的默认；</li>
 *   <li>{@link #REPLACE}：<b>用文件里的覆盖本机同 id 的对话</b>。换手机时这是他要的，
 *       但它在"文件比本机旧"的时候会真的吃掉新东西，所以必须由用户明确选。</li>
 * </ul>
 *
 * <p>注意「覆盖」的粒度是<b>对话</b>，不是整个库：本机独有的对话在任何策略下都不动。
 * 导入永远不删东西——这是有意的，一个"恢复备份"的动作不该顺手清掉手机上别的东西。
 */
public enum MergePolicy {

    ADD_ONLY,
    REPLACE;

    public boolean replacesExisting() {
        return this == REPLACE;
    }
}
