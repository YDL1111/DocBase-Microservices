package com.docbase.iam.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Technical representation of the authenticated IAM identity. This is the
 * UserDetails stored in the SecurityContext after JWT validation. It carries
 * the minimal identity needed for authorization; business data lives in the
 * domain layer, not here.
 *
 * Super-admin users (isAdmin=true) are granted the "admin:all" permission which
 * bypasses all @PreAuthorize checks.
 */
public record IamUserPrincipal(
        Long userId,
        String username,
        boolean admin,
        Set<String> permissions
) implements UserDetails {

    /** Special permission that grants access to everything (super-admin). */
    public static final String ADMIN_ALL_PERMISSION = "admin:all";

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (admin) {
            // Super-admin gets all permissions
            return Set.of(new SimpleGrantedAuthority(ADMIN_ALL_PERMISSION));
        }
        return permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
    }

    /**
     * Checks if this principal has the given permission. Super-admins always have it.
     */
    public boolean hasPermission(String permission) {
        if (admin) return true;
        return permissions.contains(permission);
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
