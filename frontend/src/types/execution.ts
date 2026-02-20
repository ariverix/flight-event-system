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
  executedAt: string;
  completedAt: string | null;
  detailsJson: string | null;
}
