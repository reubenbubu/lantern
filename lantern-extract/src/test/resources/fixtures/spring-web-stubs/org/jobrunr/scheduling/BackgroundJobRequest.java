package org.jobrunr.scheduling;

import java.time.Instant;
import org.jobrunr.jobs.lambdas.JobRequest;

public class BackgroundJobRequest {
  public static void enqueue(JobRequest request) {}

  public static void schedule(Instant at, JobRequest request) {}

  public static void scheduleRecurrently(String id, String cron, JobRequest request) {}
}
