package com.docbase.iam.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.iam.user.domain.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Set;

public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 根据用户ID查询权限标识集合（去重）。
     * 遍历用户所有角色 -> 角色关联菜单 -> 菜单的 permission 字段。
     */
    @Select("""
            SELECT DISTINCT m.permission
            FROM sys_menu m
            JOIN sys_role_menu rm ON m.menu_id = rm.menu_id
            JOIN sys_user_role ur ON rm.role_id = ur.role_id
            JOIN sys_role r ON r.role_id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND m.deleted = 0 AND m.status = 1
              AND r.deleted = 0 AND r.status = 1
              AND m.permission IS NOT NULL AND m.permission != ''""")
    Set<String> selectPermissionsByUserId(@Param("userId") Long userId);

    /**
     * 根据用户ID查询关联的角色ID集合。
     */
    @Select("""
            SELECT ur.role_id
            FROM sys_user_role ur
            JOIN sys_role r ON r.role_id = ur.role_id
            WHERE ur.user_id = #{userId} AND r.deleted = 0""")
    Set<Long> selectRoleIdsByUserId(@Param("userId") Long userId);
}
