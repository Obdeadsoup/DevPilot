// Standard Unified API Response Envelope
export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

// standard page response (Workspace, Project, Repository)
export interface PageResponse<T> {
  page: number
  size: number
  total: number
  items: T[]
}

// GitHub Snapshot Page Response (Issue, PR, Activity)
export interface GitHubSnapshotPageResponse<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

// User & Auth
export interface User {
  id: number
  username: string
  email?: string | null
  displayName: string
}

export interface LoginRequest {
  login: string
  password: string
}

export interface RegisterRequest {
  username: string
  email: string
  password: string
  verificationCode: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  user: User
}

export type AgentRunStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface AgentRun {
  runId: string
  requestId: string
  workspaceId: number
  projectId: number
  createdBy: number
  status: AgentRunStatus
  userInput: string
  repositoryFullName: string | null
  branchName: string | null
  commitSha: string | null
  finalOutput: string | null
  failureKind: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

/** 列表接口有意不返回 input/output，详情页才加载敏感且体积较大的全文。 */
export interface AgentRunHistoryItem {
  runId: string
  branchName: string | null
  commitSha: string | null
  status: AgentRunStatus
  failureKind: string | null
  startedAt: string
  finishedAt: string | null
  createdAt: string
}

export interface AgentRunEvent {
  runId: string
  sequence: number
  step: number
  toolName: string | null
  finalOutput: string | null
  failureKind: string | null
}

// Workspace
export interface Workspace {
  id: number
  name: string
  slug: string
  description: string | null
  ownerUserId: number
  status: 'ACTIVE' | 'DISABLED'
  version: number
  createdAt: string
  updatedAt: string
}

export interface WorkspaceMember {
  id: number
  userId: number
  role: 'ADMIN' | 'MEMBER' | 'VIEWER'
  status: 'INVITED' | 'ACTIVE' | 'SUSPENDED' | 'REJECTED' | 'REMOVED'
  invitedBy: number
  joinedAt: string | null
  version: number
}

export interface CreateWorkspaceRequest {
  name: string
  slug: string
  description?: string
}

export interface UpdateWorkspaceRequest {
  name: string
  description?: string
  expectedVersion: number
}

export interface ExpectedVersionRequest {
  expectedVersion: number
}

// Project
export interface Project {
  id: number
  workspaceId: number
  name: string
  projectKey: string
  description: string | null
  visibility: 'PRIVATE' | 'INTERNAL'
  status: 'PLANNING' | 'ACTIVE' | 'ARCHIVED'
  version: number
  createdAt: string
  updatedAt: string
}

export interface CreateProjectRequest {
  name: string
  projectKey: string
  description?: string
  visibility?: 'PRIVATE' | 'INTERNAL'
}

export interface UpdateProjectRequest {
  name: string
  description?: string
  visibility: 'PRIVATE' | 'INTERNAL'
  expectedVersion: number
}

// Repository Binding
export interface GitHubRepositoryBinding {
  id: number
  workspaceId: number
  projectId: number
  githubRepositoryId: number
  owner: string
  repositoryName: string
  fullName: string
  defaultBranch: string
  visibility: string
  htmlUrl: string
  bindingStatus: 'ACTIVE' | 'DISABLED'
  hasApiCredential: boolean
  hasWebhookSecret: boolean
  lastVerifiedAt: string | null
  lastSyncedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface GitHubBranch {
  name: string
  commitSha: string
}

export interface CreateGitHubRepositoryRequest {
  owner: string
  repositoryName: string
  apiCredentialRef: string
  webhookSecretRef: string
}

// Activity
export interface ActivityResponse {
  id: number
  workspaceId: number
  projectId: number
  sourceType: string
  activityType: string
  title: string
  summary: string | null
  occurredAt: string
  metadataJson: string | null
}

// GitHub Issue
export interface GitHubIssue {
  id: number
  workspaceId?: number
  projectId?: number
  repositoryBindingId?: number
  githubIssueId: number
  number: number
  title: string
  body?: string | null
  state: 'OPEN' | 'CLOSED'
  authorLogin: string
  assigneesJson?: string | null
  labelsJson?: string | null
  closedAt?: string | null
  githubCreatedAt: string
  githubUpdatedAt: string
  externalUntrustedContent: boolean
}

// GitHub Pull Request
export interface GitHubPullRequest {
  id: number
  workspaceId?: number
  projectId?: number
  repositoryBindingId?: number
  githubPullRequestId: number
  number: number
  title: string
  body?: string | null
  status: 'OPEN' | 'CLOSED' | 'MERGED'
  draft: boolean
  authorLogin: string
  headRef: string
  headSha: string
  baseRef: string
  baseSha: string
  requestedReviewersJson?: string | null
  assigneesJson?: string | null
  labelsJson?: string | null
  mergedAt?: string | null
  closedAt?: string | null
  githubCreatedAt: string
  githubUpdatedAt: string
  externalUntrustedContent: boolean
}

// GitHub Review
export interface GitHubReview {
  id: number
  workspaceId?: number
  projectId?: number
  repositoryBindingId?: number
  githubReviewId: number
  pullRequestId: number
  reviewerLogin: string
  state: 'COMMENTED' | 'APPROVED' | 'CHANGES_REQUESTED' | 'DISMISSED'
  body?: string | null
  submittedAt: string
  externalUntrustedContent: boolean
}

// Sync Run
export interface GitHubSyncRun {
  id: number
  repositoryBindingId: number
  resourceType: 'COMMIT' | 'ISSUE' | 'PULL_REQUEST' | 'PULL_REQUEST_REVIEW'
  triggerType: 'MANUAL' | 'SCHEDULED' | 'WEBHOOK'
  status: 'PENDING' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'DEAD'
  attemptCount: number
  nextRetryAt?: string | null
  startedAt?: string | null
  completedAt?: string | null
  lastErrorCode?: string | null
  createdAt: string
  updatedAt: string
}

export interface SyncRunReceipt {
  runId: number
  status: 'PENDING' | 'RUNNING'
  existing: boolean
}

// Actuator Health
export interface HealthResponse {
  status: string
}

// Normalized API Result for Internal Axios Layer & Dev Console
export interface ApiResult<T> {
  success: boolean
  httpStatus: number | null
  code: string
  message: string
  data: T | null
  rawJson: unknown
  networkError: boolean
  durationMs: number
}

// Dev Console Audit Record
export interface RequestAuditLog {
  id: string
  timestamp: string
  method: string
  url: string
  headers: Record<string, string>
  body: unknown
  httpStatus: number | null
  code: string
  message: string
  durationMs: number
  rawResponse: unknown
  success: boolean
}
