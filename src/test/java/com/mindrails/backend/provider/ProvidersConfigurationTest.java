package com.mindrails.backend.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.provider.ProvidersConfiguration;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialStreamingChatModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.context.ApplicationContext;

@SpringJUnitConfig(com.mindrails.backend.provider.ProviderTestConfiguration.class)
class ProvidersConfigurationTest {

    @Autowired
    private ApplicationContext context;

    private final ProvidersConfiguration configuration = new ProvidersConfiguration();

    @Test
    void registersExplicitTestCatalogAtStartup() {
        List<?> providers = context.getBean("providers", List.class);
        assertEquals(4, providers.size());
        Provider deepSeekSync = (Provider) providers.get(0);
        Provider openAi = (Provider) providers.get(1);
        Provider deepSeek = (Provider) providers.get(2);
        Provider openAiSync = (Provider) providers.get(3);
        assertEquals("deepseek", deepSeek.getName());
        assertEquals("deepseek-non-streaming", deepSeekSync.getName());
        assertNull(deepSeekSync.getChatModel());
        assertNull(deepSeek.getStreamingChatModel());
        assertFalse(openAi.isHasApiKey());
        assertNull(openAiSync.getApiKey());
        assertEquals(1050000, openAi.getContextWindow());
        assertEquals(16384, openAi.getMaxTokens());
    }

    @Test
    void constructsEveryIntegrationWithoutCallingModels() throws Exception {
        List<Provider> providers = load("providers/all-integrations.json");
        assertEquals(10, providers.size());
        for (Provider provider : providers) {
            boolean streaming = provider.getName().endsWith("-stream");
            assertEquals(streaming, provider.getStreaming(), provider.getName());
            assertEquals(streaming, provider.getStreamingChatModel() != null, provider.getName());
            assertEquals(!streaming, provider.getChatModel() != null, provider.getName());
        }
    }

    @Test
    void defaultsToStreamingAndPutsDefaultProviderFirst() throws Exception {
        List<Provider> providers = load("providers/default-streaming.json");
        assertEquals("second", providers.getFirst().getName());
        assertTrue(providers.getFirst().getStreaming());
    }

    @Test
    void buildsUnconfiguredProvidersFromRowsWithoutCredentials() throws Exception {
        var providers = load("providers/catalog-test.json");
        assertTrue(providers.stream().noneMatch(Provider::isHasApiKey));
        assertTrue(providers.stream().allMatch(p -> p.getApiKey() == null));
    }

    @Test
    void rejectsUnknownModesAndInvalidDefaultReferences() {
        assertThrows(IllegalArgumentException.class, () -> load("providers/unknown-mode.json"));
        assertThrows(IllegalArgumentException.class, () -> load("providers/invalid-default.json"));
    }

    private List<Provider> load(String resourcePath) throws Exception {
        var catalog = ProviderTestConfiguration.fixture(resourcePath);
        return configuration.loadDefinitions(catalog.providers(), catalog.defaultProvider());
    }
}
