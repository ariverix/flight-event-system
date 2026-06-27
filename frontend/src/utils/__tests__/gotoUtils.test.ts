/**
 * Unit-тесты GOTO-пересчёта.
 *
 * Покрываем три чистые функции из gotoUtils:
 *   1. recalculateGotosAfterReorder  — перестановка шагов
 *   2. recalculateGotosAfterInsert   — вставка нового шага
 *   3. recalculateGotosAfterDelete   — удаление шага
 *
 * Для каждой функции проверяем:
 *   - базовый случай (обычная операция)
 *   - граничные случаи (пустой массив, GOTO на себя, GOTO за пределы)
 *   - корректность пересчёта цепочки ссылок
 *   - отсутствие мутаций входных данных
 */

import { describe, it, expect } from 'vitest';
import type { StepResponse } from '../../types/sequence';
import {
  recalculateGotosAfterReorder,
  recalculateGotosAfterInsert,
  recalculateGotosAfterDelete,
} from '../gotoUtils';

// ── Хелпер ────────────────────────────────────────────────────────────────────

function step(
  partial: Partial<StepResponse> & Pick<StepResponse, 'id' | 'orderIndex'>,
): StepResponse {
  return {
    stepType: 'ACTION',
    configJson: '{}',
    onSuccessAction: 'CONTINUE',
    onSuccessGotoStep: null,
    onSuccessNotify: false,
    onFailureAction: 'ABORT',
    onFailureGotoStep: null,
    onFailureNotify: false,
    ...partial,
  };
}

// ── recalculateGotosAfterReorder ──────────────────────────────────────────────

