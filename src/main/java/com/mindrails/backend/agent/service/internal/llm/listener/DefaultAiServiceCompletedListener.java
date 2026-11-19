package com.mindrails.backend.agent.service.internal.llm.listener;

import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.listener.AiServiceCompletedListener;
import lombok.extern.slf4j.Slf4j;

/** Logs successful invocation completion and the final result. */
@Slf4j
public class DefaultAiServiceCompletedListener implements AiServiceCompletedListener {

    public static DefaultAiServiceCompletedListener newInstance() {
        return new DefaultAiServiceCompletedListener();
    }

    @Override
    public void onEvent(AiServiceCompletedEvent event) {
        var correlation = new AiServiceLogContext(event.invocationContext());
        log.info("event=ai_service_completed, {}", correlation);
        log.debug("event=ai_service_completed, {}, result={}", correlation, event.result().orElse(null));
    }
}
