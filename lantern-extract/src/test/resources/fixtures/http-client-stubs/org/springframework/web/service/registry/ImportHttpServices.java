package org.springframework.web.service.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ImportHttpServices.Container.class)
public @interface ImportHttpServices {
  Class<?>[] value() default {};

  Class<?>[] types() default {};

  String group() default HttpServiceGroup.DEFAULT_GROUP_NAME;

  Class<?>[] basePackageClasses() default {};

  String[] basePackages() default {};

  HttpServiceGroup.ClientType clientType() default HttpServiceGroup.ClientType.UNSPECIFIED;

  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @interface Container {
    ImportHttpServices[] value();
  }
}
