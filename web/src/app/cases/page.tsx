'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import { Icon, PageHeader, Shell } from '../ui';
import { useAuth } from '../auth';
import { formatInstant, presentStatus } from '../api/contract';
import { resolveSubmittedRange } from '../api/daterange';
import type { InvoiceCaseFilters, SearchField } from '../api/query';
import { filtersFromSearchParams, searchFromFilters } from './list-query';
import { pageNumbers } from './list-preview';
import { useInvoiceCases } from './use-invoice-cases';

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

  const workbench = <div className={`review-workbench ${selectedRow ? 'with-panel' : ''}`}>
    <div className="table-area">
      <div className="table-scroll" aria-busy={isLoading}>
        <table className="work-table case-list-table">
          <caption className="sr-only">매입 청구서 목록. 서버 부분 검색과 서버 페이지 이동이 적용됩니다.</caption>
          <thead><tr><th>공급사 ID / 제출자</th><th>청구번호</th><th>상태</th><th>발주번호</th><th>제출 시각 (KST)</th><th><span className="sr-only">선택</span></th></tr></thead>
          <tbody>
            {rows.map(row => {
              const presentation = presentStatus(row.status);
              return <tr key={row.id} className={selected === row.id ? 'selected' : ''}>
                <td><div className="vendor-cell"><span className="vendor-avatar" aria-hidden="true"><Icon name="grid" size={14} /></span><div><strong>{row.supplierId}</strong><small>제출자 {row.submittedBy}</small></div></div></td>
                <td><Link href={detailHref(row.id)}>{row.invoiceNumber}</Link></td>
                <td><span className={`case-status status-${presentation.tone}`}><span className="case-status-dot" />{presentation.label}</span></td>
                <td className="muted-text">{row.purchaseOrderId}</td>
                <td className="muted-text">{formatInstant(row.submittedAt)}</td>
                <td><button className="row-detail" aria-label={`${row.invoiceNumber} 요약`} aria-expanded={selected === row.id} onClick={() => setSelected(selected === row.id ? null : row.id)}><Icon name="chevron" size={14} /></button></td>
              </tr>;
            })}
            {!rows.length && <tr><td colSpan={6} className="empty-table">조건에 맞는 청구서가 없습니다.</td></tr>}
          </tbody>
        </table>
      </div>
      <div className="table-summary list-pagination">
        <span>총 {totalItems}건 · {totalItems ? filters.page * filters.size + 1 : 0}–{Math.min(filters.page * filters.size + rows.length, totalItems)}건 표시{isLoading ? ' · 불러오는 중' : ''}</span>
        <label className="small-filter">페이지당 표시 수<select aria-label="페이지당 표시 수" value={filters.size} onChange={event => updateFilters({ size: Number(event.target.value), page: 0 })}>{[20, 50, 100].map(option => <option key={option} value={option}>{option}</option>)}</select></label>
        <nav className="pagination" aria-label="목록 페이지">
          <button className="button" disabled={filters.page === 0} onClick={() => updateFilters({ page: filters.page - 1 })}>이전</button>
          {pageNumbers(currentPage, totalPages).map(item => typeof item === 'number' ? <button key={item} className={`page-number ${item === currentPage ? 'is-active' : ''}`} aria-label={`${item}페이지`} aria-current={item === currentPage ? 'page' : undefined} onClick={() => updateFilters({ page: item - 1 })}>{item}</button> : <span key={item} className="page-gap" aria-hidden="true">…</span>)}
          <button className="button" disabled={filters.page + 1 >= totalPages} onClick={() => updateFilters({ page: filters.page + 1 })}>다음</button>
        </nav>
      </div>
    </div>
    {selectedRow && <aside className="evidence-panel">
      <div className="panel-title">청구서 요약<button className="icon-button" aria-label="청구서 요약 닫기" onClick={() => setSelected(null)}><Icon name="close" /></button></div>
      <h2>{selectedRow.invoiceNumber}</h2>
      <p className="panel-subtitle">공급사 ID {selectedRow.supplierId}</p>
      <dl className="receipt-data">
        <div><dt>공급사 ID</dt><dd>{selectedRow.supplierId}</dd></div>
        <div><dt>제출자 계정</dt><dd>{selectedRow.submittedBy}</dd></div>
        <div><dt>상태</dt><dd>{selectedStatus?.label}</dd></div>
        <div><dt>발주번호</dt><dd>{selectedRow.purchaseOrderId}</dd></div>
        <div><dt>제출 시각 (KST)</dt><dd>{formatInstant(selectedRow.submittedAt)}</dd></div>
        <div><dt>청구서 버전</dt><dd>v{selectedRow.version}</dd></div>
      </dl>
      <div className="dialog-actions"><Link className="button primary" href={detailHref(selectedRow.id)}>상세 열기<Icon name="chevron" size={14} /></Link></div>
      <p className="panel-footnote">서버 목록 요약입니다. 상세는 실제 조회 화면으로 이동합니다.</p>
    </aside>}
  </div>;

  const body = !isAuthenticated
    ? <section className="empty-state" role="status"><Icon name="clock" size={25} /><h1>로그인이 필요합니다</h1><p>로그인 화면으로 이동합니다.</p></section>
    : state === 'loading' && !pageResult
      ? <section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>청구 목록을 서버에서 확인하고 있습니다.</p></section>
      : state === 'forbidden'
        ? <section className="empty-state" role="status"><Icon name="document" size={25} /><h1>이 화면에 접근할 권한이 없습니다</h1><p>계정 역할을 확인해 주세요.</p><button className="button" onClick={retry}>다시 시도</button></section>
        : state === 'error'
          ? <section className="empty-state" role="alert"><Icon name="document" size={25} /><h1>자료를 불러오지 못했습니다</h1><p>{loadError}</p><button className="button" onClick={retry}>다시 시도</button></section>
          : pageResult
            ? workbench
            : <section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>청구 목록을 서버에서 확인하고 있습니다.</p></section>;

  return <Shell active="cases" preview={false}>
    <PageHeader eyebrow="청구 업무" title="매입 청구서" subtitle={canSeeAllSubmitters ? '청구서와 처리 상태를 확인하세요.' : '제출한 청구서와 처리 상태를 확인하세요.'} action={<Link className="button primary" href="/cases/new"><Icon name="plus" />청구 작성</Link>} />
    <div className="tabs" role="tablist" aria-label="청구서 상태">{tabs.map(([id, label]) => <button key={id} role="tab" aria-selected={filters.status === id} className={filters.status === id ? 'active' : ''} onClick={() => updateFilters({ status: id, page: 0 })}>{label}</button>)}</div>
    <div className="list-filter-area">
      <form className="list-search-form" onSubmit={applySearch}>
        <select aria-label="검색 항목" value={drafts.searchField} onChange={event => patchDrafts({ searchField: event.target.value as SearchField })}><option value="invoiceNumber">청구번호</option><option value="purchaseOrderId">발주번호</option></select>
        <label className="search"><input aria-label="청구서 검색" placeholder={drafts.searchField === 'invoiceNumber' ? '청구번호 검색' : '발주번호 검색'} value={drafts.query} onChange={event => patchDrafts({ query: event.target.value })} /></label>
        <button className="button" type="submit"><Icon name="search" />검색</button>
      </form>
      <div className="list-filter-row">
        <label className="small-filter">공급사 ID<input aria-label="공급사 ID 필터" maxLength={64} placeholder="정확히 일치" value={drafts.supplier} onChange={event => patchDrafts({ supplier: event.target.value })} onBlur={commitSupplier} onKeyDown={event => commitOnEnter(event, commitSupplier)} /></label>
        {canSeeAllSubmitters && (
          <label className="small-filter">제출자 계정<input aria-label="제출자 계정 필터" maxLength={64} placeholder="정확히 일치" value={drafts.submitter} onChange={event => patchDrafts({ submitter: event.target.value })} onBlur={commitSubmitter} onKeyDown={event => commitOnEnter(event, commitSubmitter)} /></label>
        )}
        <label className="small-filter date-range">제출일
          <input type="date" aria-label="제출일 시작" value={drafts.rangeStart} onChange={event => { patchDrafts({ rangeStart: event.target.value }); commitRange(event.target.value, drafts.rangeEnd); }} />
          <span aria-hidden="true">~</span>
          <input type="date" aria-label="제출일 끝" value={drafts.rangeEnd} onChange={event => { patchDrafts({ rangeEnd: event.target.value }); commitRange(drafts.rangeStart, event.target.value); }} />
        </label>
        <button className="button filter-reset" onClick={resetFilters}>필터 초기화</button>
      </div>
      {rangeError && <p className="list-range-error" role="alert">{rangeError}</p>}
      {filters.searchValue && <div className="applied-query" role="status">적용된 검색: {filters.searchField === 'invoiceNumber' ? '청구번호' : '발주번호'} = {filters.searchValue}<button className="icon-button" aria-label="검색 조건 지우기" onClick={() => updateFilters({ searchValue: null, page: 0 })}><Icon name="close" size={12} /></button></div>}
    </div>
    {body}
  </Shell>;
}

export default function CasesPage() {
  return (
    <Suspense fallback={<Shell active="cases" preview={false}><section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>청구 목록을 서버에서 확인하고 있습니다.</p></section></Shell>}>
      <Cases />
    </Suspense>
  );
}
