package com.mindrails.backend.agent.api.websocket;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.AgentSessionScope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class AgentSessionHandshakeInterceptorTest {
    final AgentSessionHandshakeInterceptor interceptor = new AgentSessionHandshakeInterceptor(JsonMapper.builder().build());

    @Test
    void rejectsInvalidPathsAndUuidSyntax() throws Exception {
        for (String path : List.of("/invalid", "/api/agent/bad/sessions/bad/stream",
                "/api/agent/1-1-1-1-1/sessions/" + UUID.randomUUID() + "/stream")) {
            var response = new MockHttpServletResponse();
            var attributes = new HashMap<String, Object>();
            assertFalse(handshake(path, response, attributes));
            assertEquals(400, response.getStatus());
            assertEquals("INVALID_ARGUMENT", JsonMapper.builder().build().readTree(response.getContentAsString())
                    .path("code").asString());
            assertTrue(attributes.isEmpty());
        }
    }

    @Test
    void acceptsValidPathsAndStoresOnlyTheParsedScope() throws Exception {
        UUID agentId = UUID.randomUUID(), sessionId = UUID.randomUUID();
        var attributes = new HashMap<String, Object>();

        assertTrue(handshake(path(agentId, sessionId), new MockHttpServletResponse(), attributes));

        assertEquals(Map.of(AgentSessionHandshakeInterceptor.SCOPE_ATTRIBUTE,
                new AgentSessionScope(agentId, sessionId)), attributes);
    }

    @Test
    void parsesPathsUnderAServletContext() throws Exception {
        UUID agentId = UUID.randomUUID(), sessionId = UUID.randomUUID();
        var request = new MockHttpServletRequest("GET", "/mindrails" + path(agentId, sessionId));
        request.setContextPath("/mindrails");
        var attributes = new HashMap<String, Object>();

        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, attributes));
        assertEquals(new AgentSessionScope(agentId, sessionId), attributes.get(AgentSessionHandshakeInterceptor.SCOPE_ATTRIBUTE));
    }

    private boolean handshake(String path, MockHttpServletResponse response, HashMap<String, Object> attributes) throws Exception {
        return interceptor.beforeHandshake(new ServletServerHttpRequest(new MockHttpServletRequest("GET", path)),
                new ServletServerHttpResponse(response), null, attributes);
    }

    static String path(UUID agentId, UUID sessionId) { return "/api/agent/" + agentId + "/sessions/" + sessionId + "/stream"; }
}
