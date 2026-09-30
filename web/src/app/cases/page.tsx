'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon, PageHeader, Shell } from '../ui';
import { useAuth } from '../auth';
import { fetchInvoiceCases } from '../api/client';
import { ApiRequestError } from '../api/transport';
import { formatInstant, presentStatus, type InvoiceCasePage } from '../api/contract';
import type { InvoiceCaseFilters, SearchField } from '../api/query';
import { pageNumbers } from './list-preview';

type LoadState = 'ready' | 'empty' | 'error' | 'forbidden';
type LoadResult = { key: string; state: LoadState; result: InvoiceCasePage | null; error: string };

const tabs: Array<[string, string]> = [
  ['all', '전체'],
  ['REVIEW_PENDING', '검토 대기'],
  ['SUPPLEMENT_REQUIRED', '보완 대기'],
  ['EXPORT_PENDING', '인계 대기'],
  ['EXPORTED', '인계 완료'],
];

export default function Cases() {
  const router = useRouter();
  const { credentials, isAuthenticated, logout } = useAuth();

  const [status, setStatus] = useState('all');
  const [draftQuery, setDraftQuery] = useState('');
  const [searchField, setSearchField] = useState<SearchField>('invoiceNumber');
  const [searchValue, setSearchValue] = useState<string | null>(null);
  const [supplierDraft, setSupplierDraft] = useState('');
  const [supplierId, setSupplierId] = useState<string | null>(null);
  const [submitterDraft, setSubmitterDraft] = useState('');
  const [submittedBy, setSubmittedBy] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const [loaded, setLoaded] = useState<LoadResult | null>(null);

  const filters = useMemo<InvoiceCaseFilters>(
    () => ({ status, supplierId, submittedBy, searchField, searchValue, page, size }),
    [status, supplierId, submittedBy, searchField, searchValue, page, size],
  );
  const requestKey = useMemo(() => `${JSON.stringify(filters)}#${reloadToken}`, [filters, reloadToken]);
  const isLoading = !loaded || loaded.key !== requestKey;

  useEffect(() => {
    if (!isAuthenticated) router.replace('/login');
  }, [isAuthenticated, router]);

  // One server query per applied filter/page/size change. The previous request
  // is aborted so a slow, outdated response can never overwrite a newer one.
  useEffect(() => {
    if (!credentials) return;
    const controller = new AbortController();
    fetchInvoiceCases(credentials, filters, controller.signal)
      .then(pageResult => {
        setLoaded({ key: requestKey, state: pageResult.items.length ? 'ready' : 'empty', result: pageResult, error: '' });
      })
      .catch((caught: unknown) => {
        if (caught instanceof Error && caught.name === 'AbortError') return;
        if (caught instanceof ApiRequestError && caught.status === 401) {
          logout();
          router.replace('/login');
          return;
        }
        if (caught instanceof ApiRequestError && caught.status === 403) {
          setLoaded({ key: requestKey, state: 'forbidden', result: null, error: '' });
          return;
        }
        setLoaded({ key: requestKey, state: 'error', result: null, error: caught instanceof Error ? caught.message : '목록을 불러오지 못했습니다.' });
      });
    return () => controller.abort();
  }, [credentials, filters, requestKey, logout, router]);

  const resetPage = useCallback(() => { setPage(0); setSelected(null); }, []);
  function applySearch(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = draftQuery.trim();
    setSearchValue(value ? value : null);
    resetPage();
  }
  function commitSupplier() {
    const value = supplierDraft.trim();
    setSupplierId(value ? value : null);
    resetPage();
  }
  function commitSubmitter() {
    const value = submitterDraft.trim();
    setSubmittedBy(value ? value : null);
    resetPage();
  }
  function commitOnEnter(event: React.KeyboardEvent<HTMLInputElement>, commit: () => void) {
    if (event.key === 'Enter') { event.preventDefault(); commit(); }
  }
  function resetFilters() {
    setDraftQuery(''); setSearchValue(null); setSearchField('invoiceNumber');
    setSupplierDraft(''); setSupplierId(null); setSubmitterDraft(''); setSubmittedBy(null);
    setStatus('all'); resetPage();
  }
  function retry() { setReloadToken(value => value + 1); }

  const pageResult = loaded?.result ?? null;
  const rows = pageResult?.items ?? [];
  const totalItems = pageResult?.totalItems ?? 0;
  const totalPages = Math.max(1, pageResult?.totalPages ?? 1);
  const currentPage = Math.min(page + 1, totalPages);
  const selectedRow = rows.find(row => row.id === selected);
  const selectedStatus = selectedRow ? presentStatus(selectedRow.status) : null;

  const workbench = <div className={`review-workbench ${selectedRow ? 'with-panel' : ''}`}>
    <div className="table-area">
      <div className="table-scroll" aria-busy={isLoading}>
        <table className="work-table case-list-table">
          <caption className="sr-only">매입 청구서 목록. 서버 부분 검색과 서버 페이지 이동이 적용됩니다.</caption>
          <thead><tr><th>공급사 ID / 제출자</th><th>청구번호</th><th>상태</th><th>발주번호</th><th>제출 시각</th><th><span className="sr-only">선택</span></th></tr></thead>
          <tbody>
            {rows.map(row => {
              const presentation = presentStatus(row.status);
              return <tr key={row.id} className={selected === row.id ? 'selected' : ''}>
                <td><div className="vendor-cell"><span className="vendor-avatar" aria-hidden="true"><Icon name="grid" size={14} /></span><div><strong>{row.supplierId}</strong><small>제출자 {row.submittedBy}</small></div></div></td>
                <td><strong>{row.invoiceNumber}</strong></td>
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
        <span>총 {totalItems}건 · {totalItems ? page * size + 1 : 0}–{Math.min(page * size + rows.length, totalItems)}건 표시{isLoading ? ' · 불러오는 중' : ''}</span>
        <label className="small-filter">표시 수<select aria-label="페이지당 표시 수" value={size} onChange={event => { setSize(Number(event.target.value)); resetPage(); }}>{[20, 50, 100].map(option => <option key={option} value={option}>{option}건씩</option>)}</select></label>
        <nav className="pagination" aria-label="목록 페이지">
          <button className="button" disabled={page === 0} onClick={() => { setPage(page - 1); setSelected(null); }}>이전</button>
          {pageNumbers(currentPage, totalPages).map(item => typeof item === 'number' ? <button key={item} className={`page-number ${item === currentPage ? 'is-active' : ''}`} aria-label={`${item}페이지`} aria-current={item === currentPage ? 'page' : undefined} onClick={() => { setPage(item - 1); setSelected(null); }}>{item}</button> : <span key={item} className="page-gap" aria-hidden="true">…</span>)}
          <button className="button" disabled={page + 1 >= totalPages} onClick={() => { setPage(page + 1); setSelected(null); }}>다음</button>
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
        <div><dt>제출 시각</dt><dd>{formatInstant(selectedRow.submittedAt)}</dd></div>
      </dl>
      <p className="panel-footnote">서버 목록 요약입니다. 상세·비교·승인 화면은 다음 작업 범위이며 가상 시안으로만 제공됩니다.</p>
    </aside>}
  </div>;

  const body = !isAuthenticated
    ? <section className="empty-state" role="status"><Icon name="clock" size={25} /><h1>로그인이 필요합니다</h1><p>로그인 화면으로 이동합니다.</p></section>
    : isLoading && !pageResult
      ? <section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>청구 목록을 서버에서 확인하고 있습니다.</p></section>
      : loaded?.state === 'forbidden'
        ? <section className="empty-state" role="status"><Icon name="document" size={25} /><h1>이 화면에 접근할 권한이 없습니다</h1><p>계정 역할을 확인해 주세요.</p><button className="button" onClick={retry}>다시 시도</button></section>
        : loaded?.state === 'error'
          ? <section className="empty-state" role="alert"><Icon name="document" size={25} /><h1>자료를 불러오지 못했습니다</h1><p>{loaded.error}</p><button className="button" onClick={retry}>다시 시도</button></section>
          : pageResult
            ? workbench
            : <section className="empty-state" role="status" aria-busy="true"><Icon name="clock" size={25} /><h1>불러오는 중</h1><p>청구 목록을 서버에서 확인하고 있습니다.</p></section>;

  return <Shell active="cases" preview={false}>
    <PageHeader eyebrow="청구 업무" title="매입 청구서" subtitle="제출된 청구와 처리 상태를 서버에서 조회합니다." action={<Link className="button primary" href="/cases/new"><Icon name="plus" />청구 작성</Link>} />
    <div className="tabs" role="tablist" aria-label="청구서 상태">{tabs.map(([id, label]) => <button key={id} role="tab" aria-selected={status === id} className={status === id ? 'active' : ''} onClick={() => { setStatus(id); resetPage(); }}>{label}</button>)}</div>
    <div className="list-filter-area">
      <form className="list-search-form" onSubmit={applySearch}>
        <select aria-label="검색 항목" value={searchField} onChange={event => setSearchField(event.target.value as SearchField)}><option value="invoiceNumber">청구번호</option><option value="purchaseOrderId">발주번호</option></select>
        <label className="search"><input aria-label="청구서 검색" aria-describedby="list-search-help" placeholder={searchField === 'invoiceNumber' ? '청구번호의 일부 입력 · 예: 0142' : '발주번호의 일부 입력 · 예: 0142'} value={draftQuery} onChange={event => setDraftQuery(event.target.value)} /></label>
        <button className="button" type="submit"><Icon name="search" />검색</button>
        <span id="list-search-help" className="list-search-help">번호의 일부 입력 · Enter 또는 검색 · 서버 부분 검색</span>
      </form>
      <div className="list-filter-row">
        <label className="small-filter">공급사 ID<input aria-label="공급사 ID 필터" maxLength={64} placeholder="정확히 일치" value={supplierDraft} onChange={event => setSupplierDraft(event.target.value)} onBlur={commitSupplier} onKeyDown={event => commitOnEnter(event, commitSupplier)} /></label>
        <label className="small-filter">제출자 계정<input aria-label="제출자 계정 필터" maxLength={64} placeholder="정확히 일치" value={submitterDraft} onChange={event => setSubmitterDraft(event.target.value)} onBlur={commitSubmitter} onKeyDown={event => commitOnEnter(event, commitSubmitter)} /></label>
        <button className="button filter-reset" onClick={resetFilters}>필터 초기화</button>
      </div>
      {searchValue && <div className="applied-query" role="status">적용된 검색: {searchField === 'invoiceNumber' ? '청구번호' : '발주번호'} = {searchValue}<button className="icon-button" aria-label="검색 조건 지우기" onClick={() => { setSearchValue(null); setDraftQuery(''); resetPage(); }}><Icon name="close" size={12} /></button></div>}
    </div>
    {body}
  </Shell>;
}
