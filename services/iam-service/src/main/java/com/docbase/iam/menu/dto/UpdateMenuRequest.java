package com.docbase.iam.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新菜单请求 DTO。
 *
 * <p>字段约束与 {@link CreateMenuRequest} 一致。{@code menuId} 来自路径参数，不在 DTO 中。
 * 客户端不可写 status / isSystem / deleted 等系统字段：
 * <ul>
 *   <li>{@code status} 不在此 DTO 中，状态变更必须走 PUT /{menuId}/status 专用端点，
 *       以避免普通更新接口绕过"停用含启用子节点的目录"等状态专用校验。</li>
 *   <li>{@code isSystem} / {@code deleted} 为服务端维护字段，不允许客户端写入。</li>
 * </ul>
 */
public record UpdateMenuRequest(
        @NotNull(message = "parentId must not be null")
        @Min(value = 0, message = "parentId must be >= 0")
        Long parentId,

        @NotBlank(message = "menuName must not be blank")
        @Size(max = 64, message = "menuName must not exceed 64 characters")
        String menuName,

        @NotNull(message = "menuType must not be null")
        @Min(value = 1, message = "menuType must be 1 (menu), 2 (directory) or 3 (button)")
        @Max(value = 3, message = "menuType must be 1 (menu), 2 (directory) or 3 (button)")
        Integer menuType,

        @Size(max = 128, message = "routerName must not exceed 128 characters")
        String routerName,

        @Size(max = 255, message = "path must not exceed 255 characters")
        String path,

        @Size(max = 128, message = "permission must not exceed 128 characters")
        String permission,

        @Size(max = 1024, message = "metaInfo must not exceed 1024 characters")
        String metaInfo,

        @NotNull(message = "isButton must not be null")
        @Min(value = 0, message = "isButton must be 0 or 1")
        @Max(value = 1, message = "isButton must be 0 or 1")
        Integer isButton,

        @NotNull(message = "sortNum must not be null")
        @Min(value = 0, message = "sortNum must not be negative")
        @Max(value = 9999, message = "sortNum must not exceed 9999")
        Integer sortNum,

        @Size(max = 512, message = "remark must not exceed 512 characters")
        String remark) implements MenuWriteRequest {
}
