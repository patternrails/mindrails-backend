package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.model.SessionInput;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.EventSink;
import com.mindrails.backend.common.ServiceResult;
import java.util.UUID;

/** Session loop operations with message submission separate from event subscriptions. */
public interface LoopSessionStreamService {

    default ServiceResult<Void> enqueue(UUID sessionId, ClientCommand.Chat command) {
        return enqueue(sessionId, new SessionInput(command.requestId(), command.message(), command.assetVersionIds(), null));
    }

    ServiceResult<Void> enqueue(UUID sessionId, SessionInput input);

    ServiceResult<Void> stop(UUID sessionId);

    ServiceResult<Void> bind(UUID sessionId, EventSink sink);

    ServiceResult<Void> unbind(UUID sessionId, EventSink sink);
}
