package com.example.shop.web.secure;

import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PermissionAspect {

  @Before("@annotation(secured)")
  public void check(Secured secured) {}

  @Pointcut("execution(* com.example.shop.web.api.CatalogController.*(..))")
  void catalog() {}

  @Around("catalog() && !execution(* *.notMapped(..))")
  public Object time(Object joinPoint) {
    return joinPoint;
  }

  @After("@annotation(com.example.shop.web.secure.Secured) && args(..)")
  public void release() {}

  @Pointcut("@annotation(com.example.shop.web.secure.Secured)")
  void securedCall() {}

  @AfterReturning("securedCall()")
  public void audit() {}
}
