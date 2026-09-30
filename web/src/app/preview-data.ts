// Fictional design fixtures, deliberately not presented as backend responses.
export type Scenario = 'quantity' | 'normal' | 'price' | 'mapping' | 'duplicate' | 'insufficient';
export type PreviewLine = {
  number: number; name: string; item: string; po: string;
  quantity: number; ordered: number; received: number; planned: number;
  price: number; poPrice: number; issue: string | null;
};
export const scenarioLabels: Record<Scenario, string> = {
  quantity: '수량 불일치', normal: '정상', price: '단가 불일치', mapping: '품목 확인 필요',
  duplicate: '청구번호 중복 의심', insufficient: '판단 근거 부족',
};
const base: PreviewLine[] = [
  { number: 1, name: 'A4 복사용지 · 80g', item: 'ITEM-001', po: 'POL-01', quantity: 100, ordered: 100, received: 100, planned: 100, price: 24500, poPrice: 24500, issue: null },
  { number: 2, name: '레이저 프린터 토너 · 검정', item: 'ITEM-002', po: 'POL-02', quantity: 12, ordered: 20, received: 12, planned: 12, price: 68000, poPrice: 68000, issue: null },
  { number: 3, name: '문서 보관 상자', item: 'ITEM-003', po: 'POL-03', quantity: 40, ordered: 40, received: 40, planned: 40, price: 4200, poPrice: 4200, issue: null },
  { number: 4, name: '클리어 파일 · 40매', item: 'ITEM-004', po: 'POL-04', quantity: 60, ordered: 80, received: 60, planned: 60, price: 3800, poPrice: 3800, issue: null },
  { number: 5, name: '유성 볼펜 · 0.7mm', item: 'ITEM-005', po: 'POL-05', quantity: 100, ordered: 100, received: 100, planned: 100, price: 1200, poPrice: 1200, issue: null },
  { number: 6, name: '인덱스 라벨 · 12색', item: 'ITEM-006', po: 'POL-06', quantity: 30, ordered: 30, received: 30, planned: 30, price: 2900, poPrice: 2900, issue: null },
  { number: 7, name: '데스크 정리함', item: 'ITEM-007', po: 'POL-07', quantity: 15, ordered: 15, received: 15, planned: 15, price: 12800, poPrice: 12800, issue: null },
  { number: 8, name: '스테이플러 심 · 33호', item: 'ITEM-008', po: 'POL-08', quantity: 50, ordered: 50, received: 50, planned: 50, price: 1800, poPrice: 1800, issue: null },
];
export function previewLines(scenario: Scenario): PreviewLine[] {
  return base.map((line) => {
    if (line.number !== 1) return { ...line };
    if (scenario === 'quantity') return { ...line, received: 60, planned: 60, issue: '검수 수량보다 40개 초과' };
    if (scenario === 'price') return { ...line, price: 26000, issue: '발주 단가보다 1,500원 높음' };
    if (scenario === 'mapping') return { ...line, item: '', po: '', planned: 0, issue: '품목 매핑 미확정' };
    return { ...line };
  });
}
export const numberFormat = new Intl.NumberFormat('ko-KR');
export const num = (value: number) => numberFormat.format(value);
