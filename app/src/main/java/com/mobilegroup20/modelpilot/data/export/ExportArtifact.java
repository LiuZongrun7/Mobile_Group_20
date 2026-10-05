package com.mobilegroup20.modelpilot.data.export;

import java.nio.charset.StandardCharsets;

/**
 * 一份导出的产物：文件名 + 内容。
 *
 * <p>内容是 {@code byte[]} 而不是字符串，因为要和 Android 的存储层对接
 * （{@code ContentResolver} 只收字节/流），而 JSON 与 CSV 都已经在上游编成了确定的字节。
 * 中间再走一趟字符串只会多一次编码机会——多一次就有多一次编码错的机会（中文乱码就出在这里）。
 */
public final class ExportArtifact {

    public final String fileName;
    public final byte[] bytes;

    public ExportArtifact(String fileName, byte[] bytes) {
        this.fileName = fileName;
        this.bytes = bytes;
    }

    public int size() {
        return bytes.length;
    }

    /**
     * MIME 类型，给系统的「保存/分享」用。
     *
     * <p>按扩展名推，而不是让调用方再传一遍：两者一旦不一致，系统会用一个打不开的
     * 应用去打开这个文件（比如把 zip 当 json 打开），而传参的地方正是最容易漏改的。
     */
    public String mimeType() {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        return "application/octet-stream";
    }

    /** 给日志/提示用的一句话，不含内容。 */
    public String describe() {
        return fileName + "（" + size() + " 字节）";
    }

    @Override
    public String toString() {
        return describe() + "，UTF-8 文本 " + new String(bytes, StandardCharsets.UTF_8).length() + " 字符";
    }
}
