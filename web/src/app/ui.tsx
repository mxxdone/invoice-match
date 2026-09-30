'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState, type ReactNode } from 'react';
import { useAuth } from './auth';

export function Mark() { return <div className="brand"><span className="brand-mark">im<span>.</span></span><span>invoice match</span></div>; }
// Navigation is role-aware, but the server remains the authority: hiding an
// entry is guidance, not an authorization decision.
const navigation = [
  ['cases', '/cases', '청구서', 'document', []],
  ['new', '/cases/new', '청구 작성', 'plus', ['SUBMITTER']],
  ['operations', '/operations', '운영 작업', 'grid', ['OPERATOR']],
  ['handoff', '/handoff', 'ERP 인계', 'send', ['APPROVER', 'OPERATOR']],
] as const;

export type IconName = 'grid' | 'search' | 'chevron' | 'arrow' | 'check' | 'close' | 'sliders' | 'clock' | 'document' | 'plus' | 'send';
export function Icon({ name, size = 16 }: { name: IconName; size?: number }) {
  const paths: Record<IconName, ReactNode> = {
    grid: <><rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" /><rect x="3" y="14" width="7" height="7" rx="1" /><rect x="14" y="14" width="7" height="7" rx="1" /></>,
    search: <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 5 5" /></>,
    chevron: <path d="m9 5 7 7-7 7" />, arrow: <path d="M19 12H5m6-6-6 6 6 6" />,
    check: <path d="m5 12 4 4L19 6" />, close: <path d="m6 6 12 12M6 18 18 6" />,
    sliders: <><path d="M4 7h16M4 17h16" /><circle cx="9" cy="7" r="2" /><circle cx="15" cy="17" r="2" /></>,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    document: <><path d="M14 3H5v18h14V8zM14 3v5h5M8 12h8M8 16h6" /></>,
    plus: <path d="M12 5v14M5 12h14" />,
    send: <><path d="m3 4 18 8-18 8 3-8zM6 12h15" /></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

const roleLabels: Record<string, string> = { SUBMITTER: '제출자', APPROVER: '승인자', OPERATOR: '운영자' };

export function Sidebar({ active, preview = false, actor, role }: { active: string; preview?: boolean; actor?: string; role?: string }) {
  const router = useRouter();
  const { user, isAuthenticated, logout } = useAuth();
  // Preview screens keep their declared demo identity and the design-only note;
  // a live screen shows the authoritative /api/me identity and no 시안 label.
  const shownActor = preview ? (actor ?? 'approver') : (user?.username ?? 'guest');
  const shownRole = preview
    ? (role ?? '승인자')
    : isAuthenticated
      ? (user?.roles.map(item => roleLabels[item] ?? item).join(' · ') || '역할 없음')
      : '로그인이 필요합니다';
  const caption = preview ? '데모 계정' : isAuthenticated ? '로그인됨' : '인증 필요';
  function signOut() { logout(); router.push('/login'); }
  const visibleNav = preview ? navigation : navigation.filter(([, , , , roles]) => roles.length === 0 || roles.some(item => user?.roles.includes(item)));
  return <aside className="sidebar" aria-label="작업 공간"><Mark /><div className="workspace-label">업무 공간</div><nav className="primary-nav" aria-label="업무 화면 탐색">{visibleNav.map(([id, href, label, icon]) => <Link key={id} href={href} className={active === id ? 'nav-parent is-current' : 'nav-parent'} aria-current={active === id ? 'page' : undefined}><Icon name={icon} />{label}</Link>)}</nav>{preview && <div className="sidebar-note">전체 역할의 화면을 둘러보는<br />디자인 시안입니다.</div>}<div className="sidebar-bottom"><span className="avatar">{shownActor.slice(0, 2).toUpperCase()}</span><div><strong>{shownActor}</strong><span>{shownRole} · {caption}</span>{preview ? <Link href="/login" className="session-link">로그인 화면</Link> : isAuthenticated ? <button type="button" className="session-link" onClick={signOut}>로그아웃</button> : <Link href="/login" className="session-link">로그인</Link>}</div></div></aside>;
}
export function Shell({ active, preview = true, actor, role, children }: { active: string; preview?: boolean; actor?: string; role?: string; children: ReactNode }) {
  return <div className="app-shell"><Sidebar active={active} preview={preview} actor={actor} role={role} /><main className="workspace">{preview ? <StatePreview>{children}</StatePreview> : children}</main></div>;
}
export function StatePreview({ children }: { children: ReactNode }) {
  const [state, setState] = useState('ready');
  const descriptions: Record<string, [string, string]> = {
    loading: ['불러오는 중', '청구와 검토 근거를 확인하고 있습니다.'],
    empty: ['표시할 데이터가 없습니다', '아직 등록된 자료가 없습니다.'],
    error: ['자료를 불러오지 못했습니다', '연결 상태를 확인하고 다시 시도해 주세요.'],
    unauthorized: ['로그인이 필요합니다', '세션이 만료되었습니다. 다시 로그인해 주세요.'],
    forbidden: ['이 화면에 접근할 권한이 없습니다', '계정 역할과 사건 소유권을 확인해 주세요.'],
  };
  return <><div className="state-preview-control"><label>화면 상태 시안<select aria-label="화면 상태 시안" value={state} onChange={event => setState(event.target.value)}><option value="ready">기본</option><option value="loading">로딩</option><option value="empty">데이터 없음</option><option value="error">조회 실패</option><option value="unauthorized">401 · 세션 만료</option><option value="forbidden">403 · 권한 없음</option></select></label></div>{state === 'ready' ? children : <section className="empty-state" role="status" aria-busy={state === 'loading'}><Icon name="document" size={25} /><h1>{descriptions[state][0]}</h1><p>{descriptions[state][1]}</p>{state === 'unauthorized' ? <Link className="button" href="/login">로그인 화면</Link> : state !== 'loading' && <button className="button" onClick={() => setState('ready')}>기본 시안으로 돌아가기</button>}<p className="panel-footnote">실제 서버 오류가 아닌 상태 배치 시안입니다.</p></section>}</>;
}
export function PageHeader({ eyebrow, title, subtitle, action, children }: { eyebrow: string; title: string; subtitle?: string; action?: ReactNode; children?: ReactNode }) {
  return <header className="page-header"><div className="breadcrumb">{eyebrow}</div><div className="title-row"><div><h1>{title}</h1>{subtitle && <p className="header-description">{subtitle}</p>}</div>{action}</div>{children}</header>;
}
export function DemoBar({ children }: { children?: ReactNode }) { return <div className="toolbar"><span className="demo-tag">디자인 시안</span><span className="demo-description">가상 데이터 · 실제 API 미연결</span>{children}</div>; }
export function Toast({ message, dismiss }: { message: string; dismiss: () => void }) {
  useEffect(() => { if (!message) return; const timer = setTimeout(dismiss, 5000); return () => clearTimeout(timer); }, [message, dismiss]);
  return message ? <div className="toast" role="status"><Icon name="check" /><span>{message}</span><button className="icon-button" aria-label="알림 닫기" onClick={dismiss}><Icon name="close" size={14} /></button></div> : null;
}
