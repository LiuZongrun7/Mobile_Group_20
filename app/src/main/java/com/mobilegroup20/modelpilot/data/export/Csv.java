package com.mobilegroup20.modelpilot.data.export;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * RFC 4180 的 CSV 写出器。**只做一件事：把一张表变成字节**，不碰数据库也不碰界面。
 *
 * <p>手写而不是拿个库：这里要的行为只有「转义 + 拼接」，而库带来的额外东西
 * （列类型推断、表头大小写改写、自动加引号策略）恰好每一条都要再关掉一遍。
 *
 * <p><b>四个不显眼但会出事的点：</b>
 * <ol>
 *   <li><b>行尾用 {@code \r\n}</b>。RFC 4180 就是这么定的，而 Excel 对只有 {@code \n}
 *       的文件在某些区域设置下会把整个文件读成一行；</li>
 *   <li><b>开头写 UTF-8 BOM</b>。没有它，Excel 会按本机代码页解释这个文件，
 *       中文全是乱码——而「用 Excel 打开导出的 csv」几乎就是这个功能唯一的使用方式；</li>
 *   <li><b>{@code null} 是空单元格，不是 0、也不是字符串 "null"。</b>「不知道」在导出里
 *       必须还是「不知道」，这和界面上「未知不显示成 0」是同一条规矩；</li>
 *   <li><b>挡公式注入</b>：以 {@code = + - @} 或制表符/回车开头的单元格，前面补一个
 *       单引号。否则一条内容以 {@code =} 开头的消息，在 Excel 里会被当公式执行
 *       （CSV injection，能通过 {@code WEBSERVICE}/{@code DDE} 外带数据）。
 *       补引号会改动内容，所以这件事在导出的 {@code meta} 里明写着，不偷偷做。</li>
 * </ol>
 */
public final class Csv {

    /** UTF-8 BOM。写成字节而不是字符串，免得某一步再被编码一次。 */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String CRLF = "\r\n";

    /** 会被表格软件当成公式起头的字符，见类注释第 4 条。 */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    private final StringBuilder out = new StringBuilder();

    /** 表头列数。用来挡住「某一行少写一格」——那会让后面所有列整体错位。 */
    private final int columns;

    private int rows;

    public Csv(String... header) {
        this.columns = header.length;
        if (columns == 0) {
            throw new IllegalArgumentException("CSV 至少要有一列");
        }
        this.out.append(Arrays.stream(header).map(Csv::cell).reduce((a, b) -> a + "," + b).orElse(""));
        this.out.append(CRLF);
    }

    public static String cell(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw;
        // 公式注入的防护：**先补引号再谈转义**，否则那个引号会被当成字段内容的一部分。
        if (!value.isEmpty() && FORMULA_STARTERS.indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        boolean quote = value.indexOf('"') >= 0 || value.indexOf(',') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                // 首尾空白也加引号：不加的话有些表格软件会把它们吃掉，
                // 而"消息开头有几个空格"是内容的一部分。
                || (!value.isEmpty() && (Character.isWhitespace(value.charAt(0))
                || Character.isWhitespace(value.charAt(value.length() - 1))));
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** 可空的整数：{@code null} → 空单元格（见类注释第 3 条）。 */
    public static String number(Long value) {
        return value == null ? "" : Long.toString(value);
    }

    public static String number(long value) {
        return Long.toString(value);
    }

    /** 布尔写成 {@code true}/{@code false}，不写 1/0：这一列将来要用眼睛看。 */
    public static String bool(boolean value) {
        return value ? "true" : "false";
    }

    /** 追加一行。格数和表头不一致就抛——这是写代码时的错，不该变成一个列错位的文件。 */
    public void row(String... cells) {
        if (cells.length != columns) {
            throw new IllegalArgumentException(
                    "这一行有 " + cells.length + " 格，表头是 " + columns + " 列");
        }
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells[i]));
        }
        out.append(CRLF);
        rows++;
    }

    /** 数据行数（不含表头）。界面用它报「导出了多少条」。 */
    public int rows() {
        return rows;
    }

    /** BOM + 正文，UTF-8。 */
    public byte[] toBytes() {
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, all, 0, BOM.length);
        System.arraycopy(body, 0, all, BOM.length, body.length);
        return all;
    }
}
