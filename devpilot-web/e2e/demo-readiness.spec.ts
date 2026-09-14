import { expect, test, type Page, type Route } from '@playwright/test'

const user = { id: 7, username: 'interviewer', email: 'demo@example.com', displayName: 'Demo Reviewer' }
const workspace = {
  id: 1,
  name: 'Platform Engineering',
  slug: 'platform-engineering',
  description: 'Interview demo workspace',
  ownerUserId: 7,
  status: 'ACTIVE',
  version: 1,
  createdAt: '2026-09-12T08:00:00Z',
  updatedAt: '2026-09-12T09:00:00Z',
}
const project = {
  id: 10,
  workspaceId: 1,
  name: 'DevPilot Core',
  projectKey: 'DP',
  description: 'Interview-ready engineering workflow',
  visibility: 'PRIVATE',
  status: 'ACTIVE',
  version: 3,
  createdAt: '2026-09-12T08:00:00Z',
  updatedAt: '2026-09-12T09:00:00Z',
}
const repositories = [
  {
    id: 31,
    workspaceId: 1,
    projectId: 10,
    githubRepositoryId: 3100,
    owner: 'acme',
    repositoryName: 'devpilot',
    fullName: 'acme/devpilot',
    defaultBranch: 'main',
    visibility: 'private',
    htmlUrl: 'https://github.com/acme/devpilot',
    bindingStatus: 'ACTIVE',
    hasApiCredential: true,
    hasWebhookSecret: true,
    lastVerifiedAt: '2026-09-12T09:00:00Z',
    lastSyncedAt: '2026-09-12T09:00:00Z',
    version: 2,
    createdAt: '2026-09-12T08:00:00Z',
    updatedAt: '2026-09-12T09:00:00Z',
  },
  {
    id: 32,
    workspaceId: 1,
    projectId: 10,
    githubRepositoryId: 3200,
    owner: 'acme',
    repositoryName: 'docs',
    fullName: 'acme/docs',
    defaultBranch: 'trunk',
    visibility: 'private',
    htmlUrl: 'https://github.com/acme/docs',
    bindingStatus: 'ACTIVE',
    hasApiCredential: true,
    hasWebhookSecret: true,
    lastVerifiedAt: '2026-09-12T09:00:00Z',
    lastSyncedAt: null,
    version: 1,
    createdAt: '2026-09-12T08:00:00Z',
    updatedAt: '2026-09-12T09:00:00Z',
  },
]

function envelope(data: unknown) {
  return { code: 'COMMON_0000', message: 'Success', data }
}

async function seedSession(page: Page) {
  await page.addInitScript(() => {
    sessionStorage.setItem('devpilot_access_token', 'e2e-token')
    sessionStorage.setItem('devpilot_token_expires_at', String(Date.now() + 3_600_000))
  })
}

async function fulfillJson(route: Route, data: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) })
}

