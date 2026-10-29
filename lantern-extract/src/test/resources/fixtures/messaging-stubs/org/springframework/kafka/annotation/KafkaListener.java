package org.springframework.kafka.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stand-in for Spring Kafka's annotation, for compiling the messaging fixture. */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface KafkaListener {
  String id() default "";

  String[] topics() default {};

  String topicPattern() default "";

  String groupId() default "";
}
