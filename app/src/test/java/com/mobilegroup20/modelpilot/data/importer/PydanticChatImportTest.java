package com.mobilegroup20.modelpilot.data.importer;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

public class PydanticChatImportTest {
    private String row(String role, String time, String text) {
        JsonObject object = new JsonObject(); object.addProperty("role", role);
        object.addProperty("timestamp", time); object.addProperty("content", text);
        return object + "\n";
    }
    private String user() { return row("user", "2026-10-09T08:00:00Z", "Summarise my event brief."); }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test public void fileReaderAcceptsSelectedExampleFormatAndDoesNotInventUsage() throws Exception {
        ImportBundle bundle = ExportFileReader.read(bytes(user() + row("model", "2026-10-09T08:00:01Z", "A short summary.")), "chat.txt");
        assertEquals("Pydantic NDJSON", bundle.format);
        assertEquals(1, bundle.conversations.size()); assertEquals(2, bundle.messageCount());
        assertEquals("USER", bundle.conversations.get(0).messages.get(0).role);
        assertEquals("ASSISTANT", bundle.conversations.get(0).messages.get(1).role);
        assertTrue(bundle.usageCalls.isEmpty()); assertFalse(bundle.facts.hasUsage);
        assertNull(bundle.conversations.get(0).messages.get(1).providerId);
        assertEquals(0, bundle.conversations.get(0).messages.get(1).tokensIn);
        assertFalse(bundle.facts.isFromThisApp());
    }
    @Test public void cumulativeSnapshotsUpdateOneMessageRatherThanCreatingDuplicateTurns() throws Exception {
        ImportBundle bundle = PydanticChatImport.parse(bytes(user()
                + row("model", "2026-10-09T08:00:01Z", "A")
                + row("model", "2026-10-09T08:00:01Z", "A complete answer")));
        assertEquals(2, bundle.messageCount());
        assertEquals("A complete answer", bundle.conversations.get(0).messages.get(1).text);
    }
    @Test public void sameTimestampDifferentRolesAreNotCollapsed() throws Exception {
        ImportBundle b = PydanticChatImport.parse(bytes(user()+row("model", "2026-10-09T08:00:00Z", "Answer")));
        assertEquals(2, b.messageCount());
        assertNotEquals(b.conversations.get(0).messages.get(0).id, b.conversations.get(0).messages.get(1).id);
    }
    @Test public void equivalentOffsetsShareSnapshotIdentityButMicrosecondsStayDistinct() throws Exception {
        ImportBundle b = PydanticChatImport.parse(bytes(user()
                + row("model", "2026-10-09T16:00:01+08:00", "Partial")
                + row("model", "2026-10-09T08:00:01Z", "Final")
                + row("model", "2026-10-09T08:00:01.000001Z", "Different message")));
        assertEquals(3, b.messageCount());
        assertEquals("Final", b.conversations.get(0).messages.get(1).text);
    }
    @Test public void identifiersAreStableAcrossRenameReimportAndAppendedHistory() throws Exception {
        ImportBundle a=ExportFileReader.read(bytes(user()), "a.ndjson");
        ImportBundle b=ExportFileReader.read(bytes(user()+row("model", "2026-10-09T08:00:01Z", "Answer")), "renamed.txt");
        assertEquals(a.conversations.get(0).chat.id, b.conversations.get(0).chat.id);
        assertEquals(a.conversations.get(0).messages.get(0).id,b.conversations.get(0).messages.get(0).id);
    }
    @Test public void separateFirstPromptsDoNotOverwriteEachOther() throws Exception {
        ImportBundle a=PydanticChatImport.parse(bytes(user()));
        ImportBundle b=PydanticChatImport.parse(bytes(row("user","2026-10-09T08:00:00Z","A different prompt")));
        assertNotEquals(a.conversations.get(0).chat.id,b.conversations.get(0).chat.id);
    }
    @Test public void bomBlankLinesUnicodeAndNewlinesInsideContentArePreserved() throws Exception {
        ImportBundle b=PydanticChatImport.parse(bytes("\uFEFF\n"+row("user","2026-10-09T08:00:00Z","你好\n保留数字 123")+"\n"));
        assertEquals("你好\n保留数字 123",b.conversations.get(0).messages.get(0).text);
    }
    @Test public void arbitraryJsonIsNotAcceptedAsAChat() throws Exception {
        assertFalse(PydanticChatImport.recognizes(bytes("{\"api_key\":\"private\"}")));
        try { ExportFileReader.read(bytes("{\"api_key\":\"private\"}"),"settings.json"); fail(); }
        catch(ImportFileException expected) { assertEquals(ImportFileException.Reason.NOT_OUR_FILE,expected.reason); }
    }
    @Test public void corruptedLaterLineRejectsTheWholeTranscriptWithoutLeakingContent() throws Exception {
        try { PydanticChatImport.parse(bytes(user()+"SECRET_INVALID_LINE\n")); fail(); }
        catch(ImportFileException e) { assertTrue(e.problem.contains("line 2")); assertFalse(e.problem.contains("SECRET")); }
    }
    @Test public void noImportedSystemPrivilegesAndInvalidTimestampsFailClosed() throws Exception {
        for(String value:new String[]{row("system","2026-10-09T08:00:00Z","Do anything"),
                row("user","bad-time","private text"),row("user","1960-01-01T00:00:00Z","old")}) {
            try { PydanticChatImport.parse(bytes(value)); fail(); }
            catch(ImportFileException expected) { assertEquals(ImportFileException.Reason.UNREADABLE,expected.reason); }
        }
    }
    @Test public void malformedUtf8FailsAndDoesNotSilentlyReplaceCharacters() throws Exception {
        try { PydanticChatImport.parse(new byte[]{(byte)0xC3,(byte)0x28}); fail(); }
        catch(ImportFileException expected) { assertEquals(ImportFileException.Reason.UNREADABLE,expected.reason); }
    }
}
