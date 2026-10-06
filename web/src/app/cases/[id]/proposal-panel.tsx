import { Button } from '@/components/ui/button';
import { Table } from '@/components/ui/table';
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
const SECTION = 'form-section proposal-panel mx-11 my-6 min-w-0 [overflow-wrap:anywhere] max-[1200px]:mx-[30px] max-[760px]:mx-5';
const HEADING = 'section-heading mb-6 flex flex-wrap items-center justify-between gap-3';
const NOTE = 'review-note mx-[31px] my-[26px] flex items-center gap-2 text-label text-[#838572]';
const ENTRY = 'proposal-entry border-b border-[#e0e2d9] py-3';
const BLOCKQUOTE = 'my-2.5 whitespace-pre-wrap border-l-[3px] border-border bg-[#f5f6f2] px-4 py-2.5';
function Source({ view, source }: { view: ProposalView; source: { segmentId: string; start: number; end: number } }) {
  const proof = sourceQuote(view, source);
  return proof ? <details className="proposal-source mt-1.5 text-label"><summary>원문 위치</summary><p>{proof.location}</p><blockquote className={BLOCKQUOTE}>{proof.quote}</blockquote></details> : <p>원문 위치를 표시할 수 없습니다.</p>;
}
export function ProposalPanel({ load, match, canReserve, pending, blocked, onReserve, onRefresh }: {
  load: ProposalLoad; match: MatchResultView | null; canReserve: boolean; pending: boolean; blocked: boolean; onReserve: () => void; onRefresh: () => void;
}) {
  if (load.status === 'forbidden') return null;
  if (load.status === 'loading') return <section className="form-section proposal-panel mx-11 my-6 min-w-0 [overflow-wrap:anywhere] max-[1200px]:mx-[30px] max-[760px]:mx-5" aria-label="AI 검토 자료"><h2 className="text-lg font-medium">AI 검토 자료</h2><p className="my-3" role="status">분석 상태를 확인하고 있습니다.</p></section>;
  if (load.status === 'error') return <section className="form-section proposal-panel mx-11 my-6 min-w-0 [overflow-wrap:anywhere] max-[1200px]:mx-[30px] max-[760px]:mx-5" aria-label="AI 검토 자료"><h2 className="text-lg font-medium">AI 검토 자료</h2><p className="my-3" role="alert">{load.message}</p><Button variant="outline" className="button" onClick={onRefresh}>다시 조회</Button></section>;
  if (load.status !== 'ready') return null;
  const page = load.data, view = page.latest, p = view?.payload;
  if (!page.enabled && !view && page.history.length === 0) return null;
  return <section className={SECTION} aria-label="AI 검토 자료">
    <div className={HEADING}><h2 className="text-lg font-medium">AI 검토 자료</h2><span className="text-label text-muted-foreground">확정 입력과 사람의 검토 결정을 유지합니다</span></div>
    <p className="my-3">추출값·품목 후보·처리 초안은 참고 자료입니다. 매핑과 승인·보완·거절은 아래 검토 동작에서 사람이 결정합니다.</p>
    {!page.enabled && <p className="review-note mx-[31px] my-[26px] flex items-center gap-2 text-label text-[#838572]">AI 신규 분석이 비활성화되어 있습니다.</p>}
    <div className="dialog-actions mt-6 flex justify-end gap-2">
      {canReserve && <Button variant="outline" className="button" disabled={blocked || !page.enabled || !match} onClick={onReserve}>{pending ? '예약 중…' : 'AI 분석 예약'}</Button>}
      <Button variant="outline" className="button" disabled={blocked} onClick={onRefresh}>분석 상태 새로 조회</Button>
    </div>
    {!view ? <p className="my-3">예약된 AI 분석이 없습니다. 파서 완료와 최신 대사 후 예약할 수 있습니다.</p> : <>
      <div className={NOTE} role="status"><strong>{labels[view.run.status]}</strong><span>시도 {view.run.attempt} · 예약 호출 {view.run.reservedCalls} · 예약 토큰 {view.run.reservedTokens} · 도구 {view.run.toolCalls}</span></div>
      {view.run.errorCode && (view.run.status === 'FAILED'
        ? <p className="my-3" role="alert">분석 오류: {view.run.errorCode}. 원인을 해결하고 최신 입력을 준비해야 합니다.</p>
        : <p className="my-3">이전 시도 오류: {view.run.errorCode}. 현재 실행 상태와 누적 예산을 함께 확인하세요.</p>)}
      {(view.run.status === 'STALE' || (view.run.status === 'COMPLETED' && !view.run.current)) && <p className="review-warning mx-0 mb-3 border border-[#e8ddae] bg-[#faf4df] px-4 py-3 text-sm text-[#716446]">증빙·대사·매핑·구매 자료 또는 정책이 변경된 이전 분석입니다. 현재 검토 근거로 동결할 수 없습니다.</p>}
      {!p && <p className="my-3">완료된 단계: {view.run.completedStages.join(', ') || '아직 없음'}. 완료 결과가 저장되면 후보와 근거가 표시됩니다.</p>}
      {p && <>
        <h3 className="mt-6 text-base font-medium">처리 초안 · {labels[p.resolution.result.recommendation] ?? '검토 필요'}</h3><p className="my-3">{p.resolution.result.summary}</p>
        {p.resolution.result.warnings.length > 0 && <ul>{p.resolution.result.warnings.map(w => <li key={w}>{labels[w] ?? w}</li>)}</ul>}
        <details><summary>서버 대사에서 확인한 수치</summary><dl className="proposal-facts">{Object.entries(p.facts).map(([id, fact]) => <div key={id} className="flex justify-between gap-4 py-[.3rem]"><dt>{id === 'invoiceTotal' ? '청구 합계' : id.replace('invoiceQuantity', '청구 수량').replace('invoiceUnitPrice', '청구 단가').replace('availableConfirmedQuantity', '검수 잔량').replace('plannedQuantity', '예상 배분 수량').replace('invoiceAmount', '청구 금액').replace('line:', '라인 ')}</dt><dd className="m-0">{exactFact(fact.value)} {fact.unit === 'KRW' ? '원' : '개'}</dd></div>)}</dl></details>
        <h3 className="mt-6 text-base font-medium">문서에서 추출한 후보</h3>
        {p.document.result.fields.map(f => <div className={ENTRY} key={f.name}><strong>{labels[f.name] ?? f.name}</strong><span className="ml-4">{f.value}</span><Source view={view} source={f.source} /></div>)}
        <Table className="table-scroll"><thead><tr><th>추출 순번</th><th>품목 표현</th><th>수량 후보</th><th>단가 후보</th></tr></thead><tbody>{p.document.result.lines.map(l => <tr key={l.lineNumber}><td>{l.lineNumber}</td><td>{l.rawItemName.value}<Source view={view} source={l.rawItemName.source} /></td><td>{l.quantity.value}<Source view={view} source={l.quantity.source} /></td><td>{l.unitPrice.value}<Source view={view} source={l.unitPrice.source} /></td></tr>)}</tbody></Table>
        {p.document.result.warnings.length > 0 && <p className="my-3">{p.document.result.warnings.map(w => labels[w] ?? w).join(' · ')}</p>}
        <details><summary>대사에 사용한 확정 입력과 비교</summary><p className="my-3">추출 순번은 확정 입력의 라인 번호와 자동 연결하지 않습니다.</p><ul>{match?.payload.lineOutcomes.map(l => <li key={l.lineNumber}>라인 {l.lineNumber} · {l.rawItemName} · {formatExactInteger(l.invoiceQuantity)}개 · {formatExactInteger(l.invoiceUnitPrice)}원</li>)}</ul></details>
        <h3 className="mt-6 text-base font-medium">품목 후보</h3>
        {p.mapping.result.lines.length === 0 && <p className="my-3">표시할 품목 후보가 없습니다.</p>}
        {p.mapping.result.lines.map(l => <div className={ENTRY} key={l.lineNumber}><strong>추출 순번 {l.lineNumber}{l.reviewRequired ? ' · 사람 확인 필요' : ''}</strong><Source view={view} source={l.source} />{l.warningCodes.length > 0 && <p className="my-3">{l.warningCodes.map(w => labels[w] ?? w).join(' · ')}</p>}<ol>{l.candidates.map(c => <li key={`${c.itemId}:${c.purchaseOrderLineId}`}><strong>{c.itemId}</strong> · 발주 라인 {c.purchaseOrderLineId}<p className="my-3">{c.reason}</p>{c.priorSnapshotId && <small>승인된 과거 검토 근거: {c.priorSnapshotId}</small>}</li>)}</ol></div>)}
        <h3 className="mt-6 text-base font-medium">적용 정책 근거 · {labels[p.policyEvidence.status] ?? p.policyEvidence.status}</h3>
        {p.policyEvidence.result.map(chunk => <div className={ENTRY} key={chunk.chunkId}><strong>{chunk.title} · 버전 {chunk.documentVersion} · {chunk.page}쪽 · 문단 {chunk.paragraph}</strong><blockquote className={BLOCKQUOTE}>{chunk.text}</blockquote></div>)}
        {p.resolution.result.citations.map(c => <blockquote className={`proposal-citation ${BLOCKQUOTE}`} key={c.chunkId}><strong>초안 인용 · 버전 {c.documentVersion} · {c.page}쪽 · 문단 {c.paragraph}</strong><p>{c.quote}</p></blockquote>)}
      </>}
    </>}
    {page.history.length > 1 && <details><summary>이전 분석 이력</summary><ul>{page.history.slice(1).map(r => <li key={r.id}>{labels[r.status]} · 시도 {r.attempt}{r.errorCode ? ` · ${r.errorCode}` : ''}</li>)}</ul></details>}
  </section>;
}
