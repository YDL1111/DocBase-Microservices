package com.docbase.iam.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.iam.user.domain.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
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

    /**
     * 查询所有"有效"超级管理员的 ID。
     *
     * "有效" = is_admin=1 AND status=1 AND deleted=0。调用方须在事务内、并在同一
     * 事务内已通过 {@link AdminMutexMapper#lockGuardRow()} 持有守卫行锁，从而保证
     * 读取时不会有并发事务同时修改有效管理员集合。
     */
    @Select("""
            SELECT user_id FROM sys_user
            WHERE is_admin = 1 AND status = 1 AND deleted = 0
            ORDER BY user_id""")
    List<Long> selectActiveAdminIds();

}
