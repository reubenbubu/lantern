package org.springframework.web.service.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@HttpExchange(method = "DELETE")
public @interface DeleteExchange {
  String value() default "";

  String url() default "";
}
