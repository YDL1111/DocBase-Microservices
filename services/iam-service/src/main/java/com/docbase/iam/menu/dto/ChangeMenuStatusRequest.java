package com.docbase.iam.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 启用/停用菜单请求 DTO。
 *
 * <p>Status 严格限定 0（停用）或 1（启用）。null 或非 0/1 值由 Bean Validation 在到达
 * Service 层之前拒绝。
 */
public record ChangeMenuStatusRequest(
        @NotNull(message = "status must not be null")
        @Min(value = 0, message = "status must be 0 or 1")
        @Max(value = 1, message = "status must be 0 or 1")
        Integer status) {
}
