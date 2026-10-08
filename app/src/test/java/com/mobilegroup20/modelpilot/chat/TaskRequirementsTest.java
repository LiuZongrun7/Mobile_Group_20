package com.mobilegroup20.modelpilot.chat;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;

public class TaskRequirementsTest {
    private CanonicalMessage image(String data) {
        return new CanonicalMessage("image", CanonicalMessage.Role.USER, "describe",
                Collections.singletonList(new CanonicalMessage.Attachment(CanonicalMessage.Attachment.Kind.IMAGE,
                        "photo.png", data, 100, null)), null, null, 1);
    }
    @Test public void extractedPdfUsesTextCapability() {
        assertEquals(TaskKind.TEXT, TaskRequirements.forAttachments(Collections.singletonList(
                new CanonicalMessage.Attachment(CanonicalMessage.Attachment.Kind.PDF,
                        "paper.pdf", "content://paper", 100, "Extracted text"))));
    }
    @Test public void historicalImageStillRequiresVisionOnTextFollowup() {
        assertEquals(TaskKind.IMAGE, TaskRequirements.forMessages(Arrays.asList(image("data:image/png;base64,AA"),
                CanonicalMessage.user("next", "What is in the corner?", 2))));
    }
    @Test public void validMemoryReplacesImageButInvalidMemoryDoesNotHideIt() {
        java.util.List<CanonicalMessage> messages = Arrays.asList(image("data:image/png;base64,AA"),
                CanonicalMessage.user("next", "continue", 2));
        Memory valid = new Memory("m", "c", "image", "image", "image description", "p", "m", 1, 0, 0);
        assertEquals(TaskKind.TEXT, TaskRequirements.forMessages(TaskRequirements.retained(messages,
                Collections.singletonList(valid))));
        Memory invalid = new Memory("m", "c", "image", "missing", "summary", "p", "m", 1, 0, 0);
        assertEquals(TaskKind.IMAGE, TaskRequirements.forMessages(TaskRequirements.retained(messages,
                Collections.singletonList(invalid))));
    }
    @Test public void imageBase64LengthDoesNotBecomeHundredsOfThousandsOfTextTokens() {
        ContextEngine a = new ContextEngine(ProviderRegistry.defaults(), "c", Arrays.asList("OPENAI"));
        ContextEngine b = new ContextEngine(ProviderRegistry.defaults(), "c", Arrays.asList("OPENAI"));
        a.append(image("data:image/png;base64,AA"));
        b.append(image("data:image/png;base64," + new String(new char[200000]).replace('\0', 'A')));
        assertEquals(a.currentTokens(), b.currentTokens());
        assertTrue(b.currentTokens() < 2000);
        assertEquals(TaskKind.IMAGE, b.requiredTask());
    }
    @Test public void anthropicBase64IsAlsoExcludedFromTextLength() {
        ContextEngine a = new ContextEngine(ProviderRegistry.defaults(), "c", Arrays.asList("ANTHROPIC"));
        ContextEngine b = new ContextEngine(ProviderRegistry.defaults(), "c", Arrays.asList("ANTHROPIC"));
        a.append(image("data:image/png;base64,AA"));
        b.append(image("data:image/png;base64," + new String(new char[200000]).replace('\0', 'A')));
        assertEquals(a.render("ANTHROPIC", "claude-sonnet-4-6").estimatedTokens,
                b.render("ANTHROPIC", "claude-sonnet-4-6").estimatedTokens);
    }

    @Test public void compressedImageNoLongerRequiresVisionInTheEngine() {
        ContextEngine engine = new ContextEngine(ProviderRegistry.defaults(), "c", Arrays.asList("OPENAI"));
        engine.append(image("data:image/png;base64,AA"));
        engine.append(CanonicalMessage.user("next", "continue", 2));
        engine.applyCompression("image", "image", "a cat", "p", "m", 0, 0, 3);
        assertEquals(TaskKind.TEXT, engine.requiredTask());
    }
}
