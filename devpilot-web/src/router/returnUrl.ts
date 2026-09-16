export function normalizeReturnUrl(value: unknown): string {
  const candidate = Array.isArray(value) ? value[0] : value
  if (typeof candidate !== 'string') return '/workspaces'
  if (!candidate.startsWith('/') || candidate.startsWith('//') || candidate.includes('\\')) {
    return '/workspaces'
  }
  if (candidate.startsWith('/login') || candidate.startsWith('/register')) return '/workspaces'
  return candidate
}
