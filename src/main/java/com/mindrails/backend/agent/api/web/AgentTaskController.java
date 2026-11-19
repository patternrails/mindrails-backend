package com.mindrails.backend.agent.api.web;

import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.service.AgentService;
import com.mindrails.backend.agent.service.internal.DefaultTaskExecutionService;
import static com.mindrails.backend.common.ServiceResponses.response;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/{agentId}/tasks")
public class AgentTaskController {
    private final AgentService agents;
    private final DefaultTaskExecutionService executions;
    public AgentTaskController(AgentService agents,DefaultTaskExecutionService executions) { this.agents=agents;this.executions=executions; }
    @PostMapping public ResponseEntity<UUID> create(@PathVariable UUID agentId,@Valid @RequestBody TaskDefinitionRequest request) {
        return response(agents.createTask(agentId,request.toTask(),request.providerId()),HttpStatus.CREATED);
    }
    @GetMapping public ResponseEntity<List<Task>> list(@PathVariable UUID agentId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return response(agents.listTasks(agentId,page,size),HttpStatus.OK);
    }
    @GetMapping("/{taskId}") public ResponseEntity<Task> get(@PathVariable UUID agentId,@PathVariable UUID taskId) {
        return response(agents.getTask(agentId,taskId),HttpStatus.OK);
    }
    @PutMapping("/{taskId}") public ResponseEntity<Void> update(@PathVariable UUID agentId,@PathVariable UUID taskId,@Valid @RequestBody TaskDefinitionRequest request) {
        return response(agents.updateTask(agentId,taskId,request.toTask(),request.providerId()),HttpStatus.NO_CONTENT);
    }
    @DeleteMapping("/{taskId}") public ResponseEntity<Void> delete(@PathVariable UUID agentId,@PathVariable UUID taskId) {
        return response(agents.deleteTask(agentId,taskId),HttpStatus.NO_CONTENT);
    }
    @PostMapping("/{taskId}/execute/details") public ResponseEntity<TaskReply> execute(@PathVariable UUID agentId,@PathVariable UUID taskId,@RequestBody TaskExecutionRequest request) {
        return response(agents.executeWithDetailed(agentId,taskId,request),HttpStatus.OK);
    }
    public record Accepted(UUID executionId) {}
    @PostMapping("/{taskId}/execute") public ResponseEntity<Accepted> submit(@PathVariable UUID agentId,@PathVariable UUID taskId,@RequestBody TaskExecutionRequest request) {
        return response(executions.submit(agentId,taskId,request,"REST",TaskExecution.Trigger.api(),null).map(Accepted::new),HttpStatus.ACCEPTED);
    }
    @GetMapping("/{taskId}/executions") public ResponseEntity<List<TaskExecution>> executions(@PathVariable UUID agentId,@PathVariable UUID taskId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return response(agents.listTaskExecutions(agentId,taskId,page,size).map(items -> items.stream().map(com.mindrails.backend.tool.display.ToolUiProjection::task).toList()),HttpStatus.OK);
    }
    /** Lightweight authoritative state for lists and clients that did not start the execution. */
    @GetMapping("/{taskId}/executions/active")
    public ResponseEntity<com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway.TaskSnapshot> active(
            @PathVariable UUID agentId,@PathVariable UUID taskId) {
        return response(agents.getTask(agentId,taskId).map(task ->
                new com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway.TaskSnapshot(executions.active(taskId))),HttpStatus.OK);
    }
    @GetMapping("/{taskId}/executions/{executionId}") public ResponseEntity<TaskExecution> execution(@PathVariable UUID agentId,@PathVariable UUID taskId,@PathVariable UUID executionId) {
        return response(agents.getTaskExecution(agentId,taskId,executionId).map(com.mindrails.backend.tool.display.ToolUiProjection::task),HttpStatus.OK);
    }
    @PostMapping("/{taskId}/executions/{executionId}/cancel") public ResponseEntity<Void> cancel(@PathVariable UUID agentId,@PathVariable UUID taskId,@PathVariable UUID executionId) {
        return response(agents.cancelTaskExecution(agentId,taskId,executionId),HttpStatus.NO_CONTENT);
    }
}
