import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { TLEventCard } from '../TLEventCard';

afterEach(() => {
  cleanup();
});

const BASE = { id: 1, timestamp: '2026-01-15T10:00:00Z' };

describe('TLEventCard', () => {
  it('MESSAGE_RECEIVED: рендерит шаблон и направление из словаря (без хардкода)', () => {
    render(
      <TLEventCard
        event={{
          ...BASE,
          type: 'MESSAGE_RECEIVED',
          msgTemplate: 'POSITION_REPORT',
          msgDirection: 'downlink',
          aircraftId: 'VP-BQR',
        }}
      />,
    );

    expect(screen.getByText('Сообщение')).toBeTruthy();
    expect(screen.getByText('POSITION_REPORT')).toBeTruthy();
    expect(screen.getByText('Нисходящая')).toBeTruthy();
  });

  it('EXECUTION_STARTED: рендерит тег «Запуск последовательности»', () => {
    render(<TLEventCard event={{ ...BASE, type: 'EXECUTION_STARTED', execId: 42 }} />);

    expect(screen.getByText('Запуск')).toBeTruthy();
    expect(screen.getByText('Запуск последовательности')).toBeTruthy();
    expect(screen.getByText('Выполнение #42')).toBeTruthy();
  });

  it('STEP_COMPLETED успешный: рендерит «Успех»', () => {
    render(
      <TLEventCard
        event={{ ...BASE, type: 'STEP_COMPLETED', stepNum: 3, stepType: 'ACTION', stepResult: 'SUCCESS' }}
      />,
    );

    expect(screen.getByText('✓ Успех')).toBeTruthy();
  });

  it('STEP_COMPLETED неуспешный: рендерит «Ошибка»', () => {
    render(
      <TLEventCard
        event={{ ...BASE, type: 'STEP_COMPLETED', stepNum: 3, stepType: 'ACTION', stepResult: 'FAILURE' }}
      />,
    );

    expect(screen.getByText('✗ Ошибка')).toBeTruthy();
  });

  it('EXECUTION_COMPLETED: рендерит описание успеха из словаря', () => {
    render(<TLEventCard event={{ ...BASE, type: 'EXECUTION_COMPLETED', execId: 7 }} />);

    expect(screen.getByText('Последовательность успешно завершена')).toBeTruthy();
  });

  it('EXECUTION_FAILED: рендерит описание ошибки из словаря', () => {
    render(<TLEventCard event={{ ...BASE, type: 'EXECUTION_FAILED', execId: 7 }} />);

    expect(screen.getByText('Выполнение завершено с ошибкой')).toBeTruthy();
  });

  it('неизвестный тип события: показывает «Событие: {type}»', () => {
    render(<TLEventCard event={{ ...BASE, type: 'SOMETHING_ELSE' }} />);

    expect(screen.getByText(/Событие:/)).toBeTruthy();
  });
});
