package com.mindrails.backend.mcp.service.internal.transport;

import com.mindrails.backend.mcp.model.McpConnectionConfig;

import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpJson;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializationNotification;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Parameter-array execution with an explicit environment; server stderr is drained without logging
 * secrets.
 */
public final class ManagedStdioTransport implements McpTransport {
    private final McpConnectionConfig config;
    private volatile Process process;
    private McpOperationHandler handler;
    private BufferedWriter writer;

    public ManagedStdioTransport(McpConnectionConfig config) {
        this.config = config;
    }

    @Override
    public synchronized void start(McpOperationHandler handler) {
        this.handler = handler;
        try {
            var command = new ArrayList<String>();
            command.add(config.command());
            command.addAll(config.args());
            var builder = new ProcessBuilder(command);
            var inherited = System.getenv();
            builder.environment().clear();
            for (String name : List.of("PATH", "HOME", "TMPDIR", "SystemRoot", "WINDIR", "LANG")) {
                if (inherited.containsKey(name)) {
                    builder.environment().put(name, inherited.get(name));
                }
            }
            builder.environment().putAll(config.env());
            if (config.cwd() != null) {
                builder.directory(new File(config.cwd()));
            }
            process = builder.start();
            var current = process;
            writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    current.getOutputStream(), StandardCharsets.UTF_8));
            Thread.startVirtualThread(
                    () -> {
                        try (var reader =
                                new BufferedReader(
                                        new InputStreamReader(
                                                current.getInputStream(),
                                                StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = McpFraming.readLine(reader)) != null) {
                                handler.onMessage(line);
                            }
                        } catch (Exception error) {
                            handler.cancelAllPendingOperations("MCP stdio stream failed");
                        } finally {
                            handler.cancelAllPendingOperations("MCP stdio stream closed");
                        }
                    });
            Thread.startVirtualThread(
                    () -> {
                        try (var stream = current.getErrorStream()) {
                            stream.transferTo(OutputStream.nullOutputStream());
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException error) {
            throw new IllegalStateException("MCP subprocess could not start");
        }
    }

    @Override
    public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request) {
        return sendRequest(request)
                .thenApply(
                        result -> {
                            sendMessage(new McpInitializationNotification());
                            return result;
                        });
    }

    @Override
    public CompletableFuture<String> sendRequest(McpClientMessage message) {
        var future = new CompletableFuture<String>();
        handler.expectResponse(message.getId(), future);
        try {
            write(message);
        } catch (RuntimeException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @Override
    public CompletableFuture<String> sendRequest(McpCallContext context) {
        return sendRequest(context.message());
    }

    @Override
    public void sendMessage(McpClientMessage message) {
        write(message);
    }

    @Override
    public void sendMessage(McpCallContext context) {
        write(context.message());
    }

    private synchronized void write(McpClientMessage message) {
        try {
            checkHealth();
            writer.write(McpJson.serialize(message));
            writer.newLine();
            writer.flush();
        } catch (IOException error) {
            throw new IllegalStateException("MCP stdio write failed");
        }
    }

    @Override
    public void checkHealth() {
        if (process == null || !process.isAlive()) {
            throw new IllegalStateException("MCP subprocess is unavailable");
        }
    }

    @Override
    public void onFailure(Runnable action) {}

    @Override
    public synchronized void close() {
        if (handler != null) {
            handler.cancelAllPendingOperations("MCP connection closed");
        }
        if (process != null) {
            var old = process;
            process = null;
            old.descendants().forEach(ProcessHandle::destroy);
            old.destroy();
            Thread.startVirtualThread(
                    () -> {
                        try {
                            if (!old.waitFor(1, TimeUnit.SECONDS)) {
                                old.descendants().forEach(ProcessHandle::destroyForcibly);
                                old.destroyForcibly();
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            old.destroyForcibly();
                        }
                    });
        }
    }
}
