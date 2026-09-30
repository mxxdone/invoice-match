'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useCallback, useEffect, useState } from 'react';
import { Icon, PageHeader, Shell } from '../ui';
import { useAuth } from '../auth';
import { fetchCaseHandoff, fetchInvoiceCase } from '../api/client';
import { ApiRequestError } from '../api/transport';
import { formatInstant, presentStatus, type CaseHandoffStatus, type InvoiceCaseDetail } from '../api/contract';
import { EXACT_RANGE_MESSAGE, formatExactInteger, isExactInteger, presentOutboxStatus, presentPaymentStatus } from '../cases/[id]/detail-model';

type Load =
  | { status: 'loading' }
  | { status: 'ready'; detail: InvoiceCaseDetail; handoff: CaseHandoffStatus }
  | { status: 'notFound' }
  | { status: 'forbidden' }
  | { status: 'error'; message: string };

function Handoff() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const caseId = searchParams.get('case') ?? '';
  const { credentials, isAuthenticated, sessionId, logout } = useAuth();
  // The load is keyed to the case/session. A changed key resets to loading during
  // render, so the effect only writes state from its async callbacks.
  const loadKey = `${sessionId}#${caseId}`;
  const [state, setState] = useState<{ key: string; load: Load }>(() => ({ key: loadKey, load: { status: 'loading' } }));
  if (state.key !== loadKey) {
    setState({ key: loadKey, load: { status: 'loading' } });
  }
  const load = state.key === loadKey ? state.load : { status: 'loading' as const };

  const onUnauthorized = useCallback(() => {
    logout();
    router.replace('/login');
  }, [logout, router]);

  useEffect(() => {
    if (!isAuthenticated) router.replace('/login');
  }, [isAuthenticated, router]);

  useEffect(() => {
    if (!credentials || !caseId) return;
    let cancelled = false;
    const key = loadKey;
    (async () => {
      try {
        const [detail, handoff] = await Promise.all([
          fetchInvoiceCase(credentials, caseId),
          fetchCaseHandoff(credentials, caseId),
        ]);
        if (!cancelled) setState({ key, load: { status: 'ready', detail, handoff } });
      } catch (caught) {
        if (cancelled) return;
        if (caught instanceof ApiRequestError && caught.status === 401) {
          onUnauthorized();
          return;
        }
        if (caught instanceof ApiRequestError && caught.status === 404) {
          setState({ key, load: { status: 'notFound' } });
          return;
        }
        if (caught instanceof ApiRequestError && caught.status === 403) {
          setState({ key, load: { status: 'forbidden' } });
          return;
        }
        setState({ key, load: { status: 'error', message: caught instanceof Error ? caught.message : '인계 상태를 불러오지 못했습니다.' } });
      }
    })();
    return () => { cancelled = true; };
    // loadKey is derived from sessionId/caseId which are already dependencies.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [credentials, caseId, sessionId, onUnauthorized]);

  if (!isAuthenticated) {
    return <Shell active="handoff" preview={false}><section className="empty-state" role="status"><Icon name="clock" size={25} /><h1>로그인이 필요합니다</h1><p>로그인 화면으로 이동합니다.</p></section></Shell>;
  }
  if (!caseId) {
    return <Shell active="handoff" preview={false}><section className="empty-state" role="status"><Icon name="send" size={25} /><h1>사건을 먼저 선택하세요</h1><p>ERP 인계 상세는 한 사건의 실제 지급요청과 전송 상태를 조회합니다.</p><div className="dialog-actions"><Link className="button primary" href="/operations">운영 작업에서 선택</Link><Link className="button" href="/cases">청구서 목록</Link></div></section></Shell>;
  }
  if (load.status === 'loading') {
    return <Shell active="handoff" preview={false}><section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>실제 인계 상태를 서버에서 확인하고 있습니다.</p></section></Shell>;
  }
  if (load.status === 'notFound' || load.status === 'forbidden' || load.status === 'error') {
    const message = load.status === 'notFound' ? '사건이 없거나 삭제되었습니다.' : load.status === 'forbidden' ? '서버가 이 사건의 인계 조회를 허용하지 않았습니다.' : load.message;
    return <Shell active="handoff" preview={false}><section className="empty-state" role={load.status === 'error' ? 'alert' : 'status'}><Icon name="document" size={25} /><h1>인계 상태를 표시할 수 없습니다</h1><p>{message}</p><Link className="button" href="/operations">운영 작업으로</Link></section></Shell>;
  }

  const { detail, handoff } = load;
  const status = presentStatus(detail.status);
  const payment = handoff.payment;

  return (
    <Shell active="handoff" preview={false}>
      <PageHeader eyebrow="ERP 인계 / 지급요청" title="ERP 인계 상세" subtitle={`${detail.invoiceNumber} · ${detail.supplierId}`} action={<Link className="button" href={`/cases/${detail.id}`}><Icon name="arrow" size={14} />청구서 상세</Link>}>
        <dl className="case-meta">
          <div><dt>사건 상태</dt><dd>{status.label}</dd></div>
          <div><dt>사건 버전</dt><dd>v{handoff.caseVersion}</dd></div>
          <div><dt>발주번호</dt><dd>{detail.purchaseOrderId}</dd></div>
          <div><dt>제출자</dt><dd>{detail.submittedBy}</dd></div>
        </dl>
      </PageHeader>

      {!payment ? (
        <div className="inline-notice" role="status"><strong>아직 승인·인계 전입니다</strong><span>승인 시 서버가 지급요청과 Outbox를 생성합니다. 이 사건에는 아직 지급요청이 없습니다.</span></div>
      ) : (
        <>
          <div className={`inline-notice ${payment.paymentStatus === 'ACKNOWLEDGED' ? 'success' : 'warning'}`}>
            <strong>{presentPaymentStatus(payment.paymentStatus)}</strong>
            <span>
              {payment.paymentStatus === 'ACKNOWLEDGED'
                ? 'ERP 인계(ACK)가 확인되었습니다. 이는 송금 완료가 아니라 ERP가 지급요청을 접수·처리했다는 뜻입니다.'
                : payment.paymentStatus === 'RESULT_UNKNOWN'
                  ? '전송 응답이 유실되어 ERP 처리 여부를 확인할 수 없습니다. 중복 지급을 막기 위해 자동 재전송하지 않습니다.'
                  : '서버가 기록한 지급요청·Outbox 상태입니다. 화면은 상태를 재판정하지 않습니다.'}
            </span>
          </div>
          <section className="form-section">
            <div className="section-heading"><h2>지급요청과 전송 상태</h2><span>서버 값 · 읽기 전용</span></div>
            <div className="handoff-grid">
              <dl className="definition-list">
                <div><dt>지급요청 상태</dt><dd><span className={`line-badge ${payment.paymentStatus === 'ACKNOWLEDGED' ? '' : 'exception'}`}>{presentPaymentStatus(payment.paymentStatus)}</span></dd></div>
                <div><dt>Outbox 상태</dt><dd>{payment.outboxStatus ? presentOutboxStatus(payment.outboxStatus) : '—'}</dd></div>
                <div><dt>전송 시도</dt><dd>{payment.attemptCount}회</dd></div>
                <div><dt>최종 오류 코드</dt><dd>{payment.lastErrorCode ?? '—'}</dd></div>
                <div><dt>다음 시도</dt><dd>{formatInstant(payment.nextAttemptAt)}</dd></div>
                <div><dt>인계 완료 시각 (KST)</dt><dd>{formatInstant(payment.deliveredAt)}</dd></div>
              </dl>
              <div className="payment-amount">
                <small>승인된 지급요청 금액 (서버 값)</small>
                <strong>{isExactInteger(payment.amount) ? `₩ ${formatExactInteger(payment.amount)}` : EXACT_RANGE_MESSAGE}</strong>
                <span>{payment.currency} · 승인 대상에 고정된 금액</span>
                <p>실제 지급·송금은 외부 ERP의 업무 범위입니다.<br />이 서비스는 지급요청을 안전하게 인계합니다.</p>
              </div>
            </div>
          </section>
          <section className="form-section">
            <div className="section-heading"><h2>고정된 요청 식별정보</h2><span>서버 값</span></div>
            <dl className="definition-list wide-definition">
              <div><dt>지급요청 ID</dt><dd className="code-text">{payment.paymentRequestId}</dd></div>
              <div><dt>외부 요청 키</dt><dd className="code-text">{payment.externalRequestKey}</dd></div>
              <div><dt>내보내기 버전</dt><dd>{payment.exportVersion}</dd></div>
              <div><dt>생성 시각 (KST)</dt><dd>{formatInstant(payment.createdAt)}</dd></div>
            </dl>
            <p className="panel-footnote">자동 재전송·재조정·queue/DLQ 지표·재처리 버튼은 제공하지 않습니다. 결과불명은 수동 확인 대상입니다.</p>
          </section>
        </>
      )}
      <div className="dialog-actions"><Link className="button" href="/operations">운영 작업으로 돌아가기</Link></div>
    </Shell>
  );
}

export default function HandoffPage() {
  return (
    <Suspense fallback={<Shell active="handoff" preview={false}><section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>인계 상태를 준비하고 있습니다.</p></section></Shell>}>
      <Handoff />
    </Suspense>
  );
}
