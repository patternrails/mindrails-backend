package com.mindrails.backend.agent.service.internal.llm.listener;

import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import lombok.extern.slf4j.Slf4j;

/** Logs every model response without repeating the request payload. */
@Slf4j
public class DefaultAiServiceResponseReceivedListener implements AiServiceResponseReceivedListener {

    public static DefaultAiServiceResponseReceivedListener newInstance() {
        return new DefaultAiServiceResponseReceivedListener();
    }

    @Override
    public void onEvent(AiServiceResponseReceivedEvent event) {
        var correlation = new AiServiceLogContext(event.invocationContext());
        var response = event.response();
        log.info("event=ai_service_response_received, {}, modelName={}, responseId={}, finishReason={}, toolCalls={}, tokenUsage={}",
                correlation, correlation.modelName(response.modelName()), response.id(), response.finishReason(),
                response.aiMessage().toolExecutionRequests().size(), response.tokenUsage());
        log.debug("event=ai_service_response_received, {}, response={}", correlation, response);
    }
}
