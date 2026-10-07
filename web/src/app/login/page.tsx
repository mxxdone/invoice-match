'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Mark, Icon } from '../ui';
import { Button } from '@/components/ui/button';
import { InputGroup } from '@/components/ui/input-group';
import { Input } from '@/components/ui/input';
import { useAuth } from '../auth';

export default function Login() {
  const router = useRouter();
  const { login, isSubmitting, isAuthenticated, error } = useAuth();
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
  return <main className="login-layout flex min-h-screen bg-white"><aside className="login-aside hidden w-[43%] shrink-0 flex-col border-r border-border bg-sidebar px-12 py-10 min-[760px]:flex min-[1600px]:pl-18"><Mark /><div className="login-context my-auto py-18.75"><span className="workspace-label mb-7 block text-label text-muted-foreground">청구 검토 업무 공간</span><h1 className="text-display font-normal leading-hero tracking-hero">청구에서 인계까지, 근거와 함께.</h1><p className="mt-5.5 text-label leading-air text-muted-foreground">청구 · 발주 · 검수를 비교하고 검토 결정과 ERP 인계 상태를 확인합니다.</p><div className="login-rule mt-9 mb-5 h-0.5 w-10 bg-brand-rule" /></div><div className="login-aside-footer text-label text-muted-foreground">검토 · 승인 · 감사이력</div></aside><section className="login-main relative flex min-w-0 flex-1 items-center justify-center px-6.25 py-12.5 min-[760px]:px-7.5 min-[760px]:py-17.5"><div className="login-form-wrap w-[340px] max-w-full"><span className="demo-tag border border-input px-1.5 py-0.75 text-label text-muted-foreground">시연용 업무 계정 로그인</span><h2 className="mt-6 mb-3.25 text-display-sm font-medium tracking-display">로그인</h2><p className="login-description mb-7.75 text-label text-muted-foreground">사용자명과 비밀번호를 입력해 주세요.</p><form onSubmit={submit}><label className="login-field mb-5.25 block text-label text-muted-foreground">사용자명<Input autoComplete="username" required value={username} onChange={event => setUsername(event.target.value)} placeholder="사용자명을 입력하세요" className="mt-2.5" /></label><label className="login-field mb-5.25 block text-label text-muted-foreground">비밀번호<InputGroup className="password-control mt-2.5"><Input autoComplete="current-password" required type={showPassword ? 'text' : 'password'} value={password} onChange={event => setPassword(event.target.value)} placeholder="비밀번호를 입력하세요"  variant="embedded" /><Button type="button"  aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 표시'} onClick={() => setShowPassword(!showPassword)} variant="ghost" size="sm">{showPassword ? '숨기기' : '표시'}</Button></InputGroup></label><Button type="submit" className="login-submit mt-1.25 w-full justify-between" disabled={isSubmitting}>{isSubmitting ? '확인 중…' : '로그인'}<Icon name="chevron" size={14} /></Button></form>{error && <p className="login-message mt-3.75 border-l-2 border-control-border pl-2.5 text-label leading-body text-destructive" role="alert">{error}</p>}<p className="login-footnote mt-6.25 text-label leading-roomy text-muted-foreground">새로고침하거나 로그아웃하면 다시 로그인해야 합니다.</p></div><span className="login-main-footer absolute bottom-6.75 right-8.5 text-label text-muted-foreground">invoice match</span></section></main>;
}
