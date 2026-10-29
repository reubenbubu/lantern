package com.example.shop.app.jobs;

import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportJob {

  @Scheduled(cron = "${shop.report-cron}")
  public void report() {}

  @Scheduled(fixedDelay = 5 * Schedules.MINUTE)
  public void cleanUp() {}

  @Scheduled(fixedRateString = "${shop.rate:30}", timeUnit = TimeUnit.SECONDS)
  public void poll() {}

  @Scheduled(cron = Schedules.HOURLY)
  @Scheduled(cron = "${SHOP_EXTRA_CRON}")
  public void twice() {}

  @Scheduled(cron = "0 0 0 * * *", zone = "${shop.api-token}")
  @Scheduled(fixedDelayString = "${shop.api-token}")
  public void secret() {}
}
