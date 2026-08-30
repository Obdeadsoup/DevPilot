package com.obdeadsoup.devpilot.project.api.dto;

import com.obdeadsoup.devpilot.project.domain.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

public record InviteWorkspaceMemberRequest(@Email @NotNull String email, @NotNull WorkspaceRole role) { }
