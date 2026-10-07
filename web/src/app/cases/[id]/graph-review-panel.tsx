'use client';

import { NativeSelect } from '@/components/ui/native-select';
import { Notice } from '@/components/ui/notice';
// Read/write surface for the default-off graph workflow, added next to the
// existing advisory ProposalPanel. It never computes currentness, permissions,
// supported versions or hashes; those come from the Core projection. The real
// item mapping and supplement stay on the existing page actions.

import { useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Table } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { formatInstant } from '../../api/contract';
import type {
  CandidateSource,
  GraphConfirmation,
  GraphPage,
  GraphPending,
  GraphSummary,
  GraphView,
  SelectedProposal,
} from '../../api/contract';
import type { MutationOperation } from '../composer-model';
import { exactFact, sourceQuote } from './proposal-model';
import {
  buildConfirmation,
  documentDecisionOptions,
  eligibleGraphProposal,
  hasDocumentReview,
  pendingMappingLines,
  presentGraphReason,
  presentGraphResumeStatus,
  presentGraphSegment,
  presentGraphStatus,
  type ItemDecisionChoice,
} from './graph-model';
import type { GraphConfirmCommand } from './use-case-actions';
import type { GraphLoad } from './use-graph-review';

const EXTRACT_LABELS: Record<string, string> = {
  invoiceNumber: '청구 번호', supplierName: '공급사명', invoiceDate: '청구일', currency: '통화',
};
const WARNING_LABELS: Record<string, string> = {
  REVIEW_REQUIRED: '사람 검토 필요', INSUFFICIENT_EVIDENCE: '적용 근거 부족', POLICY_CONFLICT: '정책 근거 충돌',
  MAPPING_REVIEW: '품목 후보 확인 필요', DOCUMENT_REVIEW: '문서 후보 확인 필요', SPECIFICATION_MISMATCH: '규격 차이',
  AMBIGUOUS: '복수 후보', NO_CANDIDATES: '품목 후보 없음', MISSING_FIELDS: '누락된 항목', AMBIGUOUS_LAYOUT: '양식 확인 필요',
  EMPTY_DOCUMENT: '추출 항목 없음', DOCUMENT_CONFLICT: '문서 간 충돌',
};
const RECOMMENDATION_LABELS: Record<string, string> = {
  APPROVAL_REVIEW: '승인 검토 초안', SUPPLEMENT_REQUEST: '보완 요청 초안', REJECTION_REVIEW: '거절 검토 초안',
  REVIEW_REQUIRED: '사람 검토 필요', NORMAL: '정상',
};

const SECTION = 'form-section proposal-panel mx-11 my-6 min-w-0 [overflow-wrap:anywhere] max-[1200px]:mx-7.5 max-[760px]:mx-5';
const HEADING = 'section-heading mb-6 flex flex-wrap items-center justify-between gap-3';
const NOTE = 'review-note mx-7.75 my-6.5 flex items-center gap-2 text-label text-muted-foreground';
const ENTRY = 'proposal-entry border-b border-border py-3';
const ACTIONS = 'dialog-actions mt-6 flex justify-end gap-2';
const CHOICE = 'proposal-choice my-4 flex items-center gap-2.5';
const H3 = 'mt-6 text-base font-medium';
const BLOCKQUOTE = 'my-2.5 whitespace-pre-wrap border-l-3 border-border bg-muted px-4 py-2.5 text-sm text-foreground';
const MUTED = 'muted-text text-label text-muted-foreground';

function label(map: Record<string, string>, key: string): string {
  return map[key] ?? key;
}

// The shared frozen source projection renders the exact quote and location.
function Source({ view, source }: { view: GraphView; source: CandidateSource }) {
  const proof = sourceQuote(view, source);
  return proof
    ? <details className="proposal-source mt-1.5 text-label"><summary>원문 위치</summary><p>{proof.location}</p><blockquote className={BLOCKQUOTE}>{proof.quote}</blockquote></details>
    : <p>원문 위치를 표시할 수 없습니다.</p>;
}

