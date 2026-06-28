/**
 * Zustand-срез состояния редактора последовательности (P7-2).
 *
 * Хранит рабочее состояние открытой последовательности:
 *  - шаги (локально редактируемые; включая пересчитанные GOTO)
 *  - выбранный шаг
 *  - критерии старта/остановки
 *  - флаг «есть несохранённые изменения»
 *
 * Реализует save-workflow:
 *  1. reorderSteps API   — если изменился порядок шагов
 *  2. updateStep API     — для каждого шага с изменёнными GOTO-целями
 *  3. updateSequence API — если изменились критерии старта/остановки
 *  4. перезагрузка с сервера
 *
 * Типизация строгая (без any). Типы шагов из src/types/sequence.ts
 * (миграция на ApiStepResponse запланирована в P7-5, ADR-0005 п.3).
 */

import { create } from 'zustand';
import { sequenceApi } from '../api/sequenceApi';
import type {
  SequenceResponse,
  StepResponse,
  StepCreateRequest,
} from '../types/sequence';
import {
  recalculateGotosAfterReorder,
  recalculateGotosAfterDelete,
} from '../utils/gotoUtils';

// ── Вспомогательные типы ─────────────────────────────────────────────────────

interface OriginalCriteria {
  start: string | null;
  stop: string | null;
}

// ── Конвертер StepResponse → StepCreateRequest ───────────────────────────────

export function stepToCreateRequest(step: StepResponse): StepCreateRequest {
  return {
    stepType: step.stepType,
    configJson: step.configJson,
    onSuccessAction: step.onSuccessAction,
    onSuccessGotoStep: step.onSuccessGotoStep ?? undefined,
    onSuccessNotify: step.onSuccessNotify,
    onFailureAction: step.onFailureAction,
    onFailureGotoStep: step.onFailureGotoStep ?? undefined,
    onFailureNotify: step.onFailureNotify,
  };
}

// ── Состояние и экшены ───────────────────────────────────────────────────────

interface SequenceEditorState {
  // ─ Данные с сервера (точка восстановления)
  sequenceId: number | null;
  sequenceName: string;
  sequenceDescription: string;
  sequenceStatus: SequenceResponse['status'];
  originalSteps: StepResponse[];
  originalCriteria: OriginalCriteria;

  // ─ Локальное рабочее состояние (мутируется без сохранения)
  steps: StepResponse[];               // отсортированы по orderIndex
  startCriteriaJson: string | null;
  stopCriteriaJson: string | null;
  selectedStepId: number | null;       // step.id (не orderIndex)
  isDirty: boolean;

  // ─ UI-статусы
  isLoading: boolean;
  isSaving: boolean;
  loadError: string | null;
  saveError: string | null;

  // ─ Экшены
  /** Загрузить последовательность с сервера и инициализировать рабочее состояние. */
  loadSequence: (id: number) => Promise<void>;

  /** Переставить шаги локально; GOTO автоматически пересчитываются. */
  reorderStepsLocally: (newIdOrder: number[]) => void;

  /** Выбрать шаг (по step.id) для отображения деталей. */
  selectStep: (stepId: number | null) => void;

  /** Обновить критерии старта/остановки в локальном состоянии. */
  updateCriteria: (startJson: string | null, stopJson: string | null) => void;

  /**
   * Применить к локальному состоянию вновь добавленный шаг (после API-вызова).
   * Перезагружает последовательность с сервера — сбрасывает все локальные изменения.
   */
  reloadAfterStepChange: () => Promise<void>;

  /**
   * Применить удаление шага локально: пересчитать GOTO-ссылки,
   * затем вызвать API-удаление и перезагрузить.
   */
  deleteStep: (stepId: number) => Promise<void>;

  /** Сохранить все отложенные изменения на сервер. */
  saveToServer: () => Promise<void>;

  /** Сбросить стор в начальное состояние. */
  reset: () => void;
}

// ── Начальное состояние ──────────────────────────────────────────────────────

const INITIAL: Omit<
  SequenceEditorState,
  | 'loadSequence'
  | 'reorderStepsLocally'
  | 'selectStep'
  | 'updateCriteria'
  | 'reloadAfterStepChange'
  | 'deleteStep'
  | 'saveToServer'
  | 'reset'
> = {
  sequenceId: null,
  sequenceName: '',
  sequenceDescription: '',
  sequenceStatus: 'DRAFT',
  originalSteps: [],
  originalCriteria: { start: null, stop: null },
  steps: [],
  startCriteriaJson: null,
  stopCriteriaJson: null,
  selectedStepId: null,
  isDirty: false,
  isLoading: false,
  isSaving: false,
  loadError: null,
  saveError: null,
};

// ── Вспомогательные функции ──────────────────────────────────────────────────

function sortedByOrderIndex(steps: StepResponse[]): StepResponse[] {
  return [...steps].sort((a, b) => a.orderIndex - b.orderIndex);
}

