package com.mindrails.backend.image;

import static com.mindrails.backend.common.ServiceResult.ErrorCode.*;

import com.mindrails.backend.provider.ProviderConnectionTest;
import com.mindrails.backend.provider.ProviderSecrets;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

@Service
public final class ImageProviderService {
    private final @Nullable JdbcTemplate jdbc;
    private final ProviderSecrets secrets;
    private final ImageGenerationClient client;

    @Autowired
    public ImageProviderService(@Nullable @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc,
            Environment env, ImageGenerationClient client) {
        this.jdbc = jdbc; this.secrets = new ProviderSecrets(env); this.client = client;
    }

    private JdbcTemplate database() {
        if (jdbc == null) throw new ImageGenerationException(NOT_IMPLEMENTED, "Image providers require MySQL storage");
        return jdbc;
    }

    private ImageProvider.Config row(ResultSet r, int index) throws SQLException {
        String encrypted = r.getString("api_key");
        var testedAt = r.getTimestamp("tested_at");
        var info = new ImageProvider.Info(UUID.fromString(r.getString("id")), r.getString("name"),
                r.getString("provider_name"), r.getString("url"), r.getString("model"), encrypted != null,
                r.getBoolean("is_default"), r.getBoolean("enabled"), r.getString("size"),
                r.getBoolean("prompt_extend"), r.getInt("timeout_millis"), r.getBoolean("tested"),
                r.getBoolean("test_success"), testedAt == null ? null : testedAt.toInstant(),
                r.getObject("test_latency_ms") == null ? null : r.getLong("test_latency_ms"),
                r.getString("test_message"), r.getLong("version"));
        return new ImageProvider.Config(info, secrets.decrypt(encrypted));
    }

    public List<ImageProvider.Info> list(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw ImageGenerationException.invalid("Invalid pagination");
        if (jdbc == null) return List.of();
        return jdbc.query("SELECT * FROM image_generation_providers ORDER BY is_default DESC,name,id LIMIT ? OFFSET ?",
                this::row, size, page * size).stream().map(ImageProvider.Config::info).toList();
    }

    public List<ImageProvider.Info> available() {
        if (jdbc == null) return List.of();
        return jdbc.query("SELECT * FROM image_generation_providers WHERE enabled=TRUE AND tested=TRUE AND test_success=TRUE ORDER BY is_default DESC,name,id",
                this::row).stream().map(ImageProvider.Config::info).filter(ImageProvider.Info::available).toList();
    }

    ImageProvider.Config config(UUID id) {
        return database().query("SELECT * FROM image_generation_providers WHERE id=?", this::row, id.toString())
                .stream().findFirst().orElseThrow(() -> new ImageGenerationException(NOT_FOUND, "Image provider not found"));
    }

    public ImageProvider.Info get(UUID id) { return config(id).info(); }

    /** Saving invalidates old test status immediately; network IO runs outside the transaction. */
    public ImageProvider.Info save(@Nullable UUID id, ImageProvider.Input input) {
        UUID actual = persist(id, input);
        test(actual);
        return get(actual);
    }

    public ProviderConnectionTest.Result probe(ImageProvider.Input input) {
        validate(input);
        ProviderSecrets.validate(input.apiKey());
        var info = new ImageProvider.Info(UUID.randomUUID(), input.name(), input.providerName(), input.url(),
                input.model(), true, true, true, "1024x1024", true,
                input.timeoutMillis() == null ? 180000 : input.timeoutMillis(), false, false, null, null, null, 0);
        return ProviderConnectionTest.run(() -> client.checkModel(new ImageProvider.Config(info, input.apiKey())));
    }

    public ImageProvider.Info createTested(ImageProvider.Input input, long latencyMs) {
        UUID id = persist(null, input);
        var info = get(id);
        recordTest(new ImageProvider.TestResult(UUID.randomUUID(), id, info.version(), true, latencyMs,
                "Model lookup passed. Image generation was not run.", Instant.now()));
        return get(id);
    }

