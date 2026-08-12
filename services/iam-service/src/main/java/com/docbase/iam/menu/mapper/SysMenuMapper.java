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
}
