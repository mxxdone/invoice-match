'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Mark, Icon } from '../ui';

export default function Login() {
  const [username,setUsername] = useState('');
  const [password,setPassword] = useState('');
  const [showPassword,setShowPassword] = useState(false);
  const [message,setMessage] = useState('');
  return <main className="login-layout"><aside className="login-aside"><Mark /><div className="login-context"><span className="workspace-label">청구 검토 업무 공간</span><h1>청구에서 인계까지,<br />근거와 함께.</h1><p>청구 · 발주 · 검수를 비교하고<br />검토 결정과 ERP 인계 상태를 확인합니다.</p><div className="login-rule" /><span className="login-context-note">Invoice Match · Phase 1</span></div><div className="login-aside-footer">검토 · 승인 · 감사이력</div></aside><section className="login-main"><div className="login-form-wrap"><span className="demo-tag">디자인 시안 · 인증 미연결</span><h2>로그인</h2><p className="login-description">업무 계정으로 로그인하세요.</p><form onSubmit={event => {event.preventDefault();setMessage('디자인 시연입니다. 계정이나 비밀번호를 전송·저장하지 않았습니다.');setPassword('');}}><label className="login-field">사용자명<input autoComplete="username" required value={username} onChange={event => setUsername(event.target.value)} placeholder="사용자명을 입력하세요" /></label><label className="login-field">비밀번호<div className="password-control"><input autoComplete="current-password" required type={showPassword?'text':'password'} value={password} onChange={event => setPassword(event.target.value)} placeholder="비밀번호를 입력하세요" /><button type="button" aria-label={showPassword?'비밀번호 숨기기':'비밀번호 표시'} onClick={() => setShowPassword(!showPassword)}>{showPassword?'숨기기':'표시'}</button></div></label><button className="button primary login-submit" type="submit">로그인 시연<Icon name="chevron" size={14} /></button></form>{message&&<p className="login-message" role="status">{message}</p>}<div className="login-preview-link"><span>인증 없이 디자인만 둘러보기</span><Link href="/cases">화면 시안 열기<Icon name="chevron" size={13} /></Link></div><p className="login-footnote">실제 자격증명을 입력하지 마세요.<br />회원가입·비밀번호 재설정 기능은 이번 범위에 없습니다.</p></div><span className="login-main-footer">invoice match</span></section></main>;
}
