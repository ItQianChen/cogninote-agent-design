import { defineConfig, devices } from '@playwright/test'

const frontendPort = Number(process.env.COGNINOTE_E2E_FRONTEND_PORT || 4173)
const browserChannel = process.env.COGNINOTE_E2E_BROWSER_CHANNEL

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/support/global-setup.js',
  fullyParallel: false,
  workers: 1,
  // Specs share one stateful backend; retrying a single spec would reuse its partial writes.
  retries: 0,
  reporter: process.env.CI
    ? [['line'], ['html', { outputFolder: '../artifacts/browser-smoke/playwright-report', open: 'never' }]]
    : 'line',
  use: {
    baseURL: `http://127.0.0.1:${frontendPort}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  outputDir: '../artifacts/browser-smoke/test-results',
  expect: {
    timeout: 10_000
  },
  timeout: 60_000,
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${frontendPort} --strictPort`,
    url: `http://127.0.0.1:${frontendPort}`,
    reuseExistingServer: false,
    timeout: 120_000
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], channel: browserChannel }
    }
  ]
})
