package com.mindrails.backend.asset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.AssetVersion;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.common.ExceptionDiagnostics;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;

class AssetProcessingFailureTest {
    @TempDir Path dir;

    @Test void autoIndexUploadOfAnImageBecomesReadyWithoutDocumentParsing() {
        var assets = mock(DefaultAssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        var knowledge = mock(AssetKnowledgeService.class);
        var parsers = mock(DocumentParserRegistry.class);
        var version = version("screenshot.pdf");
        version.setMediaType("image/png");
        version.setAutoIndex(true);
        var lease = new AssetRegistry.Lease(version, UUID.randomUUID(), 1);
        try (var worker = new AssetWorker(registry, assets, knowledge, new AssetStorageProperties(), parsers)) {
            worker.close();
            worker.process(lease);
        }
        assertThat(version.getState()).isEqualTo("READY");
        assertThat(version.getTextChars()).isZero();
        verify(assets).parsed(version, List.of());
        verify(registry).finish(lease, version);
        verifyNoInteractions(parsers, knowledge);
    }

    @Test void unsupportedTypesPersistTheirExtensionAndSupportedFormats() throws Exception {
        var path = dir.resolve("archive.zip");
        Files.writeString(path, "fixture");
        var assets = mock(DefaultAssetStorageService.class);
        when(assets.materialize(anyString())).thenReturn(path);
        var registry = mock(AssetRegistry.class);
        var version = version("archive.zip");
        var lease = new AssetRegistry.Lease(version, UUID.randomUUID(), 1);
        try (var worker = worker(registry, assets)) {
            worker.close();
            worker.process(lease);
        }
        assertThat(version.getState()).isEqualTo("FAILED");
        assertThat(version.getError()).contains("parsing", ".zip", "Supported types", "html");
        verify(registry).finish(lease, version);
    }

    @Test void unexpectedFailurePreservesCauseAndRedactsStorageCredentials() {
        var assets = mock(DefaultAssetStorageService.class);
        when(assets.materialize(anyString())).thenThrow(new IllegalStateException(
                "Object storage read failed", new IllegalArgumentException(
                "AccessDenied access_key_id=LTAIfakeid access_key_secret=private-value")));
        var registry = mock(AssetRegistry.class);
        var version = version("a.html");
        var lease = new AssetRegistry.Lease(version, UUID.randomUUID(), 1);
        try (var worker = worker(registry, assets)) {
            worker.close();
            worker.process(lease);
        }
        assertThat(version.getError()).contains("reading original", "AccessDenied", "[REDACTED]")
                .doesNotContain("private-value", "LTAIfakeid");
        verify(registry).finish(lease, version);
    }

    @Test void safeDiagnosticsBoundAndRedactNestedExceptions() {
        var cause = new RuntimeException("timeout https://storage.test/key?signature=private-signature",
                new IllegalArgumentException("Authorization=Bearer private-token; api_key=private-key"));
        assertThat(ExceptionDiagnostics.summary(cause)).contains("timeout", "IllegalArgumentException")
                .doesNotContain("private-signature", "private-token", "private-key");
    }

    @Test void providerErrorsCannotEchoAnImageDataUrlIntoDiagnostics() {
        var cause = new IllegalArgumentException("Invalid image: data:image/png;base64,AAAA");
        var failure = ExceptionDiagnostics.withoutImagePayload(new IllegalStateException("Provider error", cause));
        assertThat(failure.getCause().getMessage()).contains("Invalid image", "[REDACTED IMAGE]")
                .doesNotContain("AAAA");
    }

    private AssetVersion version(String filename) {
        return AssetVersion.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .extractionId(UUID.randomUUID()).filename(filename).blobKey("source").build();
    }

    private AssetWorker worker(AssetRegistry registry, DefaultAssetStorageService assets) {
        return new AssetWorker(registry, assets, mock(AssetKnowledgeService.class),
                new AssetStorageProperties(), new DocumentParserRegistry(new KnowledgeProperties()));
    }
}
