'use client';

import Link from 'next/link';
import { useParams, useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useCallback, useEffect, useState } from 'react';
import { Icon, Shell } from '../../ui';
import { useAuth } from '../../auth';
import { formatInstant, presentStatus } from '../../api/contract';
import type { EvidenceBundleSummary } from '../../api/contract';
import { latestBundle } from './detail-model';
import {
  AuditPanel,
  ComparePanel,
  DecisionsPanel,
  EvidencePanel,
  HandoffStrip,
  HandoffSummary,
} from './detail-sections';
import { useCaseDetail } from './use-case-detail';

const REVIEW_ROLES = ['APPROVER', 'OPERATOR'];
const WRITE_DISABLED = '이번 실행 단위에는 쓰기 연결이 포함되지 않았습니다.';

function canReadReview(roles: string[]): boolean {
  return roles.some((role) => REVIEW_ROLES.includes(role));
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

  const { load, isLoading, auditEntries, auditNextCursor, auditLoadingMore, auditError, loadMoreAudit } = useCaseDetail({
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
  const handoff = data.handoff;
  const payment = handoff.status === 'ready' ? handoff.data.payment : null;
  // The server is the authority for ownership; this only decides whether to
  // offer the authoring entry. A wrong guess is still rejected by the API.
  const ownerSubmitter = Boolean(user?.username) && user?.username === data.detail.submittedBy;

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
          <span className="demo-tag">실제 서버 조회 · 읽기 전용</span>
        </div>
        <dl className="case-meta">
          <div><dt>공급사 ID</dt><dd>{data.detail.supplierId}</dd></div>
          <div><dt>발주번호</dt><dd>{data.detail.purchaseOrderId}</dd></div>
          <div><dt>제출자</dt><dd>{data.detail.submittedBy}</dd></div>
          <div><dt>청구서 변경 버전</dt><dd>v{data.detail.version}</dd></div>
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
      <HandoffStrip handoff={handoff} />

      <div className="tabs" role="tablist" aria-label="청구서 상세">
        {tabs.map(([id, label]) => (
          <button key={id} role="tab" id={`tab-${id}`} aria-controls={`panel-${id}`} aria-selected={tab === id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>{label}</button>
        ))}
      </div>

      <section className="tab-content" role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === 'compare' ? <ComparePanel data={data} />
          : tab === 'evidence' ? <EvidencePanel data={data} />
            : tab === 'decisions' ? <DecisionsPanel data={data} />
              : <AuditPanel data={data} entries={auditEntries} nextCursor={auditNextCursor} loadingMore={auditLoadingMore} error={auditError} onMore={loadMoreAudit} />}
      </section>

      <footer className="action-bar">
        <HandoffSummary handoff={handoff} payment={payment} />
        <div className="action-buttons">
          <span className="footer-context">검토 #{data.snapshot.status === 'ready' ? data.snapshot.data.snapshotNumber : '—'} · 증빙 {newest ? `v${newest.version}` : '—'}</span>
          {ownerSubmitter && data.detail.status === 'DRAFT' && <Link className="button" href={`/cases/new?case=${data.detail.id}`}>초안 편집</Link>}
          {ownerSubmitter && data.detail.status === 'SUPPLEMENT_REQUIRED' && <Link className="button primary" href={`/cases/new?supplement=${data.detail.id}`}>보완 작성</Link>}
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