async function mockApi(page: Page, options: { workspaceStatus?: number; projectStatus?: number } = {}) {
  await page.route('**/actuator/health', route => fulfillJson(route, { status: 'UP' }))
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname

    if (path === '/api/v1/auth/me') return fulfillJson(route, envelope(user))
    if (path === '/api/v1/auth/login') {
      return fulfillJson(route, envelope({
        accessToken: 'logged-in-token', tokenType: 'Bearer', expiresInSeconds: 3600, user,
      }))
    }
    if (path === '/api/v1/auth/logout') return fulfillJson(route, envelope(null))
    if (path === '/api/v1/notifications/unread-count') return fulfillJson(route, envelope({ count: 0 }))
    if (path === '/api/v1/notifications') {
      return fulfillJson(route, envelope({ page: 1, size: 20, total: 0, items: [] }))
    }
    if (path === '/api/v1/notifications/stream') {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: ': connected\n\n' })
    }
    if (path === '/api/v1/me/workspace-invitations') return fulfillJson(route, envelope([]))
    if (path === '/api/v1/workspaces/1') {
      if (options.workspaceStatus) {
        return fulfillJson(route, { code: 'AUTH_0403', message: 'Forbidden', data: null }, options.workspaceStatus)
      }
      return fulfillJson(route, envelope(workspace))
    }
    if (path === '/api/v1/workspaces/1/projects/10') {
      if (options.projectStatus) {
        return fulfillJson(route, { code: 'PROJECT_0404', message: 'Not found', data: null }, options.projectStatus)
      }
      return fulfillJson(route, envelope(project))
    }
    if (path === '/api/v1/workspaces/1/projects/10/tasks') {
      return fulfillJson(route, envelope({
        page: 1,
        size: 20,
        total: 1,
        items: [{
          id: 501,
          workspaceId: 1,
          projectId: 10,
          displayKey: 'DP-501',
          title: 'Stabilize interview demo',
          description: null,
          status: 'IN_PROGRESS',
          priority: 'HIGH',
          reporterUserId: 7,
          assigneeUserId: 7,
          dueAt: null,
          version: 2,
          createdAt: '2026-09-12T08:00:00Z',
          updatedAt: '2026-09-12T09:00:00Z',
        }],
      }))
    }
    if (path === '/api/v1/workspaces/1/projects/10/activities') {
      return fulfillJson(route, envelope({ page: 1, size: 20, total: 0, items: [] }))
    }
    if (path === '/api/v1/workspaces/1/projects/10/github/issues') {
      return fulfillJson(route, envelope({ page: 1, size: 20, total: 0, items: [] }))
    }
    if (path === '/api/v1/workspaces/1/projects/10/github/pull-requests') {
      return fulfillJson(route, envelope({ page: 1, size: 20, total: 0, items: [] }))
    }
    if (path === '/api/v1/workspaces/1/projects/10/github-repositories') {
      return fulfillJson(route, envelope({ page: 1, size: 20, total: repositories.length, items: repositories }))
    }
    if (path === '/api/v1/workspaces/1/projects/10/github-repositories/31/branches') {
      return fulfillJson(route, envelope([{ name: 'main', commitSha: 'a'.repeat(40) }, { name: 'release', commitSha: 'b'.repeat(40) }]))
    }
    if (path === '/api/v1/workspaces/1/projects/10/github-repositories/32/branches') {
      return fulfillJson(route, envelope([{ name: 'trunk', commitSha: 'c'.repeat(40) }]))
    }
    if (path === '/api/v1/workspaces/1/projects/10/agent-runs' && request.method() === 'GET') {
      return fulfillJson(route, envelope({ page: 0, size: 20, total: 0, items: [] }))
    }
    if (path === '/api/v1/workspaces/1/projects/10/knowledge/documents') {
      return fulfillJson(route, envelope([{
        documentId: 'doc-architecture',
        filename: 'architecture.md',
        contentType: 'text/markdown',
        sizeBytes: 2048,
        status: 'READY',
        failureCode: null,
        chunkCount: 3,
        repositoryBindingId: null,
        version: 2,
        createdAt: '2026-09-12T08:00:00Z',
        updatedAt: '2026-09-12T09:00:00Z',
      }]))
    }
    if (path === '/api/v1/workspaces/1/projects/10/knowledge/search') {
      return fulfillJson(route, envelope({
        originalQuery: '为什么使用 Outbox？',
        rewrittenQuery: '为什么使用 Outbox？',
        knowledgeVersion: 3000002,
        hits: [{
          chunkId: 'doc-architecture:2:0', documentId: 'doc-architecture', sourceFile: 'architecture.md',
          sourceType: 'UPLOAD', repositoryBindingId: null, commitSha: null, chunkIndex: 0,
          content: 'DevPilot 使用 Transactional Outbox 保证业务数据与领域事件原子提交。',
          denseScore: 0.81, sparseScore: 2.31, fusionScore: 0.032, rerankScore: 0.94,
        }],
      }))
    }
    if (path === '/api/v1/workspaces') {
      return fulfillJson(route, envelope({ page: 1, size: 20, total: 1, items: [workspace] }))
    }
    return fulfillJson(route, envelope(null))
  })
}

