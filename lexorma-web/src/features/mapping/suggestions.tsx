import { useQuery } from '@tanstack/react-query';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { checkTypeLabel, type CheckType, type TableCandidate } from './model';

/**
 * 표준 용어 기반 매핑 후보 (M-7 자동 매핑 후보). 서버가 규칙으로 정한 근거만 보여 주고, 후보를 골라도 저장하지 않는다 —
 * 저장은 사용자가 고른 뒤 기존 매핑 저장(엔터티 = [매핑 저장], 속성 = 표의 [매핑])으로만 한다 (M7-R01, R03).
 * 점수·확률 없음 : 근거 수준은 "표준 용어 근거" / "참고 근거" 둘뿐이다 (M7-R07). 후보가 여럿이어도 미리 고르지 않는다 (M7-R04).
 */

export type SuggestionLevel = 'STANDARD' | 'REFERENCE';

/** GET /api/modelMapping/entity/suggestions?entityId= */
export interface TableSuggestions {
  modelId: number;
  entityId: number;
  entityName: string;
  mapped: boolean;
  recommendedTableName: string | null;
  keyTermCount: number;
  available: boolean;
  unavailableReason: 'ALREADY_MAPPED' | 'NO_CANDIDATE' | null;
  ambiguous: boolean;
  candidates: TableSuggestion[];
}

export interface TableSuggestion {
  tableId: number;
  schemaId: number;
  schemaName: string;
  tableName: string;
  tableKorName: string | null;
  isApproval: boolean | null;
  level: SuggestionLevel;
  reasons: string[];
  matchedTerms: number;
  termAttributes: number;
}

/** GET /api/modelMapping/entity/{mappingId}/suggestions */
export interface AttributeSuggestions {
  mappingId: number;
  entityName: string;
  tableName: string;
  attributes: AttributeSuggestion[];
}

export interface AttributeSuggestion {
  attributeId: number;
  attributeName: string;
  termId: number | null;
  termName: string | null;
  termApproval: boolean | null;
  mapped: boolean;
  columnId: number | null;
  columnName: string | null;
  available: boolean;
  unavailableReason: 'ALREADY_MAPPED' | 'NO_CANDIDATE' | null;
  notes: string[];
  conflicts: string[];
  candidates: ColumnSuggestion[];
}

export interface ColumnSuggestion {
  columnId: number;
  columnName: string;
  columnDesc: string | null;
  dataType: string | null;
  maxLength: number | null;
  termName: string | null;
  level: SuggestionLevel;
  reasons: string[];
  sharedWith: string[];
  differences: { type: CheckType; logicalValue: string | null; physicalValue: string | null; reason: string | null }[];
}

const REASON: Record<string, string> = {
  KEY_TERMS: t('주 식별자 표준 용어 = PK 컬럼 용어'),
  TABLE_NAME: t('표준 단어로 만든 테이블명과 같음'),
  TABLE_COMMENT: t('테이블 코멘트 = 엔터티명'),
  STANDARD_TERM: t('같은 표준 용어'),
  STANDARD_TERM_UNAPPROVED: t('같은 표준 용어 (미승인)'),
  COLUMN_NAME: t('컬럼명 = 표준 용어 영문약어'),
  LOGICAL_NAME: t('속성명 = 컬럼 논리명'),
};
const CONFLICT: Record<string, string> = {
  MULTIPLE_STANDARD: t('같은 표준 용어를 쓰는 후보가 여러 개입니다.'),
  REASONS_DIFFER: t('근거마다 가리키는 컬럼이 다릅니다.'),
  SHARED: t('첫 후보가 다른 속성의 첫 후보이기도 합니다.'),
};
const NOTE: Record<string, string> = {
  NO_TERM: t('표준 용어가 없어 표준 용어 근거를 쓸 수 없습니다.'),
  TERM_NOT_APPROVED: t('표준 용어가 승인되지 않아 같은 용어도 참고 근거로만 봅니다.'),
};

export const reasonLabel = (code: string) => REASON[code] ?? code;

