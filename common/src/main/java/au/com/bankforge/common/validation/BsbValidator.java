package au.com.bankforge.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * Validates that a string conforms to the Australian BSB format: NNN-NNN.
 *
 * Rules:
 * - Null is invalid (null rejection per T-1-02)
 * - Must match exactly ^\\d{3}-\\d{3}$ — no leading/trailing whitespace accepted
 */
public class BsbValidator implements ConstraintValidator<ValidBsb, String> {

    static final Pattern BSB_PATTERN = Pattern.compile("^\\d{3}-\\d{3}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        return BSB_PATTERN.matcher(value).matches();
    }
}
