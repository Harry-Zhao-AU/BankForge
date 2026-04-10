package au.com.bankforge.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Jakarta Bean Validation constraint for Australian BSB (Bank-State-Branch) numbers.
 *
 * Valid format: NNN-NNN (exactly 3 digits, hyphen, 3 digits)
 * Example valid BSBs: 012-345, 062-000, 733-000
 *
 * Applied at field or parameter level. Null values are rejected (T-1-02).
 */
@Constraint(validatedBy = BsbValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidBsb {

    String message() default "Invalid BSB format. Must be NNN-NNN (e.g., 012-345)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
