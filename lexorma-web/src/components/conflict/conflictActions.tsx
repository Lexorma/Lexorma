import type { ReactNode } from 'react';
import type { DialogApi } from '../dialog/DialogProvider';
import { isConflict } from '../../lib/api';
import { formatTimestampPrecise } from '../../lib/format';
import { t } from '../../lib/i18n';
import { modifiedAtRow, openConflictReview, type CompareRow } from './ConflictRecovery';

/**
 * 삭제·승인·매핑 해제의 409 복구 (M8-C3 6~8장). 처음 요청은 화면이 본 기준값으로 보내고, 409 면 검토 대화상자가 최신 내용을 다시 읽어
 * "확인했던 값 ↔ 현재 서버 값" 을 보여 준 뒤 사용자가 [다시 …] 를 눌러야 최신 기준값으로 한 번 더 보낸다 (자동 재실행 없음 — D01).
 * 하위 구성 기준값(composition)은 서버가 준 값만 쓴다 (화면에서 만들지 않는다)
 */

/** 대상의 한 시점 상태 : 기준값, 승인 여부, 비교할 항목, (삭제) 함께 지워질 구성 */
export interface Snapshot {
  modifiedAt: string | null;
  approved?: boolean | null;
  /** removable : 하위 항목 한 줄 (속성·매핑 등) — 최신에 없으면 '삭제됨' 으로 보인다 */
  fields?: { key: string; label: string; value: ReactNode; removable?: boolean }[];
  /** 삭제 확인 조회의 구성 기준값과 종류별 수 (하위 객체를 함께 지우는 삭제) */
  composition?: string;
  cascade?: { kind: string; count: number }[];
}

const KIND_LABELS: Record<string, () => string> = {
  attributes: () => t('속성'),
  identifiers: () => t('식별자'),
  subjectAreas: () => t('주제 영역'),
  indexes: () => t('인덱스'),
  constraints: () => t('제약조건'),
  relations: () => t('관계'),
  conversions: () => t('변환 기록'),
};

const approvalText = (value: boolean | null | undefined) => (value == null ? null : value ? t('승인') : t('승인 대기'));
const text = (value: ReactNode) => (value === null || value === undefined ? '' : typeof value === 'string' || typeof value === 'number' ? String(value) : JSON.stringify(value));

/** 확인했던 상태 ↔ 지금 상태 비교 행 */
export function snapshotRows(seen: Snapshot, latest: Snapshot): CompareRow[] {
  const rows: CompareRow[] = [];
  const latestFields = new Map((latest.fields ?? []).map((f) => [f.key, f]));
  const seenKeys = new Set((seen.fields ?? []).map((f) => f.key));
  (seen.fields ?? []).forEach((f) => {
    const now = latestFields.get(f.key);
    // 최신에 없는 항목 = 그 사이 없어진 하위 항목 (M8-C4c-2 — 하위 구성 비교)
    rows.push({ key: f.key, label: f.label, before: f.value, latest: now ? now.value : (f.removable ? t('삭제됨') : null), changedOnServer: text(f.value) !== text(now?.value) });
  });
  // 최신에만 있는 항목 = 그 사이 더해진 하위 항목 (예 : 확인한 뒤 추가된 속성·TABLE 매핑)
  (latest.fields ?? []).filter((f) => !seenKeys.has(f.key)).forEach((f) => {
    rows.push({ key: f.key, label: f.label, before: t('없음 (새로 추가됨)'), latest: f.value, changedOnServer: true });
  });
  if (seen.approved !== undefined || latest.approved !== undefined) {
    rows.push({ key: '__approval', label: t('승인 상태'), before: approvalText(seen.approved), latest: approvalText(latest.approved), changedOnServer: !!seen.approved !== !!latest.approved });
  }
  if (seen.cascade || latest.cascade) {
    const kinds = new Set([...(seen.cascade ?? []), ...(latest.cascade ?? [])].map((c) => c.kind));
    kinds.forEach((kind) => {
      const before = seen.cascade?.find((c) => c.kind === kind)?.count ?? 0;
      const now = latest.cascade?.find((c) => c.kind === kind)?.count ?? 0;
      rows.push({ key: `__cascade_${kind}`, label: t('함께 삭제 : {0}', KIND_LABELS[kind]?.() ?? kind), before: t('{0}개', before), latest: t('{0}개', now),
        changedOnServer: before !== now });
    });
  }
  rows.push(modifiedAtRow(seen.modifiedAt, latest.modifiedAt));
  return rows;
}

export interface SingleAction {
  dialog: DialogApi;
  /** 검토 대화상자 제목 (예 : 엔터티 삭제) */
  title: string;
  /** 409 안내 (예 : t('다른 사용자가 이 항목을 변경했습니다.')) */
  intro: string;
  /** 화면이 확인한 상태 (처음 요청의 기준값) */
  seen: Snapshot;
  /** 최신 상태 (없으면 예외 — 대상 없음·권한은 검토 대화상자가 409 와 구분해 보여 준다) */
  load: () => Promise<Snapshot>;
  /** 요청 : 주어진 상태의 기준값으로 보낸다. api(…, { conflict: 'handled' }) 로 부른다 */
  act: (state: Snapshot) => Promise<void>;
  retryLabel: string;
  /** 승인·승인 취소 : 목표 상태. 최신이 이미 그 상태면 다시 보내지 않고 알린다 */
  targetApproval?: boolean;
  /** 최신 상태로는 다시 실행할 수 없는 사유 (예 : 매핑이 다시 만들어졌다, 승인되어 삭제할 수 없다). 없으면 다시 실행 가능 (M8-C4c-2) */
  block?: (latest: Snapshot) => string | undefined;
  /** 검토 안내를 바꾼다 (기본 : 하위 구성·승인 안내) */
  note?: (latest: Snapshot) => string | undefined;
  onDone?: () => void;
  /** 검토 대화상자를 닫을 때 (성공·취소 모두 — 목록 다시 읽기) */
  onClose?: () => void;
}

