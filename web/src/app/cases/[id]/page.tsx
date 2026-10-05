'use client';

import Link from 'next/link';
import { useParams, useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useCallback, useEffect, useState } from 'react';
import { Icon, Shell } from '../../ui';
import { useAuth } from '../../auth';
import { formatInstant, presentStatus } from '../../api/contract';
import type { EvidenceBundleSummary } from '../../api/contract';
import { canReadReview, comparisonRows, decisionSubjectPayload, detailTabHref, detailTabs, latestBundle, resolveDetailTab, reviewSubjectBinding, type DetailTab } from './detail-model';
import {
  AuditPanel,
  ComparePanel,
  DecisionsPanel,
  EvidencePanel,
  HandoffStrip,
  HandoffSummary,
  SectionMessage,
} from './detail-sections';
import { useCaseDetail } from './use-case-detail';
import { useCaseActions } from './use-case-actions';
import { MutationFailureNotice } from '../mutation-failure-notice';
import { OriginalDocuments } from './original-documents';
import { useProposalReview } from './use-proposal-review';
import { ProposalPanel } from './proposal-panel';
import { eligibleProposal, frozenProposal } from './proposal-model';
import { useGraphActionIdentity, useGraphReview, useGraphRun } from './use-graph-review';
import { GraphReviewPanel } from './graph-review-panel';
import { eligibleGraphProposal } from './graph-model';
import type { SelectedProposal } from '../../api/contract';

