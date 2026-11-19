package com.mindrails.backend.agent.service.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.provider.Provider;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.SimpleTriggerContext;

class CronjobManagerTest {
    @Test void bootRestoresSchedulesAndChangesReplaceOrRemoveWithoutStaleCallbacks() {
        var f=new TaskBackendTest.Fixture(TaskBackendTest.model());
        var definition=Task.builder().instructions("Daily report").cronjob("0 9 * * *").build();
        UUID task=TaskBackendTest.success(f.service.create(f.agent,definition,f.provider.getId()));
        var scheduler=mock(ThreadPoolTaskScheduler.class);var callbacks=new ArrayList<Runnable>();var triggers=new ArrayList<Trigger>();var futures=new ArrayList<ScheduledFuture<?>>();
        when(scheduler.schedule(any(Runnable.class),any(Trigger.class))).thenAnswer(inv -> {
            callbacks.add(inv.getArgument(0));triggers.add(inv.getArgument(1));var future=mock(ScheduledFuture.class);futures.add(future);return future;
        });
        var manager=new CronjobManager(f.tasks,f.executions,scheduler,"Asia/Shanghai");
        manager.start();assertEquals(1,callbacks.size());assertTrue(TaskBackendTest.success(f.executions.list(f.agent,task,0,20)).isEmpty());
        manager.reconcile();assertEquals(1,callbacks.size());
        Instant at=triggers.getFirst().nextExecution(new SimpleTriggerContext());assertNotNull(at);
        callbacks.getFirst().run();var record=TaskBackendTest.success(f.executions.list(f.agent,task,0,20)).getFirst();
        assertEquals(TaskExecution.TriggerType.CRON,record.trigger().type());assertEquals(at,record.trigger().scheduledAt());
        TaskBackendTest.success(f.service.update(f.agent,task,definition.toBuilder().cronjob("0 10 * * *").build(),f.provider.getId()));manager.reconcile();
        verify(futures.getFirst()).cancel(false);assertEquals(2,callbacks.size());callbacks.getFirst().run();
        assertEquals(1,TaskBackendTest.success(f.executions.list(f.agent,task,0,20)).size());
        // A->B->A must still reject the original A callback.
        TaskBackendTest.success(f.service.update(f.agent,task,definition,f.provider.getId()));manager.reconcile();callbacks.getFirst().run();
        assertEquals(1,TaskBackendTest.success(f.executions.list(f.agent,task,0,20)).size());
        TaskBackendTest.success(f.service.update(f.agent,task,definition.toBuilder().cronjob(null).build(),f.provider.getId()));manager.reconcile();
        verify(futures.getLast()).cancel(false);callbacks.getLast().run();assertEquals(1,TaskBackendTest.success(f.executions.list(f.agent,task,0,20)).size());manager.stop();
    }
    @Test void registrationFailureIsRetriedByReconciliation() {
        var f=new TaskBackendTest.Fixture(TaskBackendTest.model());
        TaskBackendTest.success(f.service.create(f.agent,Task.builder().instructions("report").cronjob("0 9 * * *").build(),f.provider.getId()));
        var scheduler=mock(ThreadPoolTaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class),any(Trigger.class))).thenThrow(new IllegalStateException("stopped"))
                .thenAnswer(inv -> mock(ScheduledFuture.class));
        var manager=new CronjobManager(f.tasks,f.executions,scheduler,"UTC");manager.start();manager.reconcile();
        verify(scheduler,times(2)).schedule(any(Runnable.class),any(Trigger.class));manager.stop();
    }
}
