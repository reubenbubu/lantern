package com.example.shop.app.jobs;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.springframework.stereotype.Service;

@Service
public class ReminderHandler implements JobRequestHandler<ReminderRequest> {

  @Override
  @Job(name = "Task's reminder job")
  public void run(ReminderRequest request) {}
}
