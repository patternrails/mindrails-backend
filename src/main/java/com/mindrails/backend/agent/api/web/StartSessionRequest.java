package com.mindrails.backend.agent.api.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Provider selected when starting a session. */
@Getter
@Setter
public class StartSessionRequest {

    @NotNull
    private UUID providerId;
}
