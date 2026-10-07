'use client';

import { Notice } from '@/components/ui/notice';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { Icon, PageHeader, Shell, Toast } from '../../ui';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { num } from '../../preview-data';
import { useAuth } from '../../auth';
import { fetchEvidenceBundle, fetchEvidenceBundles, fetchInvoiceCase } from '../../api/client';
import { ApiRequestError } from '../../api/transport';
import { claimLines, latestBundle } from '../[id]/detail-model';
import { useCaseComposer } from '../use-case-composer';
import { MutationFailureNotice } from '../mutation-failure-notice';
import { draftLinePayload, enteredDraftLines, isUnusedDraftLine, isValidDraftLine, type DraftLine } from '../draft-line-model';

const LINE_LIMIT = 100;

function toRows(lines: { rawItemName: string; quantity: number; unitPrice: number; confirmedItemId: string | null }[]): DraftLine[] {
  return lines.map((line, index) => ({
    id: index + 1,
    name: line.rawItemName,
    quantity: line.quantity,
    price: line.unitPrice,
    item: line.confirmedItemId ?? '',
    edited: true,
  }));
}

function statusText(status: string): string {
  switch (status) {
    case 'creating': return '청구서를 생성하는 중…';
    case 'created': return '청구서가 생성되었습니다. 초안을 저장하세요.';
    case 'saving': return '초안을 저장하는 중…';
    case 'draft-saved': return '초안이 저장되었습니다.';
    case 'submitting': return '제출하는 중…';
    case 'submitted': return '제출이 완료되어 증빙이 동결되었습니다.';
    case 'opening': return '보완 작성을 시작하는 중…';
    case 'failed': return '요청을 완료하지 못했습니다.';
    default: return '입력 후 저장하거나 제출하세요.';
  }
}

