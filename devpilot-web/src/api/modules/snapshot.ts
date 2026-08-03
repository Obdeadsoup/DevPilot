import { request } from '../client'
import type {
  GitHubIssue,
  GitHubPullRequest,
  GitHubReview,
  GitHubSnapshotPageResponse,
  ApiResult,
} from '@/types/api'

export function listIssuesApi(
  workspaceId: number,
  projectId: number,
  page = 1,
  size = 20
): Promise<ApiResult<GitHubSnapshotPageResponse<GitHubIssue>>> {
  return request<GitHubSnapshotPageResponse<GitHubIssue>>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github/issues`,
    method: 'GET',
    params: { page, size },
  })
}

export function getIssueApi(
  workspaceId: number,
  projectId: number,
  issueId: number
): Promise<ApiResult<GitHubIssue>> {
  return request<GitHubIssue>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github/issues/${issueId}`,
    method: 'GET',
  })
}

export function listPullRequestsApi(
  workspaceId: number,
  projectId: number,
  page = 1,
  size = 20
): Promise<ApiResult<GitHubSnapshotPageResponse<GitHubPullRequest>>> {
  return request<GitHubSnapshotPageResponse<GitHubPullRequest>>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github/pull-requests`,
    method: 'GET',
    params: { page, size },
  })
}

export function getPullRequestApi(
  workspaceId: number,
  projectId: number,
  pullRequestId: number
): Promise<ApiResult<GitHubPullRequest>> {
  return request<GitHubPullRequest>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github/pull-requests/${pullRequestId}`,
    method: 'GET',
  })
}

export function listReviewsApi(
  workspaceId: number,
  projectId: number,
  pullRequestId: number
): Promise<ApiResult<GitHubReview[]>> {
  return request<GitHubReview[]>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github/pull-requests/${pullRequestId}/reviews`,
    method: 'GET',
  })
}
