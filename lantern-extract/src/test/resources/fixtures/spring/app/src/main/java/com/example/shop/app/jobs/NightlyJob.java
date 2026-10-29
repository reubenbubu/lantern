package com.example.shop.app.jobs;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.stereotype.Component;

@Component
public class NightlyJob {

  @Recurring(id = "nightly", cron = "${shop.report-cron}")
  @Job(name = "Nightly clean-up")
  public void cleanUp() {}

  @Recurring(id = "poll", interval = "PT5M")
  public void poll() {}

  @Job(name = "Manual re-index")
  public void reindex() {}
}
