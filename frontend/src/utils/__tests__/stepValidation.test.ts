/**
 * Unit-тесты валидации конфигурации шагов.
 *
 * Покрываем:
 *   1. validateActionConfig   — 5 типов ACTION
 *   2. validateEvaluateConfig — EVALUATE (делегирует критерию)
 *   3. validateWaitConfig     — WAIT (критерий + timeoutSeconds)
 *   4. validateStepConfigJson — строковый вход
 *   5. validateDecisions      — GOTO-ссылки
 */

import { describe, it, expect } from 'vitest';
import {
  validateActionConfig,
  validateEvaluateConfig,
  validateWaitConfig,
  validateStepConfigJson,
  validateDecisions,
} from '../stepValidation';

// ── validateActionConfig ──────────────────────────────────────────────────────

describe('validateActionConfig — RAISE_CONDITION', () => {
  it('валидный', () => {
    expect(validateActionConfig({
      actionType: 'RAISE_CONDITION',
      conditionName: 'WEATHER_ALERT',
      alertLevel: 'HIGH',
    }).valid).toBe(true);
  });

  it('все уровни алертов валидны', () => {
    for (const lvl of ['NO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']) {
      expect(validateActionConfig({
        actionType: 'RAISE_CONDITION',
        conditionName: 'X',
        alertLevel: lvl,
      }).valid).toBe(true);
    }
  });

  it('без conditionName → ошибка', () => {
    const r = validateActionConfig({ actionType: 'RAISE_CONDITION', alertLevel: 'HIGH' });
    expect(r.valid).toBe(false);
    expect(r.errors.some(e => e.field === 'conditionName')).toBe(true);
  });

  it('пустой conditionName → ошибка', () => {
    expect(validateActionConfig({
      actionType: 'RAISE_CONDITION',
      conditionName: '  ',
      alertLevel: 'HIGH',
    }).valid).toBe(false);
  });

  it('без alertLevel → ошибка', () => {
    const r = validateActionConfig({ actionType: 'RAISE_CONDITION', conditionName: 'X' });
    expect(r.valid).toBe(false);
    expect(r.errors.some(e => e.messageKey === 'errAlertLevel')).toBe(true);
  });

  it('неверный alertLevel → ошибка', () => {
    const r = validateActionConfig({
      actionType: 'RAISE_CONDITION',
      conditionName: 'X',
      alertLevel: 'EXTREME',
    });
    expect(r.valid).toBe(false);
  });
});

describe('validateActionConfig — CLOSE_CONDITION', () => {
  it('валидный с conditionName', () => {
    expect(validateActionConfig({
      actionType: 'CLOSE_CONDITION',
      conditionName: 'WEATHER_ALERT',
    }).valid).toBe(true);
  });

  it('alertLevel необязателен', () => {
    expect(validateActionConfig({
      actionType: 'CLOSE_CONDITION',
      conditionName: 'X',
      alertLevel: 'NO',
    }).valid).toBe(true);
  });

  it('без conditionName → ошибка', () => {
    expect(validateActionConfig({ actionType: 'CLOSE_CONDITION' }).valid).toBe(false);
  });
});

describe('validateActionConfig — SEND_UPLINK', () => {
  it('валидный', () => {
    expect(validateActionConfig({
      actionType: 'SEND_UPLINK',
      templateName: 'WEATHER_UPDATE',
      origin: 'COMPUTER_GENERATED',
    }).valid).toBe(true);
  });

  it('EXTERNAL_USER origin — валиден', () => {
    expect(validateActionConfig({
      actionType: 'SEND_UPLINK',
      templateName: 'WEATHER_UPDATE',
      origin: 'EXTERNAL_USER',
    }).valid).toBe(true);
  });

  it('без templateName → ошибка', () => {
    const r = validateActionConfig({ actionType: 'SEND_UPLINK', origin: 'COMPUTER_GENERATED' });
    expect(r.valid).toBe(false);
    expect(r.errors.some(e => e.field === 'templateName')).toBe(true);
  });

  it('без origin → ошибка', () => {
    const r = validateActionConfig({ actionType: 'SEND_UPLINK', templateName: 'X' });
    expect(r.valid).toBe(false);
    expect(r.errors.some(e => e.field === 'origin')).toBe(true);
  });

  it('неверный origin → ошибка', () => {
    expect(validateActionConfig({
      actionType: 'SEND_UPLINK',
      templateName: 'X',
      origin: 'MANUAL',
    }).valid).toBe(false);
  });
});

