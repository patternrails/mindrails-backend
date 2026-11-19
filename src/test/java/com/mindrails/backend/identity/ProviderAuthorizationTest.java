package com.mindrails.backend.identity;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindrails.backend.agent.api.web.ProviderController;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.embedding.EmbeddingProviderService;
import com.mindrails.backend.embedding.api.EmbeddingProviderController;
import com.mindrails.backend.provider.ProviderService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProviderAuthorizationTest {
    static final String PASSWORD = "provider-test-password-2026";
    final ProviderService language = mock(ProviderService.class);
    final EmbeddingProviderService embedding = mock(EmbeddingProviderService.class);
    final UUID id = UUID.randomUUID();
    MockMvc mvc;
    String admin, normal;

    @BeforeEach void setup() {
        var properties = new IdentityProperties(); properties.setAdminPassword(PASSWORD);
        var identity = new IdentityService(new InMemoryIdentityStore(), properties);
        admin = identity.login("admin", PASSWORD, "local").accessToken();
        identity.create(identity.authenticate(admin), new UserController.UserWrite("normal", "Normal", "", "", PASSWORD, null, Identity.Role.Normal));
        normal = identity.login("normal", PASSWORD, "local").accessToken();
        when(language.list(anyInt(), anyInt())).thenReturn(ServiceResult.success(List.of()));
        when(embedding.list(anyInt(), anyInt())).thenReturn(ServiceResult.success(List.of()));
        when(language.save(any(), any(), anyBoolean(), anyBoolean())).thenReturn(ServiceResult.success(null));
        when(embedding.save(any(), any(), anyBoolean())).thenReturn(ServiceResult.success(null));
        when(language.delete(any())).thenReturn(ServiceResult.success(null));
        when(embedding.delete(any())).thenReturn(ServiceResult.success(null));
        mvc = MockMvcBuilders.standaloneSetup(new ProviderController(language), new EmbeddingProviderController(embedding))
                .setControllerAdvice(new IdentityErrorAdvice()).addFilters(new IdentityFilter(identity)).build();
    }

    @Test void catalogReadsRemainAvailableToNormalUsers() throws Exception {
        for (String path : List.of("/api/provider", "/api/embedding-provider"))
            mvc.perform(get(path).header("Authorization", "Bearer " + normal)).andExpect(status().isOk());
    }

    @Test void normalUsersCannotChangeOrTestEitherSharedCatalog() throws Exception {
        for (String root : List.of("/api/provider", "/api/embedding-provider")) {
            String entry = root + "/" + (root.endsWith("/provider") ? id : "model");
            mvc.perform(post(root).header("Authorization", "Bearer " + normal).contentType("application/json").content(root.endsWith("/provider") ? "{}" : "{\"dimensions\":1024}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put(entry).header("Authorization", "Bearer " + normal).contentType("application/json").content(root.endsWith("/provider") ? "{}" : "{\"dimensions\":1024}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(entry).header("Authorization", "Bearer " + normal)).andExpect(status().isForbidden());
            mvc.perform(post(entry + "/test").header("Authorization", "Bearer " + normal)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(language, embedding);
    }

    @Test void systemAdminsCanCreateUpdateAndDeleteBothKindsOfProvider() throws Exception {
        for (String root : List.of("/api/provider", "/api/embedding-provider")) {
            String entry = root + "/" + (root.endsWith("/provider") ? id : "model");
            mvc.perform(post(root).header("Authorization", "Bearer " + admin).contentType("application/json").content(root.endsWith("/provider") ? "{\"name\":\"model\"}" : "{\"name\":\"model\",\"dimensions\":1024}"))
                    .andExpect(status().isCreated());
            mvc.perform(put(entry).header("Authorization", "Bearer " + admin).contentType("application/json").content(root.endsWith("/provider") ? "{\"name\":\"model\"}" : "{\"name\":\"model\",\"dimensions\":1024}"))
                    .andExpect(status().isOk());
            mvc.perform(delete(entry).header("Authorization", "Bearer " + admin)).andExpect(status().isNoContent());
        }
        verify(language).save(any(), any(), eq(false), eq(true));
        verify(language).save(eq(id), any(), eq(false), eq(false));
        verify(language).delete(id);
        verify(embedding).save(eq("model"), any(), eq(true));
        verify(embedding).save(eq("model"), any(), eq(false));
        verify(embedding).delete("model");
    }
}
