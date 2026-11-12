import { t } from '../../lib/i18n';
import { TARGET_TYPES, targetTypeLabel, type HistoryState } from '../history/model';

/** 모델 버전 (M-9 3차) : 서버 ModelVersionDto 와 같은 모양 */
export interface VersionSummary {
  id: number;
  versionName: string;
  description: string | null;
  formatVersion: number;
  /** 대상 종류 → 객체 수 */
  counts: Record<string, number>;
  createdBy: string | null;
  createdAt: string;
}

export interface VersionObject {
  id: number;
  name: string | null;
  state: HistoryState;
}

export interface VersionSnapshot {
  version: VersionSummary;
  objects: Record<string, VersionObject[]>;
}

export type ChangeKind = 'ADDED' | 'REMOVED' | 'MODIFIED';

export interface VersionChange {
  targetType: string;
  targetId: number;
  name: string | null;
  change: ChangeKind;
  changedFields: string[];
  before: HistoryState | null;
  after: HistoryState | null;
}

export interface VersionComparison {
  base: { id: number; versionName: string; createdAt: string };
  /** 현재 모델이면 id 가 null */
  target: { id: number | null; versionName: string | null; createdAt: string | null };
  totals: Record<string, number>;
  byType: Record<string, Record<string, number>>;
  changes: VersionChange[];
}

/** 버전에 담기는 대상 종류 : 스키마 + 변경 이력의 대상 종류 (화면에 나열하는 순서) */
export const VERSION_TYPES: [string, string][] = [['SCHEMA', t('스키마')], ...TARGET_TYPES];

export const versionTypeLabel = (code: string) => (code === 'SCHEMA' ? t('스키마') : targetTypeLabel(code));

export const CHANGES: [ChangeKind, string, 'success' | 'danger' | 'accent'][] = [
  ['ADDED', t('추가'), 'success'],
  ['REMOVED', t('삭제'), 'danger'],
  ['MODIFIED', t('수정'), 'accent'],
];

/** 목록의 "모델 규모" : 테이블·컬럼·엔터티·속성·매핑 수 */
export function scaleText(counts: Record<string, number> | null | undefined): string {
  const count = (...types: string[]) => types.reduce((sum, type) => sum + (counts?.[type] ?? 0), 0);
  return [
    t('테이블 {0}', count('PHYSICAL_TABLE')),
    t('컬럼 {0}', count('PHYSICAL_COLUMN')),
    t('엔터티 {0}', count('LOGICAL_ENTITY')),
    t('속성 {0}', count('LOGICAL_ATTRIBUTE')),
    t('매핑 {0}', count('TABLE_MAPPING', 'COLUMN_MAPPING')),
  ].join(' · ');
}
