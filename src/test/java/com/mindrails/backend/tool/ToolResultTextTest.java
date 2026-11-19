package com.mindrails.backend.tool;

import static org.junit.jupiter.api.Assertions.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import java.util.List;

class ToolResultTextTest {
    @Test void projectsEmptyAndMultipleTextPartsWithoutUsingSingleTextAccessor() {
        var multiple = ToolExecutionResult.builder().resultContents(List.of(TextContent.from("first"), TextContent.from("second"))).build();
        assertThrows(IllegalStateException.class, multiple::resultText);
        assertEquals("", ToolResultText.from(List.of()));
        assertEquals("first\nsecond", ToolResultText.from(multiple.resultContents()));
        assertEquals(2, multiple.resultContents().size());
    }
    @Test void binaryPartsAreSummarizedWithoutLoggingImagePayloads() {
        var image = ImageContent.from("private-base64-image", "image/png");
        var original = List.<Content>of(TextContent.from("Image result"), image);
        String display = ToolResultText.from(original);
        assertTrue(display.contains("IMAGE")); assertTrue(display.contains("Image result"));
        assertFalse(display.contains("private-base64-image"));
        assertSame(image, original.get(1));
    }
}
