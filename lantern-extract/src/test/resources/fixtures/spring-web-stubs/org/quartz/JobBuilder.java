package org.quartz;

public class JobBuilder {
  public static JobBuilder newJob(Class<? extends Job> job) {
    return new JobBuilder();
  }

  public JobBuilder withIdentity(String name) {
    return this;
  }

  public JobDetail build() {
    return null;
  }
}
