package com.mindrails.backend.agent.api.websocket;

import com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway;
import com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway.*;
import com.mindrails.backend.common.ServiceResult;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

/** Connection lifetime does not own the execution; disconnecting only detaches its event sink. */
final class AgentTaskWebSocketHandler extends TextWebSocketHandler {
    private final AgentTaskExecutionStreamGateway gateway;
    private final ThreadPoolTaskScheduler scheduler;
    private final Map<String,Connection> connections=new ConcurrentHashMap<>();
    AgentTaskWebSocketHandler(AgentTaskExecutionStreamGateway gateway,ThreadPoolTaskScheduler scheduler) { this.gateway=gateway;this.scheduler=scheduler; }
    AgentTaskExecutionStreamGateway gateway() { return gateway; }
    @jakarta.annotation.PreDestroy void shutdown() { connections.values().forEach(c -> c.close(CloseStatus.GOING_AWAY)); }
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if(!(session.getAttributes().get(AgentTaskHandshakeInterceptor.SCOPE) instanceof AgentTaskScope scope)) { session.close(CloseStatus.POLICY_VIOLATION);return; }
        var result=gateway.validate(scope);
        if(result instanceof ServiceResult.Failure<?>) { session.close(CloseStatus.POLICY_VIOLATION);return; }
        session.setTextMessageSizeLimit(AgentWebSocketProperties.inboundBytes);
        var connection=new Connection(session,scope);connections.put(session.getId(),connection);
        connection.send("connection.ready",null,null,((ServiceResult.Success<TaskSnapshot>)result).value());
    }
    protected void handleTextMessage(WebSocketSession session,TextMessage message) {
        var c=connections.get(session.getId());if(c==null)return;
        if(message.getPayloadLength()>AgentWebSocketProperties.inboundBytes) { c.close(CloseStatus.TOO_BIG_TO_PROCESS);return; }
        UUID requestId=StreamProtocol.requestId(message.getPayload());
        final Command command;
        try { command=TaskStreamProtocol.decode(message.getPayload()); }
        catch(RuntimeException e) {
            c.error(requestId,new ServiceResult.Failure<>(ServiceResult.ErrorCode.INVALID_ARGUMENT,"Invalid Task command"));return;
        }
        try {
            if(command instanceof Command.Execute execute) {
                var result=gateway.reserveAndExecute(c.scope,execute,e -> c.send(e.type(),e.executionId(),e.requestId(),e.data()));
                if(result instanceof ServiceResult.Failure<?> f)c.error(requestId,f);
            } else if(command instanceof Command.Cancel cancel) {
                var result=gateway.command(c.scope,cancel);
                if(result instanceof ServiceResult.Failure<?> f)c.error(requestId,f);
                else c.send("cancel.accepted",cancel.executionId(),requestId,Map.of());
            }
        } catch(RuntimeException e) {
            c.error(requestId,new ServiceResult.Failure<>(ServiceResult.ErrorCode.INTERNAL_ERROR,"Task command failed"));
        }
    }
    protected void handlePongMessage(WebSocketSession session,PongMessage message) { var c=connections.get(session.getId());if(c!=null)c.lastPong=System.nanoTime(); }
    protected void handleBinaryMessage(WebSocketSession session,BinaryMessage message) { var c=connections.get(session.getId());if(c!=null)c.close(CloseStatus.NOT_ACCEPTABLE); }
    public void afterConnectionClosed(WebSocketSession session,CloseStatus status) { var c=connections.remove(session.getId());if(c!=null)c.close(status); }
    public void handleTransportError(WebSocketSession session,Throwable exception) { var c=connections.remove(session.getId());if(c!=null)c.close(CloseStatus.SERVER_ERROR); }
    record Envelope(int version,String type,UUID agentId,UUID taskId,UUID executionId,UUID requestId,long seq,Instant timestamp,Object data) {}
    private final class Connection {
        final WebSocketSession raw,session;
        final AgentTaskScope scope;
        final ArrayDeque<WebSocketMessage<?>> queue=new ArrayDeque<>();
        final Thread writer;
        final ScheduledFuture<?> heartbeat;
        long seq;int bytes;boolean closed;volatile long lastPong=System.nanoTime();
        Connection(WebSocketSession raw,AgentTaskScope scope) {
            this.raw=raw;this.scope=scope;
            session=new ConcurrentWebSocketSessionDecorator(raw,AgentWebSocketProperties.sendTimeoutMillis,AgentWebSocketProperties.outboundBytes);
            writer=Thread.ofVirtual().name("task-ws-writer-"+raw.getId()).start(this::write);
            heartbeat=scheduler.scheduleAtFixedRate(() -> {
                if(System.nanoTime()-lastPong>TimeUnit.MILLISECONDS.toNanos(AgentWebSocketProperties.pongTimeoutMillis))close(CloseStatus.SESSION_NOT_RELIABLE);
                else enqueue(new PingMessage());
            },java.time.Duration.ofMillis(AgentWebSocketProperties.pingMillis));
        }
        void error(UUID request,ServiceResult.Failure<?> f) { send("command.error",null,request,Map.of("code",f.code(),"message",f.message())); }
        synchronized void send(String type,UUID execution,UUID request,Object data) {
            if(closed)return;
            enqueue(new TextMessage(TaskStreamProtocol.JSON.writeValueAsString(new Envelope(1,type,scope.agentId(),scope.taskId(),execution,request,++seq,Instant.now(),data))));
        }
        synchronized void enqueue(WebSocketMessage<?> message) {
            if(closed)return;
            int size=message.getPayloadLength();
            if(bytes+size>AgentWebSocketProperties.outboundBytes || queue.size()>=1024) { close(new CloseStatus(4008,"Client is too slow"));return; }
            queue.add(message);bytes+=size;notifyAll();
        }
        void write() {
            try {
                while(true) {
                    WebSocketMessage<?> message;
                    synchronized(this) { while(!closed && queue.isEmpty())wait();if(closed)return;message=queue.remove(); }
                    var watchdog=scheduler.schedule(() -> close(CloseStatus.SESSION_NOT_RELIABLE),Instant.now().plusMillis(AgentWebSocketProperties.sendTimeoutMillis));
                    try { session.sendMessage(message); } finally { if(watchdog!=null)watchdog.cancel(false); }
                    synchronized(this) { bytes-=message.getPayloadLength(); }
                }
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            catch(Exception e) { close(CloseStatus.SERVER_ERROR); }
        }
        synchronized void close(CloseStatus status) {
            if(closed)return;closed=true;queue.clear();notifyAll();writer.interrupt();
            if(heartbeat!=null)heartbeat.cancel(false);
            connections.remove(raw.getId(),this);
            Thread.ofVirtual().start(() -> { try { if(raw.isOpen())raw.close(status); } catch(IOException ignored) {} });
        }
    }
}
