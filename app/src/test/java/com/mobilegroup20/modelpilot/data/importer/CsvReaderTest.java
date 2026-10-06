package com.mobilegroup20.modelpilot.data.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

/**
 * CSV 读取器的规矩。它要能吃下<b>导出器写出来的任何东西</b>
 * （{@code CsvTest} 钉住的是写出来的形状，这里钉住的是读回来的形状——
 * 两边合起来才是"往返不丢东西"）。
 *
 * <p>这个文件的每条用例都对应一个"不写出来就一定会踩"的坑：BOM 让表头对不上、
 * 引号里的换行被当成行尾、{@code ""} 没还原、Excel 存出来的引号后空白。
 */
public class CsvReaderTest {

    private static List<List<String>> read(String text) {
        return CsvReader.read(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void readsASimpleTable() {
        List<List<String>> rows = read("a,b\r\n1,2\r\n3,4\r\n");

        assertEquals(3, rows.size());
        assertEquals(java.util.Arrays.asList("a", "b"), rows.get(0));
        assertEquals(java.util.Arrays.asList("1", "2"), rows.get(1));
        assertEquals(java.util.Arrays.asList("3", "4"), rows.get(2));
    }

    @Test
    public void stripsTheUtf8BomFromTheFirstHeader() {
        String withBom = "\uFEFF" + "id,name\r\nm1,你好\r\n";

        List<List<String>> rows = read(withBom);

        assertEquals("BOM 不去掉，第一个表头就变成 \uFEFFid，按名字找列会全部落空",
                "id", rows.get(0).get(0));
        assertEquals("你好", rows.get(1).get(1));
    }

    @Test
    public void keepsNewlinesInsideQuotedFields() {
        List<List<String>> rows = read("id,text\r\nm1,\"第一行\n第二行\"\r\nm2,别的\r\n");

        assertEquals("引号里的换行是内容，不是行尾", 3, rows.size());
        assertEquals("第一行\n第二行", rows.get(1).get(1));
        assertEquals("m2", rows.get(2).get(0));
    }

    @Test
    public void unescapesDoubledQuotesAndCommasInsideQuotes() {
        List<List<String>> rows = read("id,text\r\nm1,\"他说 \"\"好\"\"，然后走了\"\r\n");

        assertEquals("他说 \"好\"，然后走了", rows.get(1).get(1));
    }

    @Test
    public void keepsLeadingSpacesButEatsWhitespaceAfterAClosingQuote() {
        List<List<String>> rows = read("id,text\r\nm1,  前面有空格\r\nm2,\"带引号\"  \r\n");

        assertEquals("消息开头的空格是用户打的，不能吃", "  前面有空格", rows.get(1).get(1));
        assertEquals("Excel 存出来的引号后空白要吃掉", "带引号", rows.get(2).get(1));
    }

    @Test
    public void emptyCellsAreEmptyStringsNotSkips() {
        List<List<String>> rows = read("a,b,c\r\n1,,3\r\n");

        assertEquals(3, rows.get(1).size());
        assertEquals("", rows.get(1).get(1));
    }

    @Test
    public void skipsEmptyLines() {
        List<List<String>> rows = read("a,b\r\n\r\n1,2\r\n");

        // 表头 + 一行数据 = 两行；中间那个空行是手工编辑留下的痕迹，不变成第三行。
        assertEquals("空行不该变成一行空数据：" + rows, 2, rows.size());
        assertEquals(java.util.Arrays.asList("1", "2"), rows.get(1));
    }

    @Test
    public void acceptsALastLineWithoutATrailingNewline() {
        List<List<String>> rows = read("a,b\r\n1,2");

        assertEquals(2, rows.size());
        assertEquals("2", rows.get(1).get(1));
    }

    @Test
    public void aTruncatedQuoteDoesNotLoseTheWholeFile() {
        // 文件被截断：最后一条消息的引号没闭合。剩下的行仍然是好的，必须留下来。
        List<List<String>> rows = read("id,text\r\nm1,\"完整的\"\r\nm2,\"被截断的");

        assertEquals(3, rows.size());
        assertEquals("完整的", rows.get(1).get(1));
        assertEquals("被截断的", rows.get(2).get(1));
    }

    @Test
    public void tableIndexesColumnsByNameNotByPosition() {
        List<List<String>> rows = read("text,id\r\n你好,m1\r\n");

        CsvReader.Table table = CsvReader.Table.of(rows);

        assertTrue(table.has("id"));
        assertTrue(table.has("text"));
        assertFalse(table.has("chat_id"));
        assertEquals("m1", table.get(table.data().get(0), "id"));
        assertEquals("你好", table.get(table.data().get(0), "text"));
        assertNull("没有这一列就是不知道，不是空串", table.get(table.data().get(0), "chat_id"));
        assertEquals(1, table.size());
    }

    @Test
    public void emptyCellReadsAsUnknownButGetOrEmptyGivesAnEmptyString() {
        List<List<String>> rows = read("id,name\r\nm1,\r\n");

        CsvReader.Table table = CsvReader.Table.of(rows);
        List<String> row = table.data().get(0);

        assertNull(table.get(row, "name"));
        assertEquals("", table.getOrEmpty(row, "name"));
        assertEquals(0L, table.getLong(row, "missing", 0L));
        assertNull(table.getNullableLong(row, "missing"));
    }

    @Test
    public void millisArePreferredOverTheHumanReadableTime() {
        List<List<String>> rows = read("created_at,created_at_ms\r\n2026-10-06T15:30:12+08:00,1791271812000\r\n");

        CsvReader.Table table = CsvReader.Table.of(rows);

        assertEquals(1_791_271_812_000L,
                table.getMillis(table.data().get(0), "created_at_ms", "created_at"));
    }

    @Test
    public void isoTimeIsUsedWhenTheMillisColumnWasDeletedByHand() {
        List<List<String>> rows = read("created_at\r\n2026-10-06T15:30:12+08:00\r\n");

        CsvReader.Table table = CsvReader.Table.of(rows);

        assertEquals("用户删掉毫秒列之后，文件仍然要能用",
                1_791_271_812_000L, table.getMillis(table.data().get(0), "created_at_ms", "created_at"));
    }

    @Test
    public void headerCheckRejectsATableThatIsNotOurs() {
        List<List<String>> rows = read("foo,bar\r\n1,2\r\n");

        assertFalse(CsvReader.Table.hasHeader(rows, "chat_id", "message_id"));
        assertTrue(CsvReader.Table.hasHeader(rows, "foo"));
    }
}
