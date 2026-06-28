/**
 * Unit-тесты валидации критериев.
 *
 * Покрываем:
 *   1. validateCriteriaNode    — все 6 типов критериев
 *   2. validateCriteriaJson    — JSON-обёртка
 *   3. Рекурсивная валидация COMPOUND
 *   4. Граничные случаи (null, пустой объект, неизвестный тип)
 */

import { describe, it, expect } from 'vitest';
import {
  validateCriteriaNode,
  validateCriteriaJson,
  isCriteriaNodeValid,
} from '../criteriaValidation';

// ── Вспомогательные хелперы ───────────────────────────────────────────────────

function expectValid(node: unknown) {
  const result = validateCriteriaNode(node);
  expect(result.valid).toBe(true);
  expect(result.errors).toHaveLength(0);
}

function expectInvalid(node: unknown, field?: string, key?: string) {
  const result = validateCriteriaNode(node);
  expect(result.valid).toBe(false);
  if (field) {
    expect(result.errors.some(e => e.field === field)).toBe(true);
  }
  if (key) {
    expect(result.errors.some(e => e.messageKey === key)).toBe(true);
  }
}

// ── null / undefined / primitive ─────────────────────────────────────────────

describe('граничные случаи', () => {
  it('null → невалидно', () => expectInvalid(null));
  it('undefined → невалидно', () => expectInvalid(undefined));
  it('строка → невалидно', () => expectInvalid('FLIGHT_STAGE'));
  it('число → невалидно', () => expectInvalid(42));
  it('пустой объект → ошибка type', () => expectInvalid({}, 'type', 'errCriterionType'));
  it('неизвестный тип → ошибка type', () => expectInvalid({ type: 'UNKNOWN' }, 'type', 'errCriterionType'));
});

// ── MESSAGE_RECEIVED ──────────────────────────────────────────────────────────

describe('MESSAGE_RECEIVED', () => {
  it('валидный минимум', () => {
    expectValid({ type: 'MESSAGE_RECEIVED', messageType: 'DOWNLINK' });
  });

  it('все три направления валидны', () => {
    expectValid({ type: 'MESSAGE_RECEIVED', messageType: 'UPLINK' });
    expectValid({ type: 'MESSAGE_RECEIVED', messageType: 'GROUND' });
  });

  it('с templateName', () => {
    expectValid({ type: 'MESSAGE_RECEIVED', messageType: 'DOWNLINK', templateName: 'WEATHER_UPDATE' });
  });

  it('с fromThisPointOnly', () => {
    expectValid({
      type: 'MESSAGE_RECEIVED',
      messageType: 'DOWNLINK',
      templateName: 'FOO',
      fromThisPointOnly: true,
    });
  });

  it('без messageType → ошибка', () => {
    expectInvalid({ type: 'MESSAGE_RECEIVED' }, 'messageType', 'errMessageDirection');
  });

  it('неверное направление → ошибка', () => {
    expectInvalid({ type: 'MESSAGE_RECEIVED', messageType: 'INVALID' }, 'messageType', 'errMessageDirection');
  });

  it('templateName не строка → ошибка', () => {
    expectInvalid({ type: 'MESSAGE_RECEIVED', messageType: 'DOWNLINK', templateName: 123 }, 'templateName');
  });
});

// ── FLIGHT_STAGE ──────────────────────────────────────────────────────────────

describe('FLIGHT_STAGE', () => {
  const allOperators = ['EQUALS', 'NOT_EQUALS', 'GREATER_THAN', 'LESS_THAN', 'GREATER_OR_EQUAL', 'LESS_OR_EQUAL'];
  const allStages = ['INIT', 'OUT', 'OFF', 'ON', 'IN', 'SUMMARY'];

  allOperators.forEach(op => {
    it(`оператор ${op} валиден`, () => {
      expectValid({ type: 'FLIGHT_STAGE', operator: op, targetStage: 'OUT' });
    });
  });

  allStages.forEach(stage => {
    it(`стадия ${stage} валидна`, () => {
      expectValid({ type: 'FLIGHT_STAGE', operator: 'EQUALS', targetStage: stage });
    });
  });

  it('без operator → ошибка', () => {
    expectInvalid({ type: 'FLIGHT_STAGE', targetStage: 'OUT' }, 'operator', 'errFlightOperator');
  });

  it('без targetStage → ошибка', () => {
    expectInvalid({ type: 'FLIGHT_STAGE', operator: 'EQUALS' }, 'targetStage', 'errFlightStage');
  });

  it('неверный оператор → ошибка', () => {
    expectInvalid({ type: 'FLIGHT_STAGE', operator: 'LIKE', targetStage: 'OUT' }, 'operator');
  });

  it('неверная стадия → ошибка', () => {
    expectInvalid({ type: 'FLIGHT_STAGE', operator: 'EQUALS', targetStage: 'LANDED' }, 'targetStage');
  });
});

