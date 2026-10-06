'use client';

// Presentational (read-only) sections of the live detail screen, split out from
// the route so they can be rendered and asserted directly in tests. They hold no
// fetch or navigation logic. Audit text omits internal execution references.

import { Icon } from '../../ui';
import { Button } from '@/components/ui/button';
import { Table } from '@/components/ui/table';
import { formatInstant } from '../../api/contract';
import type {
  AuditEntryView,
  CaseHandoffStatus,
  EvidenceBundleSummary,
  InvoiceLineDetail,
  ReviewFreshness,
} from '../../api/contract';
import {
  claimLines,
  comparisonRows,
  decisionDetail,
  EXACT_RANGE_MESSAGE,
  formatExactInteger,
  formatNumber,
  freshnessVerdict,
  isExactInteger,
  isStaleMatch,
  latestBundle,
  presentAuditAction,
  presentDecision,
  presentFreshnessReason,
  presentLineStatus,
  presentMatchException,
  presentOutboxStatus,
  presentPaymentStatus,
  safeJsonStringify,
} from './detail-model';
import type { AuditFailure, CaseDetailData, SectionState } from './use-case-detail';

const TH = 'border-b border-border p-3 text-left text-label font-normal text-muted-foreground';
const TD = 'border-b border-[#efefe9] px-3 py-5';
const CTH = 'border-b border-[#eaeae3] bg-[#fcfcfa] p-[10px_11px] text-label font-normal text-muted-foreground';
const CTD = 'border-b border-[#f0f0eb] p-[15px_11px] h-[66px] align-middle';
const HEADING = 'section-heading mb-6 flex flex-wrap items-center justify-between gap-3';

const INTERNAL_AUDIT_FIELDS = new Set([
  'id', 'invoiceCaseId', 'caseId', 'targetId', 'requestId', 'traceId', 'eventId',
  'documentId', 'segmentId', 'chunkId', 'runId', 'analysisRunId', 'graphRunId',
  'graphId', 'threadId', 'interruptId', 'reviewId', 'reviewSnapshotId', 'snapshotId',
  'evidenceBundleId', 'matchResultId', 'proposalId', 'predecessorId', 'successorId',
  'checkpointId', 'checkpointRef', 'documentStageRef', 'mappingStageRef',
  'resolutionStageRef', 'reviewRef', 'schemaVersion', 'checkpointSchema', 'graphVersion',
  'reservedCalls', 'reservedTokens', 'toolCalls', 'startAttempts', 'resumeAttempts',
].map(key => key.toLowerCase()));

function auditBusinessValues(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(auditBusinessValues);
  if (value === null || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value)
    .filter(([key]) => !INTERNAL_AUDIT_FIELDS.has(key.replace(/[-_]/g, '').toLowerCase()) && !key.toLowerCase().endsWith('hash'))
    .map(([key, item]) => [key, auditBusinessValues(item)]));
}

function boundedJson(value: unknown): string {
  if (value === null || value === undefined) return '내용 없음';
  try {
    const text = safeJsonStringify(auditBusinessValues(value));
    if (!text) return '내용 없음';
    return text.length > 1200 ? `${text.slice(0, 1200)}…` : text;
  } catch {
    return '변경 내용을 표시할 수 없습니다.';
  }
}

function exactMoney(value: number): string {
  return isExactInteger(value) ? `₩ ${formatExactInteger(value)}` : EXACT_RANGE_MESSAGE;
}

type MessageTone = 'notice' | 'forbidden' | 'error';

export function SectionMessage({ tone, children }: { tone: MessageTone; children: React.ReactNode }) {
  return (
    <div className="review-warning mx-0 mb-3 flex items-center justify-between gap-3 border border-[#e8ddae] bg-[#faf4df] px-4 py-3 text-sm [&_p]:mt-1 [&_p]:text-[#716446]" role={tone === 'error' ? 'alert' : 'status'}>
      <div>{children}</div>
    </div>
  );
}

