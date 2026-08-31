package com.docbase.knowledge.document.service;

import com.docbase.contracts.KnowledgeEvent;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.document.domain.KnowledgeDocumentVersion;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentAclMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentVersionMapper;
import com.docbase.knowledge.document.mapper.KnowledgeUploadRequestMapper;
import com.docbase.knowledge.event.OutboxService;
import com.docbase.knowledge.folder.mapper.KnowledgeFolderMapper;
import com.docbase.knowledge.permission.KnowledgePermissionService;
import com.docbase.knowledge.storage.KnowledgeObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentOperationsTest {
    @Mock KnowledgeDocumentMapper documentMapper;
    @Mock KnowledgeFolderMapper folderMapper;
    @Mock KnowledgeDocumentVersionMapper versionMapper;
    @Mock KnowledgeDocumentAclMapper aclMapper;
    @Mock KnowledgeUploadRequestMapper uploadRequestMapper;
    @Mock KnowledgePermissionService permissionService;
    @Mock OutboxService outboxService;
    @Mock KnowledgeObjectStorageService objectStorageService;

    private KnowledgeDocumentService service;
    private KnowledgeDocument document;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentService(documentMapper, folderMapper, versionMapper, aclMapper,
                uploadRequestMapper, permissionService, outboxService, objectStorageService);
        document = new KnowledgeDocument();
        document.setId(8L);
        document.setKnowledgeBaseId(3L);
        document.setVersion(1);
        document.setIngestStatus(3);
        document.setOriginalFilename("manual.pdf");
        document.setObjectKey("knowledge/3/manual.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(12L);
        document.setChecksum("abc");
        document.setDeleted(0);
    }

    @Test
    void openContent_returnsAuthorizedObjectStream() {
        when(documentMapper.selectById(8L)).thenReturn(document);
        when(permissionService.hasPermission(3L, 7L, false, 3)).thenReturn(true);
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[] {1, 2});
        when(objectStorageService.openObject(document.getObjectKey())).thenReturn(stream);

        KnowledgeDocumentService.DocumentContent content = service.openContent(8L, 7L, false);

        assertThat(content.document()).isSameAs(document);
        assertThat(content.inputStream()).isSameAs(stream);
        verify(permissionService).requireViewAccess(3L, 7L, null, false);
    }

    @Test
    void reingest_createsNewVersionAndDedicatedOutboxEvent() {
        when(documentMapper.selectActiveByIdForUpdate(8L)).thenReturn(document);
        when(versionMapper.insert(any(KnowledgeDocumentVersion.class))).thenAnswer(invocation -> {
            KnowledgeDocumentVersion version = invocation.getArgument(0);
            version.setId(88L);
            return 1;
        });

        service.reingest(8L, 7L, false);

        verify(documentMapper).selectActiveByIdForUpdate(8L);
        assertThat(document.getVersion()).isEqualTo(2);
        assertThat(document.getIngestStatus()).isEqualTo(1);
        ArgumentCaptor<KnowledgeEvent> event = ArgumentCaptor.forClass(KnowledgeEvent.class);
        verify(outboxService).writeEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(KnowledgeEvent.REINGEST_REQUESTED);
        assertThat(event.getValue().versionId()).isEqualTo(88L);
    }
}