describe('recalculateGotosAfterReorder', () => {
  it('пустой массив → пустой массив', () => {
    expect(recalculateGotosAfterReorder([], [])).toEqual([]);
  });

  it('один шаг — без GOTO — orderIndex остаётся 1', () => {
    const steps = [step({ id: 1, orderIndex: 1 })];
    const result = recalculateGotosAfterReorder(steps, [1]);
    expect(result).toHaveLength(1);
    expect(result[0].orderIndex).toBe(1);
    expect(result[0].onSuccessGotoStep).toBeNull();
  });

  it('перестановка двух шагов: GOTO 1 → GOTO 2', () => {
    // Шаг 1: нет GOTO. Шаг 2: GOTO к шагу 1.
    // Переставляем: шаг2 идёт первым, шаг1 вторым.
    // После перестановки шаг2 → orderIndex=1, шаг1 → orderIndex=2.
    // GOTO шага 2 был на orderIndex=1 (шаг1). Шаг1 теперь на orderIndex=2.
    // Ожидаем: GOTO шага 2 становится 2.
    const steps = [
      step({ id: 1, orderIndex: 1 }),
      step({ id: 2, orderIndex: 2, onSuccessAction: 'GOTO', onSuccessGotoStep: 1 }),
    ];
    const result = recalculateGotosAfterReorder(steps, [2, 1]);
    const step1 = result.find(s => s.id === 1)!;
    const step2 = result.find(s => s.id === 2)!;

    expect(step2.orderIndex).toBe(1);
    expect(step1.orderIndex).toBe(2);
    // Шаг2 теперь первый; его GOTO на шаг1 (ранее orderIndex=1, теперь 2)
    expect(step2.onSuccessGotoStep).toBe(2);
    // Шаг1 без GOTO — без изменений
    expect(step1.onSuccessGotoStep).toBeNull();
  });

  it('три шага: перестановка [1,2,3] → [3,1,2], GOTO пересчитываются', () => {
    // шаг3→successGoto=1, шаг2→failureGoto=1
    const steps = [
      step({ id: 1, orderIndex: 1 }),
      step({ id: 2, orderIndex: 2, onFailureAction: 'GOTO', onFailureGotoStep: 1 }),
      step({ id: 3, orderIndex: 3, onSuccessAction: 'GOTO', onSuccessGotoStep: 1 }),
    ];
    // Новый порядок: 3 1 2 → шаг3=idx1, шаг1=idx2, шаг2=idx3
    const result = recalculateGotosAfterReorder(steps, [3, 1, 2]);

    const r3 = result.find(s => s.id === 3)!;
    const r1 = result.find(s => s.id === 1)!;
    const r2 = result.find(s => s.id === 2)!;

    expect(r3.orderIndex).toBe(1);
    expect(r1.orderIndex).toBe(2);
    expect(r2.orderIndex).toBe(3);

    // GOTO шага3 было 1 (шаг1). Шаг1 теперь на idx=2.
    expect(r3.onSuccessGotoStep).toBe(2);
    // GOTO шага2 по failure было 1 (шаг1). Шаг1 теперь на idx=2.
    expect(r2.onFailureGotoStep).toBe(2);
    // Шаг1 без GOTO
    expect(r1.onSuccessGotoStep).toBeNull();
  });

  it('GOTO на себя (самозацикливание) сохраняется', () => {
    // Шаг1 → GOTO к шагу1 (петля). Порядок не меняется.
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 1 }),
    ];
    const result = recalculateGotosAfterReorder(steps, [1]);
    expect(result[0].onSuccessGotoStep).toBe(1);
  });

  it('GOTO на себя после перестановки обновляется на новый orderIndex', () => {
    // Шаги 1, 2. Шаг2 → GOTO к шагу2 (петля). Переставляем: [2, 1].
    // Шаг2 → orderIndex=1, шаг1 → orderIndex=2.
    // GOTO шага2 было 2, после перестановки шаг2 стал idx=1.
    // Ожидаем: GOTO шага2 = 1 (всё ещё сам на себя).
    const steps = [
      step({ id: 1, orderIndex: 1 }),
      step({ id: 2, orderIndex: 2, onSuccessAction: 'GOTO', onSuccessGotoStep: 2 }),
    ];
    const result = recalculateGotosAfterReorder(steps, [2, 1]);
    const r2 = result.find(s => s.id === 2)!;
    expect(r2.orderIndex).toBe(1);
    expect(r2.onSuccessGotoStep).toBe(1); // сам на себя, новый orderIndex
  });

  it('GOTO за пределами диапазона — сохраняется без изменений', () => {
    // Шаг1 → GOTO к шагу99 (несуществующий). Перестановка без изменений.
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 99 }),
    ];
    const result = recalculateGotosAfterReorder(steps, [1]);
    // Несуществующий GOTO — не меняем (остаётся 99)
    expect(result[0].onSuccessGotoStep).toBe(99);
  });

  it('шаги без GOTO (action != GOTO) не затрагиваются', () => {
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'CONTINUE' }),
      step({ id: 2, orderIndex: 2, onSuccessAction: 'END', onFailureAction: 'ABORT' }),
    ];
    const result = recalculateGotosAfterReorder(steps, [2, 1]);
    for (const s of result) {
      expect(s.onSuccessGotoStep).toBeNull();
      expect(s.onFailureGotoStep).toBeNull();
    }
  });

  it('не мутирует входной массив', () => {
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 2 }),
      step({ id: 2, orderIndex: 2 }),
    ];
    const original = JSON.stringify(steps);
    recalculateGotosAfterReorder(steps, [2, 1]);
    expect(JSON.stringify(steps)).toBe(original);
  });

  it('перестановка четырёх шагов: цепочка GOTO пересчитывается', () => {
    // [1,2,3,4] → [4,3,2,1]
    // Шаг1→GOTO=2, Шаг2→GOTO=3, Шаг3→GOTO=4, Шаг4→GOTO=1
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 2 }),
      step({ id: 2, orderIndex: 2, onSuccessAction: 'GOTO', onSuccessGotoStep: 3 }),
      step({ id: 3, orderIndex: 3, onSuccessAction: 'GOTO', onSuccessGotoStep: 4 }),
      step({ id: 4, orderIndex: 4, onSuccessAction: 'GOTO', onSuccessGotoStep: 1 }),
    ];
    // После [4,3,2,1]: шаг4=idx1, шаг3=idx2, шаг2=idx3, шаг1=idx4
    // oldIdx→newIdx: 1→4, 2→3, 3→2, 4→1
    const result = recalculateGotosAfterReorder(steps, [4, 3, 2, 1]);

    const r1 = result.find(s => s.id === 1)!;
    const r2 = result.find(s => s.id === 2)!;
    const r3 = result.find(s => s.id === 3)!;
    const r4 = result.find(s => s.id === 4)!;

    // Шаг1 был GOTO→2; шаг2 теперь на idx=3 → GOTO=3
    expect(r1.onSuccessGotoStep).toBe(3);
    // Шаг2 был GOTO→3; шаг3 теперь на idx=2 → GOTO=2
    expect(r2.onSuccessGotoStep).toBe(2);
    // Шаг3 был GOTO→4; шаг4 теперь на idx=1 → GOTO=1
    expect(r3.onSuccessGotoStep).toBe(1);
    // Шаг4 был GOTO→1; шаг1 теперь на idx=4 → GOTO=4
    expect(r4.onSuccessGotoStep).toBe(4);
  });
});

