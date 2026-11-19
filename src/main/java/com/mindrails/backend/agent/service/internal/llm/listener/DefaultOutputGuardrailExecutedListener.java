package com.mindrails.backend.agent.service.internal.llm.listener;

import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.listener.OutputGuardrailExecutedListener;
import lombok.extern.slf4j.Slf4j;

/** Logs output validation status and response validation details. */
@Slf4j
public class DefaultOutputGuardrailExecutedListener implements OutputGuardrailExecutedListener {

    public static DefaultOutputGuardrailExecutedListener newInstance() {
        return new DefaultOutputGuardrailExecutedListener();
    }

    @Override
    public void onEvent(OutputGuardrailExecutedEvent event) {
        var correlation = new AiServiceLogContext(event.invocationContext());
        log.info("event=output_guardrail_executed, {}, guardrailName={}, result={}, duration={}",
                correlation, event.guardrailName(), event.result().result(), event.duration());
        log.debug("event=output_guardrail_executed, {}, guardrailClass={}, guardrailName={}, responseFromLLM={}, result={}, duration={}",
                correlation, event.guardrailClass(), event.guardrailName(), event.request().responseFromLLM(),
                event.result(), event.duration());
    }
}