describe('validateActionConfig — SEND_GROUND', () => {
  it('валидный с templateName', () => {
    expect(validateActionConfig({
      actionType: 'SEND_GROUND',
      templateName: 'GROUND_MSG',
    }).valid).toBe(true);
  });

  it('с recipients — валидно', () => {
    expect(validateActionConfig({
      actionType: 'SEND_GROUND',
      templateName: 'GROUND_MSG',
      recipients: ['ops@airline.com'],
    }).valid).toBe(true);
  });

  it('без templateName → ошибка', () => {
    expect(validateActionConfig({ actionType: 'SEND_GROUND' }).valid).toBe(false);
  });
});

describe('validateActionConfig — WAIT_TIME', () => {
  it('валидный', () => {
    expect(validateActionConfig({
      actionType: 'WAIT_TIME',
      durationSeconds: 300,
    }).valid).toBe(true);
  });

  it('durationSeconds = 1 — минимум', () => {
    expect(validateActionConfig({ actionType: 'WAIT_TIME', durationSeconds: 1 }).valid).toBe(true);
  });

  it('durationSeconds = 0 → ошибка', () => {
    const r = validateActionConfig({ actionType: 'WAIT_TIME', durationSeconds: 0 });
    expect(r.valid).toBe(false);
    expect(r.errors.some(e => e.field === 'durationSeconds')).toBe(true);
  });

  it('durationSeconds отрицательный → ошибка', () => {
    expect(validateActionConfig({ actionType: 'WAIT_TIME', durationSeconds: -60 }).valid).toBe(false);
  });

  it('без durationSeconds → ошибка', () => {
    expect(validateActionConfig({ actionType: 'WAIT_TIME' }).valid).toBe(false);
  });
});

describe('validateActionConfig — общее', () => {
  it('null → ошибка', () => {
    expect(validateActionConfig(null).valid).toBe(false);
  });

  it('неверный actionType → ошибка', () => {
    expect(validateActionConfig({ actionType: 'DANCE' }).valid).toBe(false);
  });

  it('не объект → ошибка', () => {
    expect(validateActionConfig('RAISE_CONDITION').valid).toBe(false);
  });
});

// ── validateEvaluateConfig ────────────────────────────────────────────────────

describe('validateEvaluateConfig', () => {
  it('валидный критерий → успех', () => {
    expect(validateEvaluateConfig({
      type: 'FLIGHT_STAGE',
      operator: 'EQUALS',
      targetStage: 'OUT',
    }).valid).toBe(true);
  });

  it('невалидный критерий → ошибка', () => {
    expect(validateEvaluateConfig({ type: 'FLIGHT_STAGE', operator: 'EQUALS' }).valid).toBe(false);
  });

  it('COMPOUND → валидно', () => {
    expect(validateEvaluateConfig({
      type: 'COMPOUND',
      logic: 'AND',
      criteria: [
        { type: 'CONDITION_ACTIVE', conditionName: 'X' },
        { type: 'MESSAGE_RECEIVED', messageType: 'DOWNLINK' },
      ],
    }).valid).toBe(true);
  });

  it('null → ошибка', () => {
    expect(validateEvaluateConfig(null).valid).toBe(false);
  });
});

// ── validateWaitConfig ────────────────────────────────────────────────────────