function RunSummary({ view, enabled, isOperator, blocked, pending, onSuccessor }: {
  view: GraphView; enabled: boolean; isOperator: boolean; blocked: boolean; pending: boolean;
  onSuccessor: () => void;
}) {
  const run = view.run;
  const oldInput = run.status === 'STALE' || !run.current;
  // Reservation/successor are only offered when the server reports a supported
  // run and the workflow is enabled; an unsupported stored version is metadata
  // only and never looks like it can be resumed or re-reserved.
  const canReserve = enabled && run.supported;
  return (
    <>
      <div className={NOTE} role="status">
        <strong>{presentGraphStatus(run.status)}</strong>
        <span>{presentGraphSegment(run.segment)} · 청구 버전 v{run.caseVersion} · {run.current ? '현재 입력' : '이전 입력'}</span>
      </div>
      {!run.supported && <p className={NOTE}>지원하지 않는 저장 형식입니다. 요약 정보만 표시하며 자동으로 복원하거나 다시 예약하지 않습니다.</p>}
      {run.errorCode && (run.status === 'FAILED'
        ? <p className="my-3" role="alert">분석 오류: {run.errorCode}. 원인을 해결하고 최신 입력을 준비해야 합니다.</p>
        : <p className="my-3">이전 시도 오류: {run.errorCode}</p>)}
      {oldInput && run.supported && <Notice tone="warning" className="review-warning mx-0 mb-3">이전 입력으로 만든 분석입니다. 현재 자료의 승인 근거로 사용할 수 없습니다.</Notice>}
      {isOperator && canReserve && (
        <div className={ACTIONS}>
          {oldInput && <Button variant="outline" className="button" disabled={blocked} onClick={onSuccessor}>{pending ? '예약 중…' : '이 입력으로 새 분석 예약'}</Button>}
        </div>
      )}
    </>
  );
}

function CandidateList({ view, pending }: { view: GraphView; pending: GraphPending }) {
  const document = pending.document;
  const mapping = pending.mapping;
  return (
    <>
      <h3 className={H3}>대기 사유</h3>
      <ul>{pending.reasonCodes.map((code) => <li key={code}>{presentGraphReason(code)}</li>)}</ul>
      <h3 className={H3}>문서에서 추출한 후보</h3>
      {!document || (document.fields.length === 0 && document.lines.length === 0)
        ? <p>표시할 추출 후보가 없습니다.</p>
        : <>
            {document.fields.map((field) => (
              <div className={ENTRY} key={field.name}><strong>{label(EXTRACT_LABELS, field.name)}</strong><span className="ml-4">{field.value}</span><Source view={view} source={field.source} /></div>
            ))}
            <Table className="table-scroll"><thead><tr><th>추출 순번</th><th>품목 표현</th><th>수량 후보</th><th>단가 후보</th></tr></thead><tbody>
              {document.lines.map((line) => (
                <tr key={line.lineNumber}><td>{line.lineNumber}</td><td>{line.rawItemName.value}<Source view={view} source={line.rawItemName.source} /></td><td>{line.quantity.value}<Source view={view} source={line.quantity.source} /></td><td>{line.unitPrice.value}<Source view={view} source={line.unitPrice.source} /></td></tr>
              ))}
            </tbody></Table>
            {document.warnings.length > 0 && <p>{document.warnings.map((w) => label(WARNING_LABELS, w)).join(' · ')}</p>}
          </>}
      <h3 className={H3}>품목 후보</h3>
      {!mapping || mapping.lines.length === 0
        ? <p>표시할 품목 후보가 없습니다.</p>
        : mapping.lines.map((line) => (
            <div className={ENTRY} key={line.lineNumber}>
              <strong>추출 순번 {line.lineNumber}{line.reviewRequired ? ' · 사람 확인 필요' : ''}</strong>
              <Source view={view} source={line.source} />
              {line.candidates.length === 0 && <p>후보 없음</p>}
              {line.candidates.length > 0 && (
                <ol>{line.candidates.map((candidate) => (
                  <li key={`${candidate.itemId}:${candidate.purchaseOrderLineId}`}><strong>{candidate.itemId}</strong> · 발주 라인 {candidate.purchaseOrderLineId}{candidate.reason ? <p>{candidate.reason}</p> : null}</li>
                ))}</ol>
              )}
            </div>
          ))}
    </>
  );
}

