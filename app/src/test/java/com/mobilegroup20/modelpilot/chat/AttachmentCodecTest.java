package com.mobilegroup20.modelpilot.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mobilegroup20.modelpilot.chat.CanonicalMessage.Attachment;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 附件的落库形态往返（`message.attachments_json`）。
 *
 * <p>为什么它值得单测：这是**附件能不能活过重启**的唯一保证——
 * 图片是以 data URL 存在这一列里的，读写少一个字段（比如 `uri` 或抽出来的正文），
 * 表现是"重新打开这条对话，图还在气泡里、但再问一句模型却看不见它"，
 * 而那要跑一次真请求才发现。
 */
public class AttachmentCodecTest {

    @Test public void an_image_round_trips_with_its_data_url() {
        Attachment image = new Attachment(Attachment.Kind.IMAGE, "poster.png",
                "data:image/png;base64,AAAA", 2048, null);

        List<Attachment> back = AttachmentCodec.fromJson(
                AttachmentCodec.toJson(Collections.singletonList(image)));

        assertEquals(1, back.size());
        assertEquals(Attachment.Kind.IMAGE, back.get(0).kind);
        assertEquals("poster.png", back.get(0).fileName);
        assertEquals("data:image/png;base64,AAAA", back.get(0).uri);
        assertEquals(2048, back.get(0).bytes);
        assertNull("图片没有抽出来的正文", back.get(0).extractedText);
    }

    @Test public void extracted_text_survives() {
        Attachment pdf = new Attachment(Attachment.Kind.PDF, "brief.pdf", "content://x", 48_000,
                "活动在周五 19:00 开始");
        List<Attachment> back = AttachmentCodec.fromJson(
                AttachmentCodec.toJson(Collections.singletonList(pdf)));
        assertEquals("抽出来的正文必须留住——PDF 就是靠它进上下文的",
                "活动在周五 19:00 开始", back.get(0).extractedText);
    }

    @Test public void several_attachments_keep_their_order() {
        List<Attachment> back = AttachmentCodec.fromJson(AttachmentCodec.toJson(Arrays.asList(
                new Attachment(Attachment.Kind.IMAGE, "a.png", "data:,a", 1, null),
                new Attachment(Attachment.Kind.IMAGE, "b.png", "data:,b", 2, null))));
        assertEquals(2, back.size());
        assertEquals("a.png", back.get(0).fileName);
        assertEquals("b.png", back.get(1).fileName);
    }

    @Test public void empty_stays_empty_and_is_not_written_as_brackets() {
        // "没有附件"和"附件是空数组"是一回事，存 null 更省事：库里那一列就是 NULL。
        assertNull(AttachmentCodec.toJson(Collections.<Attachment>emptyList()));
        assertNull(AttachmentCodec.toJson(null));
        assertTrue(AttachmentCodec.fromJson(null).isEmpty());
        assertTrue(AttachmentCodec.fromJson("").isEmpty());
    }

    @Test public void a_record_we_cannot_read_does_not_break_the_conversation() {
        // 一条读不懂的旧记录不该让整条对话打不开；认不出的类型只是被跳过。
        assertTrue(AttachmentCodec.fromJson("not json at all").isEmpty());
        assertTrue(AttachmentCodec.fromJson("{\"kind\":\"IMAGE\"}").isEmpty());
        assertEquals(1, AttachmentCodec.fromJson(
                "[{\"kind\":\"SOMETHING_NEW\",\"fileName\":\"x\"},"
                        + "{\"kind\":\"IMAGE\",\"fileName\":\"ok.png\",\"uri\":\"data:,x\",\"bytes\":3}]")
                .size());
    }
}
