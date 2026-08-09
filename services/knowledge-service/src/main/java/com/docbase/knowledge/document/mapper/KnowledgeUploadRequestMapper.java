package com.docbase.knowledge.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.knowledge.document.domain.KnowledgeUploadRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface KnowledgeUploadRequestMapper extends BaseMapper<KnowledgeUploadRequest> {

    @Update("UPDATE knowledge_upload_request SET lease_token = #{newLeaseToken}, lease_expires_at = #{newLeaseExpiresAt}, " +
            "object_key = #{newObjectKey} WHERE id = #{id} AND status = 'UPLOADING' " +
            "AND (lease_expires_at IS NULL OR lease_expires_at <= #{now})")
    int claimExpiredLease(@Param("id") Long id, @Param("newLeaseToken") String newLeaseToken,
                          @Param("newLeaseExpiresAt") LocalDateTime newLeaseExpiresAt,
                          @Param("newObjectKey") String newObjectKey, @Param("now") LocalDateTime now);

    @Update("UPDATE knowledge_upload_request SET status = 'COMPLETED', document_id = #{documentId}, " +
            "lease_token = NULL, lease_expires_at = NULL WHERE id = #{id} AND lease_token = #{leaseToken} " +
            "AND status = 'UPLOADING'")
    int completeIfLeaseOwner(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                             @Param("documentId") Long documentId);

    @Update("UPDATE knowledge_upload_request SET lease_expires_at = #{releasedAt} WHERE id = #{id} " +
            "AND lease_token = #{leaseToken} AND status = 'UPLOADING'")
    int releaseIfLeaseOwner(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                            @Param("releasedAt") LocalDateTime releasedAt);
}
