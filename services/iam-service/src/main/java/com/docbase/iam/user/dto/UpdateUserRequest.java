package com.docbase.iam.user.dto;

import java.util.List;

/**
 * Request DTO for updating an existing user. username/password are intentionally
 * absent: the update flow never changes credentials, and the service ignores
 * them anyway. Enforcing @NotBlank on these (as the old shared DTO did) would
 * force the client to resend or forge a password on every edit — a contract bug.
 *
 * roleIds is nullable: null means "leave roles unchanged" (the service skips
 * reassignment), while an explicit [] clears all roles.
 */
public record UpdateUserRequest(
        String nickname,
        String email,
        String phoneNumber,
        Integer sex,
        String remark,
        List<Long> roleIds) {
}
