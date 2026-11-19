package com.mindrails.backend.agent.service.internal.llm.listener;

import com.mindrails.backend.agent.model.LlmClient;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.observability.api.event.AiServiceRequestIssuedEvent;
import dev.langchain4j.observability.api.listener.AiServiceRequestIssuedListener;
import lombok.extern.slf4j.Slf4j;

/** Checks cancellation before dispatch and logs every model request. */
@Slf4j
public class DefaultAiServiceRequestIssuedListener implements AiServiceRequestIssuedListener {

    public static DefaultAiServiceRequestIssuedListener newInstance() {
        return new DefaultAiServiceRequestIssuedListener();
    }

    @Override
    public void onEvent(AiServiceRequestIssuedEvent event) {
        var context = event.invocationContext();
        LlmClient.checkCancelled(context);
        var correlation = new AiServiceLogContext(context);
        var request = event.request();
        log.info("event=ai_service_request_issued, {}, modelName={}, messageCount={}, toolCount={}",
                correlation, correlation.modelName(request.modelName()), request.messages().size(), request.toolSpecifications().size());
        if (!log.isDebugEnabled()) return;
        for (var message : request.messages()) {
            if (message instanceof SystemMessage systemMessage) {
                log.debug("event=ai_service_system_message, {}, systemMessage={}", correlation, systemMessage.text());
            }
        }
        if (request.messages().stream().noneMatch(message ->
                message instanceof UserMessage user && user.contents().stream().anyMatch(ImageContent.class::isInstance))) {
            log.debug("event=ai_service_request_issued, {}, request={}", correlation, request);
        }
    }
}
