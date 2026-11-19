package com.mindrails.backend.identity;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.*;

/** Real authentication for tests that assemble an embedded web server from selected modules. */
@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(IdentityProperties.class)
@Import({IdentityService.class,HostedFileAccess.class,IdentityFilter.class,AuthController.class,IdentityErrorAdvice.class})
public class TestIdentityConfiguration {
    @Bean
    IdentityStore identityStore() {
        return new InMemoryIdentityStore();
    }


    @Bean(name="agentWebSocketScheduler",destroyMethod="shutdownNow")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="agentWebSocketScheduler")
    ScheduledExecutorService timer() { return Executors.newSingleThreadScheduledExecutor(); }
}
