package com.docbase.iam.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.iam.menu.domain.SysMenuOwnerRole;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Set;

/**
 * 菜单所有者角色归属的 Mapper。
 *
 * <p>与 {@link com.docbase.iam.role.mapper.SysRoleMenuMapper} 严格分开：
 * 本表只记录"谁有权管理这个菜单"，不参与用户权限集计算。
 */
public interface SysMenuOwnerRoleMapper extends BaseMapper<SysMenuOwnerRole> {

    /**
     * 统计调用者通过其<b>有效</b>角色关联到指定菜单的"所有者"链接数量。
     *
     * <p>用于 {@code assertOwnsMenuViaRole}：与菜单启用状态解耦，避免管理员停用自己负责的
     * 唯一权限菜单后无法重新启用（自锁）。
     *
     * <p>必须内连接 sys_role 并过滤 status=1、deleted=0——否则用户通过已停用/已删除角色
     * 的历史关联仍会被视为所有者。
     */
    @Select("""
            SELECT COUNT(*) FROM sys_menu_owner_role mor
            JOIN sys_user_role ur ON ur.role_id = mor.role_id AND ur.user_id = #{userId}
            JOIN sys_role r ON r.role_id = mor.role_id AND r.status = 1 AND r.deleted = 0
            WHERE mor.menu_id = #{menuId}""")
    int countOwnerLinks(@Param("userId") Long userId, @Param("menuId") Long menuId);

    /**
     * 查询关联到指定菜单、且由调用者持有的<b>有效</b>所有者角色 ID 集合。
     *
     * <p>用于 {@code create} 时从父节点继承归属：把父节点中调用者持有的有效所有者角色
     * 复制到新菜单，使创建者后续仍能通过角色归属管理该菜单。返回空集表示父节点无调用者
     * 可继承的所有者角色——此时应拒绝非超级管理员创建（无法确定归属）。
     */
    @Select("""
            SELECT DISTINCT mor.role_id FROM sys_menu_owner_role mor
            JOIN sys_user_role ur ON ur.role_id = mor.role_id AND ur.user_id = #{userId}
            JOIN sys_role r ON r.role_id = mor.role_id AND r.status = 1 AND r.deleted = 0
            WHERE mor.menu_id = #{menuId}""")
    Set<Long> selectOwnerRoleIdsLinkedToMenu(@Param("userId") Long userId, @Param("menuId") Long menuId);

    /**
     * 查询指定菜单的<b>有效</b>所有者角色 ID 集合（去重）。
     *
     * <p>用于 owner 查询接口：与"有效 owner"语义一致——已停用（status=0）或已删除
     * （deleted=1）的角色不再算有效 owner，不在此返回（其残留归属行会在下次 owner
     * 替换或角色删除时被清理）。
     */
    @Select("""
            SELECT DISTINCT mor.role_id FROM sys_menu_owner_role mor
            JOIN sys_role r ON r.role_id = mor.role_id AND r.status = 1 AND r.deleted = 0
            WHERE mor.menu_id = #{menuId}""")
    Set<Long> selectOwnerRoleIds(@Param("menuId") Long menuId);

    /**
     * 查询"指定角色是唯一有效所有者"的未删除菜单 ID 集合。
     *
     * <p>用于角色删除/停用前的生命周期校验：若该角色是某未删除菜单的最后一个有效
     * owner，删除/停用会让该菜单变成普通管理员无法管理的孤儿资源，应拒绝
     * （{@code ROLE_LAST_MENU_OWNER}）。
     *
     * <p>"唯一有效 owner" 的判定：该菜单存在 (menu, 该角色) 归属行，该角色自身有效
     * （status=1 且 deleted=0），且不存在<b>其它</b>有效角色（status=1 且 deleted=0）
     * 的归属行。因此已停用/已删除的备用角色不被计入——若唯一备用角色已停用/已删除，
     * 该菜单仍会被返回，删除/停用目标角色仍应拒绝。
     */
    @Select("""
            SELECT mor.menu_id FROM sys_menu_owner_role mor
            JOIN sys_menu m ON m.menu_id = mor.menu_id AND m.deleted = 0
            JOIN sys_role target ON target.role_id = mor.role_id
            WHERE mor.role_id = #{roleId}
              AND target.status = 1 AND target.deleted = 0
              AND NOT EXISTS (
                  SELECT 1 FROM sys_menu_owner_role other
                  JOIN sys_role r2 ON r2.role_id = other.role_id AND r2.status = 1 AND r2.deleted = 0
                  WHERE other.menu_id = mor.menu_id
                    AND other.role_id <> #{roleId}
              )""")
    java.util.List<Long> selectMenusWhereRoleIsLastOwner(@Param("roleId") Long roleId);

    /**
     * 统计指定 ID 列表中真正"有效"（未删除且启用）的角色数量。
     *
     * <p>用于 owner 全量替换前的校验：候选 roleIds 全部必须存在、启用、未删除，
     * 否则拒绝整批替换（与 {@code countValidMenus} 的整批语义一致）。
     */
    @Select("<script>" +
            "SELECT COUNT(role_id) FROM sys_role " +
            "WHERE deleted = 0 AND status = 1 " +
            "AND role_id IN " +
            "<foreach collection='roleIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    int countValidOwnerRoles(@Param("roleIds") java.util.Collection<Long> roleIds);
}