    private UUID persist(@Nullable UUID id, ImageProvider.Input input) {
        var db = database();
        validate(input);
        UUID actual = id == null ? UUID.randomUUID() : id;
        try {
            new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())))
                    .executeWithoutResult(status -> {
                        db.queryForList("SELECT id FROM image_generation_provider_catalog WHERE id=1 FOR UPDATE", Integer.class);
                        var old = id == null ? null : config(id);
                        String key = input.apiKey() == null || input.apiKey().isBlank()
                                ? old == null ? null : old.apiKey() : input.apiKey();
                        try { ProviderSecrets.validate(key); }
                        catch (IllegalArgumentException invalid) { throw ImageGenerationException.invalid(invalid.getMessage()); }
                        boolean makeDefault = Boolean.TRUE.equals(input.defaultProvider());
                        if (makeDefault) db.update("UPDATE image_generation_providers SET is_default=FALSE WHERE is_default=TRUE");
                        Object[] values = {input.name().strip(), input.providerName(), input.url().strip(),
                                secrets.encrypt(key), input.model().strip(), makeDefault,
                                input.enabled() == null || input.enabled(), input.size() == null ? "1024x1024" : input.size(),
                                input.promptExtend() == null || input.promptExtend(),
                                input.timeoutMillis() == null ? 180000 : input.timeoutMillis()};
                        if (old == null) {
                            var args = new ArrayList<Object>(); args.add(actual.toString()); args.addAll(Arrays.asList(values));
                            db.update("INSERT INTO image_generation_providers (id,name,provider_name,url,api_key,model,is_default,enabled,size,prompt_extend,timeout_millis) VALUES (?,?,?,?,?,?,?,?,?,?,?)", args.toArray());
                        } else {
                            var args = new ArrayList<>(Arrays.asList(values)); args.add(actual.toString());
                            db.update("UPDATE image_generation_providers SET name=?,provider_name=?,url=?,api_key=?,model=?,is_default=?,enabled=?,size=?,prompt_extend=?,timeout_millis=?,version=version+1,tested=FALSE,test_success=FALSE,tested_at=NULL,test_latency_ms=NULL,test_message=NULL WHERE id=?", args.toArray());
                        }
                    });
        } catch (DuplicateKeyException conflict) {
            throw new ImageGenerationException(CONFLICT, "An image provider with this name already exists");
        }
        return actual;
    }

    public ImageProvider.TestResult test(UUID id) {
        return test(id, false);
    }

    public ImageProvider.TestResult test(UUID id, boolean generateImage) {
        var snapshot = config(id);
        long start = System.nanoTime();
        boolean connected = false;
        String message = generateImage ? "Image returned and validated successfully"
                : "Model lookup passed. Image generation was not run.";
        try {
            if (generateImage) client.generate(snapshot, "A small blue circle on a white background.", null);
            else client.checkModel(snapshot);
            connected = true;
        } catch (CancellationException cancelled) {
            message = "Image provider test interrupted";
        } catch (RuntimeException error) {
            message = error instanceof ImageGenerationException ? error.getMessage()
                    : "Image provider test failed. Check the endpoint, API key and model.";
        }
        var result = new ImageProvider.TestResult(UUID.randomUUID(), id, snapshot.info().version(), connected,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), message, Instant.now());
        recordTest(result);
        return result;
    }

    private void recordTest(ImageProvider.TestResult result) {
        UUID id = result.providerId();
        var db = database();
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())))
                .executeWithoutResult(status -> {
                    // The FK prevents a deleted provider being resurrected by a late test.
                    if (db.queryForList("SELECT id FROM image_generation_providers WHERE id=? FOR UPDATE", String.class, id.toString()).isEmpty())
                        throw new ImageGenerationException(NOT_FOUND, "Image provider was deleted during testing");
                    db.update("INSERT INTO image_generation_provider_tests (id,provider_id,provider_version,connected,latency_ms,message,tested_at) VALUES (?,?,?,?,?,?,?)",
                            result.id().toString(), id.toString(), result.version(), result.connected(), result.latencyMs(), result.message(), Timestamp.from(result.testedAt()));
                    db.update("UPDATE image_generation_providers SET tested=TRUE,test_success=?,tested_at=?,test_latency_ms=?,test_message=? WHERE id=? AND version=? AND (tested_at IS NULL OR tested_at<=?)",
                            result.connected(), Timestamp.from(result.testedAt()), result.latencyMs(), result.message(), id.toString(), result.version(), Timestamp.from(result.testedAt()));
                });
    }

    public List<ImageProvider.TestResult> tests(UUID id) {
        get(id);
        return database().query("SELECT * FROM image_generation_provider_tests WHERE provider_id=? ORDER BY tested_at DESC,id LIMIT 20",
                (r, n) -> new ImageProvider.TestResult(UUID.fromString(r.getString("id")), id,
                        r.getLong("provider_version"), r.getBoolean("connected"), r.getLong("latency_ms"),
                        r.getString("message"), r.getTimestamp("tested_at").toInstant()), id.toString());
    }

    public void delete(UUID id) {
        if (database().update("DELETE FROM image_generation_providers WHERE id=?", id.toString()) == 0)
            throw new ImageGenerationException(NOT_FOUND, "Image provider not found");
    }

    public ImageProvider.Config select(@Nullable String selection) {
        var available = available();
        if (selection == null || selection.isBlank()) {
            var selected = available.stream().filter(ImageProvider.Info::defaultProvider).findFirst();
            if (selected.isPresent()) return config(selected.get().id());
            throw new ImageGenerationException(UNAVAILABLE, "No tested default image provider. Ask the user to select a tested available provider: " + choices());
        }
        var exact = available.stream().filter(p -> p.id().toString().equals(selection) || p.name().equalsIgnoreCase(selection)).toList();
        if (exact.size() == 1) return config(exact.getFirst().id());
        var vendor = available.stream().filter(p -> p.providerName().equalsIgnoreCase(selection)).toList();
        if (vendor.size() == 1) return config(vendor.getFirst().id());
        throw new ImageGenerationException(INVALID_ARGUMENT, "The requested image provider is unavailable or ambiguous. Ask the user which provider to use; do not silently substitute. Tested available providers: " + choices());
    }

    public String choices() {
        return new tools.jackson.databind.json.JsonMapper().writeValueAsString(available().stream()
                .map(p -> Map.of("id", p.id(), "name", p.name(), "vendor", p.providerName(), "model", p.model(), "default", p.defaultProvider(), "tested", true)).toList());
    }

    private static void validate(ImageProvider.Input input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 100
                || input.name().chars().anyMatch(Character::isISOControl)
                || !Set.of("qwen", "openai").contains(Objects.toString(input.providerName(), ""))
                || input.model() == null || input.model().isBlank() || input.model().length() > 255)
            throw ImageGenerationException.invalid("Name, qwen/openai provider name and model are required");
        try {
            URI uri = URI.create(Objects.requireNonNull(input.url()).strip());
            if (input.url().length() > 2048 || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw ImageGenerationException.invalid("A full HTTPS generations URL is required (HTTP allowed only for local testing)"); }
        if (input.size() != null && !Set.of("1024x1024", "1024x1536", "1536x1024", "2048x2048").contains(input.size()))
            throw ImageGenerationException.invalid("Unsupported image size");
        if (input.timeoutMillis() != null && (input.timeoutMillis() < 1000 || input.timeoutMillis() > 300000))
            throw ImageGenerationException.invalid("Timeout must be between 1000 and 300000 milliseconds");
    }
}
