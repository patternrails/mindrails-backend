package com.mindrails.backend.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.history.ChatHistory;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingApiTest {
    @Test
    void recordingBeansExposeOnlyRecord() {
        for (var type : List.of(MetricsRecorder.class, ChatHistory.class)) {
            var methods = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers())).toList();
            assertEquals(1, methods.size(), type.getSimpleName());
            assertEquals("record", methods.getFirst().getName());
        }
    }

    @Test
    void buildersHaveNoInstanceStateAndSessionRegistryHasNoMutateApi() {
        for (var type : List.of(MetricsRecords.class, com.mindrails.backend.history.service.internal.HistoryRecords.class)) {
            assertTrue(Arrays.stream(type.getDeclaredFields()).allMatch(field -> Modifier.isStatic(field.getModifiers())));
            assertTrue(Arrays.stream(type.getDeclaredConstructors()).allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        }
        for (var type : List.of(com.mindrails.backend.agent.registry.SessionRegistry.class,
                com.mindrails.backend.agent.registry.MysqlSessionRegistry.class)) {
            assertTrue(Arrays.stream(type.getMethods()).noneMatch(method -> method.getName().equals("mutate")));
        }
    }

    @Test
    void metricsRecordingDoesNotThrowWhenTheSinkFails() {
        var properties = new MetricsProperties();

        var health = new MetricsHealth();
        var buffer = mock(MetricsSnapshotBuffer.class);
        when(buffer.offer(any())).thenThrow(new IllegalStateException("Synthetic recording failure"));
        var recorder = new MetricsRecorder(properties, buffer, health);
        var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build();
        var parameters = com.mindrails.backend.agent.model.LlmClient.parameters(session, null, "REST");
        assertDoesNotThrow(() -> recorder.record(MetricsRecords.start(parameters)));
        assertDoesNotThrow(() -> recorder.record(MetricsRecords.finish(parameters, "SUCCEEDED", null, null, null)));
        assertEquals(2, health.collectionFailures.get());
        assertDoesNotThrow(() -> recorder.record(null));
    }
}
