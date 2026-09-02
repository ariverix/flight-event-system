import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { resolveWsUrl } from '../resolveWsUrl';

function setLocation(protocol: string, host: string): void {
  Object.defineProperty(window, 'location', {
    value: { ...window.location, protocol, host },
    writable: true,
    configurable: true,
  });
}

describe('resolveWsUrl', () => {
  const originalLocation = window.location;

  beforeEach(() => {
    delete (window as { __env__?: unknown }).__env__;
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    Object.defineProperty(window, 'location', { value: originalLocation, writable: true, configurable: true });
  });

  it('приоритет 1: использует window.__env__.VITE_WS_URL, если задан', () => {
    window.__env__ = { VITE_WS_URL: 'wss://runtime-override.example/ws/eca' };
    vi.stubEnv('VITE_WS_URL', 'ws://build-time-should-be-ignored/ws/eca');

    expect(resolveWsUrl()).toBe('wss://runtime-override.example/ws/eca');
  });

  it('приоритет 2: при отсутствии window.__env__ использует import.meta.env.VITE_WS_URL', () => {
    vi.stubEnv('VITE_WS_URL', 'ws://localhost:8080/ws/eca');

    expect(resolveWsUrl()).toBe('ws://localhost:8080/ws/eca');
  });

  it('игнорирует window.__env__.VITE_WS_URL, если это пустая строка', () => {
    window.__env__ = { VITE_WS_URL: '' };
    vi.stubEnv('VITE_WS_URL', 'ws://localhost:8080/ws/eca');

    expect(resolveWsUrl()).toBe('ws://localhost:8080/ws/eca');
  });

  it('приоритет 3: без override выводит URL из window.location (http → ws)', () => {
    vi.stubEnv('VITE_WS_URL', '');
    setLocation('http:', 'eca.local:8081');

    expect(resolveWsUrl()).toBe('ws://eca.local:8081/ws/eca');
  });

  it('приоритет 3: без override выводит URL из window.location (https → wss)', () => {
    vi.stubEnv('VITE_WS_URL', '');
    setLocation('https:', 'eca.example.ru');

    expect(resolveWsUrl()).toBe('wss://eca.example.ru/ws/eca');
  });
});
