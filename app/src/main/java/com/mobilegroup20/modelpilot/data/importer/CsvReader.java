package com.mobilegroup20.modelpilot.data.importer;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 的 CSV 读取器。**只做一件事：把字节变成一张表**，不碰数据库也不碰界面。
 *
 * <p>为什么手写而不是拿个库：要的行为只有「按引号规则切字段 + 吃 BOM + 认 CRLF」，
 * 而库带来的额外东西（列类型推断、空值策略、宽松模式）恰好每一条都要再关掉一遍。
 * 导出的写出器 {@code data/export/Csv} 也是手写的，两边对称着看能一眼对出问题。
 *
 * <h3>四个不显眼但会出事的点</h3>
 * <ol>
 *   <li><b>BOM 要去掉。</b>导出每个文件都以 UTF-8 BOM 开头（没有它 Excel 打开中文是乱码），
 *       不去掉的话第一个表头会变成 {@code \uFEFFid}，于是"按名字找列"全部落空，
 *       表现是"这份文件一条都读不出来"。</li>
 *   <li><b>引号里的换行是内容，不是行尾。</b>一条消息里必然有换行（Markdown 段落就是），
 *       按行切会把一条消息切成好几行，而那种错误在结果里表现为"多了几条莫名其妙的短消息"，
 *       很难查。</li>
 *   <li><b>引号里的 {@code ""} 是一个引号。</b>这是 RFC 4180 的转义，转不回来就会多出双引号。</li>
 *   <li><b>引号之后的空白要吃掉</b>（{@code "abc" , x} 这种从 Excel 存出来的写法），
 *       但<b>引号之外的空白是内容的一部分</b>——消息开头那几个空格是用户打的。</li>
 * </ol>
 *
 * <p>还有一条容错：<b>某个字段没闭合引号</b>（文件被截断）时，把已经读到的内容当作
 * 一条不完整的记录收下，而不是抛异常整份失败——剩下的行往往还是好的，
 * 而"少了一部分"由调用方计进 {@code problems} 报给用户。
 */
final class CsvReader {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final List<List<String>> rows = new ArrayList<>();

    private CsvReader(String text) {
        parse(text);
    }

    /**
     * 读一份 CSV。
     *
     * @param bytes 文件字节（UTF-8，可能带 BOM）
     * @return 行 × 列；空文件返回空表。<b>空行不返回</b>——导出不会写空行，
     *         而手工编辑过的文件里那种行没有意义。
     */
    static List<List<String>> read(byte[] bytes) {
        return new CsvReader(new String(stripBom(bytes), java.nio.charset.StandardCharsets.UTF_8))
                .rows;
    }

