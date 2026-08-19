package com.docbase.iam.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.dto.AdminSetupRequest;
import com.docbase.iam.auth.dto.AdminSetupStatus;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.mapper.SysRoleMapper;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.AdminMutexMapper;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Creates the first active super administrator using the same database mutex as
 * the last-admin lifecycle checks. The lock, active-admin recheck, user insert
 * and role assignments all run in one transaction, so two IAM instances cannot
 * both bootstrap an administrator.
 */
@Service
public class AdminSetupService {

    private static final Logger log = LoggerFactory.getLogger(AdminSetupService.class);

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{2,63}$");
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final AdminMutexMapper adminMutexMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdminSetupProperties properties;
    private final TransactionTemplate transactionTemplate;

    public AdminSetupService(SysUserMapper userMapper,
                             SysUserRoleMapper userRoleMapper,
                             SysRoleMapper roleMapper,
                             AdminMutexMapper adminMutexMapper,
                             PasswordEncoder passwordEncoder,
                             AdminSetupProperties properties,
                             PlatformTransactionManager transactionManager) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.adminMutexMapper = adminMutexMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public AdminSetupStatus status() {
        boolean required = userMapper.selectActiveAdminIds().isEmpty();
        return new AdminSetupStatus(required, required && properties.enabled());
    }

    public Long setup(AdminSetupRequest request) {
        if (!properties.enabled()) {
            throw new BusinessException("ADMIN_SETUP_DISABLED", "administrator setup key is not configured");
        }
        assertSetupKey(request.setupKey());
        if (!userMapper.selectActiveAdminIds().isEmpty()) {
            throw new BusinessException("ADMIN_SETUP_CLOSED", "administrator setup has already completed");
        }
        return createFirstAdmin(request.username(), request.nickname(), request.password());
    }

    /** Trusted startup path used by {@code AdminInitializer}; no public key is involved. */
    public Long initializeFromEnvironment(String username, String nickname, String password) {
        return createFirstAdmin(username, nickname, password);
    }

    private Long createFirstAdmin(String rawUsername, String rawNickname, String password) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        String nickname = rawNickname == null ? "" : rawNickname.trim();
        validateCredentials(username, nickname, password);

        return transactionTemplate.execute(status -> {
            lockAdminGuardRow();
            if (!userMapper.selectActiveAdminIds().isEmpty()) {
                throw new BusinessException("ADMIN_SETUP_CLOSED", "administrator setup has already completed");
            }
            if (userMapper.countAnyByUsername(username) > 0) {
                throw new BusinessException("USERNAME_EXISTS", "username already exists");
            }

            List<SysRole> systemRoles = roleMapper.selectList(
                    new QueryWrapper<SysRole>()
                            .eq("is_system", 1)
                            .eq("status", 1)
                            .orderByAsc("role_id"));
            boolean hasSystemAdmin = systemRoles.stream()
                    .anyMatch(role -> "system_admin".equals(role.getRoleKey()));
            if (!hasSystemAdmin) {
                throw new BusinessException("MIGRATION_MISSING",
                        "active system_admin role is missing; run all IAM Flyway migrations");
            }

            SysUser admin = new SysUser();
            admin.setUsername(username);
            admin.setNickname(nickname);
            admin.setPassword(passwordEncoder.encode(password));
            admin.setStatus(1);
            admin.setIsAdmin(1);
            admin.setRemark("first administrator");
            admin.setDeleted(0);
            try {
                userMapper.insert(admin);
            } catch (DuplicateKeyException exception) {
                throw new BusinessException("USERNAME_EXISTS", "username already exists");
            }

            for (SysRole role : systemRoles) {
                userRoleMapper.insert(new SysUserRole(admin.getUserId(), role.getRoleId()));
            }
            return admin.getUserId();
        });
    }

    private void assertSetupKey(String suppliedKey) {
        byte[] expected = properties.key().getBytes(StandardCharsets.UTF_8);
        byte[] supplied = suppliedKey == null
                ? new byte[0]
                : suppliedKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            log.warn("Rejected first-admin setup request because the operator key was invalid");
            throw new AdminSetupForbiddenException();
        }
    }

    private void validateCredentials(String username, String nickname, String password) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException("VALIDATION_ERROR", "username format is invalid");
        }
        if (nickname.isBlank() || nickname.length() > 64) {
            throw new BusinessException("VALIDATION_ERROR", "nickname must be 1-64 characters");
        }
        if (password == null || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new BusinessException("VALIDATION_ERROR",
                    "password must be at least 8 characters and at most 72 UTF-8 bytes");
        }
    }

    private void lockAdminGuardRow() {
        if (adminMutexMapper.lockGuardRow() == 0) {
            throw new BusinessException("MIGRATION_MISSING",
                    "sys_admin_mutex guard row missing; run IAM Flyway migration V3");
        }
    }
}
