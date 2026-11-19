package com.mindrails.backend.asset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.*;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.knowledge.service.KnowledgeSearchService;

import org.junit.jupiter.api.Test;

import java.util.*;

class AssetConversationRetrieverTest {
    @Test
    void completeAttachmentTextCanBeReadBeyondRetrievalBudgetWithoutSkippingContent() {
        var assets = mock(AssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        var sessions = mock(ReadOnlySessionRegistry.class);
        UUID session = UUID.randomUUID(), version = UUID.randomUUID(), extraction = UUID.randomUUID();
        when(sessions.find(session)).thenReturn(Optional.of(
                new ReadOnlySessionRegistry.SessionView(session, UUID.randomUUID(), null, false)));
        when(registry.sessionVersions(session)).thenReturn(List.of(version));
        when(assets.version(version)).thenReturn(AssetVersion.builder().id(version)
                .extractionId(extraction).filename("report.json.txt").build());
        String start = "{\"Calling Summary\":{},\"Participants\":[\"" + "数😀".repeat(12000);
        String end = "\"],\"lastSample\":42}";
        when(assets.blocks(version)).thenReturn(List.of(
                new AssetBlock(UUID.randomUUID(), 1, end, "end"),
                new AssetBlock(UUID.randomUUID(), 0, start, "start")));
        var service = new AssetConversationRetriever(assets, registry,
                mock(AssetKnowledgeService.class), null, sessions, new AssetStorageProperties());
        var joined = new StringBuilder();
        Integer offset = 0;
        int pages = 0;
        while (offset != null) {
            var page = service.read(session, version, offset, 24000);
            assertThat(page.extractionId()).isEqualTo(extraction);
            assertThat(page.offset()).isEqualTo(offset);
            assertThat(page.coverage()).isEqualTo("partial");
            joined.append(page.text());
            offset = page.nextOffset();
            pages++;
        }
        assertThat(pages).isGreaterThan(1);
        assertThat(joined.toString()).isEqualTo(start + "\n" + end);
        assertThatThrownBy(() -> service.read(session, version, -1, 100)).isInstanceOf(AssetException.class);
        assertThatThrownBy(() -> service.read(session, version, 0, 24001)).isInstanceOf(AssetException.class);
        assertThatThrownBy(() -> service.read(session, version, joined.length() + 1, 100)).isInstanceOf(AssetException.class);
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), version, 0, 100)).isInstanceOf(AssetException.class);
        assertThatThrownBy(() -> service.read(session, UUID.randomUUID(), 0, 100)).isInstanceOf(AssetException.class);
        when(registry.sessionVersions(session)).thenReturn(List.of());
        assertThatThrownBy(() -> service.read(session, version, 0, 100)).isInstanceOf(AssetException.class);
    }

    @Test
    void attachmentListingUsesNewestAttachmentFirstWithoutChangingRegistryOrder() {
        var assets = mock(AssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        var sessions = mock(ReadOnlySessionRegistry.class);
        UUID session = UUID.randomUUID(), oldId = UUID.randomUUID(), newId = UUID.randomUUID();
        when(sessions.find(session)).thenReturn(Optional.of(new ReadOnlySessionRegistry.SessionView(session, UUID.randomUUID(), null, false)));
        var older = AssetVersion.builder().id(oldId).build();
        var newer = AssetVersion.builder().id(newId).build();
        var chronological = new ArrayList<>(List.of(oldId, newId));
        when(registry.sessionVersions(session)).thenReturn(chronological);
        when(assets.version(oldId)).thenReturn(older);
        when(assets.version(newId)).thenReturn(newer);
        var service = new AssetConversationRetriever(assets, registry, mock(AssetKnowledgeService.class), null, sessions, new AssetStorageProperties());
        assertThat(service.files(session)).containsExactly(newer, older);
        assertThat(chronological).containsExactly(oldId, newId);
        assertThatThrownBy(() -> service.files(UUID.randomUUID())).isInstanceOf(AssetException.class);
    }

    @Test
    void indexedPageTenNeverResolvesToPageOneByPrefix() {
        var assets = mock(AssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        var knowledge = mock(AssetKnowledgeService.class);
        var sessions = mock(ReadOnlySessionRegistry.class);
        var indexed = mock(KnowledgeSearchService.class);
        var service =
                new AssetConversationRetriever(
                        assets,
                        registry,
                        knowledge,
                        indexed,
                        sessions,
                        new AssetStorageProperties());
        UUID session = UUID.randomUUID(),
                version = UUID.randomUUID(),
                asset = UUID.randomUUID(),
                extraction = UUID.randomUUID(),
                kb = UUID.randomUUID(),
                doc = UUID.randomUUID(),
                revision = UUID.randomUUID();
        var one = new AssetBlock(UUID.randomUUID(), 0, "First page", "Page 1");
        var ten = new AssetBlock(UUID.randomUUID(), 1, "Deadline is 2030-06-15", "Page 10");
        when(registry.sessionVersions(session)).thenReturn(List.of(version));
        when(assets.version(version))
                .thenReturn(
                        AssetVersion.builder()
                                .id(version)
                                .assetId(asset)
                                .extractionId(extraction)
                                .state("READY")
                                .build());
        when(assets.blocks(version)).thenReturn(List.of(one, ten));
        when(assets.asset(asset))
                .thenReturn(
                        new Asset(
                                asset,
                                AssetStorageService.LOCAL_OWNER,
                                "Contract",
                                "FILE",
                                java.time.Instant.now()));
        when(registry.links(version))
                .thenReturn(List.of(new AssetRegistry.Link(revision, version, kb, doc)));
        when(indexed.searchVersions(eq(kb), eq("deadline"), eq(Set.of(revision))))
                .thenReturn(
                        com.mindrails.backend.common.ServiceResult.success(
                                List.of(
                                        new com.mindrails.backend.knowledge.model
                                                .KnowledgeSearchHit(
                                                kb,
                                                doc,
                                                revision,
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                "Contract",
                                                ten.text(),
                                                ten.location(),
                                                .9,
                                                "unused"))));
        var parameters =
                com.mindrails.backend.agent.model.LlmClient.parameters(
                        com.mindrails.backend.agent.model.Session.builder().id(session).build(),
                        null,
                        "REST");
        var context =
                dev.langchain4j.invocation.InvocationContext.builder()
                        .invocationParameters(parameters)
                        .build();
        var metadata =
                dev.langchain4j.rag.query.Metadata.builder()
                        .chatMessage(dev.langchain4j.data.message.UserMessage.from("deadline"))
                        .invocationContext(context)
                        .build();
        var result = service.retrieve(dev.langchain4j.rag.query.Query.from("deadline", metadata));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().textSegment().metadata().getString("url"))
                .endsWith("/blocks/" + ten.id());
    }

    @Test
    void explicitAttachmentsRequireOwnedSessionAndReadyVersionsBeforeAnyReferenceIsWritten() {
        var assets = mock(AssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        var knowledge = mock(AssetKnowledgeService.class);
        var sessions = mock(ReadOnlySessionRegistry.class);
        var service =
                new AssetConversationRetriever(
                        assets,
                        registry,
                        knowledge,
                        null,
                        sessions,
                        new AssetStorageProperties());
        assertThat(service.manifest(null)).isEmpty();
        UUID agent = UUID.randomUUID(),
                session = UUID.randomUUID(),
                version = UUID.randomUUID(),
                asset = UUID.randomUUID();
        when(sessions.find(session))
                .thenReturn(
                        Optional.of(
                                new ReadOnlySessionRegistry.SessionView(
                                        session, agent, null, false)));
        assertThatThrownBy(() -> service.attach(UUID.randomUUID(), session, List.of(version)))
                .isInstanceOf(AssetException.class);
        verifyNoInteractions(assets, registry);
        when(registry.transaction(any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        when(registry.sessionVersions(session)).thenReturn(List.of());
        var v = AssetVersion.builder().id(version).assetId(asset).state("PROCESSING").build();
        when(assets.version(version)).thenReturn(v);
        assertThatThrownBy(() -> service.attach(agent, session, List.of(version)))
                .isInstanceOf(AssetException.class);
        verify(registry, never()).attach(any(), any());
        v.setState("READY");
        v.setTextChars(100);
        when(registry.links(version)).thenReturn(List.of());
        service.attach(agent, session, List.of(version));
        verify(registry).attach(session, version);
    }
}