    private static byte[] stripBom(byte[] bytes) {
        if (bytes.length >= BOM.length
                && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]) {
            byte[] out = new byte[bytes.length - BOM.length];
            System.arraycopy(bytes, BOM.length, out, 0, out.length);
            return out;
        }
        return bytes;
    }

    private void parse(String text) {
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean cellStarted = false;
        // 只有"这一行从一开始就是空的"才整行丢掉；`a,,b` 中间那两个空字段是有意义的。
        boolean rowHasAnyCell = false;

        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (inQuotes) {
                if (c == '"') {
                    boolean doubled = index + 1 < text.length() && text.charAt(index + 1) == '"';
                    if (doubled) {
                        cell.append('"');
                        index += 2;
                        continue;
                    }
                    inQuotes = false;
                    index++;
                    // 引号闭合之后的空白属于分隔符那一侧，不是内容：
                    // `"abc" , x` 是 Excel 存出来的常见写法，留着会让每个字段尾巴多两个空格。
                    // 但**引号之外**的空白是内容（消息开头的空格是用户打的），所以只在
                    // "刚刚闭合"这个时刻吃。
                    while (index < text.length()
                            && (text.charAt(index) == ' ' || text.charAt(index) == '\t')) {
                        index++;
                    }
                    continue;
                }
                cell.append(c);
                index++;
                continue;
            }
            if (c == '"') {
                // 引号只能出现在字段开头；出现在中间（`ab"c`）就当普通字符，不重开一个字段——
                // 那种写法本来就不合法，而"尽量读出来"比"整份失败"对用户更有用。
                if (!cellStarted) {
                    inQuotes = true;
                    cellStarted = true;
                } else {
                    cell.append(c);
                }
                index++;
                continue;
            }
            if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                cellStarted = false;
                rowHasAnyCell = true;
                index++;
                continue;
            }
            if (c == '\r' || c == '\n') {
                // CRLF 是一个行尾，不是两个。
                if (c == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                index++;
                if (rowHasAnyCell || cellStarted || cell.length() > 0) {
                    row.add(cell.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                cell.setLength(0);
                cellStarted = false;
                rowHasAnyCell = false;
                continue;
            }
            cell.append(c);
            cellStarted = true;
            index++;
        }

        // 最后一行可能没有行尾；不完整（引号没闭合）的那一行也照样收下（见类注释）。
        if (rowHasAnyCell || cellStarted || cell.length() > 0) {
            row.add(cell.toString());
            rows.add(row);
        }
    }

    /**
     * 把一张表按表头名字索引起来。
     *
     * <p>下一个"按名字找列"的小包装，而不是让每个解析器自己数第几列：列的顺序是导出那边
     * 定的，将来中间插一列我们这边就会整体错位——那是这类代码最容易出的错，
     * 而按名字取列让它变成一个"取到 null、那一格留空"的小问题。
     */
    static final class Table {

        /** 表头 → 列号；文件里没有的列不在这里。 */
        private final java.util.Map<String, Integer> columns = new java.util.LinkedHashMap<>();
        private final List<List<String>> rows;
        /** 表头那一行在原文里的位置（0 = 第一行），报错时用。 */
        private final int headerLine;

        private Table(List<List<String>> rows) {
            this.rows = rows;
            this.headerLine = 0;
            if (!rows.isEmpty()) {
                List<String> header = rows.get(0);
                for (int i = 0; i < header.size(); i++) {
                    String name = header.get(i) == null ? "" : header.get(i).trim();
                    if (!name.isEmpty() && !columns.containsKey(name)) {
                        columns.put(name, i);
                    }
                }
            }
        }

        /** 表头在不在。<b>不在就当这份文件不是我们导的</b>（见调用方）。 */
        static boolean hasHeader(List<List<String>> rows, String... required) {
            Table table = new Table(rows);
            for (String column : required) {
                if (!table.has(column)) {
                    return false;
                }
            }
            return true;
        }

        static Table of(List<List<String>> rows) {
            return new Table(rows);
        }

        /** 数据行（不含表头）。 */
        List<List<String>> data() {
            return rows.isEmpty() ? java.util.Collections.<List<String>>emptyList()
                    : rows.subList(1, rows.size());
        }

        int size() {
            return Math.max(0, rows.size() - 1);
        }

        boolean has(String column) {
            return columns.containsKey(column);
        }

        /** 取一格；列不存在、行不够长、值是空串，都返回 {@code null}（= 不知道）。 */
        @Nullable
        String get(List<String> row, String column) {
            Integer index = columns.get(column);
            if (index == null || row == null || index >= row.size()) {
                return null;
            }
            String value = row.get(index);
            if (value == null) {
                return null;
            }
            // 空单元格 = 不知道，不是空字符串：导出那边「不知道就留空」，
            // 这里要能把它和"真的写了空串"区分开——两者在库里的写法不同
            // （token 0 vs 消息正文确实是空的）。
            return value.isEmpty() ? null : value;
        }

        /** 取一格，空当空串（写进不能为 null 的列）。 */
        String getOrEmpty(List<String> row, String column) {
            String value = get(row, column);
            return value == null ? "" : value;
        }

        /** 取一格整数；没有 / 读不懂给 {@code fallback}。 */
        long getLong(List<String> row, String column, long fallback) {
            String value = get(row, column);
            if (value == null) {
                return fallback;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException notANumber) {
                return fallback;
            }
        }

        /** 取一格可空整数：没有 / 读不懂就是"不知道"。 */
        @Nullable
        Long getNullableLong(List<String> row, String column) {
            String value = get(row, column);
            if (value == null) {
                return null;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }

        /** 时间：先认 {@code _ms} 那列，没有才退回解析 ISO 列。 */
        long getMillis(List<String> row, String millisColumn, String isoColumn) {
            long direct = getLong(row, millisColumn, 0L);
            if (direct > 0L) {
                return direct;
            }
            return ImportValues.parseIso(get(row, isoColumn));
        }

        int headerLine() {
            return headerLine;
        }
    }
}
