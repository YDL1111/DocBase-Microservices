package com.docbase.iam.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建菜单请求 DTO。
 *
 * <p>字段长度与 {@code sys_menu} 列一致，避免写入时截断。
 * {@code menuType} 限定 1=菜单 2=目录 3=按钮；{@code status} 限定 0=停用 1=启用；
 * {@code isButton} 限定 0/1；{@code sortNum} 限定非负且不超过 9999。
 *
 * <p>客户端不可写 menuId / isSystem / deleted 等系统字段——它们不在此 DTO 中。
 * 格式校验（routerName/path/permission 正则、metaInfo JSON、节点类型不变量）在
 * MenuService 中执行，以便返回明确的业务错误码。
 */
public record CreateMenuRequest(
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

        @Min(value = 0, message = "isButton must be 0 or 1")
        @Max(value = 1, message = "isButton must be 0 or 1")
        Integer isButton,

        @Min(value = 0, message = "sortNum must not be negative")
        @Max(value = 9999, message = "sortNum must not exceed 9999")
        Integer sortNum,

        @Min(value = 0, message = "status must be 0 or 1")
        @Max(value = 1, message = "status must be 0 or 1")
        Integer status,

        @Size(max = 512, message = "remark must not exceed 512 characters")
        String remark) implements MenuWriteRequest {
}
