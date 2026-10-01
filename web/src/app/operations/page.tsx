'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import { Icon, PageHeader, Shell } from '../ui';
import { useAuth } from '../auth';
import { fetchCaseHandoff } from '../api/client';
import { ApiRequestError } from '../api/transport';
import { formatInstant, presentStatus, type CaseHandoffStatus, type InvoiceCaseSummary } from '../api/contract';
import type { InvoiceCaseFilters } from '../api/query';
import { presentOutboxStatus, presentPaymentStatus } from '../cases/[id]/detail-model';
import { MutationFailureNotice } from '../cases/mutation-failure-notice';
import { useCaseActions } from '../cases/[id]/use-case-actions';
import { useInvoiceCases } from '../cases/use-invoice-cases';

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
    ? <section className="empty-state" role="status"><Icon name="clock" size={25} /><h1>로그인이 필요합니다</h1><p>로그인 화면으로 이동합니다.</p></section>
    : state === 'loading' && !pageResult
      ? <section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>운영 대상을 서버에서 확인하고 있습니다.</p></section>
      : state === 'forbidden'
        ? <section className="empty-state" role="status"><Icon name="document" size={25} /><h1>이 화면에 접근할 권한이 없습니다</h1><p>계정 역할을 확인해 주세요.</p></section>
        : state === 'error'
          ? <section className="empty-state" role="alert"><Icon name="document" size={25} /><h1>자료를 불러오지 못했습니다</h1><p>{error}</p><button className="button" onClick={() => setReloadToken((value) => value + 1)}>다시 시도</button></section>
          : <div className={`review-workbench ${selectedRow ? 'with-panel' : ''}`}>
              <div className="table-area">
                <div className="table-scroll" aria-busy={isLoading}>
                  <table className="work-table operations-table">
                    <caption className="sr-only">운영 작업 목록. 서버 상태 필터와 페이지 이동이 적용됩니다.</caption>
                    <thead><tr><th>청구 / 공급사</th><th>상태</th><th>제출 시각 (KST)</th><th>버전</th><th><span className="sr-only">선택</span></th></tr></thead>
                    <tbody>
                      {rows.map((row: InvoiceCaseSummary) => {
                        const presentation = presentStatus(row.status);
                        return <tr key={row.id} className={selected === row.id ? 'selected' : ''}>
                          <td><strong>{row.invoiceNumber}</strong><small className="secondary-line">{row.supplierId}</small></td>
                          <td><span className={`case-status status-${presentation.tone}`}><span className="case-status-dot" />{presentation.label}</span></td>
                          <td className="muted-text">{formatInstant(row.submittedAt)}</td>
                          <td className="muted-text">v{row.version}</td>
                          <td><button className="row-detail" aria-label={`${row.invoiceNumber} 작업 근거`} aria-expanded={selected === row.id} onClick={() => setSelected(selected === row.id ? null : row.id)}><Icon name="chevron" size={14} /></button></td>
                        </tr>;
                      })}
                      {!rows.length && <tr><td colSpan={5} className="empty-table">조건에 맞는 작업이 없습니다.</td></tr>}
                    </tbody>
                  </table>
                </div>
                <div className="table-summary list-pagination">
                  <span>총 {pageResult?.totalItems ?? 0}건 · 서버 조회</span>
                  <nav className="pagination" aria-label="운영 목록 페이지">
                    <button className="button" disabled={page === 0} onClick={() => { setPage(page - 1); setSelected(null); }}>이전</button>
                    <button className="button" disabled={!pageResult?.hasNext} onClick={() => { setPage(page + 1); setSelected(null); }}>다음</button>
                  </nav>
                </div>
              </div>

              {selectedRow && <aside className="evidence-panel">
                <div className="panel-title">작업 근거<button className="icon-button" aria-label="운영 패널 닫기" onClick={() => setSelected(null)}><Icon name="close" /></button></div>
                <h2>{selectedRow.invoiceNumber}</h2>
                <p className="panel-subtitle">{selectedRow.supplierId}</p>
                <dl className="receipt-data">
                  <div><dt>상태</dt><dd>{presentStatus(selectedRow.status).label}</dd></div>
                  <div><dt>발주번호</dt><dd>{selectedRow.purchaseOrderId}</dd></div>
                  <div><dt>제출자</dt><dd>{selectedRow.submittedBy}</dd></div>
                  <div><dt>청구서 버전</dt><dd>v{selectedRow.version}</dd></div>
                </dl>

                {selectedRow.status === 'REVIEW_PENDING' && isOperator && (
                  <div className="dialog-actions">
                    <button className="button primary" disabled={matchPending || matchBlocked} onClick={() => actions.runMatch()}>{matchPending ? '대사 실행 중…' : '대사 실행'}</button>
                  </div>
                )}
                {['EXPORT_PENDING', 'EXPORTED'].includes(selectedRow.status) && (
                  <div className="panel-status" aria-busy={handoffLoad.status === 'loading'}>
                    <strong>ERP 인계 상태</strong>
                    {handoffLoad.status === 'loading' && <p>서버에서 인계 상태를 확인하고 있습니다.</p>}
                    {handoffLoad.status === 'forbidden' && <p>이 사건의 인계 조회 권한이 없습니다.</p>}
                    {handoffLoad.status === 'error' && <p>{handoffLoad.message}</p>}
                    {handoffLoad.status === 'ready' && (handoffLoad.handoff.payment
                      ? <p>지급 {presentPaymentStatus(handoffLoad.handoff.payment.paymentStatus)} · 아웃박스 {handoffLoad.handoff.payment.outboxStatus ? presentOutboxStatus(handoffLoad.handoff.payment.outboxStatus) : '—'} · 시도 {handoffLoad.handoff.payment.attemptCount}회{handoffLoad.handoff.payment.lastErrorCode ? ` · 오류 ${handoffLoad.handoff.payment.lastErrorCode}` : ''}</p>
                      : <p>아직 승인·인계 전입니다. 지급요청이 없습니다.</p>)}
                  </div>
                )}

                <div className="dialog-actions">
                  <Link className="button" href={`/cases/${selectedRow.id}`}>청구서 상세</Link>
                  {['EXPORT_PENDING', 'EXPORTED'].includes(selectedRow.status) && <Link className="button" href={`/handoff?case=${selectedRow.id}`}>ERP 인계 상세</Link>}
                </div>
                {actions.failure && <MutationFailureNotice failure={actions.failure} />}
                {actions.unresolved && (
                  <div className="dialog-actions">
                    <button className="button primary" disabled={actions.pendingAction !== null} onClick={() => actions.retry()}>같은 요청 다시 시도</button>
                    <button className="button" disabled={actions.pendingAction !== null} onClick={() => { actions.clearFailure(); setReloadToken((value) => value + 1); }}>최신 자료 다시 조회</button>
                  </div>
                )}
                <p className="panel-footnote">자동 재전송·재처리·queue/DLQ 지표는 제공하지 않습니다. 결과불명은 운영자가 별도로 확인합니다.</p>
              </aside>}
            </div>;

  return (
    <Shell active="operations" preview={false}>
      <PageHeader eyebrow="운영 업무" title="운영 작업" subtitle="대사 대상과 ERP 인계 상태를 실제 서버 계약으로 조회합니다." />
      <div className="tabs" role="tablist" aria-label="운영 작업 상태">
        {tabs.map(([id, label]) => <button key={id} role="tab" aria-selected={status === id} className={status === id ? 'active' : ''} onClick={() => { setStatus(id); setPage(0); setSelected(null); }}>{label}</button>)}
      </div>
      {!isOperator && <div className="review-warning" role="status"><div><strong>운영자 역할이 아닙니다</strong><p>대사 실행은 운영자만 가능합니다. 목록 조회는 역할에 따라 서버가 범위를 정합니다.</p></div></div>}
      <div className="list-tools"><span className="muted-text">서버 상태 필터 · 자동 재전송 없음</span></div>
      {body}
    </Shell>
  );
}

export default function OperationsPage() {
  return (
    <Suspense fallback={<Shell active="operations" preview={false}><section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>운영 대상을 확인하고 있습니다.</p></section></Shell>}>
      <Operations />
    </Suspense>
  );
}

