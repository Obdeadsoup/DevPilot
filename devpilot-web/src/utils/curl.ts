import { redactBody } from './redaction'

export function generateCurlCommand(
  method: string,
  url: string,
  headers: Record<string, any> | undefined,
  body: any
): string {
  const parts: string[] = ['curl.exe', `-X ${method.toUpperCase()}`]

  const fullUrl = url.startsWith('http') ? url : `http://127.0.0.1:8080${url}`
  parts.push(`"${fullUrl}"`)

  if (headers) {
    for (const [key, value] of Object.entries(headers)) {
      if (key.toLowerCase() === 'authorization') {
        parts.push(`-H "${key}: Bearer %TOKEN%"` )
      } else if (!['content-length', 'user-agent', 'accept-encoding'].includes(key.toLowerCase())) {
        parts.push(`-H "${key}: ${value}"`)
      }
    }
  }

  if (body && ['POST', 'PUT', 'PATCH'].includes(method.toUpperCase())) {
    const redacted = redactBody(body)
    const jsonStr = JSON.stringify(redacted)
    parts.push(`-H "Content-Type: application/json"`)
    parts.push(`-d "${jsonStr.replace(/"/g, '\\"')}"`)
  }

  return parts.join(' ')
}
