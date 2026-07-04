import { Page, expect } from '@playwright/test';

/** Логин через реальную форму (демо-учётка admin/admin, V8). */
export async function login(page: Page, username = 'admin', password = 'admin'): Promise<void> {
  await page.goto('/login');
  await page.getByPlaceholder('Имя пользователя').fill(username);
  await page.getByPlaceholder('Пароль').fill(password);
  await page.getByRole('button', { name: 'Войти в систему' }).click();
  // после успешного логина — редирект прочь со страницы /login
  await expect(page).not.toHaveURL(/\/login/, { timeout: 15_000 });
}

/** JWT кладётся фронтом в localStorage['jwt'] (см. axiosConfig) — читаем для API-сидинга. */
export async function getToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => localStorage.getItem('jwt'));
  expect(token, 'JWT должен быть в localStorage после логина').toBeTruthy();
  return token as string;
}
