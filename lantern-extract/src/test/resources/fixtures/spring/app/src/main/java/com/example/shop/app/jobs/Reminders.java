package com.example.shop.app.jobs;

import java.time.Instant;
import org.jobrunr.scheduling.BackgroundJobRequest;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.springframework.stereotype.Service;

@Service
public class Reminders {

  private final JobRequestScheduler scheduler;

  public Reminders(JobRequestScheduler scheduler) {
    this.scheduler = scheduler;
  }

  public void remindAt(long task, Instant at) {
    BackgroundJobRequest.schedule(at, new ReminderRequest(task));
  }

  public void remindNow(long task) {
    scheduler.enqueue(new ReminderRequest(task));
  }
}
