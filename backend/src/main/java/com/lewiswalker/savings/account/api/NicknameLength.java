package com.lewiswalker.savings.account.api;

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
 * Length in characters, counted the way the database counts them.
 *
 * <p>{@code @Size} counts UTF-16 code units and Postgres {@code char_length} counts
 * characters, so three emoji are six to one and three to the other.
 */
@Documented
@Constraint(validatedBy = NicknameLength.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NicknameLength {

    int min();

    int max();

    String message() default "must be between {min} and {max} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NicknameLength, String> {

        private int min;
        private int max;

        @Override
        public void initialize(NicknameLength constraint) {
            this.min = constraint.min();
            this.max = constraint.max();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {   // absence is not a length problem, as with @Size
                return true;
            }
            int characters = value.codePointCount(0, value.length());
            return characters >= min && characters <= max;
        }
    }
}
