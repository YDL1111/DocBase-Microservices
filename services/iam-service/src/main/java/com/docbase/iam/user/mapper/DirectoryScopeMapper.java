package com.docbase.iam.user.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Set;

@Mapper
public interface DirectoryScopeMapper {
    record Grant(Long roleId, Integer dataScope, String permission) {}

    @Select("""
        SELECT DISTINCT r.role_id, r.data_scope, m.permission
        FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.role_id
        JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
        WHERE ur.user_id=#{userId} AND r.deleted=0 AND r.status=1 AND m.deleted=0 AND m.status=1
        """)
    List<Grant> grants(Long userId);

    @Select("""
        SELECT DISTINCT r.role_id, r.data_scope, m.permission
        FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.role_id
        JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
        WHERE ur.user_id=#{userId} AND r.deleted=0 AND m.deleted=0
        """)
    List<Grant> potentialGrants(Long userId);

    @Select("SELECT organization_id FROM sys_role_organization WHERE role_id=#{roleId}")
    List<Long> organizations(Long roleId);

    @Delete("DELETE FROM sys_role_organization WHERE role_id=#{roleId}")
    void clearOrganizations(Long roleId);

    @Insert("INSERT INTO sys_role_organization(role_id, organization_id) VALUES(#{roleId},#{organizationId})")
    void addOrganization(@Param("roleId") Long roleId, @Param("organizationId") Long organizationId);

    // Include dormant grants: resetting a disabled privileged account must not become an escalation path.
    @Select("""
        SELECT DISTINCT m.permission FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id
        JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
        WHERE ur.user_id=#{userId} AND r.deleted=0 AND m.deleted=0
        AND m.permission IS NOT NULL AND m.permission<>''
        """)
    Set<String> potentialPermissions(Long userId);
}
