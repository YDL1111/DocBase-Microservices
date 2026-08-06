package com.docbase.ingest.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.docbase.ingest.task.domain.RagOutboxEntity;
import org.apache.ibatis.annotations.Update;

public interface RagOutboxMapper extends BaseMapper<RagOutboxEntity> {

    /**
     * Atomically claims an event for publishing by this instance.
     * Sets status to PUBLISHING to prevent other instances from picking it up.
     */
    @Update("UPDATE rag_outbox SET status = 'PUBLISHING', claimed_at = NOW(), published_by = #{instanceId} " +
            "WHERE event_id = #{eventId} AND status IN ('PENDING', 'FAILED')")
    int claimForPublishing(String eventId, String instanceId);

    /**
     * Marks an event as published after successful RabbitMQ confirm.
     */
    @Update("UPDATE rag_outbox SET status = 'PUBLISHED', published_at = NOW() " +
            "WHERE event_id = #{eventId} AND status = 'PUBLISHING'")
    int markPublished(String eventId);

    /**
     * Marks an event as failed or DEAD based on retry count.
     * If nextRetryAt is null or max retries exceeded, sets status to DEAD.
     * Otherwise sets status to FAILED and schedules retry.
     */
    @Update("<script>" +
            "UPDATE rag_outbox SET " +
            "<if test='nextRetryAt != null'>" +
            "  status = 'FAILED', retry_count = retry_count + 1, " +
            "  last_error = #{error}, next_retry_at = #{nextRetryAt} " +
            "</if>" +
            "<if test='nextRetryAt == null'>" +
            "  status = 'DEAD', retry_count = retry_count + 1, " +
            "  last_error = #{error} " +
            "</if>" +
            "WHERE event_id = #{eventId} AND status = 'PUBLISHING'" +
            "</script>")
    int markFailed(String eventId, String error, java.time.LocalDateTime nextRetryAt);

    /**
     * Recovers stale PUBLISHING events that have been claimed but not published
     * within the timeout period.
     */
    @Update("UPDATE rag_outbox SET status = 'FAILED', last_error = 'Recovered from stale PUBLISHING state' " +
            "WHERE status = 'PUBLISHING' AND claimed_at < #{cutoff}")
    int recoverStalePublishingEvents(java.time.LocalDateTime cutoff);
}
