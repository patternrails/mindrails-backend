package com.mindrails.backend.tool.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class ShellSupport {

    static BashTool.Result execute(List<String> arguments, String path, Double timeout)
            throws IOException, InterruptedException {
        try (var execution = ProcessSupport.run(arguments, ToolSupport.path(path), timeout, true)) {
            var output = ProcessSupport.readOutput(execution.output(), true);
            String fullOutputPath = null;
            if (output.truncated()) {
                Path fullOutput = execution.output().resolveSibling(execution.output().getFileName() + ".log");
                Files.move(execution.output(), fullOutput, StandardCopyOption.REPLACE_EXISTING);
                fullOutputPath = fullOutput.toString();
            }
            return new BashTool.Result(output.text(), execution.exitCode(), execution.timedOut(),
                    output.truncated(), fullOutputPath, execution.wallTimeSeconds());
        }
    }
}
