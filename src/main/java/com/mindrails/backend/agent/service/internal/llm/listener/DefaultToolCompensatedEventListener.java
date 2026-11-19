package com.mindrails.backend.agent.service.internal.llm.listener;

import dev.langchain4j.observability.api.event.ToolCompensatedEvent;
import dev.langchain4j.observability.api.listener.ToolCompensatedEventListener;
import lombok.extern.slf4j.Slf4j;

/** Logs compensated tool identity, reason and full execution details. */
@Slf4j
public class DefaultToolCompensatedEventListener implements ToolCompensatedEventListener {

    public static DefaultToolCompensatedEventListener newInstance() {
        return new DefaultToolCompensatedEventListener();
    }

    @Override
    public void onEvent(ToolCompensatedEvent event) {
        var correlation = new AiServiceLogContext(event.invocationContext());
        var request = event.request();
        log.info("event=tool_compensated, {}, toolName={}, toolCallId={}, reason={}",
                correlation, request.name(), request.id(), event.reason());
        log.debug("event=tool_compensated, {}, toolName={}, toolCallId={}, arguments={}, resultContents={}, reason={}",
                correlation, request.name(), request.id(), request.arguments(), event.resultContents(), event.reason());
    }
}
