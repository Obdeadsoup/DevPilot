package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubIssueEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubIssueMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import com.obdeadsoup.devpilot.task.application.port.TaskExternalReferenceSnapshot;
import com.obdeadsoup.devpilot.task.application.port.TaskGitHubReferenceReader;
import com.obdeadsoup.devpilot.task.domain.TaskGitHubResourceType;
import com.obdeadsoup.devpilot.task.error.TaskErrorCode;
import org.springframework.stereotype.Component;

/**
 * GitHub 模块实现 Task 的中立读取 Port。只读 scoped Snapshot、返回稳定 ID 和安全展示字段，
 * 不返回 body，也不把 GitHub login 映射成本地用户或触发 Task 状态变化。
 */
@Component
public class GitHubTaskReferenceAdapter implements TaskGitHubReferenceReader {
    private final GitHubIssueMapper issueMapper; private final GitHubPullRequestMapper pullRequestMapper;
    public GitHubTaskReferenceAdapter(GitHubIssueMapper issueMapper,GitHubPullRequestMapper pullRequestMapper){this.issueMapper=issueMapper;this.pullRequestMapper=pullRequestMapper;}
    @Override public TaskExternalReferenceSnapshot readIssue(long w,long p,long id){
        GitHubIssueEntity issue=issueMapper.findByProjectAndId(w,p,id).orElseGet(()->issueMapper.findById(id).orElseThrow(()->new BusinessException(TaskErrorCode.TASK_EXTERNAL_REFERENCE_NOT_FOUND)));
        if(issue.workspaceId()!=w||issue.projectId()!=p) throw new BusinessException(TaskErrorCode.TASK_EXTERNAL_REFERENCE_SCOPE_MISMATCH);
        return new TaskExternalReferenceSnapshot(issue.id(),TaskGitHubResourceType.ISSUE,issue.repositoryBindingId(),issue.githubRepositoryId(),issue.githubIssueId(),issue.issueNumber(),issue.title(),issue.state(),issue.htmlUrl(),issue.workspaceId(),issue.projectId());
    }
    @Override public TaskExternalReferenceSnapshot readPullRequest(long w,long p,long id){
        GitHubPullRequestEntity pr=pullRequestMapper.findByProjectAndId(w,p,id).orElseGet(()->pullRequestMapper.findById(id).orElseThrow(()->new BusinessException(TaskErrorCode.TASK_EXTERNAL_REFERENCE_NOT_FOUND)));
        if(pr.workspaceId()!=w||pr.projectId()!=p) throw new BusinessException(TaskErrorCode.TASK_EXTERNAL_REFERENCE_SCOPE_MISMATCH);
        return new TaskExternalReferenceSnapshot(pr.id(),TaskGitHubResourceType.PULL_REQUEST,pr.repositoryBindingId(),pr.githubRepositoryId(),pr.githubPullRequestId(),pr.pullRequestNumber(),pr.title(),pr.status(),pr.htmlUrl(),pr.workspaceId(),pr.projectId());
    }
}
