package com.docbase.ingest.task;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.ApiResponse;
import com.docbase.ingest.task.domain.IngestTask;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Management API for ingest tasks.
 */
@RestController
@RequestMapping("/api/ingest/tasks")
public class IngestTaskController {

    private final IngestTaskService taskService;

    public IngestTaskController(IngestTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ingest:task:list') or hasAuthority('admin:all')")
    public ApiResponse<Page<IngestTask>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(taskService.listTasks(current, size, status));
    }

    @GetMapping("/{taskId}")
    @PreAuthorize("hasAuthority('ingest:task:view') or hasAuthority('admin:all')")
    public ApiResponse<IngestTask> get(@PathVariable Long taskId) {
        IngestTask task = taskService.getById(taskId);
        if (task == null) {
            throw new com.docbase.common.core.BusinessException("TASK_NOT_FOUND", "Task not found");
        }
        return ApiResponse.success(task);
    }

    @PostMapping("/{taskId}/retry")
    @PreAuthorize("hasAuthority('ingest:task:retry') or hasAuthority('admin:all')")
    public ApiResponse<Void> retry(@PathVariable Long taskId) {
        taskService.retryTask(taskId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{taskId}/cancel")
    @PreAuthorize("hasAuthority('ingest:task:cancel') or hasAuthority('admin:all')")
    public ApiResponse<Void> cancel(@PathVariable Long taskId) {
        taskService.cancelTask(taskId);
        return ApiResponse.success(null);
    }
}
