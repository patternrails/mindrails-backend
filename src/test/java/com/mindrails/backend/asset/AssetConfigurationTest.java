package com.mindrails.backend.asset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry;
import com.mindrails.backend.asset.api.web.AssetStorageController;
import com.mindrails.backend.asset.config.AssetPropertiesConfiguration;
import com.mindrails.backend.asset.config.AssetStorageConfiguration;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.registry.MysqlAssetRegistry;
import com.mindrails.backend.asset.service.AssetQueryService;
import com.mindrails.backend.asset.service.AssetStorageService;
import com.mindrails.backend.asset.store.AssetDocumentStorage;
import com.mindrails.backend.embedding.EmbeddingProviderRegistry;
import com.mindrails.backend.identity.AvatarStorage;
import com.mindrails.backend.knowledge.config.KnowledgeConfiguration;
import com.mindrails.backend.knowledge.service.KnowledgeBindingService;
import com.mindrails.backend.knowledge.store.DocumentStorage;
import com.mindrails.backend.metrics.MetricsRecorder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

class AssetConfigurationTest {
    @TempDir Path directory;

    @Test
    void fileStorageUsesOneRootForAssetsKnowledgeAndAvatars() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        Path storage = directory.resolve("custom root");
        Path ignored = directory.resolve("ignored");
        String knowledgeKey = UUID.randomUUID() + "/source.txt";
        Path knowledgeFile = storage.resolve("knowledge").resolve(knowledgeKey);
        Files.createDirectories(knowledgeFile.getParent());
        Files.writeString(knowledgeFile, "Existing knowledge");
        String avatarName = UUID.randomUUID() + ".png";
        Path avatarFile = storage.resolve("avatars").resolve(avatarName);
        Files.createDirectories(avatarFile.getParent());
        byte[] avatar = {1, 2, 3};
        Files.write(avatarFile, avatar);
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("storage-test-systemEnvironment", Map.of(
                                    "MINDRAILS_FILE_STORAGE_DIRECTORY", storage.toString(),
                                    "MINDRAILS_KNOWLEDGE_DIRECTORY", ignored.toString(),
                                    "MINDRAILS_ASSET_STORAGE_DIRECTORY", ignored.toString(),
                                    "MINDRAILS_ASSET_STORAGE_CACHE_DIRECTORY", ignored.toString(),
                                    "MINDRAILS_AVATAR_DIRECTORY", ignored.toString())));
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })

                .withUserConfiguration(AssetStorageConfiguration.class, AssetPropertiesConfiguration.class,
                        KnowledgeConfiguration.class, AssetStorageController.class, AvatarStorage.class)
                .withBean("persistenceJdbcTemplate", JdbcTemplate.class, () -> jdbc)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withBean(ReadOnlySessionRegistry.class, () -> mock(ReadOnlySessionRegistry.class))
                .withBean(KnowledgeBindingService.class, () -> mock(KnowledgeBindingService.class))
                .withBean(MetricsRecorder.class, () -> mock(MetricsRecorder.class))
                .withBean(EmbeddingProviderRegistry.class, () -> mock(EmbeddingProviderRegistry.class))
                .withPropertyValues(
                        "mindrails.asset-storage.directory=" + ignored,
                        "mindrails.asset-storage.cache-directory=" + ignored,
                        "mindrails.knowledge.directory=" + ignored,
                        "mindrails.identity.avatar-directory=" + ignored)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var controller = context.getBean(AssetStorageController.class);
                    assertThat(controller.config()).containsEntry("enabled", true);
                    assertThat(context).hasSingleBean(AssetStorageService.class)
                            .hasSingleBean(AssetQueryService.class);
                    assertThat(context.getBean(AssetRegistry.class)).isInstanceOf(MysqlAssetRegistry.class);
                    assertThat(controller.list(0, 20)).isEmpty();
                    var documents = context.getBean(DocumentStorage.class);
                    assertThat(documents).isInstanceOf(AssetDocumentStorage.class);
                    assertThat(documents.path(knowledgeKey)).isEqualTo(knowledgeFile);
                    assertThat(context.getBean(AvatarStorage.class).read(avatarName)).isEqualTo(avatar);
                    assertThat(storage.resolve("assets")).isDirectory();
                    assertThat(storage.resolve("asset-cache")).isDirectory();
                    assertThat(ignored).doesNotExist();
                });
    }
}