function NewCaseForm({ draftId, supplementId }: { draftId: string | null; supplementId: string | null }) {
  const router = useRouter();
  const existingId = draftId ?? supplementId;
  const { credentials, user, isAuthenticated, sessionId, logout } = useAuth();
  const isSubmitter = (user?.roles ?? []).includes('SUBMITTER');
  const sessionRef = useRef(sessionId);
  const alive = useRef(true);
  useEffect(() => { sessionRef.current = sessionId; }, [sessionId]);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const composer = useCaseComposer({
    credentials,
    sessionId,
    onUnauthorized: useCallback(() => {
      logout();
      router.replace('/login');
    }, [logout, router]),
  });

  const [supplier, setSupplier] = useState('');
  const [po, setPo] = useState('');
  const [invoice, setInvoice] = useState('');
  const [rows, setRows] = useState<DraftLine[]>([{ id: 1, name: '', quantity: 1, price: 0, item: '' }]);
  const [nextId, setNextId] = useState(2);
  const [toast, setToast] = useState('');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loadingExisting, setLoadingExisting] = useState(Boolean(existingId));
  const [refreshing, setRefreshing] = useState(false);
  const adopted = useRef(false);

  useEffect(() => {
    if (!isAuthenticated) router.replace('/login');
  }, [isAuthenticated, router]);

  // Editing an existing case starts from the server: the header is fixed (the
  // API replaces only draft lines) and the claim lines are read from the current
  // draft or, for a supplement, the latest sealed evidence.
  useEffect(() => {
    if (!existingId || !credentials || adopted.current) return;
    let cancelled = false;
    setLoadingExisting(true);
    setLoadError(null);
    (async () => {
      try {
        const detail = await fetchInvoiceCase(credentials, existingId);
        if (cancelled) return;
        const expectedStatus = supplementId ? 'SUPPLEMENT_REQUIRED' : 'DRAFT';
        if (detail.status !== expectedStatus) {
          setLoadError(supplementId
            ? '보완 대기 상태의 청구서가 아니어서 보완 작성 화면을 열 수 없습니다.'
            : '작성 중(DRAFT) 상태의 청구서가 아니어서 초안 편집 화면을 열 수 없습니다.');
          return;
        }
        let lines = detail.lines;
        if (lines.length === 0) {
          const bundles = await fetchEvidenceBundles(credentials, existingId);
          const newest = latestBundle(bundles);
          if (newest) {
            const sealed = await fetchEvidenceBundle(credentials, existingId, newest.version);
            lines = claimLines(detail, sealed).lines;
          }
        }
        if (cancelled) return;
        setSupplier(detail.supplierId);
        setPo(detail.purchaseOrderId);
        setInvoice(detail.invoiceNumber);
        const prefilled = toRows(lines);
        setRows(prefilled.length > 0 ? prefilled : [{ id: 1, name: '', quantity: 1, price: 0, item: '' }]);
        setNextId(prefilled.length + 1);
        adopted.current = true;
        composer.adoptLatest(detail.id, detail.version, supplementId
          ? '보완 대상 청구서를 서버에서 불러왔습니다. 보완 작성 시작 후 내용을 저장·제출하세요.'
          : '작성 중 청구서를 서버에서 불러왔습니다. 내용을 수정하고 저장하거나 제출하세요.');
      } catch (caught) {
        if (cancelled) return;
        if (caught instanceof ApiRequestError && caught.status === 401) {
          logout();
          router.replace('/login');
          return;
        }
        setLoadError(caught instanceof Error ? caught.message : '청구서를 불러오지 못했습니다.');
      } finally {
        if (!cancelled) setLoadingExisting(false);
      }
    })();
    return () => { cancelled = true; };
    // composer is intentionally excluded: adoptLatest is stable and re-running on
    // its identity would refetch the same case.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [existingId, supplementId, credentials, logout, router]);

  const enteredRows = enteredDraftLines(rows);
  const valid = supplier.trim() && po.trim() && invoice.trim() && enteredRows.length > 0
    && enteredRows.every(isValidDraftLine);

  const dirty = rows.some(row => row.name.trim() || row.price > 0);
  const leavingRisk = composer.hasPending || composer.unresolved !== null || (dirty && composer.status !== 'submitted');

  useEffect(() => {
    if (!leavingRisk) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [leavingRisk]);

  // Navigate only after the composer has actually confirmed a submit; using the
  // hook's own state avoids a stale-render case id.
  useEffect(() => {
    if (composer.status === 'submitted' && composer.submittedBundleVersion !== null && composer.caseId) {
      // Land on the case detail with the submission history tab selected; the
      // detail defaults back to an allowed tab if the role cannot see it.
      router.push(`/cases/${composer.caseId}?tab=evidence`);
    }
  }, [composer.status, composer.submittedBundleVersion, composer.caseId, router]);

  const header = { supplierId: supplier, purchaseOrderId: po, invoiceNumber: invoice };
  const draftLines = () => draftLinePayload(rows);

  async function ensureRevision(): Promise<boolean> {
    // The composer owns whether a revision was actually opened (including via an
    // explicit retry), so the page does not track a parallel flag.
    if (!supplementId || composer.revisionOpened) return true;
    const version = composer.caseVersion;
    if (version === null) return false;
    return composer.openRevision(supplementId, version);
  }

  async function onSave() {
    if (!valid) return;
    if (composer.unresolved) return;
    if (!(await ensureRevision())) return;
    if (await composer.saveDraft(header, draftLines())) {
      setToast('초안을 서버에 저장했습니다.');
    }
  }

  async function onSubmit() {
    if (!valid) return;
    if (composer.unresolved) return;
    if (!(await ensureRevision())) return;
    if (!(await composer.saveDraft(header, draftLines()))) return;
    await composer.submit();
  }

  async function refreshLatest() {
    const id = composer.caseId;
    if (!id || !credentials) return;
    // Capture the view identity so a late response from a replaced
    // account/case/mount can never adopt, toast, error or sign out here.
    const session = sessionId;
    const stillCurrent = () => alive.current && sessionRef.current === session;
    const hadUnresolved = composer.unresolved !== null;
    setRefreshing(true);
    try {
      const detail = await fetchInvoiceCase(credentials, id);
      if (!stillCurrent()) return;
      // A server read cannot prove an unresolved write landed, so it only adds a
      // notice and leaves the frozen intent/version for an exact replay to clear.
      composer.adoptLatest(
        detail.id,
        detail.version,
        hadUnresolved ? '서버 상태를 다시 읽었습니다. 미확정 작업은 같은 요청을 다시 시도해야 해소됩니다.' : undefined,
      );
      if (!hadUnresolved) {
        setToast('서버의 최신 청구서 버전을 반영했습니다. 내용을 확인하고 다시 시도하세요.');
      }
    } catch (caught) {
      if (!stillCurrent()) return;
      if (caught instanceof ApiRequestError && caught.status === 401) {
        logout();
        router.replace('/login');
        return;
      }
      setLoadError(caught instanceof Error ? caught.message : '최신 청구서를 불러오지 못했습니다.');
    } finally {
      if (stillCurrent()) setRefreshing(false);
    }
  }

  function edit(id: number, key: keyof DraftLine, value: string | number) {
    setRows(rows.map(row => row.id === id ? { ...row, [key]: value, edited: true } : row));
  }

  if (!isAuthenticated) {
    return <Shell active="new" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-3.75 px-5 py-10 text-center text-muted-foreground" role="status"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-muted-foreground">로그인이 필요합니다</h1><p className="text-label">로그인 화면으로 이동합니다.</p></section></Shell>;
  }

  if (loadingExisting) {
    return <Shell active="new" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-3.75 px-5 py-10 text-center text-muted-foreground" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-muted-foreground">불러오는 중</h1><p className="text-label">편집할 청구서를 서버에서 확인하고 있습니다.</p></section></Shell>;
  }

  const blocked = composer.status === 'creating' || composer.status === 'saving' || composer.status === 'submitting' || composer.status === 'opening';
  const locked = blocked || composer.unresolved !== null;

  return (
    <Shell active="new" preview={false}>
      <PageHeader
        eyebrow="청구 업무 / 수동 입력"
        title={supplementId ? '보완 청구 수정' : draftId ? '초안 편집' : '청구 작성'}
        subtitle={supplementId
          ? '서버의 최신 버전으로 보완 작성을 시작하고 내용을 저장한 뒤 다시 제출합니다.'
          : draftId
            ? '작성 중 청구서를 서버에서 불러와 내용을 수정하고 저장하거나 제출합니다.'
            : '청구 정보를 입력하고 품목별 수량과 단가를 확인합니다. 생성 → 초안 저장 → 제출은 서버의 별도 작업입니다.'}
        action={<Button asChild variant="outline"><Link href="/cases"><Icon name="arrow" size={14} />목록으로</Link></Button>}
      />

      {!isSubmitter && (
        <Notice className="review-warning mx-7.5 mb-3 flex items-center justify-between" role="status" tone="warning" density="compact"><div><strong>제출자 역할이 아닙니다</strong><p className="mt-1 text-warning">작성 저장·제출은 제출자만 가능합니다. 화면은 안내일 뿐이며 서버가 역할과 소유권을 판정합니다.</p></div></Notice>
      )}
      {loadError && <Notice className="review-warning mx-7.5 mb-3 flex items-center justify-between" role="alert" tone="warning" density="compact"><div><strong>진행할 수 없습니다</strong><p className="mt-1 text-warning">{loadError}</p></div></Notice>}
      {composer.unresolved && (
        <Notice className="review-warning mx-7.5 mb-3 flex items-center justify-between" role="alert" tone="warning" density="compact">
          <div>
            <strong>이전 요청의 결과가 확정되지 않았습니다</strong>
            <p className="mt-1 text-warning">서버 응답이 유실되었을 수 있어 요청이 반영되었는지 알 수 없습니다. 자동으로 다시 보내지 않습니다.</p>
            <p className="mt-1 text-label text-muted-foreground">미확정 작업이 있는 동안에는 내용을 수정하거나 새 요청을 보낼 수 없습니다. 같은 요청을 다시 시도하거나 최신 서버 상태를 조회해 먼저 해소하세요.</p>
          </div>
          <div className="dialog-actions mt-6.25 flex justify-end gap-2">
            <Button disabled={blocked} onClick={() => composer.retry()}>같은 요청 다시 시도</Button>
            {composer.caseId && <Button variant="outline" disabled={refreshing} onClick={refreshLatest}>최신 청구서 다시 불러오기</Button>}
          </div>
        </Notice>
      )}
      {composer.notice && <div className="review-note mx-5 my-5.75 flex items-center gap-2 text-label leading-body text-muted-foreground min-[760px]:mx-7.75 min-[760px]:my-6.5 min-[760px]:leading-normal" role="status"><span className="status-dot h-1.25 w-1.25 shrink-0 rounded-full bg-olive" /><span>{composer.notice}</span></div>}
      {composer.failure && (
        <MutationFailureNotice
          failure={composer.failure}
          onRefresh={composer.caseId ? refreshLatest : undefined}
          refreshing={refreshing}
        />
      )}

      <div className="form-content px-5 py-6.25 min-[760px]:px-7.5 min-[760px]:pt-7.5 min-[760px]:pb-11.25 min-[1200px]:px-10 min-[1600px]:px-14">
        {supplementId && <Notice className="inline-notice mb-7 flex flex-col" tone="warning" variant="strip" density="compact"><strong className="text-label">보완 작성</strong><span className="text-label leading-body">보완 대상 사건 헤더는 편집하지 않습니다. 저장·제출 시 서버가 새 revision을 엽니다.</span><small className="text-label text-muted-foreground">서버 상태: {composer.status === 'draft-saved' ? '보완 revision 열림' : '보완 대상 확인됨'}</small></Notice>}
        {draftId && <Notice className="inline-notice mb-7 flex flex-col" tone="warning" variant="strip" density="compact"><strong className="text-label">초안 편집</strong><span className="text-label leading-body">작성 중 청구서 헤더는 편집하지 않습니다. 저장하면 서버가 초안 라인을 교체합니다.</span><small className="text-label text-muted-foreground">서버 상태: v{composer.caseVersion ?? '—'}</small></Notice>}
        <section className="form-section mb-8">
          <div className="section-heading mb-6.25 flex items-center justify-between"><h2 className="text-section font-medium">청구 기본 정보</h2><span className="text-label text-muted-foreground">필수 입력 *</span></div>
          <div className="field-grid grid grid-cols-1 gap-4.5 min-[760px]:grid-cols-3 min-[760px]:gap-7">
            <label className="text-label text-muted-foreground">공급사 코드 *<Input maxLength={64} required value={supplier} onChange={event => setSupplier(event.target.value)} disabled={Boolean(existingId) || locked} className="mt-3 mb-2" /><small className="text-label text-muted-foreground">구매시스템에 등록된 공급사 코드를 입력하세요.</small></label>
            <label className="text-label text-muted-foreground">발주번호 *<Input maxLength={64} required value={po} onChange={event => setPo(event.target.value)} disabled={Boolean(existingId) || locked} className="mt-3 mb-2" /><small className="text-label text-muted-foreground">이 청구서에 해당하는 발주번호를 입력하세요.</small></label>
            <label className="text-label text-muted-foreground">공급사 청구번호 *<Input maxLength={100} required value={invoice} onChange={event => setInvoice(event.target.value)} disabled={Boolean(existingId) || locked} className="mt-3 mb-2" /><small className="text-label text-muted-foreground">공급사에서 받은 청구서에 기재된 번호를 입력하세요.</small></label>
          </div>
        </section>
        <section className="form-section mb-8 border-t border-border pt-6.5">
          <div className="section-heading mb-6.25 flex items-center justify-between"><h2 className="text-section font-medium">청구 품목</h2><span className="text-label text-muted-foreground">{enteredRows.length}개 품목 · 원화</span></div>
          <Table className="work-table edit-table w-full min-w-[790px] whitespace-nowrap text-sm">
            <TableHeader><TableRow className="border-0"><TableHead className="px-2.5 py-3 text-label font-normal text-muted-foreground">#</TableHead><TableHead className="w-[30%] px-2.5 py-3 text-label font-normal text-muted-foreground">청구 품목명 *</TableHead><TableHead className="w-[10%] px-2.5 py-3 text-label font-normal text-muted-foreground">수량 *</TableHead><TableHead className="w-[15%] px-2.5 py-3 text-label font-normal text-muted-foreground">단가 · 원 *</TableHead><TableHead className="w-[20%] px-2.5 py-3 text-label font-normal text-muted-foreground">확정 품목 ID</TableHead><TableHead className="numeric px-2.5 py-3 text-right text-label font-normal text-muted-foreground">금액 · 원</TableHead><TableHead className="px-2.5 py-3"><span className="sr-only">삭제</span></TableHead></TableRow></TableHeader>
            <TableBody>
              {rows.map((row, index) => (
                <TableRow key={row.id} className="border-0">
                  <TableCell className="h-15.75 border-b border-border py-3 pl-4 pr-2.5 text-muted-foreground">{String(index + 1).padStart(2, '0')}</TableCell>
                  <TableCell className="h-15.75 border-b border-border px-2.5 py-3"><Input aria-label={`${index + 1}번 품목명`} aria-invalid={!isUnusedDraftLine(row) && !row.name.trim() || undefined} maxLength={500} value={row.name} disabled={locked} onChange={event => edit(row.id, 'name', event.target.value)}  density="compact" />{!isUnusedDraftLine(row) && !isValidDraftLine(row) && <small className="text-label text-muted-foreground" role="status">품목명, 수량(양의 정수), 단가(0 이상의 정수)를 확인하세요.</small>}</TableCell>
                  <TableCell className="h-15.75 border-b border-border px-2.5 py-3"><Input aria-label={`${index + 1}번 수량`} type="number" min={1} step={1} value={row.quantity} disabled={locked} onChange={event => edit(row.id, 'quantity', Number(event.target.value))}  density="compact" alignment="right" /></TableCell>
                  <TableCell className="h-15.75 border-b border-border px-2.5 py-3"><Input aria-label={`${index + 1}번 단가`} type="number" min={0} step={1} value={row.price} disabled={locked} onChange={event => edit(row.id, 'price', Number(event.target.value))}  density="compact" alignment="right" /></TableCell>
                  <TableCell className="h-15.75 border-b border-border px-2.5 py-3"><Input aria-label={`${index + 1}번 품목 ID`} maxLength={64} value={row.item} placeholder="선택 입력" disabled={locked} onChange={event => edit(row.id, 'item', event.target.value)}  density="compact" /></TableCell>
                  <TableCell className="h-15.75 border-b border-border px-2.5 py-3 text-right tabular-nums">{num(row.quantity * row.price)}</TableCell>
                  <TableCell className="h-15.75 border-b border-border px-2.5 py-3"><Button className="grid" aria-label={`${index + 1}번 품목 삭제`} disabled={locked || rows.length <= 1} onClick={() => setRows(rows.filter(item => item.id !== row.id))} variant="ghost" size="icon-sm"><Icon name="close" size={14} /></Button></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Button className="add-line my-3 mb-4.5 flex items-center" disabled={locked || rows.length >= LINE_LIMIT} onClick={() => { setRows([...rows, { id: nextId, name: '', quantity: 1, price: 0, item: '' }]); setNextId(nextId + 1); }} variant="ghost" size="icon-sm"><Icon name="plus" size={14} />품목 추가</Button>
          <p className="panel-footnote text-label leading-roomy text-muted-foreground">확정 품목 ID는 선택 입력입니다. 합계는 입력 확인용이며 실제 업무 금액은 서버가 검증합니다. 품목 매핑 후보 조회·자동 매핑·원본 파일 접수는 제공하지 않습니다.</p>
        </section>
      </div>

      <footer className="action-bar sticky bottom-0 z-[2] mt-auto flex min-h-17.25 flex-wrap items-center justify-between gap-3 border-0 bg-olive px-3.5 py-3 text-white min-[760px]:flex-nowrap min-[760px]:gap-5 min-[760px]:px-6.5">
        <div className="action-summary flex items-center gap-4">
          <strong className="text-sm font-medium tabular-nums tracking-number min-[760px]:text-section">₩ {num(rows.reduce((sum, row) => sum + row.quantity * row.price, 0))}</strong>
          <span className="hidden text-label text-white min-[760px]:inline">입력 금액 합계(확인용) · {statusText(composer.status)}</span>
        </div>
        <div className="action-buttons flex items-center gap-1.25 min-[760px]:gap-2.25">
          <span className="footer-context mr-2.25 hidden text-label text-white min-[1200px]:inline">
            {composer.caseId ? `사건 ${composer.caseId.slice(0, 8)} · v${composer.caseVersion ?? '—'}` : '사건 미생성'}
            {composer.submittedBundleVersion ? ` · 증빙 v${composer.submittedBundleVersion}` : ''}
          </span>
          <Button variant="outline" className="footer-secondary" disabled={!isSubmitter || !valid || locked || Boolean(loadError)} onClick={onSave}>초안 저장</Button>
          <Button disabled={!isSubmitter || !valid || locked || Boolean(loadError)} onClick={onSubmit}>{supplementId ? '보완 재제출' : '제출'}<Icon name="chevron" size={14} /></Button>
        </div>
      </footer>
      <Toast message={toast} dismiss={() => setToast('')} />
    </Shell>
  );
}

// Keyed by the target case: navigating from one draft/supplement to another
// mounts a fresh form, so no adoption ref, row or intent from the previous case
// can leak into the next.
function NewCaseRoute() {
  const searchParams = useSearchParams();
  const { sessionId } = useAuth();
  const draftId = searchParams.get('case');
  const supplementId = searchParams.get('supplement');
  // Key on both the account session and the target case: a different login must
  // not inherit the previous account's rows, adoption ref or opened revision.
  return <NewCaseForm key={`${sessionId}#${draftId ?? supplementId ?? 'new'}`} draftId={draftId} supplementId={supplementId} />;
}

export default function NewCasePage() {
  return (
    <Suspense fallback={<Shell active="new" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-3.75 px-5 py-10 text-center text-muted-foreground" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-muted-foreground">불러오는 중</h1><p className="text-label">청구 작성 화면을 준비하고 있습니다.</p></section></Shell>}>
      <NewCaseRoute />
    </Suspense>
  );
}
