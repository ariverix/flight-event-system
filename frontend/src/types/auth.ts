export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  username: string;
  role: 'OPERATOR' | 'ADMIN';
  fullName: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  fullName: string;
  role: 'OPERATOR' | 'ADMIN';
}

export interface UserResponse {
  id: number;
  username: string;
  fullName: string;
  role: 'OPERATOR' | 'ADMIN';
  enabled: boolean;
  createdAt: string;
}

export interface AuthContextType {
  user: LoginResponse | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  isAuthenticated: boolean;
  isAdmin: boolean;
}
