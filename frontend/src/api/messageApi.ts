import api from './axiosConfig';
import { IncomingMessageRequest, FlightStageChangeRequest, MessageResponse } from '../types/message';
import { PageResponse } from '../types/sequence';

export const messageApi = {
  sendMessage: async (request: IncomingMessageRequest): Promise<void> => {
    await api.post('/messages/incoming', request);
  },

  changeFlightStage: async (request: FlightStageChangeRequest): Promise<void> => {
    await api.post('/messages/stage-change', request);
  },

  getMessages: async (
    page: number = 0,
    size: number = 10,
    aircraftId?: string,
    messageType?: string,
    startDate?: string,
    endDate?: string
  ): Promise<PageResponse<MessageResponse>> => {
    const params: Record<string, any> = { page, size };
    if (aircraftId) params.aircraftId = aircraftId;
    if (messageType) params.messageType = messageType;
    if (startDate) params.startDate = startDate;
    if (endDate) params.endDate = endDate;
    const response = await api.get<PageResponse<MessageResponse>>('/messages', { params });
    return response.data;
  },
};
