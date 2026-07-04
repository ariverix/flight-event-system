import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright smoke E2E (Фаза 6). Критические потоки: логин admin/admin → список
 * последовательностей → редактор React Flow → дашборд инстансов (WebSocket-статус).
 *
 * ПРЕДУСЛОВИЕ: backend + БД должны быть подняты (docker-compose up, backend на :8081, ИЛИ
 * `mvnw spring-boot:run` на :8080). Frontend dev-сервер (vite, :5173) поднимает сам Playwright
 * (webServer ниже) и проксирует /api на backend (см. vite.config.ts). Демо-логин admin/admin (V8).
 *
 * Артефакты при падении: trace / screenshot / video (папка test-results/, gitignored).
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 1,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    // локально переиспользуем уже запущенный vite; в CI сервер всегда поднимается с нуля
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
