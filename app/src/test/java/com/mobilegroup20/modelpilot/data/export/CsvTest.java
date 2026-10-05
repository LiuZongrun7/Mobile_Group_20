package com.mobilegroup20.modelpilot.data.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * CSV 写出器的规矩。都是「错了不会报错、只会让文件在别人的表格软件里变成另一份数据」
 * 的那一类，所以逐条钉住。
 */
public class CsvTest {

    private static String text(Csv csv) {
        String all = new String(csv.toBytes(), StandardCharsets.UTF_8);
        // 开头三个字节是 BOM，断言正文时去掉（BOM 本身另有测试盯着）。
        return all.startsWith("\uFEFF") ? all.substring(1) : all;
    }

    @Test
    public void startsWithUtf8BomSoExcelDoesNotMangleChinese() {
        byte[] bytes = new Csv("名字").toBytes();
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
    }

    @Test
    public void usesCrlfLineEndings() {
        Csv csv = new Csv("a", "b");
        csv.row("1", "2");
        assertEquals("a,b\r\n1,2\r\n", text(csv));
    }

    @Test
    public void quotesFieldsWithCommasQuotesAndNewlines() {
        Csv csv = new Csv("text");
        csv.row("a,b");                 // 逗号
        csv.row("say \"hi\"");          // 引号 → 双写
        csv.row("line1\nline2");        // 换行
        csv.row(" 前后有空格 ");         // 首尾空白
        csv.row("普通");
        assertEquals("text\r\n"
                + "\"a,b\"\r\n"
                + "\"say \"\"hi\"\"\"\r\n"
                + "\"line1\nline2\"\r\n"
                + "\" 前后有空格 \"\r\n"
                + "普通\r\n", text(csv));
    }

    @Test
    public void nullIsAnEmptyCellNotZeroAndNotNull() {
        Csv csv = new Csv("n");
        csv.row(Csv.number((Long) null));
        assertEquals("n\r\n\r\n", text(csv));

        Csv numbers = new Csv("n");
        numbers.row(Csv.number(0L));    // 真的是 0 就写 0：0 和"不知道"必须分得开
        assertEquals("n\r\n0\r\n", text(numbers));
    }

    /**
     * 公式注入：一条以 {@code =} 开头的消息在 Excel 里会被当公式执行。
     * 补的那个单引号会改动内容，所以它在 {@code meta} 里明写着（见 DataExporter 的 notes）。
     */
    @Test
    public void neutralisesFormulaCells() {
        assertEquals("'=1+1", Csv.cell("=1+1"));
        assertEquals("'+1", Csv.cell("+1"));
        assertEquals("'-1", Csv.cell("-1"));
        assertEquals("'@SUM(A1)", Csv.cell("@SUM(A1)"));
        // 只是出现在中间就没事，不该被改。
        assertEquals("1+1=2", Csv.cell("1+1=2"));
    }

    @Test
    public void refusesRowsThatDoNotMatchTheHeader() {
        Csv csv = new Csv("a", "b");
        try {
            csv.row("only-one");
            fail("少一格的行使后面的列整体错位，必须当场抛");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("1"));
        }
    }
}
