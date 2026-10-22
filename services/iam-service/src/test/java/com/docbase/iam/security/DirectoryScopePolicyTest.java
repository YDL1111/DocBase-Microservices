package com.docbase.iam.security;

import com.docbase.common.core.BusinessException;
import com.docbase.iam.organization.domain.SysOrganization;
import com.docbase.iam.organization.mapper.SysOrganizationMapper;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.DirectoryScopeMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectoryScopePolicyTest {
    final DirectoryScopeMapper mapper = mock(DirectoryScopeMapper.class);
    final SysUserMapper users = mock(SysUserMapper.class);
    final SysOrganizationMapper organizations = mock(SysOrganizationMapper.class);
    final DirectoryScopePolicy policy = new DirectoryScopePolicy(mapper, users, organizations);
    final IamUserPrincipal caller = new IamUserPrincipal(1L,"manager",false,
            Set.of("system:user:list","system:user:update","system:user:reset-password"));

    SysUser user(long id, Long org) { SysUser u=new SysUser(); u.setUserId(id);u.setOrganizationId(org);return u; }
    @BeforeEach void prepare() { when(users.selectById(1L)).thenReturn(user(1,10L)); }
    void grant(int scope, String permission) { when(mapper.grants(1L)).thenReturn(List.of(new DirectoryScopeMapper.Grant(7L,scope,permission))); }

    @Test void departmentAllowsOwnDepartmentAndRejectsAnother() {
        grant(3,"system:user:list");
        assertDoesNotThrow(()->policy.assertTarget(caller,user(2,10L),"system:user:list"));
        assertThrows(BusinessException.class,()->policy.assertTarget(caller,user(3,20L),"system:user:list"));
    }
    @Test void knowledgeAllRoleCannotWidenPersonnelScope() {
        when(mapper.grants(1L)).thenReturn(List.of(new DirectoryScopeMapper.Grant(7L,3,"system:user:list"),
                new DirectoryScopeMapper.Grant(8L,1,"knowledge:base:list")));
        assertThrows(BusinessException.class,()->policy.assertTarget(caller,user(3,20L),"system:user:list"));
    }
    @Test void nullDepartmentDoesNotMatchUnassignedUsers() {
        when(users.selectById(1L)).thenReturn(user(1,null)); grant(3,"system:user:list");
        assertThrows(BusinessException.class,()->policy.assertTarget(caller,user(2,null),"system:user:list"));
    }
    @Test void descendantsAreIncludedWithoutSiblingOrganizations() {
        grant(4,"system:user:list");
        SysOrganization child=new SysOrganization(); child.setOrganizationId(11L);child.setParentId(10L);
        SysOrganization sibling=new SysOrganization(); sibling.setOrganizationId(20L);sibling.setParentId(0L);
        when(organizations.selectList(any())).thenReturn(List.of(child,sibling));
        assertDoesNotThrow(()->policy.assertTarget(caller,user(2,11L),"system:user:list"));
        assertThrows(BusinessException.class,()->policy.assertTarget(caller,user(3,20L),"system:user:list"));
    }
    @Test void selfScopeOnlyAllowsCaller() {
        grant(5,"system:user:list");
        assertDoesNotThrow(()->policy.assertTarget(caller,user(1,10L),"system:user:list"));
        assertThrows(BusinessException.class,()->policy.assertTarget(caller,user(2,10L),"system:user:list"));
    }
    @Test void customScopeUsesSelectedOrganizations() {
        grant(2,"system:user:list"); when(mapper.organizations(7L)).thenReturn(List.of(20L));
        assertDoesNotThrow(()->policy.assertTarget(caller,user(2,20L),"system:user:list"));
        assertThrows(BusinessException.class,()->policy.assertTarget(caller,user(3,10L),"system:user:list"));
    }
    @Test void roleCannotGrantAllPersonnelScopeToDepartmentManager() {
        grant(3,"system:user:list"); SysRole role=new SysRole();role.setRoleId(7L);role.setDataScope(1);
        assertThrows(BusinessException.class,()->policy.assertRoleGrant(caller,role,10L,Set.of("system:user:list")));
    }
    @Test void cannotResetAnAccountWithDormantHigherPrivileges() {
        when(mapper.potentialPermissions(2L)).thenReturn(Set.of("system:role:delete"));
        assertThrows(BusinessException.class,()->policy.assertPrivilegeBoundary(caller,user(2,10L)));
    }
    @Test void selfScopeCannotMoveCallerIntoAnotherDepartment() {
        grant(5,"system:user:update");
        assertThrows(BusinessException.class,()->policy.assertOrganizationChange(caller,20L));
    }
    @Test void emptyCustomScopeRejectedBeforeSaving() {
        var admin=new IamUserPrincipal(1L,"admin",true,Set.of("admin:all"));
        assertThrows(BusinessException.class,()->policy.configureScope(admin,null,2,List.of()));
        verify(mapper,never()).clearOrganizations(any());
    }
}
