package com.obdeadsoup.devpilot.project.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.project.api.dto.InviteWorkspaceMemberRequest;
import com.obdeadsoup.devpilot.project.api.dto.VersionRequest;
import com.obdeadsoup.devpilot.project.api.dto.WorkspaceMemberResponse;
import com.obdeadsoup.devpilot.project.application.WorkspaceMemberService;
import com.obdeadsoup.devpilot.project.domain.WorkspaceRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 成员 HTTP 契约仅编排请求；所有身份校验、RBAC 与状态转换在 WorkspaceMemberService 中完成。 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/members")
public class WorkspaceMemberController {
    private final WorkspaceMemberService memberService;

    public WorkspaceMemberController(WorkspaceMemberService memberService) { this.memberService = memberService; }

    @GetMapping
    public ApiResponse<List<WorkspaceMemberResponse>> list(@PathVariable @Positive long workspaceId) {
        return ApiResponse.success(memberService.listMembers(workspaceId).stream()
                .map(WorkspaceMemberResponse::from).toList());
    }

    @PostMapping("/invitations")
    public ApiResponse<Void> invite(@PathVariable @Positive long workspaceId,
                                    @Valid @RequestBody InviteWorkspaceMemberRequest request) {
        memberService.inviteMemberByEmail(workspaceId, request.email(), request.role());
        return ApiResponse.success(null);
    }

    @PostMapping("/invitations/accept")
    public ApiResponse<Void> accept(@PathVariable @Positive long workspaceId,
                                    @Valid @RequestBody VersionRequest request) {
        memberService.acceptOwnInvitation(workspaceId, request.expectedVersion());
        return ApiResponse.success(null);
    }

    @PostMapping("/invitations/reject")
    public ApiResponse<Void> reject(@PathVariable @Positive long workspaceId,
                                    @Valid @RequestBody VersionRequest request) {
        memberService.rejectOwnInvitation(workspaceId, request.expectedVersion());
        return ApiResponse.success(null);
    }

    @PutMapping("/{userId}/role")
    public ApiResponse<Void> changeRole(@PathVariable @Positive long workspaceId, @PathVariable @Positive long userId,
                                        @RequestParam @NotNull WorkspaceRole role,
                                        @Valid @RequestBody VersionRequest request) {
        memberService.changeMemberRole(workspaceId, userId, role, request.expectedVersion());
        return ApiResponse.success(null);
    }

    @PostMapping("/{userId}/remove")
    public ApiResponse<Void> remove(@PathVariable @Positive long workspaceId, @PathVariable @Positive long userId,
                                    @Valid @RequestBody VersionRequest request) {
        memberService.removeMember(workspaceId, userId, request.expectedVersion());
        return ApiResponse.success(null);
    }

    @PostMapping("/ownership-transfer")
    public ApiResponse<Void> transfer(@PathVariable @Positive long workspaceId,
                                      @RequestParam @Positive long newOwnerUserId,
                                      @Valid @RequestBody VersionRequest request) {
        memberService.transferOwnership(workspaceId, newOwnerUserId, request.expectedVersion());
        return ApiResponse.success(null);
    }
}
