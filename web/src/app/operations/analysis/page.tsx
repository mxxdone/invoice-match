 'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../../auth';
import { PageHeader, Shell } from '../../ui';
import { fetchAnalysisJobs, fetchAnalysisFailures, type AnalysisJob, type AnalysisFailure, type AnalysisPage } from '../../api/client';
import { ApiRequestError } from '../../api/transport';
import { formatInstant } from '../../api/contract';
import { useAnalysisRetry } from './use-analysis-retry';

const labels: Record<string, string> = { QUEUED: '대기', RUNNING: '처리 중', COMPLETED: '완료', FAILED: '문서 확인 필요',
  STALE: '이전 제출', RETRY_SCHEDULED: '재시도 대기', DEAD_LETTERED: '운영 확인 필요' };
export default function AnalysisOperations() {
  const { credentials, user, sessionId, isAuthenticated, logout } = useAuth();
  const router = useRouter();const enabled = !!user?.roles.includes('OPERATOR');
  const [status, setStatus] = useState('');const [page, setPage] = useState(0);const [reload, setReload] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);const [historyPage, setHistoryPage] = useState(0);
  const [reason, setReason] = useState('');
  const [jobs, setJobs] = useState<{ key: string; data?: AnalysisPage<AnalysisJob>; error?: string }>();
  const [history, setHistory] = useState<{ key: string; data?: AnalysisPage<AnalysisFailure>; error?: string }>();
  const key = `${sessionId}:${enabled}:${status}:${page}:${reload}`;
  const historyKey = `${sessionId}:${enabled}:${selected}:${historyPage}:${reload}`;
  const unauthorized = useCallback(() => { logout();router.replace('/login'); }, [logout, router]);
  const retry = useAnalysisRetry(credentials, sessionId, enabled, () => setReload(value => value + 1), unauthorized);
  useEffect(() => { if (!isAuthenticated) router.replace('/login'); }, [isAuthenticated, router]);
  useEffect(() => {
    if (!credentials || !enabled) return;
    const controller = new AbortController();let cancelled = false;
    fetchAnalysisJobs(credentials, status, page, controller.signal).then(data => {
      if (!cancelled) setJobs({ key, data });
    }).catch(error => {
      if (cancelled) return;
      if (error instanceof ApiRequestError && error.status === 401) unauthorized();
      else setJobs({ key, error: '분석 작업을 불러오지 못했습니다.' });
    });
    return () => { cancelled = true;controller.abort(); };
  }, [credentials, enabled, key, page, status, unauthorized]);
  useEffect(() => {
    if (!credentials || !enabled || !selected) return;
    const controller = new AbortController();let cancelled = false;
    fetchAnalysisFailures(credentials, selected, historyPage, controller.signal).then(data => {
      if (!cancelled) setHistory({ key: historyKey, data });
    }).catch(error => {
      if (cancelled) return;
      if (error instanceof ApiRequestError && error.status === 401) unauthorized();
      else setHistory({ key: historyKey, error: '실패 이력을 불러오지 못했습니다.' });
    });
    return () => { cancelled = true;controller.abort(); };
  }, [credentials, enabled, selected, historyPage, historyKey, unauthorized]);
  const data = jobs?.key === key ? jobs.data : undefined;
  const failures = history?.key === historyKey ? history.data : undefined;
  const job = data?.items.find(row => row.runId === selected);
  const locked = retry.pending || retry.uncertain;
  return <Shell active="operations" preview={false}>
    <PageHeader eyebrow="운영 업무" title="문서 분석 운영" subtitle="처리 상태와 실패 원인을 확인하고 원인 수정 후 재처리를 예약합니다."
      action={<Link className="button" href="/operations">운영 작업</Link>} />
    {!enabled ? <section className="empty-state"><h2>운영자 권한이 필요합니다.</h2></section> : <>
      <div className="list-tools"><label>상태 <select aria-label="분석 상태" disabled={locked} value={status} onChange={event => {
        setStatus(event.target.value);setPage(0);setSelected(null);setHistoryPage(0);
      }}><option value="">전체</option>{Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <button className="button" disabled={locked} onClick={() => setReload(value => value + 1)}>새로고침</button></div>
      {jobs?.key === key && jobs.error ? <p role="alert">{jobs.error}</p> : !data ? <p role="status">불러오는 중…</p> : <>
        <div className="table-scroll"><table className="work-table operations-table analysis-table"><thead><tr><th>청구서</th><th>제출 차수</th><th>분석 상태</th><th>실행 횟수</th><th>다음 예약</th><th>최근 오류</th><th>발행</th></tr></thead>
          <tbody>{data.items.map(row => <tr key={row.runId}><td><button className="button" disabled={locked} onClick={() => {
            setSelected(row.runId);setHistoryPage(0);setReason('');
          }}>{row.invoiceNumber}</button></td><td>{row.inputVersion}</td><td>{labels[row.status] ?? row.status}</td>
            <td>{row.executionAttempt} / {row.attemptLimit}</td><td>{row.nextRetryAt ? formatInstant(row.nextRetryAt) : '—'}</td>
            <td>{row.lastErrorCode ?? '—'}</td><td>{({ READY: '발행 대기', CLAIMED: '발행 중', PUBLISHED: '발행 완료', CANCELLED: '취소' } as Record<string, string>)[row.publishStatus] ?? row.publishStatus}</td></tr>)}</tbody></table></div>
        {data.items.length === 0 && <p>표시할 분석 작업이 없습니다.</p>}
        <div className="list-tools"><button className="button" disabled={locked || page === 0} onClick={() => { setPage(page - 1);setSelected(null); }}>이전</button>
          <span>{page + 1} 페이지 · {data.totalElements}건</span><button className="button" disabled={locked || (page + 1) * 20 >= data.totalElements}
            onClick={() => { setPage(page + 1);setSelected(null); }}>다음</button></div>
      </>}
      {job && <section className="history-content analysis-history"><h2>{job.invoiceNumber} · 실패 이력</h2><Link href={`/cases/${job.caseId}`}>청구서 보기</Link>
        {history?.key === historyKey && history.error ? <p role="alert">{history.error}</p> : !failures ? <p>이력을 불러오는 중…</p> : <>
          <table className="history-table"><thead><tr><th>시도</th><th>오류</th><th>상태</th><th>발생 시각</th></tr></thead><tbody>{failures.items.map(row =>
            <tr key={`${row.executionAttempt}:${row.createdAt}`}><td>{row.executionAttempt}</td><td>{row.errorCode}</td><td>{labels[row.disposition] ?? row.disposition}</td><td>{formatInstant(row.createdAt)}</td></tr>)}</tbody></table>
          {!failures.items.length && <p>실패 이력이 없습니다.</p>}
          <button className="button" disabled={locked || historyPage === 0} onClick={() => setHistoryPage(historyPage - 1)}>이전 이력</button>
          <button className="button" disabled={locked || (historyPage + 1) * 20 >= failures.totalElements} onClick={() => setHistoryPage(historyPage + 1)}>다음 이력</button>
        </>}
        {job.status === 'FAILED' && <p>문서 내용을 확인하고 보완 제출해 주세요.</p>}
        {(job.status === 'DEAD_LETTERED' || retry.uncertain) && <div><label className="reason-label">원인 수정 사유<textarea aria-label="원인 수정 사유" value={reason} maxLength={300}
          disabled={locked} onChange={event => setReason(event.target.value)} /></label><button className="button" disabled={retry.pending || (!retry.uncertain && !reason.trim())}
            onClick={() => void retry.execute(job, reason)}>{retry.pending ? '확인 중…' : retry.uncertain ? '같은 요청으로 결과 확인' : '재처리 예약'}</button></div>}
      </section>}
      {retry.message && <p role="status">{retry.message}</p>}
    </>}
  </Shell>;
}