function EvidenceLines({ lines }: { lines: InvoiceLineDetail[] }) {
  return (
    <Table className="history-table">
      <caption className="sr-only">제출된 청구 라인</caption>
      <thead>
        <tr><th className={TH}>#</th><th className={TH}>품목</th><th className={TH}>확정 품목 ID</th><th className={`${TH} numeric text-right tabular-nums`}>수량</th><th className={`${TH} numeric text-right tabular-nums`}>단가 (원)</th></tr>
      </thead>
      <tbody>
        {lines.map((line) => (
          <tr key={line.lineNumber}>
            <td className={TD}>{line.lineNumber}</td>
            <td className={TD}>{line.rawItemName}</td>
            <td className={`${TD} muted-text text-label text-muted-foreground`}>{line.confirmedItemId ?? '매핑 미확정'}</td>
            <td className={`${TD} numeric text-right tabular-nums`}>{formatNumber(line.quantity)}</td>
            <td className={`${TD} numeric text-right tabular-nums`}>{formatExactInteger(line.unitPrice)}</td>
          </tr>
        ))}
        {lines.length === 0 && <tr><td colSpan={5} className={`${TD} empty-table p-12 text-center text-muted-foreground`}>표시할 청구 라인이 없습니다.</td></tr>}
      </tbody>
    </Table>
  );
}

// The server's currentness verdict is shown on every reviewer tab, not only the
// decision tab, so an old comparison is never read as a current one.
function FreshnessNotice({ data }: { data: CaseDetailData }) {
  if (!data.canReadReview) return null;
  const freshness = data.freshness;
  if (freshness.status === 'error') {
    return <SectionMessage tone="error">현재 자료와 일치 여부를 확인하지 못했습니다. {freshness.message}</SectionMessage>;
  }
  if (freshness.status !== 'ready') return null;
  if (freshness.data.current) return null;
  return (
    <SectionMessage tone="notice">
      <strong>현재 자료와 일치 여부: 불일치</strong>
      <p>{freshness.data.reasons.map((reason) => presentFreshnessReason(reason)).join(' · ')}</p>
    </SectionMessage>
  );
}

