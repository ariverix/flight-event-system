import api from './axiosConfig';
import { PageResponse } from '../types/sequence';

export interface AuditLogEntry {
  id: number;
  userId: number | null;
  action: string;
  entityType: string | null;
  entityId: number | null;
  detailsJson: string | null;
  correlationId: string | null;
  createdAt: string;
}

export const auditApi = {
  getLogs: async (
    page = 0,
    size = 20,
    entityType?: string,
    action?: string,
  ): Promise<PageResponse<AuditLogEntry>> => {
    const params: { page: number; size: number; entityType?: string; action?: string } = { page, size };
    if (entityType) params.entityType = entityType;
    if (action)     params.action     = action;
    const response = await api.get<PageResponse<AuditLogEntry>>('/audit-log', { params });
    return response.data;
  },
};
