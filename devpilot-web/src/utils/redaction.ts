const SENSITIVE_KEY_REGEX = /password|token|secret|credential|authorization/i

export function redactHeaders(headers: Record<string, any> | undefined): Record<string, string> {
  if (!headers) return {}
  const redacted: Record<string, string> = {}
  for (const [key, value] of Object.entries(headers)) {
    if (SENSITIVE_KEY_REGEX.test(key)) {
      if (typeof value === 'string' && value.toLowerCase().startsWith('bearer ')) {
        redacted[key] = 'Bearer ***REDACTED***'
      } else {
        redacted[key] = '***REDACTED***'
      }
    } else {
      redacted[key] = String(value)
    }
  }
  return redacted
}

export function redactBody(body: any): any {
  if (body === null || body === undefined) return body
  if (typeof body !== 'object') return body

  if (Array.isArray(body)) {
    return body.map(item => redactBody(item))
  }

  const redacted: Record<string, any> = {}
  for (const [key, value] of Object.entries(body)) {
    if (SENSITIVE_KEY_REGEX.test(key)) {
      redacted[key] = '***REDACTED***'
    } else if (typeof value === 'object' && value !== null) {
      redacted[key] = redactBody(value)
    } else {
      redacted[key] = value
    }
  }
  return redacted
}
