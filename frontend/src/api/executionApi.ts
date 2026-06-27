import api from './axiosConfig';
import { ExecutionInstanceResponse, ExecutionStatus } from '../types/execution';
import { PageResponse } from '../types/sequence';

export const executionApi = {
  getExecutions: async (
    page: number = 0,
    size: number = 10,
    status?: ExecutionStatus,
    aircraftId?: string,
    sequenceId?: number,
  ): Promise<PageResponse<ExecutionInstanceResponse>> => {
    const params: {
      page: number;
      size: number;
      status?: ExecutionStatus;
      aircraftId?: string;
      sequenceId?: number;
    } = { page, size };
    if (status)     params.status     = status;
    if (aircraftId) params.aircraftId = aircraftId;
    if (sequenceId) params.sequenceId = sequenceId;
    const response = await api.get<PageResponse<ExecutionInstanceResponse>>('/executions', { params });
    return response.data;
  },

  getExecutionById: async (id: number): Promise<ExecutionInstanceResponse> => {
    const response = await api.get<ExecutionInstanceResponse>(`/executions/${id}`);
    return response.data;
  },
};
