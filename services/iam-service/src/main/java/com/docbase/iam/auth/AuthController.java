package com.docbase.iam.auth;

import com.docbase.iam.auth.dto.AuthResult;
import com.docbase.iam.auth.dto.AdminSetupRequest;
import com.docbase.iam.auth.dto.AdminSetupStatus;
import com.docbase.iam.auth.dto.LoginRequest;
import com.docbase.iam.auth.dto.RegisterRequest;
import com.docbase.iam.auth.dto.MenuNode;
import com.docbase.iam.auth.dto.UserInfo;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.common.core.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AdminSetupService adminSetupService;
    private final RegistrationService registrationService;

    public AuthController(AuthService authService, AdminSetupService adminSetupService,
                          RegistrationService registrationService) {
        this.authService = authService;
        this.adminSetupService = adminSetupService;
        this.registrationService = registrationService;
    }

    @GetMapping("/setup")
    ApiResponse<AdminSetupStatus> setupStatus() {
        return ApiResponse.success(adminSetupService.status());
    }

    @PostMapping("/setup")
    ApiResponse<Long> setup(@Valid @RequestBody AdminSetupRequest request) {
        return ApiResponse.success(adminSetupService.setup(request));
    }

    @PostMapping("/login")
    ApiResponse<AuthResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @GetMapping("/registration")
    ApiResponse<Boolean> registrationStatus() {
        return ApiResponse.success(registrationService.enabled());
    }

    @PostMapping("/register")
    ApiResponse<Long> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(registrationService.register(request));
    }

    @PostMapping("/refresh")
    ApiResponse<AuthResult> refresh(@RequestBody RefreshRequest request) {
        return ApiResponse.success(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(@RequestBody(required = false) RefreshRequest request,
                             @AuthenticationPrincipal IamUserPrincipal principal) {
        String refreshToken = request != null ? request.refreshToken() : null;
        Long userId = principal != null ? principal.userId() : null;
        authService.logout(refreshToken, userId);
        return ApiResponse.success(null);
    }

    @GetMapping("/me")
    ApiResponse<UserInfo> me(@AuthenticationPrincipal IamUserPrincipal principal) {
        return ApiResponse.success(authService.currentUserInfo(principal.userId()));
    }

    @GetMapping("/permissions")
    ApiResponse<Set<String>> permissions(@AuthenticationPrincipal IamUserPrincipal principal) {
        return ApiResponse.success(authService.permissions(principal.userId()));
    }

    @GetMapping("/menus")
    ApiResponse<List<MenuNode>> menus(@AuthenticationPrincipal IamUserPrincipal principal) {
        return ApiResponse.success(authService.menuTree(principal.userId()));
    }

    public record RefreshRequest(String refreshToken) {}
}