// ── recalculateGotosAfterInsert ───────────────────────────────────────────────

describe('recalculateGotosAfterInsert', () => {
  it('пустой массив → пустой массив', () => {
    expect(recalculateGotosAfterInsert([], 1)).toEqual([]);
  });

  it('вставка в начало: GOTO ≥ 1 увеличиваются на 1', () => {
    // Шаги 1, 2. Шаг1→GOTO=2. Вставляем новый на позицию 1.
    // Шаги сдвигаются: старый шаг1→idx2, старый шаг2→idx3.
    // GOTO шага1 (было 2, шаг2) → теперь 3 (шаг2 на idx3).
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 2 }),
      step({ id: 2, orderIndex: 2 }),
    ];
    const result = recalculateGotosAfterInsert(steps, 1);
    expect(result[0].onSuccessGotoStep).toBe(3);
  });

  it('вставка в середину: GOTO до вставки не меняются, GOTO после — увеличиваются', () => {
    // Шаги 1,2,3. Вставляем на позицию 2.
    // Шаг1→GOTO=1 (до вставки) — не меняется.
    // Шаг3→GOTO=2 (в точку вставки или после) — увеличивается.
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 1 }),
      step({ id: 2, orderIndex: 2 }),
      step({ id: 3, orderIndex: 3, onSuccessAction: 'GOTO', onSuccessGotoStep: 2 }),
    ];
    const result = recalculateGotosAfterInsert(steps, 2);
    // GOTO шага1 = 1 (< insertedAt=2) → без изменений
    expect(result[0].onSuccessGotoStep).toBe(1);
    // GOTO шага3 = 2 (>= insertedAt=2) → увеличивается до 3
    expect(result[2].onSuccessGotoStep).toBe(3);
  });

  it('вставка в конец: ни один GOTO не меняется', () => {
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 1 }),
      step({ id: 2, orderIndex: 2, onSuccessAction: 'GOTO', onSuccessGotoStep: 2 }),
    ];
    // Вставляем после последнего — insertedAt=3
    const result = recalculateGotosAfterInsert(steps, 3);
    expect(result[0].onSuccessGotoStep).toBe(1);
    expect(result[1].onSuccessGotoStep).toBe(2);
  });

  it('GOTO на себя после вставки перед ним обновляется', () => {
    // Шаг1 → GOTO=1 (петля). Вставляем на позицию 1 → шаг1 сдвигается на idx=2.
    // GOTO=1 >= insertedAt=1 → становится 2 (всё ещё сам на себя).
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 1 }),
    ];
    const result = recalculateGotosAfterInsert(steps, 1);
    expect(result[0].onSuccessGotoStep).toBe(2);
  });

  it('не мутирует входной массив', () => {
    const steps = [step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 1 })];
    const original = JSON.stringify(steps);
    recalculateGotosAfterInsert(steps, 1);
    expect(JSON.stringify(steps)).toBe(original);
  });
});

// ── recalculateGotosAfterDelete ───────────────────────────────────────────────

