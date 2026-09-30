'use client';

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { fetchCurrentUser } from './api/client';
import { ApiRequestError, type Credentials } from './api/transport';
import { Generation } from './api/generation';
import type { CurrentUser } from './api/contract';

// Phase 1 local/demo authentication state. Credentials live only in this React
// state for the lifetime of the tab: they are not written to
// sessionStorage/localStorage/IndexedDB, the URL, logs or error output, and a
// page reload or logout drops them so the user must sign in again. The server
// remains the authority for identity, roles and ownership.
type AuthContextValue = {
  credentials: Credentials | null;
  user: CurrentUser | null;
  isAuthenticated: boolean;
  isSubmitting: boolean;
  error: string | null;
  // Increments on every successful login and on logout, so consumers can scope
  // their own work to one session.
  sessionId: number;
  login: (credentials: Credentials, signal?: AbortSignal) => Promise<boolean>;
  logout: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [credentials, setCredentials] = useState<Credentials | null>(null);
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState(0);
  // A logout (or a newer login attempt) invalidates an in-flight login, so a
  // late success never signs the user back in after they signed out. The caller
  // may also pass a signal so leaving the login screen cancels its own attempt.
  const sessions = useRef(new Generation());

  const login = useCallback(async (next: Credentials, signal?: AbortSignal) => {
    const token = sessions.current.next();
    setIsSubmitting(true);
    setError(null);
    try {
      const me = await fetchCurrentUser(next, signal);
      if (!sessions.current.isCurrent(token)) return false;
      setCredentials(next);
      setUser(me);
      setSessionId(value => value + 1);
      setIsSubmitting(false);
      return true;
    } catch (caught) {
      if (!sessions.current.isCurrent(token)) return false;
      if (caught instanceof Error && caught.name === 'AbortError') {
        setIsSubmitting(false);
        return false;
      }
      setCredentials(null);
      setUser(null);
      setIsSubmitting(false);
      if (caught instanceof ApiRequestError && caught.status === 401) {
        setError('사용자명 또는 비밀번호가 올바르지 않습니다.');
      } else if (caught instanceof ApiRequestError && caught.status === 403) {
        setError('이 계정에는 업무 화면에 접근할 권한이 없습니다.');
      } else {
        setError('로그인하지 못했습니다. 서버 연결을 확인하고 다시 시도하세요.');
      }
      return false;
    }
  }, []);

  const logout = useCallback(() => {
    sessions.current.next();
    setCredentials(null);
    setUser(null);
    setError(null);
    setIsSubmitting(false);
    setSessionId(value => value + 1);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      credentials,
      user,
      isAuthenticated: Boolean(credentials && user),
      isSubmitting,
      error,
      sessionId,
      login,
      logout,
    }),
    [credentials, user, isSubmitting, error, sessionId, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
