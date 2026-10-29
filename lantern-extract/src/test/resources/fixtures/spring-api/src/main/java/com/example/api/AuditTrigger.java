package com.example.api;

import org.jobrunr.scheduling.BackgroundJobRequest;

/** Schedules a job whose handler is in another repository. */
public class AuditTrigger {

  public void auditNow() {
    BackgroundJobRequest.enqueue(new AuditRequest());
  }

  public void auditNightly() {
    BackgroundJobRequest.scheduleRecurrently("audit", "0 0 1 * * *", new AuditRequest());
  }
}
