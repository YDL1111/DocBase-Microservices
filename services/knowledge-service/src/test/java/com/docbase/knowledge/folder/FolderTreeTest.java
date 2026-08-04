package com.docbase.knowledge.folder;

import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.service.KnowledgeBaseService;
import com.docbase.knowledge.folder.domain.KnowledgeFolder;
import com.docbase.knowledge.folder.mapper.KnowledgeFolderMapper;
import com.docbase.knowledge.folder.service.KnowledgeFolderService;
import com.docbase.knowledge.folder.service.KnowledgeFolderService.FolderNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
class FolderTreeTest {

    @Autowired
    KnowledgeFolderService folderService;

    @Autowired
    KnowledgeFolderMapper folderMapper;

    @Autowired
    KnowledgeBaseService knowledgeBaseService;

    private Long createTestKnowledgeBase(Long userId) {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("Test KB " + System.nanoTime());
        return knowledgeBaseService.create(base, userId);
    }

    @Test
    void createFolder_Success() {
        Long baseId = createTestKnowledgeBase(1L);

        KnowledgeFolder folder = new KnowledgeFolder();
        folder.setParentId(0L);
        folder.setName("Root Folder");

        Long folderId = folderService.create(baseId, folder, 1L, false);

        assertThat(folderId).isNotNull();
    }

    @Test
    void getTree_ReturnsTreeStructure() {
        Long baseId = createTestKnowledgeBase(1L);

        // Create root folder
        KnowledgeFolder root = new KnowledgeFolder();
        root.setParentId(0L);
        root.setName("Root");
        Long rootId = folderService.create(baseId, root, 1L, false);

        // Create child folder
        KnowledgeFolder child = new KnowledgeFolder();
        child.setParentId(rootId);
        child.setName("Child");
        folderService.create(baseId, child, 1L, false);

        // Get tree
        List<FolderNode> tree = folderService.getTree(baseId, 1L, false);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).name()).isEqualTo("Root");
        assertThat(tree.get(0).children()).hasSize(1);
        assertThat(tree.get(0).children().get(0).name()).isEqualTo("Child");
    }

    @Test
    void createFolder_ParentInDifferentBase_Prevented() {
        Long baseId1 = createTestKnowledgeBase(1L);
        Long baseId2 = createTestKnowledgeBase(2L);

        // Create folder in base 2
        KnowledgeFolder folderInBase2 = new KnowledgeFolder();
        folderInBase2.setParentId(0L);
        folderInBase2.setName("Folder in Base 2");
        Long folderId2 = folderService.create(baseId2, folderInBase2, 2L, false);

        // Try to create folder in base 1 with parent in base 2 (should fail)
        assertThatThrownBy(() -> folderService.create(baseId1,
                createFolderRequest(folderId2, "Cross-Base Folder"), 1L, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteFolder_WithChildren_Prevented() {
        Long baseId = createTestKnowledgeBase(1L);

        // Create parent and child
        KnowledgeFolder parent = new KnowledgeFolder();
        parent.setParentId(0L);
        parent.setName("Parent With Children");
        Long parentId = folderService.create(baseId, parent, 1L, false);

        KnowledgeFolder child = new KnowledgeFolder();
        child.setParentId(parentId);
        child.setName("Child Folder");
        folderService.create(baseId, child, 1L, false);

        // Try to delete parent (should fail because it has children)
        assertThatThrownBy(() -> folderService.delete(baseId, parentId, 1L, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteFolder_Success() {
        Long baseId = createTestKnowledgeBase(1L);

        KnowledgeFolder folder = new KnowledgeFolder();
        folder.setParentId(0L);
        folder.setName("Deletable Folder");
        Long folderId = folderService.create(baseId, folder, 1L, false);

        // Delete should succeed (no children)
        folderService.delete(baseId, folderId, 1L, false);
    }

    @Test
    void deleteFolder_AndRecreate_Success() {
        Long baseId = createTestKnowledgeBase(1L);

        // Create a folder
        KnowledgeFolder folder = new KnowledgeFolder();
        folder.setParentId(0L);
        folder.setName("Recreatable Folder");
        Long folderId = folderService.create(baseId, folder, 1L, false);

        // Delete the folder
        folderService.delete(baseId, folderId, 1L, false);

        // Recreate folder with same name - should succeed (proves delete_marker was set correctly)
        // If delete_marker wasn't set, this would fail with unique constraint violation
        KnowledgeFolder newFolder = new KnowledgeFolder();
        newFolder.setParentId(0L);
        newFolder.setName("Recreatable Folder");
        Long newFolderId = folderService.create(baseId, newFolder, 1L, false);

        assertThat(newFolderId).isNotNull();
        assertThat(newFolderId).isNotEqualTo(folderId);
    }

    @Test
    void crossBaseFolderUpdate_Prevented() {
        Long baseId1 = createTestKnowledgeBase(1L);
        Long baseId2 = createTestKnowledgeBase(2L);

        // Create folder in base 2
        KnowledgeFolder folderInBase2 = new KnowledgeFolder();
        folderInBase2.setParentId(0L);
        folderInBase2.setName("Base 2 Folder");
        Long folderId2 = folderService.create(baseId2, folderInBase2, 2L, false);

        // User 1 (editor in base 1) tries to update folder in base 2
        assertThatThrownBy(() -> folderService.update(baseId1, folderId2,
                createFolderRequest(0L, "Hacked Name"), 1L, false))
                .isInstanceOf(BusinessException.class);
    }

    private KnowledgeFolder createFolderRequest(Long parentId, String name) {
        KnowledgeFolder folder = new KnowledgeFolder();
        folder.setParentId(parentId);
        folder.setName(name);
        return folder;
    }
}
