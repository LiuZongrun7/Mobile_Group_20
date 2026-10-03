package com.mobilegroup20.modelpilot.chat;

/**
 * 这次请求需要模型具备的能力。Auto 按它筛掉"吃不下这个任务"的模型
 * （设计稿里那些置灰的 "Unavailable for this task" 就是被它筛掉的）。
 */
public enum TaskKind {
    /** 纯文本。 */
    TEXT,
    /** 要看图（设计稿里选图任务下 DeepSeek/Claude 会被置灰）。 */
    IMAGE,
    /** 要读 PDF（大纲的核心工作流之一）。 */
    PDF,
    /** 要用工具（智能体那条路）。 */
    TOOLS
}
