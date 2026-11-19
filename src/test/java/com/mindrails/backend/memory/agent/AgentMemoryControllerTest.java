package com.mindrails.backend.memory.agent;

import static com.mindrails.backend.memory.agent.AgentMemoryTestSupport.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindrails.backend.agent.api.web.AgentMemoryController;
import com.mindrails.backend.common.ServiceResponseAdvice;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class AgentMemoryControllerTest {
    private final Fixture f = new Fixture();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentMemoryController(f.memory))
            .setControllerAdvice(new ServiceResponseAdvice()).build();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void completesExplicitCrudAndVersionedDelete() throws Exception {
        UUID owner = f.agent();
        String root = "/api/agent/" + owner + "/memories";
        String body = mvc.perform(post(root).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input("database", "项目使用 MySQL", true, null))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).get("id").asText();
        mvc.perform(get(root)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(root + "/search").param("query", "数据库"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(put(root + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input("database", "修改后的事实", true, 0L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(root + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input("database", "Stale", true, 0L))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(delete(root + "/" + id).param("version", "0")).andExpect(status().isConflict());
        mvc.perform(delete(root + "/" + id).param("version", "1")).andExpect(status().isNoContent());
        mvc.perform(get(root + "/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMalformedRequestsAndAccessToForeignEntries() throws Exception {
        UUID owner = f.agent(), other = f.agent();
        var entry = success(f.memory.create(owner, input("fact", "Saved", false, null)));
        String root = "/api/agent/" + owner + "/memories";
        mvc.perform(post(root).contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"bad key\",\"content\":\"x\",\"type\":\"FACT\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(root).contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"fact\",\"content\":\" \",\"type\":\"FACT\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(root).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/agent/{owner}/memories/{entry}", other, entry.id())).andExpect(status().isNotFound());
        mvc.perform(delete(root + "/" + entry.id())).andExpect(status().isBadRequest());
    }
}
