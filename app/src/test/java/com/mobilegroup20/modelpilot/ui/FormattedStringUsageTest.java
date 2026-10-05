package com.mobilegroup20.modelpilot.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * 带参数的字符串**必须带着参数用**：`getString(R.string.x)` / `setText(R.string.x)`
 * 这些写法**不做格式化**，屏幕上会原样印出 `%1$s` / `%1$d`。
 *
 * <p><b>为什么要有这条测试。</b>2026-10-05 一天之内在真机上撞了两次：
 * 「已登录：%1$s」和「其他设备上的 %1$d 个登录已失效」都是资源里带占位符、
 * 调用处只传了个 id。两次都是**编译通过、单测通过、只有人眼能发现**——
 * 而这类错最容易漏到答辩现场（屏幕上明晃晃一行 `%1$d`）。
 *
 * <p>扫描的是源码而不是运行界面：这条判据是"源码里有没有把带参字符串用在无参的位置"，
 * 静态就能查全，而且**不需要设备**（真机上一条条点过去根本覆盖不到
 * 几十个错误分支）。做法很朴素——把 `res/values/*.xml` 里带 `%n$` 的名字挑出来，
 * 再看 `src/main/java` 里有没有"消费字符串"的调用**没带参数**。
 *
 * <p>**找不到源码目录就直接失败**，不静默跳过：一条永远通过的测试比没有测试更坏
 * （这个仓库里为这件事写过好几次注释了）。
 */
public class FormattedStringUsageTest {

    /**
     * 会**消费**一个字符串资源 id 的调用形状。只认这些前缀是有意的：
     * 直接把 `R.string.x` 拿去比较（`if (id == R.string.x)`）不算违规，
     * 那样会把不是问题的地方一起报出来，报多了就没人看了。
     */
    private static final String[] SINKS = {
            "getString(", "setText(", "setError(", "setHint(",
            "setContentDescription(", "setTitle(", "setMessage(",
            ".setValue(", "setSummary(", "setTextColor(",
    };

    private static final Pattern SPECIFIER = Pattern.compile("%\\d+\\$[sd]");

    @Test
    public void everyStringWithFormatSpecifiersIsUsedWithItsArguments() throws IOException {
        Path module = moduleRoot();
        List<String> formatted = namesWithSpecifiers(module.resolve("src/main/res/values"));
        assertFalse("一个带参数的字符串都没找到？那说明资源目录读错了", formatted.isEmpty());

        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources(module.resolve("src/main/java"))) {
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            for (String name : formatted) {
                for (String sink : SINKS) {
                    // `R.string.x)` 紧跟着右括号 = 这个调用**没有**传格式化参数。
                    Pattern bare = Pattern.compile(
                            Pattern.quote(sink) + "R\\.string\\." + name + "\\s*\\)");
                    Matcher matcher = bare.matcher(text);
                    if (matcher.find()) {
                        offenders.add(source.getFileName() + " → " + sink + "R.string." + name + ")");
                    }
                }
            }
        }
        if (!offenders.isEmpty()) {
            fail("这些字符串带 %1$s/%1$d，但用在没有参数的位置（屏幕上会原样印出占位符）：\n  "
                    + String.join("\n  ", offenders));
        }
    }

    /** `res/values` 下所有带位置参数（`%1$s` 这种）的字符串名。 */
    private static List<String> namesWithSpecifiers(Path values) throws IOException {
        List<String> names = new ArrayList<>();
        assertTrue("资源目录不存在：" + values, Files.isDirectory(values));
        try (Stream<Path> files = Files.list(values)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml"))::iterator) {
                String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher entry = Pattern.compile(
                        "<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL).matcher(xml);
                while (entry.find()) {
                    if (SPECIFIER.matcher(entry.group(2)).find()) {
                        names.add(entry.group(1));
                    }
                }
            }
        }
        return names;
    }

    private static List<Path> javaSources(Path root) throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
        }
        return sources;
    }

    /**
     * 单元测试的工作目录由 Gradle 决定（不同 AGP 版本见过 `app/` 和仓库根两种），
     * 所以两个都试，找不到就报错——**不许悄悄跳过**。
     */
    private static Path moduleRoot() {
        for (String candidate : new String[]{"app", ".", "../app"}) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path.resolve("src/main/java"))) {
                return path;
            }
        }
        throw new AssertionError("找不到 app 模块目录，工作目录是：" + Paths.get(".").toAbsolutePath());
    }
}
