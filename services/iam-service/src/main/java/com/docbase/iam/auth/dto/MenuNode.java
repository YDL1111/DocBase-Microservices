package com.docbase.iam.auth.dto;

import java.util.List;

public record MenuNode(
        Long menuId,
        Long parentId,
        String menuName,
        String routerName,
        String path,
        String permission,
        Integer menuType,
        Integer isButton,
        Integer sortNum,
        String metaInfo,
        List<MenuNode> children
) {
}
