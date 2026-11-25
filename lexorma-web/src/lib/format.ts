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

/**
 * 충돌 검토용 수정 일시 (M8-C4c-2 12장) : 서버 기준값 문자열(LocalDateTime, 예 2026-11-24T15:30:42.123456)을 Date 로 바꾸지 않고 그대로 읽어
 * `2026-11-24 15:30:42.123456` 처럼 초와 서버가 준 소수 자리까지만 보인다 (가짜 정밀도·반올림·시간대 변환 없음 — 서버 값에 시간대가 없다).
 * 표시용 문자열일 뿐이고, 기준값으로는 서버 원문을 그대로 보낸다
 */
export function formatTimestampPrecise(value: unknown): string {
  if (value === null || value === undefined || value === '') return '';
  const match = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(\.\d+)?$/.exec(String(value));
  return match ? `${match[1]} ${match[2]}${match[3] ?? ''}` : formatDateTimeSeconds(value);
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
