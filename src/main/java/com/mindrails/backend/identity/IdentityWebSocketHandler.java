package com.mindrails.backend.identity;

import com.mindrails.backend.identity.Identity.Current;
import java.util.concurrent.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** Keep identity attached to each socket and revoke open sockets after logout, deletion or credential changes. */
public final class IdentityWebSocketHandler extends WebSocketHandlerDecorator {
    private final IdentityService identity;
    private final ScheduledExecutorService timer;
    private final ConcurrentMap<String,ScheduledFuture<?>> checks=new ConcurrentHashMap<>();
    public IdentityWebSocketHandler(WebSocketHandler delegate,IdentityService identity,ScheduledExecutorService timer) {
        super(delegate);this.identity=identity;this.timer=timer;
    }
    private boolean valid(WebSocketSession socket) {
        try { if(!(socket.getPrincipal() instanceof Current c)) return false; identity.sessionIdentity(c.sessionId()); return true; }
        catch(RuntimeException e) { return false; }
    }
    @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        if(!valid(socket)) {socket.close(CloseStatus.POLICY_VIOLATION);return;}
        socket.getAttributes().put(IdentityFilter.CURRENT,socket.getPrincipal());
        checks.put(socket.getId(),timer.scheduleAtFixedRate(() -> {
            if(!valid(socket)) try { socket.close(CloseStatus.POLICY_VIOLATION); } catch(java.io.IOException ignored) {}
        },15,15,TimeUnit.SECONDS));
        try (var scope = ResourceAccess.as(((Current) socket.getPrincipal()).id())) { super.afterConnectionEstablished(socket); } catch(Exception e) {cancel(socket);throw e;}
    }
    @Override public void handleMessage(WebSocketSession socket,WebSocketMessage<?> message) throws Exception {
        if(!valid(socket)) {socket.close(CloseStatus.POLICY_VIOLATION);return;}
        try (var scope = ResourceAccess.as(((Current) socket.getPrincipal()).id())) { super.handleMessage(socket,message); }
    }
    private void cancel(WebSocketSession socket) {var task=checks.remove(socket.getId());if(task!=null)task.cancel(false);}
    @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status) throws Exception {cancel(socket);super.afterConnectionClosed(socket,status);}
    @Override public void handleTransportError(WebSocketSession socket,Throwable exception) throws Exception {cancel(socket);super.handleTransportError(socket,exception);}
}
