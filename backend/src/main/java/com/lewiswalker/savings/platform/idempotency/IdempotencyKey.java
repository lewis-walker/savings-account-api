package com.lewiswalker.savings.platform.idempotency;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A well-formed idempotency key.
 */
@Documented
@Constraint(validatedBy = IdempotencyKey.Validator.class)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotencyKey {

    String message() default "must be 8 to 128 characters of A-Z, a-z, 0-9, underscore or hyphen";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<IdempotencyKey, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || IdempotencyStore.isAcceptable(value);
        }
    }
}
