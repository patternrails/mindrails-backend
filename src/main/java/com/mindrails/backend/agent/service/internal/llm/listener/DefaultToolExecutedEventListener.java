package com.mindrails.backend.agent.service.internal.llm.listener;

import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import lombok.extern.slf4j.Slf4j;

/** Logs tool identity at INFO and arguments and full result contents at DEBUG. */
@Slf4j
public class DefaultToolExecutedEventListener implements ToolExecutedEventListener {

    public static DefaultToolExecutedEventListener newInstance() {
        return new DefaultToolExecutedEventListener();
    }

    @Override
    public void onEvent(ToolExecutedEvent event) {
        var correlation = new AiServiceLogContext(event.invocationContext());
        var request = event.request();
        log.info("event=tool_executed, {}, toolName={}, toolCallId={}, resultContentCount={}",
                correlation, request.name(), request.id(), event.resultContents().size());
        log.debug("event=tool_executed, {}, toolName={}, toolCallId={}, arguments={}, resultContents={}",
                correlation, request.name(), request.id(), request.arguments(), event.resultContents());
    }
}
