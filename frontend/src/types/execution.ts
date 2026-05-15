export type ExecutionStatus = 'WAITING' | 'RUNNING' | 'COMPLETED' | 'ABORTED';
export type StepResult = 'SUCCESS' | 'FAILURE';

export interface ExecutionInstanceResponse {
  id: number;
  sequenceId: number;
  sequenceName: string;
  aircraftId: string;
  flightNumber: string | null;
  status: ExecutionStatus;
  currentStepIndex: number | null;
  contextJson: string;
  startedAt: string;
  completedAt: string | null;
  stepExecutions: StepExecutionResponse[];
}

export interface StepExecutionResponse {
  id: number;
  stepIndex: number;
  stepType: string;
  result: StepResult | null;
  transitionAction: string | null;
  transitionTarget: number | null;
  executedAt: string;
  detailsJson: string | null;
}
