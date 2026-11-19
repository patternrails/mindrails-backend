package com.mindrails.backend.agent.service.internal.llm.listener;

import dev.langchain4j.observability.api.event.InputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.listener.InputGuardrailExecutedListener;
import lombok.extern.slf4j.Slf4j;

/** Logs input validation status and rewritten message details. */
@Slf4j
public class DefaultInputGuardrailExecutedListener implements InputGuardrailExecutedListener {

    public static DefaultInputGuardrailExecutedListener newInstance() {
        return new DefaultInputGuardrailExecutedListener();
    }

    @Override
    public void onEvent(InputGuardrailExecutedEvent event) {
        var correlation = new AiServiceLogContext(event.invocationContext());
        log.info("event=input_guardrail_executed, {}, guardrailName={}, result={}, duration={}",
                correlation, event.guardrailName(), event.result().result(), event.duration());
        log.debug("event=input_guardrail_executed, {}, guardrailClass={}, guardrailName={}, userMessage={}, result={}, duration={}, rewrittenUserMessage={}",
                correlation, event.guardrailClass(), event.guardrailName(), event.request().userMessage(),
                event.result(), event.duration(), event.rewrittenUserMessage());
    }
}
