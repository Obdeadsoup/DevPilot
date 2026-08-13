import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'

class NotificationStreamService {
  private abortController: AbortController | null = null
  private retryDelay = 1000
  private maxRetryDelay = 30000
  private isConnecting = false
  private reconnectTimer: number | null = null

  public connect() {
    const authStore = useAuthStore()
    if (!authStore.accessToken || !authStore.isAuthenticated) {
      this.disconnect()
      return
    }

    if (this.isConnecting || this.abortController) {
      return
    }

    this.isConnecting = true
    const notificationStore = useNotificationStore()
    notificationStore.setStreamState('connecting')

    this.abortController = new AbortController()
    const token = authStore.accessToken

    fetch('/api/v1/notifications/stream', {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
      signal: this.abortController.signal,
    })
      .then(async (response) => {
        this.isConnecting = false
        if (!response.ok || !response.body) {
          throw new Error(`SSE HTTP error: ${response.status}`)
        }

        notificationStore.setStreamState('open')
        this.retryDelay = 1000 // Reset backoff on success

        const reader = response.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let buffer = ''

        while (true) {
          const { done, value } = await reader.read()
          if (done) break

          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || '' // keep last incomplete line in buffer

          let currentEvent = 'message'
          let currentData = ''

          for (const line of lines) {
            const trimmed = line.trim()
            if (!trimmed || trimmed.startsWith(':')) {
              // Comment / heartbeat line
              continue
            }
            if (trimmed.startsWith('event:')) {
              currentEvent = trimmed.slice(6).trim()
            } else if (trimmed.startsWith('data:')) {
              currentData += (currentData ? '\n' : '') + trimmed.slice(5).trim()
            }

            if (trimmed === '' && currentData) {
              this.handleSseEvent(currentEvent, currentData)
              currentEvent = 'message'
              currentData = ''
            }
          }
          if (currentData) {
            this.handleSseEvent(currentEvent, currentData)
            currentEvent = 'message'
            currentData = ''
          }
        }
      })
      .catch((err) => {
        this.isConnecting = false
        if (err.name === 'AbortError') {
          notificationStore.setStreamState('closed')
          return
        }
        notificationStore.setStreamState('retrying')
        this.scheduleReconnect()
      })
  }

  private handleSseEvent(eventName: string, dataStr: string) {
    const notificationStore = useNotificationStore()
    try {
      const data = JSON.parse(dataStr)
      if (eventName === 'connected') {
        if (typeof data.unreadCount === 'number') {
          notificationStore.setUnreadCount(data.unreadCount)
        }
      } else if (eventName === 'notification-created') {
        if (typeof data.unreadCount === 'number') {
          notificationStore.setUnreadCount(data.unreadCount)
        }
        notificationStore.onNotificationReceived(data)
      }
    } catch {
      // Ignore parse errors
    }
  }

  private scheduleReconnect() {
    this.disconnect(false)
    const authStore = useAuthStore()
    if (!authStore.isAuthenticated) return

    const jitter = Math.random() * 500
    const delay = this.retryDelay + jitter

    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)

    this.retryDelay = Math.min(this.retryDelay * 2, this.maxRetryDelay)
  }

  public disconnect(resetState = true) {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.abortController) {
      this.abortController.abort()
      this.abortController = null
    }
    this.isConnecting = false
    if (resetState) {
      const notificationStore = useNotificationStore()
      notificationStore.setStreamState('closed')
    }
  }
}

export const notificationStreamService = new NotificationStreamService()
