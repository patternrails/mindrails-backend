package com.mindrails.backend.agent.api.websocket;

import static com.mindrails.backend.agent.api.websocket.protocol.ServerMessageType.*;
import com.mindrails.backend.agent.api.websocket.protocol.ServerMessage;
import com.mindrails.backend.agent.api.websocket.protocol.ServerMessageType;
import com.mindrails.backend.agent.api.websocket.protocol.StreamPayloads.TextDelta;
import com.mindrails.backend.agent.api.websocket.protocol.StreamPayloads.ToolArgumentsDelta;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.AgentSessionScope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

/** One ordered, byte-bounded queue and one virtual writer per connection. Producers never perform socket I/O. */
final class OutboundMessageSender {
    @Builder
    record Draft(ServerMessageType type, UUID requestId, Object data) {
        static Draft from(StreamProtocol.Event event) {
            return new Draft(event.type(), event.requestId(), event.data());
        }
    }

    private static final CloseStatus SLOW_CLIENT = new CloseStatus(1013, "Outbound transport limit exceeded");
    private final WebSocketSession rawSession;
    private final WebSocketSession session;
    private final AgentSessionScope agentSessionScope;
    private final JsonMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final Thread writer;
    private long sequence;
    private long bufferedBytes;
    private boolean closed;

    private static final class Frame {
        ServerMessage<?> envelope;
        WebSocketMessage<?> message;
        int bytes;
        long due;
        Runnable afterSend;
    }

    public OutboundMessageSender(WebSocketSession session, AgentSessionChatStreamGateway.AgentSessionScope agentSessionScope, JsonMapper mapper, ScheduledExecutorService scheduler) {
        this.rawSession = session;
        this.session = new ConcurrentWebSocketSessionDecorator(session,
                AgentWebSocketProperties.sendTimeoutMillis, AgentWebSocketProperties.outboundBytes);
        this.agentSessionScope = agentSessionScope;
        this.mapper = mapper;
        this.scheduler = scheduler;
        writer = Thread.ofVirtual().name("agent-ws-writer-" + session.getId()).start(this::writeLoop);
    }

    public boolean send(Draft draft) { return send(draft, null); }

