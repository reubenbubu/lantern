package com.example.shop.web.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.web.bind.annotation.RestController;

/** Marks the shop's public REST controllers. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@RestController
public @interface WebApi {}
