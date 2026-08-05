package com.obdeadsoup.devpilot.notification.config;
import jakarta.validation.ConstraintValidator;import jakarta.validation.ConstraintValidatorContext;import java.time.Duration;
public class PositiveDurationValidator implements ConstraintValidator<PositiveDuration,Duration>{public boolean isValid(Duration v,ConstraintValidatorContext c){return v!=null&&!v.isZero()&&!v.isNegative();}}
