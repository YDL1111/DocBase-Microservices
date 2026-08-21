package com.docbase.ingest.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.docbase.ingest.event.IngestEventPublisher;
import com.docbase.ingest.task.domain.IngestTask;
import com.docbase.ingest.task.mapper.ConsumedEventMapper;
import com.docbase.ingest.task.mapper.IngestTaskMapper;
import com.docbase.ingest.task.mapper.RagOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestTaskServiceTest {

    @Mock IngestTaskMapper taskMapper;
    @Mock ConsumedEventMapper consumedEventMapper;
    @Mock RagOutboxMapper ragOutboxMapper;
    @Mock IngestEventPublisher eventPublisher;

    private IngestTaskService service;

    @BeforeEach
    void setUp() {
        service = new IngestTaskService(taskMapper, consumedEventMapper, ragOutboxMapper,
                eventPublisher, new ObjectMapper());
    }

    @Test
    void markSucceeded_explicitlyClearsStaleErrorAndRetryTime() {
        IngestTask task = task(1L, IngestTaskStatus.DISPATCHED, "Unknown error");
        when(taskMapper.selectById(1L)).thenReturn(task);

        service.markSucceeded(1L, 5);

        ArgumentCaptor<Wrapper<IngestTask>> wrapper = wrapperCaptor();
        verify(taskMapper).update(isNull(), wrapper.capture());
        String sqlSet = ((UpdateWrapper<IngestTask>) wrapper.getValue()).getSqlSet();
        assertThat(sqlSet).contains("last_error", "next_retry_at", "chunk_count", "finished_at");
        assertThat(task.getLastError()).isNull();
        assertThat(task.getStatus()).isEqualTo(IngestTaskStatus.SUCCEEDED.name());
    }

    @Test
    void retryTask_explicitlyClearsStaleErrorAndRetryTime() {
        IngestTask task = task(2L, IngestTaskStatus.FAILED, "previous failure");
        when(taskMapper.selectById(2L)).thenReturn(task);

        service.retryTask(2L);

        ArgumentCaptor<Wrapper<IngestTask>> wrapper = wrapperCaptor();
        verify(taskMapper).update(isNull(), wrapper.capture());
        String sqlSet = ((UpdateWrapper<IngestTask>) wrapper.getValue()).getSqlSet();
        assertThat(sqlSet).contains("last_error", "next_retry_at", "status");
        assertThat(task.getLastError()).isNull();
    }

    private IngestTask task(Long id, IngestTaskStatus status, String error) {
        IngestTask task = new IngestTask();
        task.setId(id);
        task.setStatus(status.name());
        task.setLastError(error);
        task.setKnowledgeBaseId(1L);
        task.setDocumentId(1L);
        task.setTaskType(IngestTaskType.IMPORT.name());
        task.setAttemptCount(1);
        task.setCreatedBy(1L);
        return task;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<Wrapper<IngestTask>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }
}
