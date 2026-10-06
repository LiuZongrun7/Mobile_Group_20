package com.mobilegroup20.modelpilot.data.importer;

/**
 * 这个文件我们读不了。**每一种都对应一句能说给用户听的话。**
 *
 * <p>为什么不做成一个笼统的 {@code IOException}：用户拿到的文件可能是任何东西
 * （相册里那张图、别的 App 的备份、被 Excel 转存过的 csv），而这几种情况的下一步
 * 完全不同——选错了就重选，格式对但内容坏了就得重新导一次。把「为什么读不了」
 * 说清楚，是这一屏唯一能帮到他的地方。
 *
 * <p>{@link #problem} 是英文短句（界面文案统一英文，和导出那边一致），
 * 且**不夹带文件内容**：坏文件可能很大，把一段内容拼进错误消息会跟着进日志。
 */
public final class ImportFileException extends Exception {

    public enum Reason {
        /** 不是 ModelPilot 导出的文件（格式不对，或者 {@code meta.app} 写着别人）。 */
        NOT_OUR_FILE,
        /** 是我们的文件，但字节坏了：zip 打不开、JSON 语法错、表头不对。 */
        UNREADABLE,
        /** 空文件 / 零字节。 */
        EMPTY,
        /** 读得懂，但里面没有任何可导入的数据。 */
        NO_CONTENT
    }

    public final Reason reason;

    /** 给用户看的一句话。 */
    public final String problem;

    public ImportFileException(Reason reason, String problem) {
        super(problem);
        this.reason = reason;
        this.problem = problem;
    }
}
