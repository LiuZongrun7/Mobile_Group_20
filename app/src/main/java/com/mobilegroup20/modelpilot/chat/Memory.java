package com.mobilegroup20.modelpilot.chat;

/**
 * 一段被压缩过的历史——**provider 无关**的一等对象。
 *
 * <p>它是整套"压一次、改写所有上下文"能成立的关键：压缩产物不属于任何一家，
 * 所以六份上下文都能改写成"这条记忆 + 压缩点之后的消息"，
 * 于是换模型时不需要再压一次（**压缩只花一次钱**）。
 *
 * <p>{@code madeByProvider} / {@code madeByModel} 记的是"这次压缩是谁做的"：
 * 用户可能用最便宜的那家去压，事后要能回答"这段摘要是谁写的、花了多少"
 * （大纲 §6 的 "USD provenance" 那一条）。
 */
public final class Memory {

    public final String id;
    public final String chatId;
    /** 覆盖区间：从哪一条（含）到哪一条（含）。压缩后这些消息不再进上下文。 */
    public final String fromMessageId;
    public final String toMessageId;
    /** 摘要正文。用户可以改（大纲 §4 "inspect/correct the retained memory"）。 */
    public String summary;
    public final String madeByProvider;
    public final String madeByModel;
    public final long createdAt;
    public final long tokensIn;
    public final long tokensOut;
    /** 用户手动改过就是 true——界面上要能看出来这不是模型写的了。 */
    public boolean editedByUser;

    public Memory(String id, String chatId, String fromMessageId, String toMessageId,
                  String summary, String madeByProvider, String madeByModel, long createdAt,
                  long tokensIn, long tokensOut) {
        this.id = id;
        this.chatId = chatId;
        this.fromMessageId = fromMessageId;
        this.toMessageId = toMessageId;
        this.summary = summary;
        this.madeByProvider = madeByProvider;
        this.madeByModel = madeByModel;
        this.createdAt = createdAt;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
    }

    /** 用户改了摘要：**所有渲染缓存都要跟着失效**（否则改完发出去还是旧的）。 */
    public void edit(String text) {
        this.summary = text == null ? "" : text;
        this.editedByUser = true;
    }
}
