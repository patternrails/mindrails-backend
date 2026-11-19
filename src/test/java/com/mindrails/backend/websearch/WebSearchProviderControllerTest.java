package com.mindrails.backend.websearch;

import com.mindrails.backend.identity.Identity;
import com.mindrails.backend.identity.IdentityErrorAdvice;
import com.mindrails.backend.identity.IdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebSearchProviderControllerTest {
    @Test
    void managementIsAdminOnlyAndCatalogReadsDoNotContainCredentials() throws Exception {
        var service = mock(WebSearchProviderService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WebSearchProviderController(service))
                .setControllerAdvice(new IdentityErrorAdvice()).build();
        String root = "/api/web-search-provider";
        UUID id = UUID.randomUUID();
        String entry = root + "/" + id;
        var normal = new Identity.Current(UUID.randomUUID(), "Normal", Identity.Role.Normal, UUID.randomUUID());
        var admin = new Identity.Current(UUID.randomUUID(), "Admin", Identity.Role.SystemAdmin, UUID.randomUUID());
        mvc.perform(get(root)).andExpect(status().isUnauthorized());
        for (var operation : List.of(get(root), get(entry), post(root).contentType("application/json").content("{}"),
                put(entry).contentType("application/json").content("{}"), put(entry + "/active"),
                post(entry + "/test"), delete(entry))) {
            mvc.perform(operation.requestAttr(IdentityFilter.CURRENT, normal)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        var provider = new WebSearchProvider(id, "Bocha", WebSearchProvider.BOCHA, "https://api.bocha.cn/v1/web-search",
                null, Map.of(), 15000, true, 0, Instant.EPOCH, Instant.EPOCH);
        when(service.list(0, 20)).thenReturn(List.of(provider));
        when(service.activate(id)).thenReturn(provider);
        when(service.test(id)).thenReturn(new WebSearchProvider.TestResult(id, 0, true, 12, "Search API returned a valid page"));
        mvc.perform(get(root).requestAttr(IdentityFilter.CURRENT, admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].active").value(true)).andExpect(jsonPath("$[0].apiKey").doesNotExist());
        mvc.perform(put(entry + "/active").requestAttr(IdentityFilter.CURRENT, admin)).andExpect(status().isOk());
        mvc.perform(post(entry + "/test").requestAttr(IdentityFilter.CURRENT, admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true)).andExpect(jsonPath("$.latencyMs").value(12));
    }
}
