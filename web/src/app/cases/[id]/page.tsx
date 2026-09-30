'use client';

import Link from 'next/link';
import { useParams, useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useCallback, useEffect, useState } from 'react';
import { Icon, Shell } from '../../ui';
import { useAuth } from '../../auth';
import { formatInstant, presentStatus } from '../../api/contract';
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
  formatNumber,
  freshnessVerdict,
  latestBundle,
  presentAuditAction,
  presentDecision,
  presentFreshnessReason,
  presentLineStatus,
  presentOutboxStatus,
  presentPaymentStatus,
} from './detail-model';
import { useCaseDetail, type CaseDetailData, type SectionState } from './use-case-detail';

const REVIEW_ROLES = ['APPROVER', 'OPERATOR'];
const WRITE_DISABLED = '이번 실행 단위에는 쓰기 연결이 포함되지 않았습니다.';

function canReadReview(roles: string[]): boolean {
  return roles.some((role) => REVIEW_ROLES.includes(role));
}

function shortId(value: string | null | undefined): string {
  if (!value) return '—';
  return value.length > 8 ? value.slice(0, 8) : value;
}

function boundedJson(value: unknown): string {
  if (value === null || value === undefined) return '내용 없음';
  try {
    const text = JSON.stringify(value);
    if (!text) return '내용 없음';
    return text.length > 1200 ? `${text.slice(0, 1200)}…` : text;
  } catch {
    return '변경 내용을 표시할 수 없습니다.';
  }
}

function SectionMessage({ tone, children }: { tone: 'empty' | 'forbidden' | 'error'; children: React.ReactNode }) {
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
            <td className="numeric">{formatNumber(line.unitPrice)}</td>
          </tr>
        ))}
        {lines.length === 0 && <tr><td colSpan={5} className="empty-table">표시할 청구 라인이 없습니다.</td></tr>}
      </tbody>
    </table>
  );
}

