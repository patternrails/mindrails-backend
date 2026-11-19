package com.mindrails.backend.agent.service.internal.llm.listener;

import com.mindrails.backend.common.ExceptionDiagnostics;

import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.listener.AiServiceErrorListener;
import lombok.extern.slf4j.Slf4j;

/** Logs failure type at INFO and the exception with its cause at DEBUG. */
@Slf4j
public class DefaultAiServiceErrorListener implements AiServiceErrorListener {

    public static DefaultAiServiceErrorListener newInstance() {
        return new DefaultAiServiceErrorListener();
    }

    @Override
    public void onEvent(AiServiceErrorEvent event) {
        var correlation = new AiServiceLogContext(event.invocationContext());
        log.info("event=ai_service_error, {}, errorType={}", correlation, event.error().getClass().getName());
        if (log.isDebugEnabled()) {
            var error = ExceptionDiagnostics.withoutImagePayload(event.error());
            log.debug("event=ai_service_error, {}, error={}", correlation, error.toString(), error);
        }
    }
}
