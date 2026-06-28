/**
 * Чистые функции валидации критериев (CriteriaNode).
 *
 * Без побочных эффектов, без i18n (возвращают ключи ошибок).
 * Покрыты unit-тестами: utils/__tests__/criteriaValidation.test.ts
 */

import type {
  CriteriaNode,
  CriterionType,
  FlightStageOperator,
  FlightStageValue,
  MessageDirection,
  PositionSource,
  TimeOperator,
  TimeReference,
  CompoundLogic,
} from '../types/criteria';

// ── ValidationResult ──────────────────────────────────────────────────────────

export interface ValidationError {
  field: string;
  /** Ключ из словаря i18n (d.validationErrors[key]) */
  messageKey: string;
}

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
}

function ok(): ValidationResult {
  return { valid: true, errors: [] };
}

function fail(errors: ValidationError[]): ValidationResult {
  return { valid: false, errors };
}

function err(field: string, messageKey: string): ValidationError {
  return { field, messageKey };
}

// ── Допустимые значения ───────────────────────────────────────────────────────

const CRITERION_TYPES: CriterionType[] = [
  'MESSAGE_RECEIVED',
  'FLIGHT_STAGE',
  'POSITION_REPORTED',
  'TIME_COMPARISON',
  'CONDITION_ACTIVE',
  'COMPOUND',
];

const MESSAGE_DIRECTIONS: MessageDirection[] = ['DOWNLINK', 'UPLINK', 'GROUND'];

const FLIGHT_STAGE_OPERATORS: FlightStageOperator[] = [
  'EQUALS', 'NOT_EQUALS', 'GREATER_THAN', 'LESS_THAN', 'GREATER_OR_EQUAL', 'LESS_OR_EQUAL',
];

const FLIGHT_STAGE_VALUES: FlightStageValue[] = ['INIT', 'OUT', 'OFF', 'ON', 'IN', 'SUMMARY'];

const POSITION_SOURCES: PositionSource[] = ['ACARS', 'RADAR', 'ADS_B'];

const TIME_OPERATORS: TimeOperator[] = ['BEFORE', 'EQUAL', 'AFTER'];

const TIME_REFERENCES: TimeReference[] = ['ETD', 'ETA', 'INIT', 'OUT', 'OFF', 'ON', 'IN'];

const COMPOUND_LOGICS: CompoundLogic[] = ['AND', 'OR'];

// ── Хелпер: проверка строки ───────────────────────────────────────────────────

function isNonEmptyString(v: unknown): v is string {
  return typeof v === 'string' && v.trim().length > 0;
}

// ── Валидаторы по типу ────────────────────────────────────────────────────────

function validateMessageReceived(c: Record<string, unknown>): ValidationResult {
  const errors: ValidationError[] = [];

  if (!MESSAGE_DIRECTIONS.includes(c.messageType as MessageDirection)) {
    errors.push(err('messageType', 'errMessageDirection'));
  }
  // templateName — необязательное, но если задано — должно быть строкой
  if (c.templateName !== undefined && typeof c.templateName !== 'string') {
    errors.push(err('templateName', 'errTemplate'));
  }

  return errors.length === 0 ? ok() : fail(errors);
}

function validateFlightStage(c: Record<string, unknown>): ValidationResult {
  const errors: ValidationError[] = [];

  if (!FLIGHT_STAGE_OPERATORS.includes(c.operator as FlightStageOperator)) {
    errors.push(err('operator', 'errFlightOperator'));
  }
  if (!FLIGHT_STAGE_VALUES.includes(c.targetStage as FlightStageValue)) {
    errors.push(err('targetStage', 'errFlightStage'));
  }

  return errors.length === 0 ? ok() : fail(errors);
}

