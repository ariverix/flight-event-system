/**
 * Чистые функции валидации конфигурации шагов (configJson и решений).
 *
 * Без побочных эффектов, без i18n (возвращают ключи ошибок).
 * Покрыты unit-тестами: utils/__tests__/stepValidation.test.ts
 */

import type { AlertLevel, UplinkOrigin } from '../types/criteria';
import { validateCriteriaNode } from './criteriaValidation';
import type { ValidationError, ValidationResult } from './criteriaValidation';

// ── Re-export для удобного импорта ────────────────────────────────────────────

export type { ValidationError, ValidationResult };

// ── Вспомогательные ───────────────────────────────────────────────────────────

function ok(): ValidationResult { return { valid: true, errors: [] }; }
function fail(errors: ValidationError[]): ValidationResult { return { valid: false, errors }; }
function err(field: string, messageKey: string): ValidationError { return { field, messageKey }; }

function isNonEmptyString(v: unknown): v is string {
  return typeof v === 'string' && v.trim().length > 0;
}

// ── Допустимые значения ───────────────────────────────────────────────────────

const ALERT_LEVELS: AlertLevel[] = ['NO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const UPLINK_ORIGINS: UplinkOrigin[] = ['COMPUTER_GENERATED', 'EXTERNAL_USER'];
const ACTION_TYPES = ['RAISE_CONDITION', 'CLOSE_CONDITION', 'SEND_UPLINK', 'SEND_GROUND', 'WAIT_TIME'] as const;
const TRANSITION_ACTIONS = ['CONTINUE', 'GOTO', 'END', 'ABORT'] as const;

// ── Валидация ACTION-конфигурации ─────────────────────────────────────────────

/**
 * Валидация configJson для шага ACTION.
 *
 * @param config - распарсенный configJson объект
 */
export function validateActionConfig(config: unknown): ValidationResult {
  if (!config || typeof config !== 'object') {
    return fail([err('actionType', 'errActionType')]);
  }
  const c = config as Record<string, unknown>;
  const errors: ValidationError[] = [];

  const actionType = c.actionType as string;
  if (!ACTION_TYPES.includes(actionType as typeof ACTION_TYPES[number])) {
    errors.push(err('actionType', 'errActionType'));
    return fail(errors);
  }

  switch (actionType) {
    case 'RAISE_CONDITION':
      if (!isNonEmptyString(c.conditionName)) {
        errors.push(err('conditionName', 'errConditionName'));
      }
      if (!ALERT_LEVELS.includes(c.alertLevel as AlertLevel)) {
        errors.push(err('alertLevel', 'errAlertLevel'));
      }
      break;

    case 'CLOSE_CONDITION':
      if (!isNonEmptyString(c.conditionName)) {
        errors.push(err('conditionName', 'errConditionName'));
      }
      // alertLevel — необязательный для CLOSE
      break;

    case 'SEND_UPLINK':
      if (!isNonEmptyString(c.templateName)) {
        errors.push(err('templateName', 'errTemplate'));
      }
      if (!UPLINK_ORIGINS.includes(c.origin as UplinkOrigin)) {
        errors.push(err('origin', 'errOrigin'));
      }
      break;

    case 'SEND_GROUND':
      if (!isNonEmptyString(c.templateName)) {
        errors.push(err('templateName', 'errTemplate'));
      }
      // recipients — необязательные
      break;

    case 'WAIT_TIME':
      if (typeof c.durationSeconds !== 'number' || c.durationSeconds <= 0) {
        errors.push(err('durationSeconds', 'errDuration'));
      }
      break;
  }

  return errors.length === 0 ? ok() : fail(errors);
}

// ── Валидация EVALUATE-конфигурации ──────────────────────────────────────────

/**
 * Для EVALUATE шага configJson — это сам критерий (CriteriaNode).
 */
export function validateEvaluateConfig(config: unknown): ValidationResult {
  if (!config || typeof config !== 'object') {
    return fail([err('criterion', 'errCriterionType')]);
  }
  return validateCriteriaNode(config);
}

// ── Валидация WAIT-конфигурации ───────────────────────────────────────────────

/**
 * Для WAIT шага configJson содержит поля критерия + timeoutSeconds.
 * Критерий читается из того же объекта (поля mixed).
 */
export function validateWaitConfig(config: unknown): ValidationResult {
  if (!config || typeof config !== 'object') {
    return fail([err('criterion', 'errCriterionType')]);
  }
  const c = config as Record<string, unknown>;

  // Извлекаем "чистый" критерий для валидации (без WAIT-специфичных полей)
  const criterionFields: Record<string, unknown> = { ...c };
  delete criterionFields.timeoutSeconds;
  delete criterionFields.fromThisPointOnly;

  const criterionResult = validateCriteriaNode(criterionFields);
  if (!criterionResult.valid) return criterionResult;

  // timeoutSeconds необязателен, но если задан — должен быть >= 0
  if (c.timeoutSeconds !== undefined && (typeof c.timeoutSeconds !== 'number' || c.timeoutSeconds < 0)) {
    return fail([err('timeoutSeconds', 'errTimeoutSeconds')]);
  }

  return ok();
}

// ── Универсальный валидатор шага ──────────────────────────────────────────────

/**
 * Валидация configJson для любого типа шага.
 *
 * @param stepType - 'ACTION' | 'EVALUATE' | 'WAIT'
 * @param configJsonString - строка configJson
 */
export function validateStepConfigJson(stepType: string, configJsonString: string): ValidationResult {
  let config: unknown;
  try {
    config = JSON.parse(configJsonString);
  } catch {
    return fail([err('configJson', 'errInvalidJson')]);
  }

  switch (stepType) {
    case 'ACTION':   return validateActionConfig(config);
    case 'EVALUATE': return validateEvaluateConfig(config);
    case 'WAIT':     return validateWaitConfig(config);
    default:         return fail([err('stepType', 'errStepType')]);
  }
}

// ── Валидация решений (transitions) ──────────────────────────────────────────

export interface AvailableStep {
  orderIndex: number;
}

/**
 * Валидация решений (onSuccess/onFailure).
 *
 * @param onSuccessAction    - действие при успехе
 * @param onSuccessGotoStep  - целевой шаг (orderIndex) для GOTO при успехе
 * @param onFailureAction    - действие при провале
 * @param onFailureGotoStep  - целевой шаг (orderIndex) для GOTO при провале
 * @param availableSteps     - доступные шаги (для проверки GOTO-ссылок)
 * @param currentOrderIndex  - orderIndex текущего шага (для запрета GOTO на себя)
 */
export function validateDecisions(
  onSuccessAction: string,
  onSuccessGotoStep: number | null | undefined,
  onFailureAction: string,
  onFailureGotoStep: number | null | undefined,
  availableSteps: AvailableStep[],
  currentOrderIndex?: number,
): ValidationResult {
  const errors: ValidationError[] = [];
  const validOrderIndexes = new Set(availableSteps.map(s => s.orderIndex));

  function checkGoto(
    action: string,
    gotoStep: number | null | undefined,
    prefix: string,
  ) {
    if (!TRANSITION_ACTIONS.includes(action as typeof TRANSITION_ACTIONS[number])) {
      errors.push(err(`${prefix}Action`, 'errTransitionAction'));
      return;
    }
    if (action === 'GOTO') {
      if (gotoStep === null || gotoStep === undefined) {
        errors.push(err(`${prefix}GotoStep`, 'errGotoMissing'));
      } else if (!validOrderIndexes.has(gotoStep)) {
        errors.push(err(`${prefix}GotoStep`, 'errGotoInvalid'));
      } else if (currentOrderIndex !== undefined && gotoStep === currentOrderIndex) {
        // GOTO на себя — предупреждение, не ошибка (разрешено как петля)
      }
    }
  }

  checkGoto(onSuccessAction, onSuccessGotoStep, 'onSuccess');
  checkGoto(onFailureAction, onFailureGotoStep, 'onFailure');

  return errors.length === 0 ? ok() : fail(errors);
}