// ── POSITION_REPORTED ─────────────────────────────────────────────────────────

describe('POSITION_REPORTED', () => {
  it('валидный минимум (reported=true)', () => {
    expectValid({ type: 'POSITION_REPORTED', reported: true });
  });

  it('reported=false — валидно', () => {
    expectValid({ type: 'POSITION_REPORTED', reported: false });
  });

  it('с inLastMinutes', () => {
    expectValid({ type: 'POSITION_REPORTED', reported: true, inLastMinutes: 30 });
  });

  it('с sources', () => {
    expectValid({ type: 'POSITION_REPORTED', reported: true, sources: ['ACARS', 'RADAR'] });
  });

  it('все допустимые источники', () => {
    expectValid({ type: 'POSITION_REPORTED', reported: true, sources: ['ACARS', 'RADAR', 'ADS_B'] });
  });

  it('без reported → ошибка', () => {
    expectInvalid({ type: 'POSITION_REPORTED' }, 'reported', 'errPositionStatus');
  });

  it('inLastMinutes = 0 → ошибка (должно быть > 0)', () => {
    expectInvalid({ type: 'POSITION_REPORTED', reported: true, inLastMinutes: 0 }, 'inLastMinutes');
  });

  it('inLastMinutes отрицательный → ошибка', () => {
    expectInvalid({ type: 'POSITION_REPORTED', reported: true, inLastMinutes: -5 }, 'inLastMinutes');
  });

  it('неверный источник → ошибка', () => {
    expectInvalid({ type: 'POSITION_REPORTED', reported: true, sources: ['WIFI'] }, 'sources');
  });
});

// ── TIME_COMPARISON ───────────────────────────────────────────────────────────

describe('TIME_COMPARISON', () => {
  const allOps = ['BEFORE', 'EQUAL', 'AFTER'];
  const allRefs = ['ETD', 'ETA', 'INIT', 'OUT', 'OFF', 'ON', 'IN'];

  allOps.forEach(op => {
    it(`оператор ${op} валиден`, () => {
      expectValid({ type: 'TIME_COMPARISON', operator: op, referenceTime: 'ETD' });
    });
  });

  allRefs.forEach(ref => {
    it(`referenceTime ${ref} валидно`, () => {
      expectValid({ type: 'TIME_COMPARISON', operator: 'BEFORE', referenceTime: ref });
    });
  });

  it('с offsetMinutes', () => {
    expectValid({ type: 'TIME_COMPARISON', operator: 'AFTER', referenceTime: 'ETA', offsetMinutes: 30 });
  });

  it('offsetMinutes может быть отрицательным (до события)', () => {
    expectValid({ type: 'TIME_COMPARISON', operator: 'BEFORE', referenceTime: 'ETD', offsetMinutes: -15 });
  });

  it('без operator → ошибка', () => {
    expectInvalid({ type: 'TIME_COMPARISON', referenceTime: 'ETD' }, 'operator', 'errTimeOperator');
  });

  it('без referenceTime → ошибка', () => {
    expectInvalid({ type: 'TIME_COMPARISON', operator: 'BEFORE' }, 'referenceTime', 'errTimeReference');
  });

  it('неверный оператор → ошибка', () => {
    expectInvalid({ type: 'TIME_COMPARISON', operator: 'LESS_THAN', referenceTime: 'ETD' }, 'operator');
  });

  it('неверное referenceTime → ошибка', () => {
    expectInvalid({ type: 'TIME_COMPARISON', operator: 'BEFORE', referenceTime: 'DEPARTURE' }, 'referenceTime');
  });
});

// ── CONDITION_ACTIVE ──────────────────────────────────────────────────────────

