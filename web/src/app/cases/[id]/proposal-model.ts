import type { CandidateSource, ProposalSource, ProposalView, SelectedProposal } from '../../api/contract.ts';

export function eligibleProposal(view: ProposalView | null): SelectedProposal | null {
  return view?.run.current && view.run.status === 'COMPLETED' && view.run.payloadHash && view.payload
    ? { proposalId: view.run.id, proposalHash: view.run.payloadHash } : null;
}
export function exactFact(value: string): string {
  if (!/^[0-9]{1,19}$/.test(value)) return '표시할 수 없는 수치';
  return new Intl.NumberFormat('ko-KR').format(BigInt(value));
}
// The same frozen source projection is shared by the v1 advisory and the graph
// workflow, so the quote/location reader only requires a `sources` list.
export function sourceQuote(view: { sources: ProposalSource[] }, source: CandidateSource): { quote: string; location: string } | null {
  const segment = view.sources.find(s => s.id === source.segmentId);
  if (!segment || !Number.isInteger(source.start) || !Number.isInteger(source.end)) return null;
  const points = Array.from(segment.text);
  if (source.start < 0 || source.end <= source.start || source.end > points.length) return null;
  return { quote: points.slice(source.start, source.end).join(''), location: segment.page !== null ? `원문 · ${segment.page}쪽${segment.origin === 'ocr' ? ' (OCR)' : ''}`
    : `원문 · 시트 ${segment.sheet} · 셀 ${segment.cell}` };
}
export function frozenProposal(payload: unknown): SelectedProposal | null {
  if (!payload || typeof payload !== 'object' || !('proposal' in payload)) return null;
  const p = payload.proposal;
  if (!p || typeof p !== 'object' || !('id' in p) || !('payloadHash' in p) || typeof p.id !== 'string' || typeof p.payloadHash !== 'string') return null;
  return { proposalId: p.id, proposalHash: p.payloadHash };
}