function ConfirmForm({ view, pending, blocked, inFlight, onConfirm }: {
  view: GraphView; pending: GraphPending; blocked: boolean; inFlight: boolean; onConfirm: (command: GraphConfirmCommand) => void;
}) {
  const [documentDecision, setDocumentDecision] = useState<GraphConfirmation['documentDecision'] | null>(null);
  const [choices, setChoices] = useState<Map<number, ItemDecisionChoice>>(new Map());
  const [reason, setReason] = useState('');
  const lines = pendingMappingLines(pending);
  const decisionOptions = documentDecisionOptions(pending.reasonCodes);
  const requiresChoice = hasDocumentReview(pending.reasonCodes);
  const canSubmit = reason.trim().length > 0 && (!requiresChoice || documentDecision !== null) && !blocked;
  function setChoice(lineNumber: number, value: string) {
    setChoices((previous) => {
      const next = new Map(previous);
      if (value === '') next.set(lineNumber, null);
      else {
        const candidate = pending.mapping?.lines.find((line) => line.lineNumber === lineNumber)?.candidates[Number(value)];
        next.set(lineNumber, candidate ? { itemId: candidate.itemId, purchaseOrderLineId: candidate.purchaseOrderLineId } : null);
      }
      return next;
    });
  }
  function submit() {
    const decision = documentDecision ?? 'NOT_REQUIRED';
    onConfirm({
      graphId: view.run.id,
      expectedCaseVersion: view.run.caseVersion,
      interruptId: pending.interruptId,
      checkpointHash: pending.checkpointHash,
      reviewVersion: pending.reviewVersion,
      confirmation: buildConfirmation(pending, decision, choices),
      reason: reason.trim(),
    });
  }
  return (
    <div className="graph-confirm mt-4.5 border-t border-border pt-4.5">
      <h3 className={H3}>사람 확인 저장</h3>
      <p className={MUTED}>저장하면 재개가 예약됩니다. 저장 성공은 완료가 아니며, 재개 완료는 서버 재조회로 확인합니다.</p>
      <fieldset className="my-3 rounded-sm border border-border px-3.5 py-2.5">
        <legend className="px-1.5 text-label text-muted-foreground">문서 후보 확인</legend>
        {decisionOptions.map((option) => (
          <label key={option} className={CHOICE}>
            <input type="radio" name={`document-decision-${view.run.id}`} disabled={blocked} checked={documentDecision === option} onChange={() => setDocumentDecision(option)} />
            {option === 'CONFIRMED' ? '원문 후보를 확인함' : option === 'NEEDS_CORRECTION' ? '수정이 필요함' : '문서 확인 불필요'}
          </label>
        ))}
      </fieldset>
      {lines.length === 0 ? <p>사람이 결정할 품목 후보가 없습니다.</p> : (
        <Table className="table-scroll"><thead><tr><th>추출 순번</th><th>원문 위치</th><th>확인할 후보</th></tr></thead><tbody>
          {lines.map((line) => (
            <tr key={line.lineNumber}>
              <td>{line.lineNumber}</td>
              <td><Source view={view} source={line.source} /></td>
              <td>
                <NativeSelect className="block w-full" aria-label={`추출 순번 ${line.lineNumber} 후보 확인`} disabled={blocked} value={choices.get(line.lineNumber) ? String(line.candidates.findIndex((c) => c.itemId === choices.get(line.lineNumber)?.itemId && c.purchaseOrderLineId === choices.get(line.lineNumber)?.purchaseOrderLineId)) : ''} onChange={(event) => setChoice(line.lineNumber, event.target.value)} density="compact">
                  <option value="">미해결로 기록</option>
                  {line.candidates.map((candidate, index) => (
                    <option key={`${candidate.itemId}:${candidate.purchaseOrderLineId}`} value={String(index)}>{candidate.itemId} · 발주 라인 {candidate.purchaseOrderLineId}</option>
                  ))}
                </NativeSelect>
              </td>
            </tr>
          ))}
        </tbody></Table>
      )}
      <label className="reason-label my-4 block max-w-[580px] text-label text-muted-foreground">확인 사유
        <Textarea className="mt-2" rows={3} maxLength={1000} disabled={blocked} value={reason} onChange={(event) => setReason(event.target.value)} placeholder="예: 원문과 후보를 대조한 결과를 기록합니다." />
      </label>
      <div className={ACTIONS}>
        <Button variant="default" className="button primary" disabled={!canSubmit} onClick={submit}>{inFlight ? '저장 중…' : '사람 확인 저장'}</Button>
      </div>
    </div>
  );
}

