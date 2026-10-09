package com.mobilegroup20.modelpilot.data.export;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.data.importer.ImportBundle;
import com.mobilegroup20.modelpilot.data.importer.ExportFileReader;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class PydanticTranscriptWriterTest {
    private MessageEntity message(String role,String text,long at){
        MessageEntity m=new MessageEntity();m.id=role+at;m.chatId="c";m.role=role;m.text=text;m.createdAtEpochMillis=at;return m;
    }
    private ExportArtifact output(MessageEntity...messages){return PydanticTranscriptWriter.write("c",Arrays.asList(messages));}
    @Test public void roundTripPreservesRolesTextAndTimestampAndCreatesNoLedger() throws Exception {
        ExportArtifact a=output(message("USER","你好\n42",1791532800123L),message("ASSISTANT","42",1791532801123L));
        ImportBundle b=ExportFileReader.read(a.bytes,a.fileName);
        assertEquals(2,b.messageCount());assertEquals("你好\n42",b.conversations.get(0).messages.get(0).text);
        assertEquals(1791532800123L,b.conversations.get(0).messages.get(0).createdAtEpochMillis);
        assertEquals("ASSISTANT",b.conversations.get(0).messages.get(1).role);assertTrue(b.usageCalls.isEmpty());
    }
    @Test public void sameRoleSameMillisecondDoesNotLoseTurnsOnReimport() throws Exception {
        ExportArtifact a=output(message("USER","First",1),message("USER","Second",1),message("ASSISTANT","Third",1));
        ImportBundle b=ExportFileReader.read(a.bytes,a.fileName);
        assertEquals(3,b.messageCount());assertEquals("Second",b.conversations.get(0).messages.get(1).text);
        assertEquals(1,b.conversations.get(0).messages.get(1).createdAtEpochMillis);
    }
    @Test public void serializationIsDeterministicAndUsesThreeUpstreamFields(){
        MessageEntity m=message("USER","a\"b\n\\c",1);ExportArtifact a=output(m);
        assertArrayEquals(a.bytes,output(m).bytes);
        String line=new String(a.bytes,StandardCharsets.UTF_8).trim();
        assertEquals(3,JsonParser.parseString(line).getAsJsonObject().size());assertEquals("text/plain",a.mimeType());
    }
    @Test public void structuralSecretsPathsAndLedgerAreNotExported(){
        MessageEntity m=message("ASSISTANT","Answer",1);m.attachmentsJson="PRIVATE_PATH";m.toolCallsJson="SECRET_TOOL";m.providerId="SECRET_PROVIDER";m.tokensIn=123456;
        String out=new String(output(m).bytes,StandardCharsets.UTF_8);
        assertFalse(out.contains("PRIVATE"));assertFalse(out.contains("SECRET"));assertFalse(out.contains("123456"));
    }
    @Test public void rejectsMixedConversationsRatherThanMergingTheirContexts(){
        MessageEntity m=message("USER","x",1);m.chatId="other";
        try{output(m);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("one conversation"));}
    }
    @Test public void rejectsUnsupportedRolesInsteadOfSilentlyDeletingAToolTurn(){
        for(String role:new String[]{"SYSTEM","TOOL","UNKNOWN"}){
            try{output(message(role,"secret",1));fail();}catch(IllegalArgumentException expected){assertFalse(expected.getMessage().contains("secret"));}
        }
    }
    @Test public void refusesMissingTimeOrTextRatherThanInventingThem(){
        for(MessageEntity m:new MessageEntity[]{message("USER","x",0),message("USER",null,1)}){
            try{output(m);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("missing"));}
        }
    }
    @Test public void emptyHistoryDoesNotProduceASuccessfulEmptyFile(){
        try{output();fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("no saved"));}
    }
}
