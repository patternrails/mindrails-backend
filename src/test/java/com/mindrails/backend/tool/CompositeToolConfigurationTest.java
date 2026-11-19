package com.mindrails.backend.tool;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.tool.spec.BashTool;
import com.mindrails.backend.tool.spec.EditTool;
import com.mindrails.backend.tool.spec.FindTool;
import com.mindrails.backend.tool.spec.GrepTool;
import com.mindrails.backend.tool.spec.LsTool;
import com.mindrails.backend.tool.spec.PowerShellTool;
import com.mindrails.backend.tool.spec.ReadTool;
import com.mindrails.backend.tool.spec.WriteTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.AiServiceTool;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class CompositeToolConfigurationTest {

    @Test
    void springBeanRegistersAllEightToolsWithRequiredPaths() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(com.mindrails.backend.history.ChatHistory.class, com.mindrails.backend.history.TestHistory::disabled);
            context.registerBean(com.mindrails.backend.metrics.MetricsRecorder.class, com.mindrails.backend.metrics.TestMetrics::disabled);
            context.registerBean(com.mindrails.backend.mcp.api.toolspec.McpTools.class,
                    com.mindrails.backend.agent.service.internal.llm.TestLlmClients::noMcpTools);
            context.registerBean(com.mindrails.backend.knowledge.tool.KnowledgeTools.class,
                    com.mindrails.backend.agent.service.internal.llm.TestLlmClients::disabledKnowledgeTools);
            context.registerBean(com.mindrails.backend.asset.service.SessionDeliverables.class,
                    com.mindrails.backend.agent.service.internal.llm.TestLlmClients::sessionFiles);
            context.register(CompositeToolConfiguration.class, ToolsConsumer.class, LlmClientFactory.class);
            context.refresh();
            var tools = context.getBean(ToolsConsumer.class).tools;
            assertSame(context.getBean("tools"), tools);
            assertFalse(context.containsBean("toolSpecifications"));
            assertFalse(context.containsBean("toolsById"));
            assertNotNull(context.getBean(LlmClientFactory.class));
            var specifications = tools.stream().map(AiServiceTool::toolSpecification).toList();
            assertEquals(8, specifications.size());
            assertEquals(Set.of("ls", "read", "write", "edit", "bash", "powershell", "grep", "find"),
                    specifications.stream().map(ToolSpecification::name).collect(Collectors.toSet()));
            assertEquals(8, tools.size());
            for (var specification : specifications) {
                assertFalse(specification.description().isBlank());
                assertTrue(specification.parameters().required().contains("path"), specification.name());
            }
        }
        for (Class<?> type : List.of(LsTool.class, ReadTool.class, WriteTool.class, EditTool.class,
                BashTool.class, PowerShellTool.class, GrepTool.class, FindTool.class)) {
            for (var field : type.getDeclaredFields()) {
                assertTrue(Modifier.isFinal(field.getModifiers()) && (Modifier.isStatic(field.getModifiers())
                        || type == GrepTool.class && field.getType() == String.class
                        || (type == WriteTool.class || type == EditTool.class)
                                && field.getType() == java.nio.file.Path.class), type.getName());
            }
            assertEquals(1, java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Tool.class)).count());
        }
    }

    public static class ToolsConsumer {
        final List<AiServiceTool> tools;

        public ToolsConsumer(@Qualifier("tools") List<AiServiceTool> tools) {
            this.tools = tools;
        }
    }
}
