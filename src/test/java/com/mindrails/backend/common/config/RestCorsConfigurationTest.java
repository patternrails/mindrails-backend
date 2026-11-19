package com.mindrails.backend.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindrails.backend.common.api.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class RestCorsConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {}

    @RestController
    static class WriteController {
        @PutMapping("/api/agent")
        ResponseEntity<Void> update() { return ResponseEntity.noContent().build(); }
    }

    private AnnotationConfigWebApplicationContext context(String... properties) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.register(MvcConfiguration.class, RestCorsConfiguration.class, HealthController.class, WriteController.class);
        context.refresh();
        return context;
    }

    @Test
    void allowsBothDefaultFrontendOriginsAndKeepsHealthBodyEmpty() throws Exception {
        try (var context = context()) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            for (String origin : new String[] {"http://localhost:3000", "http://127.0.0.1:3000"}) {
                mvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, origin))
                        .andExpect(status().isOk())
                        .andExpect(content().string(""))
                        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
            }
        }
    }

    @Test
    void rejectsUnlistedOriginsAndKeepsNonBrowserHealthWorking() throws Exception {
        try (var context = context()) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, "https://unlisted.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(content().string(""));
        }
    }

    @Test
    void supportsJsonWritePreflightAndConfiguredFrontendOrigin() throws Exception {
        try (var context = context("mindrails.cors.allowed-origins= https://frontend.example , http://localhost:3100 ")) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(options("/api/agent")
                            .header(HttpHeaders.ORIGIN, "https://frontend.example")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,X-Request-ID"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://frontend.example"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, X-Request-ID"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "600"));
            mvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, "http://localhost:3100"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
                    .andExpect(status().isForbidden());
        }
    }
}
