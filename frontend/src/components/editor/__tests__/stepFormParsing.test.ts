/**
 * Регрессионные тесты для stepFormParsing.ts (Blocker 1, P7-3).
 *
 * Тестируем, что:
 *  1. parseInitialValues читает timeoutSeconds из step.timeoutSeconds (верхний уровень),
 *     а НЕ из configJson — бэкенд хранит его как отдельную колонку таблицы steps.
 *  2. buildConfigJson для WAIT-шага НЕ включает timeoutSeconds в configJson.
 */

import { describe, it, expect } from 'vitest';
import { parseInitialValues, buildConfigJson, INITIAL_STATE } from '../stepFormParsing';
import type { FormState } from '../stepFormParsing';
import type { StepResponse } from '../../../types/sequence';

// ── Вспомогательные данные ────────────────────────────────────────────────────

const BASE_WAIT_STEP: StepResponse = {
  id: 1,
  stepType: 'WAIT',
  orderIndex: 1,
  configJson: JSON.stringify({ type: 'CONDITION_ACTIVE', conditionName: 'X' }),
  onSuccessAction: 'CONTINUE',
  onSuccessGotoStep: null,
  onSuccessNotify: false,
  onFailureAction: 'ABORT',
  onFailureGotoStep: null,
  onFailureNotify: false,
};

// ── parseInitialValues: WAIT + timeoutSeconds ─────────────────────────────────

describe('parseInitialValues — WAIT: timeoutSeconds', () => {
  it('читает timeoutSeconds из step.timeoutSeconds (верхний уровень), а не из configJson', () => {
    // configJson НЕ содержит timeoutSeconds; он на верхнем уровне step
    const step: StepResponse = { ...BASE_WAIT_STEP, timeoutSeconds: 300 };
    const state = parseInitialValues(step);
    expect(state.timeoutSeconds).toBe(300);
  });

  it('возвращает null если timeoutSeconds не задан на верхнем уровне', () => {
    const state = parseInitialValues(BASE_WAIT_STEP); // нет timeoutSeconds
    expect(state.timeoutSeconds).toBeNull();
  });

  it('игнорирует timeoutSeconds в configJson (устаревший формат)', () => {
    // configJson содержит timeoutSeconds (старые данные), top-level НЕ задан
    const step: StepResponse = {
      ...BASE_WAIT_STEP,
      configJson: JSON.stringify({ type: 'CONDITION_ACTIVE', conditionName: 'X', timeoutSeconds: 999 }),
    };
    const state = parseInitialValues(step);
    // top-level отсутствует → null; значение из configJson должно быть проигнорировано
    expect(state.timeoutSeconds).toBeNull();
  });

  it('top-level timeoutSeconds имеет приоритет над configJson', () => {
    const step: StepResponse = {
      ...BASE_WAIT_STEP,
      configJson: JSON.stringify({ type: 'CONDITION_ACTIVE', conditionName: 'X', timeoutSeconds: 999 }),
      timeoutSeconds: 120,
    };
    const state = parseInitialValues(step);
    expect(state.timeoutSeconds).toBe(120);
  });

  it('timeoutSeconds не попадает в criteriaJson (очищается при парсинге)', () => {
    const step: StepResponse = {
      ...BASE_WAIT_STEP,
      configJson: JSON.stringify({ type: 'CONDITION_ACTIVE', conditionName: 'X', timeoutSeconds: 999 }),
      timeoutSeconds: 120,
    };
    const state = parseInitialValues(step);
    const parsed = JSON.parse(state.criteriaJson) as Record<string, unknown>;
    expect(parsed).not.toHaveProperty('timeoutSeconds');
  });

  it('fromThisPointOnly по-прежнему читается из configJson', () => {
    const step: StepResponse = {
      ...BASE_WAIT_STEP,
      configJson: JSON.stringify({ type: 'CONDITION_ACTIVE', conditionName: 'X', fromThisPointOnly: true }),
    };
    const state = parseInitialValues(step);
    expect(state.fromThisPointOnly).toBe(true);
  });

  it('criteriaJson не содержит fromThisPointOnly (вынесен в отдельное поле)', () => {
    const step: StepResponse = {
      ...BASE_WAIT_STEP,
      configJson: JSON.stringify({ type: 'CONDITION_ACTIVE', conditionName: 'X', fromThisPointOnly: true }),
    };
    const state = parseInitialValues(step);
    const parsed = JSON.parse(state.criteriaJson) as Record<string, unknown>;
    expect(parsed).not.toHaveProperty('fromThisPointOnly');
  });
});

// ── buildConfigJson: WAIT не включает timeoutSeconds в configJson ──────────────

describe('buildConfigJson — WAIT: timeoutSeconds не в configJson', () => {
  const BASE_WAIT_STATE: FormState = {
    ...INITIAL_STATE,
    stepType: 'WAIT',
    criteriaJson: JSON.stringify({ type: 'CONDITION_ACTIVE', conditionName: 'X' }),
    timeoutSeconds: 300,
  };

  it('НЕ включает timeoutSeconds в configJson (передаётся на верхнем уровне запроса)', () => {
    const json = buildConfigJson(BASE_WAIT_STATE);
    const parsed = JSON.parse(json) as Record<string, unknown>;
    expect(parsed).not.toHaveProperty('timeoutSeconds');
  });

  it('включает поля критерия в configJson', () => {
    const json = buildConfigJson(BASE_WAIT_STATE);
    const parsed = JSON.parse(json) as Record<string, unknown>;
    expect(parsed).toHaveProperty('type', 'CONDITION_ACTIVE');
    expect(parsed).toHaveProperty('conditionName', 'X');
  });

  it('включает fromThisPointOnly в configJson когда задан', () => {
    const json = buildConfigJson({ ...BASE_WAIT_STATE, fromThisPointOnly: true });
    const parsed = JSON.parse(json) as Record<string, unknown>;
    expect(parsed).toHaveProperty('fromThisPointOnly', true);
  });

  it('не включает fromThisPointOnly когда false', () => {
    const json = buildConfigJson({ ...BASE_WAIT_STATE, fromThisPointOnly: false });
    const parsed = JSON.parse(json) as Record<string, unknown>;
    expect(parsed).not.toHaveProperty('fromThisPointOnly');
  });

  it('timeoutSeconds = 0 тоже не в configJson', () => {
    const json = buildConfigJson({ ...BASE_WAIT_STATE, timeoutSeconds: 0 });
    const parsed = JSON.parse(json) as Record<string, unknown>;
    expect(parsed).not.toHaveProperty('timeoutSeconds');
  });
});

// ── parseInitialValues: ACTION-шаги не затронуты ─────────────────────────────

describe('parseInitialValues — ACTION: поведение не изменилось', () => {
  it('ACTION с WAIT_TIME читает durationSeconds из configJson', () => {
    const step: StepResponse = {
      id: 2,
      stepType: 'ACTION',
      orderIndex: 2,
      configJson: JSON.stringify({ actionType: 'WAIT_TIME', durationSeconds: 3600 }),
      onSuccessAction: 'CONTINUE',
      onSuccessGotoStep: null,
      onSuccessNotify: false,
      onFailureAction: 'ABORT',
      onFailureGotoStep: null,
      onFailureNotify: false,
    };
    const state = parseInitialValues(step);
    expect(state.actionType).toBe('WAIT_TIME');
    expect(state.durationValue).toBe(1);
    expect(state.durationUnit).toBe('HOUR');
  });
});
