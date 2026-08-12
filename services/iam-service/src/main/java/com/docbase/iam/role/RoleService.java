package com.docbase.iam.role;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.PermissionMapping;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.domain.SysRoleMenu;
import com.docbase.iam.role.dto.AssignRoleMenusRequest;
import com.docbase.iam.role.dto.ChangeRoleStatusRequest;
import com.docbase.iam.role.dto.CreateRoleRequest;
import com.docbase.iam.role.dto.UpdateRoleRequest;
import com.docbase.iam.role.mapper.SysRoleMapper;
import com.docbase.iam.role.mapper.SysRoleMenuMapper;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoleService {

    /** 单页最大记录数上限，防止超大 size 拖垮数据库。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysMenuMapper menuMapper;
    private final TokenStore tokenStore;

    public RoleService(SysRoleMapper roleMapper, SysRoleMenuMapper roleMenuMapper,
                       SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                       SysMenuMapper menuMapper, TokenStore tokenStore) {
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.menuMapper = menuMapper;
        this.tokenStore = tokenStore;
    }

    /* ========================= 只读查询（无需调用者校验） ========================= */

    public SysRole getById(Long roleId) {
        return roleMapper.selectById(roleId);
    }

    public Page<SysRole> page(long current, long size, String roleName) {
        if (current < 1) {
            throw new BusinessException("PAGINATION_INVALID", "current must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException("PAGINATION_INVALID",
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Page<SysRole> page = new Page<>(current, size);
        QueryWrapper<SysRole> wrapper = new QueryWrapper<>();
        if (roleName != null && !roleName.isBlank()) {
            wrapper.like("role_name", roleName);
        }
        wrapper.orderByAsc("role_sort");
        return roleMapper.selectPage(page, wrapper);
    }

    public List<SysRole> listAll() {
        return roleMapper.selectList(new QueryWrapper<SysRole>().orderByAsc("role_sort"));
    }

    public List<Long> getMenuIds(Long roleId) {
        return roleMenuMapper.selectList(new QueryWrapper<SysRoleMenu>().eq("role_id", roleId))
                .stream().map(SysRoleMenu::getMenuId).toList();
    }

    /* ========================= 写操作（含调用者授权校验） ========================= */

    @Transactional
    public Long create(CreateRoleRequest request) {
        IamUserPrincipal caller = currentPrincipal();

        if (roleMapper.selectCount(
                new QueryWrapper<SysRole>().eq("role_key", request.roleKey())) > 0) {
            throw new BusinessException("ROLE_KEY_EXISTS", "role key already exists");
        }

        // 先校验菜单（含权限子集），再插入角色，保证失败时无副作用（全或无）。
        List<Long> deduped = dedupMenuIds(request.menuIds());
        validateMenus(caller, deduped);

        SysRole role = new SysRole();
        role.setRoleName(request.roleName());
        role.setRoleKey(request.roleKey());
        role.setRoleSort(request.roleSort());
        role.setDataScope(request.dataScope());
        role.setStatus(request.status() != null ? request.status() : 1);
        role.setIsSystem(0); // 接口创建的角色一律不是系统保留角色
        role.setRemark(request.remark());
        try {
            roleMapper.insert(role);
        } catch (DuplicateKeyException e) {
            // 并发创建相同 roleKey 时，唯一索引是最终仲裁者
            throw new BusinessException("ROLE_KEY_EXISTS", "role key already exists");
        }

        insertRoleMenus(role.getRoleId(), deduped);
        return role.getRoleId();
    }

    @Transactional
    public void update(Long roleId, UpdateRoleRequest request) {
        IamUserPrincipal caller = currentPrincipal();
        SysRole existing = roleMapper.selectById(roleId);
        if (existing == null || isDeleted(existing)) {
            throw new BusinessException("ROLE_NOT_FOUND", "role not found");
        }
        assertCanMutate(caller, existing);

        if (!Objects.equals(existing.getRoleKey(), request.roleKey())
                && roleMapper.selectCount(
                        new QueryWrapper<SysRole>().eq("role_key", request.roleKey())) > 0) {
            throw new BusinessException("ROLE_KEY_EXISTS", "role key already exists");
        }

        // 先校验菜单，再修改角色，保证全或无。
        List<Long> deduped = dedupMenuIds(request.menuIds());
        if (request.menuIds() != null) {
            validateMenus(caller, deduped);
        }

        existing.setRoleName(request.roleName());
        existing.setRoleKey(request.roleKey());
        existing.setRoleSort(request.roleSort());
        existing.setDataScope(request.dataScope());
        existing.setRemark(request.remark());
        try {
            roleMapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("ROLE_KEY_EXISTS", "role key already exists");
        }

        if (request.menuIds() != null) {
            replaceRoleMenus(roleId, deduped);
            invalidateRoleUsers(roleId);
        }
    }

    @Transactional
    public void delete(Long roleId) {
        IamUserPrincipal caller = currentPrincipal();
        SysRole existing = roleMapper.selectById(roleId);
        if (existing == null || isDeleted(existing)) {
            throw new BusinessException("ROLE_NOT_FOUND", "role not found");
        }
        assertCanMutate(caller, existing);

        // 先捕获受影响用户，再删除关联，保证失效通知不丢（全或无语义）。
        List<Long> affectedUserIds = userRoleMapper.selectList(
                        new QueryWrapper<SysUserRole>().eq("role_id", roleId))
                .stream().map(SysUserRole::getUserId).distinct().toList();

        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("role_id", roleId));
        userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("role_id", roleId));
        roleMapper.deleteById(roleId);

        invalidateUsers(affectedUserIds);
    }

    @Transactional
    public void changeStatus(Long roleId, ChangeRoleStatusRequest request) {
        IamUserPrincipal caller = currentPrincipal();
        SysRole existing = roleMapper.selectById(roleId);
        if (existing == null || isDeleted(existing)) {
            throw new BusinessException("ROLE_NOT_FOUND", "role not found");
        }
        assertCanMutate(caller, existing);

        existing.setStatus(request.status());
        roleMapper.updateById(existing);

        // 启用/停用都会改变该角色下用户的有效权限，统一失效其缓存与令牌。
        invalidateRoleUsers(roleId);
    }

    @Transactional
    public void assignMenus(Long roleId, AssignRoleMenusRequest request) {
        IamUserPrincipal caller = currentPrincipal();
        SysRole existing = roleMapper.selectById(roleId);
        if (existing == null || isDeleted(existing)) {
            throw new BusinessException("ROLE_NOT_FOUND", "role not found");
        }
        assertCanMutate(caller, existing);

        List<Long> deduped = dedupMenuIds(request.menuIds());
        validateMenus(caller, deduped);

        replaceRoleMenus(roleId, deduped);
        invalidateRoleUsers(roleId);
    }

    /* ========================= 菜单授权（全或无，含校验） ========================= */

    /**
     * 校验候选菜单集合：全部有效 + 非超级管理员只能授予自身已拥有的权限子集。
     * 任一不满足则抛出，由事务回滚保证不会出现半更新。
     */
    private void validateMenus(IamUserPrincipal caller, List<Long> menuIds) {
        if (menuIds.isEmpty()) return;
        int valid = menuMapper.countValidMenus(menuIds);
        if (valid != menuIds.size()) {
            throw new BusinessException("MENU_INVALID",
                    "menuIds contain non-existent, deleted or disabled menu");
        }
        assertGrantable(caller, menuIds);
    }

    private void insertRoleMenus(Long roleId, List<Long> menuIds) {
        for (Long menuId : menuIds) {
            roleMenuMapper.insert(new SysRoleMenu(roleId, menuId));
        }
    }

    /** 全量替换角色菜单（调用方须先完成校验）。 */
    private void replaceRoleMenus(Long roleId, List<Long> menuIds) {
        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("role_id", roleId));
        insertRoleMenus(roleId, menuIds);
    }

    /* ========================= 授权校验 ========================= */

    /**
     * 非超级管理员禁止操作系统保留角色（is_system=1）。
     *
     * <p>为避免信息泄露，对非超级管理员统一返回 ROLE_NOT_FOUND，使其无法区分
     * "角色不存在"与"角色存在但受系统保护"。
     */
    private void assertCanMutate(IamUserPrincipal caller, SysRole target) {
        if (caller.admin()) return;
        if (target.getIsSystem() != null && target.getIsSystem() == 1) {
            throw new BusinessException("ROLE_NOT_FOUND", "role not found");
        }
    }

    /**
     * 非超级管理员只能把"自身已拥有的权限"授予角色。
     *
     * <p>读取候选菜单的 permission 字段，要求它们全部落在调用者权限集合内。
     * 由于 AuthService 已从菜单权限中剔除 admin:all，且非管理员自身也不持有
     * admin:all，该检查天然阻断"通过菜单取得 admin:all"的提权链路；此处再显式
     * 拦截一次作为纵深防御。
     */
    private void assertGrantable(IamUserPrincipal caller, List<Long> menuIds) {
        if (caller.admin()) return;
        Set<String> requested = menuMapper.selectPermissionsByMenuIds(menuIds);
        // 数据库中的 permission 可能是旧格式（如 system:role:edit），而调用者 JWT 中的权限
        // 已是新格式。归一化后再比较，避免把旧格式权限误判为越权（与 AuthService 共用同一规则）。
        Set<String> normalized = PermissionMapping.normalize(requested);

        if (normalized.contains(IamUserPrincipal.ADMIN_ALL_PERMISSION)) {
            throw new BusinessException("PERMISSION_NOT_GRANTABLE",
                    "admin:all cannot be granted through role menus");
        }
        for (String perm : normalized) {
            if (!caller.hasPermission(perm)) {
                throw new BusinessException("PERMISSION_NOT_SUBSET",
                        "cannot grant a permission the caller does not have: " + perm);
            }
        }
    }

    /* ========================= 角色分配授权（供 UserService 调用） ========================= */

    /**
     * 校验调用者是否可以把 roleIds 这些角色分配给某个用户。
     *
     * <p>校验规则（全部满足才允许分配）：
     * <ol>
     *   <li>每个角色必须存在、启用（status=1）、未删除，否则拒绝</li>
     *   <li>系统保留角色（is_system=1）只能由超级管理员分配</li>
     *   <li>非超级管理员只能分配"自身权限子集"的角色：待分配角色的有效权限
     *       （其全部菜单的 permission 并集）必须是调用者权限的子集，
     *       防止通过分配高权限角色实现纵向提权</li>
     * </ol>
     *
     * <p>该方法集中了角色分配侧的授权规则，与 {@link #assertGrantable} 共用
     * {@link PermissionMapping#normalize} 归一化规则，避免两处权限比较逻辑漂移。
     */
    public void assertCanAssignRoles(IamUserPrincipal caller, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        for (Long roleId : roleIds) {
            SysRole role = roleMapper.selectById(roleId);
            if (role == null || isDeleted(role)) {
                throw new BusinessException("ROLE_NOT_FOUND", "role not found: " + roleId);
            }
            if (role.getStatus() == null || role.getStatus() != 1) {
                throw new BusinessException("ROLE_DISABLED", "role is disabled: " + roleId);
            }
            if (role.getIsSystem() != null && role.getIsSystem() == 1 && !caller.admin()) {
                throw new BusinessException("ROLE_ASSIGN_FORBIDDEN",
                        "system-reserved role can only be assigned by super-admin: " + roleId);
            }
        }
        // 权限子集校验：所有待分配角色的有效权限并集 ⊆ 调用者权限。
        Set<Long> menuIds = roleIds.stream()
                .flatMap(roleId -> roleMenuMapper.selectMenuIdsByRoleId(roleId).stream())
                .collect(Collectors.toSet());
        if (menuIds.isEmpty()) return;
        Set<String> rolePerms = menuMapper.selectPermissionsByMenuIds(menuIds);
        Set<String> normalized = PermissionMapping.normalize(rolePerms);
        for (String perm : normalized) {
            if (IamUserPrincipal.ADMIN_ALL_PERMISSION.equals(perm)) {
                throw new BusinessException("ROLE_ASSIGN_FORBIDDEN",
                        "cannot assign a role that grants admin:all");
            }
            if (!caller.hasPermission(perm)) {
                throw new BusinessException("PERMISSION_NOT_SUBSET",
                        "cannot assign a role with a permission the caller does not have: " + perm);
            }
        }
    }

    /* ========================= 缓存/令牌失效 ========================= */

    private void invalidateRoleUsers(Long roleId) {
        List<Long> userIds = userRoleMapper.selectList(
                        new QueryWrapper<SysUserRole>().eq("role_id", roleId))
                .stream().map(SysUserRole::getUserId).distinct().toList();
        invalidateUsers(userIds);
    }

    private void invalidateUsers(List<Long> userIds) {
        for (Long userId : userIds) {
            tokenStore.evictPermissions(userId);
            tokenStore.bumpAuthVersion(userId);
        }
    }

    /* ========================= 工具方法 ========================= */

    private List<Long> dedupMenuIds(List<Long> menuIds) {
        if (menuIds == null) return new ArrayList<>();
        return menuIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    private IamUserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof IamUserPrincipal principal)) {
            throw new BusinessException("UNAUTHENTICATED", "no authenticated principal");
        }
        return principal;
    }

    private boolean isDeleted(SysRole role) {
        return role.getDeleted() != null && role.getDeleted() == 1;
    }
}
