package com.example.live;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.messaging.handler.annotation.MessageMapping;

/** The application's own annotation carrying @MessageMapping. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@MessageMapping("/live")
public @interface Live {}
