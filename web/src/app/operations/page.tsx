'use client';

import Link from 'next/link';
import { useState } from 'react';
import { DemoBar, Icon, PageHeader, Shell, Toast } from '../ui';

const rows = [
  {id:'0140',supplier:'SUP-1002',task:'결과 확인',kind:'unknown',status:'RESULT_UNKNOWN',reason:'REQUEST_TIMEOUT · 자동 재전송 금지',updated:'11:02'},
  {id:'0134',supplier:'SUP-1005',task:'오류 확인',kind:'failed',status:'FAILED',reason:'HTTP_400 · 요청 거부',updated:'10:54'},
  {id:'0142',supplier:'SUP-1001',task:'대사 실행',kind:'match',status:'REVIEW_PENDING',reason:'현재 제출 증빙 대상',updated:'10:38'},
  {id:'0139',supplier:'SUP-1001',task:'대사 실행',kind:'match',status:'REVIEW_PENDING',reason:'현재 제출 증빙 대상',updated:'09:45'},
];

export default function Operations() {
  const [filter,setFilter] = useState('all');
  const [query,setQuery] = useState('');
  const [selected,setSelected] = useState<string | null>('0140');
  const [toast,setToast] = useState('');
  const visible = rows.filter(row => (filter==='all'||row.kind===filter) && `${row.id} ${row.supplier}`.includes(query));
  const current = visible.find(row => row.id===selected);
  return <Shell active="operations" actor="operator" role="운영자"><PageHeader eyebrow="운영 업무" title="운영 작업" subtitle="대사 대상과 ERP 인계 실패·결과불명 건을 확인합니다." /><div className="tabs" role="tablist" aria-label="운영 작업 유형">{[['all','전체'],['match','대사 대상'],['unknown','결과불명'],['failed','인계 실패']].map(([id,label]) => <button key={id} role="tab" aria-selected={filter===id} className={filter===id?'active':''} onClick={() => setFilter(id)}>{label}<span className="tab-count">{rows.filter(row => id==='all'||row.kind===id).length}</span></button>)}</div><DemoBar />
    <div className="list-tools"><label className="search"><Icon name="search" /><input aria-label="운영 사건 검색" placeholder="청구번호 또는 공급사 ID 검색" value={query} onChange={event => setQuery(event.target.value)} /></label><span className="muted-text">자동 재전송 없음</span></div><div className="review-workbench"><div className="table-area"><div className="table-scroll"><table className="work-table operations-table"><thead><tr><th>청구 / 공급사</th><th>작업</th><th>업무 상태</th><th>갱신</th><th><span className="sr-only">상세</span></th></tr></thead><tbody>{visible.map(row => <tr key={row.id} className={selected===row.id?'selected':''}><td><strong>INV-2026-{row.id}</strong><small className="secondary-line">{row.supplier}</small></td><td>{row.task}</td><td><span className={`line-badge ${row.kind==='match'?'':'exception'}`}>{row.status}</span><small className="secondary-line">{row.reason}</small></td><td className="muted-text">{row.updated}</td><td><button className="row-detail" aria-label={`${row.id}번 운영 근거`} aria-expanded={selected===row.id} onClick={() => setSelected(selected===row.id?null:row.id)}><Icon name="chevron" size={14} /></button></td></tr>)}{!visible.length&&<tr><td colSpan={5} className="empty-table">조건에 맞는 작업이 없습니다.</td></tr>}</tbody></table></div><div className="table-summary"><span>{visible.length}개 작업 · 가상 목록</span></div></div>{current&&<aside className="evidence-panel"><div className="panel-title">작업 근거<button className="icon-button" aria-label="운영 패널 닫기" onClick={() => setSelected(null)}><Icon name="close" /></button></div><h2>INV-2026-{current.id}</h2><p className="panel-subtitle">{current.supplier}</p><div className={`panel-status ${current.kind==='match'?'':'warning'}`}><strong>{current.task}</strong><p>{current.kind==='unknown'?'ERP에서 이미 처리했을 수 있습니다. 같은 요청을 다시 보내는 버튼을 제공하지 않습니다.':current.kind==='failed'?'실패 원인을 확인해야 합니다. 이 시안에는 오류 수정이나 재전송 기능이 없습니다.':'제출된 최신 증빙을 대상으로 대사를 실행하는 작업입니다.'}</p></div><dl className="receipt-data"><div><dt>현재 상태</dt><dd>{current.status}</dd></div><div><dt>오류</dt><dd>{current.kind==='match'?'—':current.kind==='unknown'?'REQUEST_TIMEOUT':'HTTP_400'}</dd></div></dl>{current.id==='0140'?<Link href="/handoff" className="button">인계 상세 열기<Icon name="chevron" size={13} /></Link>:current.kind==='match'?<button className="button" onClick={() => setToast('대사 실행 시연 · 실제 대사 API는 호출하지 않았습니다.')}>대사 실행 시연</button>:<p className="panel-footnote">인계 상세는 0140번 고정 시안에서 확인할 수 있습니다.</p>}</aside>}</div><p className="review-note">Phase 1 업무만 표시합니다. 분석 큐·RabbitMQ·DLQ 모니터링은 이후 Phase 대상입니다.</p><Toast message={toast} dismiss={() => setToast('')} />
  </Shell>;
}
