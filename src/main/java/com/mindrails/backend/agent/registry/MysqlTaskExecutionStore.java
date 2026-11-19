package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.TaskExecution;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Business executions and ordered records are separate from history/metrics retention. */
public final class MysqlTaskExecutionStore implements TaskExecutionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public MysqlTaskExecutionStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
        tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    private String encode(TaskExecution e) { return json.writeValueAsString(e.toBuilder().records(List.of()).build()); }
    public void insert(TaskExecution e) {
        jdbc.update("INSERT INTO task_executions(id,owner_id,agent_id,task_id,request_id,status,created_at,execution) VALUES(?,?,?,?,?,?,?,?)",
                e.id().toString(), com.mindrails.backend.identity.ResourceAccess.owner(e.ownerId()).toString(), e.agentId().toString(), e.taskId().toString(), e.requestId().toString(),
                e.status().name(), Timestamp.from(e.createdAt()), encode(e));
    }
    public void save(TaskExecution e) {
        jdbc.update("UPDATE task_executions SET status=?,execution=? WHERE id=? AND status IN ('STARTING','RUNNING','CANCELLING')",
                e.status().name(), encode(e), e.id().toString());
    }
    public void append(UUID id, TaskExecution.Entry entry) {
        tx.executeWithoutResult(ignored -> {
            String state = jdbc.queryForObject("SELECT status FROM task_executions WHERE id=? FOR UPDATE", String.class, id.toString());
            if (TaskExecution.Status.valueOf(state).terminal()) return;
            jdbc.update("INSERT INTO task_execution_records(execution_id,sequence,record) VALUES(?,?,?)",
                    id.toString(), entry.sequence(), json.writeValueAsString(entry));
        });
    }
    public Optional<TaskExecution> find(UUID id) {
        var user=com.mindrails.backend.identity.ResourceAccess.userId();
        return tx.execute(ignored -> jdbc.query("SELECT execution FROM task_executions WHERE id=?" + (user==null?"":" AND owner_id=?") + " FOR UPDATE",
                (rs, n) -> json.readValue(rs.getString(1), TaskExecution.class), user==null?new Object[]{id.toString()}:new Object[]{id.toString(),user.toString()}).stream().filter(e -> com.mindrails.backend.identity.ResourceAccess.visible(e.ownerId())).findFirst()
                .map(e -> e.toBuilder().records(jdbc.query("SELECT record FROM task_execution_records WHERE execution_id=? ORDER BY sequence",
                        (rs,n) -> json.readValue(rs.getString(1), TaskExecution.Entry.class), id.toString())).build()));
    }
    public List<TaskExecution> list(UUID agent, UUID task, int page, int size) {
        var user=com.mindrails.backend.identity.ResourceAccess.userId();
        var args=new ArrayList<Object>(List.of(agent.toString(),task.toString()));
        if(user!=null) args.add(user.toString());
        args.add(size); args.add((long)page*size);
        return jdbc.query("SELECT execution FROM task_executions WHERE agent_id=? AND task_id=?" + (user==null?"":" AND owner_id=?") + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs,n) -> json.readValue(rs.getString(1), TaskExecution.class), args.toArray());
    }
    public List<TaskExecution> recoverInterrupted() {
        return tx.execute(ignored -> {
            var pending = jdbc.query("SELECT execution FROM task_executions WHERE status IN ('STARTING','RUNNING','CANCELLING') FOR UPDATE",
                    (rs,n) -> json.readValue(rs.getString(1), TaskExecution.class));
            var recovered=pending.stream().map(InMemoryTaskExecutionStore::interrupted).toList();
            recovered.forEach(this::save);
            return recovered;
        });
    }
}
