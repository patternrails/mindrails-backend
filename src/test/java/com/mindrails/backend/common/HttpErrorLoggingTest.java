package com.mindrails.backend.common;
import com.mindrails.backend.knowledge.service.KnowledgeBaseService;

import com.mindrails.backend.knowledge.api.web.KnowledgeErrorAdvice;
import com.mindrails.backend.knowledge.api.web.KnowledgeBaseController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class HttpErrorLoggingTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    KnowledgeBaseService service;
    Logger logger;
    ListAppender<ILoggingEvent> appender;
    boolean additive;

    @BeforeEach void setup() {
        logger=(Logger)LoggerFactory.getLogger(HttpErrorLogging.class);
        additive=logger.isAdditive(); logger.setAdditive(false);
        appender=new ListAppender<>(); appender.start(); logger.addAppender(appender);
        context=new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
        service=context.getBean(KnowledgeBaseService.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(HttpErrorLogging.class)).build();
    }
    @AfterEach void cleanup() {
        context.close(); logger.detachAppender(appender); appender.stop(); logger.setAdditive(additive);
    }
    @Test void locallyHandledKnowledgeErrorLogsOriginalReasonAndFinalStatus() throws Exception {
        when(service.create(any(),any(),any())).thenThrow(new IllegalArgumentException("Missing API key for embedding provider qwen-default"));
        mvc.perform(post("/api/knowledge-bases?token=query-secret").header("Authorization","Bearer header-secret")
                .contentType("application/json").content("{\"name\":\"body-secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Missing API key for embedding provider qwen-default")));
        assertThat(appender.list).hasSize(1); var event=appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("HTTP 400 POST /api/knowledge-bases", "KnowledgeBaseController#create").doesNotContain("query-secret");
        assertThat(trace(event)).contains("Missing API key for embedding provider qwen-default").doesNotContain("header-secret","body-secret");
    }
    @Test void unavailableServiceLogsNestedCauseAndRedactsCredentials() throws Exception {
        when(service.create(any(),any(),any())).thenThrow(new IllegalStateException("Provider apiKey=sk-fake-value",
                new IllegalArgumentException("Connection password=db-secret Authorization=Bearer auth-secret")));
        mvc.perform(post("/api/knowledge-bases").contentType("application/json").content("{\"name\":\"Guide\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"));
        var event=appender.list.getFirst(); assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(trace(event)).contains("IllegalStateException", "IllegalArgumentException", "[REDACTED]", "HttpErrorLoggingTest")
                .doesNotContain("sk-fake-value","db-secret","auth-secret");
    }
    @Test void malformedJsonOmitsRawInputFromEveryCause() throws Exception {
        mvc.perform(post("/api/knowledge-bases").contentType("application/json")
                .content("{\"name\":\"private-body\",\"version\":\"private-value\"}"))
                .andExpect(status().isBadRequest());
        assertThat(appender.list).hasSize(1);
        assertThat(trace(appender.list.getFirst())).contains("Request JSON could not be parsed/bound", "field=version", "expected=Long")
                .doesNotContain("private-body","private-value");
    }
    @Test void beanValidationShowsFieldAndConstraintWithoutRejectedValue() throws Exception {
        mvc.perform(post("/debug-test/validate").contentType("application/json").content("{\"name\":\"private-rejected-value\"}"))
                .andExpect(status().isBadRequest());
        assertThat(trace(appender.list.getFirst())).contains("name:Size").doesNotContain("private-rejected-value");
    }
    @Test void businessErrorsKeepTheirResponseContract() throws Exception {
        mvc.perform(get("/debug-test/conflict")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("HTTP 409", "conflict");
    }
    @Test void unhandledFailureIsLoggedAndStillPropagates() {
        assertThrows(jakarta.servlet.ServletException.class,() -> mvc.perform(get("/debug-test/crash")));
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("HTTP 500");
        assertThat(trace(appender.list.getFirst())).contains("Unexpected crash");
    }
    @Test void successfulRequestsHaveNoErrorLog() throws Exception {
        mvc.perform(get("/debug-test/ok")).andExpect(status().isOk()); assertThat(appender.list).isEmpty();
    }
    String trace(ILoggingEvent event) { return ThrowableProxyUtil.asString(event.getThrowableProxy()); }

    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    @Import({HttpErrorLogging.class,KnowledgeBaseController.class,KnowledgeErrorAdvice.class,KnowledgeProperties.class,ServiceResponseAdvice.class,TestController.class})
    static class Config { @Bean KnowledgeBaseService knowledgeService() { return mock(KnowledgeBaseService.class); } }
    @RestController static class TestController {
        record Input(@Size(max=3) String name) {}
        @PostMapping("/debug-test/validate") String validate(@Valid @RequestBody Input input) { return "ok"; }
        @GetMapping("/debug-test/conflict") String conflict() { throw new ServiceResponseException(HttpStatus.CONFLICT,ServiceResult.ErrorCode.CONFLICT,"Busy Session"); }
        @GetMapping("/debug-test/crash") String crash() { throw new IllegalStateException("Unexpected crash"); }
        @GetMapping("/debug-test/ok") String ok() { return "ok"; }
    }
}
