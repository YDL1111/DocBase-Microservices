package com.docbase.iam.organization;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.organization.domain.SysOrganization;
import com.docbase.iam.organization.dto.OrganizationRequest;
import com.docbase.iam.organization.mapper.SysOrganizationMapper;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.SysUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class OrganizationService {
    private static final int MAX_DEPTH = 8;
    private final SysOrganizationMapper organizationMapper;
    private final SysUserMapper userMapper;

    public OrganizationService(SysOrganizationMapper organizationMapper, SysUserMapper userMapper) {
        this.organizationMapper = organizationMapper;
        this.userMapper = userMapper;
    }

    public List<SysOrganization> list() {
        return organizationMapper.selectList(new QueryWrapper<SysOrganization>()
                .eq("deleted", 0).orderByAsc("sort_num").orderByAsc("organization_id"));
    }

    public SysOrganization get(Long id) {
        SysOrganization value = organizationMapper.selectById(id);
        if (value == null || value.getDeleted() != null && value.getDeleted() == 1) {
            throw new BusinessException("ORGANIZATION_NOT_FOUND", "organization not found");
        }
        return value;
    }

    public void assertActive(Long id) {
        if (id == null) return;
        SysOrganization organization = get(id);
        if (organization.getStatus() == null || organization.getStatus() != 1) {
            throw new BusinessException("ORGANIZATION_DISABLED", "organization is disabled");
        }
    }

    @Transactional
    public Long create(OrganizationRequest request) {
        validateParent(request.parentId(), null);
        SysOrganization organization = new SysOrganization();
        apply(organization, request);
        organization.setIsSystem(0);
        organization.setDeleted(0);
        insertOrThrow(organization);
        return organization.getOrganizationId();
    }

    @Transactional
    public void update(Long id, OrganizationRequest request) {
        SysOrganization existing = get(id);
        if (existing.getIsSystem() != null && existing.getIsSystem() == 1
                && !existing.getOrganizationCode().equals(request.organizationCode())) {
            throw new BusinessException("ORGANIZATION_SYSTEM_PROTECTED", "system organization code cannot change");
        }
        if (request.status() == 0 && (existing.getStatus() == null || existing.getStatus() != 0)) {
            if (organizationMapper.selectCount(new QueryWrapper<SysOrganization>()
                    .eq("parent_id", id).eq("status", 1).eq("deleted", 0)) > 0) {
                throw new BusinessException("ORGANIZATION_HAS_ENABLED_CHILDREN",
                        "disable child organizations first");
            }
            if (userMapper.selectCount(new QueryWrapper<SysUser>()
                    .eq("organization_id", id).eq("status", 1).eq("deleted", 0)) > 0) {
                throw new BusinessException("ORGANIZATION_HAS_ACTIVE_USERS",
                        "move or disable organization users first");
            }
        }
        validateParent(request.parentId(), id);
        apply(existing, request);
        try {
            organizationMapper.updateById(existing);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("ORGANIZATION_CODE_EXISTS", "organization code already exists");
        }
    }

    @Transactional
    public void delete(Long id) {
        SysOrganization existing = get(id);
        if (existing.getIsSystem() != null && existing.getIsSystem() == 1) {
            throw new BusinessException("ORGANIZATION_SYSTEM_PROTECTED", "system organization cannot be deleted");
        }
        if (organizationMapper.selectCount(new QueryWrapper<SysOrganization>().eq("parent_id", id).eq("deleted", 0)) > 0) {
            throw new BusinessException("ORGANIZATION_HAS_CHILDREN", "organization has children");
        }
        if (userMapper.selectCount(new QueryWrapper<SysUser>().eq("organization_id", id).eq("deleted", 0)) > 0) {
            throw new BusinessException("ORGANIZATION_HAS_USERS", "organization has users");
        }
        organizationMapper.deleteById(id);
    }

    private void validateParent(Long parentId, Long currentId) {
        if (parentId == null || parentId == 0) return;
        if (parentId.equals(currentId)) throw new BusinessException("ORGANIZATION_CYCLE", "organization cannot parent itself");
        Set<Long> visited = new HashSet<>();
        Long cursor = parentId;
        int depth = 0;
        while (cursor != null && cursor != 0) {
            if (!visited.add(cursor) || cursor.equals(currentId)) {
                throw new BusinessException("ORGANIZATION_CYCLE", "organization hierarchy contains a cycle");
            }
            SysOrganization parent = get(cursor);
            if (parent.getStatus() == null || parent.getStatus() != 1) {
                throw new BusinessException("ORGANIZATION_PARENT_DISABLED", "parent organization is disabled");
            }
            cursor = parent.getParentId();
            if (++depth >= MAX_DEPTH) throw new BusinessException("ORGANIZATION_DEPTH_EXCEEDED", "organization depth exceeded");
        }
    }

    private void apply(SysOrganization target, OrganizationRequest request) {
        target.setParentId(request.parentId());
        target.setOrganizationName(request.organizationName().trim());
        target.setOrganizationCode(request.organizationCode().trim());
        target.setSortNum(request.sortNum());
        target.setStatus(request.status());
        target.setRemark(request.remark() == null ? "" : request.remark().trim());
    }

    private void insertOrThrow(SysOrganization organization) {
        try {
            organizationMapper.insert(organization);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("ORGANIZATION_CODE_EXISTS", "organization code already exists");
        }
    }
}
