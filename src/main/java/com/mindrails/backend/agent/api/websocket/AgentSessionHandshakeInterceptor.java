package com.mindrails.backend.agent.api.websocket;

import com.mindrails.backend.agent.api.websocket.protocol.StreamPayloads;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.AgentSessionScope;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.json.JsonMapper;

final class AgentSessionHandshakeInterceptor implements HandshakeInterceptor {
    static final String SCOPE_ATTRIBUTE = AgentSessionHandshakeInterceptor.class.getName() + ".scope";
    private final JsonMapper mapper;

    AgentSessionHandshakeInterceptor(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) throws Exception {

        String path = request.getURI().getPath();
        if (request instanceof ServletServerHttpRequest servlet) {
            path = path.substring(servlet.getServletRequest().getContextPath().length());
        }

        var match = PathPatternParser.defaultInstance.parse(AgentSessionWebSocketConfiguration.PATH)
                .matchAndExtract(PathContainer.parsePath(path));
        if (match == null) {
            return reject(response);
        }

        AgentSessionScope scope;
        try {
            scope = new AgentSessionScope(
                    StreamProtocol.uuid(match.getUriVariables().get("agentId")),
                    StreamProtocol.uuid(match.getUriVariables().get("sessionId")));
        } catch (IllegalArgumentException e) {
            return reject(response);
        }

        attributes.put(SCOPE_ATTRIBUTE, scope);
        return true;
    }

    private boolean reject(ServerHttpResponse response) throws Exception {
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getBody().write(mapper.writeValueAsBytes(
                new StreamPayloads.CommandError(ErrorCode.INVALID_ARGUMENT, "Invalid Agent/Session path")));
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {}
}
