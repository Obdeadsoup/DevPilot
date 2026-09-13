package com.obdeadsoup.devpilot.project.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.project.api.dto.WorkspaceInvitationResponse;
import com.obdeadsoup.devpilot.project.application.WorkspaceMemberService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 当前用户邀请收件箱不要求 workspace 权限；身份范围由服务端登录态唯一确定。 */
@RestController
@RequestMapping("/api/v1/me/workspace-invitations")
public class WorkspaceInvitationController {
    private final WorkspaceMemberService memberService;

    public WorkspaceInvitationController(WorkspaceMemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    public ApiResponse<List<WorkspaceInvitationResponse>> listMine() {
        return ApiResponse.success(memberService.listOwnInvitations().stream()
                .map(WorkspaceInvitationResponse::from)
                .toList());
    }
}
