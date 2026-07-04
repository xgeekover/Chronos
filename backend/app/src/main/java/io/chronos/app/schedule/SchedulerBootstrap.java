package io.chronos.app.schedule;

import io.chronos.app.persistence.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, (re)registers a Quartz job for every enabled Task. Compensates for the in-memory
 * job store (Phase 2): schedules are reconstructed from the metadata DB at boot.
 */
@Component
public class SchedulerBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchedulerBootstrap.class);

    private final TaskRepository tasks;
    private final TaskSchedulerService scheduler;

    public SchedulerBootstrap(TaskRepository tasks, TaskSchedulerService scheduler) {
        this.tasks = tasks;
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        var enabled = tasks.findByEnabledTrue();
        enabled.forEach(scheduler::schedule);
        log.info("Re-scheduled {} enabled task(s) on startup", enabled.size());
    }
}
