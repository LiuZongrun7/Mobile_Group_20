package com.mobilegroup20.modelpilot.chat;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * 「打开原文件」这条路的判断部分：<b>纯字符串、纯计算、不碰 Android</b>，所以能用 JUnit 钉住。
 *
 * <h3>为什么需要它</h3>
 * 附件在库里存两种东西，两种的"能不能再打开"完全不一样：
 * <ul>
 *   <li><b>图片</b>存的是 data URL（整张图就在字符串里）——**永远打得开**，
 *       哪怕原来那张图被用户删了、换了手机、清了相册；</li>
 *   <li><b>PDF</b>存的是 {@code content://} 那个 uri，正文另存在 {@code extractedText} 里。
 *       uri 能不能打开取决于系统那一刻还给不给我们权限：SAF 的授权默认活到进程结束，
 *       重启之后多半已经失效；用户把文件删了、或者那是别的 App 的私有文档，也会失效。</li>
 * </ul>
 * 所以这个类要回答的不是"有没有附件"，而是<b>"这个附件现在打开会成功吗"</b>——
 * 答错了的后果是用户点了一下、系统弹一个"无法打开文件"，而他不知道那是文件没了
 * 还是我们弄坏了。宁可我们自己先说清楚。
 *
 * <h3>为什么图片不用存原文件路径</h3>
 * 一条能成立的设计是"干脆把原图路径也存下来"——但那条路要求 SAF 的持久化授权
 * （{@code takePersistableUriPermission}），而它并不是所有 provider 都支持，
 * 失败时用户看到的是"发出去时好好的，之后再也打不开"。图片的字节本来就在库里，
 * 写一份到缓存再打开，反而是唯一一条**不依赖任何外部状态**的路。
 */
public final class AttachmentOpen {

    /** data URL 的前缀。 */
    private static final String DATA_PREFIX = "data:";

    /** base64 段的分隔符。 */
    private static final String BASE64_MARK = ";base64,";

    /** 认得的图片类型 → 缓存文件的后缀。**白名单**：type 是从字符串里读出来的外部输入。 */
    private static final String[][] IMAGE_TYPES = {
            {"image/png", ".png"},
            {"image/jpeg", ".jpg"},
            {"image/jpg", ".jpg"},
            {"image/webp", ".webp"},
            {"image/gif", ".gif"},
            {"image/heic", ".heic"},
            {"image/heif", ".heif"},
            {"image/bmp", ".bmp"},
    };

    /** 内嵌图片的 mime 拿不到时用它：绝大多数截图与照片都是这两类之一。 */
    private static final String FALLBACK_TYPE = "image/png";

    private AttachmentOpen() {
    }

    /**
     * 这个附件现在能不能打开。
     *
     * <p>能打开只有两种情况：内嵌图片（data URL），或者 uri 还在（PDF 那个）。
     * 别的都算打不开——包括"只有抽取正文"的 PDF（那正文是给我们看/给模型读的，
     * 不是原文件）。
     */
    public static boolean canOpen(@Nullable CanonicalMessage.Attachment attachment) {
        if (attachment == null) {
            return false;
        }
        if (isDataUrl(attachment.uri)) {
            // data URL **还得是 base64 的那种**：百分号转义的 data URL 我们不产、
            // 也没写解码器，说它能打开只会让系统拿到一段垃圾字节。
            return attachment.kind != CanonicalMessage.Attachment.Kind.PDF
                    && base64Payload(attachment.uri) != null;
        }
        return hasUri(attachment.uri);
    }

    /** 内嵌在 data URL 里的图片（图片那条路走的就是它）。 */
    public static boolean isEmbeddedImage(@Nullable CanonicalMessage.Attachment attachment) {
        return attachment != null
                && attachment.kind == CanonicalMessage.Attachment.Kind.IMAGE
                && base64Payload(attachment.uri) != null;
    }

    /**
     * 从 data URL 里取出 base64 那段；不是 data URL 就是 {@code null}。
     *
     * <p>必须在逗号之后找 {@code ;base64,}：png/jpeg 的 base64 里可能出现
     * {@code data:} 这样的子串，从头搜会切错位置，切错的后果是一张解不开的图。
     */
    @Nullable
    public static String base64Payload(@Nullable String dataUrl) {
        if (!isDataUrl(dataUrl)) {
            return null;
        }
        int mark = dataUrl.indexOf(BASE64_MARK);
        if (mark < 0) {
            return null;    // 非 base64 的 data URL（百分号转义那种）我们不产，也不认
        }
        String payload = dataUrl.substring(mark + BASE64_MARK.length());
        return payload.isEmpty() ? null : payload;
    }

    /** data URL 里写的 mime（{@code data:image/png;base64,...} → {@code image/png}）；读不出来给 null。 */
    @Nullable
    public static String embeddedType(@Nullable String dataUrl) {
        if (!isDataUrl(dataUrl)) {
            return null;
        }
        int semicolon = dataUrl.indexOf(';');
        if (semicolon < 0) {
            semicolon = dataUrl.indexOf(',');
        }
        if (semicolon <= DATA_PREFIX.length()) {
            return null;
        }
        String type = dataUrl.substring(DATA_PREFIX.length(), semicolon).trim()
                .toLowerCase(Locale.ROOT);
        return type.isEmpty() ? null : type;
    }

    /**
     * 写进缓存时用的文件名：{@code attachment-<序号>-<原名>.<后缀>}。
     *
     * <p><b>为什么名字要清洗</b>：原名来自文件名（用户/系统给的），直接拼进路径的话，
     * 一个叫 {@code ../../databases/modelpilot.db} 的文件名就能让我们把缓存写进别处。
     * 这里只留下"字母数字和 {@code . _ -}"，其余换成下划线；后缀则由**mime 白名单**决定，
     * 不从原名里取——后缀决定系统用哪个 App 打开它。
     */
    public static String cacheFileName(int index, @Nullable String originalName,
                                       @Nullable String mimeType) {
        String stem = sanitize(originalName);
        if (stem.isEmpty()) {
            stem = "attachment";
        }
        return "attachment-" + index + "-" + stem + extensionFor(mimeType);
    }

    /** mime → 后缀；认不出就用 {@code .img}（不猜 png，猜错会让系统用错 App 打开）。 */
    public static String extensionFor(@Nullable String mimeType) {
        String type = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        for (String[] pair : IMAGE_TYPES) {
            if (pair[0].equals(type)) {
                return pair[1];
            }
        }
        return ".img";
    }

    /** 系统打开它时要用的 mime；读不出来就给 {@link #FALLBACK_TYPE}。 */
    public static String openMimeType(@Nullable String embeddedMime) {
        String type = embeddedMime == null ? "" : embeddedMime.trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            return FALLBACK_TYPE;
        }
        for (String[] pair : IMAGE_TYPES) {
            if (pair[0].equals(type)) {
                return type;
            }
        }
        // 只认图片类型：data URL 里写个 application/pdf 我们也没有那份 PDF 的字节
        // （图片这条路上 bytes 是图片本身），照着打开只会让系统报"文件已损坏"。
        return FALLBACK_TYPE;
    }

    /**
     * 打不开时该说哪句话。
     *
     * <p>两类分开说，因为用户能做的事不一样：
     * <ul>
     *   <li>{@link Reason#EMPTY}：这条消息上本来就没有可打开的东西（比如只导入了文字）；</li>
     *   <li>{@link Reason#GONE}：有过，但现在打不开了（文件被删/换机/授权过期）。</li>
     * </ul>
     */
    public enum Reason {
        EMPTY,
        GONE
    }

    public static Reason reasonFor(@Nullable CanonicalMessage.Attachment attachment) {
        if (attachment == null) {
            return Reason.EMPTY;
        }
        // 有抽取正文的 PDF 说明"原文件曾经在"——那种情况是过期/被删，不是从来没有。
        boolean hadSomething = hasUri(attachment.uri)
                || (attachment.extractedText != null && !attachment.extractedText.isEmpty());
        return hadSomething ? Reason.GONE : Reason.EMPTY;
    }

    private static boolean isDataUrl(@Nullable String uri) {
        return uri != null && uri.startsWith(DATA_PREFIX);
    }

    private static boolean hasUri(@Nullable String uri) {
        if (uri == null || uri.trim().isEmpty() || isDataUrl(uri)) {
            return false;
        }
        String lower = uri.toLowerCase(Locale.ROOT);
        // 只有系统能解析的 scheme 才算"有 uri"：content:// 与 file:// 是我们的两种来源，
        // 别的（http 之类）我们没有下载器，说"能打开"是骗人。
        return lower.startsWith("content://") || lower.startsWith("file://");
    }

    /** 只留字母数字与 {@code . _ -}；顺带砍掉路径分隔符与前导点（防 {@code ../}）。 */
    private static String sanitize(@Nullable String name) {
        if (name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length() && out.length() < 48; i++) {
            char c = name.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            out.append(safe ? c : '_');
        }
        String cleaned = out.toString().replace("..", "_");
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }
}
