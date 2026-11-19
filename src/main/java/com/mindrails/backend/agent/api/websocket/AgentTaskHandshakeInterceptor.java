package com.mindrails.backend.agent.api.websocket;

import com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway;
import com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway.AgentTaskScope;
import com.mindrails.backend.common.ServiceResult;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.pattern.PathPatternParser;

final class AgentTaskHandshakeInterceptor implements HandshakeInterceptor {
    static final String SCOPE=AgentTaskHandshakeInterceptor.class.getName()+".scope";
    private final AgentTaskExecutionStreamGateway gateway;
    AgentTaskHandshakeInterceptor(AgentTaskExecutionStreamGateway gateway) { this.gateway=gateway; }
    public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes) {
        try {
            String path=request.getURI().getPath();
            if(request instanceof ServletServerHttpRequest servlet)path=path.substring(servlet.getServletRequest().getContextPath().length());
            var match=PathPatternParser.defaultInstance.parse(AgentTaskWebSocketConfiguration.PATH).matchAndExtract(PathContainer.parsePath(path));
            if(match==null)throw new IllegalArgumentException();
            var scope=new AgentTaskScope(StreamProtocol.uuid(match.getUriVariables().get("agentId")),StreamProtocol.uuid(match.getUriVariables().get("taskId")));
            if(gateway.validate(scope) instanceof ServiceResult.Failure<?>) { response.setStatusCode(HttpStatus.NOT_FOUND);return false; }
            attributes.put(SCOPE,scope);return true;
        } catch(IllegalArgumentException e) { response.setStatusCode(HttpStatus.BAD_REQUEST);return false; }
    }
    public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception) {}
}
