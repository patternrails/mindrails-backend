package com.mindrails.backend.tool.spec;

import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Stateless bash command execution. */
public final class BashTool implements IdentifiedTool {

    public static final UUID ID = UUID.fromString("e64a1303-f95b-4d35-a5dc-26f683a51d5e");

    @Override
    public UUID id() {
        return ID;
    }

    @Tool(name = "bash", value = "Execute a bash command in the supplied working directory. "
            + "Returns combined stdout/stderr, exitCode, timedOut and elapsed seconds. A nonzero exitCode or timedOut "
            + "indicates failure. Keeps the last 2000 lines or 50 KiB; fullOutputPath points to complete output if truncated. "
            + "For shell searches, prefer ripgrep (rg) when available. Use rg --files to list files. "
            + "Requires bash on PATH. No timeout is applied unless specified.")
    public Result execute(
            @P(name = "command", value = "Bash command or script to execute.") String command,
            @P(name = "path", value = "Required working directory for this command.") String path,
            @P(name = "timeout", value = "Optional positive timeout in seconds. No default timeout.", required = false) Double timeout)
            throws IOException, InterruptedException {
        ToolSupport.requireText(command, "command");
        return ShellSupport.execute(List.of("bash", "-c", command), path, timeout);
    }

    public record Result(String output, int exitCode, boolean timedOut, boolean truncated,
            String fullOutputPath, double wallTimeSeconds) {}
}
