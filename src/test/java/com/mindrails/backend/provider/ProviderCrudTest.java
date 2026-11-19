package com.mindrails.backend.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.common.ServiceResult;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class ProviderCrudTest {

    private ProviderDefinition definition(UUID id, String name) {
        return new ProviderDefinition(
                id,
                name,
                "OpenAiCompiable",
                true,
                "http://localhost:9000/v1",
                "private-test-secret",
                "test-model",
                null,
                null,
                null,
                1000,
                500);
    }

    @Test
    void cloneReusesKeyCreatesIndependentIdentityAndPreservesDefault() {
        var mapper = JsonMapper.builder().build();
        var id = UUID.randomUUID();
        var registry = catalog(definition(id, "original"));
        var service = new ProviderService(registry);
        var cloned = (ServiceResult.Success<Provider>) service.clone(id, definition(null, "original copy").withApiKey(null));
        var copyId = cloned.value().getId();
        assertNotEquals(id, copyId);
        assertEquals(id, registry.defaultProvider().orElseThrow().getId());
        assertEquals("private-test-secret", registry.find(copyId).orElseThrow().getApiKey());
        assertFalse(mapper.writeValueAsString(cloned.value()).contains("private-test-secret"));
        assertInstanceOf(ServiceResult.Failure.class, service.clone(id, definition(null, "original copy").withApiKey(null)));
        assertInstanceOf(ServiceResult.Failure.class, service.clone(UUID.randomUUID(), definition(null, "missing").withApiKey(null)));
        assertEquals(2, registry.list(0, 100).size());
        service.delete(copyId);
        assertTrue(registry.find(id).isPresent());
    }

    @Test
    void cloneControllerRequiresAdministrator() {
        var service = org.mockito.Mockito.mock(ProviderService.class);
        var controller = new com.mindrails.backend.agent.api.web.ProviderController(service);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(com.mindrails.backend.identity.IdentityFilter.CURRENT,
                new com.mindrails.backend.identity.Identity.Current(UUID.randomUUID(), "user", com.mindrails.backend.identity.Identity.Role.Normal, UUID.randomUUID()));
        assertThrows(com.mindrails.backend.identity.IdentityException.class, () -> controller.clone(UUID.randomUUID(), null, request));
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void managesDefaultAndPreservesOrRotatesCredentials() {
        var mapper = JsonMapper.builder().build();
        var id = UUID.randomUUID();
        var registry = catalog(definition(id, "first"));
        var service = new ProviderService(registry);
        var second = UUID.randomUUID();
        assertInstanceOf(
                ServiceResult.Success.class,
                service.save(second, definition(null, "second"), true, true));
        assertEquals(second, registry.defaultProvider().orElseThrow().getId());
        assertInstanceOf(
                ServiceResult.Failure.class,
                service.save(UUID.randomUUID(), definition(null, "SECOND"), false, true));
        assertTrue(mapper.writeValueAsString(registry.list(0, 100)).contains("\"hasApiKey\":true"));
        assertFalse(mapper.writeValueAsString(registry.list(0, 100)).contains("apiKeyRef"));
        assertInstanceOf(
                ServiceResult.Success.class,
                service.save(second, definition(null, "second").withApiKey(null), false, false));
        assertEquals("private-test-secret", registry.find(second).orElseThrow().getApiKey());
        assertInstanceOf(
                ServiceResult.Success.class,
                service.save(
                        second,
                        definition(null, "second").withApiKey("replacement-secret"),
                        false,
                        false));
        assertEquals(
                "replacement-secret",
                registry.find(second)
                        .orElseThrow()
                        .getApiKey());
        assertFalse(
                mapper.writeValueAsString(registry.list(0, 100)).contains("private-test-secret"));
        assertInstanceOf(
                ServiceResult.Success.class,
                service.save(second, definition(null, "renamed"), false, false));
        assertEquals("renamed", registry.defaultProvider().orElseThrow().getName());
        service.delete(second);
        assertEquals(id, registry.defaultProvider().orElseThrow().getId());
        service.delete(id);
        assertTrue(registry.list(0, 100).isEmpty());
    }

    @Test
    void rejectsInvalidInputWithoutChangingCatalog() {
        var id = UUID.randomUUID();
        var registry = catalog(definition(id, "first"));
        var service = new ProviderService(registry);
        var invalid =
                new ProviderDefinition(
                        null,
                        " ",
                        "invalid",
                        true,
                        "file:///etc",
                        "plaintext-key",
                        "model",
                        null,
                        null,
                        null,
                        -1,
                        0);
        assertEquals(
                ServiceResult.ErrorCode.INVALID_ARGUMENT,
                ((ServiceResult.Failure<?>) service.save(UUID.randomUUID(), invalid, false, true))
                        .code());
        assertEquals(
                ServiceResult.ErrorCode.NOT_FOUND,
                ((ServiceResult.Failure<?>) service.delete(UUID.randomUUID())).code());
        assertEquals(id, registry.defaultProvider().orElseThrow().getId());
    }

    private static ProviderRegistry catalog(ProviderDefinition definition) {
        return new ManagedProviderRegistry(
                new ManagedProviderRegistry.Catalog(definition.name(), List.of(definition)),
                new ProvidersConfiguration()) {
            @Override
            protected void persist(Catalog next) {}
        };
    }
}
