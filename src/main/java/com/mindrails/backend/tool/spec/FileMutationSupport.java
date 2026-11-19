package com.mindrails.backend.tool.spec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;

/** Uses OS file locks instead of keeping a mutable lock registry in tool instances. */
final class FileMutationSupport {

    static FileLock lock(FileChannel channel) throws IOException {
        while (true) {
            ToolSupport.checkInterrupted();
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ignored) {
                // Another invocation in this JVM currently owns the lock.
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting to modify file");
            }
        }
    }

    static String read(FileChannel channel) throws IOException {
        var output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        channel.position(0);
        while (channel.read(buffer) != -1) {
            ToolSupport.checkInterrupted();
            output.write(buffer.array(), 0, buffer.position());
            buffer.clear();
        }
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString();
    }

    static void write(FileChannel channel, byte[] content) throws IOException {
        ToolSupport.checkInterrupted();
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.truncate(content.length);
    }
}
