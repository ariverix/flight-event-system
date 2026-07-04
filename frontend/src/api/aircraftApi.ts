/**
 * aircraftApi — список бортов (tail numbers) для привязки последовательностей (Фаза 5/6).
 * Типы строго из OpenAPI-контракта (generated/schema), без any.
 */
import api from './axiosConfig';
import type {
  ApiAircraftSummaryResponse,
  ApiPageAircraftSummaryResponse,
} from './generated/schema';

export type AircraftSummary = ApiAircraftSummaryResponse;

export interface ListAircraftParams {
  search?: string;
  page?: number;
  size?: number;
}

export const aircraftApi = {
  /**
   * Страница бортов (различные tail numbers) с метаданными последнего контакта.
   * @param params поиск по подстроке tail number + пагинация
   */
  list: async (params: ListAircraftParams = {}): Promise<ApiPageAircraftSummaryResponse> => {
    const query: { search?: string; page: number; size: number } = {
      page: params.page ?? 0,
      size: params.size ?? 20,
    };
    if (params.search && params.search.trim()) {
      query.search = params.search.trim();
    }
    const { data } = await api.get<ApiPageAircraftSummaryResponse>('/aircraft', { params: query });
    return data;
  },
};
