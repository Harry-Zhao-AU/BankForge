package au.com.bankforge.common.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the BSB format validator.
 *
 * No Spring context required — ConstraintValidator is a plain Java class.
 */
class BsbValidatorTest {

    private BsbValidator validator;

    @BeforeEach
    void setUp() {
        validator = new BsbValidator();
        validator.initialize(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"012-345", "000-000", "999-999", "062-000", "733-000"})
    @DisplayName("Valid BSB formats are accepted")
    void validBsb(String bsb) {
        assertThat(validator.isValid(bsb, null)).isTrue();
    }

    @Test
    @DisplayName("Null is rejected")
    void nullIsRejected() {
        assertThat(validator.isValid(null, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "12-345",      // too few digits before hyphen
            "0123-456",    // too many digits before hyphen
            "abc-def",     // letters instead of digits
            "012345",      // no hyphen
            "",            // empty string
            "012-34",      // too few digits after hyphen
            "012-3456",    // too many digits after hyphen
            " 012-345",    // leading whitespace
            "012-345 ",    // trailing whitespace
            "012_345",     // underscore instead of hyphen
            "012 345"      // space instead of hyphen
    })
    @DisplayName("Invalid BSB formats are rejected")
    void invalidBsb(String bsb) {
        assertThat(validator.isValid(bsb, null)).isFalse();
    }
}
