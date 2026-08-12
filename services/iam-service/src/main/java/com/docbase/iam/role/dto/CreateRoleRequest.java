package com.docbase.iam.role.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request DTO for creating a role.
 *
 * <p>{@code roleName} and {@code roleKey} are required and bounded in length to match
 * the database columns (VARCHAR(64) / VARCHAR(128)). {@code status} is restricted to
 * 0 (disabled) or 1 (enabled); {@code dataScope} must be a legal scope value (1-5, where
 * 1 = all data, 2 = custom, 3 = dept, 4 = dept and below, 5 = self); {@code roleSort} is
 * bounded to a non-negative, sane value. {@code menuIds} is capped in size (to match
 * {@link AssignRoleMenusRequest}) and each element must be a positive, non-null ID.
 *
 * <p>注意 {@code @Positive} 按 Bean Validation 规范会把 null 视为合法，因此必须与
 * {@code @NotNull} 组合使用，才能同时拒绝 null 元素（否则 null 会被
 * {@code RoleService#dedupMenuIds} 静默丢弃，造成"静默丢菜单"的假象）。
 */
public record CreateRoleRequest(
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

        @Min(value = 0, message = "status must be 0 or 1")
        @Max(value = 1, message = "status must be 0 or 1")
        Integer status,

        @Size(max = 512, message = "remark must not exceed 512 characters")
        String remark,

        @Size(max = 500, message = "menuIds must not exceed 500 entries")
        List<@NotNull(message = "menuIds must not contain null elements")
             @Positive(message = "menuIds must contain only positive IDs") Long> menuIds) {
}
