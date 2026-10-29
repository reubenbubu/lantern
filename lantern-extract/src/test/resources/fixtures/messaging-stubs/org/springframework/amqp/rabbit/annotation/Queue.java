package org.springframework.amqp.rabbit.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stand-in for Spring AMQP's annotation, for compiling the messaging fixture. */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface Queue {
  String value() default "";

  String name() default "";
}
