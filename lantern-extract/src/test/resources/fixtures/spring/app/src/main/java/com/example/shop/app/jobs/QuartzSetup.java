package com.example.shop.app.jobs;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.stereotype.Component;

@Component
public class QuartzSetup {

  public void schedule(Scheduler scheduler) {
    JobDetail job = JobBuilder.newJob(ExpiryJob.class).withIdentity("expiry").build();
    Trigger trigger =
        TriggerBuilder.newTrigger()
            .withSchedule(CronScheduleBuilder.cronSchedule("0 15 2 * * ?"))
            .build();
    scheduler.scheduleJob(job, trigger);
  }
}
