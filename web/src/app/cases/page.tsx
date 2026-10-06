'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import { Icon, PageHeader, Shell } from '../ui';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuth } from '../auth';
import { formatInstant, presentStatus } from '../api/contract';
import { resolveSubmittedRange } from '../api/daterange';
import type { InvoiceCaseFilters, SearchField } from '../api/query';
import { filtersFromSearchParams, searchFromFilters } from './list-query';
import { pageNumbers } from './list-preview';
import { useInvoiceCases } from './use-invoice-cases';

type StatusTone = 'neutral' | 'pending' | 'attention' | 'complete' | 'rejected';

const tabs: Array<[string, string]> = [
  ['all', '전체'],
  ['REVIEW_PENDING', '검토 대기'],
  ['SUPPLEMENT_REQUIRED', '보완 대기'],
  ['EXPORT_PENDING', '인계 대기'],
  ['EXPORTED', '인계 완료'],
];

type Drafts = {
  query: string;
  searchField: SearchField;
  supplier: string;
  submitter: string;
  rangeStart: string;
  rangeEnd: string;
};

function draftsFromFilters(filters: InvoiceCaseFilters): Drafts {
  return {
    query: filters.searchValue ?? '',
    searchField: filters.searchField,
    supplier: filters.supplierId ?? '',
    submitter: filters.submittedBy ?? '',
    rangeStart: filters.submittedFrom ? filters.submittedFrom.slice(0, 10) : '',
    rangeEnd: filters.submittedTo ? filters.submittedTo.slice(0, 10) : '',
  };
}

