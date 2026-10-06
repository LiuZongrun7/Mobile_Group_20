package com.mobilegroup20.modelpilot.data.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * 导入这一屏上那几句话。**它们错了会骗人**，所以每一句都有一个测试：
 * 本机已经有 0 条时不能说得像"有 0 条重复"、结果里不能只说写进去多少、
 * 文件没写版本号时不能编一个。
 */
public class ImportWordingTest {

    private static final long AT = 1_791_271_800_000L;

    @Test
    public void freshPhoneSaysNoneAreHereYet() {
        ImportPreview preview = previewOne(0, conversation("ch1"));

        List<String> lines = ImportWording.previewLines(preview);

        assertTrue(lines.toString(), lines.contains(
                "None of these conversations are on this phone yet."));
        assertFalse("不能写成 '0 of these…'（读起来像有 0 条重复）",
                lines.toString().contains("0 of these"));
    }

    @Test
    public void existingConversationsAreCountedInWords() {
        ImportPreview preview = previewTwo(1, conversation("ch1"), conversation("ch2"));

        List<String> lines = ImportWording.previewLines(preview);

        assertTrue(lines.toString(),
                lines.contains("1 of these conversations are already on this phone."));
    }

    @Test
    public void usageRangeIsSpelledOutBecauseItIsEasyToMisread() {
        ImportPreview preview = previewOne(0, conversation("ch1"),
                usage("c1", AT), usage("c2", AT + 86_400_000L));

        List<String> lines = ImportWording.previewLines(preview);

        assertTrue(lines.toString(), lines.contains("Usage records cover 2026-10-06 → 2026-10-07."));
    }

    @Test
    public void duplicateIdsInTheFileAreCalledOut() {
        // 借 DataImporter.preview 来产生"文件内自带重复"这份预览：判断哪条算重复
        // 只应该在那一处（这里手写一份 duplicateChatIds 等于把它的逻辑抄了一遍）。
        ImportBundle bundle = bundleWith(Arrays.asList(conversation("ch1"), conversation("ch1")),
                Collections.<UsageCall>emptyList());
        ImportPreview preview = new DataImporter(new NoChatsTarget()).preview(bundle);

        List<String> lines = ImportWording.previewLines(preview);

        assertTrue(lines.toString(), lines.contains(
                "This file lists the same conversation id more than once, so it was probably "
                        + "edited by hand."));
    }

    @Test
    public void resultLineSaysHowManyRowsCouldNotBeRead() {
        ImportSummary summary = new ImportSummary(MergePolicy.ADD_ONLY,
                1, 0, 0, 2, 0, 0, 0, Arrays.asList("a", "b", "c"));

        String line = ImportWording.resultLine(summary);

        assertTrue(line, line.contains("added 1 conversations"));
        assertTrue("只说写进去多少会让残缺的文件看起来是完整的",
                line.contains("3 rows in this file could not be read"));
    }

    @Test
    public void resultLineSaysNothingNewWhenNothingWasWritten() {
        ImportSummary summary = new ImportSummary(MergePolicy.ADD_ONLY,
                0, 0, 1, 0, 0, 0, 0, Collections.<String>emptyList());

        assertEquals("Nothing new in this file", ImportWording.resultLine(summary));
    }

    @Test
    public void factsLineDoesNotInventAVersion() {
        ImportFacts silent = new ImportFacts(null, null, 0L, null, null, null, true, false, true);

        assertEquals("This file doesn't say which version of the app exported it.",
                ImportWording.factsLine(silent));
    }

    @Test
    public void factsLineShowsVersionAndTimeWhenPresent() {
        ImportFacts facts = new ImportFacts("ModelPilot", "1.0", AT, "local", null, null,
                true, false, true);

        String line = ImportWording.factsLine(facts);

        assertTrue(line, line.startsWith("Exported by ModelPilot 1.0"));
        assertTrue(line, line.contains("+08:00"));
    }

