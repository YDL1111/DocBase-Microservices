package com.docbase.iam.menu.domain;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 菜单所有者角色归属关系。
 *
 * <p>表达"哪个角色拥有管理某菜单的权利"（启停/修改/删除），与 {@code sys_role_menu}
 * 的职责严格分开：
 * <ul>
 *   <li>{@code sys_role_menu}：菜单可见性 + permission 授权，直接参与用户权限集计算，
 *       必须通过角色授权接口显式变更。</li>
 *   <li>本表（{@code sys_menu_owner_role}）：资源归属，仅用于 {@code assertOwnsMenuViaRole}
 *       等资源级授权校验，<b>不参与权限集计算</b>，不会因写入而给角色成员新增 permission。</li>
 * </ul>
 *
 * <p>创建菜单时，从父节点的所有者角色中继承调用者持有的有效角色写入本表。
 * 删除菜单或删除角色时，必须显式清理本表的对应行（角色采用逻辑删除，数据库外键
 * ON DELETE CASCADE 不会被触发，故清理逻辑放在 Service 层）。
 */
@TableName("sys_menu_owner_role")
public class SysMenuOwnerRole {

    private Long menuId;

    private Long roleId;

    public SysMenuOwnerRole() {}

    public SysMenuOwnerRole(Long menuId, Long roleId) {
        this.menuId = menuId;
        this.roleId = roleId;
    }

    public Long getMenuId() { return menuId; }
    public void setMenuId(Long menuId) { this.menuId = menuId; }
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
}