export function LevelLabel({ level }: { level: SuggestionLevel }) {
  return level === 'STANDARD'
    ? <Label variant="success" title={t('표준 용어 ID 가 근거입니다. 그래도 저장 전에 확인해주세요.')}>{t('표준 용어 근거')}</Label>
    : <Label title={t('이름·명명 규칙이 같다는 참고 근거입니다. 같은 객체로 단정하지 않습니다.')}>{t('참고 근거')}</Label>;
}

const asCandidate = (s: TableSuggestion): TableCandidate => ({
  tableId: s.tableId, schemaId: s.schemaId, schemaName: s.schemaName, tableName: s.tableName, tableKorName: s.tableKorName, isApproval: s.isApproval,
});

/** 엔터티 매핑 대화상자의 추천 후보 (일반 검색 결과와 따로 보인다). 고르면 아래 저장 대상이 그 테이블이 될 뿐 저장하지 않는다 */
export function TableSuggestionList({ entityId, schemaId, selectedId, onSelect }: {
  entityId: number;
  schemaId: string;
  selectedId: number | null;
  onSelect: (table: TableCandidate) => void;
}) {
  const query = useQuery({
    queryKey: ['mapping-table-suggestions', entityId, schemaId],
    queryFn: () => api<TableSuggestions>(`/api/modelMapping/entity/suggestions?entityId=${enc(entityId)}${schemaId ? `&schemaId=${enc(schemaId)}` : ''}`, { silent: true }),
    gcTime: 0,
  });
  const data = query.data;
  return (
    <section className="mapping-suggestions" aria-label={t('추천 후보 테이블')}>
      <h4 className="mapping-subtitle">{t('추천 후보')}</h4>
      {query.isError ? <p role="alert">{t('추천 후보를 불러오지 못했습니다.')}</p>
        : !data ? <p className="gh-muted" role="status">{t('불러오는 중입니다.')}</p>
        : data.candidates.length === 0 ? (
          <p className="gh-muted">
            {t('추천할 근거가 있는 테이블이 없습니다. 아래 일반 검색에서 직접 고를 수 있습니다.')}
            {data.recommendedTableName && <> {t('(표준 단어 테이블명 : {0})', data.recommendedTableName)}</>}
          </p>
        ) : (
          <>
            {data.ambiguous && <p className="form-help" role="note">{t('후보가 여러 개입니다. 근거를 확인하고 직접 골라주세요 (자동으로 고르지 않습니다).')}</p>}
            <ul className="mapping-candidates mapping-suggestion-list" role="radiogroup" aria-label={t('추천 후보 테이블')}>
              {data.candidates.map((c) => (
                <li key={c.tableId}>
                  <label>
                    <input type="radio" name="mappingTableSuggestion" checked={selectedId === c.tableId} onChange={() => onSelect(asCandidate(c))} />
                    <span className="gh-mono">{c.schemaName}.{c.tableName}</span>
                    {c.tableKorName && <span className="gh-muted">{c.tableKorName}</span>}
                    <LevelLabel level={c.level} />
                    {!c.isApproval && <Label variant="attention" dot>{t('승인 대기')}</Label>}
                    <span className="mapping-reasons">
                      {c.reasons.map((r) => <span key={r} className="mapping-reason">{reasonLabel(r)}</span>)}
                      {c.termAttributes > 0 && <span className="gh-muted">{t('속성 용어 {0} / {1} 일치', c.matchedTerms, c.termAttributes)}</span>}
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          </>
        )}
    </section>
  );
}

const columnText = (c: ColumnSuggestion) => [c.columnDesc, c.dataType ? `${c.dataType}${c.maxLength ? `(${c.maxLength})` : ''}` : null, c.termName].filter(Boolean).join(' · ');

/**
 * 매핑 상세의 속성별 추천 후보. [선택] 은 표의 컬럼 선택만 채운다 — 저장은 표의 [매핑] 으로 기존 저장 API 를 부른다.
 * 매핑된 속성·후보 없는 속성은 접어서 수만 보인다
 */
export function AttributeSuggestionSection({ mappingId, canEdit, picked, onPick, version }: {
  mappingId: number;
  canEdit: boolean;
  /** 속성 ID → 표에서 고른(저장 전) 컬럼 ID */
  picked: Record<number, string>;
  onPick: (attributeId: number, columnId: number) => void;
  /** 매핑이 바뀌면 다시 받는다 */
  version: number;
}) {
  const query = useQuery({
    queryKey: ['mapping-attribute-suggestions', mappingId, version],
    queryFn: () => api<AttributeSuggestions>(`/api/modelMapping/entity/${enc(mappingId)}/suggestions`, { silent: true }),
    gcTime: 0,
  });
  if (query.isError) return <p role="alert">{t('추천 후보를 불러오지 못했습니다.')}</p>;
  if (!query.data) return <p className="gh-muted" role="status">{t('불러오는 중입니다.')}</p>;
  const open = query.data.attributes.filter((a) => !a.mapped);
  const withCandidates = open.filter((a) => a.candidates.length > 0);
  const without = open.filter((a) => a.candidates.length === 0);
  return (
    <section className="mapping-suggestions" aria-label={t('추천 후보 컬럼')}>
      <h4 className="mapping-subtitle">
        {t('추천 후보')} <span className="gh-muted">{t('매핑하지 않은 속성 {0}개 중 후보 있음 {1}개', open.length, withCandidates.length)}</span>
      </h4>
      {open.length === 0 ? <p className="gh-muted">{t('모든 직접 속성이 매핑되어 있습니다.')}</p>
        : withCandidates.length === 0 ? <p className="gh-muted">{t('추천할 근거가 있는 컬럼이 없습니다. 표에서 직접 골라주세요.')}</p>
        : (
          <ul className="mapping-suggestion-attrs">
            {withCandidates.map((a) => (
              <li key={a.attributeId}>
                <div className="mapping-suggestion-head">
                  <strong>{a.attributeName}</strong>
                  {a.termName ? <span className="gh-muted">{a.termName.replaceAll('_', ' ')}</span> : <Muted>{t('표준 용어 없음')}</Muted>}
                  {a.notes.map((n) => <span key={n} className="form-help">{NOTE[n] ?? n}</span>)}
                </div>
                {a.conflicts.length > 0 && (
                  <p className="mapping-suggestion-conflict" role="note">⚠ {a.conflicts.map((c) => CONFLICT[c] ?? c).join(' ')} {t('직접 골라주세요.')}</p>
                )}
                <ul className="mapping-suggestion-columns">
                  {a.candidates.map((c) => {
                    const chosen = picked[a.attributeId] === String(c.columnId);
                    return (
                      <li key={c.columnId} className={chosen ? 'is-picked' : undefined}>
                        <Mono>{c.columnName}</Mono>
                        <LevelLabel level={c.level} />
                        <span className="mapping-reasons">{c.reasons.map((r) => <span key={r} className="mapping-reason">{reasonLabel(r)}</span>)}</span>
                        {columnText(c) && <small className="gh-muted">{columnText(c)}</small>}
                        {c.sharedWith.length > 0 && <small className="gh-muted">{t('다른 속성의 후보이기도 함 : {0}', c.sharedWith.join(', '))}</small>}
                        {c.differences.length > 0 && (
                          <small className="mapping-suggestion-diff" title={c.differences.map((d) => d.reason).filter(Boolean).join(' ')}>
                            {t('매핑하면 정합성 불일치 : {0}', c.differences.map((d) => `${checkTypeLabel(d.type)}${d.logicalValue || d.physicalValue ? ` (${d.logicalValue ?? '-'} ≠ ${d.physicalValue ?? '-'})` : ''}`).join(', '))}
                          </small>
                        )}
                        {canEdit && (
                          <button type="button" className="btn btn-sm" aria-pressed={chosen} disabled={chosen}
                            aria-label={t('{0} 속성에 {1} 컬럼 선택', a.attributeName, c.columnName)}
                            onClick={() => onPick(a.attributeId, c.columnId)}>
                            {chosen ? t('선택됨') : t('후보 선택')}
                          </button>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </li>
            ))}
          </ul>
        )}
      {without.length > 0 && <p className="gh-muted">{t('후보 없음 : {0}', without.map((a) => a.attributeName).join(', '))}</p>}
      {canEdit && withCandidates.length > 0 && <p className="form-help">{t('[후보 선택] 은 아래 표의 컬럼만 채웁니다. 표의 [매핑] 을 눌러야 저장됩니다.')}</p>}
    </section>
  );
}
