package com.docbase.knowledge.document.service;

import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.storage.KnowledgeObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Coordinates validation, object storage, database registration and compensation for multipart uploads. */
@Service
public class KnowledgeDocumentUploadService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentUploadService.class);
    private final DocumentUploadValidator validator;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeUploadRequestService uploadRequestService;
    private final KnowledgeObjectStorageService objectStorageService;

    public KnowledgeDocumentUploadService(DocumentUploadValidator validator,
                                          KnowledgeDocumentService documentService,
                                          KnowledgeUploadRequestService uploadRequestService,
                                          KnowledgeObjectStorageService objectStorageService) {
        this.validator = validator;
        this.documentService = documentService;
        this.uploadRequestService = uploadRequestService;
        this.objectStorageService = objectStorageService;
    }

    public Long upload(Long knowledgeBaseId, MultipartFile file, String title, Long folderId, Integer visibility,
                       boolean publishForChat, String clientRequestId, Long userId, boolean isAdmin) {
        return upload(knowledgeBaseId, file, title, folderId, visibility, publishForChat,
                clientRequestId, userId, null, isAdmin);
    }

    public Long upload(Long knowledgeBaseId, MultipartFile file, String title, Long folderId, Integer visibility,
                       boolean publishForChat, String clientRequestId, Long userId,
                       Long organizationId, boolean isAdmin) {
        if (visibility != null && visibility == 2 && organizationId == null) {
            throw new com.docbase.common.core.BusinessException(
                    "ORGANIZATION_REQUIRED", "department visibility requires an organization");
        }
        DocumentUploadValidator.UploadMetadata metadata = validator.validateMetadata(
                file, title, folderId, visibility, clientRequestId, knowledgeBaseId);
        documentService.validateUploadContext(knowledgeBaseId, metadata.folderId(), userId, isAdmin);
        DocumentUploadValidator.ValidatedUpload validated = withPublishPreference(
                validator.completeWithChecksum(file, metadata), publishForChat);

        KnowledgeUploadRequestService.Reservation reservation = uploadRequestService.reserve(knowledgeBaseId, userId, validated);
        if (reservation.alreadyCompleted()) {
            return reservation.request().getDocumentId();
        }
        if (reservation.abandonedObjectKey() != null) {
            compensateBestEffort(reservation.abandonedObjectKey(), "stale lease recovery");
        }

        String objectKey = reservation.request().getObjectKey();
        String leaseToken = reservation.request().getLeaseToken();
        try {
            objectStorageService.putObject(objectKey, file, validated.contentType());
        } catch (RuntimeException exception) {
            // A storage client can fail after the object has already been persisted.  Clean up
            // this attempt before making its lease available to a subsequent request.
            compensateBestEffort(objectKey, "object upload failure");
            releaseLeaseBestEffort(reservation.request().getId(), leaseToken, exception);
            throw exception;
        }

        KnowledgeDocument document = new KnowledgeDocument();
        document.setOrganizationId(organizationId);
        document.setFolderId(validated.folderId());
        document.setTitle(validated.title());
        document.setOriginalFilename(validated.originalFilename());
        document.setObjectKey(objectKey);
        document.setContentType(validated.contentType());
        document.setFileSize(validated.fileSize());
        document.setChecksum(validated.checksum());
        document.setVisibility(validated.visibility());
        document.setStatus(publishForChat ? 2 : 1);
        try {
            return documentService.registerUploadedDocument(knowledgeBaseId, document, reservation.request().getId(), leaseToken, userId, isAdmin);
        } catch (RuntimeException exception) {
            compensateBestEffort(objectKey, "registration failure");
            releaseLeaseBestEffort(reservation.request().getId(), leaseToken, exception);
            throw exception;
        }
    }

    private DocumentUploadValidator.ValidatedUpload withPublishPreference(
            DocumentUploadValidator.ValidatedUpload upload, boolean publishForChat) {
        String fingerprint;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((upload.fingerprint() + "\n" + publishForChat)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
        return new DocumentUploadValidator.ValidatedUpload(upload.title(), upload.folderId(), upload.visibility(),
                upload.originalFilename(), upload.safeFilename(), upload.contentType(), upload.fileSize(),
                upload.checksum(), upload.clientRequestId(), upload.objectKey(), fingerprint);
    }

    private void releaseLeaseBestEffort(Long requestId, String leaseToken, RuntimeException originalException) {
        try {
            uploadRequestService.release(requestId, leaseToken);
        } catch (RuntimeException cleanupException) {
            log.warn("Knowledge upload lease release failed after {}", originalException.getClass().getSimpleName());
        }
    }

    private void compensateBestEffort(String objectKey, String reason) {
        try {
            objectStorageService.deleteObjectBestEffort(objectKey);
        } catch (RuntimeException cleanupException) {
            log.warn("Knowledge upload compensation failed after {}", reason);
        }
    }
}
