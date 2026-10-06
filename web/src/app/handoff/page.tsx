'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useCallback, useEffect, useState } from 'react';
import { Icon, PageHeader, Shell } from '../ui';
import { Button } from '@/components/ui/button';
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
    return <Shell active="handoff" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">로그인이 필요합니다</h1><p className="text-label">로그인 화면으로 이동합니다.</p></section></Shell>;
  }
  if (!caseId) {
    return <Shell active="handoff" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status"><Icon name="send" size={25} /><h1 className="text-base font-normal text-[#6f7863]">사건을 먼저 선택하세요</h1><p className="text-label">ERP 인계 상세는 한 사건의 실제 지급요청과 전송 상태를 조회합니다.</p><div className="dialog-actions mt-[25px] flex justify-end gap-2"><Button asChild><Link href="/operations">운영 작업에서 선택</Link></Button><Button asChild variant="outline"><Link href="/cases">청구서 목록</Link></Button></div></section></Shell>;
  }
  if (load.status === 'loading') {
    return <Shell active="handoff" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">불러오는 중</h1><p className="text-label">실제 인계 상태를 서버에서 확인하고 있습니다.</p></section></Shell>;
  }
  if (load.status === 'notFound' || load.status === 'forbidden' || load.status === 'error') {
    const message = load.status === 'notFound' ? '사건이 없거나 삭제되었습니다.' : load.status === 'forbidden' ? '서버가 이 사건의 인계 조회를 허용하지 않았습니다.' : load.message;
    return <Shell active="handoff" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role={load.status === 'error' ? 'alert' : 'status'}><Icon name="document" size={25} /><h1 className="text-base font-normal text-[#6f7863]">인계 상태를 표시할 수 없습니다</h1><p className="text-label">{message}</p><Button asChild variant="outline"><Link href="/operations">운영 작업으로</Link></Button></section></Shell>;
  }

  const { detail, handoff } = load;
  const status = presentStatus(detail.status);
  const payment = handoff.payment;

  return (
    <Shell active="handoff" preview={false}>
      <PageHeader eyebrow="ERP 인계 / 지급요청" title="ERP 인계 상세" subtitle={`${detail.invoiceNumber} · ${detail.supplierId}`} action={<Button asChild variant="outline"><Link href={`/cases/${detail.id}`}><Icon name="arrow" size={14} />청구서 상세</Link></Button>}>
        <dl className="case-meta mt-[25px] flex flex-wrap gap-x-5 gap-y-3 min-[1200px]:flex-nowrap min-[1200px]:gap-[30px]">
          <div className="flex items-center gap-2"><dt className="text-label text-muted-foreground">사건 상태</dt><dd className="m-0 text-label">{status.label}</dd></div>
          <div className="flex items-center gap-2"><dt className="text-label text-muted-foreground">사건 버전</dt><dd className="m-0 text-label">v{handoff.caseVersion}</dd></div>
          <div className="flex items-center gap-2"><dt className="text-label text-muted-foreground">발주번호</dt><dd className="m-0 text-label">{detail.purchaseOrderId}</dd></div>
          <div className="flex items-center gap-2"><dt className="text-label text-muted-foreground">제출자</dt><dd className="m-0 text-label">{detail.submittedBy}</dd></div>
        </dl>
      </PageHeader>

      <div className="handoff-content px-5 py-[25px] min-[760px]:px-[30px] min-[760px]:py-[30px] min-[1200px]:px-10 min-[1600px]:px-14">
      {!payment ? (
        <div className="inline-notice mb-7 flex flex-col gap-2.5 border-l-2 border-[#ae9157] bg-[#faf8ef] px-5 py-[17px] text-label text-[#8d7542]" role="status"><strong className="text-label">아직 승인·인계 전입니다</strong><span className="text-label leading-[1.8]">승인 시 서버가 지급요청과 Outbox를 생성합니다. 이 사건에는 아직 지급요청이 없습니다.</span></div>
      ) : (
        <>
          <div className={`inline-notice mb-7 flex flex-col gap-2.5 border-l-2 px-5 py-[17px] text-label ${payment.paymentStatus === 'ACKNOWLEDGED' ? 'success border-[#859c70] bg-[#f3f8ef] text-[#6a8559]' : 'warning border-[#ae9157] bg-[#faf8ef] text-[#8d7542]'}`}>
            <strong className="text-label">{presentPaymentStatus(payment.paymentStatus)}</strong>
            <span className="text-label leading-[1.8]">
              {payment.paymentStatus === 'ACKNOWLEDGED'
                ? 'ERP 인계(ACK)가 확인되었습니다. 이는 송금 완료가 아니라 ERP가 지급요청을 접수·처리했다는 뜻입니다.'
                : payment.paymentStatus === 'RESULT_UNKNOWN'
                  ? '전송 응답이 유실되어 ERP 처리 여부를 확인할 수 없습니다. 중복 지급을 막기 위해 자동 재전송하지 않습니다.'
                  : '서버가 기록한 지급요청·Outbox 상태입니다. 화면은 상태를 재판정하지 않습니다.'}
            </span>
          </div>
          <section className="form-section mb-8">
            <div className="section-heading mb-[25px] flex items-center justify-between"><h2 className="text-[17px] font-medium">지급요청과 전송 상태</h2><span className="text-label text-muted-foreground">서버 값 · 읽기 전용</span></div>
            <div className="handoff-grid grid grid-cols-1 gap-[25px] min-[760px]:grid-cols-[1.2fr_1fr] min-[760px]:gap-[35px] min-[1200px]:gap-[70px]">
              <dl className="definition-list m-0 text-sm">
                <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">지급요청 상태</dt><dd className="m-0 min-w-0 text-left [overflow-wrap:anywhere]"><span className={`line-badge inline-flex items-center gap-[5px] rounded-[14px] px-2 py-[3px] text-label ${payment.paymentStatus === 'ACKNOWLEDGED' ? 'bg-[#f2f3ee] text-muted-foreground' : 'exception bg-[#f2edde] text-[#886c37]'}`}>{presentPaymentStatus(payment.paymentStatus)}</span></dd></div>
                <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">Outbox 상태</dt><dd className="m-0 min-w-0 text-left [overflow-wrap:anywhere]">{payment.outboxStatus ? presentOutboxStatus(payment.outboxStatus) : '—'}</dd></div>
                <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">전송 시도</dt><dd className="m-0 min-w-0 text-left [overflow-wrap:anywhere]">{payment.attemptCount}회</dd></div>
                <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">최종 오류 코드</dt><dd className="m-0 min-w-0 text-left [overflow-wrap:anywhere]">{payment.lastErrorCode ?? '—'}</dd></div>
                <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">다음 시도</dt><dd className="m-0 min-w-0 text-left [overflow-wrap:anywhere]">{formatInstant(payment.nextAttemptAt)}</dd></div>
                <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">인계 완료 시각 (KST)</dt><dd className="m-0 min-w-0 text-left [overflow-wrap:anywhere]">{formatInstant(payment.deliveredAt)}</dd></div>
              </dl>
              <div className="payment-amount flex flex-col border-t border-border pt-[25px] min-[760px]:border-l min-[760px]:border-t-0 min-[760px]:pb-5 min-[760px]:pl-[30px] min-[760px]:pt-[15px] min-[1200px]:pl-10">
                <small className="text-label text-muted-foreground">승인된 지급요청 금액 (서버 값)</small>
                <strong className="my-4 mb-[13px] text-[30px] font-normal tracking-[-.6px] tabular-nums">{isExactInteger(payment.amount) ? `₩ ${formatExactInteger(payment.amount)}` : EXACT_RANGE_MESSAGE}</strong>
                <span className="text-label text-muted-foreground">{payment.currency} · 승인 대상에 고정된 금액</span>
                <p className="mt-[27px] text-label leading-[1.9] text-[#959e83]">실제 지급·송금은 외부 ERP의 업무 범위입니다.<br />이 서비스는 지급요청을 안전하게 인계합니다.</p>
              </div>
            </div>
          </section>
          <section className="form-section mb-8 border-t border-border pt-[26px]">
            <div className="section-heading mb-[25px] flex items-center justify-between"><h2 className="text-[17px] font-medium">고정된 요청 식별정보</h2><span className="text-label text-muted-foreground">서버 값</span></div>
            <dl className="definition-list wide-definition m-0 mb-[22px] max-w-[780px] text-sm">
              <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">지급요청 ID</dt><dd className="code-text m-0 min-w-0 text-left font-mono text-label [overflow-wrap:anywhere]">{payment.paymentRequestId}</dd></div>
              <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">외부 요청 키</dt><dd className="code-text m-0 min-w-0 text-left font-mono text-label [overflow-wrap:anywhere]">{payment.externalRequestKey}</dd></div>
              <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">내보내기 버전</dt><dd className="m-0 min-w-0 text-left [overflow-wrap:anywhere]">{payment.exportVersion}</dd></div>
              <div className="grid min-h-[48px] grid-cols-[160px_minmax(0,1fr)] items-start gap-x-6 border-b border-border py-3 max-[760px]:grid-cols-[120px_minmax(0,1fr)] max-[760px]:gap-x-4"><dt className="text-label text-muted-foreground">생성 시각 (KST)</dt><dd className="m-0 min-w-0 text-left [overflow-wrap:anywhere]">{formatInstant(payment.createdAt)}</dd></div>
            </dl>
            <p className="panel-footnote text-label leading-[1.9] text-muted-foreground">자동 재전송·재조정·queue/DLQ 지표·재처리 버튼은 제공하지 않습니다. 결과불명은 수동 확인 대상입니다.</p>
          </section>
        </>
      )}
      <div className="dialog-actions mt-[25px] flex justify-end gap-2"><Button asChild variant="outline"><Link href="/operations">운영 작업으로 돌아가기</Link></Button></div>
      </div>
    </Shell>
  );
}

export default function HandoffPage() {
  return (
    <Suspense fallback={<Shell active="handoff" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">불러오는 중</h1><p className="text-label">인계 상태를 준비하고 있습니다.</p></section></Shell>}>
      <Handoff />
    </Suspense>
  );
}
