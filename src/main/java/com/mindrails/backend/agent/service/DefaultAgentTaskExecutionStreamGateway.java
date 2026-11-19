package com.mindrails.backend.agent.service;

import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.service.internal.DefaultTaskExecutionService;
import com.mindrails.backend.common.ServiceResult;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class DefaultAgentTaskExecutionStreamGateway implements AgentTaskExecutionStreamGateway {
    private final DefaultTaskExecutionService executions;
    public DefaultAgentTaskExecutionStreamGateway(DefaultTaskExecutionService executions) { this.executions=executions; }
    public ServiceResult<TaskSnapshot> validate(AgentTaskScope scope) {
        if(scope==null)return ServiceResult.failure(ServiceResult.ErrorCode.INVALID_ARGUMENT,"Task scope is required");
        return executions.owned(scope.agentId(),scope.taskId()).map(t -> new TaskSnapshot(executions.active(t.getId())));
    }
    public ServiceResult<UUID> reserveAndExecute(AgentTaskScope scope,Command.Execute command,EventSink sink) {
        if(scope==null || command==null || command.requestId()==null || sink==null)
            return ServiceResult.failure(ServiceResult.ErrorCode.INVALID_ARGUMENT,"Scope, request ID and sink are required");
        return executions.submit(scope.agentId(),scope.taskId(),new TaskExecutionRequest(command.requestId(),command.parameters()),
                "WEBSOCKET",TaskExecution.Trigger.api(),sink);
    }
    public ServiceResult<Void> command(AgentTaskScope scope,Command command) {
        if(scope==null || command==null || command.requestId()==null)return ServiceResult.failure(ServiceResult.ErrorCode.INVALID_ARGUMENT,"Invalid command");
        return switch(command) {
            case Command.Cancel c -> executions.cancel(scope.agentId(),scope.taskId(),c.executionId());
            default -> ServiceResult.failure(ServiceResult.ErrorCode.INVALID_ARGUMENT,"Unsupported command");
        };
    }
}
