'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { num, previewLines, scenarioLabels, type Scenario } from './preview-data';
import { Icon, Sidebar, StatePreview } from './ui';

const history = [
  ['10:45', 'approver', '검토 기준 저장', '증빙 v1 · 비교 결과 #1 · 검토 #1'],
  ['10:42', 'operator', '발주·검수·청구 비교', '수량 불일치 1건 확인'],
  ['10:38', 'submitter', '청구 제출', '증빙 v1 생성'],
];

export default function Home() {
  const [scenario, setScenario] = useState<Scenario>('quantity');
  const [tab, setTab] = useState('compare');
  const [selected, setSelected] = useState<number | null>(null);
  const [query, setQuery] = useState('');
  const [onlyIssues, setOnlyIssues] = useState(false);
  const [modal, setModal] = useState<'supplement' | 'reject' | 'approve' | 'snapshot' | null>(null);
  const [reason, setReason] = useState('');
  const [toast, setToast] = useState('');
  const [reviewState, setReviewState] = useState('current');
  const [mapped, setMapped] = useState(false);
  const [mappingItem, setMappingItem] = useState('');
  const [moreAudit, setMoreAudit] = useState(false);
  const [historyVisible, setHistoryVisible] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  const lines = previewLines(scenario === 'mapping' && mapped ? 'normal' : scenario);
  const shown = lines.filter((line) => (!onlyIssues || line.issue) && `${line.name} ${line.item}`.toLowerCase().includes(query.toLowerCase()));
  const line = lines.find((item) => item.number === selected);
  const caseException = scenario === 'duplicate' ? '동일 공급사·청구번호의 다른 사건이 있습니다. 중복 청구 여부를 확인하세요.' : scenario === 'insufficient' ? '판단에 필요한 구매 근거가 부족합니다. 보완 요청으로 추가 근거를 확인하세요.' : '';
  const issueCount = lines.filter((item) => item.issue).length + (caseException ? 1 : 0);
  const total = lines.reduce((sum, item) => sum + item.quantity * item.price, 0);
  const writeBlocked = reviewState === 'stale' || reviewState === 'operator';
  const approvalBlocked = issueCount > 0 || writeBlocked || reviewState === 'self';

  useEffect(() => {
    if (modal && !dialog.current?.open) dialog.current?.showModal();
    if (!modal && dialog.current?.open) dialog.current?.close();
  }, [modal]);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 5000);
    return () => clearTimeout(timer);
  }, [toast]);

  function changeScenario(value: Scenario) {
    setScenario(value); setSelected(null); setOnlyIssues(false); setQuery(''); setToast(''); setMapped(false); setMappingItem('');
  }
  function openAction(action: typeof modal) { setReason(''); setModal(action); }

  return (
    <div className="app-shell">
      <Sidebar active="detail" />

      <main className="workspace">
        <StatePreview>
        <header className="page-header">
          <nav className="breadcrumb" aria-label="현재 위치"><Link href="/cases">청구서</Link><Icon name="chevron" size={12} /><span aria-current="page">상세</span></nav>
          <div className="title-row"><div><h1>청구서 상세</h1><p className="invoice-id">INV-2026-0142 <span className="status-pill"><span className="status-dot" />검토 대기</span></p></div>
            <button className="button" onClick={() => openAction('snapshot')}><Icon name="document" />검토 대상 확인</button>
          </div>
          <dl className="case-meta"><div><dt>공급사</dt><dd>SUP-1001</dd></div><div><dt>발주번호</dt><dd>PO-2026-0142</dd></div><div><dt>제출자</dt><dd>submitter</dd></div><div><dt>증빙 버전</dt><dd>v1</dd></div><div><dt>처리 상태</dt><dd>검토 대기</dd></div></dl>
        </header>

        <div className="tabs" role="tablist" aria-label="청구서 상세">
          {[['compare', '발주·검수·청구 비교'], ['decisions', '검토 결정'], ['audit', '감사 이력']].map(([id, label]) => <button key={id} role="tab" id={`tab-${id}`} aria-controls={`panel-${id}`} aria-selected={tab === id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>{label}{id === 'compare' && issueCount > 0 && <span className="tab-count">{issueCount}</span>}</button>)}
        </div>

        <div className="toolbar"><span className="demo-tag">디자인 시안</span><span className="demo-description">가상 데이터 · 실제 API 미연결</span><label className="scenario-picker">예시<select aria-label="데모 시나리오" value={scenario} onChange={(event) => changeScenario(event.target.value as Scenario)}>{Object.entries(scenarioLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label></div>
        <div className="state-preview-control"><label>검토 상태 시안<select aria-label="검토 상태 시안" value={reviewState} onChange={event => setReviewState(event.target.value)}><option value="current">최신 · 승인자</option><option value="stale">409 · 오래된 검토 대상</option><option value="self">자기 승인 금지 · 제출자와 동일</option><option value="operator">운영자 · 읽기 전용</option></select></label></div>
        {reviewState !== 'current' && <div className="review-warning" role="status"><div><strong>{reviewState === 'stale' ? '검수 자료가 변경되었습니다' : reviewState === 'self' ? '본인이 제출한 청구는 승인할 수 없습니다' : '운영자는 검토 결정을 기록할 수 없습니다'}</strong><p>{reviewState === 'stale' ? '검토 #1 이후 검수 자료가 바뀌었습니다. 이전 자료로는 결정할 수 없으므로 최신 검토 자료를 확인하세요. · 가상 예시' : '제한 상태 디자인 예시입니다. 실제 권한은 서버에서 검증합니다.'}</p></div>{reviewState === 'stale' && <button className="button" onClick={() => {setReviewState('current');setToast('최신 검토 대상 표시 시연 · 실제 조회 없음');}}>최신 대상 확인 시연</button>}</div>}

        <section className="tab-content" role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
          {caseException && <div className="review-warning" role="status"><div><strong>{scenario === 'duplicate' ? '청구번호 중복 의심' : '판단 근거 부족'}</strong><p>{caseException}</p><p>청구서 전체 확인 필요 항목 · 표의 개별 라인 일치 여부와 별개로 승인 불가 · 가상 예시</p></div></div>}
          {tab === 'compare' ? <>
            <div className="table-tools"><label className="search"><Icon name="search" /><input aria-label="품목 검색" placeholder="품목명 또는 품목 ID 검색" value={query} onChange={(event) => setQuery(event.target.value)} /></label><button className={`filter-button ${onlyIssues ? 'on' : ''}`} aria-pressed={onlyIssues} onClick={() => setOnlyIssues(!onlyIssues)}><Icon name="sliders" />확인 필요만 보기{issueCount > 0 && <span>{issueCount}</span>}</button></div>
            <div className={`review-workbench ${line ? 'with-panel' : ''}`}>
              <div className="table-area"><div className="table-scroll"><table className="comparison-table"><caption className="sr-only">청구·발주·검수 비교. 근거 보기 버튼으로 라인별 상세를 확인할 수 있습니다.</caption><thead><tr className="group-head"><th colSpan={2}>청구 품목</th><th colSpan={4} className="group-separator">수량 비교</th><th colSpan={2} className="group-separator">단가 비교 · 원</th><th colSpan={2} className="group-separator">검토 결과</th></tr><tr><th className="line-number">#</th><th className="item-column">품목 / 발주 라인</th><th className="numeric group-separator">청구</th><th className="numeric">발주</th><th className="numeric">검수*</th><th className="numeric">예상 배분</th><th className="numeric group-separator">청구</th><th className="numeric">발주</th><th className="result-column group-separator">판정 / 확인 사유</th><th className="detail-column"><span className="sr-only">근거</span></th></tr></thead><tbody>
                {shown.map((item) => <tr key={item.number} className={selected === item.number ? 'selected' : ''}>
                  <td className="line-number">{String(item.number).padStart(2, '0')}</td><td><strong className="item-name">{item.name}</strong><span className="item-secondary">{item.item || '매핑 미확정'}<span>·</span>{item.po || '발주 라인 미확정'}</span></td>
                  <td className={`numeric group-separator ${item.issue && scenario === 'quantity' ? 'mismatch-cell' : ''}`}>{num(item.quantity)}</td><td className="numeric">{num(item.ordered)}</td><td className="numeric">{item.po ? num(item.received) : '—'}</td><td className="numeric">{item.po ? num(item.planned) : '—'}</td>
                  <td className={`numeric group-separator ${item.issue && scenario === 'price' ? 'mismatch-cell' : ''}`}>{num(item.price)}</td><td className="numeric">{item.po ? num(item.poPrice) : '—'}</td>
                  <td className="group-separator"><span className={`line-badge ${item.issue ? 'exception' : ''}`}>{item.issue ? <><span className="exception-dot" />확인 필요</> : <><Icon name="check" size={12} />일치</>}</span>{item.issue && <span className="issue-description">{item.issue}</span>}</td>
                  <td><button className="row-detail" aria-label={`${item.number}번 품목 근거 보기`} aria-expanded={selected === item.number} onClick={() => setSelected(selected === item.number ? null : item.number)}><Icon name="chevron" size={14} /></button></td>
                </tr>)}
                {shown.length === 0 && <tr><td colSpan={10} className="empty-table">조건에 맞는 품목이 없습니다.</td></tr>}
              </tbody></table></div><div className="table-summary"><span>{shown.length} / {lines.length}개 품목</span><span>* 비교 시점의 검수 잔량 · 예상 배분은 잔여량을 소비하지 않습니다.</span></div></div>
              {line && <aside className="evidence-panel" aria-label="선택한 품목의 근거"><div className="panel-title"><span>라인 {String(line.number).padStart(2, '0')} · 검토 근거</span><button className="icon-button" aria-label="근거 패널 닫기" onClick={() => setSelected(null)}><Icon name="close" /></button></div><h2>{line.name}</h2><p className="panel-subtitle">{line.item || '품목 확인 필요'}</p>
                <div className={`panel-status ${line.issue ? 'warning' : ''}`}><strong>{line.issue || '청구와 발주·검수 정보가 일치합니다.'}</strong><p>{line.issue && scenario === 'quantity' ? '청구 100개에 비해 검수 잔량은 60개입니다. 부족한 40개의 근거를 확인해야 하며, 자동 거절이나 60개 부분 승인은 하지 않습니다.' : line.issue && scenario === 'price' ? '청구 단가와 발주 단가가 다릅니다. 단가 변경 근거를 확인하세요.' : line.issue ? '품목을 확인하기 전에는 승인할 수 없습니다.' : '이 표시는 가상 시나리오의 예시 판정입니다.'}</p></div>
                <h3>검수 및 예상 배분</h3>{line.po ? <><div className="receipt-heading"><Icon name="document" /><div><strong>RCV-2026-0091</strong><span>검수일 2026.09.25 · v1</span></div></div><dl className="receipt-data"><div><dt>검수 가용 수량</dt><dd>{num(line.received)}개</dd></div><div><dt>예상 배분</dt><dd>{num(line.planned)}개</dd></div><div><dt>발주 라인</dt><dd>{line.po}</dd></div></dl><p className="panel-footnote">가상 검수 자료입니다. 실제 승인 시 서버가 잔여량과 최신 검토 대상을 재검증합니다.</p></> : <div className="mapping-preview"><p>현재 발주 품목에서 선택</p><label>확정할 품목<select aria-label="매핑 후보 선택" value={mappingItem} onChange={event => setMappingItem(event.target.value)}><option value="">품목 선택</option><option value="ITEM-001">A4 복사용지 · ITEM-001 · POL-01</option></select></label><button className="button" disabled={!mappingItem || writeBlocked} onClick={() => {setMapped(true);setHistoryVisible(true);setToast('매핑 후 대사·후속 검토 #2 표시 시연 · 실제 결정 없음');}}>매핑 확정 시연</button><small>AI 추천이나 자동 매핑 기능은 포함하지 않습니다.</small></div>}
              </aside>}
            </div>
            <div className="review-note"><span className={issueCount ? 'exception-dot' : 'status-dot'} /><span>{issueCount ? `확인 필요 항목 ${issueCount}건이 있습니다. 해당 라인의 근거를 확인하거나 보완을 요청하세요.` : '모든 품목이 일치하는 정상 시나리오입니다.'}</span></div>
          </> : tab === 'audit' ? <div className="history-content"><div className="section-heading"><h2>감사 이력</h2><span>가상 이벤트 {moreAudit ? 4 : 3}건</span></div><table className="history-table audit-table"><thead><tr><th>시간</th><th>작업자</th><th>작업</th><th>변경 내용</th></tr></thead><tbody>{[...history, ...(moreAudit ? [['10:35', 'submitter', '초안 저장', '작성 중 · 청구서 변경 버전 1']] : [])].map(([time, actor, action, detail]) => <tr key={action}><td>2026.09.30 {time}</td><td>{actor}</td><td>{action}</td><td><details><summary>{detail}</summary><p>청구서 변경 버전 2 → 3 · 요청 demo-request · 추적 demo-trace</p><p>변경 전후 상세 배치용 가상 자료입니다.</p></details></td></tr>)}</tbody></table><div className="audit-pagination">{moreAudit ? <p role="status">마지막 기록입니다.</p> : <button className="button" onClick={() => setMoreAudit(true)}>이전 기록 더 보기</button>}</div><p className="panel-footnote">디자인 확인용 이력입니다. 실제 감사 API에는 연결되지 않았습니다.</p></div> : <div className="history-content"><div className="section-heading"><h2>검토 결정</h2><button className="button" onClick={() => setHistoryVisible(!historyVisible)}>{historyVisible ? '빈 이력 보기' : '기록된 이력 예시'}</button></div>{historyVisible ? <table className="history-table"><thead><tr><th>결정</th><th>작업자</th><th>검토 대상</th><th>내용 / 사유</th></tr></thead><tbody><tr><td>품목 매핑</td><td>approver</td><td>검토 #1 → #2</td><td>라인 1 · ITEM-001 / POL-01 확정</td></tr><tr><td>보완 요청</td><td>approver</td><td>과거 증빙 예시</td><td>부족한 40개의 검수 근거 요청</td></tr></tbody></table> : <div className="empty-state"><Icon name="clock" size={25} /><h3>아직 기록된 결정이 없습니다.</h3><p>품목 매핑·보완요청·청구 거절·승인 결정이 여기에 표시됩니다.</p></div>}</div>}
        </section>

        <footer className="action-bar"><div className="action-summary"><strong>₩ {num(total)}</strong><span>총 청구 금액 · {lines.length}개 품목</span></div><div className="action-buttons"><span className="footer-context">검토 #{mapped ? '2' : '1'} · 증빙 v1</span><button className="button footer-secondary" disabled={writeBlocked} onClick={() => openAction('reject')}>청구 거절</button><button className="button footer-secondary" disabled={writeBlocked} onClick={() => openAction('supplement')}>보완 요청</button><button className="button primary" disabled={approvalBlocked} title={approvalBlocked ? '확인 필요 항목이 남아 있어 승인할 수 없습니다.' : '승인 확인창 시연'} onClick={() => openAction('approve')}>승인 검토<Icon name="chevron" size={14} /></button></div></footer>
        </StatePreview>
      </main>

      <dialog ref={dialog} className="action-dialog" onCancel={() => setModal(null)} onClose={() => setModal(null)}><div className="dialog-heading"><h2>{modal === 'snapshot' ? '검토 대상' : modal === 'supplement' ? '보완 요청' : modal === 'reject' ? '청구 거절' : '승인 확인'}</h2><button className="icon-button" aria-label="대화상자 닫기" onClick={() => setModal(null)}><Icon name="close" /></button></div>
        {modal === 'snapshot' ? <><p className="dialog-description">현재 화면의 가상 검토 대상입니다.</p><dl className="snapshot-data"><div><dt>청구서 변경 버전</dt><dd>3</dd></div><div><dt>증빙 버전</dt><dd>v1</dd></div><div><dt>비교 결과 / 검토 번호</dt><dd>#{mapped ? 2 : 1} / #{mapped ? 2 : 1}</dd></div><div><dt>현재 자료와 일치 여부</dt><dd>{reviewState === 'stale' ? '검수 자료 변경 · 결정 불가' : '일치함 · 가상 예시'}</dd></div></dl><details className="snapshot-technical"><summary>기술 정보 보기</summary><p>검토 기준 식별값(해시): demo-review-{mapped ? '02' : '01'}</p><p>검토 당시 자료 전체의 지문입니다. 사용자가 입력할 값이 아닙니다.</p></details><p className="panel-footnote">변경 버전과 식별값은 가상 예시이며 실제 승인에 사용할 수 없습니다.</p><div className="dialog-actions"><button className="button" onClick={() => setModal(null)}>닫기</button></div></> : <><p className="dialog-description">{modal === 'approve' ? `INV-2026-0142 · 총 ${num(total)}원 · 검토 #${mapped ? 2 : 1} · 증빙 v1. 표시된 대상의 승인 확인 시연입니다.` : modal === 'reject' ? '이 청구의 처리를 종료합니다. 수정 후 다시 제출받으려면 보완 요청을 선택하세요.' : '제출자에게 전달할 보완 요청 사유를 입력하세요.'}</p>{modal !== 'approve' && <label className="reason-label">{modal === 'supplement' ? '보완 요청 사유' : '청구 거절 사유'}<textarea autoFocus rows={4} value={reason} onChange={(event) => setReason(event.target.value)} placeholder={modal === 'reject' ? '예: 다른 회사의 거래에 대한 청구로 확인되어 처리를 종료합니다.' : '예: 40개에 대한 검수 근거를 추가해 주세요.'} /></label>}<p className="dialog-disclaimer">데모 전용 · 실제 결정이나 지급요청은 생성되지 않습니다.</p><div className="dialog-actions"><button className="button" onClick={() => setModal(null)}>취소</button><button className="button primary" disabled={modal !== 'approve' && !reason.trim()} onClick={() => { setModal(null); setToast('시연 완료 · 실제 업무 데이터는 변경되지 않았습니다.'); }}>확인 시연</button></div></>}
      </dialog>
      {toast && <div className="toast" role="status"><Icon name="check" /><span>{toast}</span><button className="icon-button" aria-label="알림 닫기" onClick={() => setToast('')}><Icon name="close" size={14} /></button></div>}
    </div>
  );
}