test('owner invite becomes invitee pending invitation and accepted workspace', async ({ page }) => {
  await seedSession(page)
  const invitedUser = { id: 8, username: 'member', email: 'member@example.com', displayName: 'Invited Member' }
  let currentUser = user
  let membership: null | {
    workspaceId: number
    userId: number
    role: 'MEMBER'
    status: 'INVITED' | 'ACTIVE'
    invitedBy: number
    version: number
  } = null

  await page.route('**/actuator/health', route => fulfillJson(route, { status: 'UP' }))
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname

    if (path === '/api/v1/auth/me') return fulfillJson(route, envelope(currentUser))
    if (path === '/api/v1/notifications/unread-count') return fulfillJson(route, envelope({ count: 0 }))
    if (path === '/api/v1/notifications/stream') {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: ': connected\n\n' })
    }
    if (path === '/api/v1/workspaces/1' && request.method() === 'GET') {
      return fulfillJson(route, envelope(workspace))
    }
    if (path === '/api/v1/workspaces/1/members' && request.method() === 'GET') {
      return fulfillJson(route, envelope(membership ? [{
        id: 41,
        userId: membership.userId,
        role: membership.role,
        status: membership.status,
        invitedBy: membership.invitedBy,
        joinedAt: membership.status === 'ACTIVE' ? '2026-09-14T10:00:00Z' : null,
        version: membership.version,
      }] : []))
    }
    if (path === '/api/v1/workspaces/1/members/invitations' && request.method() === 'POST') {
      const body = request.postDataJSON() as { email: string; role: string }
      expect(currentUser.id).toBe(user.id)
      expect(body).toEqual({ email: invitedUser.email, role: 'MEMBER' })
      membership = {
        workspaceId: workspace.id,
        userId: invitedUser.id,
        role: 'MEMBER',
        status: 'INVITED',
        invitedBy: user.id,
        version: 0,
      }
      return fulfillJson(route, envelope(null))
    }
    if (path === '/api/v1/me/workspace-invitations') {
      const invitations = currentUser.id === invitedUser.id && membership?.status === 'INVITED'
        ? [{
            workspaceId: workspace.id,
            workspaceName: workspace.name,
            workspaceSlug: workspace.slug,
            role: membership.role,
            status: membership.status,
            invitedBy: membership.invitedBy,
            version: membership.version,
          }]
        : []
      return fulfillJson(route, envelope(invitations))
    }
    if (path === '/api/v1/workspaces/1/members/invitations/accept' && request.method() === 'POST') {
      const body = request.postDataJSON() as { expectedVersion: number }
      if (currentUser.id !== invitedUser.id || membership?.status !== 'INVITED' || membership.version !== body.expectedVersion) {
        return fulfillJson(route, { code: 'IDENTITY_0502', message: 'Membership version conflict', data: null }, 409)
      }
      membership = { ...membership, status: 'ACTIVE', version: membership.version + 1 }
      return fulfillJson(route, envelope(null))
    }
    if (path === '/api/v1/workspaces') {
      const canSeeWorkspace = currentUser.id === user.id || membership?.status === 'ACTIVE'
      const items = canSeeWorkspace ? [workspace] : []
      return fulfillJson(route, envelope({ page: 1, size: 20, total: items.length, items }))
    }
    return fulfillJson(route, envelope(null))
  })

  await page.goto('/workspaces/1')
  await page.getByLabel('已注册邮箱').fill(invitedUser.email)
  await page.getByRole('button', { name: '发送邀请' }).click()
  await expect(page.getByText('邀请已创建，等待对方接受')).toBeVisible()
  expect(membership).toMatchObject({ userId: invitedUser.id, status: 'INVITED', version: 0 })

  currentUser = invitedUser
  await page.goto('/workspaces')
  const invitationRow = page.getByRole('row').filter({ hasText: workspace.name })
  await expect(invitationRow).toContainText('成员')
  await invitationRow.getByRole('button', { name: '接受' }).click()

  await expect(page.getByText('暂无待处理邀请')).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: workspace.name })).toContainText(workspace.slug)
  expect(membership).toMatchObject({ status: 'ACTIVE', version: 1 })
})

test('login preserves an authenticated project deep link', async ({ page }) => {
  await mockApi(page)
  await page.goto('/workspaces/1/projects/10/tasks')

  await expect(page).toHaveURL(/\/login\?returnUrl=/)
  await expect(page.getByRole('link', { name: '创建账号' })).toHaveAttribute(
    'href',
    '/register?returnUrl=/workspaces/1/projects/10/tasks',
  )
  await page.getByLabel('用户名或邮箱').fill('interviewer')
  await page.getByLabel('密码').fill('correct-horse-battery-staple')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL('/workspaces/1/projects/10/tasks')
  await expect(page.getByRole('link', { name: 'Stabilize interview demo' })).toBeVisible()
  await expect(page.getByText('Platform Engineering', { exact: true })).toBeVisible()
  await expect(page.getByText('DP', { exact: true }).first()).toBeVisible()
})

test('login rejects an external return URL', async ({ page }) => {
  await mockApi(page)
  await page.goto('/login?returnUrl=https://example.invalid/steal')
  await page.getByLabel('用户名或邮箱').fill('interviewer')
  await page.getByLabel('密码').fill('correct-horse-battery-staple')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/workspaces')
})

