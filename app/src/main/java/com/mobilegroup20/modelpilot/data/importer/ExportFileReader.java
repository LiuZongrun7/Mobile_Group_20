package com.mobilegroup20.modelpilot.data.importer;

import androidx.annotation.Nullable;

import java.util.zip.ZipInputStream;

/**
 * 一次导入的入口：把「用户选的那个文件的字节」变成一份 {@link ImportBundle}。
 *
 * <p>它只管三件事：<b>认格式</b>（JSON 还是 CSV 的 zip）、<b>派活</b>给对应的解析器、
 * 把"读不了"翻译成 {@link ImportFileException}。合并在 {@link DataImporter} 里，
 * 写库在 {@link RoomImportTarget} 里——这三段分开是为了每段都能单独测。
 *
 * <h3>为什么先认字节、再看扩展名</h3>
 * 用户从任意位置选文件（云盘、微信下载目录、他自己改过名的副本），扩展名是最不可靠的
 * 信息。所以判定只看内容：以 {@code PK} 开头就是 zip（CSV 那种产物），
 * 去掉 BOM 与空白后以 <code>{</code> 开头就是 JSON。扩展名只用来在报错时称呼这个文件。
 *
 * <h3>为什么拒绝得这么早</h3>
 * 「选错文件」最坏的结果不是报错，而是<b>静默什么都不导</b>——用户以为数据回来了。
 * 所以两条底线在解析之前就判死：文件得是我们家导出的（{@code meta} 那一段），
 * 而且里面至少得有一半内容。
 */
public final class ExportFileReader {

    /** zip 的魔数：CSV 产物是一个 zip。 */
    private static final int ZIP_MAGIC_0 = 0x50;   // 'P'
    private static final int ZIP_MAGIC_1 = 0x4B;   // 'K'

    /** UTF-8 BOM，{@code Csv.toBytes()} 每次都写它。 */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ExportFileReader() {
    }

    /**
     * 读一个导出文件。
     *
     * @param bytes    文件的全部字节（调用方已经限过大小，见 {@code ImportSheet}）
     * @param fileName 文件显示名，只用于报错时称呼它；可以是 null
     * @throws ImportFileException 读不了，或者读得出但不是我们的文件 / 里面没内容
     */
    public static ImportBundle read(byte[] bytes, @Nullable String fileName)
            throws ImportFileException {
        String name = fileName == null || fileName.trim().isEmpty() ? "file" : fileName.trim();
        if (bytes == null || bytes.length == 0) {
            throw new ImportFileException(ImportFileException.Reason.EMPTY,
                    name + " is empty.");
        }
        boolean zip = looksLikeZip(bytes);
        if (!zip && PydanticChatImport.recognizes(bytes)) {
            return PydanticChatImport.parse(bytes);
        }
        if (!zip && !looksLikeJson(bytes)) {
            throw new ImportFileException(ImportFileException.Reason.NOT_OUR_FILE,
                    name + " is not a ModelPilot export. Pick the .json file or the .zip "
                            + "file that Export data produced.");
        }

        ImportBundle bundle = zip
                ? ExportZipParser.parse(bytes)
                : ExportJsonParser.parse(stripBom(bytes));

        if (!bundle.facts.hasMeta) {
            // 是一个合法的 JSON 对象，但没有我们那段自述——**这不是我们的文件**。
            // 和"我们的文件但里面是空的"必须分开：前者用户要重选文件，后者要重新导一次。
            throw new ImportFileException(ImportFileException.Reason.NOT_OUR_FILE,
                    name + " is not a ModelPilot export (it has no meta section).");
        }
        if (!bundle.facts.isFromThisApp()) {
            throw new ImportFileException(ImportFileException.Reason.NOT_OUR_FILE,
                    name + " says it was exported by \"" + bundle.facts.app
                            + "\", not by ModelPilot.");
        }
        if (bundle.conversations.isEmpty() && bundle.usageCalls.isEmpty()) {
            throw new ImportFileException(ImportFileException.Reason.NO_CONTENT,
                    "No conversations and no usage records in this file"
                            + (bundle.problems.isEmpty()
                            ? "." : " (" + bundle.problems.size() + " unreadable rows)."));
        }
        return bundle;
    }

    private static boolean looksLikeZip(byte[] bytes) {
        return bytes.length > 3
                && (bytes[0] & 0xFF) == ZIP_MAGIC_0 && (bytes[1] & 0xFF) == ZIP_MAGIC_1;
    }

    /** 跳过 BOM 与空白之后是不是以 <code>{</code> 开头。 */
    private static boolean looksLikeJson(byte[] bytes) {
        int index = 0;
        if (startsWithBom(bytes)) {
            index = BOM.length;
        }
        while (index < bytes.length && Character.isWhitespace((char) (bytes[index] & 0xFF))) {
            index++;
        }
        return index < bytes.length && bytes[index] == '{';
    }

    private static boolean startsWithBom(byte[] bytes) {
        return bytes.length >= BOM.length
                && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2];
    }

    private static byte[] stripBom(byte[] bytes) {
        if (!startsWithBom(bytes)) {
            return bytes;
        }
        byte[] out = new byte[bytes.length - BOM.length];
        System.arraycopy(bytes, BOM.length, out, 0, out.length);
        return out;
    }

    /** 给 {@link ExportZipParser} 用：是不是一个能读的 zip 头（读不出来就当坏文件报）。 */
    static boolean isZip(byte[] bytes) {
        return looksLikeZip(bytes);
    }

    /** 给测试用：zip 的 InputStream 包装（这里集中一处，免得两个解析器各写一遍）。 */
    static ZipInputStream zipStream(byte[] bytes) {
        return new ZipInputStream(new java.io.ByteArrayInputStream(bytes));
    }
}
