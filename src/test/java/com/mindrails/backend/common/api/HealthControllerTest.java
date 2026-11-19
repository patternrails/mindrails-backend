package com.mindrails.backend.common.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {

    @Test
    void returnsOkWithoutAResponseBody() throws Exception {
        MockMvcBuilders.standaloneSetup(new HealthController()).build()
                .perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }
}
