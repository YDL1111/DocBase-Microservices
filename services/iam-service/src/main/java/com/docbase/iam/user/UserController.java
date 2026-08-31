package com.docbase.iam.user;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.docbase.common.core.ApiResponse;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.dto.ChangeUserStatusRequest;
import com.docbase.iam.user.dto.CreateUserRequest;
import com.docbase.iam.user.dto.ResetPasswordRequest;
import com.docbase.iam.user.dto.UpdateUserRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:user:list') or hasAuthority('admin:all')")
    ApiResponse<Page<SysUser>> list(@RequestParam(defaultValue = "1") long current,
                                   @RequestParam(defaultValue = "20") long size,
                                   @RequestParam(required = false) String username) {
        return ApiResponse.success(userService.page(current, size, username));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('system:user:list') or hasAuthority('admin:all')")
    ApiResponse<SysUser> get(@PathVariable Long userId) {
        return ApiResponse.success(userService.getById(userId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:user:create') or hasAuthority('admin:all')")
    ApiResponse<Long> create(@Valid @RequestBody CreateUserRequest request) {
        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setOrganizationId(request.organizationId());
        user.setNickname(request.nickname());
        user.setPassword(request.password());
        user.setEmail(request.email());
        user.setPhoneNumber(request.phoneNumber());
        user.setSex(request.sex());
        user.setStatus(request.status());
        user.setRemark(request.remark());
        return ApiResponse.success(userService.create(user, request.roleIds()));
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('system:user:update') or hasAuthority('admin:all')")
    ApiResponse<Void> update(@PathVariable Long userId, @Valid @RequestBody UpdateUserRequest request) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setOrganizationId(request.organizationId());
        user.setNickname(request.nickname());
        user.setEmail(request.email());
        user.setPhoneNumber(request.phoneNumber());
        user.setSex(request.sex());
        user.setRemark(request.remark());
        userService.update(user, request.roleIds());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('system:user:delete') or hasAuthority('admin:all')")
    ApiResponse<Void> delete(@PathVariable Long userId) {
        userService.delete(userId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{userId}/status")
    @PreAuthorize("hasAuthority('system:user:update') or hasAuthority('admin:all')")
    ApiResponse<Void> changeStatus(@PathVariable Long userId, @Valid @RequestBody ChangeUserStatusRequest request) {
        userService.changeStatus(userId, request.status());
        return ApiResponse.success(null);
    }

    @PutMapping("/{userId}/password")
    @PreAuthorize("hasAuthority('system:user:reset-password') or hasAuthority('admin:all')")
    ApiResponse<Void> resetPassword(@PathVariable Long userId, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(userId, request.password());
        return ApiResponse.success(null);
    }

    @GetMapping("/{userId}/roles")
    @PreAuthorize("hasAuthority('system:user:list') or hasAuthority('admin:all')")
    ApiResponse<List<Long>> getRoles(@PathVariable Long userId) {
        return ApiResponse.success(userService.getRoleIds(userId));
    }
}
