package org.quartz;

public interface Scheduler {
  void scheduleJob(JobDetail job, Trigger trigger);
}
