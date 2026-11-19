package com.mindrails.backend.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import static org.mockito.Mockito.mock;
import com.mindrails.backend.agent.api.web.AgentController;
import com.mindrails.backend.agent.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IdentityCorsTest {
    @Test void agentPreflightIsAllowedOnlyForConfiguredOrigins() throws Exception {
        var filter = new IdentityCorsConfiguration().identityCors(new IdentityProperties()).getFilter();
        var mvc = MockMvcBuilders.standaloneSetup(new AgentController(mock(AgentService.class))).addFilters(filter).build();
        var allowed = mvc.perform(options("/api/agent")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,x-mindrails-auth")).andReturn().getResponse();
        assertEquals(200, allowed.getStatus());
        assertEquals("http://localhost:3000", allowed.getHeader("Access-Control-Allow-Origin"));
        assertTrue(allowed.getHeader("Access-Control-Allow-Headers").toLowerCase().contains("x-mindrails-auth"));
        var denied = mvc.perform(options("/api/agent")
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-mindrails-auth")).andReturn().getResponse();
        assertEquals(403, denied.getStatus());
    }
}
