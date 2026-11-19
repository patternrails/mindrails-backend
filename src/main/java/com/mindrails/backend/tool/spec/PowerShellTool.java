package com.mindrails.backend.tool.spec;

import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Stateless PowerShell execution using PowerShell 7's cross-platform pwsh executable. */
public final class PowerShellTool implements IdentifiedTool {

    public static final UUID ID = UUID.fromString("981ae532-b2f7-42d5-bb0e-d958aa382cae");

    @Override
    public UUID id() {
        return ID;
    }

    @Tool(name = "powershell", value = "Execute a PowerShell script in the supplied working directory. "
            + "For shell searches, prefer ripgrep (rg) when available. Use rg --files to list files. "
            + "Requires PowerShell 7 (pwsh) on PATH. Returns combined UTF-8 stdout/stderr, exitCode, timedOut and elapsed seconds. "
            + "A nonzero exitCode or timedOut indicates failure. Keeps the last 2000 lines or 50 KiB, "
            + "saving complete truncated output at fullOutputPath. No timeout unless specified.")
    public BashTool.Result execute(
            @P(name = "command", value = "PowerShell command or script to execute.") String command,
            @P(name = "path", value = "Required working directory for this command.") String path,
            @P(name = "timeout", value = "Optional positive timeout in seconds. No default timeout.", required = false) Double timeout)
            throws IOException, InterruptedException {
        ToolSupport.requireText(command, "command");
        String script = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)\n"
                + "$OutputEncoding = [Console]::OutputEncoding\n" + command;
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return ShellSupport.execute(List.of("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded),
                path, timeout);
    }
}
