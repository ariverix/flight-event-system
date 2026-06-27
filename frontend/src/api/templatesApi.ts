/**
 * templatesApi — ОБРАЗЕЦ использования сгенерированных типов OpenAPI.
 *
 * Этот модуль намеренно написан на сгенерированных типах из schema.ts,
 * а не на ручных типах из src/types/.
 * Остальные API-модули мигрируют инкрементально (план в ADR-0005, п. 3).
 */
import api from './axiosConfig';
import type {
  ApiTemplateResponse,
  ApiTemplateCreateRequest,
  ApiTemplateUpdateRequest,
  ApiTemplateRenderRequest,
  ApiTemplateRenderResponse,
  ApiPage,
} from './generated/schema';

interface TemplateListParams {
  page?: number;
  size?: number;
  messageType?: 'DOWNLINK' | 'UPLINK' | 'GROUND';
  category?: string;
  active?: boolean;
}

export const templatesApi = {
  list: async (params: TemplateListParams = {}): Promise<ApiPage<ApiTemplateResponse>> => {
    const { data } = await api.get<ApiPage<ApiTemplateResponse>>('/templates', { params });
    return data;
  },

  getById: async (id: number): Promise<ApiTemplateResponse> => {
    const { data } = await api.get<ApiTemplateResponse>(`/templates/${id}`);
    return data;
  },

  getByName: async (name: string): Promise<ApiTemplateResponse> => {
    const { data } = await api.get<ApiTemplateResponse>(`/templates/by-name/${encodeURIComponent(name)}`);
    return data;
  },

  create: async (request: ApiTemplateCreateRequest): Promise<ApiTemplateResponse> => {
    const { data } = await api.post<ApiTemplateResponse>('/templates', request);
    return data;
  },

  update: async (id: number, request: ApiTemplateUpdateRequest): Promise<ApiTemplateResponse> => {
    const { data } = await api.put<ApiTemplateResponse>(`/templates/${id}`, request);
    return data;
  },

  delete: async (id: number): Promise<void> => {
    await api.delete(`/templates/${id}`);
  },

  render: async (request: ApiTemplateRenderRequest): Promise<ApiTemplateRenderResponse> => {
    const { data } = await api.post<ApiTemplateRenderResponse>('/templates/render', request);
    return data;
  },
};
