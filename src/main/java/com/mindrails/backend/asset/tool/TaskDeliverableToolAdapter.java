package com.mindrails.backend.asset.tool;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.asset.service.AssetException;
import com.mindrails.backend.asset.service.TaskExecutionDeliverables;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Adapts selected file-producing tools to publish files managed by {@link TaskExecutionDeliverables}. */
public final class TaskDeliverableToolAdapter {
    private final @Nullable TaskExecutionDeliverables deliverables;
    private final JsonMapper json = new JsonMapper();

    public TaskDeliverableToolAdapter(@Nullable TaskExecutionDeliverables deliverables) {
        this.deliverables = deliverables;
    }

    public boolean available() {
        return deliverables != null;
    }

    public String instructions(UUID execution) {
        if (!available()) return "";
        return "When the user requests a file or document, create or update it using write/edit. "
                + "All output files belong in this execution's local Asset Storage folder: "
                + deliverables.outputDirectory(execution)
                + ". Use relative filenames such as report.md or reports/budget.csv; never write into another execution or /tmp. "
                + "Write/edit return a deliverable with a downloadUrl. Include a Markdown download link using that exact URL "
                + "in your answer. Do not invent download URLs or use file:// links. Use absolute returned paths to read files. "
                + "Shell tools must use this folder as their working directory when producing files.";
    }

    /**
     * Adapts one tool selected by {@code LlmClientFactory} for the supplied Task execution's deliverables.
     * The caller selects only {@code write}, {@code edit}, {@code bash} and {@code powershell};
     * this method does not discover or filter a tool collection.
     *
     * <p>The returned copy keeps the tool's name, description and parameter schema. Its executor
     * binds the server-resolved Task execution ID and delegates to the original executor. Write/edit
     * operate on a temporary file, commit after success and cancellation/Task execution checks, and
     * return {@code success}, {@code path} and a {@code deliverable} with its download URL.
     * Shell tools use the Task execution output directory and, after successful non-timed-out execution,
     * return the original {@code execution} plus discovered {@code deliverables}. Failed tool
     * results pass through without publishing files.
     *
     * @param tool one of the four supported tools, already selected during client assembly
     * @param execution the Task execution that owns the output files
     * @return an adapted copy, or the original tool when local deliverables are unavailable;
     *         the shared original tool is never modified
     * @throws IllegalArgumentException if the supplied tool is unsupported
     */
    public AiServiceTool adapt(AiServiceTool tool, UUID execution) {
        boolean mutation = switch (tool.name()) {
            case "write", "edit" -> true;
            case "bash", "powershell" -> false;
            default -> throw new IllegalArgumentException("Unsupported deliverable tool: " + tool.name());
        };
        var files = deliverables;
        if (files == null) return tool;
        ToolExecutor delegate = tool.toolExecutor();
        return tool.toBuilder().toolExecutor(new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                return com.mindrails.backend.tool.ToolResultText.from(executeWithContext(request,
                        InvocationContext.builder().chatMemoryId(memoryId).build()).resultContents());
            }

            @Override
            public ToolExecutionResult executeWithContext(
                    ToolExecutionRequest request, InvocationContext context) {
                Path temporary = null;
                try {
                    var args = new LinkedHashMap<String, Object>(json.readValue(
                            request.arguments(), new TypeReference<Map<String, Object>>() {}));
                    if (!mutation) {
                        Path root = files.outputDirectory(execution);
                        Files.createDirectories(root);
                        args.put("path", root.toString());
                        var actual = ToolExecutionRequest.builder()
                                .id(request.id()).name(request.name())
                                .arguments(json.writeValueAsString(args)).build();
                        var result = delegate.executeWithContext(actual, context);
                        if (result.isError()) return result;
                        var response = json.readTree(com.mindrails.backend.tool.ToolResultText.from(result.resultContents()));
                        if (response.path("exitCode").asInt(-1) != 0
                                || response.path("timedOut").asBoolean()) return result;
                        LlmClient.checkCancelled(context);
                        var published = files.publishAll(execution);
                        return ToolExecutionResult.builder().resultText(json.writeValueAsString(
                                Map.of("execution", response, "deliverables", published))).build();
                    }
                    if (!(args.get("path") instanceof String name)) {
                        throw AssetException.invalid("Output filename is required");
                    }
                    Path path = files.resolve(execution, name);
                    Files.createDirectories(path.getParent());
                    path = files.resolve(execution, name);
                    temporary = Files.createTempFile(path.getParent(), ".pending-", ".part");
                    if (tool.name().equals("edit")) {
                        Files.copy(path, temporary, StandardCopyOption.REPLACE_EXISTING);
                    }
                    args.put("path", temporary.toString());
                    var actual = ToolExecutionRequest.builder()
                            .id(request.id()).name(request.name())
                            .arguments(json.writeValueAsString(args)).build();
                    var result = delegate.executeWithContext(actual, context);
                    if (result.isError()) return result;
                    LlmClient.checkCancelled(context);
                    // Removed Task executions must not publish outputs after a tool finishes.
                    path = files.resolve(execution, name);
                    Files.move(temporary, path,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    var published = files.publish(execution, path);
                    return ToolExecutionResult.builder().resultText(json.writeValueAsString(
                            Map.of("success", true, "path", path.toString(),
                                    "deliverable", published))).build();
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                } finally {
                    if (temporary != null) {
                        try {
                            Files.deleteIfExists(temporary);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }).build();
    }
}
