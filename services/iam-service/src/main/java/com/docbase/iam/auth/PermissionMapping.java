package com.docbase.iam.auth;

import java.util.Map;

/**
 * Maps old permission strings (from the legacy AgileBoot project) to new ones.
 * This ensures backward compatibility when migrating existing menu data.
 *
 * Old format examples: system:user:add, system:role:edit, system:menu:remove
 * New format examples: system:user:create, system:role:update, system:menu:delete
 */
public final class PermissionMapping {

    private static final Map<String, String> OLD_TO_NEW = Map.ofEntries(
            // User management
            Map.entry("system:user:add", "system:user:create"),
            Map.entry("system:user:edit", "system:user:update"),
            Map.entry("system:user:remove", "system:user:delete"),
            Map.entry("system:user:resetPwd", "system:user:reset-password"),
            Map.entry("system:user:list", "system:user:list"),
            Map.entry("system:user:query", "system:user:list"),
            // Role management
            Map.entry("system:role:add", "system:role:create"),
            Map.entry("system:role:edit", "system:role:update"),
            Map.entry("system:role:remove", "system:role:delete"),
            Map.entry("system:role:list", "system:role:list"),
            Map.entry("system:role:query", "system:role:list"),
            // Menu management
            Map.entry("system:menu:add", "system:menu:create"),
            Map.entry("system:menu:edit", "system:menu:update"),
            Map.entry("system:menu:remove", "system:menu:delete"),
            Map.entry("system:menu:list", "system:menu:list"),
            Map.entry("system:menu:query", "system:menu:list")
    );

    private PermissionMapping() {}

    /**
     * Maps an old permission string to the new format. If the permission is already
     * in the new format or is unrecognized, returns it unchanged.
     */
    public static String mapToNew(String permission) {
        if (permission == null || permission.isBlank()) {
            return null;
        }
        return OLD_TO_NEW.getOrDefault(permission, permission);
    }

    /**
     * Maps a new permission string back to the old format (for reverse lookups).
     */
    public static String mapToOld(String permission) {
        if (permission == null || permission.isBlank()) {
            return null;
        }
        for (Map.Entry<String, String> entry : OLD_TO_NEW.entrySet()) {
            if (entry.getValue().equals(permission)) {
                return entry.getKey();
            }
        }
        return permission;
    }
}
