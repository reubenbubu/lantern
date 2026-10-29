package org.quartz;

public interface Job {
  void execute(JobExecutionContext context) throws JobExecutionException;
}
