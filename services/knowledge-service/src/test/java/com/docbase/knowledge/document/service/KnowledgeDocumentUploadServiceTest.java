package com.docbase.knowledge.document.service;

import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.document.domain.KnowledgeUploadRequest;
import com.docbase.knowledge.storage.KnowledgeObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentUploadServiceTest {

    @Mock DocumentUploadValidator validator;
    @Mock KnowledgeDocumentService documentService;
    @Mock KnowledgeUploadRequestService requestService;
    @Mock KnowledgeObjectStorageService objectStorageService;

    @Test
    void putObjectFailureCompensatesThenReleasesLeaseAndKeepsOriginalException() {
        KnowledgeDocumentUploadService service = serviceForUploadFailure();
        BusinessException original = new BusinessException("OBJECT_STORAGE_UPLOAD_FAILED", "upload failed");
        doThrow(original).when(objectStorageService).putObject(anyString(), any(), anyString());

        assertThatThrownBy(() -> service.upload(1L, file(), null, 0L, null, true, "request-1", 2L, false))
                .isSameAs(original);

        InOrder order = inOrder(objectStorageService, requestService);
        order.verify(objectStorageService).putObject(anyString(), any(), anyString());
        order.verify(objectStorageService).deleteObjectBestEffort(anyString());
        order.verify(requestService).release(anyLong(), anyString());
    }

    @Test
    void cleanupFailuresAfterPutObjectFailureDoNotReplaceOriginalException() {
        KnowledgeDocumentUploadService service = serviceForUploadFailure();
        BusinessException original = new BusinessException("OBJECT_STORAGE_UPLOAD_FAILED", "upload failed");
        doThrow(original).when(objectStorageService).putObject(anyString(), any(), anyString());
        doThrow(new RuntimeException("delete failed")).when(objectStorageService).deleteObjectBestEffort(anyString());
        doThrow(new RuntimeException("release failed")).when(requestService).release(anyLong(), anyString());

        assertThatThrownBy(() -> service.upload(1L, file(), null, 0L, null, true, "request-1", 2L, false))
                .isSameAs(original);
    }

    private KnowledgeDocumentUploadService serviceForUploadFailure() {
        DocumentUploadValidator.UploadMetadata metadata = new DocumentUploadValidator.UploadMetadata(
                "file", 0L, 1, "file.pdf", "file.pdf", "application/pdf", 3L, "request-1", 1L);
        DocumentUploadValidator.ValidatedUpload upload = new DocumentUploadValidator.ValidatedUpload(
                "file", 0L, 1, "file.pdf", "file.pdf", "application/pdf", 3L, "checksum", "request-1",
                "knowledge/1/attempt/file.pdf", "fingerprint");
        KnowledgeUploadRequest request = new KnowledgeUploadRequest();
        request.setId(9L);
        request.setObjectKey(upload.objectKey());
        request.setLeaseToken("lease-token");
        when(validator.validateMetadata(any(), any(), any(), any(), any(), any())).thenReturn(metadata);
        when(validator.completeWithChecksum(any(), any())).thenReturn(upload);
        when(requestService.reserve(1L, 2L, upload))
                .thenReturn(new KnowledgeUploadRequestService.Reservation(request, false, null));
        return new KnowledgeDocumentUploadService(validator, documentService, requestService, objectStorageService);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "file.pdf", "application/pdf", new byte[] {1, 2, 3});
    }
}
