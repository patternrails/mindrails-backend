package com.mindrails.backend.agent.api.websocket;

import com.mindrails.backend.identity.*;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway;
import com.mindrails.backend.common.ServiceResult;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.server.ServerContainer;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class AgentSessionWebSocketConfiguration {
    public static final String PATH = "/api/agent/{agentId}/sessions/{sessionId}/stream";

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService agentWebSocketScheduler() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("agent-ws-timer-", 0).factory());
    }

    @Bean
    AgentSessionWebSocketHandler agentSessionWebSocketHandler(ObjectProvider<AgentSessionChatStreamGateway> gateways, JsonMapper mapper,
                                                              @Qualifier("agentWebSocketScheduler") ScheduledExecutorService scheduler) {
        return new AgentSessionWebSocketHandler(gateways.getIfAvailable(this::unavailableAgentChatStreamGateway), mapper, scheduler);
    }

    @Bean
    WebSocketConfigurer agentSessionWebSocketConfigurer(AgentSessionWebSocketHandler handler,
            JsonMapper mapper, IdentityService identity, IdentityProperties properties,
            @Qualifier("agentWebSocketScheduler") ScheduledExecutorService scheduler) {
        return registry -> registry.addHandler(new IdentityWebSocketHandler(handler, identity, scheduler), PATH)
                .addInterceptors(new AgentSessionHandshakeInterceptor(mapper))
                .setAllowedOrigins(properties.origins());
    }

    @Bean
    ServletContextInitializer agentWebSocketContainer() {
        // The container is available after servlet initialization, and absent in mock-MVC contexts.
        return context -> context.addListener(new ServletContextListener() {
            @Override public void contextInitialized(ServletContextEvent event) {
                var container = (ServerContainer) event.getServletContext().getAttribute(ServerContainer.class.getName());
                if (container != null) {
                    container.setDefaultMaxTextMessageBufferSize(AgentWebSocketProperties.inboundBytes);
                    container.setDefaultMaxBinaryMessageBufferSize(AgentWebSocketProperties.inboundBytes);
                    container.setDefaultMaxSessionIdleTimeout(0L);
                }
            }
        });
    }

    /** Local stage-one placeholder, used only when no application Gateway bean is available. */
    AgentSessionChatStreamGateway unavailableAgentChatStreamGateway() {
        return new AgentSessionChatStreamGateway() {
            private <T> ServiceResult<T> unavailable() {
                return ServiceResult.failure(ServiceResult.ErrorCode.NOT_IMPLEMENTED, "Streaming service is not implemented");
            }
            @Override public ServiceResult<SessionSnapshot> validate(AgentSessionScope agentSessionScope) { return unavailable(); }

            @Override
            public ServiceResult<Void> chat(AgentSessionScope agentSessionScope, ClientCommand.Chat command) {
                return unavailable();
            }

            @Override
            public ServiceResult<Void> command(AgentSessionScope agentSessionScope, CommandType type, Object payload) {
                return unavailable();
            }
        };
    }
}
