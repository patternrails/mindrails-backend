package com.mindrails.backend.mcp.service.internal.transport;

import java.io.BufferedReader;
import java.io.IOException;

/** Bounds line-delimited JSON and SSE frames before accumulating them in memory. */
final class McpFraming {
    private McpFraming() {}

    static String readLine(BufferedReader reader) throws IOException {
        var line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                return line.toString();
            }
            if (c != '\r') {
                line.append((char) c);
            }
            if (line.length() > 1048576) {
                throw new IOException("MCP response exceeds 1 MiB");
            }
        }
        return line.isEmpty() ? null : line.toString();
    }
}
