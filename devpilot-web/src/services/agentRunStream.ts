import { agentRunStreamUrl } from '@/api/modules/agent'
import { useAuthStore } from '@/stores/auth'
import type { AgentRunEvent } from '@/types/api'

export type AgentRunStreamMessage = {
  id: string | null
  event: string
  data: AgentRunEvent | null
}

type AgentRunStreamOptions = {
  workspaceId: number
  projectId: number
  runId: string
  lastEventId?: string | null
  onEvent: (message: AgentRunStreamMessage) => void
  onError: (message: string) => void
}

/**
 * 使用 Fetch 而非 EventSource 建立 SSE，以便 Bearer Token 仅通过 Authorization Header 传给 Java Core。
 * 断开连接只终止浏览器读取，不会向 Agent Runtime 发送 CancelRun。
 */
export function connectAgentRunStream(options: AgentRunStreamOptions): () => void {
  const authStore = useAuthStore()
  const token = authStore.accessToken
  const controller = new AbortController()

  if (!token || !authStore.isAuthenticated) {
    options.onError('登录已失效，无法连接 Agent 事件流。')
    return () => controller.abort()
  }

  const headers: HeadersInit = {
    Authorization: `Bearer ${token}`,
    Accept: 'text/event-stream',
    'Cache-Control': 'no-cache',
  }
  if (options.lastEventId) headers['Last-Event-ID'] = options.lastEventId

  void fetch(agentRunStreamUrl(options.workspaceId, options.projectId, options.runId), {
    headers,
    signal: controller.signal,
  }).then(async (response) => {
    if (!response.ok || !response.body) {
      throw new Error(`SSE HTTP ${response.status}`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (!controller.signal.aborted) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      const frames = buffer.split('\n\n')
      buffer = frames.pop() ?? ''
      frames.forEach((frame) => publishFrame(frame, options.onEvent))
    }
  }).catch((error: unknown) => {
    if (!controller.signal.aborted) {
      options.onError(error instanceof Error ? error.message : 'Agent 事件流连接失败。')
    }
  })

  return () => controller.abort()
}

function publishFrame(frame: string, onEvent: (message: AgentRunStreamMessage) => void) {
  let id: string | null = null
  let event = 'message'
  const dataLines: string[] = []
  for (const line of frame.split('\n')) {
    if (line.startsWith(':')) continue
    if (line.startsWith('id:')) id = line.slice(3).trim()
    else if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (!dataLines.length) return
  try {
    onEvent({ id, event, data: JSON.parse(dataLines.join('\n')) as AgentRunEvent })
  } catch {
    // Java 的 heartbeat/replay-gap 可没有普通 AgentRunEvent 数据；交由页面按事件名恢复权威状态。
    onEvent({ id, event, data: null })
  }
}
