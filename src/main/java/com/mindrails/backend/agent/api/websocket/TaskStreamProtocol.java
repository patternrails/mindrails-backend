package com.mindrails.backend.agent.api.websocket;

import com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway.Command;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

final class TaskStreamProtocol {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static Command decode(String text) {
        var root=JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).readTree(text);
        if(root==null || !root.isObject())throw new IllegalArgumentException("Expected an object");
        fields(root,"version","type","requestId","data");
        if(!root.path("version").isIntegralNumber() || root.path("version").asInt()!=1)throw new IllegalArgumentException("Unsupported version");
        UUID request=StreamProtocol.uuid(root.path("requestId"));var data=root.path("data");
        if(!data.isObject())throw new IllegalArgumentException("data must be an object");
        return switch(root.path("type").asString("")) {
            case "execute" -> {
                fields(data,"parameters");
                if(data.has("parameters") && !data.path("parameters").isObject())throw new IllegalArgumentException("parameters must be an object");
                Map<String,Object> parameters=data.has("parameters")?JSON.convertValue(data.get("parameters"),new TypeReference<Map<String,Object>>() {}):Map.of();
                yield new Command.Execute(request,parameters);
            }
            case "cancel" -> { fields(data,"executionId");yield new Command.Cancel(request,StreamProtocol.uuid(data.path("executionId"))); }
            default -> throw new IllegalArgumentException("Unsupported Task command");
        };
    }
    private static void fields(JsonNode node,String... allowed) {
        var valid=Set.of(allowed);for(String name:node.propertyNames())if(!valid.contains(name))throw new IllegalArgumentException("Unknown field: "+name);
    }
}
