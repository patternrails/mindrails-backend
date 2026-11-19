package com.mindrails.backend.tool;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import java.util.List;
import java.util.stream.Collectors;

/** A display/legacy projection only. Native tool contents stay intact for model and memory. */
public final class ToolResultText {
    private ToolResultText() {}

    public static String from(List<Content> contents) {
        if (contents == null) return "";
        return contents.stream().map(content -> content instanceof TextContent text ? text.text()
                : "[Non-text tool content omitted: " + content.type() + "]").collect(Collectors.joining("\n"));
    }
}
