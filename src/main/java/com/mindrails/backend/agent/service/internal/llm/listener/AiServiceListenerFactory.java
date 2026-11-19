package com.mindrails.backend.agent.service.internal.llm.listener;

import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.history.service.internal.HistoryRecords;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.metrics.MetricsRecords;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.observability.api.listener.AiServiceErrorListener;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.observability.api.listener.AiServiceRequestIssuedListener;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.service.tool.ToolExecution;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component("aiServiceListenerFactory")
public class AiServiceListenerFactory {
    private final ChatHistory chatHistory;
    private final MetricsRecorder metricsRecorder;

    public AiServiceListenerFactory(
            @Qualifier("chatHistory") ChatHistory chatHistory,
            @Qualifier("metricsRecorder") MetricsRecorder metricsRecorder) {
        this.chatHistory = chatHistory;
        this.metricsRecorder = metricsRecorder;
    }

    public List<AiServiceListener<?>> defaultListeners() {
        return List.of(
                DefaultAiServiceStartedListener.newInstance(),
                DefaultAiServiceRequestIssuedListener.newInstance(),
                DefaultAiServiceResponseReceivedListener.newInstance(),
                DefaultAiServiceErrorListener.newInstance(),
                DefaultAiServiceCompletedListener.newInstance(),
                DefaultToolExecutedEventListener.newInstance(),
                DefaultToolCompensatedEventListener.newInstance(),
                DefaultInputGuardrailExecutedListener.newInstance(),
                DefaultOutputGuardrailExecutedListener.newInstance());
    }

    public RecordListeners recordListeners() {
        AiServiceRequestIssuedListener requestIssued = event -> metricsRecorder.record(
                MetricsRecords.modelRequested(event.invocationContext(), event.request().modelName()));
        AiServiceResponseReceivedListener responseReceived = event -> {
            chatHistory.record(HistoryRecords.response(event.invocationContext(), event.response()));
            metricsRecorder.record(MetricsRecords.modelResponded(event.invocationContext(), event.response()));
        };
        AiServiceErrorListener error = event -> metricsRecorder.record(MetricsRecords.modelFailed(event.invocationContext()));
        // The after-execution callback preserves error flags and attributes omitted by ToolExecutedEvent.
        Consumer<ToolExecution> toolExecution = event -> chatHistory.record(HistoryRecords.message(event.invocationContext(),
                ToolExecutionResultMessage.builder().id(event.request().id()).toolName(event.request().name())
                        .contents(event.resultContents()).isError(event.hasFailed()).attributes(event.attributes()).build()));
        return new RecordListeners(List.of(requestIssued, responseReceived, error), toolExecution);
    }

    public record RecordListeners(
            List<AiServiceListener<?>> listeners,
            Consumer<ToolExecution> toolExecutionListener) {}
}
