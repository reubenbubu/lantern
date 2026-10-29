package org.springframework.jms.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stand-in for Spring JMS's annotation, for compiling the messaging fixture. */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface JmsListener {
  String id() default "";

  String destination();
}
