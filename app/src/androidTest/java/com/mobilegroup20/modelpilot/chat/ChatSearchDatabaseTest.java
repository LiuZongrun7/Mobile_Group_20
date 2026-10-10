package com.mobilegroup20.modelpilot.chat;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mobilegroup20.modelpilot.chat.local.*;
import com.mobilegroup20.modelpilot.data.local.AppDatabase;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ChatSearchDatabaseTest {
    private AppDatabase database;private ChatDao dao;
    @Before public void open(){database=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().getTargetContext(),AppDatabase.class).build();dao=database.chatDao();}
    @After public void close(){database.close();}
    private void chat(String id,String title,String project,long at){ChatEntity c=new ChatEntity();c.id=id;c.title=title;c.projectId=project;c.updatedAtEpochMillis=at;dao.upsertChat(c);}
    private void project(String id,String name){ProjectEntity p=new ProjectEntity();p.id=id;p.name=name;p.instructions="PRIVATE_INSTRUCTION";dao.upsertProject(p);}
    private void message(String id,String chat,String text,String role,long at){MessageEntity m=new MessageEntity();m.id=id;m.chatId=chat;m.text=text;m.role=role;m.createdAtEpochMillis=at;dao.upsertMessage(m);}
    private List<ChatSearchResult> find(String text,String scope){ChatSearchQuery q=new ChatSearchQuery(text,scope);return dao.searchHistory(q.text,q.pattern,q.projectId,51);}
    @Test public void emptyQueryReturnsNoHistory(){chat("c","hello",null,1);assertTrue(find("",null).isEmpty());}
    @Test public void titleAndBodyBothMatchWithoutDuplicatingAConversation(){chat("c","hello",null,1);message("u","c","hello twice","USER",1);message("a","c","hello again","ASSISTANT",2);assertEquals(1,find("hello",null).size());assertEquals("hello again",find("hello",null).get(0).preview);}
    @Test public void percentUnderscoreAndBackslashAreLiteral(){String slash=Character.toString((char)92);chat("wild","50%_"+slash+"path",null,1);chat("plain","50otherpath",null,2);assertEquals("wild",find("%_"+slash,null).get(0).chatId);assertEquals(1,find("%",null).size());assertEquals(1,find("_",null).size());assertEquals(1,find(slash,null).size());}
    @Test public void userInputCannotInjectSql(){chat("c","hello",null,1);assertTrue(find("' OR 1=1 --",null).isEmpty());assertNotNull(dao.chat("c"));}
    @Test public void chineseAndAsciiCaseSearchSavedMessageText(){chat("c","Task",null,1);message("u","c","中文活动 BUDGET","USER",1);assertEquals(1,find("中文活动",null).size());assertEquals(1,find("budget",null).size());}
    @Test public void projectScopeAndUnfiledAreStrict(){project("p","Mobile course");chat("pchat","task","p",1);chat("u","task",null,2);chat("empty","task","",3);assertEquals(3,find("task",null).size());assertEquals(1,find("task","p").size());assertEquals(2,find("task","").size());assertTrue(find("task","missing").isEmpty());assertEquals("pchat",find("course",null).get(0).chatId);}
    @Test public void onlyMatchingBodyIsPreviewedAndLongMessagesAreBounded(){chat("c","task",null,1);String before=new String(new char[500]).replace('\0','x');message("hit","c",before+"needle"+before,"USER",1);message("new","c","nonmatching recent text","ASSISTANT",2);ChatSearchResult row=find("needle",null).get(0);assertTrue(row.preview.contains("needle"));assertTrue(row.preview.length()<=240);assertFalse(row.preview.contains("nonmatching"));}
    @Test public void systemToolsAndInstructionsAreNotSearched(){project("p","Project");chat("c","Task","p",1);message("s","c","secret","SYSTEM",1);message("t","c","secret","TOOL",2);assertTrue(find("secret",null).isEmpty());assertTrue(find("PRIVATE_INSTRUCTION",null).isEmpty());}
    @Test public void deletedChatsDoNotLeakOrphanedMessageResults(){chat("c","hello",null,1);message("m","c","hello","USER",1);dao.deleteChat("c");assertTrue(find("hello",null).isEmpty());}
    @Test public void resultsHaveDeterministicRecentOrderingAndSentinelLimit(){for(int i=0;i<60;i++)chat("c"+i,"matching",null,i);List<ChatSearchResult> rows=find("matching",null);assertEquals(51,rows.size());assertEquals("c59",rows.get(0).chatId);assertEquals("c9",rows.get(50).chatId);}
}
