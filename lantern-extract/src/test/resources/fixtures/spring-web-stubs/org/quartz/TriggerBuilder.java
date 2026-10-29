package org.quartz;

public class TriggerBuilder {
  public static TriggerBuilder newTrigger() {
    return new TriggerBuilder();
  }

  public TriggerBuilder withSchedule(CronScheduleBuilder schedule) {
    return this;
  }

  public Trigger build() {
    return null;
  }
}
