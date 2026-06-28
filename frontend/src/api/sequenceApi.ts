import api from './axiosConfig';
import {
  SequenceResponse,
  SequenceCreateRequest,
  StepCreateRequest,
  StepResponse,
  PageResponse,
  SequenceStatus,
} from '../types/sequence';

export const sequenceApi = {
  getSequences: async (
    page: number = 0,
    size: number = 10,
    status?: SequenceStatus,
  ): Promise<PageResponse<SequenceResponse>> => {
    const params: { page: number; size: number; status?: SequenceStatus } = { page, size };
    if (status) params.status = status;
    const response = await api.get<PageResponse<SequenceResponse>>('/sequences', { params });
    return response.data;
  },

  getSequenceById: async (id: number): Promise<SequenceResponse> => {
    const response = await api.get<SequenceResponse>(`/sequences/${id}`);
    return response.data;
  },

  createSequence: async (request: SequenceCreateRequest): Promise<SequenceResponse> => {
    const response = await api.post<SequenceResponse>('/sequences', request);
    return response.data;
  },

  updateSequence: async (id: number, request: SequenceCreateRequest): Promise<SequenceResponse> => {
    const response = await api.put<SequenceResponse>(`/sequences/${id}`, request);
    return response.data;
  },

  deleteSequence: async (id: number): Promise<void> => {
    await api.delete(`/sequences/${id}`);
  },

  activateSequence: async (id: number): Promise<SequenceResponse> => {
    const response = await api.post<SequenceResponse>(`/sequences/${id}/activate`);
    return response.data;
  },

  deactivateSequence: async (id: number): Promise<SequenceResponse> => {
    const response = await api.post<SequenceResponse>(`/sequences/${id}/deactivate`);
    return response.data;
  },

  reorderSteps: async (sequenceId: number, stepIds: number[]): Promise<StepResponse[]> => {
    const response = await api.put<StepResponse[]>(`/sequences/${sequenceId}/steps/reorder`, stepIds);
    return response.data;
  },

  addStep: async (sequenceId: number, request: StepCreateRequest): Promise<StepResponse> => {
    const response = await api.post<StepResponse>(`/sequences/${sequenceId}/steps`, request);
    return response.data;
  },

  updateStep: async (
    sequenceId: number,
    stepId: number,
    request: StepCreateRequest,
  ): Promise<StepResponse> => {
    const response = await api.put<StepResponse>(`/sequences/${sequenceId}/steps/${stepId}`, request);
    return response.data;
  },

  deleteStep: async (sequenceId: number, stepId: number): Promise<void> => {
    await api.delete(`/sequences/${sequenceId}/steps/${stepId}`);
  },

  /**
   * Назначить последовательность в папку (PUT /api/v1/sequences/{id}/folder).
   * folderId === null — снять из папки.
   */
  assignFolder: async (sequenceId: number, folderId: number | null): Promise<void> => {
    await api.put(`/sequences/${sequenceId}/folder`, { folderId });
  },
};
