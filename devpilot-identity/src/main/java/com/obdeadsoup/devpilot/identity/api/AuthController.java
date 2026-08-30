package com.obdeadsoup.devpilot.identity.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.api.dto.LoginRequest;
import com.obdeadsoup.devpilot.identity.api.dto.LoginResponse;
import com.obdeadsoup.devpilot.identity.api.dto.RegisterRequest;
import com.obdeadsoup.devpilot.identity.api.dto.EmailVerificationRequest;
import com.obdeadsoup.devpilot.identity.api.dto.UserResponse;
import com.obdeadsoup.devpilot.identity.application.AuthenticationResult;
import com.obdeadsoup.devpilot.identity.application.AuthenticationService;
import com.obdeadsoup.devpilot.identity.application.RegistrationService;
import com.obdeadsoup.devpilot.identity.application.VerificationCodeService;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.identity.security.BearerTokenResolver;
import com.obdeadsoup.devpilot.identity.security.InvalidAccessTokenAuthenticationException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final RegistrationService registrationService;
    private final VerificationCodeService verificationCodeService;
    private final BearerTokenResolver bearerTokenResolver;

    public AuthController(
            AuthenticationService authenticationService,
            RegistrationService registrationService,
            VerificationCodeService verificationCodeService,
            BearerTokenResolver bearerTokenResolver
    ) {
        this.authenticationService = authenticationService;
        this.registrationService = registrationService;
        this.verificationCodeService = verificationCodeService;
        this.bearerTokenResolver = bearerTokenResolver;
    }

    /** 创建本地账号；注册成功后由浏览器显式登录，避免引入第二种会话语义。 */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.success(UserResponse.from(
                registrationService.register(request.username(), request.email(), request.password(), request.verificationCode())
        )));
    }

    /** 匿名获取注册验证码；IP 只用于 Redis 基础防刷，不参与用户身份判断。 */
    @PostMapping("/verification/email")
    public ApiResponse<Void> sendVerificationCode(@Valid @RequestBody EmailVerificationRequest request,
                                                   HttpServletRequest servletRequest) {
        verificationCodeService.issue(request.email(), servletRequest.getRemoteAddr());
        return ApiResponse.success(null);
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthenticationResult result = authenticationService.login(request.login(), request.password());
        return ApiResponse.success(new LoginResponse(
                result.accessToken(),
                "Bearer",
                result.expiresInSeconds(),
                UserResponse.from(result.principal())
        ));
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> currentUser(
            @AuthenticationPrincipal DevPilotUserPrincipal principal
    ) {
        return ApiResponse.success(UserResponse.from(principal));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        try {
            String accessToken = bearerTokenResolver.resolve(authorizationHeader)
                    .orElseThrow(InvalidAccessTokenAuthenticationException::new);
            authenticationService.logout(accessToken);
            return ApiResponse.success(null);
        } catch (InvalidAccessTokenAuthenticationException exception) {
            throw new BusinessException(IdentityErrorCode.INVALID_ACCESS_TOKEN);
        }
    }
}