export function ComparePanel({ data }: { data: CaseDetailData }) {
  const { match } = data;
  const bundles = data.bundles.status === 'ready' ? data.bundles.data : [];
  const newest = latestBundle(bundles);
  const currentVersion = newest?.version ?? null;
  const currentHash = newest?.payloadHash ?? null;

  if (!data.canReadReview) {
    return <SectionMessage tone="forbidden">비교 결과는 승인자·운영자만 조회할 수 있습니다. 제출한 청구 라인은 &lsquo;제출 이력&rsquo; 탭에서 확인할 수 있습니다.</SectionMessage>;
  }
  if (match.status === 'forbidden') {
    return <><FreshnessNotice data={data} /><SectionMessage tone="forbidden">서버가 이 계정의 비교 결과 조회를 허용하지 않았습니다.</SectionMessage></>;
  }
  if (match.status === 'empty') {
    return <><FreshnessNotice data={data} /><SectionMessage tone="notice">아직 대사(비교) 결과가 없습니다. 운영자가 대사를 실행하면 최신 비교 결과가 표시됩니다.</SectionMessage></>;
  }
  if (match.status === 'error') {
    return <><FreshnessNotice data={data} /><SectionMessage tone="error">비교 결과를 불러오지 못했습니다. {match.message}</SectionMessage></>;
  }

  const rows = comparisonRows(match.data);
  const issues = rows.filter((row) => row.issues.length > 0 || row.status !== 'MATCHED').length;
  const caseExceptions = match.data.payload.exceptions;
  const bundleVersion = match.data.payload.evidenceBundle.version;
  const stale = isStaleMatch(match.data, currentVersion, currentHash);

  return (
    <>
      <FreshnessNotice data={data} />
      {stale && (
        <SectionMessage tone="notice">
          <strong>이전 제출자료 기준 비교 결과 (오래된 결과)</strong>
          <p>이 비교 결과는 증빙 v{bundleVersion} 기준이며 현재 증빙은 {currentVersion === null ? '—' : `v${currentVersion}`}입니다. 현재 청구의 승인 근거로 사용할 수 없습니다.</p>
        </SectionMessage>
      )}
      <div className="toolbar flex min-h-[46px] flex-wrap items-center gap-2.5 border-b border-border bg-[#faf9f6] px-11 py-3 max-[1200px]:px-[30px] max-[760px]:px-5">
        <span className={`line-badge inline-flex items-center gap-1.5 rounded-full border border-transparent px-2 py-1 text-label leading-tight ${match.data.payload.normal ? 'bg-[#f2f3ee] text-muted-foreground' : 'exception bg-[#f2edde] text-[#886c37]'}`}>
          {match.data.payload.normal ? <><Icon name="check" size={12} />당시 자료 서버판정: 정상</> : <><span className="exception-dot inline-block h-[5px] w-[5px] shrink-0 rounded-full bg-[#b18039]" />당시 자료 서버판정: 확인 필요</>}
        </span>
        <span className="demo-description text-label text-muted-foreground">
          비교 결과 #{match.data.resultNumber} · 증빙 v{bundleVersion} 기준{stale ? ' · 오래된 결과' : ''} · 판정 {issues}개 라인
        </span>
      </div>
      {caseExceptions.length > 0 && (
        <SectionMessage tone="notice">
          <strong>확인 필요 항목 (청구서 전체)</strong>
          <p>{caseExceptions.map((exception) => presentMatchException(exception.type)).join(' · ')}</p>
        </SectionMessage>
      )}
      <div className="table-area min-w-0 flex-1">
        <Table className="comparison-table table-scroll whitespace-nowrap">
          <caption className="sr-only">발주·검수·청구 비교. 서버 대사 결과의 라인별 값을 그대로 표시합니다.</caption>
          <thead>
            <tr>
              <th className={`${CTH} line-number w-11 text-center pl-5 pr-[13px]`}>#</th>
              <th className={`${CTH} item-column min-w-[220px]`}>품목 / 발주 라인</th>
              <th className={`${CTH} numeric text-right tabular-nums`}>청구 수량</th>
              <th className={`${CTH} numeric text-right tabular-nums`}>발주 수량</th>
              <th className={`${CTH} numeric text-right tabular-nums`}>검수 가용</th>
              <th className={`${CTH} numeric text-right tabular-nums`}>예상 배분</th>
              <th className={`${CTH} numeric text-right tabular-nums`}>청구 단가</th>
              <th className={`${CTH} numeric text-right tabular-nums`}>발주 단가</th>
              <th className={`${CTH} result-column min-w-[170px]`}>판정 / 확인 사유</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.lineNumber}>
                <td className={`${CTD} line-number w-11 text-center pl-5 pr-[13px]`}>{String(row.lineNumber).padStart(2, '0')}</td>
                <td className={CTD}>
                  <strong className="item-name mb-1.5 block text-sm">{row.rawItemName}</strong>
                  <span className="item-secondary flex items-center gap-1.5 text-label text-muted-foreground">{row.confirmedItemId ?? '매핑 미확정'}<span>·</span>{row.hasPurchaseOrderLine ? '발주 라인 확정' : '발주 라인 미확정'}</span>
                </td>
                <td className={`${CTD} numeric text-right tabular-nums`}>{formatNumber(row.invoiceQuantity)}</td>
                <td className={`${CTD} numeric text-right tabular-nums`}>{row.orderedQuantity === null ? '—' : formatNumber(row.orderedQuantity)}</td>
                <td className={`${CTD} numeric text-right tabular-nums`}>{formatNumber(row.availableConfirmedQuantity)}</td>
                <td className={`${CTD} numeric text-right tabular-nums`}>{formatNumber(row.plannedQuantity)}</td>
                <td className={`${CTD} numeric text-right tabular-nums`}>{formatExactInteger(row.invoiceUnitPrice)}</td>
                <td className={`${CTD} numeric text-right tabular-nums`}>{row.poUnitPrice === null ? '—' : formatExactInteger(row.poUnitPrice)}</td>
                <td className={CTD}>
                  <span className={`line-badge inline-flex items-center gap-1.5 rounded-full border border-transparent px-2 py-1 text-label leading-tight ${row.status === 'MATCHED' && row.issues.length === 0 ? 'bg-[#f2f3ee] text-muted-foreground' : 'exception bg-[#f2edde] text-[#886c37]'}`}>
                    {row.status === 'MATCHED' && row.issues.length === 0 ? <><Icon name="check" size={12} />일치</> : <><span className="exception-dot inline-block h-[5px] w-[5px] shrink-0 rounded-full bg-[#b18039]" />{presentLineStatus(row.status)}</>}
                  </span>
                  {row.issues.map((issue) => <span key={issue.type} className="issue-description mt-1.5 block text-label text-warning">{issue.label}</span>)}
                </td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={9} className={`${CTD} empty-table p-12 text-center text-muted-foreground`}>대사 결과에 청구 라인이 없습니다.</td></tr>}
          </tbody>
        </Table>
        <div className="table-summary flex justify-between gap-4 border-b border-[#efefe9] px-6 py-4 text-label text-muted-foreground">
          <span>{rows.length}개 라인 · 검수 가용과 예상 배분은 서버 대사 결과 값입니다.</span>
        </div>
      </div>
    </>
  );
}

