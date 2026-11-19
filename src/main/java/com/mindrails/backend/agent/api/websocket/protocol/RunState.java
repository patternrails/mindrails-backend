package com.mindrails.backend.agent.api.websocket.protocol;

/**
 * Run lifecycle:
 * STARTING -> RUNNING
 * RUNNING -> CANCELLING -> CANCELLED -> RUNNING
 * RUNNING -> COMPLETED -> RUNNING
 * RUNNING -> FAILED -> RUNNING
 * RUNNING -> TERMINATING -> TERMINATED
 */
public enum RunState {
    STARTING,
    RUNNING,
    CANCELLING,
    CANCELLED,
    COMPLETED,
    FAILED,
    TERMINATING,
    TERMINATED;

    public boolean roundFinished() {
        return this == CANCELLED || this == COMPLETED || this == FAILED;
    }

    public boolean terminal() {
        return this == TERMINATED;
    }
}