describe('validateWaitConfig', () => {
  it('валидный минимум (только критерий)', () => {
    expect(validateWaitConfig({
      type: 'CONDITION_ACTIVE',
      conditionName: 'WEATHER',
    }).valid).toBe(true);
  });

  it('с timeoutSeconds → валидно', () => {
    expect(validateWaitConfig({
      type: 'CONDITION_ACTIVE',
      conditionName: 'WEATHER',
      timeoutSeconds: 300,
    }).valid).toBe(true);
  });

  it('timeoutSeconds = 0 → валидно (нет лимита)', () => {
    expect(validateWaitConfig({
      type: 'CONDITION_ACTIVE',
      conditionName: 'WEATHER',
      timeoutSeconds: 0,
    }).valid).toBe(true);
  });

  it('timeoutSeconds отрицательный → ошибка', () => {
    expect(validateWaitConfig({
      type: 'CONDITION_ACTIVE',
      conditionName: 'WEATHER',
      timeoutSeconds: -1,
    }).valid).toBe(false);
  });

  it('с fromThisPointOnly → игнорируется в валидации критерия', () => {
    expect(validateWaitConfig({
      type: 'FLIGHT_STAGE',
      operator: 'EQUALS',
      targetStage: 'OUT',
      fromThisPointOnly: true,
    }).valid).toBe(true);
  });

  it('невалидный критерий → ошибка', () => {
    expect(validateWaitConfig({ type: 'FLIGHT_STAGE', operator: 'EQUALS' }).valid).toBe(false);
  });

  it('null → ошибка', () => {
    expect(validateWaitConfig(null).valid).toBe(false);
  });
});

// ── validateStepConfigJson ────────────────────────────────────────────────────

describe('validateStepConfigJson', () => {
  it('ACTION валидный JSON', () => {
    const json = JSON.stringify({ actionType: 'WAIT_TIME', durationSeconds: 60 });
    expect(validateStepConfigJson('ACTION', json).valid).toBe(true);
  });

  it('EVALUATE валидный JSON', () => {
    const json = JSON.stringify({ type: 'CONDITION_ACTIVE', conditionName: 'X' });
    expect(validateStepConfigJson('EVALUATE', json).valid).toBe(true);
  });

  it('WAIT валидный JSON', () => {
    const json = JSON.stringify({ type: 'CONDITION_ACTIVE', conditionName: 'X', timeoutSeconds: 120 });
    expect(validateStepConfigJson('WAIT', json).valid).toBe(true);
  });

  it('невалидный JSON → ошибка парсинга', () => {
    expect(validateStepConfigJson('ACTION', '{bad}').valid).toBe(false);
  });

  it('неизвестный stepType → ошибка', () => {
    expect(validateStepConfigJson('LOOP', '{}').valid).toBe(false);
  });
});

// ── validateDecisions ─────────────────────────────────────────────────────────

describe('validateDecisions', () => {
  const steps = [
    { orderIndex: 1 },
    { orderIndex: 2 },
    { orderIndex: 3 },
  ];

  it('CONTINUE/CONTINUE — всегда валидно', () => {
    expect(validateDecisions('CONTINUE', null, 'CONTINUE', null, steps).valid).toBe(true);
  });

  it('END/ABORT — валидно', () => {
    expect(validateDecisions('END', null, 'ABORT', null, steps).valid).toBe(true);
  });

  it('GOTO к существующему шагу — валидно', () => {
    expect(validateDecisions('GOTO', 2, 'CONTINUE', null, steps).valid).toBe(true);
  });

  it('GOTO к несуществующему шагу → ошибка', () => {
    const r = validateDecisions('GOTO', 99, 'CONTINUE', null, steps);
    expect(r.valid).toBe(false);
    expect(r.errors.some(e => e.messageKey === 'errGotoInvalid')).toBe(true);
  });

  it('GOTO без gotoStep → ошибка', () => {
    const r = validateDecisions('GOTO', null, 'CONTINUE', null, steps);
    expect(r.valid).toBe(false);
    expect(r.errors.some(e => e.messageKey === 'errGotoMissing')).toBe(true);
  });

  it('оба GOTO к разным валидным шагам — валидно', () => {
    expect(validateDecisions('GOTO', 1, 'GOTO', 3, steps).valid).toBe(true);
  });

  it('failure GOTO к несуществующему → ошибка', () => {
    const r = validateDecisions('CONTINUE', null, 'GOTO', 10, steps);
    expect(r.valid).toBe(false);
    expect(r.errors.some(e => e.field === 'onFailureGotoStep')).toBe(true);
  });

  it('пустой список шагов + GOTO → ошибка (нет куда идти)', () => {
    const r = validateDecisions('GOTO', 1, 'CONTINUE', null, []);
    expect(r.valid).toBe(false);
  });

  it('GOTO на себя — разрешено (петля)', () => {
    // GOTO на себя технически допустимо (петля)
    expect(validateDecisions('GOTO', 2, 'CONTINUE', null, steps, 2).valid).toBe(true);
  });
});
