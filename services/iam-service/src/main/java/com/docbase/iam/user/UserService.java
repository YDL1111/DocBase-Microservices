package com.docbase.iam.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.AuthService;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final TokenStore tokenStore;

    public UserService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                       PasswordEncoder passwordEncoder, AuthService authService,
                       TokenStore tokenStore) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.tokenStore = tokenStore;
    }

    public SysUser getById(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }

    public Page<SysUser> page(long current, long size, String username) {
        Page<SysUser> page = new Page<>(current, size);
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        if (username != null && !username.isBlank()) {
            wrapper.like("username", username);
        }
        wrapper.orderByDesc("create_time");
        Page<SysUser> result = userMapper.selectPage(page, wrapper);
        result.getRecords().forEach(u -> u.setPassword(null));
        return result;
    }

    @Transactional
    public Long create(SysUser user, List<Long> roleIds) {
        if (userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", user.getUsername())) > 0) {
            throw new BusinessException("USERNAME_EXISTS", "username already exists");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setStatus(user.getStatus() != null ? user.getStatus() : 1);
        user.setIsAdmin(user.getIsAdmin() != null ? user.getIsAdmin() : 0);
        userMapper.insert(user);
        assignRoles(user.getUserId(), roleIds);
        return user.getUserId();
    }

    @Transactional
    public void update(SysUser user, List<Long> roleIds) {
        SysUser existing = userMapper.selectById(user.getUserId());
        if (existing == null) {
            throw new BusinessException("USER_NOT_FOUND", "user not found");
        }
        existing.setNickname(user.getNickname());
        existing.setEmail(user.getEmail());
        existing.setPhoneNumber(user.getPhoneNumber());
        existing.setSex(user.getSex());
        existing.setRemark(user.getRemark());
        userMapper.updateById(existing);
        if (roleIds != null) {
            userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", user.getUserId()));
            assignRoles(user.getUserId(), roleIds);
            // Role change invalidates permissions cache and sessions
            tokenStore.evictPermissions(user.getUserId());
            tokenStore.bumpAuthVersion(user.getUserId());
        }
    }

    @Transactional
    public void delete(Long userId) {
        userMapper.deleteById(userId);
        userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", userId));
        // Invalidate all sessions for deleted user
        authService.invalidateSessions(userId);
    }

    public void changeStatus(Long userId, Integer status) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("USER_NOT_FOUND", "user not found");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        // Disabling a user invalidates all their sessions immediately
        if (status != null && status == 0) {
            authService.invalidateSessions(userId);
        }
    }

    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("USER_NOT_FOUND", "user not found");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        // Password change invalidates all prior sessions immediately
        authService.invalidateSessions(userId);
    }

    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        if (roleIds == null) return;
        for (Long roleId : roleIds) {
            userRoleMapper.insert(new SysUserRole(userId, roleId));
        }
        // Role assignment changes invalidate permissions
        tokenStore.evictPermissions(userId);
        tokenStore.bumpAuthVersion(userId);
    }

    public List<Long> getRoleIds(Long userId) {
        return userRoleMapper.selectList(new QueryWrapper<SysUserRole>().eq("user_id", userId))
                .stream().map(SysUserRole::getRoleId).toList();
    }
}
