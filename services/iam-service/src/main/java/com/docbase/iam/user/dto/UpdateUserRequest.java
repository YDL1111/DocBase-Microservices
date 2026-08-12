package com.docbase.iam.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request DTO for updating an existing user. username/password are intentionally
 * absent: the update flow never changes credentials, and the service ignores
 * them anyway. Enforcing @NotBlank on these (as the old shared DTO did) would
 * force the client to resend or forge a password on every edit — a contract bug.
 *
 * <p>roleIds is nullable: null means "leave roles unchanged" (the service skips
 * reassignment), while an explicit [] clears all roles. 非 null 时限制最多 20 个角色，
 * 每个元素必须为正数且非 null；重复 ID 由 {@code UserService#assignRoles} 在授权
 * 校验前去重。
 */
public record UpdateUserRequest(
        String nickname,
        String email,
        String phoneNumber,
        Integer sex,
        String remark,
        @Size(max = 20, message = "roleIds must not exceed 20 entries")
        List<@NotNull(message = "roleIds must not contain null elements")
             @Positive(message = "roleIds must contain only positive IDs") Long> roleIds) {
}
