/**
 * conditionsApi — активные условия бортов/рейсов (P3-3). Типы из OpenAPI-контракта.
 */
import api from './axiosConfig';
import type { ApiRaisedConditionResponse } from './generated/schema';

export const conditionsApi = {
  listActive: async (): Promise<ApiRaisedConditionResponse[]> => {
    const { data } = await api.get<ApiRaisedConditionResponse[]>('/conditions');
    return data;
  },
};
