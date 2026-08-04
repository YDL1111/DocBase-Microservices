package com.docbase.iam.role;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.domain.SysRoleMenu;
import com.docbase.iam.role.mapper.SysRoleMapper;
import com.docbase.iam.role.mapper.SysRoleMenuMapper;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RoleService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final TokenStore tokenStore;

    public RoleService(SysRoleMapper roleMapper, SysRoleMenuMapper roleMenuMapper,
                       SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                       TokenStore tokenStore) {
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.tokenStore = tokenStore;
    }

    public SysRole getById(Long roleId) {
        return roleMapper.selectById(roleId);
    }

    public Page<SysRole> page(long current, long size, String roleName) {
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

    @Transactional
    public Long create(SysRole role, List<Long> menuIds) {
        if (roleMapper.selectCount(new QueryWrapper<SysRole>().eq("role_key", role.getRoleKey())) > 0) {
            throw new BusinessException("ROLE_KEY_EXISTS", "role key already exists");
        }
        role.setStatus(role.getStatus() != null ? role.getStatus() : 1);
        roleMapper.insert(role);
        assignMenus(role.getRoleId(), menuIds);
        return role.getRoleId();
    }

    @Transactional
    public void update(SysRole role, List<Long> menuIds) {
        SysRole existing = roleMapper.selectById(role.getRoleId());
        if (existing == null) {
            throw new BusinessException("ROLE_NOT_FOUND", "role not found");
        }
        existing.setRoleName(role.getRoleName());
        existing.setRoleKey(role.getRoleKey());
        existing.setRoleSort(role.getRoleSort());
        existing.setDataScope(role.getDataScope());
        existing.setRemark(role.getRemark());
        roleMapper.updateById(existing);
        if (menuIds != null) {
            roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("role_id", role.getRoleId()));
            assignMenus(role.getRoleId(), menuIds);
            // Menu assignment changed: invalidate all users with this role
            invalidateRoleUsers(role.getRoleId());
        }
    }

    @Transactional
    public void delete(Long roleId) {
        // Invalidate all users with this role BEFORE deleting associations
        invalidateRoleUsers(roleId);
        // Clean up associations
        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("role_id", roleId));
        // Clean up user-role associations
        userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("role_id", roleId));
        roleMapper.deleteById(roleId);
    }

    public void changeStatus(Long roleId, Integer status) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("ROLE_NOT_FOUND", "role not found");
        }
        role.setStatus(status);
        roleMapper.updateById(role);
        // Status change affects all users with this role
        if (status != null && status == 0) {
            invalidateRoleUsers(roleId);
        }
    }

    @Transactional
    public void assignMenus(Long roleId, List<Long> menuIds) {
        if (menuIds == null) return;
        for (Long menuId : menuIds) {
            roleMenuMapper.insert(new SysRoleMenu(roleId, menuId));
        }
        // Menu assignment changed: invalidate all users with this role
        invalidateRoleUsers(roleId);
    }

    public List<Long> getMenuIds(Long roleId) {
        return roleMenuMapper.selectList(new QueryWrapper<SysRoleMenu>().eq("role_id", roleId))
                .stream().map(SysRoleMenu::getMenuId).toList();
    }

    /**
     * Invalidates permissions cache and auth version for all users with this role.
     */
    private void invalidateRoleUsers(Long roleId) {
        List<SysUser> users = userMapper.selectList(
                new QueryWrapper<SysUser>().inSql("user_id",
                        "SELECT user_id FROM sys_user_role WHERE role_id = " + roleId));
        for (SysUser user : users) {
            tokenStore.evictPermissions(user.getUserId());
            tokenStore.bumpAuthVersion(user.getUserId());
        }
    }
}
