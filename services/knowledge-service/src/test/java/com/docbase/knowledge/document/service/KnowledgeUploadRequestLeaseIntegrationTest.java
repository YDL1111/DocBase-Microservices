package com.docbase.knowledge.document.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.document.domain.KnowledgeUploadRequest;
import com.docbase.knowledge.document.mapper.KnowledgeUploadRequestMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@ActiveProfiles("test")
class KnowledgeUploadRequestLeaseIntegrationTest {

    @Autowired KnowledgeUploadRequestService requestService;
    @Autowired KnowledgeUploadRequestMapper requestMapper;

    @Test
    void unexpiredLeaseReturnsInProgress() {
        RequestContext context = context();
        KnowledgeUploadRequestService.Reservation initial = requestService.reserve(context.baseId(), context.userId(), upload(context, "first"));

        Throwable error = capture(() -> requestService.reserve(context.baseId(), context.userId(), upload(context, "second")));

        assertThat(error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error).code()).isEqualTo("UPLOAD_IN_PROGRESS");
        assertThat(initial.request().getLeaseExpiresAt()).isAfter(LocalDateTime.now(Clock.systemUTC()).minusSeconds(1));
    }

    @Test
    void expiredLeaseCanBeRecoveredAndOldTokenCannotCompleteOrReleaseIt() {
        RequestContext context = context();
        KnowledgeUploadRequestService.Reservation oldAttempt = requestService.reserve(context.baseId(), context.userId(), upload(context, "old"));
        expire(oldAttempt.request().getId());

        KnowledgeUploadRequestService.Reservation recovered = requestService.reserve(context.baseId(), context.userId(), upload(context, "new"));

        assertThat(recovered.alreadyCompleted()).isFalse();
        assertThat(recovered.abandonedObjectKey()).isEqualTo(oldAttempt.request().getObjectKey());
        assertThat(recovered.request().getLeaseToken()).isNotEqualTo(oldAttempt.request().getLeaseToken());
        assertThat(requestMapper.completeIfLeaseOwner(oldAttempt.request().getId(), oldAttempt.request().getLeaseToken(), 999L)).isZero();
        assertThat(requestService.release(oldAttempt.request().getId(), oldAttempt.request().getLeaseToken())).isFalse();
        assertThat(requestMapper.completeIfLeaseOwner(recovered.request().getId(), recovered.request().getLeaseToken(), 123L)).isEqualTo(1);

        KnowledgeUploadRequestService.Reservation completed = requestService.reserve(context.baseId(), context.userId(), upload(context, "again"));
        assertThat(completed.alreadyCompleted()).isTrue();
        assertThat(completed.request().getDocumentId()).isEqualTo(123L);
    }

    @Test
    void v6UploadingRecordBackfilledByV7UtcTimestampIsImmediatelyRecoverable() {
        RequestContext context = context();
        KnowledgeUploadRequest legacy = new KnowledgeUploadRequest();
        legacy.setKnowledgeBaseId(context.baseId());
        legacy.setUserId(context.userId());
        legacy.setClientRequestId(context.clientRequestId());
        legacy.setRequestFingerprint("b".repeat(64));
        legacy.setObjectKey("knowledge/legacy/file.pdf");
        legacy.setStatus(KnowledgeUploadRequestService.STATUS_UPLOADING);
        // This models V7's UTC_TIMESTAMP() backfill. The next UTC application read is eligible to recover it.
        legacy.setLeaseExpiresAt(LocalDateTime.now(Clock.systemUTC()).minusSeconds(1));
        requestMapper.insert(legacy);

        KnowledgeUploadRequestService.Reservation recovered = requestService.reserve(
                context.baseId(), context.userId(), upload(context, "recovered"));

        assertThat(recovered.alreadyCompleted()).isFalse();
        assertThat(recovered.request().getLeaseToken()).isNotBlank();
        assertThat(recovered.abandonedObjectKey()).isEqualTo("knowledge/legacy/file.pdf");
    }

    @Test
    void onlyOneConcurrentRecoveryClaimsExpiredLease() throws Exception {
        RequestContext context = context();
        KnowledgeUploadRequestService.Reservation oldAttempt = requestService.reserve(context.baseId(), context.userId(), upload(context, "old"));
        expire(oldAttempt.request().getId());

        List<Object> outcomes = concurrently(4, () -> {
            try {
                return requestService.reserve(context.baseId(), context.userId(), upload(context, UUID.randomUUID().toString()));
            } catch (RuntimeException exception) {
                return exception;
            }
        });
        long recovered = outcomes.stream().filter(KnowledgeUploadRequestService.Reservation.class::isInstance)
                .map(KnowledgeUploadRequestService.Reservation.class::cast).filter(r -> !r.alreadyCompleted()).count();
        long inProgress = outcomes.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast).filter(e -> "UPLOAD_IN_PROGRESS".equals(e.code())).count();

        assertThat(recovered).isEqualTo(1);
        assertThat(inProgress).isEqualTo(3);
    }

    @Test
    void onlyOneConcurrentInitialReservationWinsAndCrashCanRecover() throws Exception {
        RequestContext context = context();
        List<Object> outcomes = concurrently(2, () -> {
            try {
                return requestService.reserve(context.baseId(), context.userId(), upload(context, UUID.randomUUID().toString()));
            } catch (RuntimeException exception) {
                return exception;
            }
        });
        List<KnowledgeUploadRequestService.Reservation> reservations = outcomes.stream()
                .filter(KnowledgeUploadRequestService.Reservation.class::isInstance)
                .map(KnowledgeUploadRequestService.Reservation.class::cast).toList();
        assertThat(reservations).hasSize(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance).map(BusinessException.class::cast)
                .map(BusinessException::code)).containsExactly("UPLOAD_IN_PROGRESS");

        expire(reservations.getFirst().request().getId()); // Simulates a process crash after reservation.
        KnowledgeUploadRequestService.Reservation recovered = requestService.reserve(context.baseId(), context.userId(), upload(context, "recovered"));
        assertThat(recovered.alreadyCompleted()).isFalse();
        assertThat(recovered.request().getLeaseToken()).isNotEqualTo(reservations.getFirst().request().getLeaseToken());
    }

    private void expire(Long requestId) {
        requestMapper.update(null, new UpdateWrapper<KnowledgeUploadRequest>()
                .eq("id", requestId).set("lease_expires_at", LocalDateTime.now(Clock.systemUTC()).minusSeconds(1)));
    }

    private RequestContext context() {
        long unique = Math.abs(UUID.randomUUID().getMostSignificantBits());
        return new RequestContext(unique, unique + 1, "lease-" + unique);
    }

    private DocumentUploadValidator.ValidatedUpload upload(RequestContext context, String objectSuffix) {
        return new DocumentUploadValidator.ValidatedUpload("title", 0, 1, "file.pdf", "file.pdf", "application/pdf", 3,
                "a".repeat(64), context.clientRequestId(), "knowledge/" + context.baseId() + "/" + objectSuffix + "/file.pdf", "b".repeat(64));
    }

    private List<Object> concurrently(int threads, ThrowingSupplier supplier) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return supplier.get();
                }));
            }
            ready.await();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Throwable capture(ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected exception");
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    private record RequestContext(Long baseId, Long userId, String clientRequestId) { }
    @FunctionalInterface private interface ThrowingSupplier { Object get() throws Exception; }
    @FunctionalInterface private interface ThrowingRunnable { void run(); }
}
