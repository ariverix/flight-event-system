/**
 * Утилиты для пересчёта GOTO-ссылок при изменении порядка шагов.
 *
 * Домен: решения GOTO step {x} ссылаются на `orderIndex` целевого шага.
 * При перестановке/добавлении/удалении шагов `orderIndex`-значения меняются,
 * и все существующие ссылки GOTO нужно обновить, чтобы они продолжали
 * указывать на тот же логический шаг (идентифицированный по step.id).
 *
 * Чистые функции — без побочных эффектов, легко тестируются.
 */

import type { StepResponse, TransitionAction } from '../types/sequence';

// ── Вспомогательные ──────────────────────────────────────────────────────────

function remapTarget(
  action: TransitionAction,
  current: number | null,
  remap: (idx: number) => number | null,
): number | null {
  if (action !== 'GOTO' || current === null) return current;
  return remap(current);
}

// ── Пересчёт при перестановке ────────────────────────────────────────────────

/**
 * Пересчитывает GOTO-ссылки после перестановки шагов.
 *
 * Алгоритм:
 * 1. Строим карту stepId → текущий orderIndex.
 * 2. По `newIdOrder` строим карту oldOrderIndex → newOrderIndex.
 * 3. Для каждого шага обновляем orderIndex и GOTO-цели через эту карту.
 *
 * @param steps      - Текущий массив шагов (с актуальными orderIndex).
 * @param newIdOrder - ID шагов в желаемом новом порядке (newIdOrder[0] → orderIndex 1 и т.д.).
 * @returns Новый массив шагов с обновлёнными orderIndex и GOTO-целями.
 */
export function recalculateGotosAfterReorder(
  steps: StepResponse[],
  newIdOrder: number[],
): StepResponse[] {
  // stepId → текущий orderIndex
  const idToOldIdx = new Map<number, number>(steps.map(s => [s.id, s.orderIndex]));

  // oldOrderIndex → newOrderIndex (позиция в newIdOrder + 1)
  const oldIdx2newIdx = new Map<number, number>();
  newIdOrder.forEach((stepId, position) => {
    const oldIdx = idToOldIdx.get(stepId);
    if (oldIdx !== undefined) {
      oldIdx2newIdx.set(oldIdx, position + 1);
    }
  });

  // stepId → newOrderIndex
  const idToNewIdx = new Map<number, number>(
    newIdOrder.map((stepId, pos) => [stepId, pos + 1]),
  );

  const remap = (idx: number): number | null =>
    // Если цель не найдена (GOTO был вне диапазона) — сохраняем как есть
    oldIdx2newIdx.get(idx) ?? idx;

  return steps.map(step => ({
    ...step,
    orderIndex: idToNewIdx.get(step.id) ?? step.orderIndex,
    onSuccessGotoStep: remapTarget(step.onSuccessAction, step.onSuccessGotoStep, remap),
    onFailureGotoStep: remapTarget(step.onFailureAction, step.onFailureGotoStep, remap),
  }));
}

// ── Пересчёт при вставке ─────────────────────────────────────────────────────

/**
 * Пересчитывает GOTO-ссылки после вставки нового шага.
 *
 * Шаги, стоящие в позиции `insertedAt` и дальше, сдвигаются на 1 вперёд,
 * поэтому все GOTO, указывающие на них, тоже должны увеличиться на 1.
 *
 * Новый шаг в `steps` ещё не присутствует — функция получает только
 * существующие шаги и обновляет их ссылки.
 *
 * @param steps      - Существующие шаги (без нового шага).
 * @param insertedAt - 1-based orderIndex вставляемого шага.
 * @returns Шаги с обновлёнными GOTO-целями.
 */
export function recalculateGotosAfterInsert(
  steps: StepResponse[],
  insertedAt: number,
): StepResponse[] {
  const remap = (idx: number): number | null =>
    idx >= insertedAt ? idx + 1 : idx;

  return steps.map(step => ({
    ...step,
    onSuccessGotoStep: remapTarget(step.onSuccessAction, step.onSuccessGotoStep, remap),
    onFailureGotoStep: remapTarget(step.onFailureAction, step.onFailureGotoStep, remap),
  }));
}

// ── Пересчёт при удалении ────────────────────────────────────────────────────

/**
 * Пересчитывает GOTO-ссылки после удаления шага.
 *
 * - GOTO на удалённый шаг (`deletedAt`) → `null` (ссылка недействительна).
 * - GOTO на шаг после удалённого → декрементируется на 1.
 * - GOTO на шаг до удалённого → без изменений.
 *
 * Функция получает шаги ДО удаления (включая удаляемый).
 * Вызывающий код должен убрать шаг из массива самостоятельно.
 *
 * @param steps     - Шаги до удаления (включая удаляемый).
 * @param deletedAt - 1-based orderIndex удаляемого шага.
 * @returns Оставшиеся шаги с обновлёнными GOTO-целями.
 */
export function recalculateGotosAfterDelete(
  steps: StepResponse[],
  deletedAt: number,
): StepResponse[] {
  const remap = (idx: number): number | null => {
    if (idx === deletedAt) return null; // цель удалена — ссылка стала недействительной
    if (idx > deletedAt) return idx - 1; // сдвиг назад
    return idx;
  };

  return steps.map(step => ({
    ...step,
    onSuccessGotoStep: remapTarget(step.onSuccessAction, step.onSuccessGotoStep, remap),
    onFailureGotoStep: remapTarget(step.onFailureAction, step.onFailureGotoStep, remap),
  }));
}
