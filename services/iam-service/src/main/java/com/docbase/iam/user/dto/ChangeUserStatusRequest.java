package com.docbase.iam.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for changing a user's status.
 *
 * <p>Status is strictly limited to 0 (disabled) or 1 (enabled). Any null,
 * out-of-range, or non-{0,1} value is rejected by Bean Validation before the
 * service layer is reached, so the database never receives an invalid status.
 */
public record ChangeUserStatusRequest(
        @NotNull(message = "status must not be null")
        @Min(value = 0, message = "status must be 0 or 1")
        @Max(value = 1, message = "status must be 0 or 1")
        Integer status) {
}