describe('recalculateGotosAfterDelete', () => {
  it('пустой массив → пустой массив', () => {
    expect(recalculateGotosAfterDelete([], 1)).toEqual([]);
  });

  it('GOTO на удалённый шаг становится null', () => {
    // Шаги 1, 2, 3. Шаг1→GOTO=2. Удаляем шаг2.
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 2 }),
      step({ id: 2, orderIndex: 2 }),
      step({ id: 3, orderIndex: 3 }),
    ];
    const result = recalculateGotosAfterDelete(steps, 2);
    const r1 = result.find(s => s.id === 1)!;
    expect(r1.onSuccessGotoStep).toBeNull();
  });

  it('GOTO на шаг после удалённого декрементируется', () => {
    // Шаги 1, 2, 3. Шаг1→GOTO=3. Удаляем шаг2 (idx=2).
    // Шаг3 сдвигается на idx=2. GOTO шага1 = 3 > 2 → становится 2.
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 3 }),
      step({ id: 2, orderIndex: 2 }),
      step({ id: 3, orderIndex: 3 }),
    ];
    const result = recalculateGotosAfterDelete(steps, 2);
    const r1 = result.find(s => s.id === 1)!;
    expect(r1.onSuccessGotoStep).toBe(2);
  });

  it('GOTO на шаг до удалённого не меняется', () => {
    // Шаги 1, 2, 3. Шаг3→GOTO=1. Удаляем шаг2.
    // GOTO шага3 = 1 < 2 → без изменений.
    const steps = [
      step({ id: 1, orderIndex: 1 }),
      step({ id: 2, orderIndex: 2 }),
      step({ id: 3, orderIndex: 3, onSuccessAction: 'GOTO', onSuccessGotoStep: 1 }),
    ];
    const result = recalculateGotosAfterDelete(steps, 2);
    const r3 = result.find(s => s.id === 3)!;
    expect(r3.onSuccessGotoStep).toBe(1);
  });

  it('удаление первого шага: все GOTO > 1 декрементируются', () => {
    const steps = [
      step({ id: 1, orderIndex: 1 }),
      step({ id: 2, orderIndex: 2, onSuccessAction: 'GOTO', onSuccessGotoStep: 3 }),
      step({ id: 3, orderIndex: 3, onFailureAction: 'GOTO', onFailureGotoStep: 2 }),
    ];
    const result = recalculateGotosAfterDelete(steps, 1);
    // GOTO 3 > 1 → 2
    expect(result.find(s => s.id === 2)!.onSuccessGotoStep).toBe(2);
    // GOTO 2 > 1 → 1
    expect(result.find(s => s.id === 3)!.onFailureGotoStep).toBe(1);
  });

  it('оба типа GOTO (success и failure) обновляются независимо', () => {
    // Шаг1: successGoto=2 (удаляемый), failureGoto=3 (после удаляемого)
    const steps = [
      step({
        id: 1,
        orderIndex: 1,
        onSuccessAction: 'GOTO',
        onSuccessGotoStep: 2,
        onFailureAction: 'GOTO',
        onFailureGotoStep: 3,
      }),
      step({ id: 2, orderIndex: 2 }),
      step({ id: 3, orderIndex: 3 }),
    ];
    const result = recalculateGotosAfterDelete(steps, 2);
    const r1 = result.find(s => s.id === 1)!;
    expect(r1.onSuccessGotoStep).toBeNull(); // удалённая цель
    expect(r1.onFailureGotoStep).toBe(2);    // 3 > 2 → 2
  });

  it('безопасно при GOTO = 0 (за нижней границей)', () => {
    // Некорректный, но возможный вход — GOTO=0 меньше любого валидного orderIndex
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 0 }),
    ];
    const result = recalculateGotosAfterDelete(steps, 1);
    // 0 < 1 = deletedAt → без изменений (не null, не декрементируется)
    expect(result[0].onSuccessGotoStep).toBe(0);
  });

  it('не мутирует входной массив', () => {
    const steps = [
      step({ id: 1, orderIndex: 1, onSuccessAction: 'GOTO', onSuccessGotoStep: 2 }),
      step({ id: 2, orderIndex: 2 }),
    ];
    const original = JSON.stringify(steps);
    recalculateGotosAfterDelete(steps, 2);
    expect(JSON.stringify(steps)).toBe(original);
  });
});
