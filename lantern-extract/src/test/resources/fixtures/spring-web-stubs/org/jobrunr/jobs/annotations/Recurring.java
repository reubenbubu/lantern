package org.jobrunr.jobs.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Recurring {
  String id() default "";

  String cron() default "";

  String interval() default "";
}
