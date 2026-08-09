package com.docbase.knowledge.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.config.DocumentUploadProperties;
import com.docbase.knowledge.document.domain.KnowledgeUploadRequest;
import com.docbase.knowledge.document.mapper.KnowledgeUploadRequestMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class KnowledgeUploadRequestService {
    public static final String STATUS_UPLOADING = "UPLOADING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private final KnowledgeUploadRequestMapper mapper;
    private final DocumentUploadProperties properties;
    private final Clock clock;

    @Autowired
    public KnowledgeUploadRequestService(KnowledgeUploadRequestMapper mapper, DocumentUploadProperties properties) {
        this(mapper, properties, Clock.systemUTC());
    }

    KnowledgeUploadRequestService(KnowledgeUploadRequestMapper mapper, DocumentUploadProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(Long knowledgeBaseId, Long userId, DocumentUploadValidator.ValidatedUpload upload) {
        KnowledgeUploadRequest request = new KnowledgeUploadRequest();
        request.setKnowledgeBaseId(knowledgeBaseId);
        request.setUserId(userId);
        request.setClientRequestId(upload.clientRequestId());
        request.setRequestFingerprint(upload.fingerprint());
        request.setObjectKey(upload.objectKey());
        request.setStatus(STATUS_UPLOADING);
        request.setLeaseToken(newLeaseToken());
        request.setLeaseExpiresAt(leaseExpiresAt());
        try {
            mapper.insert(request);
            return Reservation.newReservation(request);
        } catch (DuplicateKeyException duplicate) {
            KnowledgeUploadRequest existing = find(knowledgeBaseId, userId, upload.clientRequestId());
            if (existing == null) {
                throw duplicate;
            }
            if (!existing.getRequestFingerprint().equals(upload.fingerprint())) {
                throw new BusinessException("IDEMPOTENCY_CONFLICT", "clientRequestId was already used with different metadata");
            }
            if (STATUS_COMPLETED.equals(existing.getStatus()) && existing.getDocumentId() != null) {
                return Reservation.completed(existing);
            }
            LocalDateTime now = now();
            if (STATUS_UPLOADING.equals(existing.getStatus())
                    && existing.getLeaseExpiresAt() != null && existing.getLeaseExpiresAt().isAfter(now)) {
                throw new BusinessException("UPLOAD_IN_PROGRESS", "An upload with this clientRequestId is already in progress");
            }

            String newLeaseToken = newLeaseToken();
            LocalDateTime newLeaseExpiresAt = leaseExpiresAt();
            String abandonedObjectKey = existing.getObjectKey();
            int claimed = mapper.claimExpiredLease(existing.getId(), newLeaseToken, newLeaseExpiresAt,
                    upload.objectKey(), now);
            if (claimed == 1) {
                existing.setLeaseToken(newLeaseToken);
                existing.setLeaseExpiresAt(newLeaseExpiresAt);
                existing.setObjectKey(upload.objectKey());
                return Reservation.recovered(existing, abandonedObjectKey);
            }

            KnowledgeUploadRequest current = find(knowledgeBaseId, userId, upload.clientRequestId());
            if (current != null && STATUS_COMPLETED.equals(current.getStatus()) && current.getDocumentId() != null) {
                return Reservation.completed(current);
            }
            throw new BusinessException("UPLOAD_IN_PROGRESS", "An upload with this clientRequestId is already in progress");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(Long requestId, String leaseToken) {
        return mapper.releaseIfLeaseOwner(requestId, leaseToken, now()) == 1;
    }

    private KnowledgeUploadRequest find(Long knowledgeBaseId, Long userId, String clientRequestId) {
        return mapper.selectOne(new QueryWrapper<KnowledgeUploadRequest>()
                .eq("knowledge_base_id", knowledgeBaseId)
                .eq("user_id", userId)
                .eq("client_request_id", clientRequestId));
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private LocalDateTime leaseExpiresAt() { return now().plus(properties.getLeaseDuration()); }
    private String newLeaseToken() { return UUID.randomUUID().toString(); }

    public record Reservation(KnowledgeUploadRequest request, boolean alreadyCompleted, String abandonedObjectKey) {
        static Reservation newReservation(KnowledgeUploadRequest request) { return new Reservation(request, false, null); }
        static Reservation completed(KnowledgeUploadRequest request) { return new Reservation(request, true, null); }
        static Reservation recovered(KnowledgeUploadRequest request, String abandonedObjectKey) {
            return new Reservation(request, false, abandonedObjectKey);
        }
    }
}
