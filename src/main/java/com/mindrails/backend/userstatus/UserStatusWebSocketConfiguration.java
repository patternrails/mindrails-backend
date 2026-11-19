package com.mindrails.backend.userstatus;

import com.mindrails.backend.identity.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import tools.jackson.databind.json.JsonMapper;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
public class UserStatusWebSocketConfiguration {
    public static final String PATH = "/api/user/status/stream";
    @Bean(destroyMethod = "shutdownNow") ScheduledExecutorService userStatusScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("user-status-timer").factory());
    }
    @Bean UserStatusWebSocketHandler userStatusWebSocketHandler(UserSessionStatusService statuses, JsonMapper mapper,
            @Qualifier("userStatusScheduler") ScheduledExecutorService scheduler) {
        return new UserStatusWebSocketHandler(statuses, mapper, scheduler);
    }
    @Bean WebSocketConfigurer userStatusWebSocketConfigurer(UserStatusWebSocketHandler handler, IdentityService identity,
            IdentityProperties properties, @Qualifier("userStatusScheduler") ScheduledExecutorService scheduler) {
        return registry -> registry.addHandler(new IdentityWebSocketHandler(handler, identity, scheduler), PATH)
                .setAllowedOrigins(properties.origins());
    }
}
