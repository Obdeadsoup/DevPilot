package com.obdeadsoup.devpilot.notification.config;
import jakarta.validation.Constraint;import jakarta.validation.Payload;import java.lang.annotation.*;
@Documented @Constraint(validatedBy=PositiveDurationValidator.class) @Target({ElementType.FIELD,ElementType.PARAMETER,ElementType.RECORD_COMPONENT}) @Retention(RetentionPolicy.RUNTIME)
public @interface PositiveDuration{String message() default "must be positive";Class<?>[] groups() default {};Class<? extends Payload>[] payload() default {};}
