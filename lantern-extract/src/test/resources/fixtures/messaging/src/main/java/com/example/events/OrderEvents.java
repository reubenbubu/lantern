package com.example.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.kafka.annotation.KafkaListener;

/** A custom annotation carrying a listener. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@KafkaListener(topics = "orders", groupId = "orders")
public @interface OrderEvents {}
