package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.SessionDeliverables;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.store.HistoryForkStore;
import com.mindrails.backend.memory.ChatMemoryStoreFactory;
import com.mindrails.backend.memory.ToolCallRecovery;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson;
import static dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson;

/** Copies persisted conversation state. No model calls, tool execution, or Metrics are replayed. */
@Service
public final class SessionForkService {
    private final SessionRegistry sessions;
    private final ChatMemoryStoreFactory memory;
    private final HistoryForkStore history;
    private final @Nullable DefaultLoopSessionStreamService loops;
    private final @Nullable SessionDeliverables files;
    private final @Nullable AssetRegistry assets;
    private final @Nullable TransactionTemplate transaction;
    private final @Nullable JdbcTemplate jdbc;
    private final JsonMapper json = new JsonMapper();

    public SessionForkService(SessionRegistry sessions, ChatMemoryStoreFactory memory, @Qualifier("historyForkStore") HistoryForkStore history,
            @Nullable DefaultLoopSessionStreamService loops, @Nullable SessionDeliverables files,
            @Nullable AssetRegistry assets, @Qualifier("persistenceJdbcTemplate") @Nullable JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.memory = memory;
        this.history = history;
        this.loops = loops;
        this.files = files;
        this.assets = assets;
        this.jdbc = jdbc;
        this.transaction = jdbc == null ? null : new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    public ServiceResult<Session> fork(UUID sourceId) {
        if (sessions.findManaged(sourceId).isEmpty()) return ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found");
        return loops == null ? copy(sourceId, List.of()) : loops.withIdleSession(sourceId, context -> copy(sourceId, context));
    }

    public ServiceResult<Void> discard(UUID sessionId) {
        return ServiceResult.<Void>tryOf(() -> {
            var target = sessions.findManaged(sessionId).orElse(null);
            if (target == null) return null;
            java.util.function.Supplier<Void> discard = () -> {
                if (files != null) files.deleteSession(sessionId);
                for (var record : history.session(sessionId)) {
                    if (assets != null) assets.clearRun(record.id());
                    history.deleteFork(record.id());
                }
                if (assets != null) assets.clearSession(sessionId);
                memory.delete(target.getMemoryId());
                sessions.remove(sessionId);
                return null;
            };
            return transaction == null ? discard.get() : transaction.execute(ignored -> discard.get());
        }, ErrorCode.INTERNAL_ERROR);
    }

    private ServiceResult<Session> copy(UUID sourceId, List<String> stoppedContext) {
        var source = sessions.snapshot(sourceId).orElse(null);
        if (source == null) return ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found");
        Instant now = Instant.now();
        var target = source.toBuilder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .name(forkName(source.getName()))
                .skillIds(List.of()).version(0).createdAt(now).updatedAt(now).build();
        var copiedHistory = new ArrayList<UUID>();
        boolean[] inserted = {false};
        try (var staged = files == null ? null : files.stageFork(sourceId, target.getId())) {
            java.util.function.Supplier<Session> copy = () -> {
                if (assets == null && jdbc != null && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM asset_chat_refs WHERE session_id=?)"
                                + " OR EXISTS(SELECT 1 FROM asset_session_output_refs WHERE session_id=?)"
                                + " OR EXISTS(SELECT 1 FROM asset_source_refs r JOIN history h ON h.id=r.run_id WHERE h.session_id=?)",
                        Boolean.class, sourceId.toString(), sourceId.toString(), sourceId.toString()))) {
                    throw new IllegalStateException("Enable Asset Storage before forking a Session with Asset references");
                }
                var archive = history.session(sourceId);
                if (archive.stream().anyMatch(r -> r.status() == HistoryRecord.Status.RUNNING)) {
                    throw new IllegalStateException("Session has unfinished History; wait for it to finish before forking");
                }
                // Read the actual stored window. An archive can contain more (or fewer) messages.
                var messages = new ArrayList<>(memory.snapshot(source.getMemoryId()));
                for (String text : stoppedContext) messages.add(UserMessage.from(text));
                messages = new ArrayList<>(messagesFromJson(rewrite(messagesToJson(messages), sourceId, target.getId())));
                // Repair only the detached copy; stopping a tool may leave its result unknown.
                var detached = MessageWindowChatMemory.builder().id(target.getMemoryId()).maxMessages(Integer.MAX_VALUE)
                        .chatMemoryStore(new InMemoryChatMemoryStore()).build();
                detached.set(messages);
                ToolCallRecovery.recover(detached);
                if (!sessions.insertIfAbsent(target)) throw new IllegalStateException("Fork Session ID already exists");
                inserted[0] = true;
                memory.restore(target.getMemoryId(), detached.messages());
                if (assets != null) {
                    for (UUID versionId : assets.sessionVersions(sourceId)) {
                        var version = assets.version(versionId);
                        if (version == null) throw new IllegalStateException("Attached Asset version is missing");
                        assets.lock(version.getAssetId());
                        var asset = assets.asset(version.getAssetId());
                        if (asset == null) throw new IllegalStateException("Attached Asset is missing");
                        com.mindrails.backend.identity.ResourceAccess.requireBinding(target.getOwnerId(), asset.ownerId());
                        assets.attach(target.getId(), versionId);
                    }
                }
                for (var record : archive) {
                    UUID id = UUID.randomUUID();
                    ObjectNode metadata = (ObjectNode) json.readTree(rewrite(record.metadata(), sourceId, target.getId()));
                    metadata.put("originHistoryId", record.id().toString());
                    metadata.put("forkedFromSessionId", sourceId.toString());
                    history.insertFork(record.toBuilder().id(id).sessionId(target.getId()).memoryId(target.getMemoryId())
                            .messages(rewrite(record.messages(), sourceId, target.getId()))
                            .metadata(json.writeValueAsString(metadata)).build());
                    copiedHistory.add(id);
                    if (assets != null) assets.copyRunSources(record.id(), id);
                }
                if (staged != null) {
                    for (var file : staged.manifest()) {
                        if (file.versionId() != null) {
                            // Backfill legacy source images too, without changing their manifest or content.
                            files.bindOutput(sourceId, file);
                            files.bindOutput(target.getId(), file);
                        }
                    }
                    try { staged.publish(); }
                    catch (IOException error) { throw new UncheckedIOException(error); }
                }
                return target;
            };
            var result = transaction == null ? copy.get() : transaction.execute(ignored -> copy.get());
            if (staged != null) staged.committed();
            return ServiceResult.success(result);
        } catch (RuntimeException | IOException error) {
            // SQL rolls back together. These operations also compensate in-memory stores in mixed configurations.
            if (inserted[0]) {
                cleanup(error, () -> { if (assets != null) assets.clearSession(target.getId()); });
                for (UUID id : copiedHistory) {
                    cleanup(error, () -> { if (assets != null) assets.clearRun(id); });
                    cleanup(error, () -> history.deleteFork(id));
                }
                cleanup(error, () -> memory.delete(target.getMemoryId()));
                cleanup(error, () -> sessions.remove(target.getId()));
            }
            org.slf4j.LoggerFactory.getLogger(SessionForkService.class).error("Session fork failed: sessionId={}", sourceId, error);
            return ServiceResult.failure(ErrorCode.CONFLICT, "Session fork failed: " + error.getMessage());
        }
    }

    private String forkName(String name) {
        if (name == null) return null;
        int end = name.offsetByCodePoints(0, Math.min(153, name.codePointCount(0, name.length())));
        return name.substring(0, end) + " (fork)";
    }

    private String rewrite(String value, UUID source, UUID target) {
        return files == null ? value : files.rewriteForkResources(value, source, target);
    }

    private void cleanup(Throwable failure, Runnable operation) {
        try { operation.run(); } catch (RuntimeException error) { failure.addSuppressed(error); }
    }
}