describe('CONDITION_ACTIVE', () => {
  it('валидный', () => {
    expectValid({ type: 'CONDITION_ACTIVE', conditionName: 'WEATHER_ALERT' });
  });

  it('без conditionName → ошибка', () => {
    expectInvalid({ type: 'CONDITION_ACTIVE' }, 'conditionName', 'errConditionName');
  });

  it('пустой conditionName → ошибка', () => {
    expectInvalid({ type: 'CONDITION_ACTIVE', conditionName: '' }, 'conditionName');
  });

  it('пробельный conditionName → ошибка', () => {
    expectInvalid({ type: 'CONDITION_ACTIVE', conditionName: '  ' }, 'conditionName');
  });
});

// ── COMPOUND ──────────────────────────────────────────────────────────────────

describe('COMPOUND', () => {
  const simpleStage = { type: 'FLIGHT_STAGE', operator: 'EQUALS', targetStage: 'OUT' };
  const simpleMsg = { type: 'MESSAGE_RECEIVED', messageType: 'DOWNLINK' };

  it('AND с двумя валидными критериями', () => {
    expectValid({
      type: 'COMPOUND',
      logic: 'AND',
      criteria: [simpleStage, simpleMsg],
    });
  });

  it('OR с двумя валидными критериями', () => {
    expectValid({
      type: 'COMPOUND',
      logic: 'OR',
      criteria: [simpleStage, simpleMsg],
    });
  });

  it('вложенный COMPOUND', () => {
    expectValid({
      type: 'COMPOUND',
      logic: 'AND',
      criteria: [
        simpleStage,
        {
          type: 'COMPOUND',
          logic: 'OR',
          criteria: [simpleMsg, { type: 'CONDITION_ACTIVE', conditionName: 'X' }],
        },
      ],
    });
  });

  it('без logic → ошибка', () => {
    expectInvalid({ type: 'COMPOUND', criteria: [simpleStage] }, 'logic', 'errLogic');
  });

  it('без criteria → ошибка (пустая группа)', () => {
    expectInvalid({ type: 'COMPOUND', logic: 'AND' }, 'criteria', 'errEmptyGroup');
  });

  it('пустой массив criteria → ошибка', () => {
    expectInvalid({ type: 'COMPOUND', logic: 'AND', criteria: [] }, 'criteria', 'errEmptyGroup');
  });

  it('невалидный вложенный критерий → ошибки пробрасываются', () => {
    const result = validateCriteriaNode({
      type: 'COMPOUND',
      logic: 'AND',
      criteria: [
        simpleStage,
        { type: 'FLIGHT_STAGE', operator: 'EQUALS' }, // нет targetStage
      ],
    });
    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.field.includes('criteria[1]'))).toBe(true);
  });

  it('неверный logic → ошибка', () => {
    expectInvalid({ type: 'COMPOUND', logic: 'XOR', criteria: [simpleStage] }, 'logic');
  });
});

// ── validateCriteriaJson ──────────────────────────────────────────────────────

describe('validateCriteriaJson', () => {
  it('null → валидно (критерий не задан)', () => {
    expect(validateCriteriaJson(null).valid).toBe(true);
  });

  it('пустая строка → валидно', () => {
    expect(validateCriteriaJson('').valid).toBe(true);
  });

  it('только пробелы → валидно', () => {
    expect(validateCriteriaJson('   ').valid).toBe(true);
  });

  it('валидный JSON → валидно', () => {
    const json = JSON.stringify({ type: 'FLIGHT_STAGE', operator: 'EQUALS', targetStage: 'OUT' });
    expect(validateCriteriaJson(json).valid).toBe(true);
  });

  it('невалидный JSON → ошибка парсинга', () => {
    const result = validateCriteriaJson('{invalid json}');
    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.messageKey === 'errInvalidJson')).toBe(true);
  });

  it('валидный JSON с ошибочным критерием → ошибка', () => {
    const json = JSON.stringify({ type: 'FLIGHT_STAGE', operator: 'EQUALS' }); // нет targetStage
    expect(validateCriteriaJson(json).valid).toBe(false);
  });
});

// ── isCriteriaNodeValid (type-guard) ─────────────────────────────────────────

describe('isCriteriaNodeValid', () => {
  it('валидный узел → true', () => {
    expect(isCriteriaNodeValid({ type: 'CONDITION_ACTIVE', conditionName: 'X' })).toBe(true);
  });

  it('невалидный узел → false', () => {
    expect(isCriteriaNodeValid({ type: 'CONDITION_ACTIVE' })).toBe(false);
  });

  it('null → false', () => {
    expect(isCriteriaNodeValid(null)).toBe(false);
  });
});