function Cases() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { credentials, user, sessionId, isAuthenticated, logout } = useAuth();
  // A submitter-only account is already row-scoped to its own cases by the
  // server, so the 제출자 filter is meaningless for it and is hidden.
  const canSeeAllSubmitters = (user?.roles ?? []).some((role) => role === 'APPROVER' || role === 'OPERATOR');

  // The committed filters live in the URL so navigating into a case detail and
  // back restores the same server query; only the uncommitted text inputs are
  // component state.
  const search = searchParams.toString();
  const filters = useMemo(() => filtersFromSearchParams(new URLSearchParams(search)), [search]);
  const listFrom = search;
  const detailHref = useCallback(
    (id: string) => (listFrom ? `/cases/${id}?from=${encodeURIComponent(listFrom)}` : `/cases/${id}`),
    [listFrom],
  );

  // The editable inputs are seeded from the URL during render (not in an
  // effect): when the committed filters change (a commit, or back/forward) the
  // inputs re-derive, while typing only touches the draft state.
  const [drafts, setDrafts] = useState<Drafts>(() => draftsFromFilters(filters));
  const [seededFilters, setSeededFilters] = useState(filters);
  const [rangeError, setRangeError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  if (seededFilters !== filters) {
    setSeededFilters(filters);
    setDrafts(draftsFromFilters(filters));
    setRangeError(null);
  }
  const patchDrafts = (patch: Partial<Drafts>) => setDrafts((previous) => ({ ...previous, ...patch }));

  const updateFilters = useCallback((patch: Partial<InvoiceCaseFilters>) => {
    const next = { ...filters, ...patch };
    const query = searchFromFilters(next);
    router.replace(query ? `/cases?${query}` : '/cases', { scroll: false });
    setSelected(null);
  }, [filters, router]);

  const onUnauthorized = useCallback(() => {
    logout();
    router.replace('/login');
  }, [logout, router]);

  useEffect(() => {
    if (!isAuthenticated) router.replace('/login');
  }, [isAuthenticated, router]);

  const { state, page: pageResult, error: loadError, isLoading } = useInvoiceCases({
    credentials,
    sessionId,
    filters,
    reloadToken,
    onUnauthorized,
  });

  function applySearch(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = drafts.query.trim();
    updateFilters({ searchField: drafts.searchField, searchValue: value ? value : null, page: 0 });
  }
  function commitSupplier() {
    const value = drafts.supplier.trim();
    updateFilters({ supplierId: value ? value : null, page: 0 });
  }
  function commitSubmitter() {
    const value = drafts.submitter.trim();
    updateFilters({ submittedBy: value ? value : null, page: 0 });
  }
  // The two date inputs map to the KST inclusive instant range. An invalid or
  // reversed range is reported and not applied; a valid change resets page 0.
  function commitRange(nextStart: string, nextEnd: string) {
    const resolved = resolveSubmittedRange(nextStart, nextEnd);
    if (resolved.error) {
      setRangeError(resolved.error);
      return;
    }
    setRangeError(null);
    updateFilters({ submittedFrom: resolved.from, submittedTo: resolved.to, page: 0 });
  }
  function commitOnEnter(event: React.KeyboardEvent<HTMLInputElement>, commit: () => void) {
    if (event.key === 'Enter') { event.preventDefault(); commit(); }
  }
  function resetFilters() {
    setRangeError(null);
    router.replace('/cases', { scroll: false });
    setSelected(null);
  }
  function retry() { setReloadToken(value => value + 1); }

  const rows = pageResult?.items ?? [];
  const totalItems = pageResult?.totalItems ?? 0;
  const totalPages = Math.max(1, pageResult?.totalPages ?? 1);
  const currentPage = Math.min(filters.page + 1, totalPages);
  const selectedRow = rows.find(row => row.id === selected);
  const selectedStatus = selectedRow ? presentStatus(selectedRow.status) : null;

  const workbench = <div className={`review-workbench flex items-stretch border-t border-border ${selectedRow ? 'with-panel max-[760px]:block' : ''}`}>
    <div className="table-area min-w-0 flex-1">
      <Table className="work-table case-list-table w-full whitespace-nowrap text-sm max-[760px]:min-w-[710px]" aria-busy={isLoading}>
        <caption className="sr-only">매입 청구서 목록. 서버 부분 검색과 서버 페이지 이동이 적용됩니다.</caption>
        <TableHeader><TableRow className="border-0"><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-[#919586] min-[1200px]:px-5">공급사 ID / 제출자</TableHead><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-[#919586] min-[1200px]:px-5">청구번호</TableHead><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-[#919586] min-[1200px]:px-5">상태</TableHead><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-[#919586] min-[1200px]:px-5">발주번호</TableHead><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-[#919586] min-[1200px]:px-5">제출 시각 (KST)</TableHead><TableHead className="border-b border-border bg-[#fcfcfa] px-[14px] py-3 text-label font-normal text-[#919586] min-[1200px]:px-5"><span className="sr-only">선택</span></TableHead></TableRow></TableHeader>
        <TableBody>
          {rows.map(row => {
            const presentation = presentStatus(row.status);
            return <TableRow key={row.id} className={`border-0 ${selected === row.id ? 'selected bg-mint' : 'hover:bg-[#fafbf6]'}`}>
              <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] min-[1200px]:px-5"><div className="vendor-cell flex items-center gap-[11px]"><span className="vendor-avatar grid h-[31px] w-[31px] place-items-center rounded-full bg-[#dcdcca] text-label text-[#61684e]" aria-hidden="true"><Icon name="grid" size={14} /></span><div><strong className="text-sm">{row.supplierId}</strong><small className="secondary-line mt-1.5 block text-label text-[#959989]">제출자 {row.submittedBy}</small></div></div></TableCell>
              <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] min-[1200px]:px-5"><Link href={detailHref(row.id)}>{row.invoiceNumber}</Link></TableCell>
              <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] min-[1200px]:px-5"><Badge variant={presentation.tone as StatusTone} className="case-status gap-1.5 px-2 py-[5px] text-label"><span className="case-status-dot h-[5px] w-[5px] rounded-full bg-current" />{presentation.label}</Badge></TableCell>
              <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] text-label text-[#8e9382] min-[1200px]:px-5">{row.purchaseOrderId}</TableCell>
              <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] text-label text-[#8e9382] min-[1200px]:px-5">{formatInstant(row.submittedAt)}</TableCell>
              <TableCell className="h-[68px] border-b border-[#eeeee7] px-[14px] py-[15px] min-[1200px]:px-5"><button className="row-detail grid h-7 w-6 place-items-center border-0 bg-transparent text-[#949985] hover:bg-[#e5ece1] hover:text-[#3a513e]" aria-label={`${row.invoiceNumber} 요약`} aria-expanded={selected === row.id} onClick={() => setSelected(selected === row.id ? null : row.id)}><Icon name="chevron" size={14} /></button></TableCell>
            </TableRow>;
          })}
          {!rows.length && <TableRow className="border-0"><TableCell colSpan={6} className="empty-table p-12 text-center text-muted-foreground">조건에 맞는 청구서가 없습니다.</TableCell></TableRow>}
        </TableBody>
      </Table>
      <div className="table-summary list-pagination flex min-h-[70px] flex-wrap justify-between gap-3.5 border-b border-[#efefe9] px-[25px] py-4 text-label text-[#93968a]">
        <span>총 {totalItems}건 · {totalItems ? filters.page * filters.size + 1 : 0}–{Math.min(filters.page * filters.size + rows.length, totalItems)}건 표시{isLoading ? ' · 불러오는 중' : ''}</span>
        <label className="small-filter flex items-center gap-2.5 text-label text-muted-foreground">페이지당 표시 수<select aria-label="페이지당 표시 수" className="rounded-sm border border-[#e3e3d8] bg-[#fafaf6] py-[7px] pl-[9px] pr-6 text-label text-[#727964]" value={filters.size} onChange={event => updateFilters({ size: Number(event.target.value), page: 0 })}>{[20, 50, 100].map(option => <option key={option} value={option}>{option}</option>)}</select></label>
        <nav className="pagination flex items-center gap-[5px]" aria-label="목록 페이지">
          <Button variant="outline" disabled={filters.page === 0} onClick={() => updateFilters({ page: filters.page - 1 })}>이전</Button>
          {pageNumbers(currentPage, totalPages).map(item => typeof item === 'number' ? <button key={item} className={`page-number min-w-[29px] h-[29px] rounded-sm border ${item === currentPage ? 'is-active border-[#d1d8bf] bg-[#e7ebdc] text-[#435236]' : 'border-transparent text-[#747e67] hover:bg-[#f3f5ee]'}`} aria-label={`${item}페이지`} aria-current={item === currentPage ? 'page' : undefined} onClick={() => updateFilters({ page: item - 1 })}>{item}</button> : <span key={item} className="page-gap px-1 text-[#9da38f]" aria-hidden="true">…</span>)}
          <Button variant="outline" disabled={filters.page + 1 >= totalPages} onClick={() => updateFilters({ page: filters.page + 1 })}>다음</Button>
        </nav>
      </div>
    </div>
    {selectedRow && <aside className="evidence-panel w-full shrink-0 border-t border-border bg-[#fdfdfb] px-[22px] py-[17px] min-[760px]:w-[280px] min-[760px]:border-l min-[760px]:border-t-0 min-[1200px]:w-[300px] min-[1600px]:w-[330px]">
      <div className="panel-title mb-[22px] flex items-center justify-between text-label text-[#8e9282]">청구서 요약<button className="icon-button grid place-items-center rounded-sm border-0 bg-transparent p-1 text-[#838776] hover:bg-[#eee]" aria-label="청구서 요약 닫기" onClick={() => setSelected(null)}><Icon name="close" /></button></div>
      <h2 className="text-detail font-medium leading-[1.6] tracking-[-.3px]">{selectedRow.invoiceNumber}</h2>
      <p className="panel-subtitle mt-1.5 text-label text-[#979b89]">공급사 ID {selectedRow.supplierId}</p>
      <dl className="receipt-data my-[22px] text-label">
        <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">공급사 ID</dt><dd className="m-0">{selectedRow.supplierId}</dd></div>
        <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">제출자 계정</dt><dd className="m-0">{selectedRow.submittedBy}</dd></div>
        <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">상태</dt><dd className="m-0">{selectedStatus?.label}</dd></div>
        <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">발주번호</dt><dd className="m-0">{selectedRow.purchaseOrderId}</dd></div>
        <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">제출 시각 (KST)</dt><dd className="m-0">{formatInstant(selectedRow.submittedAt)}</dd></div>
        <div className="mb-[13px] flex justify-between"><dt className="text-[#959a8a]">청구서 버전</dt><dd className="m-0">v{selectedRow.version}</dd></div>
      </dl>
      <div className="dialog-actions mt-[25px] flex justify-end gap-2"><Button asChild><Link href={detailHref(selectedRow.id)}>상세 열기<Icon name="chevron" size={14} /></Link></Button></div>
      <p className="panel-footnote text-label leading-[1.9] text-[#969c8b]">서버 목록 요약입니다. 상세는 실제 조회 화면으로 이동합니다.</p>
    </aside>}
  </div>;

  const body = !isAuthenticated
    ? <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">로그인이 필요합니다</h1><p className="text-label">로그인 화면으로 이동합니다.</p></section>
    : state === 'loading' && !pageResult
      ? <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">불러오는 중</h1><p className="text-label">청구 목록을 서버에서 확인하고 있습니다.</p></section>
      : state === 'forbidden'
        ? <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status"><Icon name="document" size={25} /><h1 className="text-base font-normal text-[#6f7863]">이 화면에 접근할 권한이 없습니다</h1><p className="text-label">계정 역할을 확인해 주세요.</p><Button variant="outline" onClick={retry}>다시 시도</Button></section>
        : state === 'error'
          ? <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="alert"><Icon name="document" size={25} /><h1 className="text-base font-normal text-[#6f7863]">자료를 불러오지 못했습니다</h1><p className="text-label">{loadError}</p><Button variant="outline" onClick={retry}>다시 시도</Button></section>
          : pageResult
            ? workbench
            : <section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">불러오는 중</h1><p className="text-label">청구 목록을 서버에서 확인하고 있습니다.</p></section>;

  return <Shell active="cases" preview={false}>
    <PageHeader eyebrow="청구 업무" title="매입 청구서" subtitle={canSeeAllSubmitters ? '청구서와 처리 상태를 확인하세요.' : '제출한 청구서와 처리 상태를 확인하세요.'} action={<Button asChild><Link href="/cases/new"><Icon name="plus" />청구 작성</Link></Button>} />
    <div className="tabs flex h-[53px] gap-[31px] border-b border-border px-5 min-[760px]:px-[30px] min-[1200px]:px-11 min-[1600px]:px-14" role="tablist" aria-label="청구서 상태">{tabs.map(([id, label]) => <button key={id} role="tab" aria-selected={filters.status === id} className={`flex items-center gap-2 border-b-2 bg-transparent px-1.5 text-label ${filters.status === id ? 'active border-b-[#686c5e] text-ink' : 'border-b-transparent text-[#858579]'}`} onClick={() => updateFilters({ status: id, page: 0 })}>{label}</button>)}</div>
    <div className="list-filter-area border-b border-border px-[30px] py-4">
      <form className="list-search-form flex flex-wrap items-center gap-3" onSubmit={applySearch}>
        <select aria-label="검색 항목" className="h-9 min-h-9 rounded-sm border border-[#d9ded5] bg-[#fafbf8] px-2.5 text-sm text-[#616d55]" value={drafts.searchField} onChange={event => patchDrafts({ searchField: event.target.value as SearchField })}><option value="invoiceNumber">청구번호</option><option value="purchaseOrderId">발주번호</option></select>
        <label className="search flex h-9 min-h-9 items-center gap-[9px] rounded-sm border border-[#d9ded5] bg-[#fafbf8] px-2.5 text-[#616d55]"><Input aria-label="청구서 검색" placeholder={drafts.searchField === 'invoiceNumber' ? '청구번호 검색' : '발주번호 검색'} value={drafts.query} onChange={event => patchDrafts({ query: event.target.value })} className="h-[34px] w-[180px] border-0 bg-transparent p-0 text-sm text-inherit placeholder:text-[#96998c] min-[760px]:w-[280px]" /></label>
        <Button type="submit" variant="outline"><Icon name="search" />검색</Button>
      </form>
      <div className="list-filter-row mt-3.5 flex flex-wrap items-center gap-[22px]">
        <label className="small-filter flex items-center gap-2.5 text-label text-muted-foreground">공급사 ID<Input aria-label="공급사 ID 필터" maxLength={64} placeholder="정확히 일치" value={drafts.supplier} onChange={event => patchDrafts({ supplier: event.target.value })} onBlur={commitSupplier} onKeyDown={event => commitOnEnter(event, commitSupplier)} className="h-9 min-h-9 w-40 rounded-sm border-[#d9ded5] bg-[#fafbf8] px-2.5 text-sm text-[#616d55]" /></label>
        {canSeeAllSubmitters && (
          <label className="small-filter flex items-center gap-2.5 text-label text-muted-foreground">제출자 계정<Input aria-label="제출자 계정 필터" maxLength={64} placeholder="정확히 일치" value={drafts.submitter} onChange={event => patchDrafts({ submitter: event.target.value })} onBlur={commitSubmitter} onKeyDown={event => commitOnEnter(event, commitSubmitter)} className="h-9 min-h-9 w-40 rounded-sm border-[#d9ded5] bg-[#fafbf8] px-2.5 text-sm text-[#616d55]" /></label>
        )}
        <label className="small-filter date-range flex items-center gap-2 text-label text-muted-foreground">제출일
          <Input type="date" aria-label="제출일 시작" value={drafts.rangeStart} onChange={event => { patchDrafts({ rangeStart: event.target.value }); commitRange(event.target.value, drafts.rangeEnd); }} className="h-9 w-[150px] rounded-sm border-[#d9ded5] bg-[#fafbf8] px-2.5 text-sm text-[#616d55]" />
          <span aria-hidden="true">~</span>
          <Input type="date" aria-label="제출일 끝" value={drafts.rangeEnd} onChange={event => { patchDrafts({ rangeEnd: event.target.value }); commitRange(drafts.rangeStart, event.target.value); }} className="h-9 w-[150px] rounded-sm border-[#d9ded5] bg-[#fafbf8] px-2.5 text-sm text-[#616d55]" />
        </label>
        <Button variant="outline" className="filter-reset ml-auto" onClick={resetFilters}>필터 초기화</Button>
      </div>
      {rangeError && <p className="list-range-error mt-2 text-label text-[#92594c]" role="alert">{rangeError}</p>}
      {filters.searchValue && <div className="applied-query mt-3 flex items-center gap-2.5 text-label text-[#68765a]" role="status">적용된 검색: {filters.searchField === 'invoiceNumber' ? '청구번호' : '발주번호'} = {filters.searchValue}<button className="icon-button grid place-items-center rounded-sm border-0 bg-transparent p-1 text-[#838776] hover:bg-[#eee]" aria-label="검색 조건 지우기" onClick={() => updateFilters({ searchValue: null, page: 0 })}><Icon name="close" size={12} /></button></div>}
    </div>
    {body}
  </Shell>;
}

export default function CasesPage() {
  return (
    <Suspense fallback={<Shell active="cases" preview={false}><section className="empty-state flex min-h-0 flex-1 flex-col items-center justify-center gap-[15px] px-5 py-10 text-center text-[#919887]" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1 className="text-base font-normal text-[#6f7863]">불러오는 중</h1><p className="text-label">청구 목록을 서버에서 확인하고 있습니다.</p></section></Shell>}>
      <Cases />
    </Suspense>
  );
}
