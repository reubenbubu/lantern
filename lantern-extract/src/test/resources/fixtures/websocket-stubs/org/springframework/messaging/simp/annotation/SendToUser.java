package org.springframework.messaging.simp.annotation;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SendToUser {
  String[] value() default {};

  boolean broadcast() default true;
}