    public boolean send(Draft draft, Runnable afterSend) {
        boolean overflow = false;
        try {
            synchronized (this) {
                if (closed) return false;
                Frame tail = queue.peekLast();
                if (afterSend == null && mergeable(tail, draft)) {
                    Object combined = merge(tail.envelope.data(), draft.data());
                    var e = tail.envelope;
                    var merged = new ServerMessage<>(1, e.type(), e.agentId(), e.sessionId(), e.requestId(),
                            e.seq(), e.timestamp(), combined);
                    String json = mapper.writeValueAsString(merged);
                    int bytes = utf8(json);
                    overflow = bufferedBytes - tail.bytes + bytes > AgentWebSocketProperties.outboundBytes;
                    if (!overflow) {
                        bufferedBytes += bytes - tail.bytes;
                        tail.envelope = merged;
                        tail.message = new TextMessage(json);
                        tail.bytes = bytes;
                        if (utf8(deltaText(combined)) >= AgentWebSocketProperties.batchBytes) tail.due = 0;
                    }
                } else {
                    if (tail != null) tail.due = 0; // Flush a prior delta before every boundary.
                    var envelope = new ServerMessage<>(1, draft.type(), agentSessionScope.agentId(), agentSessionScope.sessionId(),
                            draft.requestId(), sequence + 1, Instant.now(), draft.data());
                    String json = mapper.writeValueAsString(envelope);
                    int bytes = utf8(json);
                    overflow = bufferedBytes + bytes > AgentWebSocketProperties.outboundBytes || queue.size() >= 1024;
                    if (!overflow) {
                        sequence++;
                        Frame frame = new Frame();
                        frame.envelope = envelope;
                        frame.message = new TextMessage(json);
                        frame.bytes = bytes;
                        frame.afterSend = afterSend;
                        frame.due = isTextDelta(draft) && utf8(deltaText(draft.data())) < AgentWebSocketProperties.batchBytes
                                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AgentWebSocketProperties.batchMillis) : 0;
                        queue.add(frame);
                        bufferedBytes += bytes;
                    }
                }
                notifyAll();
            }
        } catch (RuntimeException exception) {
            close(CloseStatus.SERVER_ERROR);
            return false;
        }
        if (overflow) close(SLOW_CLIENT);
        return !overflow;
    }

    private static boolean isTextDelta(Draft draft) {
        return ((draft.type() == ANSWER_DELTA || draft.type() == THINKING_DELTA) && draft.data() instanceof TextDelta)
                || (draft.type() == TOOL_ARGUMENTS_DELTA && draft.data() instanceof ToolArgumentsDelta);
    }

    private static String deltaText(Object data) {
        return data instanceof TextDelta text ? text.text() : ((ToolArgumentsDelta) data).text();
    }

    private static Object merge(Object previous, Object next) {
        if (previous instanceof TextDelta old && next instanceof TextDelta text)
            return new TextDelta(old.round(), old.messageId(), old.text() + text.text());
        ToolArgumentsDelta old = (ToolArgumentsDelta) previous, tool = (ToolArgumentsDelta) next;
        return new ToolArgumentsDelta(old.round(), old.toolCallId(), tool.toolName() != null ? tool.toolName() : old.toolName(),
                old.text() + tool.text(), tool.state());
    }

    private static boolean mergeable(Frame tail, Draft draft) {
        if (tail == null || tail.envelope == null || tail.afterSend != null || tail.due == 0 || !isTextDelta(draft)) return false;
        var e = tail.envelope;
        if (e.type() != draft.type()
                || !java.util.Objects.equals(e.requestId(), draft.requestId())) return false;
        if (e.data() instanceof TextDelta old && draft.data() instanceof TextDelta next)
            return old.round() == next.round() && old.messageId().equals(next.messageId());
        if (e.data() instanceof ToolArgumentsDelta old && draft.data() instanceof ToolArgumentsDelta next)
            return old.round() == next.round() && old.toolCallId().equals(next.toolCallId());
        return false;
    }

    public void ping(byte[] payload, Runnable afterSend) {
        boolean overflow;
        synchronized (this) {
            if (closed) return;
            overflow = bufferedBytes + payload.length > AgentWebSocketProperties.outboundBytes || queue.size() >= 1024;
            if (!overflow) {
                Frame tail = queue.peekLast();
                if (tail != null) tail.due = 0;
                Frame frame = new Frame();
                frame.message = new PingMessage(ByteBuffer.wrap(payload));
                frame.bytes = payload.length;
                frame.afterSend = afterSend;
                bufferedBytes += frame.bytes;
                queue.add(frame);
                notifyAll();
            }
        }
        if (overflow) close(SLOW_CLIENT);
    }

    private void writeLoop() {
        try {
            while (true) {
                Frame frame;
                synchronized (this) {
                    while (!closed && (queue.isEmpty() || queue.peek().due > System.nanoTime())) {
                        if (queue.isEmpty()) wait();
                        else TimeUnit.NANOSECONDS.timedWait(this, Math.max(1, queue.peek().due - System.nanoTime()));
                    }
                    if (closed) return;
                    frame = queue.remove(); // Keep in-flight bytes charged until send completes.
                }
                ScheduledFuture<?> watchdog = scheduler.schedule(() -> close(SLOW_CLIENT),
                        AgentWebSocketProperties.sendTimeoutMillis, TimeUnit.MILLISECONDS);
                try { session.sendMessage(frame.message); }
                finally { watchdog.cancel(false); }
                synchronized (this) {
                    if (closed) return;
                    bufferedBytes -= frame.bytes;
                }
                if (frame.afterSend != null) frame.afterSend.run();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            close(CloseStatus.SERVER_ERROR);
        }
    }

    public void close(CloseStatus status) {
        synchronized (this) {
            if (closed) return;
            closed = true;
            queue.clear();
            bufferedBytes = 0;
            notifyAll();
        }
        writer.interrupt();
        // Closing a network session may block too. Do not do this in a model callback or timer thread.
        Thread.ofVirtual().name("agent-ws-close").start(() -> {
            try { if (rawSession.isOpen()) rawSession.close(status); }
            catch (IOException ignored) { /* Already disconnected. */ }
        });
    }

    public synchronized long bufferedBytes() { return bufferedBytes; }

    public boolean exceedsFrameLimit(Draft draft) {
        // Use the longest sequence and timestamp encodings so the eventual envelope fits too.
        var envelope = new ServerMessage<>(1, draft.type(), agentSessionScope.agentId(), agentSessionScope.sessionId(), draft.requestId(),
                Long.MAX_VALUE, Instant.parse("2000-01-01T00:00:00.999999999Z"), draft.data());
        return mapper.writeValueAsBytes(envelope).length > AgentWebSocketProperties.outboundBytes;
    }

    private static int utf8(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
}
