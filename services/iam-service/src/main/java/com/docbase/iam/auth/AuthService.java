package com.docbase.iam.auth;

import com.docbase.iam.auth.dto.AuthResult;
import com.docbase.iam.auth.dto.LoginRequest;
import com.docbase.iam.auth.dto.MenuNode;
import com.docbase.iam.auth.dto.UserInfo;
import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.iam.security.JwtProperties;
import com.docbase.iam.security.JwtTokenProvider;
import com.docbase.iam.security.JwtTokenVerifier;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final SysUserMapper userMapper;
    private final SysMenuMapper menuMapper;
    private final JwtTokenProvider tokenProvider;
    private final JwtTokenVerifier tokenVerifier;
    private final TokenStore tokenStore;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;

    public AuthService(SysUserMapper userMapper, SysMenuMapper menuMapper,
                       JwtTokenProvider tokenProvider, JwtTokenVerifier tokenVerifier,
                       TokenStore tokenStore, PasswordEncoder passwordEncoder,
                       JwtProperties jwtProperties) {
        this.userMapper = userMapper;
        this.menuMapper = menuMapper;
        this.tokenProvider = tokenProvider;
        this.tokenVerifier = tokenVerifier;
        this.tokenStore = tokenStore;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Authenticates a user and issues an access/refresh token pair.
     * Does not distinguish "user not found" from "wrong password" to avoid leaking account existence.
     */
    @Transactional(readOnly = true)
    public AuthResult login(LoginRequest request) {
        SysUser user = findByUsername(request.username());
        if (user == null) {
            user = findByEmail(request.username());
        }
        if (user == null || isDeleted(user)) {
            throw new BadCredentialsException("invalid username or password");
        }
        if (!isEnabled(user)) {
            throw new BadCredentialsException("invalid username or password");
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("invalid username or password");
        }

        Long userId = user.getUserId();
        long sessionVersion = tokenStore.initSessionVersion(userId);
        long authVersion = tokenStore.initAuthVersion(userId);
        Set<String> permissions = loadPermissions(userId);

        String access = tokenProvider.signAccess(String.valueOf(userId), user.getUsername(),
                user.getOrganizationId(), permissions, authVersion);
        String refresh = tokenProvider.signRefresh(String.valueOf(userId), sessionVersion);
        String refreshJti = extractVerifiedJti(refresh);
        tokenStore.storeRefresh(refreshJti, userId, sessionVersion, tokenProvider.refreshTtl());

        tokenStore.cachePermissions(userId, permissions, Duration.ofHours(1));

        UserInfo userInfo = new UserInfo(
                userId, user.getUsername(), user.getNickname(), user.getEmail(),
                user.getPhoneNumber(), user.getOrganizationId(), user.getIsAdmin() != null && user.getIsAdmin() == 1);
        log.info("User logged in: {}", user.getUsername());
        return new AuthResult(access, refresh, tokenProvider.accessTtl().getSeconds(), userInfo, permissions);
    }

    /**
     * Rotates a refresh token using full JWT verification + atomic Redis rotation.
     * The refresh token is fully verified (signature, issuer, expiry, token_type, jti, sub).
     */
    @Transactional(readOnly = true)
    public AuthResult refresh(String refreshToken) {
        // Step 1: Fully verify the refresh token cryptographically
        Claims claims = tokenVerifier.verifyRefresh(refreshToken);
        if (claims == null) {
            throw new BadCredentialsException("invalid refresh token");
        }

        // Step 2: Extract and validate claims
        String jti = claims.getId();
        Long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new BadCredentialsException("invalid refresh token");
        }
        if (jti == null || userId == null) {
            throw new BadCredentialsException("invalid refresh token");
        }

        // Step 3: Get current session version (read-only, no increment)
        long currentVersion = tokenStore.getSessionVersion(userId);
        if (currentVersion == 0) {
            throw new BadCredentialsException("session expired or revoked");
        }

        // Step 4: Verify the token's session version matches current
        long tokenSessionVersion;
        try {
            Object sv = claims.get("session_version");
            tokenSessionVersion = sv instanceof Number ? ((Number) sv).longValue() : Long.parseLong(sv.toString());
        } catch (Exception e) {
            throw new BadCredentialsException("invalid refresh token");
        }
        if (tokenSessionVersion != currentVersion) {
            log.warn("Refresh token session version mismatch for user {}: token={}, current={}",
                    userId, tokenSessionVersion, currentVersion);
            throw new BadCredentialsException("refresh token expired or revoked");
        }

        // Step 5: Verify user still exists and is enabled
        SysUser user = userMapper.selectById(userId);
        if (user == null || isDeleted(user) || !isEnabled(user)) {
            throw new BadCredentialsException("invalid refresh token");
        }

        // Step 6: Atomically rotate the refresh token (prevents concurrent replay)
        long authVersion = tokenStore.initAuthVersion(userId);
        Set<String> permissions = loadPermissions(userId);
        String newAccess = tokenProvider.signAccess(String.valueOf(userId), user.getUsername(),
                user.getOrganizationId(), permissions, authVersion);
        String newRefresh = tokenProvider.signRefresh(String.valueOf(userId), currentVersion);
        String newJti = extractVerifiedJti(newRefresh);

        boolean rotated = tokenStore.rotateRefresh(
                jti, userId, currentVersion, newJti, tokenProvider.refreshTtl());
        if (!rotated) {
            // Token was already consumed (replay detected) or version mismatch
            log.warn("Refresh token replay detected for user {}: jti={}", userId, jti);
            throw new BadCredentialsException("refresh token expired or revoked");
        }

        tokenStore.cachePermissions(userId, permissions, Duration.ofHours(1));

        UserInfo userInfo = new UserInfo(
                userId, user.getUsername(), user.getNickname(), user.getEmail(),
                user.getPhoneNumber(), user.getOrganizationId(), user.getIsAdmin() != null && user.getIsAdmin() == 1);
        return new AuthResult(newAccess, newRefresh, tokenProvider.accessTtl().getSeconds(), userInfo, permissions);
    }

    /**
     * Logs out the current user: revokes refresh token and bumps auth version (invalidates access tokens).
     */
    public void logout(String refreshToken, Long userId) {
        if (refreshToken != null) {
            // Verify token first to get jti, then revoke
            Claims claims = tokenVerifier.verifyRefresh(refreshToken);
            if (claims != null) {
                tokenStore.revokeRefresh(claims.getId());
            }
        }
        if (userId != null) {
            tokenStore.bumpAuthVersion(userId);
            tokenStore.bumpSessionVersion(userId);
            tokenStore.evictPermissions(userId);
        }
        SecurityContextHolder.clearContext();
    }

    public UserInfo currentUserInfo(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        return new UserInfo(user.getUserId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getPhoneNumber(), user.getOrganizationId(),
                user.getIsAdmin() != null && user.getIsAdmin() == 1);
    }

    public Set<String> permissions(Long userId) {
        Set<String> cached = tokenStore.getCachedPermissions(userId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        Set<String> perms = loadPermissions(userId);
        tokenStore.cachePermissions(userId, perms, Duration.ofHours(1));
        return perms;
    }

    public List<MenuNode> menuTree(Long userId) {
        List<SysMenu> menus = menuMapper.selectMenusByUserId(userId);
        return buildTree(menus);
    }

    /**
     * Invalidates all sessions for a user (used after password change, disable, delete).
     * Bumps both auth version (invalidates access tokens) and session version (invalidates refresh tokens).
     */
    public void invalidateSessions(Long userId) {
        tokenStore.bumpAuthVersion(userId);
        tokenStore.bumpSessionVersion(userId);
        tokenStore.evictPermissions(userId);
    }

    private Set<String> loadPermissions(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return Collections.emptySet();
        }
        Set<String> perms = userMapper.selectPermissionsByUserId(userId);
        if (perms == null) {
            perms = Collections.emptySet();
        }
        // 归一化：把旧格式权限映射为新格式并过滤空串（与 RoleService.assertGrantable 共用
        // PermissionMapping.normalize，避免两处规则漂移）。
        Set<String> mapped = PermissionMapping.normalize(perms);
        // admin:all 是超级管理员专属标记，绝不通过菜单授权取得。即便有人把某菜单的
        // permission 字段写成 admin:all，也在此处过滤掉，从根源上阻断提权链路。
        mapped.remove(IamUserPrincipal.ADMIN_ALL_PERMISSION);
        // 仅数据库标记为超级管理员（is_admin=1）的用户才能获得 admin:all，与菜单数据无关。
        if (user.getIsAdmin() != null && user.getIsAdmin() == 1) {
            mapped.add(IamUserPrincipal.ADMIN_ALL_PERMISSION);
        }
        return mapped;
    }

    private boolean isDeleted(SysUser user) {
        return user.getDeleted() != null && user.getDeleted() == 1;
    }

    private boolean isEnabled(SysUser user) {
        return user.getStatus() != null && user.getStatus() == 1;
    }

    private SysUser findByUsername(String username) {
        return userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                        .eq("username", username)
                        .last("limit 1"));
    }

    private SysUser findByEmail(String email) {
        return userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                        .eq("email", email)
                        .last("limit 1"));
    }

    private List<MenuNode> buildTree(List<SysMenu> menus) {
        Map<Long, MenuNode> nodeMap = new HashMap<>();
        for (SysMenu m : menus) {
            nodeMap.put(m.getMenuId(), new MenuNode(
                    m.getMenuId(), m.getParentId(), m.getMenuName(), m.getRouterName(),
                    m.getPath(), m.getPermission(), m.getMenuType(), m.getIsButton(),
                    m.getSortNum(), m.getMetaInfo(), new ArrayList<>()));
        }
        List<MenuNode> roots = new ArrayList<>();
        for (MenuNode node : nodeMap.values()) {
            if (node.parentId() == null || node.parentId() == 0L) {
                roots.add(node);
            } else {
                MenuNode parent = nodeMap.get(node.parentId());
                if (parent != null) {
                    parent.children().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        roots.sort(Comparator.comparingInt(a -> a.sortNum() != null ? a.sortNum() : 0));
        return roots;
    }

    /**
     * Extracts jti from a verified token (we trust this token because it was just signed by us).
     */
    private String extractVerifiedJti(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            int idx = payload.indexOf("\"jti\"");
            if (idx < 0) return null;
            int colon = payload.indexOf(':', idx);
            int quote1 = payload.indexOf('"', colon + 1);
            int quote2 = payload.indexOf('"', quote1 + 1);
            return payload.substring(quote1 + 1, quote2);
        } catch (Exception e) {
            return null;
        }
    }
}