function ReviewRecord({ review }: { review: NonNullable<GraphView['review']> }) {
  const confirmation = review.confirmation && typeof review.confirmation === 'object'
    ? review.confirmation as Record<string, unknown> : null;
  const documentDecision = confirmation?.documentDecision;
  const items = Array.isArray(confirmation?.itemDecisions)
    ? confirmation.itemDecisions.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object') : [];
  return (
    <div className={NOTE} role="status">
      <strong>사람 확인 기록 · {presentGraphResumeStatus(review.resumeStatus)}</strong>
      <span>작업자 {review.actor} · {formatInstant(review.createdAt)}</span>
      <p>사유: {review.reason}</p>
      {typeof documentDecision === 'string' && <p>문서 검토: {documentDecision === 'CONFIRMED' ? '확인 완료' : documentDecision === 'NEEDS_CORRECTION' ? '수정 필요' : documentDecision === 'NOT_REQUIRED' ? '추가 확인 불필요' : '확인 결과를 표시할 수 없습니다.'}</p>}
      {items.length > 0 && <ul>{items.map((item, index) => <li key={index}>추출 순번 {typeof item.lineNumber === 'number' ? item.lineNumber : '—'} · {typeof item.itemId === 'string' ? `선택 품목 ${item.itemId}` : '품목 미확정'}{typeof item.purchaseOrderLineId === 'string' ? ` · 발주 라인 ${item.purchaseOrderLineId}` : ''}</li>)}</ul>}
    </div>
  );
}

function CompletedPayload({ view, isApprover, proofCandidate, proofSelected, blocked, onSelectProof }: {
  view: GraphView; isApprover: boolean; proofCandidate: SelectedProposal | null; proofSelected: boolean; blocked: boolean; onSelectProof: (proof: SelectedProposal | null) => void;
}) {
  const payload = view.payload;
  if (!payload) return <p>완료된 결과가 저장되면 후보와 근거가 표시됩니다.</p>;
  // The proof control is only offered when this displayed view is itself the
  // eligible current proposal and the parent supplied the exact same id/hash.
  // A non-null latest candidate must never select a different history view.
  const eligible = eligibleGraphProposal(view);
  const selectable = eligible !== null && proofCandidate !== null
    && proofCandidate.proposalId === eligible.proposalId && proofCandidate.proposalHash === eligible.proposalHash;
  return (
    <>
      <h3 className={H3}>처리 초안 · {label(RECOMMENDATION_LABELS, payload.resolution.result.recommendation)}</h3>
      <p>{payload.resolution.result.summary}</p>
      {payload.resolution.result.warnings.length > 0 && <ul>{payload.resolution.result.warnings.map((w) => <li key={w}>{label(WARNING_LABELS, w)}</li>)}</ul>}
      <details><summary>서버 대사에서 확인한 수치</summary><dl className="proposal-facts">{Object.entries(payload.facts).map(([id, fact]) => (
        <div key={id} className="flex justify-between gap-4 py-fact-row"><dt>{id.replace('invoiceQuantity', '청구 수량').replace('invoiceUnitPrice', '청구 단가').replace('availableConfirmedQuantity', '검수 잔량').replace('plannedQuantity', '예상 배분 수량').replace('invoiceAmount', '청구 금액').replace('line:', '라인 ')}</dt><dd className="m-0">{exactFact(fact.value)} {fact.unit === 'KRW' ? '원' : '개'}</dd></div>
      ))}</dl></details>
      <h3 className={H3}>문서에서 추출한 후보</h3>
      {payload.document.result.lines.map((line) => (
        <div className={ENTRY} key={line.lineNumber}><strong>추출 순번 {line.lineNumber}</strong><span className="ml-4">{line.rawItemName.value}</span><Source view={view} source={line.rawItemName.source} /></div>
      ))}
      <h3 className={H3}>품목 후보</h3>
      {payload.mapping.result.lines.map((line) => (
        <div className={ENTRY} key={line.lineNumber}><strong>추출 순번 {line.lineNumber}</strong><Source view={view} source={line.source} /><ol>{line.candidates.map((candidate) => <li key={`${candidate.itemId}:${candidate.purchaseOrderLineId}`}><strong>{candidate.itemId}</strong> · 발주 라인 {candidate.purchaseOrderLineId}</li>)}</ol></div>
      ))}
      <details><summary>적용 정책 근거 · {payload.policyEvidence.status}</summary>{payload.policyEvidence.result.map((chunk) => (
        <div className={ENTRY} key={chunk.chunkId}><strong>{chunk.title} · 버전 {chunk.documentVersion} · {chunk.page}쪽 · 문단 {chunk.paragraph}</strong><blockquote className={BLOCKQUOTE}>{chunk.text}</blockquote></div>
      ))}</details>
      {isApprover && selectable && (
        <label className={CHOICE}>
          <input type="checkbox" disabled={blocked} checked={proofSelected} onChange={(event) => onSelectProof(event.target.checked ? eligible : null)} />
          이 AI 제안을 검토 근거에 포함
        </label>
      )}
      {isApprover && !selectable && (
        <Notice tone="warning" className="review-warning mx-0 mb-3">현재 입력의 완료 제안이 아니므로 검토 근거로 선택할 수 없습니다.</Notice>
      )}
    </>
  );
}

