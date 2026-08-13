package com.docbase.iam.menu.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 菜单 owner（所有者角色）全量替换请求 DTO。
 *
 * <p>这是 all-or-nothing 替换：传入的 {@code roleIds} 成为该菜单完整的有效 owner
 * 集合。语义明确：
 * <ul>
 *   <li>非空列表：把菜单的管理归属完整替换为这些角色（转让语义）；</li>
 *   <li>空列表：表示超级管理员明确选择"系统托管"——该菜单不再归属任何普通角色，
 *       仅超级管理员可管理。绝不被解释为"权限清空"或"拒绝管理"。</li>
 * </ul>
 *
 * <p>owner 写入的是 sys_menu_owner_role（资源归属），<b>绝不</b>写入 sys_role_menu
 * （权限授权），因此本接口不会给任何角色成员新增 permission，不会造成权限扩散。
 *
 * <p>元素使用 {@code @NotNull} + {@code @Positive}：单独 {@code @Positive} 按 Bean
 * Validation 规范会把 null 视为合法，必须组合使用才能同时拒绝 null 元素（否则
 * null 会被去重逻辑静默丢弃）。列表上限与角色菜单授权一致，防止滥用。
 */
public record MenuOwnerRolesRequest(
        @NotNull(message = "roleIds must not be null (use an empty list for system-managed)")
        @Size(max = 100, message = "roleIds must not exceed 100 entries")
        List<@NotNull(message = "roleIds must not contain null elements")
             @Positive(message = "roleIds must contain only positive IDs") Long> roleIds) {
}
