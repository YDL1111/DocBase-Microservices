package com.docbase.iam.role.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request DTO for assigning the full set of menus to a role.
 *
 * <p>This is an all-or-nothing replace: the supplied list becomes the role's complete
 * menu set. An empty list clears all menus. Duplicate IDs are deduplicated by the
 * service layer; the list is capped to prevent abuse.
 *
 * <p>元素同时使用 {@code @NotNull} + {@code @Positive}，拒绝 null / 非正数元素，
 * 与 {@link CreateRoleRequest} / {@link UpdateRoleRequest} 保持一致。
 */
public record AssignRoleMenusRequest(
        @NotNull(message = "menuIds must not be null (use an empty list to clear)")
        @Size(max = 500, message = "menuIds must not exceed 500 entries")
        List<@NotNull(message = "menuIds must not contain null elements")
             @Positive(message = "menuIds must contain only positive IDs") Long> menuIds) {
}
