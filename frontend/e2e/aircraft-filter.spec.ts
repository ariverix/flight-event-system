import { test, expect } from '@playwright/test';
import { login } from './helpers';

test.describe('Aircraft-bindings: фильтр журнала по борту (Фазы 5–6)', () => {
  test('ингест сообщения → борт появляется в AircraftPicker и фильтрует журнал', async ({ page }) => {
    await login(page);

    // сидим сообщение через открытый ACARS-ингест (permitAll, сетевое ограждение — CLAUDE.md §7)
    const tail = `VP-E2E${Date.now() % 100000}`;
    const resp = await page.request.post('/api/v1/messages/incoming', {
      data: {
        messageType: 'DOWNLINK',
        templateName: 'STATUS',
        aircraftId: tail,
        flightNumber: 'SU1234',
      },
    });
    expect(resp.ok(), `ингест: ${resp.status()}`).toBeTruthy();

    // журнал сообщений: пикер бортов (aria-label из i18n) подгружает известные tail numbers
    await page.goto('/messages');
    const picker = page.getByRole('combobox', { name: 'Борт (tail number)' });
    await expect(picker).toBeVisible();

    // серверный поиск по подстроке: вводим tail — появляется опция из GET /api/v1/aircraft.
    // NB: role="option" у rc-select живёт в СКРЫТОМ a11y-списке — видимая опция это
    // .ant-select-item-option (стабильный класс AntD, как .react-flow у React Flow).
    await picker.click();
    await picker.fill(tail);
    const option = page.locator('.ant-select-item-option').filter({ hasText: tail }).first();
    await expect(option).toBeVisible({ timeout: 10_000 });

    // выбор борта фильтрует журнал — строка с этим бортом видна в таблице
    await option.click();
    await expect(page.locator('table').getByText(tail).first()).toBeVisible({ timeout: 10_000 });
  });
});
