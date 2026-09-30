'use client';

import Link from 'next/link';
import { useState } from 'react';
import { DemoBar, Icon, PageHeader, Shell } from '../ui';

const examples = {
  unknown: {label:'결과불명',payment:'RESULT_UNKNOWN',outbox:'RESULT_UNKNOWN',state:'EXPORT_PENDING',error:'REQUEST_TIMEOUT',description:'전송 응답이 유실되어 ERP의 처리 여부를 확인할 수 없습니다. 중복 지급을 방지하기 위해 자동으로 다시 보내지 않습니다.',tone:'warning'},
  success: {label:'인계 완료',payment:'ACKNOWLEDGED',outbox:'DELIVERED',state:'EXPORTED',error:'—',description:'ERP의 처리 결과가 확인됐습니다. 인계 완료는 실제 송금 완료를 의미하지 않습니다.',tone:'success'},
  failed: {label:'인계 실패',payment:'FAILED',outbox:'FAILED',state:'EXPORT_PENDING',error:'HTTP_400',description:'ERP가 요청을 거부했습니다. 원인 확인이 필요하며 이 시안에는 재전송 기능이 없습니다.',tone:'warning'},
};

export default function Handoff() {
  const [example,setExample] = useState<keyof typeof examples>('unknown');
  const [tab,setTab] = useState('status');
  const data = examples[example];
  return <Shell active="handoff" actor="operator" role="운영자"><PageHeader eyebrow="ERP 인계 / 지급요청" title="ERP 인계 상세" subtitle="INV-2026-0140 · SUP-1002" action={<Link className="button" href="/operations"><Icon name="arrow" size={14} />운영 목록</Link>}><dl className="case-meta"><div><dt>사건 상태</dt><dd>{data.state}</dd></div><div><dt>내보내기 버전</dt><dd>v1</dd></div><div><dt>전송 시도</dt><dd>1회</dd></div></dl></PageHeader>
    <div className="tabs" role="tablist" aria-label="인계 정보"><button role="tab" aria-selected={tab==='status'} className={tab==='status'?'active':''} onClick={() => setTab('status')}>인계 상태</button><button role="tab" aria-selected={tab==='identity'} className={tab==='identity'?'active':''} onClick={() => setTab('identity')}>지급요청 식별정보</button></div><DemoBar><label className="scenario-picker">예시<select aria-label="인계 시나리오" value={example} onChange={event => setExample(event.target.value as keyof typeof examples)}>{Object.entries(examples).map(([id,item]) => <option key={id} value={id}>{item.label}</option>)}</select></label></DemoBar>
    <div className="handoff-content">{tab==='status' ? <><div className={`inline-notice ${data.tone}`}><strong>{data.label}</strong><span>{data.description}</span></div><section className="form-section"><div className="section-heading"><h2>지급요청과 전송 상태</h2><span>가상 상태 예시</span></div><div className="handoff-grid"><dl className="definition-list"><div><dt>지급요청 상태</dt><dd><span className={`line-badge ${example==='success'?'':'exception'}`}>{data.payment}</span></dd></div><div><dt>Outbox 상태</dt><dd>{data.outbox}</dd></div><div><dt>최종 오류 코드</dt><dd>{data.error}</dd></div><div><dt>다음 시도</dt><dd>예약 없음</dd></div><div><dt>인계 완료 시각</dt><dd>{example==='success'?'2026.09.30 11:06':'—'}</dd></div></dl><div className="payment-amount"><small>승인된 지급요청 금액</small><strong>₩ 3,268,000</strong><span>KRW · 승인 대상에 고정된 금액</span><p>실제 지급은 외부 ERP의 업무 범위입니다.<br />이 서비스는 지급요청을 안전하게 인계합니다.</p></div></div></section><section className="form-section"><div className="section-heading"><h2>상태 해석</h2></div><div className="state-explanation"><Icon name="send" /><div><strong>{example==='success'?'수신 확인':'전송 결과 확인 필요'}</strong><p>{example==='success'?'지급요청 ACKNOWLEDGED · Outbox DELIVERED · 사건 EXPORTED가 함께 표시됩니다.':'지급요청과 Outbox의 상태를 함께 확인해야 합니다. 결과불명과 실패를 같은 상태로 취급하지 않습니다.'}</p></div></div><p className="panel-footnote">실제 지급요청이나 전송 이력은 조회하지 않은 디자인 예시입니다. 자동 조회·재조정·재전송 버튼을 구현한 것으로 표시하지 않습니다.</p></section></> : <section className="form-section"><div className="section-heading"><h2>고정된 요청 식별정보</h2></div><dl className="definition-list wide-definition"><div><dt>지급요청 ID</dt><dd className="code-text">10000000-0000-4000-8000-000000000140</dd></div><div><dt>외부 요청 키</dt><dd className="code-text">PAYMENT:case-0140:snapshot-0140</dd></div><div><dt>내보내기 버전</dt><dd>1</dd></div><div><dt>승인 검토 대상</dt><dd>검토 #1 · 증빙 v1</dd></div></dl><p className="panel-footnote">배치 확인용 식별자입니다. 실제 UUID·외부 요청 키는 API 연결 시 서버 값을 그대로 사용합니다.</p></section>}</div>
  </Shell>;
}
