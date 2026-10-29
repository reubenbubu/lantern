package org.springframework.cloud.stream.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stand-in for Spring Cloud Stream's legacy listener annotation. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface StreamListener {
  String value() default "";

  String target() default "";
}
