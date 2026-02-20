import api from './axiosConfig';
import { LoginRequest, LoginResponse, RegisterRequest, UserResponse } from '../types/auth';

export const authApi = {
  login: async (request: LoginRequest): Promise<LoginResponse> => {
    const response = await api.post<LoginResponse>('/auth/login', request);
    return response.data;
  },

  register: async (request: RegisterRequest): Promise<UserResponse> => {
    const response = await api.post<UserResponse>('/auth/register', request);
    return response.data;
  },

  me: async (): Promise<UserResponse> => {
    const response = await api.get<UserResponse>('/auth/me');
    return response.data;
  },

  getUsers: async (): Promise<UserResponse[]> => {
    const response = await api.get<UserResponse[]>('/users');
    return response.data;
  },

  toggleUser: async (userId: number): Promise<UserResponse> => {
    const response = await api.patch<UserResponse>(`/users/${userId}/toggle`);
    return response.data;
  },
};
