"use client";

import React, { createContext, useContext, useState, useCallback, useMemo } from "react";
import { AuthCredentials, api } from "./api-client";
import { CurrentUserView, Role } from "@/types/api";

export interface DemoAccount {
  username: string;
  label: string;
  role: Role;
  description: string;
}

export const DEMO_ACCOUNTS: DemoAccount[] = [
  {
    username: "submitter",
    label: "제출자 (submitter)",
    role: "SUBMITTER",
    description: "청구서 작성, 임시저장(Draft), 제출 및 보완개정(Revision)",
  },
  {
    username: "submitter2",
    label: "제출자 2 (submitter2)",
    role: "SUBMITTER",
    description: "다른 공급사/사건 담당 제출자 (소유권 분리 검증용)",
  },
  {
    username: "approver",
    label: "승인자 (approver)",
    role: "APPROVER",
    description: "검토 스냅샷 동결, 품목 매핑 결정, 보완요청, 거절, 최종 승인",
  },
  {
    username: "operator",
    label: "운영자 (operator)",
    role: "OPERATOR",
    description: "결정론적 3-way 대사(Match) 실행 및 이력 조회",
  },
];

interface AuthContextValue {
  credentials: AuthCredentials | null;
  user: CurrentUserView | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  login: (credentials: AuthCredentials) => Promise<{ success: boolean; error?: string }>;
  logout: () => void;
  hasRole: (role: Role) => boolean;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  // Credentials are held in React state (session memory only), never in localStorage.
  const [credentials, setCredentials] = useState<AuthCredentials | null>(null);
  const [user, setUser] = useState<CurrentUserView | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const login = useCallback(async (creds: AuthCredentials) => {
    setIsLoading(true);
    setError(null);

    const res = await api.getMe(creds);
    if (res.ok && res.data) {
      setCredentials(creds);
      setUser(res.data);
      setIsLoading(false);
      return { success: true };
    } else {
      const errMsg = res.status === 401
        ? "인증에 실패했습니다. 아이디 또는 비밀번호를 확인하세요."
        : res.error?.message || "로그인 중 오류가 발생했습니다.";
      setError(errMsg);
      setIsLoading(false);
      return { success: false, error: errMsg };
    }
  }, []);

  const logout = useCallback(() => {
    setCredentials(null);
    setUser(null);
    setError(null);
  }, []);

  const hasRole = useCallback((role: Role) => {
    return user?.roles.includes(role) ?? false;
  }, [user]);

  const value = useMemo(
    () => ({
      credentials,
      user,
      isAuthenticated: Boolean(credentials && user),
      isLoading,
      error,
      login,
      logout,
      hasRole,
    }),
    [credentials, user, isLoading, error, login, logout, hasRole]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return ctx;
}
