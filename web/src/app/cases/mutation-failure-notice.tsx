'use client';

import { failureDetailLines, type MutationFailure } from './composer-model';

// Shared presentation of a failed write. It shows the server code/message and
// the structured cause lines (stale reasons, allocation shortfalls, version
// mismatch) verbatim. It never auto-retries and never re-derives the cause.
export function MutationFailureNotice({
  failure,
  onRefresh,
  refreshing = false,
}: {
  failure: MutationFailure;
  onRefresh?: () => void;
  refreshing?: boolean;
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
    <div className="review-warning" role="alert">
      <div>
        <strong>{title}</strong>
        <p>{failure.message} <span className="muted-text">({failure.code})</span></p>
        {details.length > 0 && (
          <ul className="failure-details">
            {details.map((line) => <li key={line}>{line}</li>)}
          </ul>
        )}
        {failure.kind === 'uncertain'
          ? <p>서버 응답이 유실되었을 수 있어 자동으로 다시 보내지 않습니다. 같은 내용으로 다시 시도하면 동일한 요청 식별자로 한 번만 반영됩니다.</p>
          : <p>화면 이동은 서버에 반영된 내용을 취소하지 않습니다. 원인을 확인한 뒤 진행하세요.</p>}
      </div>
      {onRefresh && failure.kind === 'conflict' && (
        <button className="button" disabled={refreshing} onClick={onRefresh}>{refreshing ? '불러오는 중…' : '최신 자료 다시 조회'}</button>
      )}
    </div>
  );
}
