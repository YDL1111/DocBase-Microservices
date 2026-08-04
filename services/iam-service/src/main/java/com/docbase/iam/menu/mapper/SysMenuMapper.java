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
}
