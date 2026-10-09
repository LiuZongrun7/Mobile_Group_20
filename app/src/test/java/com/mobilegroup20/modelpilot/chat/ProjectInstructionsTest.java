package com.mobilegroup20.modelpilot.chat;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class ProjectInstructionsTest {
    private ContextEngine engine() { return new ContextEngine(ProviderRegistry.defaults(),"c",Arrays.asList("OPENAI","ANTHROPIC")); }
    private String payload(ContextEngine e,String p,String m) {
        RenderedContext c=e.render(p,m);return String.valueOf(c.payload.system)+String.valueOf(c.payload.messages);
    }
    @Test public void instructionsTravelAcrossBothProviderFormats() {
        ContextEngine e=engine();e.setProjectInstructions("Keep all original numbers.");e.append(CanonicalMessage.user("u","Hello",1));
        assertTrue(payload(e,"OPENAI","gpt-5-mini").contains("Keep all original numbers."));
        assertTrue(payload(e,"ANTHROPIC","claude-sonnet-4-6").contains("Keep all original numbers."));
    }
    @Test public void editingAndClearingInvalidatesCachedPayloads() {
        ContextEngine e=engine();e.setProjectInstructions("FIRST");assertTrue(payload(e,"OPENAI","gpt-5-mini").contains("FIRST"));
        e.setProjectInstructions("SECOND");String changed=payload(e,"OPENAI","gpt-5-mini");
        assertTrue(changed.contains("SECOND"));assertFalse(changed.contains("FIRST"));
        e.setProjectInstructions("");assertFalse(payload(e,"OPENAI","gpt-5-mini").contains("SECOND"));
    }
    @Test public void compressionNeverReplacesExplicitInstructions() {
        ContextEngine e=engine();e.setProjectInstructions("Answer in Chinese.");
        e.append(CanonicalMessage.user("old","A brief",1));e.append(CanonicalMessage.user("new","Continue",2));
        e.applyCompression("old","old","Short memory","p","m",0,0,3);
        assertTrue(payload(e,"OPENAI","gpt-5-mini").contains("Answer in Chinese."));
        assertFalse(e.messages().stream().anyMatch(m->m.id.equals("project-instructions")));
    }
    @Test public void instructionsAreIncludedInContextEstimateAndNeverSharedAcrossEngines() {
        ContextEngine a=engine(),b=engine();int before=a.currentTokens();
        a.setProjectInstructions(new String(new char[4000]).replace('\0','x'));
        assertTrue(a.currentTokens()>before+900);assertEquals(before,b.currentTokens());
    }
}
