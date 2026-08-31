package com.docbase.iam.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.dto.RegisterRequest;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.mapper.SysRoleMapper;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class RegistrationService {
    private final RegistrationProperties properties;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(RegistrationProperties properties, SysUserMapper userMapper,
                               SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
                               PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean enabled() { return properties.enabled(); }

    @Transactional
    public Long register(RegisterRequest request) {
        if (!properties.enabled()) throw new BusinessException("REGISTRATION_DISABLED", "registration is disabled");
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException("PASSWORD_TOO_LONG", "password exceeds bcrypt byte limit");
        }
        String username = request.username().trim();
        if (userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", username)) > 0) {
            throw new BusinessException("USERNAME_EXISTS", "username already exists");
        }
        String email = request.email() == null ? "" : request.email().trim();
        if (!email.isEmpty() && userMapper.selectCount(new QueryWrapper<SysUser>().eq("email", email).eq("deleted", 0)) > 0) {
            throw new BusinessException("EMAIL_EXISTS", "email already exists");
        }
        SysRole defaultRole = roleMapper.selectOne(new QueryWrapper<SysRole>()
                .eq("role_key", properties.defaultRoleKey()).eq("status", 1).eq("deleted", 0).last("LIMIT 1"));
        if (defaultRole == null) throw new BusinessException("REGISTRATION_ROLE_MISSING", "default registration role is missing");

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname(request.nickname().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        user.setPhoneNumber("");
        user.setSex(0);
        user.setAvatar("");
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setLoginIp("");
        user.setRemark("用户自助注册，待管理员分配组织");
        user.setDeleted(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("USERNAME_EXISTS", "username already exists");
        }
        userRoleMapper.insert(new SysUserRole(user.getUserId(), defaultRole.getRoleId()));
        return user.getUserId();
    }
}