function ComparePanel({ data }: { data: CaseDetailData }) {
  const { match } = data;
  if (!data.canReadReview) {
    return <SectionMessage tone="forbidden">비교 결과는 승인자·운영자만 조회할 수 있습니다. 제출한 청구 라인은 &lsquo;제출 근거&rsquo; 탭에서 확인할 수 있습니다.</SectionMessage>;
  }
  if (match.status === 'forbidden') {
    return <SectionMessage tone="forbidden">서버가 이 계정의 비교 결과 조회를 허용하지 않았습니다.</SectionMessage>;
  }
  if (match.status === 'empty') {
    return <SectionMessage tone="empty">아직 대사(비교) 결과가 없습니다. 운영자가 대사를 실행하면 최신 결과가 표시됩니다.</SectionMessage>;
  }
  if (match.status === 'error') {
    return <SectionMessage tone="error">비교 결과를 불러오지 못했습니다. {match.message}</SectionMessage>;
  }
  const rows = comparisonRows(match.data);
  const issues = rows.filter((row) => row.issues.length > 0 || row.status !== 'MATCHED').length;
  const caseExceptions = match.data.payload.exceptions;
  return (
    <>
      <div className="toolbar">
        <span className={`line-badge ${match.data.payload.normal ? '' : 'exception'}`}>
          {match.data.payload.normal ? <><Icon name="check" size={12} />서버 판정: 정상</> : <><span className="exception-dot" />서버 판정: 확인 필요</>}
        </span>
        <span className="demo-description">비교 결과 #{match.data.resultNumber} · 판정 {issues}개 라인 · 대사 해시 {shortId(match.data.resultHash)}</span>
      </div>
      {caseExceptions.length > 0 && (
        <div className="review-warning" role="status">
          <div>
            <strong>청구서 전체 확인 필요</strong>
            <p>{caseExceptions.map((exception) => presentLineStatus(exception.type)).join(' · ')}</p>
          </div>
        </div>
      )}
      <div className="table-area">
        <div className="table-scroll">
          <table className="comparison-table">
            <caption className="sr-only">청구·발주·검수 비교. 서버 대사 결과의 라인별 값을 그대로 표시합니다.</caption>
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
                  <td className="numeric">{formatNumber(row.invoiceUnitPrice)}</td>
                  <td className="numeric">{row.poUnitPrice === null ? '—' : formatNumber(row.poUnitPrice)}</td>
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

function EvidencePanel({ data }: { data: CaseDetailData }) {
  const bundles = data.bundles.status === 'ready' ? data.bundles.data : [];
  const sealedPayloadLines = data.sealed.status === 'ready' ? claimLines(data.detail, data.sealed.data) : claimLines(data.detail, null);
  const newest = latestBundle(bundles);
  return (
    <div className="history-content">
      <div className="section-heading">
        <h2>제출 근거</h2>
        <span>{bundles.length > 0 ? `증빙 ${bundles.length}개 버전` : '제출된 증빙 없음'}</span>
      </div>
      {data.bundles.status === 'error'
        ? <SectionMessage tone="error">증빙 목록을 불러오지 못했습니다. {data.bundles.message}</SectionMessage>
        : bundles.length > 0
          ? <table className="history-table">
              <caption className="sr-only">동결된 증빙 버전 목록</caption>
              <thead><tr><th>버전</th><th>제출 시각 (KST)</th><th>지문(해시)</th></tr></thead>
              <tbody>
                {bundles.map((bundle) => (
                  <tr key={bundle.version}>
                    <td>v{bundle.version}{newest?.version === bundle.version ? ' · 최신' : ''}</td>
                    <td>{formatInstant(bundle.submittedAt)}</td>
                    <td className="muted-text">{shortId(bundle.payloadHash)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          : <SectionMessage tone="empty">{data.detail.status === 'DRAFT' ? '작성 중이라 아직 동결된 증빙이 없습니다.' : '동결된 증빙 버전이 없습니다.'}</SectionMessage>}
      <div className="section-heading"><h3>{sealedPayloadLines.source === 'draft' ? '작성 중 청구 라인' : '제출 시점 청구 라인'}</h3></div>
      {data.sealed.status === 'error'
        ? <SectionMessage tone="error">증빙 본문을 불러오지 못했습니다. {data.sealed.message}</SectionMessage>
        : sealedPayloadLines.lines.length > 0
          ? <EvidenceLines lines={sealedPayloadLines.lines} />
          : <SectionMessage tone="empty">표시할 청구 라인이 없습니다.</SectionMessage>}
      {sealedPayloadLines.source === 'evidence' && data.sealed.status === 'ready' && (
        <details className="snapshot-technical">
          <summary>기술 정보 보기</summary>
          <p>증빙 v{data.sealed.data.version} 지문(해시): {data.sealed.data.payloadHash}</p>
          <p>동결된 제출 본문의 전체 지문입니다. 사용자가 입력할 값이 아닙니다.</p>
        </details>
      )}
    </div>
  );
}

function FreshnessBlock({ freshness }: { freshness: SectionState<ReviewFreshness> }) {
  if (freshness.status === 'empty') {
    return <SectionMessage tone="empty">현재 자료 일치 여부를 확인할 수 있는 검토 대상이 없습니다.</SectionMessage>;
  }
  if (freshness.status === 'forbidden') {
    return <SectionMessage tone="forbidden">일치 여부는 승인자·운영자만 조회할 수 있습니다.</SectionMessage>;
  }
  if (freshness.status === 'error') {
    return <SectionMessage tone="error">일치 여부를 불러오지 못했습니다. {freshness.message}</SectionMessage>;
  }
  const detail = freshness.data;
  return (
    <div className={`panel-status ${detail.current ? '' : 'warning'}`}>
      <strong>{freshnessVerdict(detail)}</strong>
      {detail.current
        ? <p>서버가 현재 사건·증빙·대사·매핑·구매 스냅샷과 일치한다고 판정했습니다.</p>
        : <p>불일치 사유: {detail.reasons.map((reason) => presentFreshnessReason(reason)).join(' · ')}</p>}
      <details className="snapshot-technical">
        <summary>기술 정보 보기</summary>
        <p>검토 대상 snapshot #{detail.snapshotNumber} · 청구 버전 {detail.snapshotCaseVersion} → 현재 {detail.currentCaseVersion}</p>
        <p>증빙 {shortId(detail.snapshotEvidenceBundleId)} → 최신 {shortId(detail.latestEvidenceBundleId)}</p>
        <p>대사 {shortId(detail.snapshotMatchResultId)} → 최신 {shortId(detail.latestMatchResultId)}</p>
        <p>매핑 watermark {detail.snapshotMappingWatermark} → 현재 {detail.currentMappingWatermark}</p>
        <p>구매 스냅샷 v{detail.snapshotPurchasingSnapshotVersion} → 현재 {detail.currentPurchasingSnapshotVersion ?? '—'}</p>
      </details>
    </div>
  );
}

function DecisionsPanel({ data }: { data: CaseDetailData }) {
  if (!data.canReadReview) {
    return <SectionMessage tone="forbidden">검토 결정과 검토 대상은 승인자·운영자만 조회할 수 있습니다.</SectionMessage>;
  }
  const { snapshot, decisions } = data;
  return (
    <div className="history-content">
      <div className="section-heading"><h2>검토 대상</h2></div>
      {snapshot.status === 'empty'
        ? <SectionMessage tone="empty">아직 동결된 검토 대상(스냅샷)이 없습니다.</SectionMessage>
        : snapshot.status === 'forbidden'
          ? <SectionMessage tone="forbidden">검토 대상 조회가 허용되지 않았습니다.</SectionMessage>
          : snapshot.status === 'error'
            ? <SectionMessage tone="error">검토 대상을 불러오지 못했습니다. {snapshot.message}</SectionMessage>
            : <>
                <dl className="receipt-data">
                  <div><dt>검토 번호</dt><dd>#{snapshot.data.snapshotNumber}</dd></div>
                  <div><dt>대상 청구 버전</dt><dd>v{snapshot.data.targetCaseVersion}</dd></div>
                  <div><dt>증빙 버전</dt><dd>v{snapshot.data.evidenceBundleVersion}</dd></div>
                  <div><dt>대사 결과 번호</dt><dd>{snapshot.data.matchResultNumber === null ? '—' : `#${snapshot.data.matchResultNumber}`}</dd></div>
                  <div><dt>검토 지문(해시)</dt><dd>{shortId(snapshot.data.payloadHash)}</dd></div>
                  <div><dt>생성 시각 (KST)</dt><dd>{formatInstant(snapshot.data.createdAt)}</dd></div>
                </dl>
                <FreshnessBlock freshness={data.freshness} />
              </>}
      <div className="section-heading"><h3>결정 이력</h3></div>
      {decisions.status === 'empty'
        ? <SectionMessage tone="empty">아직 기록된 결정이 없습니다.</SectionMessage>
        : decisions.status === 'forbidden'
          ? <SectionMessage tone="forbidden">결정 이력 조회가 허용되지 않았습니다.</SectionMessage>
          : decisions.status === 'error'
            ? <SectionMessage tone="error">결정 이력을 불러오지 못했습니다. {decisions.message}</SectionMessage>
            : <table className="history-table">
                <caption className="sr-only">검토 결정 이력</caption>
                <thead><tr><th>결정</th><th>작업자</th><th>검토 번호</th><th>내용 / 사유</th><th>시각 (KST)</th></tr></thead>
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

function AuditPanel({ data, entries, nextCursor, loadingMore, onMore }: {
  data: CaseDetailData;
  entries: AuditEntryView[];
  nextCursor: string | null;
  loadingMore: boolean;
  onMore: () => void;
}) {
  if (!data.canReadReview) {
    return <SectionMessage tone="forbidden">감사 이력은 승인자·운영자만 조회할 수 있습니다.</SectionMessage>;
  }
  if (data.audit.status === 'error') {
    return <SectionMessage tone="error">감사 이력을 불러오지 못했습니다. {data.audit.message}</SectionMessage>;
  }
  if (data.audit.status === 'empty') {
    return <SectionMessage tone="empty">아직 감사 기록이 없습니다.</SectionMessage>;
  }
  if (data.audit.status === 'forbidden') {
    return <SectionMessage tone="forbidden">감사 이력 조회가 허용되지 않았습니다.</SectionMessage>;
  }
  return (
    <div className="history-content">
      <div className="section-heading"><h2>감사 이력</h2><span>서버 최신순 페이지</span></div>
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
                  <summary>{entry.targetType} · 사건 버전 {entry.businessVersion}</summary>
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
          ? <button className="button" onClick={onMore} disabled={loadingMore}>{loadingMore ? '불러오는 중…' : '이전 기록 더 보기'}</button>
          : <p role="status">마지막 기록입니다.</p>}
      </div>
    </div>
  );
}

function HandoffStrip({ handoff }: { handoff: SectionState<CaseHandoffStatus> }) {
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

function Detail() {
  const params = useParams<{ id: string }>();
  const caseId = params?.id ?? '';
  const searchParams = useSearchParams();
  const router = useRouter();
  const { credentials, user, isAuthenticated, sessionId, logout } = useAuth();
  const reviewReader = canReadReview(user?.roles ?? []);
  const [tab, setTab] = useState('compare');
  const [reloadToken, setReloadToken] = useState(0);

  const from = searchParams.get('from');
  const listHref = from ? `/cases?${from}` : '/cases';

  const onUnauthorized = useCallback(() => {
    logout();
    router.replace('/login');
  }, [logout, router]);

  useEffect(() => {
    if (!isAuthenticated) router.replace('/login');
  }, [isAuthenticated, router]);

  const { load, isLoading, auditEntries, auditNextCursor, auditLoadingMore, loadMoreAudit } = useCaseDetail({
    credentials,
    sessionId,
    caseId,
    canReadReview: reviewReader,
    reloadToken,
    onUnauthorized,
  });

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
  const payment = data.handoff.status === 'ready' ? data.handoff.data.payment : null;

  const tabs: Array<[string, string]> = [
    ['compare', '발주·검수·청구 비교'],
    ['evidence', '제출 근거'],
    ['decisions', '검토 결정'],
    ['audit', '감사 이력'],
  ];

  return (
    <Shell active="cases" preview={false}>
      <header className="page-header">
        <nav className="breadcrumb" aria-label="현재 위치">
          <Link href={listHref}><Icon name="arrow" size={12} />목록</Link>
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
          <span className="demo-tag">실제 서버 조회 · 읽기 전용</span>
        </div>
        <dl className="case-meta">
          <div><dt>공급사 ID</dt><dd>{data.detail.supplierId}</dd></div>
          <div><dt>발주번호</dt><dd>{data.detail.purchaseOrderId}</dd></div>
          <div><dt>제출자</dt><dd>{data.detail.submittedBy}</dd></div>
          <div><dt>청구서 버전</dt><dd>v{data.detail.version}</dd></div>
          <div><dt>현재 revision</dt><dd>{data.detail.currentRevision ? `${data.detail.currentRevision.revisionNumber} · ${data.detail.currentRevision.status}` : '—'}</dd></div>
          <div><dt>증빙 버전</dt><dd>{newest ? `v${newest.version}` : '—'}</dd></div>
          <div><dt>제출 시각 (KST)</dt><dd>{formatInstant(newest?.submittedAt ?? null)}</dd></div>
        </dl>
      </header>

      <div className="review-warning" role="status">
        <div>
          <strong>조회 전용 화면입니다</strong>
          <p>승인·매핑·보완 요청·거절·작성 쓰기는 이번 실행 단위에 연결하지 않았습니다. 아래 버튼은 비활성 상태이며 실제 요청을 보내지 않습니다.</p>
        </div>
      </div>
      <HandoffStrip handoff={data.handoff} />

      <div className="tabs" role="tablist" aria-label="청구서 상세">
        {tabs.map(([id, label]) => (
          <button key={id} role="tab" id={`tab-${id}`} aria-controls={`panel-${id}`} aria-selected={tab === id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>{label}</button>
        ))}
      </div>

      <section className="tab-content" role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === 'compare' ? <ComparePanel data={data} />
          : tab === 'evidence' ? <EvidencePanel data={data} />
            : tab === 'decisions' ? <DecisionsPanel data={data} />
              : <AuditPanel data={data} entries={auditEntries} nextCursor={auditNextCursor} loadingMore={auditLoadingMore} onMore={loadMoreAudit} />}
      </section>

      <footer className="action-bar">
        <div className="action-summary">
          <strong>{payment ? `₩ ${formatNumber(payment.amount)}` : '금액 미확정'}</strong>
          <span>{payment ? '승인된 지급 금액 · 서버 값' : '승인 전에는 서버가 지급 금액을 확정하지 않습니다.'}</span>
        </div>
        <div className="action-buttons">
          <span className="footer-context">검토 #{data.snapshot.status === 'ready' ? data.snapshot.data.snapshotNumber : '—'} · 증빙 {newest ? `v${newest.version}` : '—'}</span>
          <button className="button footer-secondary" disabled title={WRITE_DISABLED}>청구 거절</button>
          <button className="button footer-secondary" disabled title={WRITE_DISABLED}>보완 요청</button>
          <button className="button primary" disabled title={WRITE_DISABLED}>승인 검토</button>
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
