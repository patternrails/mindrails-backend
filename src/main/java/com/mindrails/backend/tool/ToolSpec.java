package com.mindrails.backend.tool;

import com.mindrails.backend.tool.spec.BashTool;
import com.mindrails.backend.tool.spec.EditTool;
import com.mindrails.backend.tool.spec.FindTool;
import com.mindrails.backend.tool.spec.GrepTool;
import com.mindrails.backend.tool.spec.LsTool;
import com.mindrails.backend.tool.spec.PowerShellTool;
import com.mindrails.backend.tool.spec.ReadTool;
import com.mindrails.backend.tool.spec.WriteTool;
import com.mindrails.backend.tool.web.WebSearchTool;

import dev.langchain4j.agent.tool.Tool;

import java.util.Arrays;
import java.util.List;

/** Local capability policy. Tool names come from the actual @Tool declarations. */
public enum ToolSpec {
    READ(ReadTool.class, false),
    LS(LsTool.class, false),
    FIND(FindTool.class, false),
    GREP(GrepTool.class, false),
    BASH(BashTool.class, false),
    POWERSHELL(PowerShellTool.class, false),
    WRITE(WriteTool.class, true),
    EDIT(EditTool.class, true),
    WEB_SEARCH(WebSearchTool.class, false),
    IMAGE_GENERATE(com.mindrails.backend.tool.spec.ImageGenerationTools.class, true);

    private final List<String> names;
    private final boolean workspaceWrite;

    ToolSpec(Class<? extends IdentifiedTool> type, boolean workspaceWrite) {
        this.names =
                Arrays.stream(type.getMethods())
                        .filter(m -> m.isAnnotationPresent(Tool.class))
                        .map(
                                m ->
                                        m.getAnnotation(Tool.class).name().isEmpty()
                                                ? m.getName()
                                                : m.getAnnotation(Tool.class).name())
                        .toList();
        this.workspaceWrite = workspaceWrite;
    }

    public boolean workspaceWrite() {
        return workspaceWrite;
    }

    public static ToolSpec named(String name) {
        return Arrays.stream(values()).filter(s -> s.names.contains(name)).findFirst().orElse(null);
    }

    public static boolean isWorkspaceWrite(String name) {
        var spec = named(name);
        return spec != null && spec.workspaceWrite();
    }
}
