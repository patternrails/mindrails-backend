package com.mindrails.backend.agent.service.internal.llm;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.model.Task;
import com.mindrails.backend.agent.service.internal.llm.listener.AiServiceListenerFactory;
import com.mindrails.backend.asset.service.AssetConversationRetriever;
import com.mindrails.backend.asset.tool.SessionDeliverableToolAdapter;
import com.mindrails.backend.asset.tool.TaskDeliverableToolAdapter;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.identity.OwnedTools;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.knowledge.tool.KnowledgeTools;
import com.mindrails.backend.mcp.api.toolspec.McpTools;
import com.mindrails.backend.memory.ChatMemoryStoreFactory;
import com.mindrails.backend.memory.ToolCallRecovery;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.skill.SkillCatalog;
import com.mindrails.backend.tool.ToolErrorHandler;
import com.mindrails.backend.tool.spec.ImageGenerationTools;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.observability.api.listener.AiServiceRequestIssuedListener;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.observability.api.listener.AiServiceStartedListener;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServiceContext;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.skills.Skills;

import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Builds fresh clients; Session client lifetime belongs to its run loop. */
@Component
@Import(LlmSupportConfiguration.class)
@Slf4j
public class LlmClientFactory {
    private static final Set<String> DELIVERABLE_TOOLS =
            Set.of("write", "edit", "bash", "powershell");
    private final List<AiServiceTool> tools;
    private final ChatMemoryStoreFactory chatMemoryStoreFactory;
    private final SkillCatalog skillCatalog;
    private final AiServiceListenerFactory aiServiceListenerFactory;
    private final McpTools mcpTools;
    private final KnowledgeTools knowledgeTools;
    private final RetrievalAugmentor rag;
    private final ToolErrorHandler toolErrorHandler;
    private final SessionDeliverableToolAdapter sessionDeliverableToolAdapter;
    private final TaskDeliverableToolAdapter taskDeliverableToolAdapter;
    private final @Nullable ImageGenerationTools imageGenerationTools;
    private final @Nullable AssetConversationRetriever assets;

    public LlmClientFactory(
            @Qualifier("tools") List<AiServiceTool> tools,
            @Qualifier("chatMemoryStoreFactory") ChatMemoryStoreFactory chatMemoryStoreFactory,
            SkillCatalog skillCatalog,
            @Qualifier("aiServiceListenerFactory")
                    AiServiceListenerFactory aiServiceListenerFactory,
            @Qualifier("mcpTools") McpTools mcpTools,
            @Qualifier("knowledgeTools") KnowledgeTools knowledgeTools,
            @Qualifier("conversationRag") RetrievalAugmentor rag,
            @Qualifier("toolErrorHandler") ToolErrorHandler toolErrorHandler,
            @Qualifier("sessionDeliverableToolAdapter")
                    SessionDeliverableToolAdapter sessionDeliverableToolAdapter,
            @Qualifier("taskDeliverableToolAdapter")
                    TaskDeliverableToolAdapter taskDeliverableToolAdapter,
            @Qualifier("imageGenerationTools") @Nullable ImageGenerationTools imageGenerationTools,
            @Nullable AssetConversationRetriever assets) {
        this.tools = tools;
        this.chatMemoryStoreFactory = chatMemoryStoreFactory;
        this.skillCatalog = skillCatalog;
        this.aiServiceListenerFactory = aiServiceListenerFactory;
        this.mcpTools = mcpTools;
        this.knowledgeTools = knowledgeTools;
        this.rag = rag;
        this.toolErrorHandler = toolErrorHandler;
        this.sessionDeliverableToolAdapter = sessionDeliverableToolAdapter;
        this.taskDeliverableToolAdapter = taskDeliverableToolAdapter;
        this.imageGenerationTools = imageGenerationTools;
        this.assets = assets;
    }

    public ServiceResult<LlmClient> createClientForLoop(Session session) {
        return validate(session).map(ignored -> buildClient(session));
    }

