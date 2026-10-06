'use client';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { failureDetailLines, type MutationFailure } from './composer-model';

// Shared presentation of a failed write. It shows the server code/message and
// the structured cause lines (stale reasons, allocation shortfalls, version
// mismatch) verbatim. It never auto-retries and never re-derives the cause.
export function MutationFailureNotice({
  failure,
  onRefresh,
  refreshing = false,
  className,
}: {
  failure: MutationFailure;
  onRefresh?: () => void;
  refreshing?: boolean;
  className?: string;
}) {
  const details = failureDetailLines(failure);
  const title = failure.kind === 'conflict'
    ? '최신 자료와 충돌했습니다'
    : failure.kind === 'uncertain'
      ? '결과를 확인할 수 없습니다'
      : failure.kind === 'forbidden'
        ? '이 작업을 수행할 권한이 없습니다'
        : '요청을 완료하지 못했습니다';
  return (
    <div className={cn("review-warning mx-[30px] mb-3 flex flex-wrap items-center justify-between gap-3 border border-[#e8ddae] bg-[#faf4df] px-4 py-3 text-sm", className)} role="alert">
      <div className="min-w-0 flex-1 [overflow-wrap:anywhere]">
        <strong>{title}</strong>
        <p className="mt-1 text-[#716446]">{failure.message} <span className="muted-text text-label text-muted-foreground">({failure.code})</span></p>
        {details.length > 0 && (
          <ul className="failure-details">
            {details.map((line) => <li key={line}>{line}</li>)}
          </ul>
        )}
        {failure.kind === 'uncertain'
          ? <p className="mt-1 text-[#716446]">서버 응답이 유실되었을 수 있어 자동으로 다시 보내지 않습니다. 같은 내용으로 다시 시도하면 동일한 요청 식별자로 한 번만 반영됩니다.</p>
          : <p className="mt-1 text-[#716446]">화면 이동은 서버에 반영된 내용을 취소하지 않습니다. 원인을 확인한 뒤 진행하세요.</p>}
      </div>
      {onRefresh && failure.kind === 'conflict' && (
        <Button variant="outline" disabled={refreshing} onClick={onRefresh}>{refreshing ? '불러오는 중…' : '최신 자료 다시 조회'}</Button>
      )}
    </div>
  );
}
