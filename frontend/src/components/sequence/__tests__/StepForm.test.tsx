import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StepForm } from '../StepForm';
import type { StepResponse } from '../../../types/sequence';

// jsdom не реализует matchMedia, а antd Select/Form его требуют
window.matchMedia = ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addListener: () => {},
  removeListener: () => {},
  addEventListener: () => {},
  removeEventListener: () => {},
  dispatchEvent: () => false,
})) as typeof window.matchMedia;

afterEach(() => {
  cleanup();
});

/** Клик по опции открытого дропдауна AntD (визуальный узел .ant-select-item-option-content). */
function clickDropdownOption(text: RegExp): void {
  const contents = Array.from(document.querySelectorAll('.ant-select-item-option-content'));
  const target = contents.find((el) => text.test(el.textContent ?? ''));
  expect(target, `опция дропдауна ${text} не найдена`).toBeTruthy();
  fireEvent.click(target!);
}

describe('StepForm', () => {
  it('по умолчанию (ACTION): рендерит лейблы и опции из словаря (без хардкода)', () => {
    render(<StepForm onSubmit={vi.fn()} onCancel={vi.fn()} />);

    expect(screen.getByText('Тип шага')).toBeTruthy();
    expect(screen.getByText('Тип действия')).toBeTruthy();
    expect(screen.getByText('Переходы')).toBeTruthy();
    expect(screen.getByText('При успехе (true)')).toBeTruthy();
    expect(screen.getByText('При ошибке / false')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Добавить шаг' })).toBeTruthy();
  });

  it('выбор SEND_UPLINK: показывает поле «Шаблон сообщения» с переведённой опцией', async () => {
    const user = userEvent.setup();
    render(<StepForm onSubmit={vi.fn()} onCancel={vi.fn()} />);

    await user.click(screen.getByLabelText('Тип действия'));
    clickDropdownOption(/SEND_UPLINK/);

    expect(screen.getByText('Шаблон сообщения')).toBeTruthy();
    expect(screen.getByText('Параметры (JSON)')).toBeTruthy();
  });

  it('переключение на EVALUATE: показывает поле «Тип критерия»', async () => {
    const user = userEvent.setup();
    render(<StepForm onSubmit={vi.fn()} onCancel={vi.fn()} />);

    await user.click(screen.getByLabelText('Тип шага'));
    clickDropdownOption(/EVALUATE/);

    expect(screen.getByText('Тип критерия')).toBeTruthy();
    expect(screen.queryByText('Тип действия')).toBeFalsy();
  });

  it('режим редактирования: кнопка — «Сохранить шаг»', () => {
    const initialValues: StepResponse = {
      id: 1, stepType: 'ACTION', orderIndex: 0,
      configJson: JSON.stringify({ actionType: 'WAIT_TIME', durationSeconds: 30 }),
      onSuccessAction: 'CONTINUE', onSuccessGotoStep: null, onSuccessNotify: false,
      onFailureAction: 'ABORT', onFailureGotoStep: null, onFailureNotify: false,
    };
    render(<StepForm onSubmit={vi.fn()} onCancel={vi.fn()} initialValues={initialValues} />);

    expect(screen.getByRole('button', { name: 'Сохранить шаг' })).toBeTruthy();
  });

  it('кнопка «Отмена» зовёт onCancel', async () => {
    const onCancel = vi.fn();
    const user = userEvent.setup();
    render(<StepForm onSubmit={vi.fn()} onCancel={onCancel} />);

    await user.click(screen.getByRole('button', { name: 'Отмена' }));

    expect(onCancel).toHaveBeenCalledOnce();
  });
});
