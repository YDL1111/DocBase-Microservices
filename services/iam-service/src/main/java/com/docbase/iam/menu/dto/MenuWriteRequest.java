package com.docbase.iam.menu.dto;

/**
 * 菜单写请求（create/update）的公共视图，用于 Service 层统一校验字段不变量。
 *
 * <p>{@link CreateMenuRequest} 与 {@link UpdateMenuRequest} 共享此接口以复用字段不变量校验。
 * 注意：自状态变更收口到 {@code PUT /{menuId}/status} 专用端点后，
 * {@link UpdateMenuRequest} 不再携带 {@code status}，故 {@code status()} 不在此接口中；
 * 需要 {@code status()} 的校验（如 create）直接访问具体 DTO。
 */
public interface MenuWriteRequest {
    Long parentId();

    String menuName();

    Integer menuType();

    String routerName();

    String path();

    String permission();

    String metaInfo();

    Integer isButton();

    Integer sortNum();

    String remark();
}
