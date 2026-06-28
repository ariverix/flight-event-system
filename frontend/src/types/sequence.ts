export type SequenceStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE';
export type StepType = 'ACTION' | 'EVALUATE' | 'WAIT';
export type ActionType = 'SEND_UPLINK' | 'SEND_GROUND' | 'RAISE_CONDITION' | 'CLOSE_CONDITION' | 'WAIT_TIME';
export type CriterionType = 'MESSAGE_RECEIVED' | 'FLIGHT_STAGE' | 'POSITION_REPORTED' | 'TIME_COMPARISON' | 'CONDITION_ACTIVE' | 'COMPOUND';
export type TransitionAction = 'CONTINUE' | 'GOTO' | 'END' | 'ABORT';
export type ComparisonOperator = 'EQUALS' | 'NOT_EQUALS' | 'GREATER_THAN' | 'LESS_THAN' | 'GREATER_OR_EQUAL' | 'LESS_OR_EQUAL';
export type FlightStage = 'INIT' | 'OUT' | 'OFF' | 'ON' | 'IN';
export type MessageType = 'DOWNLINK' | 'UPLINK' | 'GROUND';

export interface SequenceResponse {
  id: number;
  name: string;
  description: string;
  status: SequenceStatus;
  startCriteriaJson: string | null;
  stopCriteriaJson: string | null;
  createdAt: string;
  updatedAt: string;
  steps: StepResponse[];
}

export interface StepResponse {
  id: number;
  stepType: StepType;
  orderIndex: number;
  configJson: string;
  /** Тайм-аут в секундах для WAIT-шагов (отдельная колонка БД, НЕ в configJson). */
  timeoutSeconds?: number;
  onSuccessAction: TransitionAction;
  onSuccessGotoStep: number | null;
  onSuccessNotify: boolean;
  onFailureAction: TransitionAction;
  onFailureGotoStep: number | null;
  onFailureNotify: boolean;
}

export interface SequenceCreateRequest {
  name: string;
  description: string;
  startCriteriaJson?: string;
  stopCriteriaJson?: string;
}

export interface StepCreateRequest {
  stepType: StepType;
  configJson: string;
  /** Тайм-аут в секундах для WAIT-шагов (соответствует полю OpenAPI StepCreateRequest). */
  timeoutSeconds?: number;
  onSuccessAction: TransitionAction;
  onSuccessGotoStep?: number;
  onSuccessNotify: boolean;
  onFailureAction: TransitionAction;
  onFailureGotoStep?: number;
  onFailureNotify: boolean;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}
