import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { NodeDetailPanel } from '../NodeDetailPanel';
import type { Node } from '@xyflow/react';

afterEach(() => {
  cleanup();
});

describe('NodeDetailPanel', () => {
  it('пустое состояние: подсказка из словаря (без хардкода)', () => {
    render(<NodeDetailPanel selectedNode={null} onClose={vi.fn()} isDark={false} />);

    expect(screen.getByText(/Нажмите на узел/)).toBeTruthy();
    expect(screen.getByText(/для просмотра деталей/)).toBeTruthy();
  });

  it('выбранный узел: рендерит заголовок, тип шага, состояние и детали', () => {
    const node = {
      id:   '1',
      type: 'action',
      position: { x: 0, y: 0 },
      data: {
        configLabel: 'Отправить сообщение',
        orderIndex:  2,
        stepType:    'ACTION',
        state:       'success',
      },
    } as unknown as Node;

    render(<NodeDetailPanel selectedNode={node} onClose={vi.fn()} isDark={false} />);

    expect(screen.getByText('Отправить сообщение')).toBeTruthy();
    expect(screen.getByText('Завершено')).toBeTruthy();
    expect(screen.getByText('Тип шага')).toBeTruthy();
    expect(screen.getByText('Порядковый номер')).toBeTruthy();
    expect(screen.getByText('#2')).toBeTruthy();
  });

  it('клик по закрытию зовёт onClose', () => {
    const onClose = vi.fn();
    const node = {
      id: '1', type: 'action', position: { x: 0, y: 0 },
      data: { configLabel: 'Шаг', orderIndex: 1, stepType: 'ACTION', state: 'idle' },
    } as unknown as Node;

    render(<NodeDetailPanel selectedNode={node} onClose={onClose} isDark={false} />);
    fireEvent.click(screen.getByText('×'));

    expect(onClose).toHaveBeenCalledOnce();
  });
});
