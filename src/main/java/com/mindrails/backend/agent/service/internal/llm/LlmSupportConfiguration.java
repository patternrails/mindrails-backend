package com.mindrails.backend.agent.service.internal.llm;

import com.mindrails.backend.agent.service.internal.llm.listener.AiServiceListenerFactory;
import com.mindrails.backend.asset.config.AssetPropertiesConfiguration;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.tool.SessionDeliverableToolAdapter;
import com.mindrails.backend.asset.service.AssetConversationRetriever;
import com.mindrails.backend.asset.service.SessionDeliverables;
import com.mindrails.backend.asset.service.TaskExecutionDeliverables;
import com.mindrails.backend.asset.tool.TaskDeliverableToolAdapter;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.memory.ChatMemoryStoreFactory;
import com.mindrails.backend.persistence.PersistenceConfiguration;
import com.mindrails.backend.skill.SkillCatalog;
import com.mindrails.backend.skill.SkillFactory;
import com.mindrails.backend.tool.ToolErrorHandler;

import dev.langchain4j.rag.content.retriever.ContentRetriever;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.jspecify.annotations.Nullable;

@Configuration(proxyBeanMethods = false)
@Import({
    AssetPropertiesConfiguration.class,
    PersistenceConfiguration.class,
    ChatMemoryStoreFactory.class,
    SkillFactory.class,
    SkillCatalog.class,
    AiServiceListenerFactory.class,
    ToolErrorHandler.class
})
@EnableConfigurationProperties(KnowledgeProperties.class)
public class LlmSupportConfiguration {
    @Bean
    SessionDeliverableToolAdapter sessionDeliverableToolAdapter(SessionDeliverables deliverables) {
        return new SessionDeliverableToolAdapter(deliverables);
    }

    @Bean
    TaskDeliverableToolAdapter taskDeliverableToolAdapter(
            @Nullable TaskExecutionDeliverables deliverables) {
        return new TaskDeliverableToolAdapter(deliverables);
    }

    /**
     * The RetrievalAugmentor injected into LlmClientFactory is this shared ConversationRag bean.
     */
    @Bean
    ConversationRag conversationRag(
            ObjectProvider<ContentRetriever> retrievers,
            @Qualifier(
                            "mindrails.knowledge-com.mindrails.backend.knowledge.config.KnowledgeProperties")
                    KnowledgeProperties properties,
            @Qualifier("chatHistory") ChatHistory chatHistory,
            AssetStorageProperties assets,
            @Nullable AssetConversationRetriever assetConversationRetriever) {
        var sources = retrievers.orderedStream().toList();
        return new ConversationRag(
                sources,
                Math.max(assets.getContextChars(), properties.getCombinedContextChars()),
                chatHistory,
                assetConversationRetriever);
    }
}
