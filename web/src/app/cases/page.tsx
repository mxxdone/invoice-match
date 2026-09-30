'use client';

import Link from 'next/link';
import { useState } from 'react';
import { DemoBar, Icon, PageHeader, Shell } from '../ui';
import { pageNumbers, previewCases, statusPresentation, suppliers } from './list-preview';

type SearchField = 'invoiceNumber' | 'purchaseOrderId';
// Preview normalization mirrors the existing invoice-number search for these fixtures.
const normalizeInvoice = (value: string) => value.replace(/[^\p{L}\p{N}]/gu, '').toUpperCase();

export default function Cases() {
  const [status, setStatus] = useState('all');
  const [draftQuery, setDraftQuery] = useState('');
  const [searchField, setSearchField] = useState<SearchField>('invoiceNumber');
  const [search, setSearch] = useState<{field:SearchField;value:string} | null>(null);
  const [supplier, setSupplier] = useState('all');
  const [owner, setOwner] = useState('all');
  const [date, setDate] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [selected, setSelected] = useState<string | null>(null);
  const rows = previewCases.filter(row => (status === 'all' || row.status === status)
    && (supplier === 'all' || row.supplierId === supplier) && (owner === 'all' || row.submittedBy === owner)
    && (!date || row.submittedAt?.startsWith(date.replaceAll('-', '.')))
    && (!search || (search.field === 'invoiceNumber'
      ? normalizeInvoice(row.invoiceNumber).includes(normalizeInvoice(search.value))
      : row.purchaseOrderId.toLowerCase().includes(search.value.toLowerCase()))));
  const pageCount = Math.max(1, Math.ceil(rows.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const visibleRows = rows.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const selectedRow = visibleRows.find(row => row.id === selected);
  const selectedSupplier = suppliers.find(item => item.id === selectedRow?.supplierId);
  const tabs = [['all','전체'],['REVIEW_PENDING','검토 대기'],['SUPPLEMENT_REQUIRED','보완 대기'],['EXPORT_PENDING','인계 대기'],['EXPORTED','인계 완료']];
  const resetPage = () => {setPage(1);setSelected(null);};
  function resetFilters() {
    setDraftQuery('');setSearch(null);setSearchField('invoiceNumber');setSupplier('all');setOwner('all');setDate('');setStatus('all');resetPage();
  }
  return <Shell active="cases">
    <PageHeader eyebrow="청구 업무" title="매입 청구서" subtitle="제출된 청구와 처리 상태를 한곳에서 확인합니다." action={<Link className="button primary" href="/cases/new"><Icon name="plus" />청구 작성</Link>} />
    <div className="tabs" role="tablist" aria-label="청구서 상태">{tabs.map(([id,label]) => <button key={id} role="tab" aria-selected={status === id} className={status === id ? 'active' : ''} onClick={() => {setStatus(id);resetPage();}}>{label}<span className="tab-count">{previewCases.filter(row => id === 'all' || row.status === id).length}</span></button>)}</div>
    <DemoBar><span className="list-fixture-note">회사명·48건 목록은 가상 예시</span></DemoBar>
    <div className="list-filter-area">
      <form className="list-search-form" onSubmit={event => {event.preventDefault();setSearch(draftQuery.trim() ? {field:searchField,value:draftQuery.trim()} : null);resetPage();}}>
        <select aria-label="검색 항목" value={searchField} onChange={event => setSearchField(event.target.value as SearchField)}><option value="invoiceNumber">청구번호</option><option value="purchaseOrderId">발주번호</option></select>
        <label className="search"><input aria-label="청구서 검색" aria-describedby="list-search-help" placeholder={searchField === 'invoiceNumber' ? '청구번호의 일부 입력 · 예: 0142' : '발주번호의 일부 입력 · 예: 0142'} value={draftQuery} onChange={event => setDraftQuery(event.target.value)} /></label>
        <button className="button" type="submit"><Icon name="search" />검색</button>
        <span id="list-search-help" className="list-search-help">번호의 일부 입력 · Enter 또는 검색</span>
      </form>
      <div className="list-filter-row">
        <label className="small-filter">공급사<select aria-label="공급사 필터" value={supplier} onChange={event => {setSupplier(event.target.value);resetPage();}}><option value="all">전체</option>{suppliers.map(item => <option key={item.id} value={item.id}>{item.name} · {item.id}</option>)}</select></label>
        <label className="small-filter">제출자<select aria-label="제출자 필터" value={owner} onChange={event => {setOwner(event.target.value);resetPage();}}><option value="all">전체</option>{Array.from(new Set(previewCases.map(row => row.submittedBy))).map(id => <option key={id}>{id}</option>)}</select></label>
        <label className="small-filter">제출일<input type="date" aria-label="제출일 필터" value={date} onChange={event => {setDate(event.target.value);resetPage();}} /></label>
        <button className="button filter-reset" onClick={resetFilters}>필터 초기화</button>
      </div>
      {search && <div className="applied-query" role="status">적용된 검색: {search.field === 'invoiceNumber' ? '청구번호' : '발주번호'} = {search.value}<button className="icon-button" aria-label="검색 조건 지우기" onClick={() => {setSearch(null);setDraftQuery('');resetPage();}}><Icon name="close" size={12} /></button></div>}
    </div>
    <div className={`review-workbench ${selectedRow ? 'with-panel' : ''}`}>
      <div className="table-area"><div className="table-scroll"><table className="work-table case-list-table"><caption className="sr-only">가상 매입 청구서 목록. 검색과 페이지 이동은 시안 데이터에만 적용됩니다.</caption><thead><tr><th>공급사 / 제출자</th><th>청구번호</th><th>상태</th><th>발주번호</th><th>제출 시각</th><th><span className="sr-only">선택</span></th></tr></thead><tbody>
        {visibleRows.map(row => {
          const company = suppliers.find(item => item.id === row.supplierId);
          const presentation = statusPresentation[row.status];
          return <tr key={row.id} className={selected === row.id ? 'selected' : ''}>
            <td><div className="vendor-cell"><span className={`vendor-avatar tint-${company?.tone ?? 0}`} aria-hidden="true">{company ? Array.from(company.name).slice(0,2).join('') : <Icon name="grid" size={14} />}</span><div><strong>{company?.name ?? row.supplierId}</strong><small>{row.supplierId} · {row.submittedBy}</small></div></div></td>
            <td><strong>{row.invoiceNumber}</strong></td><td><span className={`case-status status-${presentation.tone}`}><span className="case-status-dot" />{presentation.label}</span></td><td className="muted-text">{row.purchaseOrderId}</td><td className="muted-text">{row.submittedAt ?? '—'}</td>
            <td><button className="row-detail" aria-label={`${row.id}번 청구서 요약`} aria-expanded={selected === row.id} onClick={() => setSelected(selected === row.id ? null : row.id)}><Icon name="chevron" size={14} /></button></td>
          </tr>;
        })}
        {!rows.length && <tr><td colSpan={6} className="empty-table">조건에 맞는 청구서가 없습니다.</td></tr>}
      </tbody></table></div>
      <div className="table-summary list-pagination"><span>총 {rows.length}건 · {rows.length ? (currentPage-1)*pageSize+1 : 0}–{Math.min(currentPage*pageSize,rows.length)}건 표시</span><label className="small-filter">표시 수<select aria-label="페이지당 표시 수" value={pageSize} onChange={event => {setPageSize(Number(event.target.value));resetPage();}}>{[20,50,100].map(size => <option key={size} value={size}>{size}건씩</option>)}</select></label>
        <nav className="pagination" aria-label="목록 페이지"><button className="button" disabled={currentPage === 1} onClick={() => {setPage(currentPage-1);setSelected(null);}}>이전</button>{pageNumbers(currentPage,pageCount).map(item => typeof item === 'number' ? <button key={item} className={`page-number ${item === currentPage ? 'is-active' : ''}`} aria-label={`${item}페이지`} aria-current={item === currentPage ? 'page' : undefined} onClick={() => {setPage(item);setSelected(null);}}>{item}</button> : <span key={item} className="page-gap" aria-hidden="true">…</span>)}<button className="button" disabled={currentPage === pageCount} onClick={() => {setPage(currentPage+1);setSelected(null);}}>다음</button></nav>
      </div></div>
      {selectedRow && <aside className="evidence-panel"><div className="panel-title">청구서 요약<button className="icon-button" aria-label="청구서 요약 닫기" onClick={() => setSelected(null)}><Icon name="close" /></button></div><h2>{selectedRow.invoiceNumber}</h2><p className="panel-subtitle">{selectedSupplier?.name ?? selectedRow.supplierId}</p><dl className="receipt-data"><div><dt>공급사 코드</dt><dd>{selectedRow.supplierId}</dd></div><div><dt>제출자</dt><dd>{selectedRow.submittedBy}</dd></div><div><dt>상태</dt><dd>{statusPresentation[selectedRow.status].label}</dd></div><div><dt>발주번호</dt><dd>{selectedRow.purchaseOrderId}</dd></div></dl>{selectedRow.id === '0142' ? <Link className="button" href="/">청구서 상세 열기<Icon name="chevron" size={13} /></Link> : <p className="panel-footnote">목록 배치 확인용입니다. 상세 시안은 INV-2026-0142 한 건만 제공합니다.</p>}</aside>}
    </div>
  </Shell>;
}