test('deep-link refresh and browser history keep route scope authoritative', async ({ page }) => {
  await seedSession(page)
  await mockApi(page)
  await page.goto('/workspaces/1/projects/10/tasks')
  await expect(page.getByRole('link', { name: 'Stabilize interview demo' })).toBeVisible()
  await expect(page.locator('.el-table').getByText('进行中', { exact: true })).toBeVisible()
  await expect(page.locator('.el-table').getByText('高', { exact: true })).toBeVisible()

  await page.reload()
  await expect(page.getByText('Platform Engineering', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Stabilize interview demo' })).toBeVisible()
  if (process.env.CAPTURE_DEMO_ARTIFACTS) {
    await page.screenshot({ path: '../docs/interview-demo-artifacts/authenticated-desktop.png', fullPage: true })
  }

  await page.getByText('GitHub 仓库', { exact: true }).click()
  await expect(page).toHaveURL('/workspaces/1/projects/10/repositories')
  await page.goBack()
  await expect(page).toHaveURL('/workspaces/1/projects/10/tasks')
  await expect(page.getByRole('link', { name: 'Stabilize interview demo' })).toBeVisible()
  await page.goForward()
  await expect(page).toHaveURL('/workspaces/1/projects/10/repositories')
})

test('Agent Run sends the selected repository binding and branch explicitly', async ({ page }) => {
  await seedSession(page)
  await mockApi(page)
  let submitted: Record<string, unknown> | undefined
  await page.route('**/api/v1/workspaces/1/projects/10/agent-runs', async route => {
    const request = route.request()
    if (request.method() === 'POST') {
      submitted = request.postDataJSON()
      return fulfillJson(route, envelope({
        runId: 'run-e2e-1',
        requestId: 'request-e2e-1',
        workspaceId: 1,
        projectId: 10,
        createdBy: 7,
        status: 'PENDING',
        userInput: submitted?.input,
        repositoryFullName: 'acme/docs',
        branchName: 'trunk',
        commitSha: 'c'.repeat(40),
        finalOutput: null,
        failureKind: null,
        startedAt: null,
        finishedAt: null,
        createdAt: '2026-09-12T09:00:00Z',
        updatedAt: '2026-09-12T09:00:00Z',
        version: 0,
      }))
    }
    return fulfillJson(route, envelope({ page: 0, size: 20, total: 0, items: [] }))
  })
  await page.route('**/api/v1/workspaces/1/projects/10/agent-runs/run-e2e-1/stream', route => (
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: 'event: run-succeeded\ndata: {"finalOutput":"done","step":1}\n\n',
    })
  ))
  await page.route('**/api/v1/workspaces/1/projects/10/agent-runs/run-e2e-1', route => (
    fulfillJson(route, envelope({
      runId: 'run-e2e-1', requestId: 'request-e2e-1', workspaceId: 1, projectId: 10, createdBy: 7,
      status: 'SUCCEEDED', userInput: 'Summarize risk', repositoryFullName: 'acme/docs', branchName: 'trunk',
      commitSha: 'c'.repeat(40), finalOutput: 'done', failureKind: null,
      startedAt: '2026-09-12T09:00:00Z', finishedAt: '2026-09-12T09:00:01Z',
      createdAt: '2026-09-12T09:00:00Z', updatedAt: '2026-09-12T09:00:01Z', version: 2,
    }))
  ))

  await page.goto('/workspaces/1/projects/10/agent')
  await expect(page.locator('.agent-run-view').getByText('acme/devpilot', { exact: true }).first()).toBeVisible()
  await page.locator('.agent-run-view .el-select').first().click()
  await page.getByRole('option', { name: /acme\/docs/ }).click()
  await page.getByLabel('请求内容').fill('Summarize risk')
  await page.getByRole('button', { name: '启动运行' }).click()

  await expect.poll(() => submitted).toEqual({
    input: 'Summarize risk',
    repositoryBindingId: 32,
    branchName: 'trunk',
  })
  await expect(page.locator('.final-output')).toContainText('done')
})

test('invalid, forbidden, and missing scope routes fail explicitly', async ({ page }) => {
  await seedSession(page)
  await mockApi(page, { workspaceStatus: 403 })
  await page.goto('/workspaces/1/projects')
  await expect(page.getByRole('heading', { name: '没有访问权限' })).toBeVisible()

  await page.goto('/workspaces/not-a-number/projects')
  await expect(page.getByRole('heading', { name: '页面不存在' })).toBeVisible()
})

test('a missing project renders the scoped 404 state', async ({ page }) => {
  await seedSession(page)
  await mockApi(page, { projectStatus: 404 })
  await page.goto('/workspaces/1/projects/10/tasks')
  await expect(page.getByRole('heading', { name: '资源不存在' })).toBeVisible()
})

test('logout clears the session and returns to login', async ({ page }) => {
  await seedSession(page)
  await mockApi(page)
  await page.goto('/workspaces')
  await page.getByRole('button', { name: '打开账号菜单' }).click()
  await page.getByText('退出登录', { exact: true }).click()
  await expect(page).toHaveURL('/login')
  await expect(page.evaluate(() => sessionStorage.getItem('devpilot_access_token'))).resolves.toBeNull()
})

