/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // Дефолт 8081 — локальный docker-compose (порт хоста, см. CLAUDE.md). CI E2E job
        // (.github/workflows/ci.yml) поднимает backend напрямую (`java -jar`, без Docker) на
        // 8080 и переопределяет через VITE_BACKEND_URL — раньше это было захардкожено на 8081
        // независимо от окружения, из-за чего в CI прокси стучался в порт, где ничего не
        // слушает (ECONNREFUSED на каждый /api-запрос, все E2E-сценарии падали на логине).
        target: process.env.VITE_BACKEND_URL ?? 'http://localhost:8081',
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'jsdom',
    // globals: false — виtест-функции импортируются явно, нет загрязнения глобального пространства
    globals: false,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  }
})
