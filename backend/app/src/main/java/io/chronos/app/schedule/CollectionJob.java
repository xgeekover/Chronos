package io.chronos.app.schedule;

import io.chronos.app.service.CollectionService;
import io.chronos.engine.pipeline.CollectionResult;
import java.util.UUID;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz job that runs one Task through the collection pipeline (§7 step 1). Instances are
 * created by Quartz and autowired by Spring's job factory, so field injection is used.
 *
 * <p>{@link DisallowConcurrentExecution}: if a run exceeds the task interval, Quartz must not start
 * a second run of the same task in parallel (avoids duplicate history/log writes + alarm races).
 */
@DisallowConcurrentExecution
public class CollectionJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(CollectionJob.class);

    static final String TASK_ID = "taskId";

    @Autowired
    private CollectionService collectionService;

    @Override
    public void execute(JobExecutionContext context) {
        String taskId = context.getMergedJobDataMap().getString(TASK_ID);
        try {
            CollectionResult result = collectionService.runTaskNow(UUID.fromString(taskId));
            if (!result.ok()) {
                log.warn("Task {} collection {}: {}", taskId, result.status(), result.error());
            }
        } catch (Exception e) {
            log.warn("Task {} collection threw: {}", taskId, e.getMessage());
        }
    }
}