function initFromResponse(seq: SequenceResponse) {
  const sorted = sortedByOrderIndex(seq.steps);
  return {
    sequenceId: seq.id,
    sequenceName: seq.name,
    sequenceDescription: seq.description,
    sequenceStatus: seq.status,
    originalSteps: sorted,
    originalCriteria: { start: seq.startCriteriaJson, stop: seq.stopCriteriaJson },
    steps: sorted,
    startCriteriaJson: seq.startCriteriaJson,
    stopCriteriaJson: seq.stopCriteriaJson,
    selectedStepId: null as number | null,
    isDirty: false,
    isLoading: false,
    loadError: null as string | null,
  };
}

// ── Создание стора ────────────────────────────────────────────────────────────

export const useSequenceEditorStore = create<SequenceEditorState>()((set, get) => ({
  ...INITIAL,

  // ─────────────────────────────────────────────────────────────────
  loadSequence: async (id: number) => {
    set({ isLoading: true, loadError: null });
    try {
      const seq = await sequenceApi.getSequenceById(id);
      set(initFromResponse(seq));
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      set({ isLoading: false, loadError: msg });
    }
  },

  // ─────────────────────────────────────────────────────────────────
  reorderStepsLocally: (newIdOrder: number[]) => {
    const { steps } = get();
    const recalculated = recalculateGotosAfterReorder(steps, newIdOrder);
    set({ steps: sortedByOrderIndex(recalculated), isDirty: true });
  },

  // ─────────────────────────────────────────────────────────────────
  selectStep: (stepId: number | null) => {
    set({ selectedStepId: stepId });
  },

  // ─────────────────────────────────────────────────────────────────
  updateCriteria: (startJson: string | null, stopJson: string | null) => {
    set({ startCriteriaJson: startJson, stopCriteriaJson: stopJson, isDirty: true });
  },

  // ─────────────────────────────────────────────────────────────────
  reloadAfterStepChange: async () => {
    const { sequenceId } = get();
    if (sequenceId === null) return;
    try {
      const seq = await sequenceApi.getSequenceById(sequenceId);
      set(initFromResponse(seq));
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      set({ loadError: msg });
    }
  },

  // ─────────────────────────────────────────────────────────────────
  deleteStep: async (stepId: number) => {
    const { sequenceId, steps } = get();
    if (sequenceId === null) return;

    const target = steps.find(s => s.id === stepId);
    if (!target) return;

    // Пересчитываем GOTO до вызова API (оптимистичное обновление).
    // Передаём полный массив (включая удаляемый) — контракт recalculateGotosAfterDelete.
    // Удаляемый шаг фильтруем из результата после пересчёта.
    const recalculated = recalculateGotosAfterDelete(steps, target.orderIndex);
    const withoutDeleted = recalculated.filter(s => s.id !== stepId);
    set({ steps: sortedByOrderIndex(withoutDeleted), isDirty: true });

    try {
      await sequenceApi.deleteStep(sequenceId, stepId);
      // Перезагружаем с сервера, чтобы получить актуальные orderIndex
      await get().reloadAfterStepChange();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      set({ saveError: msg });
      // Откатываем — перезагружаем с сервера
      await get().reloadAfterStepChange();
    }
  },

  // ─────────────────────────────────────────────────────────────────
  saveToServer: async () => {
    const {
      sequenceId,
      steps,
      originalSteps,
      startCriteriaJson,
      stopCriteriaJson,
      originalCriteria,
      sequenceName,
      sequenceDescription,
    } = get();

    if (sequenceId === null) return;

    set({ isSaving: true, saveError: null });

    try {
      // 1. Если порядок изменился — отправляем reorder
      const currentOrder = steps.map(s => s.id);
      const originalOrder = originalSteps.map(s => s.id);
      const orderChanged = currentOrder.some((id, i) => id !== originalOrder[i]);

      if (orderChanged) {
        await sequenceApi.reorderSteps(sequenceId, currentOrder);
      }

      // 2. Если GOTO-ссылки изменились — обновляем шаги
      // Важно: делаем это ПОСЛЕ reorder, т.к. orderIndex в backend уже обновились.
      for (const step of steps) {
        const orig = originalSteps.find(s => s.id === step.id);
        if (
          orig !== undefined &&
          (step.onSuccessGotoStep !== orig.onSuccessGotoStep ||
            step.onFailureGotoStep !== orig.onFailureGotoStep)
        ) {
          await sequenceApi.updateStep(sequenceId, step.id, stepToCreateRequest(step));
        }
      }

      // 3. Если критерии изменились — обновляем последовательность
      const criteriaChanged =
        startCriteriaJson !== originalCriteria.start ||
        stopCriteriaJson !== originalCriteria.stop;

      if (criteriaChanged) {
        await sequenceApi.updateSequence(sequenceId, {
          name: sequenceName,
          description: sequenceDescription,
          startCriteriaJson: startCriteriaJson ?? undefined,
          stopCriteriaJson: stopCriteriaJson ?? undefined,
        });
      }

      // 4. Перезагружаем с сервера — получаем актуальное состояние
      const refreshed = await sequenceApi.getSequenceById(sequenceId);
      set({ ...initFromResponse(refreshed), isSaving: false });

    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      set({ isSaving: false, saveError: msg });
    }
  },

  // ─────────────────────────────────────────────────────────────────
  reset: () => set(INITIAL),
}));
