package com.mindrails.backend.agent.api.websocket.protocol;

import com.fasterxml.jackson.annotation.JsonValue;

/** Stable wire types shared by streaming events and command responses. */
public enum ServerMessageType {
    CONNECTION_READY("connection.ready"),
    COMMAND_ERROR("command.error"),
    CHAT_ACCEPTED("chat.accepted"),
    MESSAGE_ENQUEUED("message.enqueued"),
    RUN_CANCELLING("run.cancelling"),
    RUN_STARTED("run.started"),
    THINKING_DELTA("thinking.delta"),
    ANSWER_DELTA("answer.delta"),
    RETRIEVAL_COMPLETED("retrieval.completed"),
    TOOL_ARGUMENTS_DELTA("tool.arguments.delta"),
    MODEL_RESPONSE_COMPLETED("model.response.completed"),
    TOOL_STARTED("tool.started"),
    TOOL_COMPLETED("tool.completed"),
    TOOL_FAILED("tool.failed"),
    RUN_COMPLETED("run.completed"),
    RUN_CANCELLED("run.cancelled"),
    RUN_FAILED("run.failed");

    private final String value;

    ServerMessageType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
