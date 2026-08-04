package com.docbase.knowledge.folder.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.folder.domain.KnowledgeFolder;
import com.docbase.knowledge.folder.mapper.KnowledgeFolderMapper;
import com.docbase.knowledge.permission.KnowledgePermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeFolderService {

    private final KnowledgeFolderMapper folderMapper;
    private final KnowledgePermissionService permissionService;

    public KnowledgeFolderService(KnowledgeFolderMapper folderMapper,
                                  KnowledgePermissionService permissionService) {
        this.folderMapper = folderMapper;
        this.permissionService = permissionService;
    }

    /**
     * Gets the folder tree for a knowledge base.
     */
    public List<FolderNode> getTree(Long knowledgeBaseId, Long userId, boolean isAdmin) {
        permissionService.requireMembership(knowledgeBaseId, userId, isAdmin);

        List<KnowledgeFolder> folders = folderMapper.selectList(
                new QueryWrapper<KnowledgeFolder>()
                        .eq("knowledge_base_id", knowledgeBaseId)
                        .eq("deleted", 0)
                        .orderByAsc("sort_num")
        );

        return buildTree(folders);
    }

    /**
     * Creates a folder. Requires EDITOR or higher (or admin:all).
     * Verifies the knowledge base exists first.
     */
    @Transactional
    public Long create(Long knowledgeBaseId, KnowledgeFolder folder, Long userId, boolean isAdmin) {
        permissionService.requireActiveKnowledgeBase(knowledgeBaseId); // Verify base exists
        permissionService.requirePermission(knowledgeBaseId, userId, isAdmin, 3, "Editor permission required");
        validateParentFolder(knowledgeBaseId, folder.getParentId(), null);

        folder.setId(null);
        folder.setKnowledgeBaseId(knowledgeBaseId);
        folder.setCreatedBy(userId);
        folder.setUpdatedBy(userId);
        folder.setSortNum(folder.getSortNum() != null ? folder.getSortNum() : 0);
        folder.setDeleted(0);
        folderMapper.insert(folder);
        return folder.getId();
    }

    /**
     * Updates a folder. Requires EDITOR or higher (or admin:all).
     * Verifies the folder belongs to the specified knowledge base.
     */
    @Transactional
    public void update(Long knowledgeBaseId, Long folderId, KnowledgeFolder updates, Long userId, boolean isAdmin) {
        permissionService.requirePermission(knowledgeBaseId, userId, isAdmin, 3, "Editor permission required");
        // Verify folder exists AND belongs to this knowledge base (prevents IDOR)
        KnowledgeFolder existing = folderMapper.selectOne(
                new QueryWrapper<KnowledgeFolder>()
                        .eq("id", folderId)
                        .eq("knowledge_base_id", knowledgeBaseId)
                        .eq("deleted", 0)
        );
        if (existing == null) {
            throw new BusinessException("FOLDER_NOT_FOUND", "Folder not found");
        }
        validateParentFolder(knowledgeBaseId, updates.getParentId(), folderId);

        if (updates.getName() != null) {
            existing.setName(updates.getName());
        }
        if (updates.getParentId() != null) {
            existing.setParentId(updates.getParentId());
        }
        if (updates.getSortNum() != null) {
            existing.setSortNum(updates.getSortNum());
        }
        existing.setUpdatedBy(userId);
        folderMapper.updateById(existing);
    }

    /**
     * Deletes a folder. Requires EDITOR or higher (or admin:all).
     * Verifies the folder belongs to the specified knowledge base.
     */
    @Transactional
    public void delete(Long knowledgeBaseId, Long folderId, Long userId, boolean isAdmin) {
        permissionService.requirePermission(knowledgeBaseId, userId, isAdmin, 3, "Editor permission required");
        // Verify folder exists AND belongs to this knowledge base (prevents IDOR)
        KnowledgeFolder existing = folderMapper.selectOne(
                new QueryWrapper<KnowledgeFolder>()
                        .eq("id", folderId)
                        .eq("knowledge_base_id", knowledgeBaseId)
                        .eq("deleted", 0)
        );
        if (existing == null) {
            throw new BusinessException("FOLDER_NOT_FOUND", "Folder not found");
        }

        // Check for children
        long children = folderMapper.selectCount(
                new QueryWrapper<KnowledgeFolder>()
                        .eq("parent_id", folderId)
                        .eq("deleted", 0)
        );
        if (children > 0) {
            throw new BusinessException("FOLDER_HAS_CHILDREN", "Cannot delete folder with children");
        }

        // Use softDeleteById to set both deleted=1 and delete_marker=id
        folderMapper.softDeleteById(folderId);
    }

    private void validateParentFolder(Long knowledgeBaseId, Long parentId, Long currentFolderId) {
        if (parentId == null || parentId == 0L) {
            return; // Root level
        }
        KnowledgeFolder parent = folderMapper.selectById(parentId);
        if (parent == null || parent.getDeleted() == 1) {
            throw new BusinessException("PARENT_NOT_FOUND", "Parent folder not found");
        }
        if (!parent.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new BusinessException("PARENT_IN_DIFFERENT_BASE", "Parent folder belongs to different knowledge base");
        }
        // Prevent circular reference
        if (currentFolderId != null && parentId.equals(currentFolderId)) {
            throw new BusinessException("CIRCULAR_REFERENCE", "Folder cannot be its own parent");
        }
        // Check ancestors to prevent deeper cycles
        Long ancestor = parentId;
        while (ancestor != null && ancestor != 0L) {
            KnowledgeFolder ancestorFolder = folderMapper.selectById(ancestor);
            if (ancestorFolder == null) break;
            if (currentFolderId != null && currentFolderId.equals(ancestorFolder.getId())) {
                throw new BusinessException("CIRCULAR_REFERENCE", "Circular folder reference detected");
            }
            ancestor = ancestorFolder.getParentId();
        }
    }

    private List<FolderNode> buildTree(List<KnowledgeFolder> folders) {
        Map<Long, FolderNode> nodeMap = new HashMap<>();
        for (KnowledgeFolder f : folders) {
            nodeMap.put(f.getId(), new FolderNode(
                    f.getId(), f.getParentId(), f.getName(), f.getSortNum(), new ArrayList<>()));
        }
        List<FolderNode> roots = new ArrayList<>();
        for (FolderNode node : nodeMap.values()) {
            if (node.parentId() == null || node.parentId() == 0L) {
                roots.add(node);
            } else {
                FolderNode parent = nodeMap.get(node.parentId());
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

    public record FolderNode(Long id, Long parentId, String name, Integer sortNum, List<FolderNode> children) {
    }
}
