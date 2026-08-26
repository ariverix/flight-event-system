import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { ErrorBoundary } from '../ErrorBoundary';

function Boom(): never {
  throw new Error('boom');
}

describe('ErrorBoundary', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('рендерит детей, когда ошибки нет', () => {
    render(
      <ErrorBoundary>
        <div>content</div>
      </ErrorBoundary>,
    );

    expect(screen.getByText('content')).toBeTruthy();
  });

  it('перехватывает исключение при рендере ребёнка и показывает фолбэк вместо краха', () => {
    // React пишет ошибку в консоль дважды (наш componentDidCatch + внутренний лог React) — шумно, подавляем
    vi.spyOn(console, 'error').mockImplementation(() => {});

    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    expect(screen.getByText('Что-то пошло не так')).toBeTruthy();
    expect(screen.queryByText('content')).toBeNull();
  });

  it('перезагружает страницу по клику на кнопку фолбэка', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const reload = vi.fn();
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, reload },
    });

    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    fireEvent.click(screen.getByRole('button'));

    expect(reload).toHaveBeenCalledOnce();
  });
});
