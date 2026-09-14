import { request } from '../client'
import type { ApiResult, KnowledgeDocument, KnowledgeSearchResult } from '@/types/api'

function base(workspaceId: number, projectId: number) {
  return `/api/v1/workspaces/${workspaceId}/projects/${projectId}/knowledge`
}

export function listKnowledgeDocumentsApi(workspaceId: number, projectId: number): Promise<ApiResult<KnowledgeDocument[]>> {
  return request<KnowledgeDocument[]>({ url: `${base(workspaceId, projectId)}/documents`, method: 'GET' })
}

export function uploadKnowledgeDocumentApi(
  workspaceId: number,
  projectId: number,
  file: File,
): Promise<ApiResult<KnowledgeDocument>> {
  const data = new FormData()
  data.append('file', file)
  return request<KnowledgeDocument>({
    url: `${base(workspaceId, projectId)}/documents`,
    method: 'POST',
    data,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function retryKnowledgeDocumentApi(
  workspaceId: number,
  projectId: number,
  documentId: string,
  expectedVersion: number,
): Promise<ApiResult<KnowledgeDocument>> {
  return request<KnowledgeDocument>({
    url: `${base(workspaceId, projectId)}/documents/${encodeURIComponent(documentId)}/retry`,
    method: 'POST',
    data: { expectedVersion },
  })
}

export function searchProjectKnowledgeApi(
  workspaceId: number,
  projectId: number,
  query: string,
  conversationHistory: string[] = [],
  topK = 8,
): Promise<ApiResult<KnowledgeSearchResult>> {
  return request<KnowledgeSearchResult>({
    url: `${base(workspaceId, projectId)}/search`,
    method: 'POST',
    data: { query, conversationHistory, topK },
  })
}
