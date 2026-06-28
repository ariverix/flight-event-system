/**
 * Доменные типы для конфигурации критериев и действий шагов.
 *
 * Эти типы описывают содержимое поля configJson в StepCreateRequest/StepResponse.
 * Они НЕ дублируют типы из OpenAPI (configJson — opaque string в схеме).
 *
 * Использование: CriteriaBuilder, StepFormV2, validation-утилиты.
 */

// ── Критерии (6 типов по спецификации SITA) ──────────────────────────────────

export type CriterionType =
  | 'MESSAGE_RECEIVED'
  | 'FLIGHT_STAGE'
  | 'POSITION_REPORTED'
  | 'TIME_COMPARISON'
  | 'CONDITION_ACTIVE'
  | 'COMPOUND';

export type MessageDirection = 'DOWNLINK' | 'UPLINK' | 'GROUND';

export type FlightStageOperator =
  | 'EQUALS'
  | 'NOT_EQUALS'
  | 'GREATER_THAN'
  | 'LESS_THAN'
  | 'GREATER_OR_EQUAL'
  | 'LESS_OR_EQUAL';

export type FlightStageValue = 'INIT' | 'OUT' | 'OFF' | 'ON' | 'IN' | 'SUMMARY';

export type PositionSource = 'ACARS' | 'RADAR' | 'ADS_B';

export type TimeOperator = 'BEFORE' | 'EQUAL' | 'AFTER';

export type TimeReference = 'ETD' | 'ETA' | 'INIT' | 'OUT' | 'OFF' | 'ON' | 'IN';

export type CompoundLogic = 'AND' | 'OR';

export interface MessageReceivedCriterion {
  type: 'MESSAGE_RECEIVED';
  messageType: MessageDirection;
  templateName?: string;
  fromThisPointOnly?: boolean;
}

export interface FlightStageCriterion {
  type: 'FLIGHT_STAGE';
  operator: FlightStageOperator;
  targetStage: FlightStageValue;
}

export interface PositionReportedCriterion {
  type: 'POSITION_REPORTED';
  reported: boolean;
  inLastMinutes?: number;
  sources?: PositionSource[];
}

export interface TimeComparisonCriterion {
  type: 'TIME_COMPARISON';
  operator: TimeOperator;
  referenceTime: TimeReference;
  offsetMinutes?: number;
}

export interface ConditionActiveCriterion {
  type: 'CONDITION_ACTIVE';
  conditionName: string;
}

export interface CompoundCriterion {
  type: 'COMPOUND';
  logic: CompoundLogic;
  criteria: CriteriaNode[];
}

export type CriteriaNode =
  | MessageReceivedCriterion
  | FlightStageCriterion
  | PositionReportedCriterion
  | TimeComparisonCriterion
  | ConditionActiveCriterion
  | CompoundCriterion;

// ── Конфигурации ACTION-шагов ─────────────────────────────────────────────────

export type AlertLevel = 'NO' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type UplinkOrigin = 'COMPUTER_GENERATED' | 'EXTERNAL_USER';

export interface RaiseConditionConfig {
  actionType: 'RAISE_CONDITION';
  conditionName: string;
  alertLevel: AlertLevel;
}

export interface CloseConditionConfig {
  actionType: 'CLOSE_CONDITION';
  conditionName: string;
  alertLevel?: AlertLevel;
}

export interface SendUplinkConfig {
  actionType: 'SEND_UPLINK';
  templateName: string;
  origin: UplinkOrigin;
}

export interface SendGroundConfig {
  actionType: 'SEND_GROUND';
  templateName: string;
  recipients?: string[];
}

export interface WaitTimeConfig {
  actionType: 'WAIT_TIME';
  /** Длительность паузы в секундах (unit-конвертация делается в UI). */
  durationSeconds: number;
}

export type ActionConfig =
  | RaiseConditionConfig
  | CloseConditionConfig
  | SendUplinkConfig
  | SendGroundConfig
  | WaitTimeConfig;

// ── Конфигурация WAIT-шага ────────────────────────────────────────────────────

/**
 * configJson для шага WAIT содержит поля критерия + дополнительные поля WAIT.
 * Совместимо с форматом, который читает backend CriterionEvaluator.
 */
export interface WaitStepExtra {
  timeoutSeconds?: number;
  fromThisPointOnly?: boolean;
}
