import { defineConfig, devices } from '@playwright/test'

// Local proxy settings must never intercept the supervised Vite readiness probe.
const localBypass = '127.0.0.1,localhost'
process.env.NO_PROXY = [process.env.NO_PROXY, localBypass].filter(Boolean).join(',')
process.env.no_proxy = [process.env.no_proxy, localBypass].filter(Boolean).join(',')

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  reporter: [['line'], ['html', { outputFolder: '../docs/interview-demo-artifacts/playwright-report', open: 'never' }]],
  outputDir: '../docs/interview-demo-artifacts/playwright-results',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    channel: 'chrome',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop-chrome', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: process.env.PLAYWRIGHT_EXTERNAL_SERVER ? undefined : {
    command: 'node ./node_modules/vite/bin/vite.js --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173/login',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
