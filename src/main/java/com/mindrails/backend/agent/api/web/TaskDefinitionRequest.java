package com.mindrails.backend.agent.api.web;

import com.mindrails.backend.agent.model.Task;
import jakarta.validation.constraints.*;
import java.util.*;

/** PUT replaces all editable fields; omitting/clearing cronjob disables scheduling. */
public record TaskDefinitionRequest(@NotNull UUID providerId,@Size(max=160) String name,
        @NotBlank @Size(max=100000) String instructions,@Size(max=100) List<@NotNull UUID> skillIds,String cronjob) {
    public Task toTask() { return Task.builder().name(name).instructions(instructions)
            .skillIds(skillIds==null?List.of():skillIds).cronjob(cronjob).build(); }
}