function validatePositionReported(c: Record<string, unknown>): ValidationResult {
  const errors: ValidationError[] = [];

  if (typeof c.reported !== 'boolean') {
    errors.push(err('reported', 'errPositionStatus'));
  }
  if (c.inLastMinutes !== undefined) {
    if (typeof c.inLastMinutes !== 'number' || c.inLastMinutes <= 0) {
      errors.push(err('inLastMinutes', 'errInLastMinutes'));
    }
  }
  if (c.sources !== undefined) {
    if (!Array.isArray(c.sources) || c.sources.some(s => !POSITION_SOURCES.includes(s as PositionSource))) {
      errors.push(err('sources', 'errPositionSources'));
    }
  }

  return errors.length === 0 ? ok() : fail(errors);
}

function validateTimeComparison(c: Record<string, unknown>): ValidationResult {
  const errors: ValidationError[] = [];

  if (!TIME_OPERATORS.includes(c.operator as TimeOperator)) {
    errors.push(err('operator', 'errTimeOperator'));
  }
  if (!TIME_REFERENCES.includes(c.referenceTime as TimeReference)) {
    errors.push(err('referenceTime', 'errTimeReference'));
  }
  if (c.offsetMinutes !== undefined && typeof c.offsetMinutes !== 'number') {
    errors.push(err('offsetMinutes', 'errOffsetMinutes'));
  }

  return errors.length === 0 ? ok() : fail(errors);
}

function validateConditionActive(c: Record<string, unknown>): ValidationResult {
  if (!isNonEmptyString(c.conditionName)) {
    return fail([err('conditionName', 'errConditionName')]);
  }
  return ok();
}

function validateCompound(c: Record<string, unknown>): ValidationResult {
  const errors: ValidationError[] = [];

  if (!COMPOUND_LOGICS.includes(c.logic as CompoundLogic)) {
    errors.push(err('logic', 'errLogic'));
  }
  if (!Array.isArray(c.criteria) || c.criteria.length === 0) {
    errors.push(err('criteria', 'errEmptyGroup'));
    return fail(errors);
  }

  // Рекурсивная проверка вложенных критериев
  c.criteria.forEach((child: unknown, i: number) => {
    const childResult = validateCriteriaNode(child);
    if (!childResult.valid) {
      childResult.errors.forEach(e => {
        errors.push(err(`criteria[${i}].${e.field}`, e.messageKey));
      });
    }
  });

  return errors.length === 0 ? ok() : fail(errors);
}

// ── Главная функция ───────────────────────────────────────────────────────────

/**
 * Рекурсивная валидация CriteriaNode.
 *
 * @param node - любое значение (проверяем с нуля)
 */
export function validateCriteriaNode(node: unknown): ValidationResult {
  if (!node || typeof node !== 'object') {
    return fail([err('type', 'errCriterionType')]);
  }
  const c = node as Record<string, unknown>;

  if (!CRITERION_TYPES.includes(c.type as CriterionType)) {
    return fail([err('type', 'errCriterionType')]);
  }

  switch (c.type) {
    case 'MESSAGE_RECEIVED':  return validateMessageReceived(c);
    case 'FLIGHT_STAGE':      return validateFlightStage(c);
    case 'POSITION_REPORTED': return validatePositionReported(c);
    case 'TIME_COMPARISON':   return validateTimeComparison(c);
    case 'CONDITION_ACTIVE':  return validateConditionActive(c);
    case 'COMPOUND':          return validateCompound(c);
    default:                  return fail([err('type', 'errCriterionType')]);
  }
}

/**
 * Валидация CriteriaNode из JSON-строки.
 * Возвращает false, если строка пустая (критерий не задан — допустимо).
 */
export function validateCriteriaJson(json: string | null | undefined): ValidationResult {
  if (!json?.trim()) return ok(); // пустой критерий допустим (не задан)
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    return fail([err('json', 'errInvalidJson')]);
  }
  return validateCriteriaNode(parsed);
}

/**
 * Является ли CriteriaNode полностью валидным (type-guard).
 */
export function isCriteriaNodeValid(node: unknown): node is CriteriaNode {
  return validateCriteriaNode(node).valid;
}
