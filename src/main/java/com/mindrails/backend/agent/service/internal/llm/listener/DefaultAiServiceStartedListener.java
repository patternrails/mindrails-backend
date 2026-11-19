package com.mindrails.backend.agent.service.internal.llm.listener;

import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.listener.AiServiceStartedListener;
import lombok.extern.slf4j.Slf4j;

/** Logs invocation start and the initial messages. */
@Slf4j
public class DefaultAiServiceStartedListener implements AiServiceStartedListener {

    public static DefaultAiServiceStartedListener newInstance() {
        return new DefaultAiServiceStartedListener();
    }

    @Override
    public void onEvent(AiServiceStartedEvent event) {
        var context = event.invocationContext();
        var correlation = new AiServiceLogContext(context);
        log.info("event=ai_service_started, {}, interfaceName={}, methodName={}",
                correlation, context.interfaceName(), context.methodName());
        log.debug("event=ai_service_started, {}, systemMessage={}, userMessage={}",
                correlation, event.systemMessage().orElse(null), event.userMessage());
    }
}
