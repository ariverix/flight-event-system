import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Дашборд инстансов (реал-тайм / WebSocket)', () => {
  test('дашборд мониторинга открывается и показывает индикатор WS-подключения', async ({ page }) => {
    await login(page);
    await page.goto('/monitoring');

    // дашборд отрисован (таблица инстансов / заголовок)
    await expect(page.locator('table, [role="table"], h1, h2, h3').first()).toBeVisible({ timeout: 15_000 });

    // индикатор состояния WS (ConnectionStatus/Badge, P7-4) присутствует независимо от
    // того, установилось ли соединение — текст «WS подключён/отключён» из i18n (dict.ts)
    const wsIndicator = page.getByText(/WS (подключ|отключ|connect|disconnect)/i).first();
    await expect(wsIndicator).toBeVisible({ timeout: 15_000 });
  });
});
