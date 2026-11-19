package com.mindrails.backend.tool;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.tool.spec.BashTool;
import com.mindrails.backend.tool.spec.EditTool;
import com.mindrails.backend.tool.spec.FindTool;
import com.mindrails.backend.tool.spec.GrepTool;
import com.mindrails.backend.tool.spec.LsTool;
import com.mindrails.backend.tool.spec.PowerShellTool;
import com.mindrails.backend.tool.spec.ReadTool;
import com.mindrails.backend.tool.spec.WriteTool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

import dev.langchain4j.service.tool.AiServiceTool;

/**
 * Provides tool instances with methods annotated with LangChain4j's {@code @Tool}.
 */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class CompositeToolConfiguration {

    private final List<IdentifiedTool> toolInstances;

    public CompositeToolConfiguration() {
        this(new FileStorageProperties(Path.of("./data")));
    }

    @Autowired
    public CompositeToolConfiguration(FileStorageProperties files) {
        this(List.of(new LsTool(), new ReadTool(), new WriteTool(files.directory()), new EditTool(files.directory()),
                new BashTool(), new PowerShellTool(), new GrepTool(), new FindTool()));
    }

    public CompositeToolConfiguration(List<? extends IdentifiedTool> toolInstances) {
        this.toolInstances = List.copyOf(toolInstances);
    }

    @Bean
    public List<AiServiceTool> tools(ObjectProvider<IdentifiedTool> extensions,
            @Qualifier("metricsRecorder") MetricsRecorder metricsRecorder) {
        return java.util.stream.Stream.concat(toolInstances.stream(), extensions.orderedStream())
                .flatMap(tool -> tool.buildTools(metricsRecorder).stream()).toList();
    }
}
