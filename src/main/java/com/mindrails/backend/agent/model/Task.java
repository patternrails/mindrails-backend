package com.mindrails.backend.agent.model;

import com.mindrails.backend.provider.Provider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.*;

/** Reusable work definition owned by an Agent; never contains conversation memory. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Task implements com.mindrails.backend.identity.UserOwnedResource {
    private UUID id;
    private UUID ownerId;
    private UUID parentAgentId;
    private String name;
    private Provider provider;
    private String instructions;
    @Builder.Default private List<UUID> skillIds = new ArrayList<>();
    /** Unix cron: minute hour day-of-month month day-of-week; null disables scheduling. */
    private String cronjob;
    private Instant createdAt;
    private Instant updatedAt;
    @com.fasterxml.jackson.annotation.JsonIgnore private long version;

    public Task snapshot() {
        return toBuilder().provider(provider == null ? null : provider.toBuilder().build()).skillIds(new ArrayList<>(skillIds)).build();
    }
}
