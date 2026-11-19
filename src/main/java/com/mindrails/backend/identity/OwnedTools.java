package com.mindrails.backend.identity;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.*;
import java.util.UUID;
import java.util.function.Supplier;

/** Model transports can invoke providers/executors on arbitrary threads. Capture the persisted owner,
 * and re-evaluate the provider for each invocation so cached clients cannot retain revoked grants. */
public final class OwnedTools {
    private OwnedTools() {}
    public static ToolProvider provider(UUID owner, Supplier<ToolProvider> source) {
        return request -> {
            try(var ignored=ResourceAccess.as(ResourceAccess.owner(owner))) {
                var provider=source.get();
                return provider==null ? ToolProviderResult.builder().build()
                        : ToolProviderResult.builder().addAll(provider.provideTools(request).tools().keySet().stream()
                            .map(spec -> AiServiceTool.builder().toolSpecification(spec).toolExecutor(new ToolExecutor() {
                                private ToolExecutor current() {
                                    var latest=source.get();
                                    if(latest==null) throw new IdentityException(404,"Resource not found");
                                    return latest.provideTools(request).tools().entrySet().stream()
                                            .filter(entry -> entry.getKey().name().equals(spec.name()))
                                            .map(java.util.Map.Entry::getValue).findFirst()
                                            .orElseThrow(() -> new IdentityException(404,"Resource not found"));
                                }
                                public String execute(ToolExecutionRequest call,Object memoryId) {
                                    try(var scope=ResourceAccess.as(ResourceAccess.owner(owner))) { return current().execute(call,memoryId); }
                                }
                                public ToolExecutionResult executeWithContext(ToolExecutionRequest call,InvocationContext context) {
                                    try(var scope=ResourceAccess.as(ResourceAccess.owner(owner))) { return current().executeWithContext(call,context); }
                                }
                            }).build()).toList()).build();
            }
        };
    }
    public static AiServiceTool tool(UUID owner,AiServiceTool tool) {
        var original=tool.toolExecutor();
        return tool.toBuilder().toolExecutor(new ToolExecutor() {
            public String execute(ToolExecutionRequest call,Object memoryId) {
                try(var ignored=ResourceAccess.as(ResourceAccess.owner(owner))) { return original.execute(call,memoryId); }
            }
            public ToolExecutionResult executeWithContext(ToolExecutionRequest call,InvocationContext context) {
                try(var ignored=ResourceAccess.as(ResourceAccess.owner(owner))) { return original.executeWithContext(call,context); }
            }
        }).build();
    }
}
