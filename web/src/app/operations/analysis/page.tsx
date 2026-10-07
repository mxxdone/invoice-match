'use client';

import { NativeSelect } from '@/components/ui/native-select';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../../auth';
import { PageHeader, Shell } from '../../ui';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
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
      action={<Button asChild variant="outline"><Link href="/operations">운영 작업</Link></Button>} />
    {!enabled ? <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-3.75 px-5 py-10 text-center text-muted-foreground"><h2 className="text-base font-normal text-muted-foreground">운영자 권한이 필요합니다.</h2></section> : <>
      <div className="list-tools flex min-h-18 flex-wrap items-center justify-between gap-5 border-b border-border px-5 py-3 min-[760px]:px-8 min-[1600px]:px-11"><label className="flex items-center gap-2.5 text-label text-muted-foreground">상태 <NativeSelect aria-label="분석 상태"  disabled={locked} value={status} onChange={event => {
        setStatus(event.target.value);setPage(0);setSelected(null);setHistoryPage(0);
      }} density="default"><option value="">전체</option>{Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</NativeSelect></label>
        <Button variant="outline" disabled={locked} onClick={() => setReload(value => value + 1)}>새로고침</Button></div>
      {jobs?.key === key && jobs.error ? <p role="alert" className="text-label">{jobs.error}</p> : !data ? <p role="status" className="text-label">불러오는 중…</p> : <>
        <Table className="work-table operations-table analysis-table min-w-[630px] w-full whitespace-nowrap text-sm"><TableHeader><TableRow className="border-0"><TableHead className="border-b border-border bg-table-heading px-3.5 py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">청구서</TableHead><TableHead className="border-b border-border bg-table-heading px-3.5 py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">제출 차수</TableHead><TableHead className="border-b border-border bg-table-heading px-3.5 py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">분석 상태</TableHead><TableHead className="border-b border-border bg-table-heading px-3.5 py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">실행 횟수</TableHead><TableHead className="border-b border-border bg-table-heading px-3.5 py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">다음 예약</TableHead><TableHead className="border-b border-border bg-table-heading px-3.5 py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">최근 오류</TableHead><TableHead className="border-b border-border bg-table-heading px-3.5 py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">발행</TableHead></TableRow></TableHeader>
          <TableBody>{data.items.map(row => <TableRow key={row.runId} className="border-0"><TableCell className="h-17 border-b border-border px-3.5 py-3.75 min-[1200px]:px-5"><Button variant="outline" className="max-w-[280px] [overflow-wrap:anywhere]" disabled={locked} onClick={() => {
            setSelected(row.runId);setHistoryPage(0);setReason('');
          }} size="content">{row.invoiceNumber}</Button></TableCell><TableCell className="h-17 border-b border-border px-3.5 py-3.75 text-label text-muted-foreground min-[1200px]:px-5">{row.inputVersion}</TableCell><TableCell className="h-17 border-b border-border px-3.5 py-3.75 text-label text-muted-foreground min-[1200px]:px-5">{labels[row.status] ?? row.status}</TableCell>
            <TableCell className="h-17 border-b border-border px-3.5 py-3.75 text-label text-muted-foreground min-[1200px]:px-5">{row.executionAttempt} / {row.attemptLimit}</TableCell><TableCell className="h-17 border-b border-border px-3.5 py-3.75 text-label text-muted-foreground min-[1200px]:px-5">{row.nextRetryAt ? formatInstant(row.nextRetryAt) : '—'}</TableCell>
            <TableCell className="h-17 border-b border-border px-3.5 py-3.75 text-label text-muted-foreground min-[1200px]:px-5">{row.lastErrorCode ?? '—'}</TableCell><TableCell className="h-17 border-b border-border px-3.5 py-3.75 text-label text-muted-foreground min-[1200px]:px-5">{({ READY: '발행 대기', CLAIMED: '발행 중', PUBLISHED: '발행 완료', CANCELLED: '취소' } as Record<string, string>)[row.publishStatus] ?? row.publishStatus}</TableCell></TableRow>)}</TableBody></Table>
        {data.items.length === 0 && <p className="text-label">표시할 분석 작업이 없습니다.</p>}
        <div className="list-tools flex min-h-18 flex-wrap items-center gap-5 border-b border-border px-5 py-3 min-[760px]:px-8 min-[1600px]:px-11"><Button variant="outline" disabled={locked || page === 0} onClick={() => { setPage(page - 1);setSelected(null); }}>이전</Button>
          <span className="text-label">{page + 1} 페이지 · {data.totalElements}건</span><Button variant="outline" disabled={locked || (page + 1) * 20 >= data.totalElements}
            onClick={() => { setPage(page + 1);setSelected(null); }}>다음</Button></div>
      </>}
      {job && <section className="history-content analysis-history overflow-x-auto px-5 py-6.25 min-[760px]:px-10 min-[760px]:py-8"><h2 className="mb-4 text-section font-medium [overflow-wrap:anywhere]">{job.invoiceNumber} · 실패 이력</h2><Link className="text-olive underline underline-offset-4" href={`/cases/${job.caseId}`}>청구서 보기</Link>
        {history?.key === historyKey && history.error ? <p role="alert" className="text-label">{history.error}</p> : !failures ? <p className="text-label">이력을 불러오는 중…</p> : <>
          <Table className="history-table w-full text-sm"><TableHeader><TableRow className="border-0"><TableHead className="border-b border-border p-3 text-label font-normal text-muted-foreground">시도</TableHead><TableHead className="border-b border-border p-3 text-label font-normal text-muted-foreground">오류</TableHead><TableHead className="border-b border-border p-3 text-label font-normal text-muted-foreground">상태</TableHead><TableHead className="border-b border-border p-3 text-label font-normal text-muted-foreground">발생 시각</TableHead></TableRow></TableHeader><TableBody>{failures.items.map(row =>
            <TableRow key={`${row.executionAttempt}:${row.createdAt}`} className="border-0"><TableCell className="border-b border-border px-3 py-5">{row.executionAttempt}</TableCell><TableCell className="border-b border-border px-3 py-5">{row.errorCode}</TableCell><TableCell className="border-b border-border px-3 py-5">{labels[row.disposition] ?? row.disposition}</TableCell><TableCell className="border-b border-border px-3 py-5">{formatInstant(row.createdAt)}</TableCell></TableRow>)}</TableBody></Table>
          {!failures.items.length && <p className="text-label">실패 이력이 없습니다.</p>}
          <Button variant="outline" disabled={locked || historyPage === 0} onClick={() => setHistoryPage(historyPage - 1)}>이전 이력</Button>
          <Button variant="outline" disabled={locked || (historyPage + 1) * 20 >= failures.totalElements} onClick={() => setHistoryPage(historyPage + 1)}>다음 이력</Button>
        </>}
        {job.status === 'FAILED' && <p className="text-label">문서 내용을 확인하고 보완 제출해 주세요.</p>}
        {(job.status === 'DEAD_LETTERED' || retry.uncertain) && <div><label className="reason-label my-5 block max-w-[580px] text-label text-muted-foreground">원인 수정 사유<Textarea className="mt-2.5" aria-label="원인 수정 사유" value={reason} maxLength={300}
          disabled={locked} onChange={event => setReason(event.target.value)} /></label><Button variant="outline" disabled={retry.pending || (!retry.uncertain && !reason.trim())}
            onClick={() => void retry.execute(job, reason)}>{retry.pending ? '확인 중…' : retry.uncertain ? '같은 요청으로 결과 확인' : '재처리 예약'}</Button></div>}
      </section>}
      {retry.message && <p role="status" className="text-label">{retry.message}</p>}
    </>}
  </Shell>;
}
