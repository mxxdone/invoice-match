'use client';

import { useState } from 'react';
import Link from 'next/link';
import { DemoBar, Icon, PageHeader, Shell, Toast } from '../../ui';
import { num } from '../../preview-data';

type DraftLine = { id: number; name: string; quantity: number; price: number; item: string };
export default function NewCase() {
  const [supplier, setSupplier] = useState('SUP-1001');
  const [po, setPo] = useState('PO-2026-0142');
  const [invoice, setInvoice] = useState('INV-2026-0143');
  const [mode, setMode] = useState('new');
  const [rows, setRows] = useState<DraftLine[]>([{id:1,name:'A4 복사용지 · 80g',quantity:100,price:24500,item:'ITEM-001'},{id:2,name:'레이저 프린터 토너 · 검정',quantity:12,price:68000,item:'ITEM-002'}]);
  const [toast, setToast] = useState('');
  const [nextId,setNextId] = useState(3);
  const total = rows.reduce((sum,row) => sum + row.quantity * row.price,0);
  const valid = supplier.trim() && po.trim() && invoice.trim() && rows.length > 0 && rows.every(row => row.name.trim() && Number.isInteger(row.quantity) && row.quantity > 0 && Number.isSafeInteger(row.price) && row.price >= 0);
  function edit(id:number,key:keyof DraftLine,value:string | number) {setRows(rows.map(row => row.id === id ? {...row,[key]:value} : row));}
  return <Shell active="new" actor="submitter" role="제출자"><PageHeader eyebrow="청구 업무 / 수동 입력" title={mode === 'new' ? '청구 작성' : '보완 청구 수정'} subtitle="청구 정보를 입력하고 품목별 수량과 단가를 확인합니다." action={<Link href="/cases" className="button">목록으로</Link>} />
    <div className="tabs" role="tablist" aria-label="작성 예시"><button role="tab" aria-selected={mode === 'new'} className={mode === 'new' ? 'active' : ''} onClick={() => setMode('new')}>신규 작성</button><button role="tab" aria-selected={mode === 'supplement'} className={mode === 'supplement' ? 'active' : ''} onClick={() => setMode('supplement')}>보완 수정 예시</button></div><DemoBar />
    <div className="form-content">{mode === 'supplement' && <div className="inline-notice"><strong>보완 요청</strong><span>수량 40개에 대한 검수 근거를 확인하고 청구 내용을 수정해 주세요.</span><small>가상 예시 · 새 증빙 버전으로 재제출하는 화면</small></div>}<section className="form-section"><div className="section-heading"><h2>청구 기본 정보</h2><span>필수 입력 *</span></div><div className="field-grid"><label>공급사 ID *<input maxLength={64} required value={supplier} onChange={event => setSupplier(event.target.value)} disabled={mode === 'supplement'} /><small>구매시스템의 공급사 식별자</small></label><label>발주번호 *<input maxLength={64} required value={po} onChange={event => setPo(event.target.value)} disabled={mode === 'supplement'} /><small>연결할 발주 건의 식별자</small></label><label>청구번호 *<input maxLength={100} required value={invoice} onChange={event => setInvoice(event.target.value)} disabled={mode === 'supplement'} /><small>공급사가 발행한 청구번호</small></label></div></section>
    <section className="form-section"><div className="section-heading"><h2>청구 품목</h2><span>{rows.length}개 품목 · 원화</span></div><div className="table-scroll"><table className="work-table edit-table"><thead><tr><th>#</th><th>청구 품목명 *</th><th>수량 *</th><th>단가 · 원 *</th><th>확정 품목 ID</th><th className="numeric">금액 · 원</th><th><span className="sr-only">삭제</span></th></tr></thead><tbody>{rows.map((row,index) => <tr key={row.id}><td>{String(index+1).padStart(2,'0')}</td><td><input aria-label={`${index+1}번 품목명`} maxLength={500} value={row.name} onChange={event => edit(row.id,'name',event.target.value)} /></td><td><input aria-label={`${index+1}번 수량`} type="number" min={1} step={1} value={row.quantity} onChange={event => edit(row.id,'quantity',Number(event.target.value))} /></td><td><input aria-label={`${index+1}번 단가`} type="number" min={0} step={1} value={row.price} onChange={event => edit(row.id,'price',Number(event.target.value))} /></td><td><input aria-label={`${index+1}번 품목 ID`} maxLength={64} value={row.item} placeholder="선택 입력" onChange={event => edit(row.id,'item',event.target.value)} /></td><td className="numeric">{num(row.quantity*row.price)}</td><td><button className="icon-button" aria-label={`${index+1}번 품목 삭제`} onClick={() => setRows(rows.filter(item => item.id !== row.id))}><Icon name="close" size={14} /></button></td></tr>)}</tbody></table></div><button className="add-line" disabled={rows.length >=100} onClick={() => {setRows([...rows,{id:nextId,name:'',quantity:1,price:0,item:''}]);setNextId(nextId+1);}}><Icon name="plus" size={14} />품목 추가</button><p className="panel-footnote">확정 품목 ID는 선택 입력입니다. 합계는 입력 확인용이며 실제 업무 금액은 서버가 검증합니다. 원본 파일 접수는 이후 Phase에서 제공합니다.</p></section></div>
    <footer className="action-bar"><div className="action-summary"><strong>₩ {num(total)}</strong><span>입력 금액 합계 · 데모</span></div><div className="action-buttons"><button className="button footer-secondary" disabled={!valid} onClick={() => setToast('임시저장 시연 · 서버에는 저장하지 않았습니다.')}>임시저장 시연</button><button className="button primary" disabled={!valid} onClick={() => setToast('제출 시연 · 사건이나 증빙은 생성되지 않았습니다.')}>{mode === 'new' ? '제출 시연' : '재제출 시연'}<Icon name="chevron" size={14} /></button></div></footer><Toast message={toast} dismiss={() => setToast('')} />
  </Shell>;
}
