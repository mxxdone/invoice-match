'use client';

// Presentational (read-only) sections of the live detail screen, split out from
// the route so they can be rendered and asserted directly in tests. They hold no
// fetch or navigation logic and render server DTO values verbatim.

import { Icon } from '../../ui';
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

export function shortId(value: string | null | undefined): string {
  if (!value) return '—';
  return value.length > 8 ? value.slice(0, 8) : value;
}

function boundedJson(value: unknown): string {
  if (value === null || value === undefined) return '내용 없음';
  try {
    const text = safeJsonStringify(value);
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
    <div className="review-warning" role={tone === 'error' ? 'alert' : 'status'}>
      <div>{children}</div>
    </div>
  );
}

function EvidenceLines({ lines }: { lines: InvoiceLineDetail[] }) {
  return (
    <table className="history-table">
      <caption className="sr-only">제출된 청구 라인</caption>
      <thead>
        <tr><th>#</th><th>품목</th><th>확정 품목 ID</th><th className="numeric">수량</th><th className="numeric">단가 (원)</th></tr>
      </thead>
      <tbody>
        {lines.map((line) => (
          <tr key={line.lineNumber}>
            <td>{line.lineNumber}</td>
            <td>{line.rawItemName}</td>
            <td className="muted-text">{line.confirmedItemId ?? '매핑 미확정'}</td>
            <td className="numeric">{formatNumber(line.quantity)}</td>
            <td className="numeric">{formatExactInteger(line.unitPrice)}</td>
          </tr>
        ))}
        {lines.length === 0 && <tr><td colSpan={5} className="empty-table">표시할 청구 라인이 없습니다.</td></tr>}
      </tbody>
    </table>
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
      <div className="toolbar">
        <span className={`line-badge ${match.data.payload.normal ? '' : 'exception'}`}>
          {match.data.payload.normal ? <><Icon name="check" size={12} />당시 자료 서버판정: 정상</> : <><span className="exception-dot" />당시 자료 서버판정: 확인 필요</>}
        </span>
        <span className="demo-description">
          비교 결과 #{match.data.resultNumber} · 증빙 v{bundleVersion} 기준{stale ? ' · 오래된 결과' : ''} · 판정 {issues}개 라인
        </span>
      </div>
      {caseExceptions.length > 0 && (
        <SectionMessage tone="notice">
          <strong>확인 필요 항목 (청구서 전체)</strong>
          <p>{caseExceptions.map((exception) => presentMatchException(exception.type)).join(' · ')}</p>
        </SectionMessage>
      )}
      <div className="table-area">
        <div className="table-scroll">
          <table className="comparison-table">
            <caption className="sr-only">발주·검수·청구 비교. 서버 대사 결과의 라인별 값을 그대로 표시합니다.</caption>
            <thead>
              <tr>
                <th className="line-number">#</th>
                <th className="item-column">품목 / 발주 라인</th>
                <th className="numeric">청구 수량</th>
                <th className="numeric">발주 수량</th>
                <th className="numeric">검수 가용</th>
                <th className="numeric">예상 배분</th>
                <th className="numeric">청구 단가</th>
                <th className="numeric">발주 단가</th>
                <th className="result-column">판정 / 확인 사유</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.lineNumber}>
                  <td className="line-number">{String(row.lineNumber).padStart(2, '0')}</td>
                  <td>
                    <strong className="item-name">{row.rawItemName}</strong>
                    <span className="item-secondary">{row.confirmedItemId ?? '매핑 미확정'}<span>·</span>{row.hasPurchaseOrderLine ? '발주 라인 확정' : '발주 라인 미확정'}</span>
                  </td>
                  <td className="numeric">{formatNumber(row.invoiceQuantity)}</td>
                  <td className="numeric">{row.orderedQuantity === null ? '—' : formatNumber(row.orderedQuantity)}</td>
                  <td className="numeric">{formatNumber(row.availableConfirmedQuantity)}</td>
                  <td className="numeric">{formatNumber(row.plannedQuantity)}</td>
                  <td className="numeric">{formatExactInteger(row.invoiceUnitPrice)}</td>
                  <td className="numeric">{row.poUnitPrice === null ? '—' : formatExactInteger(row.poUnitPrice)}</td>
                  <td>
                    <span className={`line-badge ${row.status === 'MATCHED' && row.issues.length === 0 ? '' : 'exception'}`}>
                      {row.status === 'MATCHED' && row.issues.length === 0 ? <><Icon name="check" size={12} />일치</> : <><span className="exception-dot" />{presentLineStatus(row.status)}</>}
                    </span>
                    {row.issues.map((issue) => <span key={issue.type} className="issue-description">{issue.label}</span>)}
                  </td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={9} className="empty-table">대사 결과에 청구 라인이 없습니다.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="table-summary">
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
        <table className="history-table">
          <caption className="sr-only">제출 이력 목록</caption>
          <thead><tr><th>제출 차수</th><th>제출 시각 (KST)</th></tr></thead>
          <tbody>
            {bundles.map((bundle) => (
              <tr key={bundle.version}>
                <td>#{bundle.version}{newest?.version === bundle.version ? ' · 최신' : ''}</td>
                <td>{formatInstant(bundle.submittedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
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
    <div className="history-content">
      <div className="section-heading">
        <h2>제출 이력</h2>
        <span>
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
      <div className="section-heading"><h3>{sealedPayloadLines.source === 'draft' ? '작성 중 청구 라인' : `최신 제출 본문${newest ? ` · 제출 차수 #${newest.version}` : ''}`}</h3></div>
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
    <div className={`panel-status ${detail.current ? '' : 'warning'}`}>
      <strong>현재 자료와 일치 여부: {freshnessVerdict(detail)}</strong>
      {detail.current
        ? <p>서버가 현재 사건·증빙·대사·매핑·구매 스냅샷과 일치한다고 판정했습니다.</p>
        : <p>불일치 사유: {detail.reasons.map((reason) => presentFreshnessReason(reason)).join(' · ')}</p>}
    </div>
  );
}

export function DecisionsPanel({ data }: { data: CaseDetailData }) {
  if (!data.canReadReview) {
    return <SectionMessage tone="forbidden">검토 결정과 검토 대상은 승인자·운영자만 조회할 수 있습니다.</SectionMessage>;
  }
  const { snapshot, decisions } = data;
  return (
    <div className="history-content">
      <div className="section-heading"><h2>검토 대상</h2></div>
      {snapshot.status === 'empty'
        ? <SectionMessage tone="notice">아직 동결된 검토 대상(스냅샷)이 없습니다.</SectionMessage>
        : snapshot.status === 'forbidden'
          ? <SectionMessage tone="forbidden">검토 대상 조회가 허용되지 않았습니다.</SectionMessage>
          : snapshot.status === 'error'
            ? <SectionMessage tone="error">검토 대상을 불러오지 못했습니다. {snapshot.message}</SectionMessage>
            : <>
                <dl className="receipt-data">
                  <div><dt>검토 대상 번호</dt><dd>#{snapshot.data.snapshotNumber}</dd></div>
                  <div><dt>대상 청구서 변경 버전</dt><dd>v{snapshot.data.targetCaseVersion}</dd></div>
                  <div><dt>증빙 버전</dt><dd>v{snapshot.data.evidenceBundleVersion}</dd></div>
                  <div><dt>비교 결과 번호</dt><dd>{snapshot.data.matchResultNumber === null ? '—' : `#${snapshot.data.matchResultNumber}`}</dd></div>
                  <div><dt>생성 시각 (KST)</dt><dd>{formatInstant(snapshot.data.createdAt)}</dd></div>
                </dl>
                <FreshnessBlock freshness={data.freshness} />
              </>}
      <div className="section-heading"><h3>결정 이력</h3></div>
      {decisions.status === 'empty'
        ? <SectionMessage tone="notice">아직 기록된 결정이 없습니다.</SectionMessage>
        : decisions.status === 'forbidden'
          ? <SectionMessage tone="forbidden">결정 이력 조회가 허용되지 않았습니다.</SectionMessage>
          : decisions.status === 'error'
            ? <SectionMessage tone="error">결정 이력을 불러오지 못했습니다. {decisions.message}</SectionMessage>
            : <table className="history-table">
                <caption className="sr-only">검토 결정 이력</caption>
                <thead><tr><th>결정</th><th>작업자</th><th>결정 번호</th><th>내용 / 사유</th><th>시각 (KST)</th></tr></thead>
                <tbody>
                  {decisions.data.map((decision) => (
                    <tr key={decision.id}>
                      <td>{presentDecision(decision.decision)}</td>
                      <td>{decision.decidedBy}</td>
                      <td>#{decision.decisionNumber}</td>
                      <td>{decisionDetail(decision)}</td>
                      <td className="muted-text">{formatInstant(decision.decidedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>}
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
    <div className="history-content">
      <div className="section-heading"><h2>감사 이력</h2><span>서버 최신순 페이지</span></div>
      {error && (
        <SectionMessage tone={error.kind === 'forbidden' ? 'forbidden' : 'error'}>
          <strong>{error.kind === 'forbidden' ? '감사 기록을 더 불러올 수 없습니다' : '감사 기록을 더 불러오지 못했습니다'}</strong>
          <p>{error.message}</p>
          <p className="muted-text">지금 화면의 감사 기록과 다음 위치는 그대로 유지됩니다.</p>
        </SectionMessage>
      )}
      <table className="history-table audit-table">
        <caption className="sr-only">사건 감사 이력</caption>
        <thead><tr><th>시간 (KST)</th><th>작업자</th><th>작업</th><th>변경 내용</th></tr></thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.id}>
              <td>{formatInstant(entry.occurredAt)}</td>
              <td>{entry.actor}</td>
              <td>{presentAuditAction(entry.action)}</td>
              <td>
                <details>
                  <summary>{entry.targetType} · 청구서 변경 버전 {entry.businessVersion}</summary>
                  <p>{boundedJson(entry.after)}</p>
                  <p className="muted-text">요청 {shortId(entry.requestId)} · 추적 {shortId(entry.traceId)}</p>
                </details>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="audit-pagination">
        {nextCursor
          ? <button className="button" onClick={onMore} disabled={loadingMore}>{loadingMore ? '불러오는 중…' : error ? '이전 기록 다시 시도' : '이전 기록 더 보기'}</button>
          : <p role="status">마지막 기록입니다.</p>}
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
      <div className="review-note">
        <span className="status-dot" />
        <span>아직 승인·인계 전입니다. 지급요청과 금액은 승인 시 서버가 생성합니다.</span>
      </div>
    );
  }
  return (
    <div className="review-note">
      <span className="status-dot" />
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
    <div className="action-summary">
      <strong>{amount ?? (handoff.status === 'error' || handoff.status === 'forbidden' ? '인계 상태 확인 필요' : '금액 미확정')}</strong>
      <span>{caption}</span>
    </div>
  );
}
