// Fictional work-list data. Supplier names are display-only fixtures, not API fields.
export type CaseStatus = 'DRAFT' | 'REVIEW_PENDING' | 'SUPPLEMENT_REQUIRED' | 'EXPORT_PENDING' | 'EXPORTED' | 'REJECTED';
export const statusPresentation: Record<CaseStatus, { label: string; tone: string }> = {
  DRAFT: { label: '작성 중', tone: 'neutral' }, REVIEW_PENDING: { label: '검토 대기', tone: 'pending' },
  SUPPLEMENT_REQUIRED: { label: '보완 대기', tone: 'attention' }, EXPORT_PENDING: { label: '인계 대기', tone: 'pending' },
  EXPORTED: { label: '인계 완료', tone: 'complete' }, REJECTED: { label: '청구 거절', tone: 'rejected' },
};
export const suppliers = [
  { id:'SUP-1001', name:'한빛상사', tone:0 }, { id:'SUP-1002', name:'빅컴퍼니', tone:1 },
  { id:'SUP-1003', name:'다온문구', tone:2 }, { id:'SUP-1004', name:'새봄오피스', tone:0 },
  { id:'SUP-1005', name:'늘푸른유통', tone:1 },
] as const;
export type PreviewCase = {id:string; invoiceNumber:string; supplierId:string; submittedBy:string; status:CaseStatus; submittedAt:string|null; purchaseOrderId:string};
const firstCases: PreviewCase[] = [
  {id:'0142',invoiceNumber:'INV-2026-0142',supplierId:'SUP-1001',submittedBy:'submitter',status:'REVIEW_PENDING',submittedAt:'2026.09.30 10:38',purchaseOrderId:'PO-2026-0142'},
  {id:'0141',invoiceNumber:'INV-2026-0141',supplierId:'SUP-1003',submittedBy:'submitter-02',status:'SUPPLEMENT_REQUIRED',submittedAt:'2026.09.30 09:12',purchaseOrderId:'PO-2026-0139'},
  {id:'0140',invoiceNumber:'INV-2026-0140',supplierId:'SUP-1002',submittedBy:'submitter',status:'EXPORT_PENDING',submittedAt:'2026.09.29 16:40',purchaseOrderId:'PO-2026-0138'},
  {id:'0139',invoiceNumber:'INV-2026-0139',supplierId:'SUP-1001',submittedBy:'submitter-03',status:'REVIEW_PENDING',submittedAt:'2026.09.29 15:05',purchaseOrderId:'PO-2026-0136'},
  {id:'0138',invoiceNumber:'INV-2026-0138',supplierId:'SUP-1004',submittedBy:'submitter-02',status:'EXPORTED',submittedAt:'2026.09.29 13:24',purchaseOrderId:'PO-2026-0135'},
  {id:'0137',invoiceNumber:'INV-2026-0137',supplierId:'SUP-1002',submittedBy:'submitter',status:'REJECTED',submittedAt:'2026.09.28 11:42',purchaseOrderId:'PO-2026-0131'},
  {id:'0136',invoiceNumber:'INV-2026-0136',supplierId:'SUP-1005',submittedBy:'submitter-03',status:'DRAFT',submittedAt:null,purchaseOrderId:'PO-2026-0128'},
  {id:'0135',invoiceNumber:'INV-2026-0135',supplierId:'SUP-1003',submittedBy:'submitter-02',status:'EXPORTED',submittedAt:'2026.09.28 10:17',purchaseOrderId:'PO-2026-0125'},
];
const statuses = Object.keys(statusPresentation) as CaseStatus[];
export const previewCases: PreviewCase[] = [...firstCases,...Array.from({length:40},(_,index)=>{
  const id=String(134-index).padStart(4,'0'); const status=statuses[index%statuses.length];
  return {id,invoiceNumber:`INV-2026-${id}`,supplierId:suppliers[index%suppliers.length].id,
    submittedBy:['submitter','submitter-02','submitter-03'][index%3],status,
    submittedAt:status==='DRAFT'?null:`2026.09.${String(27-Math.floor(index/5)).padStart(2,'0')} 10:15`,
    purchaseOrderId:`PO-2026-${String(124-index).padStart(4,'0')}`};
})];
export function pageNumbers(current:number,total:number):Array<number|string>{
  const numbers=Array.from(new Set([1,total,current-1,current,current+1].filter(n=>n>=1&&n<=total))).sort((a,b)=>a-b);
  const result:Array<number|string>=[];
  numbers.forEach((n,i)=>{if(i>0&&n-numbers[i-1]>1)result.push(`gap-${n}`);result.push(n);});
  return result;
}
