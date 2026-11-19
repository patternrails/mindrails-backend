package com.mindrails.backend.agent.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindrails.backend.common.ServiceResponseAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringJUnitConfig({com.mindrails.backend.provider.ProviderTestConfiguration.class, ProviderController.class,
        com.mindrails.backend.provider.ProviderService.class})
class ProviderControllerTest {

    @Autowired
    private ProviderController controller;

    private MockMvc mvc;

    @BeforeEach
    void initialize() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ServiceResponseAdvice()).build();
    }

    @Test
    void listsConfiguredProvidersWithoutKeysOrRuntimeClients() throws Exception {
        mvc.perform(get("/api/provider"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].name").value("deepseek-non-streaming"))
                .andExpect(jsonPath("$[0].modelName").value("deepseek-flash"))
                .andExpect(jsonPath("$[0].streaming").value(false))
                .andExpect(jsonPath("$[1].streaming").value(true))
                .andExpect(jsonPath("$[*].apiKey").doesNotExist())
                .andExpect(jsonPath("$[*].chatModel").doesNotExist())
                .andExpect(jsonPath("$[*].streamingChatModel").doesNotExist());
    }

    @Test
    void paginatesProvidersInConfiguredOrder() throws Exception {
        mvc.perform(get("/api/provider").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("deepseek-non-streaming"))
                .andExpect(jsonPath("$[1].name").value("openai"));
        mvc.perform(get("/api/provider").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("deepseek"))
                .andExpect(jsonPath("$[1].name").value("openai-non-streaming"));
        mvc.perform(get("/api/provider").param("page", "1").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("openai-non-streaming"));
    }

    @Test
    void returnsAnEmptyListBeyondTheLastPageWithoutOffsetOverflow() throws Exception {
        for (String page : new String[] {"2", "2147483647"}) {
            mvc.perform(get("/api/provider").param("page", page).param("size", "100"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));
        }
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,101"})
    void rejectsInvalidPagination(int page, int size) throws Exception {
        mvc.perform(get("/api/provider").param("page", Integer.toString(page))
                        .param("size", Integer.toString(size)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}
