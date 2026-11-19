package com.mindrails.backend.persistence;

import java.util.List;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public final class RegistryJson {
    private final ObjectMapper mapper;
    public RegistryJson(ObjectMapper mapper) { this.mapper = mapper; }
    public String ids(List<UUID> ids) { return mapper.writeValueAsString(ids == null ? List.of() : ids); }
    public List<UUID> ids(String json) {
        return json == null ? List.of() : mapper.readValue(json, new TypeReference<List<UUID>>() {});
    }
    public static String key(UUID id) { return id == null ? null : id.toString(); }
    public static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
