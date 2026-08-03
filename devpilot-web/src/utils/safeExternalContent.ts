/**
 * Safe content processing for untrusted GitHub external content.
 * Strips HTML tags and script elements to prevent XSS.
 * NEVER use v-html on raw untrusted strings.
 */
export function sanitizeExternalText(text: string | null | undefined): string {
  if (!text) return ''

  // Replace HTML tags with space/nothing
  const clean = text
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '[SCRIPT REMOVED]')
    .replace(/<[^>]+>/g, '')

  return clean
}

export function parseJsonArraySafe(jsonStr: string | null | undefined): string[] {
  if (!jsonStr) return []
  try {
    const parsed = JSON.parse(jsonStr)
    if (Array.isArray(parsed)) {
      return parsed.map(item => (typeof item === 'string' ? item : JSON.stringify(item)))
    }
  } catch {
    // If parse fails, return the string as single element array
  }
  return [jsonStr]
}