    /** Each Task execution gets a fresh client and in-memory tool loop. */
    public ServiceResult<LlmClient> forTask(
            Task task, String agentSystemMessage, UUID executionId, TaskExecutionObserver observer) {
        if (!hasConfiguredModel(task.getProvider())) {
            return ServiceResult.failure(
                    ErrorCode.NOT_IMPLEMENTED, "Task provider has no configured model");
        }
        var ownerId = ResourceAccess.owner(task.getOwnerId());
        var skills = skillCatalog.forTask(ownerId, task.getSkillIds());
        var builder = newBuilder();
        builder.chatMemory(chatMemoryStoreFactory.buildTransientChatMemory(executionId));
        configureTaskTools(builder, task, executionId);
        configureToolProviders(builder, task.getOwnerId(),
                () -> skillCatalog.forTask(ownerId, task.getSkillIds()));
        configureRetrievalAugmentor(builder, task.getOwnerId());
        var systemMessage = taskSystemMessage(agentSystemMessage, executionId, skills);
        builder.systemMessageTransformer(ignored -> systemMessage);
        configureCompatibleToolResults(builder, task.getProvider());
        configureModel(builder, task.getProvider());
        configureListeners(builder, observer);
        return ServiceResult.success(builder.build());
    }

    private ServiceResult<Void> validate(Session session) {
        if (session.getMemoryId() == null) {
            return ServiceResult.failure(
                    ErrorCode.INVALID_ARGUMENT, "Session memory ID is required");
        }
        var provider = session.getProvider();
        if (!hasConfiguredModel(provider)) {
            return ServiceResult.failure(ErrorCode.NOT_IMPLEMENTED,
                    Boolean.TRUE.equals(provider.getStreaming())
                            ? "Provider has no streaming chat model"
                            : "Provider has no chat model");
        }
        return ServiceResult.success();
    }

    private boolean hasConfiguredModel(Provider provider) {
        return Boolean.TRUE.equals(provider.getStreaming())
                ? provider.getStreamingChatModel() != null
                : provider.getChatModel() != null;
    }

    private AiServices<LlmClient> newBuilder() {
        var context = AiServiceContext.create(LlmClient.class);
        // A cancelled invocation must stop before the next model request is sent.
        context.eventListenerRegistrar.shouldThrowExceptionOnEventError(true);
        return AiServices.builder(context);
    }

    private LlmClient buildClient(Session session) {
        var ownerId = session.getOwnerId();
        Supplier<Skills> skills = () -> skillCatalog.forSession(ResourceAccess.owner(ownerId));
        var builder = newBuilder();
        var memory = chatMemoryStoreFactory.buildChatMemory(session.getMemoryId());
        builder.chatMemory(memory);
        // Recover before LC4J copies history, so the first model request sees a complete tool exchange.
        builder.registerListeners((AiServiceStartedListener) event -> ToolCallRecovery.recover(memory));
        configureSessionTools(builder, session);
        configureToolProviders(builder, ownerId, skills);
        configureListeners(builder, null);
        configureRetrievalAugmentor(builder, session.getOwnerId());
        // Instructions are literal text, including {{variables}}, and skills stay current on every
        // turn.
        builder.systemMessageTransformer(ignored -> systemMessage(session, skills.get()));
        var provider = session.getProvider();
        configureCompatibleToolResults(builder, provider);
        if (provider.getChatModel() instanceof QwenChatModel
                || provider.getStreamingChatModel() instanceof QwenStreamingChatModel) {
            builder.chatRequestTransformer((request, memoryId) -> {
                boolean hasImages = request.messages().stream().anyMatch(message ->
                        message instanceof UserMessage user && user.contents().stream().anyMatch(ImageContent.class::isInstance));
                if (!hasImages) return request;
                // DashScope's text endpoint silently drops image parts.
                return request.toBuilder().parameters(QwenChatRequestParameters.builder()
                        .overrideWith(request.parameters()).isMultimodalModel(true).build()).build();
            });
        }
        if (!Boolean.TRUE.equals(provider.getStreaming())) {
            builder.streamingChatModel(new dev.langchain4j.model.chat.StreamingChatModel() {
                @Override
                public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                        dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                    try { handler.onCompleteResponse(provider.getChatModel().chat(request)); }
                    catch (RuntimeException error) { handler.onError(error); }
                }
            });
        } else {
            configureModel(builder, provider);
        }

