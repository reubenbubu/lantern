package org.springframework.integration.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.messaging.handler.annotation.MessageMapping;

/**
 * Stands in for a framework annotation that carries @MessageMapping as a meta-annotation, as
 * Spring's listener annotations do; the methods it marks do not handle STOMP messages.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@MessageMapping
public @interface ServiceActivator {}