export function GraphReviewPanel({
  load, view, historySelectedId, onSelectHistory, historyLoading, historyError, isOperator, isApprover, blocked, reservePending, confirmPending, lastSuccess, onReserve, onSuccessor, onConfirm, onRefresh, proofCandidate, proofSelected, onSelectProof,
}: {
  load: GraphLoad;
  view: GraphView | null;
  historySelectedId: string | null;
  onSelectHistory: (id: string | null) => void;
  historyLoading: boolean;
  historyError: string | null;
  isOperator: boolean;
  isApprover: boolean;
  blocked: boolean;
  reservePending: boolean;
  confirmPending: boolean;
  lastSuccess: MutationOperation | null;
  onReserve: () => void;
  onSuccessor: (predecessorId: string) => void;
  onConfirm: (command: GraphConfirmCommand) => void;
  onRefresh: () => void;
  proofCandidate: SelectedProposal | null;
  proofSelected: boolean;
  onSelectProof: (proof: SelectedProposal | null) => void;
}) {
  if (load.status === 'forbidden') return null;
  if (load.status === 'loading') return <section className="form-section proposal-panel mx-11 my-6 min-w-0 [overflow-wrap:anywhere] max-[1200px]:mx-7.5 max-[760px]:mx-5" aria-label="AI 확인·재개"><h2 className="text-lg font-medium">AI 확인·재개</h2><p className="my-3" role="status">AI 분석 상태를 확인하고 있습니다.</p></section>;
  if (load.status === 'error') return <section className="form-section proposal-panel mx-11 my-6 min-w-0 [overflow-wrap:anywhere] max-[1200px]:mx-7.5 max-[760px]:mx-5" aria-label="AI 확인·재개"><h2 className="text-lg font-medium">AI 확인·재개</h2><p className="my-3" role="alert">{load.message}</p><Button variant="outline" className="button" onClick={onRefresh}>다시 조회</Button></section>;
  if (load.status !== 'ready') return null;

  const page: GraphPage = load.data;
  const history: GraphSummary[] = page.history;
  // The default-off workflow keeps the existing AI-off screen: an empty graph
  // panel is hidden entirely, while any stored history stays visible read-only.
  if (!page.enabled && history.length === 0) return null;

  let body: ReactNode = null;
  if (view && view.run.supported && view.pending && page.enabled && view.run.current && view.run.status === 'WAITING_HUMAN') {
    body = <>
      <CandidateList view={view} pending={view.pending} />
      {isOperator
        ? <ConfirmForm key={`${view.run.id}#${view.pending.interruptId}#${view.pending.reviewVersion}`} view={view} pending={view.pending} blocked={blocked} inFlight={confirmPending} onConfirm={onConfirm} />
        : <p className={NOTE}>운영자만 사람 확인을 저장할 수 있습니다.</p>}
    </>;
  } else if (view && view.run.supported && view.pending && !page.enabled) {
    body = <p className={NOTE}>AI 확인·재개가 비활성화되어 저장할 수 없습니다. 기존 기록만 조회합니다.</p>;
  } else if (view && view.run.supported && view.pending) {
    body = <Notice tone="warning" className="review-warning mx-0 mb-3">현재 입력의 대기가 아니어서 확인을 저장할 수 없습니다. 최신 입력으로 다시 분석해야 합니다.</Notice>;
  } else if (view && view.run.supported && view.run.status === 'COMPLETED') {
    body = <CompletedPayload view={view} isApprover={isApprover} proofCandidate={proofCandidate} proofSelected={proofSelected} blocked={blocked} onSelectProof={onSelectProof} />;
  } else if (view && view.run.supported) {
    body = <p>대기 중인 사람 확인이 없습니다.</p>;
  }

  return <section className={SECTION} aria-label="AI 확인·재개">
    <div className={HEADING}><h2 className="text-lg font-medium">AI 확인·재개</h2><span className="text-label text-muted-foreground">사람 확인이 필요한 경우에만 멈추고, 저장 후 재개합니다</span></div>
    <p className="my-3">AI 분석 결과는 참고 자료입니다. 실제 품목 매핑과 보완은 아래 기존 검토 동작에서 사람이 결정합니다.</p>
    {!page.enabled && <p className={NOTE}>AI 신규 분석이 비활성화되어 있습니다. 기존 이력은 그대로 조회합니다.</p>}
    <div className={ACTIONS}>
      <Button variant="outline" className="button" disabled={blocked} onClick={onRefresh}>AI 분석 상태 새로 조회</Button>
      {historySelectedId && <Button variant="outline" className="button" disabled={blocked} onClick={() => onSelectHistory(null)}>최신 분석 보기</Button>}
    </div>
    {!view && historyLoading ? <p role="status">선택한 분석을 확인하고 있습니다.</p> : null}
    {!view && historyError ? <p role="alert">{historyError}</p> : null}
    {!view && !historyLoading && !historyError ? <>
      <p>예약된 AI 분석이 없습니다. 파서 완료와 최신 대사 후 운영자가 예약할 수 있습니다.</p>
      {page.enabled && isOperator && <div className={ACTIONS}><Button variant="outline" className="button" disabled={blocked} onClick={onReserve}>{reservePending ? '예약 중…' : 'AI 분석 예약'}</Button></div>}
    </> : null}
    {view ? <>
      <RunSummary view={view} enabled={page.enabled} isOperator={isOperator} blocked={blocked} pending={reservePending} onSuccessor={() => onSuccessor(view.run.id)} />
      {view.review && <ReviewRecord review={view.review} />}
      {body}
    </> : null}
    {lastSuccess === 'graphConfirm' && <div className={NOTE} role="status"><span className="status-dot inline-block h-1.25 w-1.25 shrink-0 rounded-full bg-olive" /><span>사람 확인이 저장되어 재개가 예약되었습니다. 완료 여부는 서버 재조회로 확인합니다.</span></div>}
    {history.length > 1 && (
      <details open={historySelectedId !== null}>
        <summary>AI 분석 이력</summary>
        <ul className="graph-history my-3 flex list-none flex-col items-start gap-2 p-0">
          {history.map((run) => (
            <li key={run.id}>
              <Button variant={historySelectedId === run.id ? 'default' : 'outline'} size="content" className="button max-w-full" disabled={blocked || historyLoading} onClick={() => onSelectHistory(run.id)}>
                {presentGraphStatus(run.status)} · {presentGraphSegment(run.segment)} · v{run.caseVersion}{historySelectedId === run.id ? ' · 표시 중' : ''}
              </Button>
            </li>
          ))}
        </ul>
      </details>
    )}
  </section>;
}
