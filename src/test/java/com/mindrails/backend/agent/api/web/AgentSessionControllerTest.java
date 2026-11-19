package com.mindrails.backend.agent.api.web;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.AgentService;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.common.ServiceResponseAdvice;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.provider.Provider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class AgentSessionControllerTest {

    @Test
    void completesSessionRestOperationsWithTheLocalServices() throws Exception {
        UUID providerId = UUID.randomUUID();
        Provider provider = Provider.builder().id(providerId).name("test-provider").build();
        DefaultAgentService localService = new DefaultAgentService(
                new AgentRegistryConfiguration().agentRegistry(),
                com.mindrails.backend.agent.service.internal.TestSessionFork.install(new DefaultSessionService(
                        new SessionRegistryConfiguration().sessionRegistry())), List.of(provider));
        UUID agentId = ((ServiceResult.Success<UUID>) localService.create(
                Agent.builder().name("Travel").systemMessage("Original").build())).value();
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(new AgentSessionController(localService))
                .setControllerAdvice(new ServiceResponseAdvice()).build();
        JsonMapper mapper = JsonMapper.builder().build();

        String started = localMvc.perform(post("/api/agent/{agentId}/sessions", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("providerId", providerId))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID sessionId = mapper.readValue(started, UUID.class);
        localMvc.perform(get("/api/agent/{agentId}/sessions", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sessionId.toString()))
                .andExpect(jsonPath("$[0].systemMessage").value("Original"));

        localMvc.perform(put("/api/agent/{agentId}/sessions/{sessionId}", agentId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("providerId", providerId,
                                "systemMessage", "Updated"))))
                .andExpect(status().isNoContent());
        localMvc.perform(get("/api/agent/{agentId}/sessions", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].systemMessage").value("Updated"));
        List<Session> sessions = ((ServiceResult.Success<List<Session>>) localService.listSessions(agentId, 0, 20)).value();

        String forked = localMvc.perform(post("/api/agent/{agentId}/sessions/{sessionId}/fork", agentId, sessionId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertNotEquals(sessionId, mapper.readValue(forked, UUID.class));
        localMvc.perform(delete("/api/agent/{agentId}/sessions/{sessionId}", agentId, sessionId))
                .andExpect(status().isNoContent());
        localMvc.perform(get("/api/agent/{agentId}/sessions", agentId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        localMvc.perform(get("/api/agent/{agentId}/sessions", agentId).param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void synchronousSessionChatRoutesAreRemoved() throws Exception {
        var service = mock(AgentService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AgentSessionController(service)).build();
        for (var suffix : List.of("chat", "chat/details")) {
            mvc.perform(post("/api/agent/{agentId}/sessions/{sessionId}/" + suffix, UUID.randomUUID(), UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                    .andExpect(status().isNotFound());
        }
        org.mockito.Mockito.verifyNoInteractions(service);
    }
}
