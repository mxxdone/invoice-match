'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Mark, Icon } from '../ui';
import { Button } from '@/components/ui/button';
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
  return <main className="login-layout flex min-h-screen bg-white"><aside className="login-aside hidden w-[43%] shrink-0 flex-col border-r border-[#e1dfd9] bg-sidebar px-12 py-10 min-[760px]:flex min-[1600px]:pl-[72px]"><Mark /><div className="login-context my-auto py-[75px]"><span className="workspace-label mb-7 block text-label text-[#999a8d]">청구 검토 업무 공간</span><h1 className="text-[36px] font-normal leading-[1.65] tracking-[-1.3px]">청구에서 인계까지,<br />근거와 함께.</h1><p className="mt-[22px] text-label leading-[2.1] text-[#929787]">청구 · 발주 · 검수를 비교하고<br />검토 결정과 ERP 인계 상태를 확인합니다.</p><div className="login-rule mt-9 mb-5 h-0.5 w-10 bg-[#cad683]" /></div><div className="login-aside-footer text-label text-[#9b9f90]">검토 · 승인 · 감사이력</div></aside><section className="login-main relative flex flex-1 items-center justify-center px-[25px] py-[50px] min-[760px]:px-[30px] min-[760px]:py-[70px]"><div className="login-form-wrap w-[340px] max-w-full"><span className="demo-tag border border-[#d6d5c9] px-1.5 py-[3px] text-label text-[#74756a]">시연용 업무 계정 로그인</span><h2 className="mt-6 mb-[13px] text-[30px] font-medium tracking-[-.8px]">로그인</h2><p className="login-description mb-[31px] text-label text-[#989d8b]">업무 계정으로 로그인하세요. 자격증명은 서버가 판정합니다.</p><form onSubmit={submit}><label className="login-field mb-[21px] block text-label text-[#7f876f]">사용자명<Input autoComplete="username" required value={username} onChange={event => setUsername(event.target.value)} placeholder="사용자명을 입력하세요" className="mt-2.5" /></label><label className="login-field mb-[21px] block text-label text-[#7f876f]">비밀번호<div className="password-control mt-2.5 flex h-10 w-full items-center gap-[5px] rounded-sm border border-[#d9ddcf] bg-white"><Input autoComplete="current-password" required type={showPassword ? 'text' : 'password'} value={password} onChange={event => setPassword(event.target.value)} placeholder="비밀번호를 입력하세요" className="h-full rounded-none border-0 bg-transparent px-3" /><button type="button" className="border-0 bg-transparent px-2.5 py-[10px] text-label whitespace-nowrap text-[#8a9577]" aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 표시'} onClick={() => setShowPassword(!showPassword)}>{showPassword ? '숨기기' : '표시'}</button></div></label><Button type="submit" className="login-submit mt-[5px] w-full justify-between" disabled={isSubmitting}>{isSubmitting ? '확인 중…' : '로그인'}<Icon name="chevron" size={14} /></Button></form>{error && <p className="login-message mt-[15px] border-l-2 border-[#b9cba4] pl-2.5 text-label leading-[1.8] text-[#7b865f]" role="alert">{error}</p>}<p className="login-footnote mt-[25px] text-label leading-[1.9] text-[#a0a690]">자격증명은 이 브라우저 메모리에만 보관되고 저장소·URL·로그에 남기지 않습니다.<br />새로고침하거나 로그아웃하면 제거되므로 다시 로그인해야 합니다.</p></div><span className="login-main-footer absolute bottom-[27px] right-[34px] text-label text-[#adb09f]">invoice match</span></section></main>;
}
