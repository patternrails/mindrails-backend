package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.service.SessionDeliverables;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.store.HistoryForkStore;
import com.mindrails.backend.history.store.InMemoryHistoryStore;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.memory.ChatMemoryStoreFactory;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.provider.Provider;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionForkServiceTest {
    @TempDir Path root;
    private final SnapshotIds holder = new SnapshotIds();
    private final com.mindrails.backend.agent.registry.SessionRegistry sessions = new SessionRegistryConfiguration().sessionRegistry();
    private final ChatMemoryStoreFactory memory = com.mindrails.backend.memory.TestMemory.inMemory(2);
    private final InMemoryHistoryStore history = new InMemoryHistoryStore();
    private final Session source = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
            .ownerId(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).name("Original")
            .provider(Provider.builder().id(UUID.randomUUID()).build()).systemMessage("Instructions")
            .skillIds(new ArrayList<>(List.of(UUID.randomUUID()))).createdAt(Instant.now()).updatedAt(Instant.now()).build();

    private SessionDeliverables files() {
        var properties = new AssetStorageProperties(new FileStorageProperties(root));
        return new SessionDeliverables(properties, sessions);
    }
    private SessionForkService forks(HistoryForkStore archive, DefaultLoopSessionStreamService loops, SessionDeliverables files) {
        sessions.insertIfAbsent(source);
        return new SessionForkService(sessions, memory, archive, loops, files, null, null);
    }
    private HistoryRecord record(int index) {
        return HistoryRecord.builder().id(UUID.randomUUID()).ownerId(source.getOwnerId()).agentId(source.getParentAgentId())
                .sessionId(source.getId()).memoryId(source.getMemoryId()).providerId(source.getProvider().getId())
                .requestId(UUID.randomUUID()).transport(HistoryRecord.Transport.STREAM).status(HistoryRecord.Status.COMPLETED)
                .messages("[{\"type\":\"USER\",\"text\":\"message " + index + "\"}]")
                .metadata("{\"usage\":{\"totalTokens\":123}}")
                .startedAt(source.getCreatedAt().plusSeconds(index)).finishedAt(source.getCreatedAt().plusSeconds(index + 1)).schemaVersion(1).build();
    }

    @Test void copiesRawArchiveBeyondUiLimitAndTheStoredWindowWithoutClipping() {
        var forks = forks(history, null, files());
        for (int i = 0; i < 1005; i++) history.insert(record(i));
        var window = List.<ChatMessage>of(SystemMessage.from("Instructions"), UserMessage.from("Recent"), AiMessage.from("Response"));
        memory.restore(source.getMemoryId(), window);
        var target = success(forks.fork(source.getId()));
        assertEquals(window, memory.snapshot(target.getMemoryId()));
        assertEquals(1005, history.session(target.getId()).size());
        assertEquals(source.getProvider(), target.getProvider());
        assertTrue(target.getSkillIds().isEmpty());
        assertNotEquals(source.getId(), target.getId());
        assertNotEquals(source.getMemoryId(), target.getMemoryId());
        var original = history.session(source.getId()).getFirst();
        var copied = history.session(target.getId()).getFirst();
        assertNotEquals(original.id(), copied.id());
        assertEquals(original.requestId(), copied.requestId());
        assertEquals(original.startedAt(), copied.startedAt());
        assertEquals(original.messages(), copied.messages());
        var metadata = new JsonMapper().readTree(copied.metadata());
        assertEquals(original.id().toString(), metadata.get("originHistoryId").asText());
        assertEquals(123, metadata.path("usage").path("totalTokens").asInt());
        history.delete(original.id());
        memory.restore(target.getMemoryId(), List.of(UserMessage.from("Fork only")));
        assertEquals(window, memory.snapshot(source.getMemoryId()));
        assertEquals(1005, history.session(target.getId()).size());
    }

    @Test void filesAndResourceLinksRemainIndependentAfterSourceAndHistoryDeletion() throws Exception {
        var files = files(); var forks = forks(history, null, files);
        Path report = files.resolve(source.getId(), "site/index.html"); Files.createDirectories(report.getParent());
        String sourceUrl = "/api/asset-storage/sessions/" + source.getId() + "/deliverables/";
        Files.writeString(report, sourceUrl + "file/download " + report + " <img src=images/photo.png>");
        var published = files.publish(source.getId(), report);
        Path image = report.getParent().resolve("images/photo.png"); Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{0, 1, 2, 3}); // Unlisted sidecars must be copied too.
        var entry = record(0).toBuilder().messages("[{\"url\":\"" + published.downloadUrl() + "\"}]").build();
        history.insert(entry);
        memory.restore(source.getMemoryId(), List.of(UserMessage.from(published.downloadUrl() + " " + report)));
        var target = success(forks.fork(source.getId()));
        var copy = files.list(target.getId()).stream().filter(f -> f.name().equals("index.html")).findFirst().orElseThrow();
        assertEquals(published.id(), copy.id());
        String content = Files.readString(files.download(target.getId(), copy.id()));
        assertTrue(content.contains("/sessions/" + target.getId() + "/deliverables/"));
        assertFalse(content.contains(source.getId().toString()));
        assertArrayEquals(new byte[]{0, 1, 2, 3}, Files.readAllBytes(files.resolve(target.getId(), "site/images/photo.png")));
        assertTrue(memory.snapshot(target.getMemoryId()).toString().contains(target.getId().toString()));
        assertTrue(history.session(target.getId()).getFirst().messages().contains(target.getId().toString()));
        Files.writeString(files.resolve(target.getId(), copy.path()), "Fork edit");
        assertTrue(Files.readString(report).contains(source.getId().toString()));
        files.deleteSession(source.getId()); sessions.remove(source.getId()); history.delete(entry.id());
        assertEquals("Fork edit", Files.readString(files.download(target.getId(), copy.id())));
        assertEquals(1, history.session(target.getId()).size());
        files.recoverForkFiles(); // A committed target survives recovery; marker disappears.
        assertEquals("Fork edit", Files.readString(files.download(target.getId(), copy.id())));
    }

    @Test void escapedOutputPathsAreReboundWithoutReplacingUnrelatedSessionIds() {
        var properties = new AssetStorageProperties(new FileStorageProperties(root.resolve("quoted \" folder")));
        var files = new SessionDeliverables(properties, sessions);
        var forks = forks(history, null, files);
        String path = files.resolve(source.getId(), "report.md").toString();
        memory.restore(source.getMemoryId(), List.of(UserMessage.from("Use " + path + "; original ID " + source.getId())));
        var target = success(forks.fork(source.getId()));
        String copied = ((UserMessage) memory.snapshot(target.getMemoryId()).getFirst()).singleText();
        assertTrue(copied.contains(files.resolve(target.getId(), "report.md").toString()));
        assertTrue(copied.contains("original ID " + source.getId()));
    }

    @Test void archiveFailureRollsBackOnlyNewStateAndCleansStagedFiles() throws Exception {
        var files = files();
        var broken = new HistoryForkStore() {
            int writes;
            public List<HistoryRecord> session(UUID id) { return history.session(id); }
            public void insertFork(HistoryRecord record) {
                holder.target = record.sessionId(); holder.memory = record.memoryId();
                if (++writes == 2) throw new IllegalStateException("Archive unavailable");
                history.insertFork(record);
            }
            public void deleteFork(UUID id) { history.deleteFork(id); }
        };
        var forks = forks(broken, null, files);
        history.insert(record(0)); history.insert(record(1));
        memory.restore(source.getMemoryId(), List.of(UserMessage.from("Keep me")));
        Path report = files.resolve(source.getId(), "report.txt"); Files.createDirectories(report.getParent());
        Files.writeString(report, "Source"); files.publish(source.getId(), report);
        assertInstanceOf(ServiceResult.Failure.class, forks.fork(source.getId()));
        assertEquals(1, sessions.listManaged(source.getParentAgentId(), 0, 100).size());
        assertTrue(history.session(holder.target).isEmpty()); assertTrue(memory.snapshot(holder.memory).isEmpty());
        try (var dirs = Files.list(root.resolve("assets/sessions"))) { assertEquals(List.of(source.getId().toString()), dirs.map(p -> p.getFileName().toString()).toList()); }
        assertEquals(2, history.session(source.getId()).size());
        assertEquals(List.of(UserMessage.from("Keep me")), memory.snapshot(source.getMemoryId()));
        assertEquals("Source", Files.readString(report));
    }

    @Test void unauthorizedOrUnfinishedSessionsNeverCreateACopy() {
        var forks = forks(history, null, files());
        try (var ignored = ResourceAccess.as(UUID.randomUUID())) {
            assertEquals(ServiceResult.ErrorCode.NOT_FOUND, ((ServiceResult.Failure<?>) forks.fork(source.getId())).code());
        }
        history.insert(record(0).toBuilder().status(HistoryRecord.Status.RUNNING).build());
        assertInstanceOf(ServiceResult.Failure.class, forks.fork(source.getId()));
        assertEquals(1, sessions.listManaged(source.getParentAgentId(), 0, 100).size());
    }

    @Test void runningAndDoomedAreRejectedAndStopContextIsCopiedWithoutConsumingSource() {
        var factory = mock(LlmClientFactory.class); var client = mock(LlmClient.class);
        when(factory.createClientForLoop(any())).thenReturn(ServiceResult.success(client));
        var stream = mock(TokenStream.class, RETURNS_SELF);
        when(client.chatWithStream(any(), anyString(), any())).thenReturn(stream);
        try (var shutdown = new LoopFixture(new DefaultLoopSessionStreamService(sessions, factory, TestHistory.disabled(), TestMetrics.disabled(), null))) {
            var loops = shutdown.loops; var forks = forks(history, loops, files());
            success(loops.enqueue(source.getId(), new ClientCommand.Chat(UUID.randomUUID(), "A")));
            TestSessionChat.await(() -> { try { verify(stream).start(); return true; } catch (AssertionError ignored) { return false; } });
            assertInstanceOf(ServiceResult.Failure.class, forks.fork(source.getId()));
            success(loops.enqueue(source.getId(), new ClientCommand.Chat(UUID.randomUUID(), "B")));
            success(loops.enqueue(source.getId(), new ClientCommand.Chat(UUID.randomUUID(), "C")));
            var call = ToolExecutionRequest.builder().id("call").name("write").arguments("{}").build();
            memory.restore(source.getMemoryId(), List.of(UserMessage.from("A"), AiMessage.from(call)));
            success(loops.stop(source.getId()));
            var target = success(forks.fork(source.getId()));
            var copied = memory.snapshot(target.getMemoryId());
            assertTrue(copied.toString().contains("B")); assertTrue(copied.toString().contains("C"));
            assertTrue(copied.toString().contains("user stopped"));
            assertInstanceOf(ToolExecutionResultMessage.class, copied.get(2));
            assertEquals(2, memory.snapshot(source.getMemoryId()).size()); // Recovery never alters source.
            var loop = ((Map<?, ?>) ReflectionTestUtils.getField(loops, "llmRunLoops")).get(source.getId());
            assertEquals(3, ((List<?>) ReflectionTestUtils.getField(loop, "stoppedContext")).size());
            var state = (Enum<?>) ReflectionTestUtils.getField(loop, "state");
            ReflectionTestUtils.setField(loop, "state", Arrays.stream(state.getDeclaringClass().getEnumConstants()).filter(s -> s.toString().equals("DOOMED")).findFirst().orElseThrow());
            assertInstanceOf(ServiceResult.Failure.class, forks.fork(source.getId()));
        }
    }

    @Test void enqueueCannotStartInsideTheSnapshotButOtherSessionsRemainUsable() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocked = new HistoryForkStore() {
            public List<HistoryRecord> session(UUID id) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Snapshot timed out"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return history.session(id);
            }
            public void insertFork(HistoryRecord record) { history.insertFork(record); }
            public void deleteFork(UUID id) { history.deleteFork(id); }
        };
        var factory = mock(LlmClientFactory.class); var client = mock(LlmClient.class);
        when(factory.createClientForLoop(any())).thenReturn(ServiceResult.success(client));
        when(client.chatWithStream(any(), anyString(), any())).thenReturn(mock(TokenStream.class, RETURNS_SELF));
        try (var fixture = new LoopFixture(new DefaultLoopSessionStreamService(sessions, factory, TestHistory.disabled(), TestMetrics.disabled(), null));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var loops = fixture.loops; var forks = forks(blocked, loops, files());
            memory.restore(source.getMemoryId(), List.of(UserMessage.from("Snapshot boundary")));
            var other = source.toBuilder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build(); sessions.insertIfAbsent(other);
            var fork = executor.submit(() -> forks.fork(source.getId()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = executor.submit(() -> loops.enqueue(source.getId(), new ClientCommand.Chat(UUID.randomUUID(), "After snapshot")));
            try {
                assertThrows(TimeoutException.class, () -> queued.get(100, TimeUnit.MILLISECONDS));
                success(loops.enqueue(other.getId(), new ClientCommand.Chat(UUID.randomUUID(), "Independent work")));
                var sessionService = new DefaultSessionService(sessions);
                ReflectionTestUtils.setField(sessionService, "loops", loops);
                assertEquals(ServiceResult.ErrorCode.CONFLICT,
                        ((ServiceResult.Failure<?>) sessionService.deleteSession(source.getId())).code());
                assertEquals(ServiceResult.ErrorCode.CONFLICT,
                        ((ServiceResult.Failure<?>) sessionService.updateSession(source.getId(), source)).code());
                assertTrue(sessions.findManaged(source.getId()).isPresent());
            } finally { release.countDown(); }
            var target = success(fork.get(2, TimeUnit.SECONDS));
            success(queued.get(2, TimeUnit.SECONDS));
            assertEquals(List.of(UserMessage.from("Snapshot boundary")), memory.snapshot(target.getMemoryId()));
        }
    }

    @Test void startupRecoveryRemovesOnlyUncommittedForkFolders() throws Exception {
        var files = files(); forks(history, null, files);
        Path path = files.resolve(source.getId(), "keep.txt"); Files.createDirectories(path.getParent());
        Files.writeString(path, "Source"); files.publish(source.getId(), path);
        UUID staged = UUID.randomUUID(), uncommitted = UUID.randomUUID(), committed = UUID.randomUUID();
        files.stageFork(source.getId(), staged); // Simulated process exit before rename.
        files.stageFork(source.getId(), uncommitted).publish(); // Simulated exit before SQL commit.
        files.stageFork(source.getId(), committed).publish();
        sessions.insertIfAbsent(source.toBuilder().id(committed).memoryId(UUID.randomUUID()).build());
        files.recoverForkFiles();
        assertFalse(Files.exists(root.resolve("assets/sessions/.fork-" + staged)));
        assertFalse(Files.exists(root.resolve("assets/sessions/" + uncommitted)));
        assertEquals("Source", Files.readString(files.resolve(committed, "keep.txt")));
        assertEquals("Source", Files.readString(path));
    }

    private static <T> T success(ServiceResult<T> result) { return ((ServiceResult.Success<T>) result).value(); }
    private static class SnapshotIds { UUID target, memory; }
    private record LoopFixture(DefaultLoopSessionStreamService loops) implements AutoCloseable { public void close() { loops.shutdown(); } }
}
