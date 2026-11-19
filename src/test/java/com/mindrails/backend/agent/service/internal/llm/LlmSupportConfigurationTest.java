package com.mindrails.backend.agent.service.internal.llm;

import com.mindrails.backend.asset.service.SessionDeliverables;
import com.mindrails.backend.asset.tool.SessionDeliverableToolAdapter;
import com.mindrails.backend.history.ChatHistory;
import static org.assertj.core.api.Assertions.assertThat;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.model.Task;
import com.mindrails.backend.agent.service.internal.llm.listener.AiServiceListenerFactory;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.mcp.api.toolspec.McpTools;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.skill.SkillFactory;
import com.mindrails.backend.tool.ToolErrorHandler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class LlmSupportConfigurationTest {
    @Test
    void sharedRagReceivesTheOptionalAssetCoordinatorThroughConfiguration() {
        var assets = org.mockito.Mockito.mock(com.mindrails.backend.asset.service.AssetConversationRetriever.class);
        org.mockito.Mockito.when(assets.manifest(org.mockito.ArgumentMatchers.any()))
                .thenReturn("\nAttached inventory marker\n");
        runner()
                .withBean(com.mindrails.backend.asset.service.AssetConversationRetriever.class, () -> assets)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var message = UserMessage.from("Original question");
                    var invocation = dev.langchain4j.invocation.InvocationContext.builder()
                            .invocationParameters(new dev.langchain4j.invocation.InvocationParameters()).build();
                    var request = new dev.langchain4j.rag.AugmentationRequest(message,
                            dev.langchain4j.rag.query.Metadata.builder()
                                    .chatMessage(message).invocationContext(invocation).build());
                    var result = context.getBean(ConversationRag.class).augment(request);
                    assertThat(((UserMessage) result.chatMessage()).singleText())
                            .contains("Original question", "Attached inventory marker");
                });
    }

    @Test
    void sharedCapabilitiesAreAlwaysCreatedWithoutBusinessBackends() {
        runner()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed().hasSingleBean(LlmClientFactory.class)
                                    .hasBean("chatMemoryStoreFactory")
                                    .hasSingleBean(SkillFactory.class)
                                    .hasSingleBean(AiServiceListenerFactory.class)
                                    .hasSingleBean(ToolErrorHandler.class)
                                    .hasSingleBean(SessionDeliverableToolAdapter.class)
                                    .doesNotHaveBean(ToolArgumentsErrorHandler.class)
                                    .doesNotHaveBean(ToolExecutionErrorHandler.class)
                                    .hasSingleBean(RetrievalAugmentor.class)
                                    .hasSingleBean(com.mindrails.backend.knowledge.tool.KnowledgeTools.class)
                                    .doesNotHaveBean(ChatMemoryStore.class);
                            assertThat(context).hasBean("conversationRag")
                                    .hasBean("sessionDeliverableToolAdapter").doesNotHaveBean("toolProviders");
                        });
    }

    @Test
    void conversationRagBeanIsTheRetrievalAugmentorUsedByClients() {
        var requests = new ArrayList<ChatRequest>();
        var queries = new ArrayList<String>();
        runner()
                .withBean(ContentRetriever.class, () -> query -> {
                    queries.add(query.text());
                    return List.of(Content.from(TextSegment.from("Shared retrieval marker")));
                })
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConversationRag.class)
                            .hasSingleBean(RetrievalAugmentor.class);
                    assertThat(context.getBean("conversationRag"))
                            .isSameAs(context.getBean(RetrievalAugmentor.class));
                    var model = new ChatModel() {
                        @Override public ChatResponse doChat(ChatRequest request) {
                            requests.add(request);
                            return ChatResponse.builder().aiMessage(AiMessage.from("[\"reply\"]")).build();
                        }
                    };
                    var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                            .provider(Provider.builder().streaming(false).chatModel(model).build()).build();
                    var result = context.getBean(LlmClientFactory.class).createClientForLoop(session);
                    assertThat(result).isInstanceOf(ServiceResult.Success.class);
                    TestLlmClients.chat(((ServiceResult.Success<LlmClient>) result).value(), session.getMemoryId(), "Original question");
                    assertThat(queries).containsExactly("Original question");
                    assertThat(requests.getFirst().messages().stream()
                            .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                            .map(UserMessage::singleText))
                            .anySatisfy(text -> assertThat(text).contains("Original question", "Shared retrieval marker"));
                });
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void retrievalUsesTheClientOwnerAndRestoresTheCallerScope(boolean taskExecution, boolean failRetrieval) {
        var ownerId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var retrievalOwners = new ArrayList<UUID>();
        runner()
                .withBean(ContentRetriever.class, () -> query -> {
                    retrievalOwners.add(ResourceAccess.userId());
                    if (failRetrieval) {
                        throw new IllegalStateException("Retrieval unavailable");
                    }
                    return List.of();
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var provider = Provider.builder().streaming(false).chatModel(new ChatModel() {
                        @Override
                        public ChatResponse doChat(ChatRequest request) {
                            return ChatResponse.builder().aiMessage(AiMessage.from("[\"reply\"]")).build();
                        }
                    }).build();
                    var factory = context.getBean(LlmClientFactory.class);
                    var memoryId = UUID.randomUUID();
                    var result = taskExecution
                            ? factory.forTask(Task.builder().ownerId(ownerId).provider(provider).build(),
                                    null, memoryId, mock(TaskExecutionObserver.class))
                            : factory.createClientForLoop(Session.builder().id(UUID.randomUUID()).ownerId(ownerId)
                                    .memoryId(memoryId).provider(provider).build());
                    var client = ((ServiceResult.Success<LlmClient>) result).value();

                    try (var ignored = ResourceAccess.as(callerId)) {
                        if (failRetrieval) {
                            assertThrows(IllegalStateException.class, () -> {
                                if (taskExecution) client.chatWithLines(memoryId, "Hello");
                                else TestLlmClients.chat(client, memoryId, "Hello");
                            });
                        } else {
                            if (taskExecution) client.chatWithLines(memoryId, "Hello");
                            else TestLlmClients.chat(client, memoryId, "Hello");
                        }
                        assertThat(ResourceAccess.userId()).isEqualTo(callerId);
                    }
                    assertThat(retrievalOwners).containsExactly(ownerId);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 6})
    void memoryWindowUsesChatMemoryConfiguration(int maxMessages) {
        var runner = runner().withPropertyValues(
                "mindrails.chat-memory.max-messages=" + maxMessages);
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasBean("memoryJdbcTemplate");
            var requests = new ArrayList<ChatRequest>();
            var model = new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    requests.add(request);
                    return ChatResponse.builder().aiMessage(AiMessage.from("[\"reply\"]")).build();
                }
            };
            var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                    .provider(Provider.builder().streaming(false).chatModel(model).build()).build();
            var result = context.getBean(LlmClientFactory.class).createClientForLoop(session);
            assertThat(result).isInstanceOf(ServiceResult.Success.class);
            var client = ((ServiceResult.Success<LlmClient>) result).value();
            TestLlmClients.chat(client, session.getMemoryId(), "First question");
            TestLlmClients.chat(client, session.getMemoryId(), "Second question");
            TestLlmClients.chat(client, session.getMemoryId(), "Third question");
            assertThat(requests.getLast().messages().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .anyMatch(message -> message.singleText().startsWith("First question")))
                    .isEqualTo(maxMessages == 6);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void optionalImageToolsAreInjectedWithTheOtherFactoryDependencies(boolean enabled) {
        var images = mock(com.mindrails.backend.tool.spec.ImageGenerationTools.class);
        org.mockito.Mockito.when(images.instructions()).thenReturn("Image capability marker");
        org.mockito.Mockito.when(images.provideTools(org.mockito.ArgumentMatchers.any()))
                .thenReturn(dev.langchain4j.service.tool.ToolProviderResult.builder().build());
        var runner = runner();
        if (enabled) runner = runner.withBean("imageGenerationTools", com.mindrails.backend.tool.spec.ImageGenerationTools.class, () -> images);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var requests = new ArrayList<ChatRequest>();
            ChatModel model = new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    requests.add(request);
                    return ChatResponse.builder().aiMessage(AiMessage.from("Done")).build();
                }
            };
            var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                    .provider(Provider.builder().streaming(false).chatModel(model).build()).build();
            var result = context.getBean(LlmClientFactory.class).createClientForLoop(session);
            var client = ((ServiceResult.Success<LlmClient>) result).value();
            assertThat(TestLlmClients.chat(client, session.getMemoryId(), "Hello").content()).isEqualTo("Done");
            assertThat(requests.getFirst().messages().toString().contains("Image capability marker")).isEqualTo(enabled);
            if (enabled) org.mockito.Mockito.verify(images).provideTools(org.mockito.ArgumentMatchers.any());
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(LlmClientFactory.class, com.mindrails.backend.memory.TestMemory.Wiring.class)
                .withBean(ChatHistory.class, TestHistory::disabled)
                .withBean(SessionDeliverables.class, TestLlmClients::sessionFiles)
                .withBean(MetricsRecorder.class, TestMetrics::disabled)
                .withBean(McpTools.class, TestLlmClients::noMcpTools)
                .withBean(com.mindrails.backend.knowledge.tool.KnowledgeTools.class, TestLlmClients::disabledKnowledgeTools)
                .withBean("tools", List.class, List::of);
    }
}
