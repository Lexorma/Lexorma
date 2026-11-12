import { t } from './i18n';
const pad = (n: number) => String(n).padStart(2, '0');

function toDate(value: unknown): Date | null {
  if (value === null || value === undefined || value === '') return null;
  const date = new Date(value as string);
  return isNaN(date.getTime()) ? null : date;
}

/** yyyy-MM-dd HH:mm */
export function formatDateTime(value: unknown): string {
  const date = toDate(value);
  if (!date) return '';
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** yyyy-MM-dd HH:mm:ss */
export function formatDateTimeSeconds(value: unknown): string {
  const date = toDate(value);
  if (!date) return '';
  return `${formatDateTime(date)}:${pad(date.getSeconds())}`;
}

/** "3분 전", "2일 전" (7일이 넘으면 원래 날짜) */
export function formatRelative(value: unknown): string {
  const date = toDate(value);
  if (!date) return '';
  const diffSec = Math.floor((Date.now() - date.getTime()) / 1000);
  if (diffSec < 60) return t('방금 전');
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return t('{0}분 전', diffMin);
  const diffHour = Math.floor(diffMin / 60);
  if (diffHour < 24) return t('{0}시간 전', diffHour);
  const diffDay = Math.floor(diffHour / 24);
  if (diffDay < 7) return t('{0}일 전', diffDay);
  return formatDateTime(date);
}

/** 빈 값 여부 (null / undefined / 공백 문자열) */
export function isBlank(value: unknown): boolean {
  return value === null || value === undefined || String(value).trim() === '';
}

/** 이니셜 아바타용 첫 글자 */
export function initial(value: unknown): string {
  const text = String(value ?? '').trim();
  return text ? text.charAt(0) : '?';
}

/** 숫자 입력칸에서 e, E, +, - 입력 막기 */
export function blockNonNumericKeys(event: { key: string; preventDefault: () => void }) {
  if (['e', 'E', '+', '-'].includes(event.key)) event.preventDefault();
}
