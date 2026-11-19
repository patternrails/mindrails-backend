package com.mindrails.backend.tool.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShellToolsTest {

    @TempDir Path directory;

    @Test
    void bashUsesTheSuppliedDirectoryAndReportsOutputAndExitCode() throws Exception {
        var result = new BashTool().execute("printf '你好'; printf 'error' >&2; printf data > output.txt; exit 7",
                directory.toString(), 5.0);
        assertEquals("你好error", result.output());
        assertEquals(7, result.exitCode());
        assertFalse(result.timedOut());
        assertFalse(result.truncated());
        assertEquals("data", Files.readString(directory.resolve("output.txt")));
    }

    @Test
    void largeOutputIsBoundedAndCompleteOutputIsAvailable() throws Exception {
        var result = new BashTool().execute("for ((i=0; i<4000; i++)); do printf 'line-%s-中文\\n' \"$i\"; done",
                directory.toString(), 5.0);
        assertTrue(result.truncated());
        assertTrue(result.output().contains("line-3999-中文"));
        assertTrue(result.output().lines().count() <= 2000);
        assertFalse(result.output().contains("\uFFFD"));
        Path full = Path.of(result.fullOutputPath());
        try {
            assertEquals(4000, Files.readString(full).lines().count());
            assertTrue(Files.readString(full).startsWith("line-0-中文"));
        } finally {
            Files.delete(full);
        }
    }

    @Test
    void timeoutReturnsPartialOutputAndStopsTheCommand() throws Exception {
        var result = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                new BashTool().execute("printf started; sleep 5; printf done > finished", directory.toString(), 0.15));
        assertTrue(result.timedOut());
        assertNotEquals(0, result.exitCode());
        assertEquals("started", result.output());
        assertFalse(Files.exists(directory.resolve("finished")));
    }

    @Test
    void cancellationInterruptsAnExecutingCommand() throws Exception {
        Path pidFile = directory.resolve("pid");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> new BashTool().execute("echo $$ > pid; exec sleep 30", directory.toString(), null));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile));
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            future.cancel(true);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void rejectsInvalidTimeoutAndReportsMissingExecutable() throws Exception {
        for (double timeout : List.of(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> new BashTool().execute("true", directory.toString(), timeout));
        }
        var error = assertThrows(IOException.class, () -> ProcessSupport.run(
                List.of("mindrails-nonexistent-executable"), directory, 1.0, true));
        assertTrue(error.getMessage().contains("available on PATH"));
    }

    @Test
    void powershellRunsUnicodeScriptsWhenInstalled() throws Exception {
        try (var probe = ProcessSupport.run(List.of("pwsh", "--version"), directory, 5.0, true)) {
            assumeTrue(probe.exitCode() == 0, "pwsh is unavailable");
        } catch (IOException exception) {
            assumeTrue(false, "pwsh is not installed in this environment");
        }
        var result = new PowerShellTool().execute("[Console]::Write('你好'); Set-Content output.txt 'done'; exit 7",
                directory.toString(), 5.0);
        assertEquals("你好", result.output());
        assertEquals(7, result.exitCode());
        assertTrue(Files.readString(directory.resolve("output.txt")).contains("done"));
    }
}
