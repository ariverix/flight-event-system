/**
 * dlqApi — Dead Letter Queue (P2-6). Типы из OpenAPI-контракта.
 */
import api from './axiosConfig';
import type {
  ApiDeadLetterMessageResponse,
  ApiDeadLetterReprocessResponse,
  ApiPage,
} from './generated/schema';

type DlqStatus = 'NEW' | 'REPROCESSED' | 'DISCARDED';

export const dlqApi = {
  list: async (page = 0, size = 20, status?: DlqStatus): Promise<ApiPage<ApiDeadLetterMessageResponse>> => {
    const params: { page: number; size: number; status?: DlqStatus } = { page, size };
    if (status) params.status = status;
    const { data } = await api.get<ApiPage<ApiDeadLetterMessageResponse>>('/dlq', { params });
    return data;
  },

  getById: async (id: number): Promise<ApiDeadLetterMessageResponse> => {
    const { data } = await api.get<ApiDeadLetterMessageResponse>(`/dlq/${id}`);
    return data;
  },

  reprocess: async (id: number): Promise<ApiDeadLetterReprocessResponse> => {
    const { data } = await api.post<ApiDeadLetterReprocessResponse>(`/dlq/${id}/reprocess`);
    return data;
  },

  discard: async (id: number): Promise<void> => {
    await api.post(`/dlq/${id}/discard`);
  },
};
