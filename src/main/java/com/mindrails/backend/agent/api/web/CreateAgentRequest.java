package com.mindrails.backend.agent.api.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Client-supplied fields for creating an agent. */
@JsonIgnoreProperties("status")
@Getter
@Setter
public class CreateAgentRequest {

    @NotBlank
    private String name;
    private String description;

    /** Broad, long-term purpose of this agent. */
    private String goal;

    /** General system message shared across this agent's sessions. */
    private String systemMessage;
}
