package com.mobilegroup20.modelpilot.chat;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mobilegroup20.modelpilot.data.local.AppDatabase;
import com.mobilegroup20.modelpilot.chat.local.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Room tests: compile now; run on connected emulator/device, never count as JVM passes. */
@RunWith(AndroidJUnit4.class)
public class ChatHistoryTransactionTest {
    private AppDatabase database;
    private ChatDao dao;
    @Before public void create(){
        database=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().getTargetContext(),AppDatabase.class).build();
        dao=database.chatDao();
        ProjectEntity p=new ProjectEntity();p.id="p";p.name="Project";p.instructions="Keep 42";dao.upsertProject(p);
        ChatEntity c=new ChatEntity();c.id="c";c.projectId="p";dao.upsertChat(c);
        ChatEntity other=new ChatEntity();other.id="other";dao.upsertChat(other);
    }
    @After public void close(){database.close();}
    private MessageEntity reply(){MessageEntity m=new MessageEntity();m.id="a";m.chatId="c";m.role="ASSISTANT";m.text="42";m.createdAtEpochMillis=1;return m;}
    @Test public void historyIncludesOnlyTheActualChatAndItsProject(){
        dao.upsertMessage(reply());MessageEntity other=reply();other.id="o";other.chatId="other";dao.upsertMessage(other);
        ChatHistorySnapshot s=dao.readHistory("c");assertEquals("Keep 42",s.project.instructions);assertEquals(1,s.messages.size());assertEquals("c",s.messages.get(0).chatId);
        assertNull(dao.readHistory("other").project);
    }
    @Test public void completeReplyPersistsMessageAndModelBadgeTogether(){
        assertTrue(dao.completeReply("c",reply(),"DEEPSEEK","deepseek-chat",2));
        ChatHistorySnapshot s=dao.readHistory("c");assertEquals(1,s.messages.size());assertEquals("DEEPSEEK",s.chat.lastProviderId);assertEquals(2,s.chat.updatedAtEpochMillis);
    }
    @Test public void lateReplyCannotResurrectADeletedConversation(){
        dao.deleteChat("c");assertFalse(dao.completeReply("c",reply(),"p","m",2));assertTrue(dao.messages("c").isEmpty());
    }
    @Test public void mismatchedReplyRollsBackWithoutChangingTheBadge(){
        MessageEntity bad=reply();bad.chatId="other";
        try{dao.completeReply("c",bad,"p","m",2);fail();}catch(IllegalArgumentException expected){}
        assertTrue(dao.messages("c").isEmpty());assertNull(dao.chat("c").lastProviderId);
    }
    @Test public void missingHistoryIsAnErrorInsteadOfAnEmptyValidConversation(){
        try{dao.readHistory("missing");fail();}catch(IllegalStateException expected){}
    }
}
