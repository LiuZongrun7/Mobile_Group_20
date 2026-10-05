package com.mobilegroup20.modelpilot.chat;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.InputStream;

/**
 * PDF 的本机文字抽取（大纲 §4-4/§7.2）：**文件不出手机**，只把抽出来的正文交给模型。
 *
 * <p>三条上限都是必须的，不是"防君子"：
 *
 * <ol>
 *   <li><b>页数上限</b>：一份 300 页的标书抽出来几十万字，塞进上下文只会得到
 *       一句"超出上下文长度"，而用户不知道自己踩了什么。</li>
 *   <li><b>字数上限</b>：同上，且抽出来的正文还要进库（`attachments_json`）、进请求。</li>
 *   <li><b>抽不到文字就是抽不到</b>：扫描件（图片型 PDF）没有文字层，
 *       `PDFTextStripper` 会返回空串。这时**明确说"这份 PDF 里没有可提取的文字
 *       （可能是扫描件）"**，而不是发一段空内容让模型自己猜——大纲把 OCR 明确列为
 *       暂不做，那就得让用户知道边界在哪。</li>
 * </ol>
 *
 * <p>返回 {@link Result} 而不是抛异常：调用方要拿"抽到了多少、为什么没抽到"去写提示，
 * 而异常只适合表达"程序坏了"。
 */
public final class PdfText {

    /** 最多抽这么多页。 */
    public static final int MAX_PAGES = 40;
    /** 最多留这么多字符（约 3 万 token 量级，正文里够用了）。 */
    public static final int MAX_CHARS = 100_000;

    /** 抽取结果。 */
    public static final class Result {
        public final String text;
        public final int pages;
        /** 是不是被上限截断了（**要说出来**，不许静默截断）。 */
        public final boolean truncated;
        /** 没抽到字时的原因（给用户看的一句话）；成功时为 null。 */
        public final String problem;

        Result(String text, int pages, boolean truncated, String problem) {
            this.text = text;
            this.pages = pages;
            this.truncated = truncated;
            this.problem = problem;
        }

        public boolean ok() {
            return problem == null && !text.isEmpty();
        }
    }

    private PdfText() {
    }

    /**
     * 抽一份 PDF。
     *
     * <p>**会阻塞**（还要读流、解压），只能在后台线程调。
     */
    public static Result extract(Context context, InputStream in) {
        if (in == null) {
            return new Result("", 0, false, "empty");
        }
        // PDFBox-Android 要先初始化资源（字体等），漏了会在解析某些 PDF 时抛
        // "no resource found"，而报错点在库内部，看不出跟初始化有关。
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try (PDDocument document = PDDocument.load(in)) {
            int total = document.getNumberOfPages();
            int pages = Math.min(total, MAX_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            String text = stripper.getText(document);
            if (text == null) {
                text = "";
            }
            text = text.trim();
            if (text.isEmpty()) {
                // 扫描件：没有文字层。OCR 我们不做（大纲明确排除），所以照实说。
                return new Result("", total, false, "scanned");
            }
            boolean truncated = total > pages || text.length() > MAX_CHARS;
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS);
            }
            return new Result(text, pages, truncated, null);
        } catch (Exception failed) {
            android.util.Log.e("ModelPilot", "PDF 抽取失败", failed);
            // 加密的、坏掉的、格式不标准的都会走到这里。分类太细对用户没用，
            // 他能做的只有换一个文件。
            return new Result("", 0, false, "failed");
        }
    }
}
