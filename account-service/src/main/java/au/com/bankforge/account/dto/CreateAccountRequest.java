package au.com.bankforge.account.dto;

import au.com.bankforge.common.validation.ValidBsb;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Request DTO for account creation.
 *
 * bsb — Australian Bank State Branch number, validated via @ValidBsb (format: NNN-NNN)
 * accountNumber — 6-10 digit numeric string
 * initialBalance — optional starting balance; defaults to zero in AccountService
 *
 * All monetary fields use BigDecimal (D-09 — never double/float).
 */
public record CreateAccountRequest(
        @ValidBsb
        String bsb,

        @NotBlank
        @Pattern(regexp = "^\\d{6,10}$", message = "Account number must be 6 to 10 digits")
        String accountNumber,

        @NotBlank
        String accountName,

        @DecimalMin(value = "0.00", message = "Initial balance cannot be negative")
        BigDecimal initialBalance
) {}
