package com.mindrails.backend.knowledge;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.api.web.KnowledgeBaseController;
import com.mindrails.backend.knowledge.api.web.KnowledgeErrorAdvice;
import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeBaseState;
import com.mindrails.backend.knowledge.model.KnowledgePage;
import com.mindrails.backend.knowledge.service.KnowledgeBaseService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

class KnowledgeControllerTest {
    @Test
    void disabledConfigurationIsReadableAndManagementIsExplicitlyUnavailable() throws Exception {
        var controller =
                new KnowledgeBaseController(
                        org.mockito.Mockito.mock(ObjectProvider.class),
                        org.mockito.Mockito.mock(ObjectProvider.class));
        var mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new KnowledgeErrorAdvice())
                        .build();
        mvc.perform(get("/api/knowledge-bases/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceFailureKeepsTheKnowledgeHttpErrorContract() throws Exception {
        var service = mock(KnowledgeBaseService.class);
        ObjectProvider<KnowledgeBaseService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        UUID id = UUID.randomUUID();
        when(service.detail(id))
                .thenReturn(
                        ServiceResult.failure(
                                ServiceResult.ErrorCode.NOT_FOUND, "Knowledge resource not found"));
        var controller =
                new KnowledgeBaseController(
                        provider, mock(ObjectProvider.class));
        var mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new KnowledgeErrorAdvice())
                        .build();
        mvc.perform(get("/api/knowledge-bases/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Knowledge resource not found"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageAndEnumStateKeepTheExistingResponseShape() throws Exception {
        var service = mock(KnowledgeBaseService.class);
        ObjectProvider<KnowledgeBaseService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        UUID id = UUID.randomUUID();
        var base =
                KnowledgeBase.builder()
                        .id(id)
                        .name("Guide")
                        .state(KnowledgeBaseState.ACTIVE)
                        .version(3)
                        .build();
        when(service.page(0, 20))
                .thenReturn(
                        ServiceResult.success(new KnowledgePage<>(List.of(base), 1, 0, 20, false)));
        var controller =
                new KnowledgeBaseController(
                        provider, mock(ObjectProvider.class));
        var mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new KnowledgeErrorAdvice())
                        .build();
        mvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }
}
