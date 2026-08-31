package com.docbase.iam.auth;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.dto.RegisterRequest;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.mapper.SysRoleMapper;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegistrationServiceTest {

    @Test
    void register_AlwaysAssignsFixedMinimalRoleWithoutAdminOrOrganization() {
        RegistrationProperties properties = new RegistrationProperties(true, "registered_user");
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("password123")).thenReturn("bcrypt-hash");
        SysRole role = new SysRole();
        role.setRoleId(9L);
        role.setRoleKey("registered_user");
        role.setStatus(1);
        when(roleMapper.selectOne(any(Wrapper.class))).thenReturn(role);
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setUserId(21L);
            return 1;
        }).when(userMapper).insert(any(SysUser.class));

        RegistrationService service = new RegistrationService(
                properties, userMapper, roleMapper, userRoleMapper, encoder);
        Long userId = service.register(new RegisterRequest(
                "alice", "Alice", "password123", "alice@example.com"));

        assertEquals(21L, userId);
        verify(userMapper).insert(argThat((SysUser user) -> user.getIsAdmin() == 0
                && user.getOrganizationId() == null
                && "bcrypt-hash".equals(user.getPassword())));
        verify(userRoleMapper).insert(argThat((SysUserRole link) -> link.getUserId().equals(21L)
                && link.getRoleId().equals(9L)));
    }

    @Test
    void register_RejectsWhenFeatureIsDisabled() {
        RegistrationService service = new RegistrationService(
                new RegistrationProperties(false, "registered_user"),
                mock(SysUserMapper.class), mock(SysRoleMapper.class),
                mock(SysUserRoleMapper.class), mock(PasswordEncoder.class));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.register(
                new RegisterRequest("alice", "Alice", "password123", "")));
        assertEquals("REGISTRATION_DISABLED", exception.code());
    }
}
