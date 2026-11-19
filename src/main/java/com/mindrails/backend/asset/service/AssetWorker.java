package com.mindrails.backend.asset.service;

import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.*;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.common.ExceptionDiagnostics;
import com.mindrails.backend.knowledge.model.DocumentBlock;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** SQL leases recover interrupted parsing. READY extraction snapshots are never overwritten. */
@lombok.extern.slf4j.Slf4j
public final class AssetWorker implements AutoCloseable {
    private int failures;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "asset-parser");
                        t.setDaemon(true);
                        return t;
                    });
    private final AssetRegistry registry;
    private final DefaultAssetStorageService assets;
    private final AssetKnowledgeService knowledge;
    private final AssetStorageProperties properties;
    private final DocumentParserRegistry parsers;

    public AssetWorker(
            AssetRegistry registry,
            DefaultAssetStorageService assets,
            AssetKnowledgeService knowledge,
            AssetStorageProperties properties,
            DocumentParserRegistry parsers) {
        this.registry = registry;
        this.assets = assets;
        this.knowledge = knowledge;
        this.properties = properties;
        this.parsers = parsers;
        executor.scheduleWithFixedDelay(this::tick, 300, 500, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            var lease = registry.claim();
            if (lease != null) process(lease);
            failures = 0;
        } catch (RuntimeException failure) {
            if (++failures == 1 || failures % 120 == 0)
                log.warn("Asset worker could not claim/process a job",
                        ExceptionDiagnostics.forLogging(failure));
        }
    }

    public void process(AssetRegistry.Lease lease) {
        var v = lease.version();
        String phase = "reading original";
        try {
            if (lease.attempts() > 3) throw AssetException.conflict("Parsing retry limit reached");
            if (lease.attempts() > 1) v.setExtractionId(UUID.randomUUID());
            var path = assets.materialize(v.getBlobKey());
            phase = "parsing";
            boolean image = v.getMediaType() != null && v.getMediaType().startsWith("image/");
            var parsed = image
                    ? List.<DocumentBlock>of()
                    : parsers.parse(path, v.getFilename());
            var blocks = new ArrayList<AssetBlock>();
            int chars = 0;
            for (int i = 0; i < parsed.size(); i++) {
                var b = parsed.get(i);
                chars += b.text().length();
                var id =
                        UUID.nameUUIDFromBytes(
                                (v.getExtractionId() + ":" + i).getBytes(StandardCharsets.UTF_8));
                blocks.add(new AssetBlock(id, i, b.text(), b.location()));
            }
            phase = "saving extracted text";
            assets.parsed(v, blocks);
            registry.artifact(v.getId(), DefaultAssetStorageService.blocksKey(v));
            v.setTextChars(chars);
            v.setError(null);
            String ext = DefaultAssetStorageService.extension(v.getFilename());
            phase = "submitting knowledge index";
            if (!image && v.isAutoIndex()
                    && (chars > properties.getDirectTextChars()
                            || List.of("pdf", "docx", "pptx").contains(ext)))
                knowledge.autoIndex(v);
            v.setState("READY");
            registry.finish(lease, v);
        } catch (RuntimeException failure) {
            v.setState("FAILED");
            v.setError("Failed while " + phase + ": " + ExceptionDiagnostics.summary(failure));
            log.warn("Asset processing failed: version={}, extraction={}, attempt={}, phase={}",
                    v.getId(), v.getExtractionId(), lease.attempts(), phase,
                    ExceptionDiagnostics.forLogging(failure));
            registry.finish(lease, v);
        }
    }

    public void close() {
        executor.shutdownNow();
    }
}
