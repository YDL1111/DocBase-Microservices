package com.docbase.iam.organization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrganizationRequest(
        @NotNull @Min(0) Long parentId,
        @NotBlank @Size(max = 128) String organizationName,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-z][a-z0-9_-]{1,63}$") String organizationCode,
        @NotNull @Min(0) @Max(9999) Integer sortNum,
        @NotNull @Min(0) @Max(1) Integer status,
        @Size(max = 512) String remark) {
}