        var client = builder.build();
        log.info(
                "Created LlmClient: sessionId={}, streaming={}",
                session.getId(),
                Boolean.TRUE.equals(session.getProvider().getStreaming()));
        return client;
    }

    private void configureSessionTools(AiServices<LlmClient> builder, Session session) {
        var sessionTools = tools.stream().map(tool -> adaptSessionTool(tool, session.getId())).toList();
        configureTools(builder, session.getOwnerId(), sessionTools);
    }

    private AiServiceTool adaptSessionTool(AiServiceTool tool, UUID sessionId) {
        if (DELIVERABLE_TOOLS.contains(tool.name())) {
            return sessionDeliverableToolAdapter.adapt(tool, sessionId);
        }
        return tool;
    }

    private void configureTaskTools(AiServices<LlmClient> builder, Task task, UUID executionId) {
        var deliverablesAvailable = taskDeliverableToolAdapter.available();
        var taskTools = tools.stream()
                .filter(tool -> !com.mindrails.backend.agent.tool.SessionCommunicationTool.NAME.equals(tool.name()))
                .filter(tool -> !com.mindrails.backend.agent.tool.SessionListTool.NAME.equals(tool.name()))
                .map(tool -> deliverablesAvailable && DELIVERABLE_TOOLS.contains(tool.name())
                        ? taskDeliverableToolAdapter.adapt(tool, executionId)
                        : tool)
                .toList();
        configureTools(builder, task.getOwnerId(), taskTools);
    }

    private void configureTools(AiServices<LlmClient> builder, UUID ownerId, List<AiServiceTool> selectedTools) {
        builder.toolArgumentsErrorHandler(toolErrorHandler.argumentsErrorHandler())
                .toolExecutionErrorHandler(toolErrorHandler.executionErrorHandler())
                .tools(selectedTools.stream().map(tool -> OwnedTools.tool(ownerId, tool)).toList());
    }

    private void configureToolProviders(AiServices<LlmClient> builder, UUID ownerId, Supplier<Skills> skills) {
        builder.toolProviders(List.of(
                OwnedTools.provider(ownerId, () -> mcpTools),
                OwnedTools.provider(ownerId, () -> knowledgeTools),
                OwnedTools.provider(ownerId, () -> imageGenerationTools),
                OwnedTools.provider(ownerId, () -> {
                    var current = skills.get();
                    return current == null ? null : current.toolProvider();
                })));
    }

    private void configureRetrievalAugmentor(AiServices<LlmClient> builder, UUID ownerId) {
        builder.retrievalAugmentor(request -> {
            try (var ignored = ResourceAccess.as(ResourceAccess.owner(ownerId))) {
                var result = rag.augment(request);
                if (assets == null || !(result.chatMessage() instanceof UserMessage user)) return result;
                var metadata = request.metadata();
                var images = assets.imageContents(metadata.invocationParameters(), metadata.invocationContext());
                if (images.isEmpty()) return result;
                // The augmented message goes to the model; memory keeps the original text.
                var contents = new ArrayList<Content>(user.contents());
                contents.addAll(images);
                return new AugmentationResult(UserMessage.from(user.name(), contents), result.contents());
            }
        }).storeRetrievedContentInChatMemory(false);
    }

    private String systemMessage(Session session, Skills skills) {
        var instructions = new ArrayList<String>();
        if (imageGenerationTools != null) {
            addInstructions(instructions, imageGenerationTools.instructions());
        }
        boolean hasFileTools = tools.stream().anyMatch(tool -> DELIVERABLE_TOOLS.contains(tool.name()));
        if (hasFileTools) addInstructions(instructions, sessionDeliverableToolAdapter.instructions(session.getId()));
        addInstructions(instructions, session.getSystemMessage());
        if (skills != null) {
            instructions.add(
                    "You have access to the following skills:\n"
                            + skills.formatAvailableSkills()
                            + "\n"
                            + "When the user's request relates to one of these skills, activate it"
                            + " first using the activate_skill tool before proceeding.");
        }
        if (hasFileTools) instructions.add(skillInstallationInstructions(session));
        return instructions.isEmpty() ? null : String.join("\n\n", instructions);
    }

    private String skillInstallationInstructions(Session session) {
        var directory = skillCatalog.directory(ResourceAccess.owner(session.getOwnerId()));
        return """
                The user's MindRails skill directory is %s.
                Install requested skills for this MindRails account by default. The target directory above
                already specifies the account and scope, so proceed directly with installation.

                Download or unpack the source into a temporary directory outside the session output folder.
                Find each directory containing a SKILL.md with name and description front matter, including
                skills nested in category folders. Use the shell to create the user's skill directory if
                needed and copy each complete skill directory and its supporting files there. The final
                layout for each skill is %s/<skill-name>/SKILL.md. Skip agent-specific installers.

                Preserve existing skills unless the user asks to replace them. Verify SKILL.md at each
                destination, then report which skills were installed, skipped, or failed. Use write/edit
                with absolute paths to author or edit skills. New and updated skills are discovered
                on the next turn.
                """.formatted(directory, directory).strip();
    }

    private String taskSystemMessage(String agentSystemMessage, UUID executionId, Skills skills) {
        var instructions = new ArrayList<String>();
        addInstructions(instructions, taskDeliverableToolAdapter.instructions(executionId));
        addInstructions(instructions, agentSystemMessage);
        if (skills != null) {
            instructions.add(
                    "You have access to the following skills:\n"
                            + skills.formatAvailableSkills()
                            + "\nActivate a relevant skill before proceeding.");
        }
        return instructions.isEmpty() ? null : String.join("\n\n", instructions);
    }

    private void addInstructions(List<String> instructions, @Nullable String text) {
        if (text != null && !text.isBlank()) {
            instructions.add(text);
        }
    }

    private void configureCompatibleToolResults(AiServices<LlmClient> builder, Provider provider) {
        if (!(provider.getChatModel() instanceof OpenAiChatModel)
                && !(provider.getStreamingChatModel() instanceof OpenAiStreamingChatModel)) return;
        // Chat Completions requires one text block per tool result, including results restored from memory.
        builder.chatRequestTransformer(request -> {
            var messages = request.messages().stream().map(message -> {
                if (!(message instanceof ToolExecutionResultMessage result) || result.hasSingleText()) {
                    return message;
                }
                var text = result.contents().stream()
                        .map(content -> content instanceof TextContent part ? part.text()
                                : "[Non-text tool output omitted. This tool message can only send text to the model.]")
                        .collect(Collectors.joining("\n"));
                return result.toBuilder().contents(List.of(TextContent.from(text))).build();
            }).toList();
            return messages.equals(request.messages()) ? request : request.toBuilder().messages(messages).build();
        });
    }

    private void configureModel(AiServices<LlmClient> builder, Provider provider) {
        if (Boolean.TRUE.equals(provider.getStreaming())) {
            builder.streamingChatModel(provider.getStreamingChatModel());
        } else {
            builder.chatModel(provider.getChatModel());
        }
    }

    private void configureListeners(AiServices<LlmClient> builder, @Nullable TaskExecutionObserver observer) {
        var recordListeners = aiServiceListenerFactory.recordListeners();
        builder.registerListeners(aiServiceListenerFactory.defaultListeners())
                .registerListeners(recordListeners.listeners());
        builder.afterToolExecution(event -> {
            recordListeners.toolExecutionListener().accept(event);
            if (observer != null) {
                observer.toolCompleted(event);
            }
        });
        if (observer != null) {
            builder.registerListeners(
                    (AiServiceRequestIssuedListener) event -> observer.modelRequested(event.request().messages()),
                    (AiServiceResponseReceivedListener) event -> observer.modelResponded(event.response()));
            builder.beforeToolExecution(event -> observer.toolStarted(event.request()));
        }
    }

}
