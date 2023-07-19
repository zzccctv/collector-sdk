package com.datapipeline.collector.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MHistogram {
    String tag();

    String metric();

    String[] attribute() default {};

    String unit() default "";

    String description() default "";
}
