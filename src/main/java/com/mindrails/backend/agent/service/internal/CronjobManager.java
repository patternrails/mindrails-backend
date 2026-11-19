package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** Internal single-instance scheduler. Task definitions are the durable source of truth. */
@Component
@lombok.extern.slf4j.Slf4j
public class CronjobManager {
    private final TaskRegistry tasks;
    private final DefaultTaskExecutionService executions;
    private final ThreadPoolTaskScheduler scheduler;
    private final ZoneId zone;
    private final Map<UUID,Registration> jobs=new HashMap<>();
    private volatile boolean ready;
    private record Registration(String expression,UUID generation,ScheduledFuture<?> future) {}
    public CronjobManager(TaskRegistry tasks,DefaultTaskExecutionService executions,
            ThreadPoolTaskScheduler taskCronScheduler,@Value("${mindrails.tasks.cron-zone:Asia/Shanghai}") String zone) {
        this.tasks=tasks;this.executions=executions;this.scheduler=taskCronScheduler;this.zone=ZoneId.of(zone);
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        ready=true;reconcile();
    }
    @EventListener public void changed(TaskService.Changed event) { if(ready)reconcile(); }
    @Scheduled(fixedDelayString="${mindrails.tasks.cron-reconcile-ms:30000}")
    public void reconcile() {
        if(!ready)return;
        // Always take the registry guard first, matching Task create/update/delete and reserve.
        synchronized(tasks) { synchronized(this) {
            var scheduled=tasks.scheduled();var present=new HashSet<UUID>();
            for(var task:scheduled) {
                present.add(task.getId());var old=jobs.get(task.getId());
                if(old!=null && old.expression().equals(task.getCronjob()))continue;
                if(old!=null) { old.future().cancel(false);jobs.remove(task.getId()); }
                try {
                    var cron=new UnixCron(task.getCronjob());
                    UUID generation=UUID.randomUUID();
                    var scheduledAt=new java.util.concurrent.atomic.AtomicReference<Instant>();
                    var future=scheduler.schedule(() -> fire(task.getId(),task.getCronjob(),generation,scheduledAt.get()),ctx -> {
                        Instant last=ctx.lastScheduledExecution();Instant now=ctx.getClock().instant();
                        Instant next=cron.next(last==null || last.isBefore(now)?now:last,zone);
                        scheduledAt.set(next);return next;
                    });
                    if(future!=null)jobs.put(task.getId(),new Registration(task.getCronjob(),generation,future));
                } catch(RuntimeException e) { log.error("Task cron registration failed: taskId={}",task.getId(),e); }
            }
            jobs.entrySet().removeIf(e -> { if(present.contains(e.getKey()))return false;e.getValue().future().cancel(false);return true; });
        } }
    }
    void fire(UUID id,String expression,UUID generation,Instant scheduledAt) {
        synchronized(tasks) {
            synchronized(this) {
                var current=jobs.get(id);
                if(current==null || !current.generation().equals(generation))return;
            }
            var task=tasks.find(id).orElse(null);
            if(task==null || !Objects.equals(task.getCronjob(),expression))return;
            try (var scope = com.mindrails.backend.identity.ResourceAccess.as(com.mindrails.backend.identity.ResourceAccess.owner(task.getOwnerId()))) {
            var result=executions.submit(task.getParentAgentId(),id,new TaskExecutionRequest(UUID.randomUUID(),Map.of()),"CRON",
                    new TaskExecution.Trigger(TaskExecution.TriggerType.CRON,null,null,scheduledAt==null?Instant.now():scheduledAt),null);
            if(result instanceof com.mindrails.backend.common.ServiceResult.Failure<?> f && f.code()!=com.mindrails.backend.common.ServiceResult.ErrorCode.CONFLICT)
                log.warn("Task cron trigger failed: taskId={}, code={}",id,f.code());
            }
        }
    }
    @jakarta.annotation.PreDestroy public synchronized void stop() {
        ready=false;jobs.values().forEach(j -> j.future().cancel(false));jobs.clear();
    }
}
