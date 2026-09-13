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
    if (path === '/api/v1/workspaces') {
      return fulfillJson(route, envelope({ page: 1, size: 20, total: 1, items: [workspace] }))
    }
    return fulfillJson(route, envelope(null))
  })
}

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
})