/** 처음 요청 → 409 면 검토 대화상자. 처음 요청이 성공하면 true, 409 면 false(검토 중), 그 밖의 오류는 그대로 던진다 (공통 알림) */
export async function runWithReview(options: SingleAction): Promise<boolean> {
  try {
    await options.act(options.seen);
    return true;
  } catch (error) {
    if (!isConflict(error)) throw error;
  }
  openConflictReview(options.dialog, {
    title: options.title,
    intro: options.intro,
    retryLabel: options.retryLabel,
    onClose: options.onClose,
    onDone: options.onDone,
    load: async () => {
      const latest = await options.load();
      const already = options.targetApproval !== undefined && !!latest.approved === options.targetApproval;
      const changedChildren = options.seen.composition !== undefined && latest.composition !== options.seen.composition;
      return {
        rows: snapshotRows(options.seen, latest),
        // 승인도 하위 구성을 함께 다룬다 (M8-C4c-1 엔터티 승인 — 속성·식별자). 화면이 안내를 따로 주면 그것을 쓴다 (M8-C4c-2)
        note: options.note?.(latest) ?? (changedChildren
          ? (options.targetApproval !== undefined ? t('함께 승인되는 하위 항목(속성·식별자)이 변경되었습니다. 최신 구성을 다시 확인해 주세요.') : t('삭제 대상의 하위 항목이 변경되었습니다. 함께 삭제될 항목을 다시 확인해 주세요.'))
          : options.targetApproval !== undefined ? t('최신 승인 대상을 다시 확인해 주세요.') : undefined),
        blocked: already ? (options.targetApproval ? t('다른 사용자가 이미 승인했습니다. 다시 승인할 필요가 없습니다.') : t('다른 사용자가 이미 승인을 취소했습니다.')) : options.block?.(latest),
        payload: latest,
      };
    },
    retry: (latest) => options.act(latest),
  });
  return false;
}

export interface ListItem {
  id: number;
  label: string;
  seen: Snapshot;
}

export interface ListAction {
  dialog: DialogApi;
  title: string;
  intro: string;
  items: ListItem[];
  /** 최신 상태 : 없어진 항목은 빠진다 */
  load: () => Promise<Map<number, Snapshot>>;
  /** 요청 : 항목 ID → 그 상태 (기준값·구성) */
  act: (states: Map<number, Snapshot>) => Promise<void>;
  retryLabel: string;
  targetApproval?: boolean;
  /** 최신 상태로는 다시 실행할 수 없는 사유 (예 : 승인되어 삭제할 수 없는 항목). 일부만 빼고 나머지를 보내지 않는다 — 사용자가 선택을 바꾼다 (M8-C4c-2) */
  block?: (latest: Map<number, Snapshot>) => string | undefined;
  /** 안내 (기본 : 승인 안내) */
  note?: string;
  onDone?: () => void;
  onClose?: () => void;
}

/** 여러 건을 한 요청으로 (테이블·컬럼 목록의 승인·삭제) : 서버는 하나라도 다르면 전체를 롤백한다. 검토는 항목마다 확인 시점 ↔ 지금 */
export async function runListWithReview(options: ListAction): Promise<boolean> {
  try {
    await options.act(new Map(options.items.map((i) => [i.id, i.seen])));
    return true;
  } catch (error) {
    if (!isConflict(error)) throw error;
  }
  openConflictReview(options.dialog, {
    title: options.title,
    intro: options.intro,
    retryLabel: options.retryLabel,
    onClose: options.onClose,
    onDone: options.onDone,
    load: async () => {
      const latest = await options.load();
      const rows: CompareRow[] = options.items.map((item) => {
        const now = latest.get(item.id);
        const describe = (s: Snapshot | undefined) => s ? [
          // 항목별 비교 값 (예 : 엔터티의 주제 영역 — M8-C4c-2)
          ...(s.fields ?? []).map((f) => `${f.label} : ${text(f.value) || '—'}`),
          s.approved == null ? null : approvalText(s.approved),
          s.cascade?.filter((c) => c.count > 0).map((c) => t('{0} {1}개', KIND_LABELS[c.kind]?.() ?? c.kind, c.count)).join(', ') || null,
          s.modifiedAt ? formatTimestampPrecise(s.modifiedAt) : null,
        ].filter(Boolean).join(' · ') : t('삭제됨');
        const changed = !now || now.modifiedAt !== item.seen.modifiedAt || (item.seen.composition !== undefined && now.composition !== item.seen.composition)
          || (item.seen.approved !== undefined && !!now.approved !== !!item.seen.approved)
          || JSON.stringify((item.seen.fields ?? []).map((f) => text(f.value))) !== JSON.stringify((now.fields ?? []).map((f) => text(f.value)));
        return { key: String(item.id), label: item.label, before: describe(item.seen), latest: describe(now), changedOnServer: changed };
      });
      const gone = options.items.filter((i) => !latest.get(i.id));
      return {
        rows: rows.filter((r) => r.changedOnServer),
        note: options.note ?? (options.targetApproval !== undefined ? t('최신 승인 대상을 다시 확인해 주세요. 이미 그 상태인 항목은 바뀌지 않습니다.') : undefined),
        blocked: gone.length ? t('그 사이 삭제되었거나 접근할 수 없는 항목이 있습니다 : {0}. 목록을 다시 불러와 선택해 주세요.', gone.map((g) => g.label).join(', ')) : options.block?.(latest),
        payload: latest,
      };
    },
    retry: (latest) => options.act(latest),
  });
  return false;
}
