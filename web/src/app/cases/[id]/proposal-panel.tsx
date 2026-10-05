import type { MatchResultView, ProposalView } from '../../api/contract';
import type { ProposalLoad } from './use-proposal-review';
import { exactFact, sourceQuote } from './proposal-model';
import { formatExactInteger } from './detail-model';

const labels: Record<string, string> = {
  QUEUED: '분석 대기', RUNNING: '분석 중', COMPLETED: '분석 완료', FAILED: '분석 실패', STALE: '이전 자료의 분석',
  APPROVAL_REVIEW: '승인 검토 초안', SUPPLEMENT_REQUEST: '보완 요청 초안', REJECTION_REVIEW: '거절 검토 초안',
  REVIEW_REQUIRED: '사람 검토 필요', INSUFFICIENT_EVIDENCE: '적용 근거 부족', POLICY_CONFLICT: '정책 근거 충돌',
  MAPPING_REVIEW: '품목 후보 확인 필요', DOCUMENT_REVIEW: '문서 후보 확인 필요', SPECIFICATION_MISMATCH: '규격 차이',
  AMBIGUOUS: '복수 후보', NO_CANDIDATES: '품목 후보 없음', MISSING_FIELDS: '누락된 항목', AMBIGUOUS_LAYOUT: '양식 확인 필요',
  EMPTY_DOCUMENT: '추출 항목 없음', DOCUMENT_CONFLICT: '문서 간 충돌', FOUND: '적용 근거 검색됨', CONFLICT: '근거 충돌', NOT_REQUIRED: '정상 대사로 정책 검색 생략',
  invoiceNumber: '청구 번호', supplierName: '공급사명', invoiceDate: '청구일', currency: '통화',
};
function Source({ view, source }: { view: ProposalView; source: { segmentId: string; start: number; end: number } }) {
  const proof = sourceQuote(view, source);
  return proof ? <details className="proposal-source"><summary>원문 위치</summary><p>{proof.location}</p><blockquote>{proof.quote}</blockquote></details> : <p>원문 위치를 표시할 수 없습니다.</p>;
}
export function ProposalPanel({ load, match, canReserve, pending, blocked, onReserve, onRefresh }: {
  load: ProposalLoad; match: MatchResultView | null; canReserve: boolean; pending: boolean; blocked: boolean; onReserve: () => void; onRefresh: () => void;
}) {
  if (load.status === 'forbidden') return null;
  if (load.status === 'loading') return <section className="form-section" aria-label="AI 검토 자료"><h2>AI 검토 자료</h2><p role="status">분석 상태를 확인하고 있습니다.</p></section>;
  if (load.status === 'error') return <section className="form-section" aria-label="AI 검토 자료"><h2>AI 검토 자료</h2><p role="alert">{load.message}</p><button className="button" onClick={onRefresh}>다시 조회</button></section>;
  if (load.status !== 'ready') return null;
  const page = load.data, view = page.latest, p = view?.payload;
  if (!page.enabled && !view && page.history.length === 0) return null;
  return <section className="form-section proposal-panel" aria-label="AI 검토 자료">
    <div className="section-heading"><h2>AI 검토 자료</h2><span>확정 입력과 사람의 검토 결정을 유지합니다</span></div>
    <p>추출값·품목 후보·처리 초안은 참고 자료입니다. 매핑과 승인·보완·거절은 아래 검토 동작에서 사람이 결정합니다.</p>
    {!page.enabled && <p className="review-note">AI 신규 분석이 비활성화되어 있습니다.</p>}
    <div className="dialog-actions">
      {canReserve && <button className="button" disabled={blocked || !page.enabled || !match} onClick={onReserve}>{pending ? '예약 중…' : 'AI 분석 예약'}</button>}
      <button className="button" disabled={blocked} onClick={onRefresh}>분석 상태 새로 조회</button>
    </div>
    {!view ? <p>예약된 AI 분석이 없습니다. 파서 완료와 최신 대사 후 예약할 수 있습니다.</p> : <>
      <div className="review-note" role="status"><strong>{labels[view.run.status]}</strong><span>시도 {view.run.attempt} · 예약 호출 {view.run.reservedCalls} · 예약 토큰 {view.run.reservedTokens} · 도구 {view.run.toolCalls}</span></div>
      {view.run.errorCode && (view.run.status === 'FAILED'
        ? <p role="alert">분석 오류: {view.run.errorCode}. 원인을 해결하고 최신 입력을 준비해야 합니다.</p>
        : <p>이전 시도 오류: {view.run.errorCode}. 현재 실행 상태와 누적 예산을 함께 확인하세요.</p>)}
      {(view.run.status === 'STALE' || (view.run.status === 'COMPLETED' && !view.run.current)) && <p className="review-warning">증빙·대사·매핑·구매 자료 또는 정책이 변경된 이전 분석입니다. 현재 검토 근거로 동결할 수 없습니다.</p>}
      {!p && <p>완료된 단계: {view.run.completedStages.join(', ') || '아직 없음'}. 완료 결과가 저장되면 후보와 근거가 표시됩니다.</p>}
      {p && <>
        <h3>처리 초안 · {labels[p.resolution.result.recommendation] ?? '검토 필요'}</h3><p>{p.resolution.result.summary}</p>
        {p.resolution.result.warnings.length > 0 && <ul>{p.resolution.result.warnings.map(w => <li key={w}>{labels[w] ?? w}</li>)}</ul>}
        <details><summary>서버 대사에서 확인한 수치</summary><dl className="proposal-facts">{Object.entries(p.facts).map(([id, fact]) => <div key={id}><dt>{id === 'invoiceTotal' ? '청구 합계' : id.replace('invoiceQuantity', '청구 수량').replace('invoiceUnitPrice', '청구 단가').replace('availableConfirmedQuantity', '검수 잔량').replace('plannedQuantity', '예상 배분 수량').replace('invoiceAmount', '청구 금액').replace('line:', '라인 ')}</dt><dd>{exactFact(fact.value)} {fact.unit === 'KRW' ? '원' : '개'}</dd></div>)}</dl></details>
        <h3>문서에서 추출한 후보</h3>
        {p.document.result.fields.map(f => <div className="proposal-entry" key={f.name}><strong>{labels[f.name] ?? f.name}</strong><span>{f.value}</span><Source view={view} source={f.source} /></div>)}
        <div className="table-scroll"><table><thead><tr><th>추출 순번</th><th>품목 표현</th><th>수량 후보</th><th>단가 후보</th></tr></thead><tbody>{p.document.result.lines.map(l => <tr key={l.lineNumber}><td>{l.lineNumber}</td><td>{l.rawItemName.value}<Source view={view} source={l.rawItemName.source} /></td><td>{l.quantity.value}<Source view={view} source={l.quantity.source} /></td><td>{l.unitPrice.value}<Source view={view} source={l.unitPrice.source} /></td></tr>)}</tbody></table></div>
        {p.document.result.warnings.length > 0 && <p>{p.document.result.warnings.map(w => labels[w] ?? w).join(' · ')}</p>}
        <details><summary>대사에 사용한 확정 입력과 비교</summary><p>추출 순번은 확정 입력의 라인 번호와 자동 연결하지 않습니다.</p><ul>{match?.payload.lineOutcomes.map(l => <li key={l.lineNumber}>라인 {l.lineNumber} · {l.rawItemName} · {formatExactInteger(l.invoiceQuantity)}개 · {formatExactInteger(l.invoiceUnitPrice)}원</li>)}</ul></details>
        <h3>품목 후보</h3>
        {p.mapping.result.lines.length === 0 && <p>표시할 품목 후보가 없습니다.</p>}
        {p.mapping.result.lines.map(l => <div className="proposal-entry" key={l.lineNumber}><strong>추출 순번 {l.lineNumber}{l.reviewRequired ? ' · 사람 확인 필요' : ''}</strong><Source view={view} source={l.source} />{l.warningCodes.length > 0 && <p>{l.warningCodes.map(w => labels[w] ?? w).join(' · ')}</p>}<ol>{l.candidates.map(c => <li key={`${c.itemId}:${c.purchaseOrderLineId}`}><strong>{c.itemId}</strong> · 발주 라인 {c.purchaseOrderLineId}<p>{c.reason}</p>{c.priorSnapshotId && <small>승인된 과거 검토 근거: {c.priorSnapshotId}</small>}</li>)}</ol></div>)}
        <h3>적용 정책 근거 · {labels[p.policyEvidence.status] ?? p.policyEvidence.status}</h3>
        {p.policyEvidence.result.map(chunk => <div className="proposal-entry" key={chunk.chunkId}><strong>{chunk.title} · 버전 {chunk.documentVersion} · {chunk.page}쪽 · 문단 {chunk.paragraph}</strong><blockquote>{chunk.text}</blockquote></div>)}
        {p.resolution.result.citations.map(c => <blockquote className="proposal-citation" key={c.chunkId}><strong>초안 인용 · 버전 {c.documentVersion} · {c.page}쪽 · 문단 {c.paragraph}</strong><p>{c.quote}</p></blockquote>)}
      </>}
    </>}
    {page.history.length > 1 && <details><summary>이전 분석 이력</summary><ul>{page.history.slice(1).map(r => <li key={r.id}>{labels[r.status]} · 시도 {r.attempt}{r.errorCode ? ` · ${r.errorCode}` : ''}</li>)}</ul></details>}
  </section>;
}
