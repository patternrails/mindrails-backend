package com.mindrails.backend.metrics;

import com.mindrails.backend.agent.model.Session;
import java.util.UUID;

/** Test harness owns writer controls; they are not part of the recording API. */
public final class TestMetrics implements AutoCloseable {
    private final MetricsProperties properties;
    private final MetricsHealth health;
    private final MetricsSnapshotBuffer buffer;
    private final MetricsWriter writer;
    private final MetricsRecorder recorder;

    public TestMetrics(MetricsProperties properties, MetricsRepository repository, MetricsHealth health) {
        this(properties, repository, health, true);
    }

    public TestMetrics(MetricsProperties properties, MetricsRepository repository, MetricsHealth health, boolean schedule) {
        this.properties = properties;
        this.health = health;
        buffer = new MetricsSnapshotBuffer(Math.max(1, properties.getMaxRuns()), Math.max(8192, properties.getMaxBufferBytes()), properties.getLateCallbackRetention(), health);
        recorder = new MetricsRecorder(properties, buffer, health);
        writer = new MetricsWriter(repository, buffer, properties, health);
        if (schedule) writer.start();
    }

    public static MetricsRecorder disabled() {
        var properties = new MetricsProperties();
        properties.setMaxRuns(0);
        var health = new MetricsHealth();
        return new MetricsRecorder(properties, new MetricsSnapshotBuffer(1, 8192, java.time.Duration.ofSeconds(60), health), health);
    }

    public MetricsRecorder recorder() { return recorder; }
    public Sample begin(Session session, UUID requestId, String transport) {
        return new Sample(session == null ? Session.builder().build() : session, requestId, transport);
    }
    public final class Sample {
        private final dev.langchain4j.invocation.InvocationParameters parameters;
        private MetricsSnapshot snapshot;
        Sample(Session session, UUID requestId, String transport) {
            parameters = com.mindrails.backend.agent.model.LlmClient.parameters(session, requestId, transport);
            record(MetricsRecords.start(parameters));
        }
        private void record(MetricsSnapshot value) {
            snapshot = MetricsRecords.merge(snapshot, value);
            recorder.record(value);
        }
        public UUID runId() { return parameters.get(com.mindrails.backend.agent.model.LlmClient.RUN_ID); }
        public dev.langchain4j.invocation.InvocationParameters parameters() { return parameters; }
        public MetricsSnapshot snapshot() { return snapshot; }
        public void finish(String status, String reason, String code) { record(MetricsRecords.finish(parameters, status, reason, code, null)); }
        public Model modelStarted(UUID invocationId, String model) {
            var call = new Model(dev.langchain4j.invocation.InvocationContext.builder().invocationId(invocationId).invocationParameters(parameters).build(), UUID.randomUUID(), java.time.Instant.now(), model);
            record(MetricsRecords.model(call.context(), call.id(), call.at(), model, null, false));
            return call;
        }
        public Capability capabilityStarted(UUID invocationId, String toolId, String name, String kind) {
            var call = new Capability(dev.langchain4j.invocation.InvocationContext.builder().invocationId(invocationId).invocationParameters(parameters).build(), UUID.randomUUID(), java.time.Instant.now(), toolId, name, kind);
            record(MetricsRecords.capability(call.context(), call.id(), call.at(), toolId, name, kind, null, null, null));
            return call;
        }
        public final class Model {
            private final dev.langchain4j.invocation.InvocationContext context;
            private final UUID id;
            private final java.time.Instant at;
            private final String name;
            Model(dev.langchain4j.invocation.InvocationContext context, UUID id, java.time.Instant at, String name) { this.context=context;this.id=id;this.at=at;this.name=name; }
            dev.langchain4j.invocation.InvocationContext context() { return context; }
            UUID id() { return id; }
            java.time.Instant at() { return at; }
            public void response(dev.langchain4j.model.chat.response.ChatResponse response) { record(MetricsRecords.model(context,id,at,name,response,false)); }
            public void failed() { record(MetricsRecords.model(context,id,at,name,null,true)); }
        }
        public final class Capability {
            private final dev.langchain4j.invocation.InvocationContext context;
            private final UUID id;
            private final java.time.Instant at;
            private final String toolId, name, kind;
            Capability(dev.langchain4j.invocation.InvocationContext context, UUID id, java.time.Instant at, String toolId, String name, String kind) { this.context=context;this.id=id;this.at=at;this.toolId=toolId;this.name=name;this.kind=kind; }
            dev.langchain4j.invocation.InvocationContext context() { return context; }
            UUID id() { return id; }
            java.time.Instant at() { return at; }
            public void finish(boolean failed) { record(MetricsRecords.capability(context,id,at,toolId,name,kind,null,null,failed)); }
        }

    }
    public MetricsHealth health() { return health; }
    public MetricsSnapshotBuffer buffer() { return buffer; }
    public boolean enabled() { return properties.valid(); }
    public void flush() { writer.flush(); }
    @Override public void close() { writer.close(); }
}
