package com.docbase.iam.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.PermissionMapping;
import com.docbase.iam.organization.domain.SysOrganization;
import com.docbase.iam.organization.mapper.SysOrganizationMapper;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.DirectoryScopeMapper;
import org.springframework.stereotype.Service;
import java.util.*;

/** Administrative personnel scopes. Knowledge assets retain their own membership/visibility policy. */
@Service
public class DirectoryScopePolicy {
    private final DirectoryScopeMapper mapper;
    private final SysUserMapper users;
    private final SysOrganizationMapper organizations;

    public DirectoryScopePolicy(DirectoryScopeMapper mapper, SysUserMapper users, SysOrganizationMapper organizations) {
        this.mapper = mapper; this.users = users; this.organizations = organizations;
    }

    public record Scope(boolean all, boolean self, Set<Long> organizations) {
        boolean allows(Long callerId, SysUser target) {
            return all || self && Objects.equals(callerId, target.getUserId())
                    || target.getOrganizationId() != null && organizations.contains(target.getOrganizationId());
        }
    }

    public Scope scope(IamUserPrincipal caller, String permission) {
        if (caller.admin()) return new Scope(true, true, Set.of());
        SysUser user = users.selectById(caller.userId());
        Long org = user == null ? null : user.getOrganizationId();
        Set<Long> ids = new HashSet<>();
        boolean self = false;
        for (var grant : mapper.grants(caller.userId())) {
            // A knowledge role with ALL scope must never widen a personnel-management grant.
            if (!permission.equals(PermissionMapping.mapToNew(grant.permission()))) continue;
            int value = grant.dataScope() == null ? 5 : grant.dataScope();
            if (value == 1) return new Scope(true, true, Set.of());
            if (value == 5) self = true;
            ids.addAll(scopeOrganizations(value, grant.roleId(), org));
        }
        return new Scope(false, self, Set.copyOf(ids));
    }

    private Set<Long> scopeOrganizations(int value, Long roleId, Long org) {
        if (value == 2) return new HashSet<>(mapper.organizations(roleId));
        if (org == null || value != 3 && value != 4) return Set.of();
        Set<Long> result = new HashSet<>(Set.of(org));
        if (value == 4) {
            List<SysOrganization> rows = organizations.selectList(new QueryWrapper<SysOrganization>().eq("deleted", 0));
            boolean changed;
            do {
                changed = false;
                for (var row : rows) if (result.contains(row.getParentId())) changed |= result.add(row.getOrganizationId());
            } while (changed);
        }
        return result;
    }

    public void restrict(QueryWrapper<SysUser> query, IamUserPrincipal caller, String permission) {
        Scope scope = scope(caller, permission);
        if (scope.all()) return;
        query.and(q -> {
            q.eq("user_id", -1L); // deny empty scopes, including users without organization
            if (scope.self()) q.or().eq("user_id", caller.userId());
            if (!scope.organizations().isEmpty()) q.or().in("organization_id", scope.organizations());
        });
    }

    public void assertTarget(IamUserPrincipal caller, SysUser target, String permission) {
        if (!scope(caller, permission).allows(caller.userId(), target)) denied();
    }

    public void assertOrganizationChange(IamUserPrincipal caller, Long destination) {
        Scope allowed = scope(caller, "system:user:update");
        if (!allowed.all() && (destination == null || !allowed.organizations().contains(destination))) denied();
    }

    public void assertRelocation(IamUserPrincipal caller, SysUser target, Long destination) {
        if (!caller.admin()) assertGrantScopes(caller, target, destination);
    }

    public void assertPrivilegeBoundary(IamUserPrincipal caller, SysUser target) {
        if (caller.admin() || Objects.equals(caller.userId(), target.getUserId())) return;
        if (Objects.equals(target.getIsAdmin(), 1)
                || !caller.permissions().containsAll(PermissionMapping.normalize(mapper.potentialPermissions(target.getUserId())))) {
            throw new BusinessException("OPERATION_FORBIDDEN", "cannot manage a user with higher privileges");
        }
        assertGrantScopes(caller, target, target.getOrganizationId());
    }

    private void assertGrantScopes(IamUserPrincipal caller, SysUser target, Long organizationId) {
        for (var grant : mapper.potentialGrants(target.getUserId())) {
            String permission = PermissionMapping.mapToNew(grant.permission());
            if (permission == null || !permission.startsWith("system:user:")) continue;
            Scope allowed = scope(caller, permission);
            int value = grant.dataScope() == null ? 5 : grant.dataScope();
            if (allowed.all()) continue;
            if (value == 1 || value == 5 && !allowed.allows(caller.userId(), target)
                    || !allowed.organizations().containsAll(scopeOrganizations(value, grant.roleId(), organizationId))) denied();
        }
    }

    public List<SysOrganization> organizationOptions(IamUserPrincipal caller) {
        Set<Long> allowed = new HashSet<>();
        boolean all = caller.admin();
        for (String operation : List.of("system:user:list", "system:user:create", "system:user:update")) {
            if (!caller.hasPermission(operation)) continue;
            Scope scope = scope(caller, operation);
            all |= scope.all(); allowed.addAll(scope.organizations());
        }
        var query = new QueryWrapper<SysOrganization>().eq("deleted", 0).orderByAsc("sort_num");
        if (!all) query.in("organization_id", allowed.isEmpty() ? Set.of(-1L) : allowed);
        return organizations.selectList(query);
    }

    public void assertRoleGrant(IamUserPrincipal caller, SysRole role, Long targetOrg, Collection<String> permissions) {
        if (caller.admin()) return;
        int value = role.getDataScope() == null ? 5 : role.getDataScope();
        for (String permission : PermissionMapping.normalize(permissions)) {
            if (!permission.startsWith("system:user:")) continue;
            Scope allowed = scope(caller, permission);
            if (allowed.all()) continue;
            if (value == 1) denied();
            Set<Long> requested = scopeOrganizations(value, role.getRoleId(), targetOrg);
            if (!allowed.organizations().containsAll(requested)) denied();
            // A SELF grant follows the recipient. The recipient is checked by the assignment operation.
        }
    }

    public List<Long> customOrganizations(Long roleId) { return mapper.organizations(roleId); }

    public void configureScope(IamUserPrincipal caller, SysRole existing, Integer value, List<Long> ids) {
        int selected = value == null ? 5 : value;
        if (!caller.admin()) {
            if (existing == null && selected != 5 || existing != null
                    && (selected != (existing.getDataScope() == null ? 5 : existing.getDataScope()) || ids != null)) {
                throw new BusinessException("OPERATION_FORBIDDEN", "only super-admin may configure role data scope");
            }
            return;
        }
        if (selected == 2) {
            if (ids == null && existing != null && Objects.equals(existing.getDataScope(), 2)) return;
            if (ids == null || ids.isEmpty()) throw new BusinessException("SCOPE_INVALID", "select at least one organization");
            for (Long id : new HashSet<>(ids)) {
                var org = organizations.selectById(id);
                if (org == null || !Objects.equals(org.getStatus(), 1))
                    throw new BusinessException("SCOPE_INVALID", "organization does not exist or is disabled");
            }
        }
    }

    public void saveScope(Long roleId, Integer value, List<Long> ids) {
        if (Objects.equals(value, 2) && ids == null) return;
        mapper.clearOrganizations(roleId);
        if (Objects.equals(value, 2)) for (Long id : new HashSet<>(ids)) mapper.addOrganization(roleId, id);
    }

    private static void denied() { throw new BusinessException("DATA_SCOPE_FORBIDDEN", "user is outside your management scope"); }
}