export function EvidencePanel({ data }: { data: CaseDetailData }) {
  const bundles: EvidenceBundleSummary[] = data.bundles.status === 'ready' ? data.bundles.data : [];
  const sealedPayloadLines = data.sealed.status === 'ready' ? claimLines(data.detail, data.sealed.data) : claimLines(data.detail, null);
  const newest = latestBundle(bundles);

  let bundleBody: React.ReactNode;
  if (data.bundles.status === 'forbidden') {
    bundleBody = <SectionMessage tone="forbidden">증빙 목록을 조회할 권한이 없습니다.</SectionMessage>;
  } else if (data.bundles.status === 'error') {
    bundleBody = <SectionMessage tone="error">증빙 목록을 불러오지 못했습니다. {data.bundles.message}</SectionMessage>;
  } else if (bundles.length > 0) {
    bundleBody = (
      <>
        <Table className="history-table">
          <caption className="sr-only">제출 이력 목록</caption>
          <thead><tr><th className={TH}>제출 차수</th><th className={TH}>제출 시각 (KST)</th></tr></thead>
          <tbody>
            {bundles.map((bundle) => (
              <tr key={bundle.version}>
                <td className={TD}>#{bundle.version}{newest?.version === bundle.version ? ' · 최신' : ''}</td>
                <td className={TD}>{formatInstant(bundle.submittedAt)}</td>
              </tr>
            ))}
          </tbody>
        </Table>
      </>
    );
  } else {
    bundleBody = <SectionMessage tone="notice">{data.detail.status === 'DRAFT' ? '작성 중이라 아직 동결된 증빙이 없습니다.' : '동결된 증빙 버전이 없습니다.'}</SectionMessage>;
  }

  let linesBody: React.ReactNode;
  if (data.sealed.status === 'forbidden') {
    linesBody = <SectionMessage tone="forbidden">증빙 본문을 조회할 권한이 없습니다.</SectionMessage>;
  } else if (data.sealed.status === 'error') {
    linesBody = <SectionMessage tone="error">증빙 본문을 불러오지 못했습니다. {data.sealed.message}</SectionMessage>;
  } else if (sealedPayloadLines.lines.length > 0) {
    linesBody = <EvidenceLines lines={sealedPayloadLines.lines} />;
  } else {
    linesBody = <SectionMessage tone="notice">표시할 청구 라인이 없습니다.</SectionMessage>;
  }

  return (
    <div className="history-content p-8 max-[760px]:px-5 max-[760px]:py-[25px]">
      <div className={HEADING}>
        <h2 className="text-lg font-medium">제출 이력</h2>
        <span className="text-label text-muted-foreground">
          {data.bundles.status === 'forbidden'
            ? '제출 이력 권한 없음'
            : data.bundles.status === 'error'
              ? '제출 이력 조회 실패'
              : bundles.length > 0
                ? `제출 ${bundles.length}건`
                : '제출된 이력 없음'}
        </span>
      </div>
      {bundleBody}
      <div className="section-heading mb-6 flex flex-wrap items-center justify-between gap-3"><h3 className="text-base font-medium">{sealedPayloadLines.source === 'draft' ? '작성 중 청구 라인' : `최신 제출 본문${newest ? ` · 제출 차수 #${newest.version}` : ''}`}</h3></div>
      {linesBody}
    </div>
  );
}

function FreshnessBlock({ freshness }: { freshness: SectionState<ReviewFreshness> }) {
  if (freshness.status === 'empty') {
    return <SectionMessage tone="notice">현재 자료와 일치 여부를 확인할 수 있는 검토 대상이 없습니다.</SectionMessage>;
  }
  if (freshness.status === 'forbidden') {
    return <SectionMessage tone="forbidden">현재 자료와 일치 여부는 승인자·운영자만 조회할 수 있습니다.</SectionMessage>;
  }
  if (freshness.status === 'error') {
    return <SectionMessage tone="error">현재 자료와 일치 여부를 불러오지 못했습니다. {freshness.message}</SectionMessage>;
  }
  const detail = freshness.data;
  return (
    <div className={`panel-status mt-6 border-y border-border py-5 ${detail.current ? 'text-[#66755a]' : 'warning text-[#8e733e]'}`}>
      <strong className="text-label font-medium">현재 자료와 일치 여부: {freshnessVerdict(detail)}</strong>
      {detail.current
        ? <p className="mt-[7px] text-label leading-[1.8] text-muted-foreground">서버가 현재 사건·증빙·대사·매핑·구매 스냅샷과 일치한다고 판정했습니다.</p>
        : <p className="mt-[7px] text-label leading-[1.8] text-muted-foreground">불일치 사유: {detail.reasons.map((reason) => presentFreshnessReason(reason)).join(' · ')}</p>}
    </div>
  );
}

export function DecisionsPanel({ data }: { data: CaseDetailData }) {
  if (!data.canReadReview) {
    return <SectionMessage tone="forbidden">검토 결정과 검토 대상은 승인자·운영자만 조회할 수 있습니다.</SectionMessage>;
  }
  const { snapshot, decisions } = data;
  return (
    <div className="history-content p-8 max-[760px]:px-5 max-[760px]:py-[25px]">
      <div className={HEADING}><h2 className="text-lg font-medium">검토 대상</h2></div>
      {snapshot.status === 'empty'
        ? <SectionMessage tone="notice">아직 동결된 검토 대상(스냅샷)이 없습니다.</SectionMessage>
        : snapshot.status === 'forbidden'
          ? <SectionMessage tone="forbidden">검토 대상 조회가 허용되지 않았습니다.</SectionMessage>
          : snapshot.status === 'error'
            ? <SectionMessage tone="error">검토 대상을 불러오지 못했습니다. {snapshot.message}</SectionMessage>
            : <>
                <dl className="receipt-data my-[22px] text-label">
                  <div className="mb-[13px] flex justify-between"><dt>검토 대상 번호</dt><dd className="m-0">#{snapshot.data.snapshotNumber}</dd></div>
                  <div className="mb-[13px] flex justify-between"><dt>대상 청구서 변경 버전</dt><dd className="m-0">v{snapshot.data.targetCaseVersion}</dd></div>
                  <div className="mb-[13px] flex justify-between"><dt>증빙 버전</dt><dd className="m-0">v{snapshot.data.evidenceBundleVersion}</dd></div>
                  <div className="mb-[13px] flex justify-between"><dt>비교 결과 번호</dt><dd className="m-0">{snapshot.data.matchResultNumber === null ? '—' : `#${snapshot.data.matchResultNumber}`}</dd></div>
                  <div className="mb-[13px] flex justify-between"><dt>생성 시각 (KST)</dt><dd className="m-0">{formatInstant(snapshot.data.createdAt)}</dd></div>
                </dl>
                <FreshnessBlock freshness={data.freshness} />
              </>}
      <div className="section-heading mb-6 flex flex-wrap items-center justify-between gap-3"><h3 className="text-base font-medium">결정 이력</h3></div>
      {decisions.status === 'empty'
        ? <SectionMessage tone="notice">아직 기록된 결정이 없습니다.</SectionMessage>
        : decisions.status === 'forbidden'
          ? <SectionMessage tone="forbidden">결정 이력 조회가 허용되지 않았습니다.</SectionMessage>
          : decisions.status === 'error'
            ? <SectionMessage tone="error">결정 이력을 불러오지 못했습니다. {decisions.message}</SectionMessage>
            : <Table className="history-table">
                <caption className="sr-only">검토 결정 이력</caption>
                <thead><tr><th className={TH}>결정</th><th className={TH}>작업자</th><th className={TH}>결정 번호</th><th className={TH}>내용 / 사유</th><th className={TH}>시각 (KST)</th></tr></thead>
                <tbody>
                  {decisions.data.map((decision) => (
                    <tr key={decision.id}>
                      <td className={TD}>{presentDecision(decision.decision)}</td>
                      <td className={TD}>{decision.decidedBy}</td>
                      <td className={TD}>#{decision.decisionNumber}</td>
                      <td className={TD}>{decisionDetail(decision)}</td>
                      <td className={`${TD} muted-text text-label text-muted-foreground`}>{formatInstant(decision.decidedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </Table>}
    </div>
  );
}

export function AuditPanel({ data, entries, nextCursor, loadingMore, error, onMore }: {
  data: CaseDetailData;
  entries: AuditEntryView[];
  nextCursor: string | null;
  loadingMore: boolean;
  error: AuditFailure | null;
  onMore: () => void;
}) {
  if (!data.canReadReview) {
    return <SectionMessage tone="forbidden">감사 이력은 승인자·운영자만 조회할 수 있습니다.</SectionMessage>;
  }
  if (data.audit.status === 'error') {
    return <SectionMessage tone="error">감사 이력을 불러오지 못했습니다. {data.audit.message}</SectionMessage>;
  }
  if (data.audit.status === 'forbidden') {
    return <SectionMessage tone="forbidden">감사 이력 조회가 허용되지 않았습니다.</SectionMessage>;
  }
  if (data.audit.status === 'empty') {
    return <SectionMessage tone="notice">아직 감사 기록이 없습니다.</SectionMessage>;
  }
  return (
    <div className="history-content p-8 max-[760px]:px-5 max-[760px]:py-[25px]">
      <div className={HEADING}><h2 className="text-lg font-medium">감사 이력</h2><span className="text-label text-muted-foreground">서버 최신순 페이지</span></div>
      {error && (
        <SectionMessage tone={error.kind === 'forbidden' ? 'forbidden' : 'error'}>
          <strong>{error.kind === 'forbidden' ? '감사 기록을 더 불러올 수 없습니다' : '감사 기록을 더 불러오지 못했습니다'}</strong>
          <p>{error.message}</p>
          <p className="muted-text text-label text-muted-foreground">지금 화면의 감사 기록과 다음 위치는 그대로 유지됩니다.</p>
        </SectionMessage>
      )}
      <Table className="history-table audit-table table-fixed">
        <caption className="sr-only">사건 감사 이력</caption>
        <thead><tr><th className={`${TH} w-[22%]`}>시간 (KST)</th><th className={`${TH} w-[14%]`}>작업자</th><th className={`${TH} w-[20%]`}>작업</th><th className={`${TH} w-[44%]`}>변경 내용</th></tr></thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.id}>
              <td className={`${TD} align-top [overflow-wrap:anywhere]`}>{formatInstant(entry.occurredAt)}</td>
              <td className={`${TD} align-top [overflow-wrap:anywhere]`}>{entry.actor}</td>
              <td className={`${TD} align-top [overflow-wrap:anywhere]`}>{presentAuditAction(entry.action)}</td>
              <td className={`${TD} align-top [overflow-wrap:anywhere]`}>
                <details>
                  <summary className="flex cursor-pointer list-none justify-between gap-2.5 after:content-['⌄'] after:shrink-0 [&::-webkit-details-marker]:hidden [[open]_&]:after:content-['⌃']">변경 내용 · 청구서 변경 버전 {entry.businessVersion}</summary>
                  <p className="mt-3 leading-[1.8] text-[#737b65]">{boundedJson(entry.after)}</p>
                </details>
              </td>
            </tr>
          ))}
        </tbody>
      </Table>
      <div className="audit-pagination my-6 mb-4 flex min-h-9 items-center justify-center">
        {nextCursor
          ? <Button variant="outline" className="button" onClick={onMore} disabled={loadingMore}>{loadingMore ? '불러오는 중…' : error ? '이전 기록 다시 시도' : '이전 기록 더 보기'}</Button>
          : <p className="m-0 text-label text-[#74786e]" role="status">마지막 기록입니다.</p>}
      </div>
    </div>
  );
}

export function HandoffStrip({ handoff }: { handoff: SectionState<CaseHandoffStatus> }) {
  if (handoff.status === 'forbidden') {
    return <SectionMessage tone="forbidden">ERP 인계 상태를 조회할 권한이 없습니다.</SectionMessage>;
  }
  if (handoff.status === 'error') {
    return <SectionMessage tone="error">ERP 인계 상태를 불러오지 못했습니다. {handoff.message}</SectionMessage>;
  }
  if (handoff.status !== 'ready') return null;
  const { payment } = handoff.data;
  if (!payment) {
    return (
      <div className="review-note mx-[31px] my-[26px] flex items-center gap-2 text-label text-muted-foreground">
        <span className="status-dot inline-block h-[5px] w-[5px] shrink-0 rounded-full bg-[#828753]" />
        <span>아직 승인·인계 전입니다. 지급요청과 금액은 승인 시 서버가 생성합니다.</span>
      </div>
    );
  }
  return (
    <div className="review-note mx-[31px] my-[26px] flex items-center gap-2 text-label text-muted-foreground">
      <span className="status-dot inline-block h-[5px] w-[5px] shrink-0 rounded-full bg-[#828753]" />
      <span>
        ERP 인계: 지급 {presentPaymentStatus(payment.paymentStatus)} · 아웃박스 {payment.outboxStatus ? presentOutboxStatus(payment.outboxStatus) : '—'} · 시도 {payment.attemptCount}회
        {payment.lastErrorCode ? ` · 오류 ${payment.lastErrorCode}` : ''}
      </span>
    </div>
  );
}

export function HandoffSummary({ handoff, payment }: { handoff: SectionState<CaseHandoffStatus>; payment: CaseHandoffStatus['payment'] }) {
  const amount = payment ? exactMoney(payment.amount) : null;
  const caption = payment
    ? '승인된 지급 금액 · 서버 값'
    : handoff.status === 'forbidden'
      ? 'ERP 인계/지급 상태를 조회할 권한이 없습니다.'
      : handoff.status === 'error'
        ? '인계 상태를 불러오지 못해 지급 금액을 확인하지 못했습니다.'
        : '승인 전에는 서버가 지급 금액을 확정하지 않습니다.';
  return (
    <div className="action-summary flex items-center gap-4">
      <strong className="text-[17px] tracking-[.1px] tabular-nums max-[760px]:text-sm">{amount ?? (handoff.status === 'error' || handoff.status === 'forbidden' ? '인계 상태 확인 필요' : '금액 미확정')}</strong>
      <span className="text-label text-white max-[760px]:hidden">{caption}</span>
    </div>
  );
}
