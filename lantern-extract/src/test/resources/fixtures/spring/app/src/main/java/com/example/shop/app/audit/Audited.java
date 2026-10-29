package com.example.shop.app.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** A component that exists only when auditing. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Profile("audit")
public @interface Audited {}