    @Test
    public void sourceLineNamesTheFileItsSizeAndItsFormat() {
        assertEquals("modelpilot-export-20261006-1530.json · 22 KB · JSON",
                ImportWording.sourceLine("modelpilot-export-20261006-1530.json", "22 KB", "JSON"));
    }

    /** 一台空手机（本机一条对话都没有）。 */
    private static final class NoChatsTarget implements ImportTarget {

        @Override
        public List<String> existingChatIds() {
            return Collections.emptyList();
        }

        @Override
        public void writeProject(com.mobilegroup20.modelpilot.chat.local.ProjectEntity project) {
        }

        @Override
        public void writeChat(ChatEntity chat) {
        }

        @Override
        public void writeMessage(MessageEntity message) {
        }

        @Override
        public void writeMemory(com.mobilegroup20.modelpilot.chat.local.MemoryEntity memory) {
        }

        @Override
        public int writeUsage(List<com.mobilegroup20.modelpilot.data.local.UsageCallEntity> calls) {
            return 0;
        }
    }

    // ==================== 小工具 ====================

    private static ImportPreview preview(int alreadyOnDevice, ImportBundle.Conversation... items) {
        return preview(alreadyOnDevice, items, null);
    }

    private static ImportPreview preview(int alreadyOnDevice, ImportBundle.Conversation[] items,
                                         UsageCall[] calls) {
        return previewWith(alreadyOnDevice, Arrays.asList(items),
                calls == null ? Collections.<UsageCall>emptyList() : Arrays.asList(calls));
    }

    /** 一条对话 + 若干条用量的版本（名字不同是为了不和上面那个 varargs 产生歧义）。 */
    private static ImportPreview previewOne(int alreadyOnDevice, ImportBundle.Conversation first,
                                            UsageCall... calls) {
        return previewWith(alreadyOnDevice, Collections.singletonList(first),
                Arrays.asList(calls));
    }

    /** 两份对话的版本（上面那个 varargs 和它会产生歧义，所以单独一个名字）。 */
    private static ImportPreview previewTwo(int alreadyOnDevice, ImportBundle.Conversation a,
                                            ImportBundle.Conversation b) {
        return previewWith(alreadyOnDevice, Arrays.asList(a, b),
                Collections.<UsageCall>emptyList());
    }

    private static ImportBundle bundleWith(List<ImportBundle.Conversation> items,
                                           List<UsageCall> calls) {
        ImportFacts facts = new ImportFacts("ModelPilot", "1.0", AT, "local", null, null,
                true, !calls.isEmpty(), true);
        return new ImportBundle("JSON", facts, ImportBundle.Coverage.FULL,
                null, items, calls, null);
    }

    private static ImportPreview previewWith(int alreadyOnDevice,
                                             List<ImportBundle.Conversation> items,
                                             List<UsageCall> calls) {
        ImportBundle bundle = bundleWith(items, calls);
        List<String> existing = new java.util.ArrayList<>();
        for (int i = 0; i < alreadyOnDevice && i < items.size(); i++) {
            existing.add(items.get(i).chat.id);
        }
        return new ImportPreview(bundle, existing, null);
    }

    private static ImportBundle.Conversation conversation(String id) {
        ChatEntity chat = new ChatEntity();
        chat.id = id;
        chat.title = id;
        chat.createdAtEpochMillis = AT;
        chat.updatedAtEpochMillis = AT;
        MessageEntity message = new MessageEntity();
        message.id = id + "-m1";
        message.chatId = id;
        message.role = "USER";
        message.text = "x";
        message.createdAtEpochMillis = AT;
        return new ImportBundle.Conversation(chat, Collections.singletonList(message),
                Collections.<com.mobilegroup20.modelpilot.chat.local.MemoryEntity>emptyList());
    }

    private static UsageCall usage(String id, long at) {
        UsageCall call = new UsageCall();
        call.id = id;
        call.provider = Provider.DEEPSEEK;
        call.model = "deepseek-chat";
        call.startedAtEpochMillis = at;
        call.source = UsageCall.Source.APP;
        return call;
    }
}
