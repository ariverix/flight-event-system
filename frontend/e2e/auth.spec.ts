import { test, expect } from '@playwright/test';
import { login, getToken } from './helpers';

test.describe('Аутентификация', () => {
  test('логин admin/admin → редирект в приложение, JWT сохранён', async ({ page }) => {
    await login(page);

    // ушли со страницы логина
    await expect(page).not.toHaveURL(/\/login/);
    // токен доступа сохранён фронтом
    const token = await getToken(page);
    expect(token.length).toBeGreaterThan(10);
  });

  test('неверный пароль → остаёмся на /login', async ({ page }) => {
    await page.goto('/login');
    await page.getByPlaceholder('Имя пользователя').fill('admin');
    await page.getByPlaceholder('Пароль').fill('wrong-password');
    await page.getByRole('button', { name: 'Войти в систему' }).click();

    // не пустили внутрь
    await expect(page).toHaveURL(/\/login/);
  });
});
