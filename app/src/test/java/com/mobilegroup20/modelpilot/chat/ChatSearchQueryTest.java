package com.mobilegroup20.modelpilot.chat;
import org.junit.Test;
import static org.junit.Assert.*;
public class ChatSearchQueryTest {
    @Test public void wildcardCharactersAreLiteralRatherThanMatchEverything(){String slash=Character.toString((char)92);assertEquals("%50!%!_"+slash+"path%",ChatSearchQuery.literalPattern("50%_"+slash+"path"));}
    @Test public void escapeMarkerItselfIsLiteral(){assertEquals("%a!!b%",ChatSearchQuery.literalPattern("a!b"));}
    @Test public void nullAndWhitespaceDoNotBecomeAllHistorySearches(){assertEquals("",new ChatSearchQuery(null,null).text);assertEquals("",new ChatSearchQuery(" \n ",null).text);}
    @Test public void projectScopeIsPartOfQueryIdentity(){assertFalse(new ChatSearchQuery("a",null).sameAs(new ChatSearchQuery("a","")));assertFalse(new ChatSearchQuery("a","p1").sameAs(new ChatSearchQuery("a","p2")));assertTrue(new ChatSearchQuery(" a ","p1").sameAs(new ChatSearchQuery("a","p1")));}
    @Test public void inputLengthIsBoundedWithoutChangingStoredHistory(){assertEquals(160,new ChatSearchQuery(new String(new char[200]).replace('\0','x'),null).text.length());}
}
