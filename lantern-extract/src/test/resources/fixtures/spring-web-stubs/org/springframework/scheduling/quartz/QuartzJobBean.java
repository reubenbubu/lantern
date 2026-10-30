package org.springframework.scheduling.quartz;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

public abstract class QuartzJobBean implements Job {
  public final void execute(JobExecutionContext context) throws JobExecutionException {}

  protected abstract void executeInternal(JobExecutionContext context) throws JobExecutionException;
}
