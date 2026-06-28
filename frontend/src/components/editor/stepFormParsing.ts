/**
 * Чистые функции разбора/сборки состояния формы шага.
 *
 * Вынесены из StepFormV2.tsx для возможности unit-тестирования без React.
 * Без побочных эффектов, без хуков, без Ant Design.
 */

import type { StepResponse } from '../../types/sequence';
import type { AlertLevel, UplinkOrigin } from '../../types/criteria';

// ── Локальные типы ─────────────────────────────────────────────────────────────

type StepType = 'ACTION' | 'EVALUATE' | 'WAIT';
type ActionType = 'RAISE_CONDITION' | 'CLOSE_CONDITION' | 'SEND_UPLINK' | 'SEND_GROUND' | 'WAIT_TIME';
type TransitionAction = 'CONTINUE' | 'GOTO' | 'END' | 'ABORT';

// ── Состояние формы ────────────────────────────────────────────────────────────

export interface FormState {
  stepType: StepType;
  // ACTION fields
  actionType: ActionType | '';
  conditionName: string;
  alertLevel: AlertLevel | '';
  templateName: string;
  origin: UplinkOrigin | '';
  recipients: string;          // comma-separated
  durationValue: number | null;
  durationUnit: 'SEC' | 'MIN' | 'HOUR';
  // EVALUATE / WAIT: criteria stored as JSON string
  criteriaJson: string;
  // WAIT extra:
  // timeoutSeconds — отдельная колонка БД (НЕ в configJson), передаётся на верхнем уровне запроса
  timeoutSeconds: number | null;
  fromThisPointOnly: boolean;
  // Transitions
  onSuccessAction: TransitionAction;
  onSuccessGotoStep: number | null;
  onSuccessNotify: boolean;
  onFailureAction: TransitionAction;
  onFailureGotoStep: number | null;
  onFailureNotify: boolean;
}

export const INITIAL_STATE: FormState = {
  stepType: 'ACTION',
  actionType: '',
  conditionName: '',
  alertLevel: '',
  templateName: '',
  origin: '',
  recipients: '',
  durationValue: null,
  durationUnit: 'SEC',
  criteriaJson: '',
  timeoutSeconds: null,
  fromThisPointOnly: false,
  onSuccessAction: 'CONTINUE',
  onSuccessGotoStep: null,
  onSuccessNotify: false,
  onFailureAction: 'ABORT',
  onFailureGotoStep: null,
  onFailureNotify: false,
};

// ── Парсинг существующего шага ─────────────────────────────────────────────────

/**
 * Преобразует StepResponse (с сервера) во FormState для редактора.
 *
 * ВАЖНО (Blocker 1): timeoutSeconds читается из step.timeoutSeconds (верхний уровень),
 * а не из configJson — бэкенд хранит его как отдельную колонку таблицы steps.
 */
export function parseInitialValues(step: StepResponse): FormState {
  const state: FormState = { ...INITIAL_STATE };
  state.stepType          = step.stepType as StepType;
  state.onSuccessAction   = step.onSuccessAction as TransitionAction;
  state.onSuccessGotoStep = step.onSuccessGotoStep ?? null;
  state.onSuccessNotify   = step.onSuccessNotify;
  state.onFailureAction   = step.onFailureAction as TransitionAction;
  state.onFailureGotoStep = step.onFailureGotoStep ?? null;
  state.onFailureNotify   = step.onFailureNotify;

  let cfg: Record<string, unknown> = {};
  try { cfg = JSON.parse(step.configJson) as Record<string, unknown>; } catch { /* ignore */ }

  if (step.stepType === 'ACTION') {
    state.actionType = (cfg.actionType as ActionType) ?? '';
    switch (state.actionType) {
      case 'RAISE_CONDITION':
      case 'CLOSE_CONDITION':
        state.conditionName = String(cfg.conditionName ?? '');
        state.alertLevel    = (cfg.alertLevel as AlertLevel) ?? '';
        break;
      case 'SEND_UPLINK':
        state.templateName = String(cfg.templateName ?? '');
        state.origin       = (cfg.origin as UplinkOrigin) ?? '';
        break;
      case 'SEND_GROUND':
        state.templateName = String(cfg.templateName ?? '');
        state.recipients   = Array.isArray(cfg.recipients) ? (cfg.recipients as string[]).join(', ') : '';
        break;
      case 'WAIT_TIME': {
        const durSec = typeof cfg.durationSeconds === 'number' ? cfg.durationSeconds : null;
        if (durSec !== null) {
          if (durSec % 3600 === 0) { state.durationValue = durSec / 3600; state.durationUnit = 'HOUR'; }
          else if (durSec % 60 === 0) { state.durationValue = durSec / 60; state.durationUnit = 'MIN'; }
          else { state.durationValue = durSec; state.durationUnit = 'SEC'; }
        }
        break;
      }
    }
  } else {
    // EVALUATE / WAIT
    // timeoutSeconds — отдельная колонка БД, читаем из step.timeoutSeconds (не из cfg)
    // fromThisPointOnly остаётся в configJson
    const criterionPart: Record<string, unknown> = { ...cfg };
    delete criterionPart['timeoutSeconds'];     // стрип на случай устаревших данных
    delete criterionPart['fromThisPointOnly'];
    state.criteriaJson      = JSON.stringify(criterionPart);
    state.timeoutSeconds    = step.timeoutSeconds ?? null;
    state.fromThisPointOnly = !!cfg['fromThisPointOnly'];
  }
  return state;
}

// ── Построение configJson ──────────────────────────────────────────────────────

/**
 * Собирает configJson из FormState.
 *
 * ВАЖНО (Blocker 1): для WAIT-шага timeoutSeconds НЕ включается в configJson —
 * он передаётся на верхнем уровне StepCreateRequest.
 */
export function buildConfigJson(state: FormState): string {
  if (state.stepType === 'ACTION') {
    const cfg: Record<string, unknown> = { actionType: state.actionType };
    switch (state.actionType) {
      case 'RAISE_CONDITION':
      case 'CLOSE_CONDITION':
        cfg.conditionName = state.conditionName.trim();
        if (state.alertLevel) cfg.alertLevel = state.alertLevel;
        break;
      case 'SEND_UPLINK':
        cfg.templateName = state.templateName.trim();
        if (state.origin) cfg.origin = state.origin;
        break;
      case 'SEND_GROUND':
        cfg.templateName = state.templateName.trim();
        if (state.recipients.trim()) {
          cfg.recipients = state.recipients.split(',').map(s => s.trim()).filter(Boolean);
        }
        break;
      case 'WAIT_TIME': {
        const mult = state.durationUnit === 'HOUR' ? 3600 : state.durationUnit === 'MIN' ? 60 : 1;
        cfg.durationSeconds = (state.durationValue ?? 0) * mult;
        break;
      }
    }
    return JSON.stringify(cfg);
  }

  // EVALUATE / WAIT — критерий
  // timeoutSeconds НЕ входит в configJson (отдельная колонка БД, передаётся в StepCreateRequest)
  let criterion: Record<string, unknown> = {};
  try { criterion = JSON.parse(state.criteriaJson || '{}') as Record<string, unknown>; } catch { /* ignore */ }

  if (state.stepType === 'WAIT' && state.fromThisPointOnly) {
    criterion.fromThisPointOnly = true;
  }

  return JSON.stringify(criterion);
}
