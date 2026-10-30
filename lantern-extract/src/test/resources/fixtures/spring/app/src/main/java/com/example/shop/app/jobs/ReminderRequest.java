package com.example.shop.app.jobs;

import org.jobrunr.jobs.lambdas.JobRequest;

public class ReminderRequest implements JobRequest {
  public final long taskId;

  public ReminderRequest(long taskId) {
    this.taskId = taskId;
  }
}