function Detail() {
  const params = useParams<{ id: string }>();
  const caseId = params?.id ?? '';
  const searchParams = useSearchParams();
  const router = useRouter();
  const { credentials, user, isAuthenticated, sessionId, logout } = useAuth();
  const reviewReader = canReadReview(user?.roles ?? []);
  const isOperator = (user?.roles ?? []).includes('OPERATOR');
  const isApprover = (user?.roles ?? []).includes('APPROVER');
  // The URL is the single authority for the selected tab: it is resolved on
  // every render from the account roles + `?tab=`, so a session/role/case change
  // or a `?tab=` edit can never keep a stale local tab, and there is no separate
  // tab state to drift from the URL. Clicking a tab updates the URL (preserving
  // other query params such as `from`) without touching the detail fetch or any
  // pending write intent; the default is the role-allowed first tab.
  const roles = user?.roles ?? [];
  const tabs = detailTabs(roles);
  const allowedTabIds: string[] = tabs.map(([id]) => id);
  const requestedTab = searchParams.get('tab');
  const resolvedTab = resolveDetailTab(roles, requestedTab);
  const tab = resolvedTab.tab;
  const unsupportedTab = resolvedTab.unsupported;
  const selectTab = (id: DetailTab) => {
    if (!allowedTabIds.includes(id)) return;
    router.replace(`/cases/${caseId}?${detailTabHref(searchParams.toString(), id)}`, { scroll: false });
  };
  const [reloadToken, setReloadToken] = useState(0);
  const [reasonPanel, setReasonPanel] = useState<'supplement' | 'reject' | null>(null);
  const [reason, setReason] = useState('');
  const [mappingLine, setMappingLine] = useState('');
  const [mappingItem, setMappingItem] = useState('');
  const [proposalSelection, setProposalSelection] = useState<(SelectedProposal & { identity: string; kind: 'v1' | 'graph' }) | null>(null);
  const [graphSelectedId, setGraphSelectedId] = useState<string | null>(null);
  const identity = `${sessionId}#${caseId}`;
  const [proposalIdentity, setProposalIdentity] = useState(identity);
  if (proposalIdentity !== identity) {
    setProposalIdentity(identity);
    setProposalSelection(null);
    setGraphSelectedId(null);
  }

  const from = searchParams.get('from');
  const listHref = from ? `/cases?${from}` : '/cases';

  const onUnauthorized = useCallback(() => {
    logout();
    router.replace('/login');
  }, [logout, router]);

  useEffect(() => {
    if (!isAuthenticated) router.replace('/login');
  }, [isAuthenticated, router]);

  const { load, isLoading, auditEntries, auditNextCursor, auditLoadingMore, auditError, loadMoreAudit } = useCaseDetail({
    credentials,
    sessionId,
    caseId,
    canReadReview: reviewReader,
    reloadToken,
    onUnauthorized,
  });

  const proposalLoad = useProposalReview({ credentials, sessionId, caseId, enabled: reviewReader && isAuthenticated, reloadToken, onUnauthorized });
  const graphLoad = useGraphReview({ credentials, sessionId, caseId, enabled: reviewReader && isAuthenticated, reloadToken, onUnauthorized });
  const graphRun = useGraphRun({ credentials, sessionId, caseId, graphId: graphSelectedId, enabled: reviewReader && isAuthenticated, reloadToken, onUnauthorized });
  const graphPage = graphLoad.status === 'ready' ? graphLoad.data : null;
  const graphLatest = graphPage?.latest ?? null;
  const graphView = graphSelectedId === null ? graphLatest : (graphRun.status === 'ready' ? graphRun.data : null);
  const graphViewLoading = graphSelectedId !== null && graphRun.status === 'loading';
  const graphViewError = graphSelectedId === null
    ? null
    : graphRun.status === 'error' ? graphRun.message
      : graphRun.status === 'forbidden' ? '선택한 그래프 실행을 조회할 권한이 없습니다.'
        : null;
  // The exact waiting identity: a server run/interrupt/review change or an
  // explicit history selection invalidates only graph intents in the action
  // hook; a read that is only loading does not, so an uncertain confirm keeps
  // its exact retry. The page's own pending write is never touched.
  const graphIdentity = useGraphActionIdentity(graphSelectedId, graphView);
  // Successful writes re-read the authoritative case instead of applying the
  // response locally, so the displayed subject always matches the server.
  const onCompleted = useCallback(() => setReloadToken((value) => value + 1), []);
  const actions = useCaseActions({ credentials, sessionId, caseId, graphIdentity, onUnauthorized, onCompleted });

  if (!isAuthenticated) {
    return <Shell active="cases" preview={false}><section className="empty-state" role="status"><Icon name="clock" size={25} /><h1>로그인이 필요합니다</h1><p>로그인 화면으로 이동합니다.</p></section></Shell>;
  }
  if (isLoading) {
    return <Shell active="cases" preview={false}><section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>청구서 상세를 서버에서 확인하고 있습니다.</p></section></Shell>;
  }
  if (load.status === 'notFound') {
    return <Shell active="cases" preview={false}><section className="empty-state" role="status"><Icon name="document" size={25} /><h1>청구서를 찾을 수 없습니다</h1><p>요청한 사건이 없거나 삭제되었습니다.</p><Link className="button" href={listHref}>목록으로</Link></section></Shell>;
  }
  if (load.status === 'forbidden') {
    return <Shell active="cases" preview={false}><section className="empty-state" role="status"><Icon name="document" size={25} /><h1>이 청구서를 볼 권한이 없습니다</h1><p>서버가 이 계정의 사건 조회를 허용하지 않았습니다.</p><Link className="button" href={listHref}>목록으로</Link></section></Shell>;
  }
  if (load.status === 'error') {
    return <Shell active="cases" preview={false}><section className="empty-state" role="alert"><Icon name="document" size={25} /><h1>자료를 불러오지 못했습니다</h1><p>{load.message}</p><button className="button" onClick={() => setReloadToken((value) => value + 1)}>다시 시도</button></section></Shell>;
  }
  if (load.status !== 'ready') {
    return <Shell active="cases" preview={false}><section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>청구서 상세를 서버에서 확인하고 있습니다.</p></section></Shell>;
  }

  const data = load.data;
  const status = presentStatus(data.detail.status);
  const bundles: EvidenceBundleSummary[] = data.bundles.status === 'ready' ? data.bundles.data : [];
  const newest = latestBundle(bundles);
  const handoff = data.handoff;
  const payment = handoff.status === 'ready' ? handoff.data.payment : null;
  // The server is the authority for ownership; this only decides whether to
  // offer the authoring entry. A wrong guess is still rejected by the API.
  const ownerSubmitter = Boolean(user?.username) && user?.username === data.detail.submittedBy;

  const snapshot = data.snapshot.status === 'ready' ? data.snapshot.data : null;
  const fresh = data.freshness.status === 'ready' ? data.freshness.data.current : null;
  const match = data.match.status === 'ready' ? data.match.data : null;
  // A decision is only offered when the displayed comparison is exactly the
  // frozen subject's own facts; a B snapshot + current flag alone is not enough.
  const binding = reviewSubjectBinding({
    snapshot,
    freshnessCurrent: fresh,
    match,
    currentBundleVersion: newest?.version ?? null,
    currentBundleHash: newest?.payloadHash ?? null,
  });
  const subjectReady = binding.bound;
  const candidateProof = proposalLoad.status === 'ready' ? eligibleProposal(proposalLoad.data.latest) : null;
  const graphCandidateProof = eligibleGraphProposal(graphLatest);
  // Exactly one advisory source may be chosen as the frozen proof: the selected
  // kind's own current candidate must still match, so switching sources or a
  // newer server state clears the stale selection instead of mixing them.
  const selectedCandidateProof = proposalSelection?.kind === 'graph' ? graphCandidateProof : candidateProof;
  const selectedProof = proposalSelection?.identity === identity && selectedCandidateProof?.proposalId === proposalSelection.proposalId && selectedCandidateProof?.proposalHash === proposalSelection.proposalHash ? selectedCandidateProof : null;
  const frozenProof = snapshot ? frozenProposal(snapshot.payload) : null;
  const sameAdvisory = selectedProof ? frozenProof?.proposalId === selectedProof.proposalId && frozenProof?.proposalHash === selectedProof.proposalHash : frozenProof === null;
  const mappingRows = match ? comparisonRows(match) : [];
  const actionInFlight = actions.pendingAction !== null;
  const actionUnresolved = actions.unresolved !== null;
  // In-flight and unresolved are distinct: while a request is in flight every
  // action is blocked, but an unresolved request only blocks *other* intents and
  // leaves the exact retry enabled.
  const actionBlocked = actionInFlight || actionUnresolved;

  async function doFreeze() {
    if (!newest) return;
    await actions.freezeSnapshot(data.detail.version, selectedProof ?? undefined);
  }
  async function doMapping() {
    if (!snapshot || !subjectReady) return;
    const line = Number(mappingLine);
    const itemId = mappingItem.trim();
    if (!Number.isInteger(line) || line < 1 || !itemId) return;
    if (await actions.recordMapping({
      ...decisionSubjectPayload(data.detail.version, snapshot),
      lineNumber: line,
      itemId,
    })) {
      setMappingLine('');
      setMappingItem('');
    }
  }
  async function doSupplement() {
    if (!snapshot || !subjectReady || !reason.trim()) return;
    if (await actions.requestSupplement({
      ...decisionSubjectPayload(data.detail.version, snapshot),
      reason: reason.trim(),
    })) {
      setReason('');
      setReasonPanel(null);
    }
  }
  async function doReject() {
    if (!snapshot || !subjectReady || !reason.trim()) return;
    if (await actions.reject({
      ...decisionSubjectPayload(data.detail.version, snapshot),
      reason: reason.trim(),
    })) {
      setReason('');
      setReasonPanel(null);
    }
  }
  async function doApprove() {
    if (!snapshot || !subjectReady) return;
    await actions.approve(decisionSubjectPayload(data.detail.version, snapshot));
  }

  return (
    <Shell active="cases" preview={false}>
      <header className="page-header">
        <nav className="breadcrumb" aria-label="현재 위치">
          <Link href={listHref}><Icon name="arrow" size={12} />청구서</Link>
          <Icon name="chevron" size={12} />
          <span aria-current="page">상세</span>
        </nav>
        <div className="title-row">
          <div>
            <h1>청구서 상세</h1>
            <p className="invoice-id">
              {data.detail.invoiceNumber}{' '}
              <span className={`case-status status-${status.tone}`}><span className="case-status-dot" />{status.label}</span>
            </p>
          </div>
          <span className="demo-tag">실제 서버 연결 · 역할별 동작</span>
        </div>
        <dl className="case-meta">
          <div><dt>공급사 ID</dt><dd>{data.detail.supplierId}</dd></div>
          <div><dt>발주번호</dt><dd>{data.detail.purchaseOrderId}</dd></div>
          <div><dt>제출자</dt><dd>{data.detail.submittedBy}</dd></div>
          <div><dt>청구서 변경 버전</dt><dd>v{data.detail.version}</dd></div>
          <div><dt>작성 차수</dt><dd>{data.detail.currentRevision ? `#${data.detail.currentRevision.revisionNumber}` : '—'}</dd></div>
          <div><dt>증빙 버전</dt><dd>{newest ? `v${newest.version}` : '—'}</dd></div>
          <div><dt>제출 시각 (KST)</dt><dd>{formatInstant(newest?.submittedAt ?? null)}</dd></div>
        </dl>
      </header>

      {actions.failure && (
        <MutationFailureNotice
          failure={actions.failure}
          onRefresh={() => { actions.clearFailure(); setReloadToken((value) => value + 1); }}
        />
      )}
      {actions.lastSuccess && !actions.failure && (
        <div className="review-note" role="status"><span className="status-dot" /><span>작업이 서버에 반영되었습니다. 최신 자료를 다시 조회했습니다.</span></div>
      )}
      <HandoffStrip handoff={handoff} />

      {isOperator && tab === 'compare' && (
        <div className="toolbar">
          <span className="demo-description">운영자 대사 실행 · 최신 제출 증빙을 대상으로 서버가 결정론적으로 대사합니다.</span>
          <button className="button" disabled={actionBlocked || newest === null} title={newest === null ? '제출된 증빙이 없어 대사를 실행할 수 없습니다.' : '대사 실행'} onClick={actions.runMatch}>{actions.pendingAction === 'match' ? '대사 실행 중…' : '대사 실행'}</button>
        </div>
      )}

      {reviewReader && <ProposalPanel load={proposalLoad} match={match} canReserve={isOperator} pending={actions.pendingAction === 'proposal'} blocked={actionBlocked}
        onReserve={() => actions.reserveProposal(data.detail.version)} onRefresh={() => setReloadToken(value => value + 1)} />}
      {reviewReader && <GraphReviewPanel load={graphLoad} view={graphView} historySelectedId={graphSelectedId} onSelectHistory={setGraphSelectedId}
        historyLoading={graphViewLoading} historyError={graphViewError} isOperator={isOperator} isApprover={isApprover} blocked={actionBlocked}
        reservePending={actions.pendingAction === 'graphReserve' || actions.pendingAction === 'graphSuccessor'} confirmPending={actions.pendingAction === 'graphConfirm'} lastSuccess={actions.lastSuccess}
        onReserve={() => actions.graphReserve(data.detail.version)} onSuccessor={(predecessorId) => actions.graphSuccessor(predecessorId, data.detail.version)}
        onConfirm={(command) => actions.graphConfirm(command)} onRefresh={() => setReloadToken(value => value + 1)}
        proofCandidate={graphCandidateProof} proofSelected={selectedProof !== null && proposalSelection?.kind === 'graph'}
        onSelectProof={(proof) => setProposalSelection(proof ? { ...proof, identity, kind: 'graph' } : null)} />}
      <div className="tabs" role="tablist" aria-label="청구서 상세">
        {tabs.map(([id, label]) => (
          <button key={id} role="tab" id={`tab-${id}`} aria-controls={`panel-${id}`} aria-selected={tab === id} className={tab === id ? 'active' : ''} onClick={() => selectTab(id)}>{label}</button>
        ))}
      </div>

      <div className="action-panel">
      {actionUnresolved && (
        <section className="form-section" aria-label="미확정 요청 복구">
          <SectionMessage tone="notice">
            <strong>이전 요청의 결과가 확정되지 않았습니다</strong>
            <p>자동으로 다시 보내지 않습니다. 같은 요청을 다시 시도하면 동일한 요청 식별자로 한 번만 반영됩니다. 서버 재조회만으로는 이 작업이 반영됐는지 확정할 수 없어 미확정 상태를 유지합니다.</p>
            <div className="dialog-actions">
              <button className="button primary" disabled={actionInFlight} onClick={() => actions.retry()}>{actions.pendingAction ? '다시 시도 중…' : '같은 요청 다시 시도'}</button>
              <button className="button" onClick={() => { actions.clearFailure(); setReloadToken((value) => value + 1); }}>최신 자료 다시 조회</button>
            </div>
          </SectionMessage>
        </section>
      )}

      {isApprover && (
        <section className="form-section" aria-label="승인자 검토 동작">
          <div className="section-heading"><h2>검토 동작</h2><span>서버가 권한·소유권·최신성을 검증합니다</span></div>
          {!subjectReady && (
            <SectionMessage tone="notice">
              결정을 기록하려면 표시된 비교·증빙·구매 사실이 동결된 검토 대상과 일치해야 합니다.
              {binding.reasons.length > 0 && <ul className="failure-details">{binding.reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>}
              {!snapshot && newest && ' 아직 검토 대상이 없어 동결할 수 있습니다.'}
            </SectionMessage>
          )}
          {candidateProof && <label className="proposal-choice"><input type="checkbox" disabled={actionBlocked} checked={selectedProof !== null && proposalSelection?.kind === 'v1'}
            onChange={event => setProposalSelection(event.target.checked ? { ...candidateProof, identity, kind: 'v1' } : null)} />이 AI 제안을 검토 근거에 포함</label>}
          {frozenProof && <p>현재 검토 대상에 동결된 AI 제안: {frozenProof.proposalId}</p>}
          <div className="dialog-actions">
            <button className="button" disabled={actionBlocked || newest === null || (subjectReady && sameAdvisory)} title={subjectReady && sameAdvisory ? '이미 최신 검토 대상이 있습니다.' : '현재 자료로 검토 대상을 동결합니다.'} onClick={doFreeze}>
              {actions.pendingAction === 'freeze' ? '동결 중…' : '검토 대상 동결'}
            </button>
          </div>

          {subjectReady && snapshot && (
            <>
              <div className="field-grid mapper-grid">
                <label>매핑할 라인
                  <select aria-label="매핑할 라인" value={mappingLine} onChange={(event) => setMappingLine(event.target.value)}>
                    <option value="">라인 선택</option>
                    {mappingRows.map((row) => <option key={row.lineNumber} value={String(row.lineNumber)}>라인 {row.lineNumber} · {row.rawItemName}{row.confirmedItemId ? ` (현재 ${row.confirmedItemId})` : ''}</option>)}
                  </select>
                  <small>서버 대사 결과의 라인만 나열합니다.</small>
                </label>
                <label>확정 품목 ID
                  <input maxLength={64} value={mappingItem} onChange={(event) => setMappingItem(event.target.value)} placeholder="품목 ID 직접 입력" />
                  <small>품목 후보 조회 API는 제공하지 않으므로 ID를 직접 입력하며 서버가 검증합니다.</small>
                </label>
              </div>
              <div className="dialog-actions">
                <button className="button" disabled={actionBlocked || !mappingLine || !mappingItem.trim()} onClick={doMapping}>{actions.pendingAction === 'mapping' ? '확정 중…' : '매핑 확정'}</button>
              </div>

              <div className="review-warning" role="status">
                <div>
                  <strong>승인 대상 확인</strong>
                  <p>검토 #{snapshot.snapshotNumber} · 청구서 v{data.detail.version} · 현재 자료와 일치함</p>
                  <p>서버가 표시된 검토 대상과 최신성을 재검증합니다. 화면에 보이는 값으로만 결정합니다.</p>
                  <details className="snapshot-technical">
                    <summary>기술 정보 보기</summary>
                    <p>검토 지문(해시): {snapshot.payloadHash}</p>
                    <p>검토 대상 snapshot #{snapshot.snapshotNumber} · 증빙 v{snapshot.evidenceBundleVersion} · 비교 결과 #{snapshot.matchResultNumber ?? '—'}</p>
                  </details>
                </div>
              </div>

              {reasonPanel && (
                <label className="reason-label">{reasonPanel === 'supplement' ? '보완 요청 사유' : '청구 거절 사유'}
                  <textarea rows={3} value={reason} onChange={(event) => setReason(event.target.value)} placeholder={reasonPanel === 'reject' ? '예: 다른 거래에 대한 청구로 확인되어 처리를 종료합니다.' : '예: 부족한 수량의 검수 근거를 추가해 주세요.'} />
                </label>
              )}
              <div className="dialog-actions">
                {reasonPanel ? (
                  <>
                    <button className="button" disabled={actionBlocked} onClick={() => { setReasonPanel(null); setReason(''); }}>취소</button>
                    <button className="button primary" disabled={actionBlocked || !reason.trim()} onClick={reasonPanel === 'supplement' ? doSupplement : doReject}>
                      {actions.pendingAction === reasonPanel ? '기록 중…' : reasonPanel === 'supplement' ? '보완 요청 기록' : '거절 기록'}
                    </button>
                  </>
                ) : (
                  <>
                    <button className="button footer-secondary" disabled={actionBlocked} onClick={() => { setReason(''); setReasonPanel('reject'); }}>청구 거절</button>
                    <button className="button footer-secondary" disabled={actionBlocked} onClick={() => { setReason(''); setReasonPanel('supplement'); }}>보완 요청</button>
                    <button className="button primary" disabled={actionBlocked} title={ownerSubmitter ? '본인이 제출한 청구는 서버가 승인을 거부합니다.' : '표시된 검토 대상으로 승인합니다.'} onClick={doApprove}>{actions.pendingAction === 'approve' ? '승인 중…' : '승인'}</button>
                  </>
                )}
              </div>
              {ownerSubmitter && <p className="panel-footnote" role="status">이 청구의 제출자 계정으로 로그인되어 있습니다. 서버는 자기 승인을 거부하며, 승인을 누르면 서버 응답으로 사유가 표시됩니다.</p>}
            </>
          )}
        </section>
      )}
      </div>

      <section className="tab-content" role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {unsupportedTab ? (
          <SectionMessage tone="forbidden">이 탭은 현재 계정 역할에서 허용되지 않습니다. 서버도 이 계정의 해당 자료 조회를 허용하지 않습니다.</SectionMessage>
        ) : tab === 'compare' ? <ComparePanel data={data} />
          : tab === 'evidence' ? <><EvidencePanel data={data} />{credentials && <OriginalDocuments key={`${sessionId}:${caseId}`} credentials={credentials} sessionId={sessionId} caseId={caseId} onUnauthorized={onUnauthorized} />}</>
            : tab === 'decisions' ? <DecisionsPanel data={data} />
              : <AuditPanel data={data} entries={auditEntries} nextCursor={auditNextCursor} loadingMore={auditLoadingMore} error={auditError} onMore={loadMoreAudit} />}
      </section>

      <footer className="action-bar">
        <HandoffSummary handoff={handoff} payment={payment} />
        <div className="action-buttons">
          <span className="footer-context">검토 #{data.snapshot.status === 'ready' ? data.snapshot.data.snapshotNumber : '—'} · 증빙 {newest ? `v${newest.version}` : '—'}</span>
          {ownerSubmitter && data.detail.status === 'DRAFT' && <Link className="button" href={`/cases/new?case=${data.detail.id}`}>초안 편집</Link>}
          {ownerSubmitter && data.detail.status === 'SUPPLEMENT_REQUIRED' && <Link className="button primary" href={`/cases/new?supplement=${data.detail.id}`}>보완 작성</Link>}
          {!isApprover && !isOperator && <span className="muted-text">이 계정은 검토 결정 권한이 없습니다. 화면 표시는 안내일 뿐 서버가 권한을 판정합니다.</span>}
        </div>
      </footer>
    </Shell>
  );
}

export default function CaseDetailPage() {
  return (
    <Suspense fallback={<Shell active="cases" preview={false}><section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>청구서 상세를 서버에서 확인하고 있습니다.</p></section></Shell>}>
      <Detail />
    </Suspense>
  );
}
