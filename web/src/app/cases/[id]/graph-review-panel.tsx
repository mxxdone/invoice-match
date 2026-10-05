'use client';

// Read/write surface for the default-off graph workflow, added next to the
// existing advisory ProposalPanel. It never computes currentness, permissions,
// supported versions or hashes; those come from the Core projection. The real
// item mapping and supplement stay on the existing page actions.

import { useState } from 'react';
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

function label(map: Record<string, string>, key: string): string {
  return map[key] ?? key;
}

// The shared frozen source projection renders the exact quote and location.
function Source({ view, source }: { view: GraphView; source: CandidateSource }) {
  const proof = sourceQuote(view, source);
  return proof
    ? <details className="proposal-source"><summary>원문 위치</summary><p>{proof.location}</p><blockquote>{proof.quote}</blockquote></details>
    : <p>원문 위치를 표시할 수 없습니다.</p>;
}

function RunSummary({ view, isOperator, blocked, pending, onReserve, onSuccessor }: {
  view: GraphView; isOperator: boolean; blocked: boolean; pending: boolean;
  onReserve: () => void; onSuccessor: () => void;
}) {
  const run = view.run;
  const stale = run.status === 'STALE' || !run.current;
  return (
    <>
      <div className="review-note" role="status">
        <strong>{presentGraphStatus(run.status)}</strong>
        <span>{presentGraphSegment(run.segment)} · 청구 버전 v{run.caseVersion} · {run.current ? '현재 입력' : '이전 입력'}</span>
      </div>
      <details className="snapshot-technical">
        <summary>실행 정보 보기</summary>
        <p>실행 {run.id} · 시작 시도 {run.startAttempts} · 재개 시도 {run.resumeAttempts}</p>
        <p>예약 호출 {run.reservedCalls} · 예약 토큰 {run.reservedTokens} · 도구 {run.toolCalls}</p>
        <p>완료 단계: {run.completedStages.join(', ') || '아직 없음'}</p>
        <p>입력 hash {run.contextHash}{run.payloadHash ? ` · 결과 hash ${run.payloadHash}` : ''}</p>
        {run.predecessorId && <p>이전 실행 {run.predecessorId}</p>}
        <p>생성 {formatInstant(run.createdAt)}</p>
      </details>
      {!run.supported && <p className="review-note">지원하지 않는 저장 버전입니다. 메타데이터만 표시하며 자동으로 복원하지 않습니다.</p>}
      {run.errorCode && (run.status === 'FAILED'
        ? <p role="alert">분석 오류: {run.errorCode}. 원인을 해결하고 최신 입력을 준비해야 합니다.</p>
        : <p>이전 시도 오류: {run.errorCode}</p>)}
      {stale && <p className="review-warning">이전 입력으로 만든 실행입니다. 현재 자료의 승인 근거로 사용할 수 없습니다.</p>}
      {isOperator && (
        <div className="dialog-actions">
          {run.status === 'STALE' && <button className="button" disabled={blocked} onClick={onSuccessor}>{pending ? '예약 중…' : '이 입력으로 새 실행 예약'}</button>}
          <button className="button" disabled={blocked} onClick={onReserve}>{pending ? '예약 중…' : '그래프 분석 예약'}</button>
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
      <h3>대기 사유</h3>
      <ul>{pending.reasonCodes.map((code) => <li key={code}>{presentGraphReason(code)}</li>)}</ul>
      <h3>문서에서 추출한 후보</h3>
      {!document || (document.fields.length === 0 && document.lines.length === 0)
        ? <p>표시할 추출 후보가 없습니다.</p>
        : <>
            {document.fields.map((field) => (
              <div className="proposal-entry" key={field.name}><strong>{label(EXTRACT_LABELS, field.name)}</strong><span>{field.value}</span><Source view={view} source={field.source} /></div>
            ))}
            <div className="table-scroll"><table><thead><tr><th>추출 순번</th><th>품목 표현</th><th>수량 후보</th><th>단가 후보</th></tr></thead><tbody>
              {document.lines.map((line) => (
                <tr key={line.lineNumber}><td>{line.lineNumber}</td><td>{line.rawItemName.value}<Source view={view} source={line.rawItemName.source} /></td><td>{line.quantity.value}<Source view={view} source={line.quantity.source} /></td><td>{line.unitPrice.value}<Source view={view} source={line.unitPrice.source} /></td></tr>
              ))}
            </tbody></table></div>
            {document.warnings.length > 0 && <p>{document.warnings.map((w) => label(WARNING_LABELS, w)).join(' · ')}</p>}
          </>}
      <h3>품목 후보</h3>
      {!mapping || mapping.lines.length === 0
        ? <p>표시할 품목 후보가 없습니다.</p>
        : mapping.lines.map((line) => (
            <div className="proposal-entry" key={line.lineNumber}>
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
    <div className="graph-confirm">
      <h3>사람 확인 저장</h3>
      <p className="muted-text">저장하면 재개가 예약됩니다. 저장 성공은 완료가 아니며, 재개 완료는 서버 재조회로 확인합니다.</p>
      <fieldset>
        <legend>문서 후보 확인</legend>
        {decisionOptions.map((option) => (
          <label key={option} className="proposal-choice">
            <input type="radio" name={`document-decision-${view.run.id}`} disabled={blocked} checked={documentDecision === option} onChange={() => setDocumentDecision(option)} />
            {option === 'CONFIRMED' ? '원문 후보를 확인함' : option === 'NEEDS_CORRECTION' ? '수정이 필요함' : '문서 확인 불필요'}
          </label>
        ))}
      </fieldset>
      {lines.length === 0 ? <p>사람이 결정할 품목 후보가 없습니다.</p> : (
        <div className="table-scroll"><table><thead><tr><th>추출 순번</th><th>원문 위치</th><th>확인할 후보</th></tr></thead><tbody>
          {lines.map((line) => (
            <tr key={line.lineNumber}>
              <td>{line.lineNumber}</td>
              <td><Source view={view} source={line.source} /></td>
              <td>
                <select aria-label={`추출 순번 ${line.lineNumber} 후보 확인`} disabled={blocked} value={choices.get(line.lineNumber) ? String(line.candidates.findIndex((c) => c.itemId === choices.get(line.lineNumber)?.itemId && c.purchaseOrderLineId === choices.get(line.lineNumber)?.purchaseOrderLineId)) : ''} onChange={(event) => setChoice(line.lineNumber, event.target.value)}>
                  <option value="">미해결로 기록</option>
                  {line.candidates.map((candidate, index) => (
                    <option key={`${candidate.itemId}:${candidate.purchaseOrderLineId}`} value={String(index)}>{candidate.itemId} · 발주 라인 {candidate.purchaseOrderLineId}</option>
                  ))}
                </select>
              </td>
            </tr>
          ))}
        </tbody></table></div>
      )}
      <label className="reason-label">확인 사유
        <textarea rows={3} maxLength={1000} disabled={blocked} value={reason} onChange={(event) => setReason(event.target.value)} placeholder="예: 원문과 후보를 대조한 결과를 기록합니다." />
      </label>
      <div className="dialog-actions">
        <button className="button primary" disabled={!canSubmit} onClick={submit}>{inFlight ? '저장 중…' : '사람 확인 저장'}</button>
      </div>
    </div>
  );
}

function ReviewRecord({ review }: { review: NonNullable<GraphView['review']> }) {
  return (
    <div className="review-note" role="status">
      <strong>사람 확인 기록 · {presentGraphResumeStatus(review.resumeStatus)}</strong>
      <span>작업자 {review.actor} · {formatInstant(review.createdAt)}</span>
      <p>사유: {review.reason}</p>
      <details className="snapshot-technical"><summary>확인 내용 보기</summary><p>{JSON.stringify(review.confirmation)}</p></details>
    </div>
  );
}

function CompletedPayload({ view, isApprover, proofSelected, blocked, onSelectProof }: {
  view: GraphView; isApprover: boolean; proofSelected: boolean; blocked: boolean; onSelectProof: (proof: SelectedProposal | null) => void;
}) {
  const payload = view.payload;
  if (!payload) return <p>완료된 결과가 저장되면 후보와 근거가 표시됩니다.</p>;
  const proposal = view.run.payloadHash ? { proposalId: view.run.id, proposalHash: view.run.payloadHash } : null;
  return (
    <>
      <h3>처리 초안 · {label(RECOMMENDATION_LABELS, payload.resolution.result.recommendation)}</h3>
      <p>{payload.resolution.result.summary}</p>
      {payload.resolution.result.warnings.length > 0 && <ul>{payload.resolution.result.warnings.map((w) => <li key={w}>{label(WARNING_LABELS, w)}</li>)}</ul>}
      <details><summary>서버 대사에서 확인한 수치</summary><dl className="proposal-facts">{Object.entries(payload.facts).map(([id, fact]) => (
        <div key={id}><dt>{id.replace('invoiceQuantity', '청구 수량').replace('invoiceUnitPrice', '청구 단가').replace('availableConfirmedQuantity', '검수 잔량').replace('plannedQuantity', '예상 배분 수량').replace('invoiceAmount', '청구 금액').replace('line:', '라인 ')}</dt><dd>{exactFact(fact.value)} {fact.unit === 'KRW' ? '원' : '개'}</dd></div>
      ))}</dl></details>
      <h3>문서에서 추출한 후보</h3>
      {payload.document.result.lines.map((line) => (
        <div className="proposal-entry" key={line.lineNumber}><strong>추출 순번 {line.lineNumber}</strong><span>{line.rawItemName.value}</span><Source view={view} source={line.rawItemName.source} /></div>
      ))}
      <h3>품목 후보</h3>
      {payload.mapping.result.lines.map((line) => (
        <div className="proposal-entry" key={line.lineNumber}><strong>추출 순번 {line.lineNumber}</strong><Source view={view} source={line.source} /><ol>{line.candidates.map((candidate) => <li key={`${candidate.itemId}:${candidate.purchaseOrderLineId}`}><strong>{candidate.itemId}</strong> · 발주 라인 {candidate.purchaseOrderLineId}</li>)}</ol></div>
      ))}
      <details><summary>적용 정책 근거 · {payload.policyEvidence.status}</summary>{payload.policyEvidence.result.map((chunk) => (
        <div className="proposal-entry" key={chunk.chunkId}><strong>{chunk.title} · 버전 {chunk.documentVersion} · {chunk.page}쪽 · 문단 {chunk.paragraph}</strong><blockquote>{chunk.text}</blockquote></div>
      ))}</details>
      {isApprover && proposal && (
        <label className="proposal-choice">
          <input type="checkbox" disabled={blocked} checked={proofSelected} onChange={(event) => onSelectProof(event.target.checked ? proposal : null)} />
          이 그래프 제안을 검토 근거에 포함
        </label>
      )}
      <details className="snapshot-technical"><summary>분석 기준 확인</summary><p>증빙 {payload.evidenceBundleId} · 대사 {payload.matchResultId}</p><p>제안 {view.run.id}</p><p>결과 hash {view.run.payloadHash}</p><p>입력 hash {view.run.contextHash}</p></details>
    </>
  );
}

export function GraphReviewPanel({
  load, view, historySelectedId, onSelectHistory, historyLoading, historyError, isOperator, isApprover, blocked, confirmPending, lastSuccess, onReserve, onSuccessor, onConfirm, onRefresh, proofSelected, onSelectProof,
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
  confirmPending: boolean;
  lastSuccess: MutationOperation | null;
  onReserve: () => void;
  onSuccessor: (predecessorId: string) => void;
  onConfirm: (command: GraphConfirmCommand) => void;
  onRefresh: () => void;
  proofSelected: boolean;
  onSelectProof: (proof: SelectedProposal | null) => void;
}) {
  if (load.status === 'forbidden') return null;
  if (load.status === 'loading') return <section className="form-section proposal-panel" aria-label="그래프 검토"><h2>그래프 검토</h2><p role="status">그래프 분석 상태를 확인하고 있습니다.</p></section>;
  if (load.status === 'error') return <section className="form-section proposal-panel" aria-label="그래프 검토"><h2>그래프 검토</h2><p role="alert">{load.message}</p><button className="button" onClick={onRefresh}>다시 조회</button></section>;
  if (load.status !== 'ready') return null;

  const page: GraphPage = load.data;
  const history: GraphSummary[] = page.history;

  return <section className="form-section proposal-panel" aria-label="그래프 검토">
    <div className="section-heading"><h2>그래프 검토</h2><span>사람 확인이 필요한 경우에만 멈추고, 저장 후 재개합니다</span></div>
    <p>그래프 결과는 참고 자료입니다. 실제 품목 매핑과 보완은 아래 기존 검토 동작에서 사람이 결정합니다.</p>
    {!page.enabled && <p className="review-note">그래프 신규 분석이 비활성화되어 있습니다. 기존 이력은 그대로 조회합니다.</p>}
    <div className="dialog-actions">
      <button className="button" disabled={blocked} onClick={onRefresh}>그래프 상태 새로 조회</button>
      {historySelectedId && <button className="button" disabled={blocked} onClick={() => onSelectHistory(null)}>최신 실행 보기</button>}
    </div>
    {!view && historyLoading ? <p role="status">선택한 그래프 실행을 확인하고 있습니다.</p> : null}
    {!view && historyError ? <p role="alert">{historyError}</p> : null}
    {!view && !historyLoading && !historyError ? <p>예약된 그래프 분석이 없습니다. 파서 완료와 최신 대사 후 운영자가 예약할 수 있습니다.</p> : null}
    {view ? <>
      <RunSummary view={view} isOperator={isOperator} blocked={blocked} pending={confirmPending} onReserve={onReserve} onSuccessor={() => onSuccessor(view.run.id)} />
      {view.review && <ReviewRecord review={view.review} />}
      {!view.run.supported ? null : view.pending
        ? <><CandidateList view={view} pending={view.pending} />{isOperator
              ? <ConfirmForm key={`${view.run.id}#${view.pending.interruptId}#${view.pending.reviewVersion}`} view={view} pending={view.pending} blocked={blocked} inFlight={confirmPending} onConfirm={onConfirm} />
              : <p className="review-note">운영자만 사람 확인을 저장할 수 있습니다.</p>}</>
        : view.run.status === 'COMPLETED'
          ? <CompletedPayload view={view} isApprover={isApprover} proofSelected={proofSelected} blocked={blocked} onSelectProof={onSelectProof} />
          : <p>대기 중인 사람 확인이 없습니다.</p>}
    </> : null}
    {lastSuccess === 'graphConfirm' && <div className="review-note" role="status"><span className="status-dot" /><span>사람 확인이 저장되어 재개가 예약되었습니다. 완료 여부는 서버 재조회로 확인합니다.</span></div>}
    {history.length > 1 && (
      <details open={historySelectedId !== null}>
        <summary>그래프 실행 이력</summary>
        <ul className="graph-history">
          {history.map((run) => (
            <li key={run.id}>
              <button className={`button ${historySelectedId === run.id ? 'primary' : ''}`} disabled={blocked || historyLoading} onClick={() => onSelectHistory(run.id)}>
                {presentGraphStatus(run.status)} · {presentGraphSegment(run.segment)} · v{run.caseVersion}{historySelectedId === run.id ? ' · 표시 중' : ''}
              </button>
            </li>
          ))}
        </ul>
      </details>
    )}
  </section>;
}
