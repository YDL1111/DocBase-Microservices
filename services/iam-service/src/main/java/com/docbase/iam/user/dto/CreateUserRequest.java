package com.docbase.iam.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request DTO for creating a user. Both username and password are required
 * (@NotBlank) because a new account must be created with credentials.
 *
 * <p>{@code roleIds} 限制最多 20 个角色（防超大集合触发逐角色 N+1 授权查询），
 * 每个元素必须为正数且非 null；重复 ID 由 {@code UserService#assignRoles} 在授权
 * 校验前去重，数据库唯一键 (user_id, role_id) 继续作为最终防线。
 */
public record CreateUserRequest(
        @NotBlank String username,
        String nickname,
        @NotBlank String password,
        String email,
        String phoneNumber,
        Integer sex,
        Integer status,
        String remark,
        @Size(max = 20, message = "roleIds must not exceed 20 entries")
        List<@NotNull(message = "roleIds must not contain null elements")
             @Positive(message = "roleIds must contain only positive IDs") Long> roleIds) {
}
