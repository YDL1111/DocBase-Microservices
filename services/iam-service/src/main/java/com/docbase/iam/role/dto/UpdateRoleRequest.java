package com.docbase.iam.role.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request DTO for updating an existing role's basic fields and menu assignment.
 *
 * <p>All scalar fields follow the same length / range rules as creation. The menu
 * assignment is treated as a full replace: passing {@code menuIds} (even an empty list)
 * replaces the role's whole menu set in a single transaction. Passing {@code null}
 * leaves the menu assignment untouched.
 *
 * <p>menuIds 元素同时使用 {@code @NotNull} + {@code @Positive}，原因同
 * {@link CreateRoleRequest}：单独 {@code @Positive} 会把 null 视为合法。
 */
public record UpdateRoleRequest(
        @NotBlank(message = "roleName must not be blank")
        @Size(max = 64, message = "roleName must not exceed 64 characters")
        String roleName,

        @NotBlank(message = "roleKey must not be blank")
        @Size(max = 128, message = "roleKey must not exceed 128 characters")
        String roleKey,

        @Min(value = 0, message = "roleSort must not be negative")
        @Max(value = 9999, message = "roleSort must not exceed 9999")
        Integer roleSort,

        @Min(value = 1, message = "dataScope must be at least 1")
        @Max(value = 5, message = "dataScope must not exceed 5")
        Integer dataScope,

        @Size(max = 512, message = "remark must not exceed 512 characters")
        String remark,

        /** Null = leave menus unchanged; non-null = full replace. */
        @Size(max = 500, message = "menuIds must not exceed 500 entries")
        List<@NotNull(message = "menuIds must not contain null elements")
             @Positive(message = "menuIds must contain only positive IDs") Long> menuIds) {
}
