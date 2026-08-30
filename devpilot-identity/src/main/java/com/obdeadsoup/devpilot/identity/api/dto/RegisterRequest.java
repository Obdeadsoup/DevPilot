package com.obdeadsoup.devpilot.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 公开注册请求；密码只在认证服务内用于生成不可逆 Hash。 */
public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*", message = "username contains unsupported characters")
        String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank
        @Size(min = 12, max = 72)
        @Pattern(regexp = ".*[A-Za-z].*", message = "password must contain a letter")
        @Pattern(regexp = ".*\\d.*", message = "password must contain a digit")
        String password,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "verificationCode must be six digits") String verificationCode
) {
}
