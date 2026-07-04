import { test, expect } from '@playwright/test';
import { login, getToken } from './helpers';

test.describe('Редактор последовательностей (React Flow)', () => {
  test('список последовательностей открывается', async ({ page }) => {
    await login(page);
    await page.goto('/sequences');
    // страница списка загрузилась (таблица AntD или заголовок)
    await expect(page.locator('table, [role="table"], h1, h2, h3').first()).toBeVisible();
  });

  test('создание последовательности через API → открытие редактора React Flow', async ({ page }) => {
    await login(page);
    const token = await getToken(page);

    // сидим последовательность через API (тем же токеном; /api проксируется vite на backend)
    const resp = await page.request.post('/api/v1/sequences', {
      headers: { Authorization: `Bearer ${token}` },
      data: { name: `E2E ${Date.now()}`, description: 'e2e smoke', startCriteria: null, stopCriteria: null },
    });
    expect(resp.ok(), `создание последовательности: ${resp.status()}`).toBeTruthy();
    const created = await resp.json();
    expect(created.id).toBeTruthy();

    // открываем визуальный редактор React Flow
    await page.goto(`/sequences/${created.id}/editor`);
    // канва React Flow отрисована (@xyflow/react добавляет класс .react-flow)
    await expect(page.locator('.react-flow').first()).toBeVisible({ timeout: 15_000 });
  });
});