test('project knowledge page exposes ingestion status and traceable hybrid retrieval', async ({ page }) => {
  await seedSession(page)
  await mockApi(page)

  await page.goto('/workspaces/1/projects/10/knowledge')
  await expect(page.getByRole('heading', { name: '项目知识库' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: 'architecture.md' })).toContainText('READY')

  await page.getByLabel('问题').fill('为什么使用 Outbox？')
  await page.getByRole('button', { name: '检索项目知识' }).click()
  await expect(page.getByText('DevPilot 使用 Transactional Outbox')).toBeVisible()
  await expect(page.getByText('Rerank 0.940')).toBeVisible()
  await expect(page.getByText('BM25 2.310')).toBeVisible()
  if (process.env.CAPTURE_DEMO_ARTIFACTS) {
    await page.screenshot({ path: '../docs/interview-demo-artifacts/project-knowledge.png', fullPage: true })
  }
})

test('business pages do not render transport or endpoint copy', async ({ page }) => {
  await seedSession(page)
  await mockApi(page)

  const businessPaths = [
    '/workspaces',
    '/workspaces/1/projects/10/tasks',
    '/workspaces/1/projects/10/repositories',
    '/workspaces/1/projects/10/activities',
    '/workspaces/1/projects/10/github/issues',
    '/workspaces/1/projects/10/github/pull-requests',
    '/workspaces/1/projects/10/agent',
    '/workspaces/1/projects/10/knowledge',
    '/notifications',
  ]

  for (const path of businessPaths) {
    await page.goto(path)
    await expect(page.locator('.main-content')).toBeVisible()
    const renderedText = await page.locator('body').innerText()
    expect(renderedText, `${path} rendered a private endpoint`).not.toMatch(/\/api\/v1/i)
    expect(renderedText, `${path} rendered an HTTP method and endpoint`).not.toMatch(/\b(?:GET|POST)\s+\/api/i)
  }

  await page.getByRole('button', { name: '打开账号菜单' }).click()
  await page.getByText('开发者工具', { exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Developer console' })).toBeVisible()
  await expect(page.getByText('/api/v1/github/webhooks', { exact: false })).toBeVisible()
})

test('business errors keep raw endpoint details out of the primary UI', async ({ page }) => {
  await seedSession(page)
  await mockApi(page)
  await page.route('**/api/v1/workspaces/1/projects/10/github-repositories/31/sync/commits', route => (
    fulfillJson(route, {
      code: 'REPOSITORY_0409',
      message: 'POST /api/v1/workspaces/1/projects/10/github-repositories/31/sync/commits returned 409',
      data: null,
    }, 409)
  ))

  await page.goto('/workspaces/1/projects/10/repositories')
  await page.getByRole('button', { name: '同步提交' }).first().click()
  await expect(page.getByText('内容已发生变化，请刷新后重试。', { exact: true })).toBeVisible()
  const renderedText = await page.locator('body').innerText()
  expect(renderedText).not.toContain('/api/v1')
  expect(renderedText).not.toContain('HTTP 409')
})

test.describe('mobile shell', () => {
  test.use({ viewport: { width: 412, height: 915 } })

  test('keeps project navigation reachable without horizontal overflow', async ({ page }) => {
    await seedSession(page)
    await mockApi(page)
    await page.goto('/workspaces/1/projects/10/tasks')
    await expect(page.getByRole('link', { name: 'Stabilize interview demo' })).toBeVisible()
    await page.getByRole('button', { name: '打开导航' }).click()
    await expect(page.getByText('项目概览', { exact: true })).toBeVisible()
    const metrics = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }))
    expect(metrics.scroll).toBeLessThanOrEqual(metrics.width)
    if (process.env.CAPTURE_DEMO_ARTIFACTS) {
      await page.waitForTimeout(400)
      await page.screenshot({ path: '../docs/interview-demo-artifacts/authenticated-mobile.png', fullPage: true })
    }
  })

  test('keeps knowledge upload and retrieval usable without page overflow', async ({ page }) => {
    await seedSession(page)
    await mockApi(page)
    await page.goto('/workspaces/1/projects/10/knowledge')
    await expect(page.getByRole('heading', { name: '项目知识库' })).toBeVisible()
    await expect(page.getByText('拖放文件到这里')).toBeVisible()
    const metrics = await page.evaluate(() => ({
      width: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
    }))
    expect(metrics.scroll).toBeLessThanOrEqual(metrics.width)
  })
})
