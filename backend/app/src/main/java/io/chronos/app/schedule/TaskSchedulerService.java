package io.chronos.app.schedule;

import io.chronos.app.persistence.TaskEntity;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registers/unregisters Quartz jobs for Tasks (ADR-003). Per-Task schedule: INTERVAL → simple
 * repeating trigger, CRON → cron trigger. In-memory job store (Phase 2); enabled tasks are
 * re-registered on startup by {@link SchedulerBootstrap}.
 */
@Service
public class TaskSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerService.class);
    private static final String GROUP = "chronos-collection";

    private final Scheduler scheduler;

    public TaskSchedulerService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void schedule(TaskEntity task) {
        JobKey key = jobKey(task.getId().toString());
        try {
            scheduler.deleteJob(key); // replace any existing schedule
            if (!task.isEnabled()) {
                return;
            }
            JobDetail job = JobBuilder.newJob(CollectionJob.class)
                    .withIdentity(key)
                    .usingJobData(CollectionJob.TASK_ID, task.getId().toString())
                    .build();
            scheduler.scheduleJob(job, trigger(task));
            log.info("Scheduled task {} ({} {})", task.getId(), task.getScheduleKind(),
                    "CRON".equals(task.getScheduleKind()) ? task.getCronExpr() : task.getIntervalMs() + "ms");
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to schedule task " + task.getId(), e);
        }
    }

    public void unschedule(String taskId) {
        try {
            scheduler.deleteJob(jobKey(taskId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to unschedule task " + taskId, e);
        }
    }

    private Trigger trigger(TaskEntity task) {
        TriggerBuilder<Trigger> tb = TriggerBuilder.newTrigger()
                .withIdentity("trigger-" + task.getId(), GROUP);
        if ("CRON".equals(task.getScheduleKind())) {
            return tb.withSchedule(CronScheduleBuilder.cronSchedule(task.getCronExpr())).build();
        }
        long interval = task.getIntervalMs() == null ? 60_000L : task.getIntervalMs();
        return tb.startNow()
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(interval).repeatForever())
                .build();
    }

    private static JobKey jobKey(String taskId) {
        return new JobKey("task-" + taskId, GROUP);
    }
}
