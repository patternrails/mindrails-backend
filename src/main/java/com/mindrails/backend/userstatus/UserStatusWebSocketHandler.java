package com.mindrails.backend.userstatus;

import com.mindrails.backend.identity.Identity.Current;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only status transport, deliberately separate from Session content sockets. */
public final class UserStatusWebSocketHandler extends AbstractWebSocketHandler {
    private final UserSessionStatusService statuses;
    private final JsonMapper mapper;
    private final ScheduledExecutorService timer;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    public UserStatusWebSocketHandler(UserSessionStatusService statuses, JsonMapper mapper, ScheduledExecutorService timer) {
        this.statuses = statuses; this.mapper = mapper; this.timer = timer;
    }
    @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        if (!(socket.getPrincipal() instanceof Current actor)) { socket.close(CloseStatus.POLICY_VIOLATION); return; }
        var connection = new Connection(socket);
        connections.put(socket.getId(), connection);
        var unsubscribe = statuses.subscribe(actor.id(), connection::offer);
        connection.bind(unsubscribe);
        connection.heartbeat = timer.scheduleAtFixedRate(connection::ping, 30, 30, TimeUnit.SECONDS);
        if (connection.closed.get()) connection.heartbeat.cancel(false);
    }
    @Override public void handleMessage(WebSocketSession socket, WebSocketMessage<?> message) {
        var connection = connections.get(socket.getId());
        if (connection == null) return;
        if (message instanceof PongMessage) connection.lastPong = System.nanoTime();
        else connection.close(CloseStatus.POLICY_VIOLATION);
    }
    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        var connection = connections.remove(socket.getId());
        if (connection != null) connection.close(status);
    }
    @Override public void handleTransportError(WebSocketSession socket, Throwable error) {
        var connection = connections.get(socket.getId());
        if (connection != null) connection.close(CloseStatus.SERVER_ERROR);
    }
    @jakarta.annotation.PreDestroy public void shutdown() { connections.values().forEach(c -> c.close(CloseStatus.GOING_AWAY)); }

    private final class Connection {
        final WebSocketSession socket;
        final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(128);
        final AtomicBoolean closed = new AtomicBoolean();
        final Thread writer;
        volatile Runnable unsubscribe = () -> {};
        volatile ScheduledFuture<?> heartbeat;
        volatile long lastPong = System.nanoTime();
        volatile CloseStatus closeStatus = CloseStatus.NORMAL;
        Connection(WebSocketSession socket) {
            this.socket = new org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator(socket, 10000, 1024 * 1024);
            writer = Thread.ofVirtual().name("user-status-ws-" + socket.getId()).start(this::write);
        }
        void bind(Runnable action) { unsubscribe = action; if (closed.get()) action.run(); }
        void offer(Object event) {
            if (closed.get()) return;
            if (!queue.offer(event)) close(new CloseStatus(1013, "Status consumer is too slow"));
        }
        void ping() {
            if (System.nanoTime() - lastPong > TimeUnit.SECONDS.toNanos(60)) close(CloseStatus.SESSION_NOT_RELIABLE);
            else offer(new PingMessage());
        }
        synchronized void close(CloseStatus reason) {
            if (closed.get()) return;
            closeStatus = reason;
            closed.set(true);
            // Writer owns socket I/O and cleanup; status publication only flips a flag and wakes it.
            writer.interrupt();
        }
        void write() {
            try {
                while (!closed.get()) {
                    Object next = queue.take();
                    WebSocketMessage<?> message = next instanceof WebSocketMessage<?> frame ? frame
                            : new TextMessage(mapper.writeValueAsBytes(next));
                    if (message.getPayloadLength() > 1024 * 1024) { closeStatus = CloseStatus.TOO_BIG_TO_PROCESS; break; }
                    socket.sendMessage(message);
                }
            } catch (InterruptedException ignored) { /* Closing wakes a parked writer. */ }
            catch (Exception error) { if (!closed.get()) closeStatus = CloseStatus.SERVER_ERROR; }
            finally {
                Thread.interrupted(); // Closing the socket may itself perform I/O.
                closed.set(true);
                connections.remove(socket.getId(), this);
                if (heartbeat != null) heartbeat.cancel(false);
                unsubscribe.run();
                try { socket.close(closeStatus); } catch (Exception ignored) {}
            }
        }
    }
}
