package com.lewiswalker.savings.account;

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
 * <p>{@code @Size} counts {@code String.length()}, which is UTF-16 code units. Postgres
 * {@code char_length} counts characters. For anything outside the Basic Multilingual
 * Plane — emoji, most obviously — those disagree by a factor of two, and the disagreement
 * runs the wrong way: a nickname of three emoji is six units to Bean Validation and three
 * characters to Postgres, so it passes the edge and violates the table.
 *
 * <p>That is not a cosmetic difference. An input error that reaches the database arrives
 * as a constraint violation rather than a 400, and a constraint violation carries the
 * failing row — including the customer's name — into whatever handles it.
 *
 * <p>Counting code points here makes the two agree, so the edge rejects everything the
 * table would.
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
            // Null passes, as @Size does. The nickname is optional; absence is not a
            // length problem and @NotNull is the annotation for saying otherwise.
            if (value == null) {
                return true;
            }
            int characters = value.codePointCount(0, value.length());
            return characters >= min && characters <= max;
        }
    }
}
