package com.docbase.iam.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.iam.menu.domain.SysMenu;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /**
     * 查询用户可见的菜单集合（去重），用于构建菜单树。
     */
    @Select("""
            SELECT DISTINCT m.*
            FROM sys_menu m
            JOIN sys_role_menu rm ON m.menu_id = rm.menu_id
            JOIN sys_user_role ur ON rm.role_id = ur.role_id
            JOIN sys_role r ON r.role_id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND m.deleted = 0 AND m.status = 1
              AND r.deleted = 0 AND r.status = 1
            ORDER BY m.parent_id, m.sort_num""")
    List<SysMenu> selectMenusByUserId(@Param("userId") Long userId);

    /**
     * 根据角色ID查询关联的菜单ID集合。
     */
    @Select("SELECT menu_id FROM sys_role_menu WHERE role_id = #{roleId}")
    Set<Long> selectMenuIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 统计指定父菜单下的子节点数量（仅未删除节点）。
     *
     * 用于删除前置校验：有子节点的菜单不得删除。
     */
    @Select("SELECT COUNT(menu_id) FROM sys_menu WHERE deleted = 0 AND parent_id = #{parentId}")
    int countChildren(@Param("parentId") Long parentId);

    /**
     * 查询指定父菜单下的直接子菜单 ID 列表（仅未删除节点）。
     *
     * 用于循环校验：向下遍历子树，检测"把祖先移到后代"的非法操作。
     */
    @Select("SELECT menu_id FROM sys_menu WHERE deleted = 0 AND parent_id = #{parentId}")
    Set<Long> selectChildIds(@Param("parentId") Long parentId);

    /**
     * 统计指定父菜单下"启用"的子节点数量（未删除且 status=1）。
     *
     * 用于停用前置校验：有启用子节点的目录/菜单不得停用，避免把启用的子节点
     * 提升为孤儿根（selectMenusByUserId 只过滤 status=1，停用父节点后其启用子节点
     * 会作为根节点暴露给前端）。
     */
    @Select("SELECT COUNT(menu_id) FROM sys_menu WHERE deleted = 0 AND status = 1 AND parent_id = #{parentId}")
    int countEnabledChildren(@Param("parentId") Long parentId);

    /**
     * 查询关联了指定菜单的全部用户的 ID（去重）。
     *
     * 通过 sys_role_menu → sys_user_role 串联，找出所有拥有包含该菜单的角色的用户。
     * 在删除/停用菜单前调用，以便精确失效这些用户的权限缓存与 access token，
     * 避免全局 SCAN。
     */
    @Select("""
            SELECT DISTINCT ur.user_id
            FROM sys_user_role ur
            JOIN sys_role_menu rm ON ur.role_id = rm.role_id
            WHERE rm.menu_id = #{menuId}""")
    Set<Long> selectUserIdsByMenuId(@Param("menuId") Long menuId);

    /**
     * 查询指定 ID 列表中真正"有效"（未删除且启用）的菜单数量。
     *
     * 用于角色授权前的校验：调用方传入候选 menuId 集合，若返回数量与候选数量不一致，
     * 说明存在不存在、已删除或已停用的菜单，应拒绝整批授权。
     */
    @Select("<script>" +
            "SELECT COUNT(menu_id) FROM sys_menu " +
            "WHERE deleted = 0 AND status = 1 " +
            "AND menu_id IN " +
            "<foreach collection='menuIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    int countValidMenus(@Param("menuIds") java.util.Collection<Long> menuIds);

    /**
     * 查询指定"有效"菜单的权限标识集合（去重，排除空串）。
     *
     * 用于角色授权时的"权限子集"校验：非超级管理员只能授予自身已拥有的权限，
     * 因此需要读取候选菜单的 permission 字段，确认它们都是调用者权限的子集。
     * 同时可检出是否有人试图通过菜单取得 admin:all。
     */
    @Select("<script>" +
            "SELECT DISTINCT permission FROM sys_menu " +
            "WHERE deleted = 0 AND status = 1 " +
            "AND permission IS NOT NULL AND permission != '' " +
            "AND menu_id IN " +
            "<foreach collection='menuIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    Set<String> selectPermissionsByMenuIds(@Param("menuIds") java.util.Collection<Long> menuIds);

    /**
     * 查询指定菜单集合的权限标识集合（去重，排除空串），包含启用与停用的菜单（仅排除已删除）。
     *
     * <p>用于资源归属校验：空 permission 的结构节点（目录/菜单）的归属由其全部未删除后代的
     * permission 共同决定。不能带 status=1 条件——否则调用者可能趁高权限后代停用时
     * 移动、停用或修改其父目录，之后这些后代仍会被重新启用，造成归属校验被绕过。
     * 角色授权校验仍使用 {@link #selectPermissionsByMenuIds}（只取启用菜单），二者职责不同。
     */
    @Select("<script>" +
            "SELECT DISTINCT permission FROM sys_menu " +
            "WHERE deleted = 0 " +
            "AND permission IS NOT NULL AND permission != '' " +
            "AND menu_id IN " +
            "<foreach collection='menuIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    Set<String> selectPermissionsByMenuIdsIgnoreStatus(@Param("menuIds") java.util.Collection<Long> menuIds);

}
