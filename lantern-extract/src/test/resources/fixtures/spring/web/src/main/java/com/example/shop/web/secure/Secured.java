package com.example.shop.web.secure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a method that needs a permission. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Secured {
  String value();
}
