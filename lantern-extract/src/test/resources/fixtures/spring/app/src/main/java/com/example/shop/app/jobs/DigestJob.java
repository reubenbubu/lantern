package com.example.shop.app.jobs;

import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

public class DigestJob extends QuartzJobBean {

  @Override
  protected void executeInternal(JobExecutionContext context) {}
}
