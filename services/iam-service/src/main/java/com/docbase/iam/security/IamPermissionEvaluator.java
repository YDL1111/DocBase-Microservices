package com.docbase.iam.security;

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * Custom permission evaluator that supports super-admin bypass.
 * Super-admins (with "admin:all" authority) automatically have all permissions.
 */
@Component
public class IamPermissionEvaluator implements PermissionEvaluator {

    private static final String ADMIN_ALL = "admin:all";

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        // Super-admin has all permissions
        if (hasAdminAuthority(authentication)) {
            return true;
        }
        // Check specific permission
        if (permission instanceof String permStr) {
            return authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(permStr::equals);
        }
        return false;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        return hasPermission(authentication, null, permission);
    }

    private boolean hasAdminAuthority(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_ALL::equals);
    }
}
