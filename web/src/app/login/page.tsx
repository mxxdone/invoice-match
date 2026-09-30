'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Mark, Icon } from '../ui';
import { useAuth } from '../auth';

export default function Login() {
  const router = useRouter();
  const { login, logout, isSubmitting, isAuthenticated, error } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  // Leaving this screen cancels its own in-flight attempt so a late success
  // cannot set the auth state or navigate after the user has moved on.
  const attempt = useRef<AbortController | null>(null);
  useEffect(() => () => attempt.current?.abort(), []);
  useEffect(() => { if (isAuthenticated) router.replace('/cases'); }, [isAuthenticated, router]);
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    attempt.current?.abort();
    const controller = new AbortController();
    attempt.current = controller;
    const ok = await login({ username: username.trim(), password }, controller.signal);
    if (controller.signal.aborted) return;
    if (ok) { setPassword(''); router.replace('/cases'); }
  }
  return <main className="login-layout"><aside className="login-aside"><Mark /><div className="login-context"><span className="workspace-label">청구 검토 업무 공간</span><h1>청구에서 인계까지,<br />근거와 함께.</h1><p>청구 · 발주 · 검수를 비교하고<br />검토 결정과 ERP 인계 상태를 확인합니다.</p><div className="login-rule" /><span className="login-context-note">Invoice Match · Phase 1</span></div><div className="login-aside-footer">검토 · 승인 · 감사이력</div></aside><section className="login-main"><div className="login-form-wrap"><span className="demo-tag">로컬 데모 로그인 · /api/me 검증</span><h2>로그인</h2><p className="login-description">업무 계정으로 로그인하세요. 자격증명은 서버가 판정합니다.</p><form onSubmit={submit}><label className="login-field">사용자명<input autoComplete="username" required value={username} onChange={event => setUsername(event.target.value)} placeholder="사용자명을 입력하세요" /></label><label className="login-field">비밀번호<div className="password-control"><input autoComplete="current-password" required type={showPassword ? 'text' : 'password'} value={password} onChange={event => setPassword(event.target.value)} placeholder="비밀번호를 입력하세요" /><button type="button" aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 표시'} onClick={() => setShowPassword(!showPassword)}>{showPassword ? '숨기기' : '표시'}</button></div></label><button className="button primary login-submit" type="submit" disabled={isSubmitting}>{isSubmitting ? '확인 중…' : '로그인'}<Icon name="chevron" size={14} /></button></form>{error && <p className="login-message" role="alert">{error}</p>}<div className="login-preview-link"><span>로그인 전 디자인 화면</span><Link href="/" onClick={() => logout()}>상세 화면 시안 열기<Icon name="chevron" size={13} /></Link></div><p className="login-footnote">자격증명은 이 브라우저 메모리에만 보관되고 저장소·URL·로그에 남기지 않습니다.<br />새로고침하거나 로그아웃하면 제거되므로 다시 로그인해야 합니다.</p></div><span className="login-main-footer">invoice match</span></section></main>;
}
