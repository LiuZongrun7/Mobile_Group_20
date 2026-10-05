package com.mobilegroup20.modelpilot.data.export;

import java.util.Collections;
import java.util.List;

/**
 * 一次导出的结果：产物 + 「里面有多少条」。
 *
 * <p>条数是给界面报数用的。设计稿要求导出后能看出「导了什么」，而只报一句
 * 「导出成功」等于让用户自己去文件里数——尤其在他选了两个范围、其中一个是空的时候
 * （比如从来没导出过用量），这一行字是唯一能告诉他「用量那边其实是 0 条」的地方。
 *
 * <p><b>0 条就是 0 条</b>，不写「暂无数据」这种含糊话：这里不涉及「未知」，
 * 我们确实查过本机库了。
 */
public final class ExportResult {

    public final List<ExportArtifact> artifacts;

    public final int projects;
    public final int conversations;
    public final int messages;
    public final int memories;
    public final int usageCalls;

    public ExportResult(List<ExportArtifact> artifacts,
                        int projects, int conversations, int messages, int memories,
                        int usageCalls) {
        this.artifacts = Collections.unmodifiableList(artifacts);
        this.projects = projects;
        this.conversations = conversations;
        this.messages = messages;
        this.memories = memories;
        this.usageCalls = usageCalls;
    }

    /** 只有一份产物（JSON 导出，或者 CSV 打包后的那个 zip）。 */
    public ExportArtifact artifact() {
        if (artifacts.size() != 1) {
            throw new IllegalStateException("这次导出有 " + artifacts.size() + " 份产物");
        }
        return artifacts.get(0);
    }

    public int totalBytes() {
        int total = 0;
        for (ExportArtifact artifact : artifacts) {
            total += artifact.size();
        }
        return total;
    }
}
