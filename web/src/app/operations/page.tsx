'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import { Icon, PageHeader, Shell } from '../ui';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuth } from '../auth';
import { fetchCaseHandoff } from '../api/client';
import { ApiRequestError } from '../api/transport';
import { formatInstant, presentStatus, type CaseHandoffStatus, type InvoiceCaseSummary } from '../api/contract';
import type { InvoiceCaseFilters } from '../api/query';
import { presentOutboxStatus, presentPaymentStatus } from '../cases/[id]/detail-model';
import { MutationFailureNotice } from '../cases/mutation-failure-notice';
import { useCaseActions } from '../cases/[id]/use-case-actions';
import { useInvoiceCases } from '../cases/use-invoice-cases';

type StatusTone = 'neutral' | 'pending' | 'attention' | 'complete' | 'rejected';

const tabs: Array<[string, string]> = [
  ['REVIEW_PENDING', '대사 대상'],
  ['EXPORT_PENDING', '인계 대기'],
  ['EXPORTED', '인계 완료'],
];

function baseFilters(status: string): InvoiceCaseFilters {
  return {
    status,
    supplierId: null,
    submittedBy: null,
    submittedFrom: null,
    submittedTo: null,
    searchField: 'invoiceNumber',
    searchValue: null,
    page: 0,
    size: 20,
  };
}

type HandoffLoad =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; handoff: CaseHandoffStatus }
  | { status: 'forbidden' }
  | { status: 'error'; message: string };

