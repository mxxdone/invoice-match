// KST (Asia/Seoul) day boundaries for the work-list submitted-at filter.
//
// The screen shows Korean business dates, so a chosen day maps to the KST
// calendar day. Asia/Seoul has no DST and is a fixed +09:00 offset, so the
// offset is written literally instead of being recomputed.
//
// The backend treats `submittedFrom`/`submittedTo` as inclusive instants
// (`>= from` and `<= to`). PostgreSQL `timestamptz` stores microsecond
// precision, so the end of a day is sent as the last microsecond before the
// next KST day starts (`23:59:59.999999+09:00`). Sending only `.999` would drop
// stored values between `.999001` and `.999999`, and sending the next day
// `00:00:00` would wrongly include it.
export const KST_OFFSET = '+09:00';

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const DAY_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

export type SubmittedRange = {
  from: string | null;
  to: string | null;
  error: string | null;
};

function isRealCalendarDate(value: string): boolean {
  const match = DAY_PATTERN.exec(value);
  if (!match) return false;
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(Number(match[1]), month - 1, day));
  return date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

export function kstDayStart(date: string): string {
  return `${date}T00:00:00.000000${KST_OFFSET}`;
}

export function kstDayEndInclusive(date: string): string {
  return `${date}T23:59:59.999999${KST_OFFSET}`;
}

export function resolveSubmittedRange(start: string, end: string): SubmittedRange {
  const rawStart = start.trim();
  const rawEnd = end.trim();
  if (rawStart && (!DATE_PATTERN.test(rawStart) || !isRealCalendarDate(rawStart))) {
    return { from: null, to: null, error: '시작일 날짜 형식이 올바르지 않습니다.' };
  }
  if (rawEnd && (!DATE_PATTERN.test(rawEnd) || !isRealCalendarDate(rawEnd))) {
    return { from: null, to: null, error: '종료일 날짜 형식이 올바르지 않습니다.' };
  }
  if (rawStart && rawEnd && rawStart > rawEnd) {
    return { from: null, to: null, error: '시작일이 종료일보다 늦을 수 없습니다.' };
  }
  return {
    from: rawStart ? kstDayStart(rawStart) : null,
    to: rawEnd ? kstDayEndInclusive(rawEnd) : null,
    error: null,
  };
}
