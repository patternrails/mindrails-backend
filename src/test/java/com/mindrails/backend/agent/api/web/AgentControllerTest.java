package com.mindrails.backend.agent.api.web;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.AgentService;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.common.ServiceResponseAdvice;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class AgentControllerTest {

    private final AgentService service = mock(AgentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(service))
            .setControllerAdvice(new ServiceResponseAdvice()).build();

    @Test
    void completesRestCrudWithTheLocalService() throws Exception {
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(
                        new AgentController(new DefaultAgentService(
                                new AgentRegistryConfiguration().agentRegistry(),
                                new DefaultSessionService(
                                        new SessionRegistryConfiguration().sessionRegistry()), List.of())))
                .setControllerAdvice(new ServiceResponseAdvice()).build();
        byte[] createRequest;
        byte[] updateRequest;
        try (var input = new ClassPathResource("agent/create-agent.json").getInputStream()) {
            createRequest = input.readAllBytes();
        }
        try (var input = new ClassPathResource("agent/update-agent.json").getInputStream()) {
            updateRequest = input.readAllBytes();
        }
        String body = localMvc.perform(post("/api/agent").contentType(MediaType.APPLICATION_JSON).content(createRequest))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = JsonMapper.builder().build().readValue(body, UUID.class);
        localMvc.perform(get("/api/agent/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Travel assistant"));
        localMvc.perform(get("/api/agent").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        localMvc.perform(put("/api/agent/{id}", id).contentType(MediaType.APPLICATION_JSON).content(updateRequest))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        localMvc.perform(get("/api/agent/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated travel assistant"))
                .andExpect(jsonPath("$.status").doesNotExist());
        localMvc.perform(delete("/api/agent/{id}", id)).andExpect(status().isNoContent());
        localMvc.perform(get("/api/agent/{id}", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void createsAgentWithServerAssignedOwner() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any(Agent.class))).thenReturn(ServiceResult.success(id));
        byte[] request;
        try (var input = new ClassPathResource("agent/create-agent.json").getInputStream()) {
            request = input.readAllBytes();
        }
        mvc.perform(post("/api/agent").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(content().json("\"" + id + "\""));
        var captured = ArgumentCaptor.forClass(Agent.class);
        verify(service).create(captured.capture());
        Agent agent = captured.getValue();
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), agent.getOwnerId());
        assertEquals("Help me organize travel", agent.getGoal());
        assertEquals("Compare options before booking", agent.getSystemMessage());
        assertNotNull(agent.getSessions());
    }

    @Test
    void updatesEditableFieldsWithNoContent() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.update(eq(id), any(Agent.class))).thenReturn(ServiceResult.success(null));
        byte[] request;
        try (var input = new ClassPathResource("agent/update-agent.json").getInputStream()) {
            request = input.readAllBytes();
        }
        mvc.perform(put("/api/agent/{id}", id).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        var captured = ArgumentCaptor.forClass(Agent.class);
        verify(service).update(eq(id), captured.capture());
        Agent agent = captured.getValue();
        assertEquals(id, agent.getId());
        assertEquals("Updated travel assistant", agent.getName());
        assertEquals("Plans family trips", agent.getDescription());
        assertEquals("Help organize family travel", agent.getGoal());
        assertEquals("Confirm before booking", agent.getSystemMessage());
    }

    @Test
    void passesPaginationToServiceAndRejectsInvalidBounds() throws Exception {
        when(service.list(0, 20)).thenReturn(ServiceResult.success(List.of()));
        when(service.list(2, 10)).thenReturn(ServiceResult.success(List.of()));
        mvc.perform(get("/api/agent")).andExpect(status().isOk());
        mvc.perform(get("/api/agent").param("page", "2").param("size", "10"))
                .andExpect(status().isOk());
        verify(service).list(0, 20);
        verify(service).list(2, 10);
        mvc.perform(get("/api/agent").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/agent").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/agent").param("size", "101")).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }

    @Test
    void getsAgentAndReturnsNotFoundWhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(ServiceResult.success(Agent.builder().id(id).name("Test agent").build()));
        UUID missingId = UUID.randomUUID();
        when(service.get(missingId)).thenReturn(ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent not found"));
        mvc.perform(get("/api/agent/{id}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get("/api/agent/{id}", missingId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void deletesAgentWithNoContent() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.delete(id)).thenReturn(ServiceResult.success(null));
        mvc.perform(delete("/api/agent/{id}", id))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).delete(id);
    }

    @Test
    void mapsServiceFailuresToHttpStatusWithoutThrowingExceptions() throws Exception {
        Map<ErrorCode, Integer> statuses = Map.of(ErrorCode.INVALID_ARGUMENT, 400, ErrorCode.NOT_FOUND, 404,
                ErrorCode.CONFLICT, 409, ErrorCode.NOT_IMPLEMENTED, 501, ErrorCode.INTERNAL_ERROR, 500);
        for (var entry : statuses.entrySet()) {
            when(service.list(0, 20)).thenReturn(ServiceResult.failure(entry.getKey(), "Operation failed"));
            mvc.perform(get("/api/agent")).andExpect(status().is(entry.getValue()))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(entry.getKey().name()))
                    .andExpect(jsonPath("$.message").value("Operation failed"));
        }
        UUID id = UUID.randomUUID();
        when(service.delete(id)).thenReturn(ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent not found"));
        mvc.perform(delete("/api/agent/{id}", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Agent not found"));
    }

}