function Operations() {
  const router = useRouter();
  const { credentials, user, isAuthenticated, sessionId, logout } = useAuth();
  const isOperator = (user?.roles ?? []).includes('OPERATOR');

  const [status, setStatus] = useState('REVIEW_PENDING');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const [handoffState, setHandoffState] = useState<{ key: string | null; load: HandoffLoad }>({ key: null, load: { status: 'idle' } });

  const onUnauthorized = useCallback(() => {
    logout();
    router.replace('/login');
  }, [logout, router]);

  useEffect(() => {
    if (!isAuthenticated) router.replace('/login');
  }, [isAuthenticated, router]);

  const filters = useMemo(() => ({ ...baseFilters(status), page }), [status, page]);
  const { state, page: pageResult, error, isLoading } = useInvoiceCases({ credentials, sessionId, filters, reloadToken, onUnauthorized });

  const rows = pageResult?.items ?? [];
  const selectedRow = rows.find((row) => row.id === selected) ?? null;
  const selectedId = selectedRow?.id ?? null;
  const selectedStatus = selectedRow?.status ?? null;
  const handoffKey = selectedId && selectedStatus && ['EXPORT_PENDING', 'EXPORTED'].includes(selectedStatus)
    ? `${sessionId}#${selectedId}#${selectedStatus}`
    : null;
  if (handoffState.key !== handoffKey) {
    setHandoffState({ key: handoffKey, load: handoffKey ? { status: 'loading' } : { status: 'idle' } });
  }
  const handoffLoad: HandoffLoad = handoffState.key === handoffKey ? handoffState.load : (handoffKey ? { status: 'loading' } : { status: 'idle' });

  // Selecting an exported/pending case reads exactly one handoff; the list is
  // never fanned out into per-row handoff calls.
  useEffect(() => {
    if (!credentials || !selectedId || !handoffKey) return;
    let cancelled = false;
    const key = handoffKey;
    fetchCaseHandoff(credentials, selectedId)
      .then((handoff) => { if (!cancelled) setHandoffState({ key, load: { status: 'ready', handoff } }); })
      .catch((caught) => {
        if (cancelled) return;
        if (caught instanceof ApiRequestError && caught.status === 401) { onUnauthorized(); return; }
        if (caught instanceof ApiRequestError && caught.status === 403) { setHandoffState({ key, load: { status: 'forbidden' } }); return; }
        setHandoffState({ key, load: { status: 'error', message: caught instanceof Error ? caught.message : '인계 상태를 불러오지 못했습니다.' } });
      });
    return () => { cancelled = true; };
  }, [credentials, selectedId, handoffKey, sessionId, onUnauthorized]);

  // Match runs through the shared actions hook so its intent/id are frozen and
  // an uncertain failure is retried with the exact same request id (never a new
  // one), and a late response from a replaced session/case is dropped.
  const actions = useCaseActions({
    credentials,
    sessionId,
    caseId: selectedId ?? '',
    onUnauthorized,
    onCompleted: () => setReloadToken((value) => value + 1),
  });
  const matchPending = actions.pendingAction !== null && actions.pendingAction === 'match';
  const matchBlocked = actions.pendingAction !== null || actions.unresolved !== null;

  const body = !isAuthenticated
    ? <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">로그인이 필요합니다</h1><p className="text-label">로그인 화면으로 이동합니다.</p></section>
    : state === 'loading' && !pageResult
      ? <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">불러오는 중</h1><p className="text-label">운영 대상을 서버에서 확인하고 있습니다.</p></section>
      : state === 'forbidden'
        ? <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status"><Icon name="document" size={25} /><h1 className="text-base font-normal text-[#6f7863]">이 화면에 접근할 권한이 없습니다</h1><p className="text-label">계정 역할을 확인해 주세요.</p></section>
        : state === 'error'
          ? <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="alert"><Icon name="document" size={25} /><h1 className="text-base font-normal text-[#6f7863]">자료를 불러오지 못했습니다</h1><p className="text-label">{error}</p><Button variant="outline" onClick={() => setReloadToken((value) => value + 1)}>다시 시도</Button></section>
          : <div className={`review-workbench flex items-stretch border-t border-border ${selectedRow ? 'with-panel max-[760px]:block' : ''}`}>
              <div className="table-area min-w-0 flex-1">
                <Table className="work-table operations-table min-w-[630px] w-full whitespace-nowrap text-sm" aria-busy={isLoading}>
                  <caption className="sr-only">운영 작업 목록. 서버 상태 필터와 페이지 이동이 적용됩니다.</caption>
                  <TableHeader><TableRow className="border-0"><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">청구 / 공급사</TableHead><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">상태</TableHead><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">제출 시각 (KST)</TableHead><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5">버전</TableHead><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-muted-foreground min-[1200px]:px-5"><span className="sr-only">선택</span></TableHead></TableRow></TableHeader>
                  <TableBody>
                    {rows.map((row: InvoiceCaseSummary) => {
                      const presentation = presentStatus(row.status);
                      return <TableRow key={row.id} className={`border-0 ${selected === row.id ? 'selected bg-mint' : 'hover:bg-[#fafbf6]'}`}>
                        <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] min-[1200px]:px-5"><strong className="text-sm">{row.invoiceNumber}</strong><small className="secondary-line mt-1.5 block text-label text-muted-foreground">{row.supplierId}</small></TableCell>
                        <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] min-[1200px]:px-5"><Badge variant={presentation.tone as StatusTone} className="case-status gap-1.5 px-2 py-[5px] text-label"><span className="case-status-dot h-[5px] w-[5px] rounded-full bg-current" />{presentation.label}</Badge></TableCell>
                        <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] text-label text-muted-foreground min-[1200px]:px-5">{formatInstant(row.submittedAt)}</TableCell>
                        <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] text-label text-muted-foreground min-[1200px]:px-5">v{row.version}</TableCell>
                        <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] min-[1200px]:px-5"><button className="row-detail grid h-7 w-6 place-items-center border-0 bg-transparent text-muted-foreground hover:bg-[#e5ece1] hover:text-[#3a513e]" aria-label={`${row.invoiceNumber} 작업 근거`} aria-expanded={selected === row.id} onClick={() => setSelected(selected === row.id ? null : row.id)}><Icon name="chevron" size={14} /></button></TableCell>
                      </TableRow>;
                    })}
                    {!rows.length && <TableRow className="border-0"><TableCell colSpan={5} className="empty-table p-12 text-center text-muted-foreground">조건에 맞는 작업이 없습니다.</TableCell></TableRow>}
                  </TableBody>
                </Table>
                <div className="table-summary list-pagination flex min-h-[70px] flex-wrap justify-between gap-3.5 border-b border-[#efefe9] px-[25px] py-4 text-label text-muted-foreground">
                  <span>총 {pageResult?.totalItems ?? 0}건 · 서버 조회</span>
                  <nav className="pagination flex items-center gap-[5px]" aria-label="운영 목록 페이지">
                    <Button variant="outline" disabled={page === 0} onClick={() => { setPage(page - 1); setSelected(null); }}>이전</Button>
                    <Button variant="outline" disabled={!pageResult?.hasNext} onClick={() => { setPage(page + 1); setSelected(null); }}>다음</Button>
                  </nav>
                </div>
              </div>

              {selectedRow && <aside className="evidence-panel w-full shrink-0 border-t border-border bg-[#fdfdfb] px-[22px] py-[17px] min-[760px]:w-[280px] min-[760px]:border-l min-[760px]:border-t-0 min-[1200px]:w-[300px]">
                <div className="panel-title mb-[22px] flex items-center justify-between text-label text-muted-foreground">작업 근거<button className="icon-button grid place-items-center rounded-sm border-0 bg-transparent p-1 text-[#838776] hover:bg-[#eee]" aria-label="운영 패널 닫기" onClick={() => setSelected(null)}><Icon name="close" /></button></div>
                <h2 className="text-detail font-medium leading-[1.6] tracking-[-.3px]">{selectedRow.invoiceNumber}</h2>
                <p className="panel-subtitle mt-1.5 text-label text-muted-foreground">{selectedRow.supplierId}</p>
                <dl className="receipt-data my-[22px] text-label">
                  <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">상태</dt><dd className="m-0">{presentStatus(selectedRow.status).label}</dd></div>
                  <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">발주번호</dt><dd className="m-0">{selectedRow.purchaseOrderId}</dd></div>
                  <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">제출자</dt><dd className="m-0">{selectedRow.submittedBy}</dd></div>
                  <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">청구서 버전</dt><dd className="m-0">v{selectedRow.version}</dd></div>
                </dl>

                {selectedRow.status === 'REVIEW_PENDING' && isOperator && (
                  <div className="dialog-actions mt-[25px] flex justify-end gap-2">
                    <Button disabled={matchPending || matchBlocked} onClick={() => actions.runMatch()}>{matchPending ? '대사 실행 중…' : '대사 실행'}</Button>
                  </div>
                )}
                {['EXPORT_PENDING', 'EXPORTED'].includes(selectedRow.status) && (
                  <div className="panel-status mt-6 border-y border-border py-[19px] text-label text-[#66755a]" aria-busy={handoffLoad.status === 'loading'}>
                    <strong className="text-label">ERP 인계 상태</strong>
                    {handoffLoad.status === 'loading' && <p className="mt-[7px] text-label leading-[1.8] text-muted-foreground">서버에서 인계 상태를 확인하고 있습니다.</p>}
                    {handoffLoad.status === 'forbidden' && <p className="mt-[7px] text-label leading-[1.8] text-muted-foreground">이 사건의 인계 조회 권한이 없습니다.</p>}
                    {handoffLoad.status === 'error' && <p className="mt-[7px] text-label leading-[1.8] text-muted-foreground">{handoffLoad.message}</p>}
                    {handoffLoad.status === 'ready' && (handoffLoad.handoff.payment
                      ? <p className="mt-[7px] text-label leading-[1.8] text-muted-foreground">지급 {presentPaymentStatus(handoffLoad.handoff.payment.paymentStatus)} · 아웃박스 {handoffLoad.handoff.payment.outboxStatus ? presentOutboxStatus(handoffLoad.handoff.payment.outboxStatus) : '—'} · 시도 {handoffLoad.handoff.payment.attemptCount}회{handoffLoad.handoff.payment.lastErrorCode ? ` · 오류 ${handoffLoad.handoff.payment.lastErrorCode}` : ''}</p>
                      : <p className="mt-[7px] text-label leading-[1.8] text-muted-foreground">아직 승인·인계 전입니다. 지급요청이 없습니다.</p>)}
                  </div>
                )}

                <div className="dialog-actions mt-[25px] flex justify-end gap-2">
                  <Button asChild variant="outline"><Link href={`/cases/${selectedRow.id}`}>청구서 상세</Link></Button>
                  {['EXPORT_PENDING', 'EXPORTED'].includes(selectedRow.status) && <Button asChild variant="outline"><Link href={`/handoff?case=${selectedRow.id}`}>ERP 인계 상세</Link></Button>}
                </div>
                {actions.failure && <MutationFailureNotice failure={actions.failure} />}
                {actions.unresolved && (
                  <div className="dialog-actions mt-[25px] flex justify-end gap-2">
                    <Button disabled={actions.pendingAction !== null} onClick={() => actions.retry()}>같은 요청 다시 시도</Button>
                    <Button variant="outline" disabled={actions.pendingAction !== null} onClick={() => { actions.clearFailure(); setReloadToken((value) => value + 1); }}>최신 자료 다시 조회</Button>
                  </div>
                )}
                <p className="panel-footnote text-label leading-[1.9] text-muted-foreground">자동 재전송·재처리·queue/DLQ 지표는 제공하지 않습니다. 결과불명은 운영자가 별도로 확인합니다.</p>
              </aside>}
            </div>;

  return (
    <Shell active="operations" preview={false}>
      <PageHeader eyebrow="운영 업무" title="운영 작업" subtitle="대사 대상과 ERP 인계 상태를 조회합니다." action={<Button asChild variant="outline"><Link href="/operations/analysis">문서 분석 운영</Link></Button>} />
      <div className="tabs flex h-[53px] gap-[31px] border-b border-border px-5 min-[760px]:px-[30px] min-[1200px]:px-11 min-[1600px]:px-14" role="tablist" aria-label="운영 작업 상태">
        {tabs.map(([id, label]) => <button key={id} role="tab" aria-selected={status === id} className={`flex items-center gap-2 border-x-0 border-t-0 border-b-2 bg-transparent px-1.5 text-label ${status === id ? 'active border-b-[#686c5e] text-ink' : 'border-b-transparent text-[#858579]'}`} onClick={() => { setStatus(id); setPage(0); setSelected(null); }}>{label}</button>)}
      </div>
      {!isOperator && <div className="review-warning mx-[30px] mb-3 flex items-center justify-between gap-3 border border-[#e8ddae] bg-[#faf4df] px-4 py-3 text-label" role="status"><div><strong>운영자 역할이 아닙니다</strong><p className="mt-1 text-[#716446]">대사 실행은 운영자만 가능합니다. 목록 조회는 역할에 따라 서버가 범위를 정합니다.</p></div></div>}
      <div className="list-tools flex min-h-[72px] flex-wrap items-center justify-between gap-5 border-b border-border px-5 py-3 min-[760px]:px-8 min-[1600px]:px-11"><span className="muted-text text-label text-muted-foreground">서버 상태 필터 · 자동 재전송 없음</span></div>
      {body}
    </Shell>
  );
}

export default function OperationsPage() {
  return (
    <Suspense fallback={<Shell active="operations" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">불러오는 중</h1><p className="text-label">운영 대상을 확인하고 있습니다.</p></section></Shell>}>
      <Operations />
    </Suspense>
  );
}
