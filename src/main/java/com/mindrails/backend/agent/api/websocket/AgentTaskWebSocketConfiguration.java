package com.mindrails.backend.agent.api.websocket;

import com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway;
import com.mindrails.backend.identity.*;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@EnableWebSocket
public class AgentTaskWebSocketConfiguration {
    public static final String PATH="/api/agent/{agentId}/tasks/{taskId}/stream";
    @Bean AgentTaskWebSocketHandler agentTaskWebSocketHandler(AgentTaskExecutionStreamGateway gateway,ThreadPoolTaskScheduler taskCronScheduler) {
        return new AgentTaskWebSocketHandler(gateway,taskCronScheduler);
    }
    @Bean WebSocketConfigurer agentTaskWebSocketConfigurer(AgentTaskWebSocketHandler handler, IdentityService identity, IdentityProperties properties,
            @Qualifier("agentWebSocketScheduler") ScheduledExecutorService scheduler) {
        return registry -> registry.addHandler(new IdentityWebSocketHandler(handler,identity,scheduler),PATH)
                .addInterceptors(new AgentTaskHandshakeInterceptor(handler.gateway())).setAllowedOrigins(properties.origins());
    }
}
