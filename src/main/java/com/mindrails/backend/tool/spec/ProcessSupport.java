package com.mindrails.backend.tool.spec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Per-call processes and output files; no shared process or stream state. */
final class ProcessSupport {

    static Execution run(List<String> command, Path directory, Double timeout, boolean mergeError)
            throws IOException, InterruptedException {
        if (!Files.isDirectory(directory)) throw new NotDirectoryException(directory.toString());
        if (timeout != null && (!Double.isFinite(timeout) || timeout <= 0 || timeout > 2_147_483)) {
            throw new IllegalArgumentException("timeout must be a finite positive number of seconds, at most 2147483");
        }
        ToolSupport.checkInterrupted();
        Path output = Files.createTempFile("mindrails-tool-", ".out");
        Path error = null;
        Process process = null;
        Set<ProcessHandle> descendants = new HashSet<>();
        boolean completed = false;
        long started = System.nanoTime();
        try {
            error = Files.createTempFile("mindrails-tool-", ".err");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectOutput(output.toFile())
                    .redirectError(error.toFile())
                    .redirectErrorStream(mergeError);
            try {
                process = builder.start();
            } catch (IOException exception) {
                throw new IOException("Unable to start '" + command.getFirst()
                        + "'. Ensure the executable is installed and available on PATH.", exception);
            }
            process.getOutputStream().close();
            boolean timedOut = false;
            while (true) {
                process.descendants().forEach(descendants::add);
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) break;
                if (timeout != null && (System.nanoTime() - started) / 1_000_000_000.0 >= timeout) {
                    timedOut = true;
                    terminate(process, descendants);
                    if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Process did not terminate after timeout");
                    break;
                }
            }
            completed = true;
            return new Execution(output, error, process.exitValue(), timedOut,
                    (System.nanoTime() - started) / 1_000_000_000.0);
        } catch (InterruptedException exception) {
            if (process != null) terminate(process, descendants);
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            if (!completed) {
                if (process != null) terminate(process, descendants);
                Files.deleteIfExists(output);
                if (error != null) Files.deleteIfExists(error);
            }
        }
    }

    private static void terminate(Process process, Set<ProcessHandle> knownDescendants) {
        process.descendants().forEach(knownDescendants::add);
        knownDescendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    static Captured readOutput(Path file, boolean tail) throws IOException {
        long size = Files.size(file);
        int length = (int) Math.min(size, ToolSupport.MAX_OUTPUT_BYTES);
        ByteBuffer bytes = ByteBuffer.allocate(length);
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (tail) channel.position(size - length);
            while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
        }
        int start = 0;
        if (tail && size > length) {
            while (start < bytes.position() && (bytes.array()[start] & 0xc0) == 0x80) start++;
        }
        String output = new String(bytes.array(), start, bytes.position() - start, StandardCharsets.UTF_8);
        List<String> lines = output.lines().toList();
        boolean lineLimit = lines.size() > 2000;
        if (lineLimit) {
            int from = tail ? lines.size() - 2000 : 0;
            output = String.join("\n", lines.subList(from, from + 2000));
        }
        return new Captured(output, size > length || lineLimit);
    }

    record Captured(String text, boolean truncated) {}

    record Execution(Path output, Path error, int exitCode, boolean timedOut, double wallTimeSeconds)
            implements AutoCloseable {

        @Override
        public void close() throws IOException {
            try {
                Files.deleteIfExists(output);
            } finally {
                Files.deleteIfExists(error);
            }
        }
    }
}
