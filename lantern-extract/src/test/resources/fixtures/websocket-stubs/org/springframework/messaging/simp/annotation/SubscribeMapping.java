package org.springframework.messaging.simp.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SubscribeMapping {
  String[] value() default {};
}
