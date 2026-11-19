package com.mindrails.backend.asset.tool;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.asset.service.AssetException;
import com.mindrails.backend.asset.service.SessionDeliverables;
import com.mindrails.backend.tool.FileStoragePaths;
import com.mindrails.backend.tool.ToolResultText;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/** Runs file-producing tools in a Session's output directory and publishes the resulting files. */
public final class SessionDeliverableToolAdapter {
    private static final TypeReference<LinkedHashMap<String, Object>> ARGUMENTS = new TypeReference<>() {};

    private final SessionDeliverables files;
    private final JsonMapper json = new JsonMapper();

    public SessionDeliverableToolAdapter(SessionDeliverables files) {
        this.files = Objects.requireNonNull(files);
    }

    public String instructions(UUID session) {
        return "When the user requests a file or document, create or update it using write/edit. "
                + "Relative write/edit paths and shell commands use this output directory: "
                + files.outputDirectory(session)
                + ". For write/edit, you may also specify other file paths inside " + files.storageRoot() + ". "
                + "Use the session output directory for files you want to deliver to the user. "
                + "When a downloadUrl is returned, use that exact URL in your answer. "
                + "Use absolute returned paths to read files.";
    }

    public AiServiceTool adapt(AiServiceTool tool, UUID session) {
        Operation operation = switch (tool.name()) {
            case "write" -> Operation.WRITE;
            case "edit" -> Operation.EDIT;
            case "bash", "powershell" -> Operation.SHELL;
            default -> throw new IllegalArgumentException("Unsupported deliverable tool: " + tool.name());
        };
        return tool.toBuilder().toolExecutor(new DeliverableExecutor(tool.toolExecutor(), session, operation)).build();
    }

    private enum Operation { WRITE, EDIT, SHELL }

    private final class DeliverableExecutor implements ToolExecutor {
        private final ToolExecutor delegate;
        private final UUID session;
        private final Operation operation;

        private DeliverableExecutor(ToolExecutor delegate, UUID session, Operation operation) {
            this.delegate = delegate;
            this.session = session;
            this.operation = operation;
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            var context = InvocationContext.builder().chatMemoryId(memoryId).build();
            return ToolResultText.from(executeWithContext(request, context).resultContents());
        }

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
            var arguments = json.readValue(request.arguments(), ARGUMENTS);
            try {
                Path output = files.outputDirectory(session);
                return operation == Operation.SHELL
                        ? runShell(request, context, arguments, output)
                        : mutateFile(request, context, arguments, output);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private ToolExecutionResult runShell(ToolExecutionRequest request, InvocationContext context,
                Map<String, Object> arguments, Path output) throws IOException {
            Files.createDirectories(output);
            var result = executeAt(request, context, arguments, output);
            if (result.isError()) return result;

            var execution = json.readTree(ToolResultText.from(result.resultContents()));
            if (execution.path("exitCode").asInt(-1) != 0 || execution.path("timedOut").asBoolean()) return result;
            LlmClient.checkCancelled(context);
            return result(Map.of("execution", execution, "deliverables", files.publishAll(session)));
        }

        private ToolExecutionResult mutateFile(ToolExecutionRequest request, InvocationContext context,
                Map<String, Object> arguments, Path output) throws IOException {
            if (!(arguments.get("path") instanceof String name)) {
                throw AssetException.invalid("Output filename is required");
            }
            Path target = resolve(output, name);
            Files.createDirectories(target.getParent());
            target = resolve(output, name);
            Path temporary = Files.createTempFile(target.getParent(), ".pending-", ".part");
            try {
                if (operation == Operation.EDIT) Files.copy(target, temporary, REPLACE_EXISTING, NOFOLLOW_LINKS);
                var result = executeAt(request, context, arguments, temporary);
                if (result.isError()) return result;
                LlmClient.checkCancelled(context);
                return commit(temporary, name);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private ToolExecutionResult commit(Path temporary, String name) throws IOException {
            Path output = files.outputDirectory(session);
            Path target = resolve(output, name);
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);

            var response = new LinkedHashMap<String, Object>();
            response.put("success", true);
            response.put("path", target.toString());
            if (target.startsWith(output)) response.put("deliverable", files.publish(session, target));
            return result(response);
        }

        private ToolExecutionResult executeAt(ToolExecutionRequest request, InvocationContext context,
                Map<String, Object> arguments, Path path) {
            arguments.put("path", path.toString());
            var actual = ToolExecutionRequest.builder().id(request.id()).name(request.name())
                    .arguments(json.writeValueAsString(arguments)).build();
            return delegate.executeWithContext(actual, context);
        }
    }

    private Path resolve(Path output, String name) {
        return FileStoragePaths.resolveFile(files.storageRoot(), output, name);
    }

    private ToolExecutionResult result(Map<String, Object> response) {
        return ToolExecutionResult.builder().resultText(json.writeValueAsString(response)).build();
    }
}
