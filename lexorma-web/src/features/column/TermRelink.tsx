import { useState } from 'react';
import { ApiError, api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { searchTerms, termLabel, type Term, type TermSearchType } from './terms';
import '../../styles/ai-dictionary.css';
import '../../styles/term-relink.css';

/** 컬럼 행의 지금 표준 용어 (GET /api/selectColumn/data 의 standardTerms) */
export interface LinkedTerm {
  id: number;
  commonStandardTermName?: string;
  commonStandardTermAbbreviation?: string;
  commonStandardDomainName?: string | null;
  dataType?: string | null;
  isApprovalYn?: string;
}

export interface TermRelinkColumn {
  id: number;
  tableName?: string;
  columnName?: string;
  columnDesc?: string;
  dataType?: string;
  maxLength?: number | string | null;
  precision?: number | string | null;
  scale?: number | string | null;
  /** 연결된 표준 용어 (없으면 null — DEC-26 처음 연결) */
  standardTerms: LinkedTerm | null;
}

/** 컬럼 타입 표기 : 도메인 타입 표기(VARCHAR(10))와 같은 모양으로 */
function columnType(c: TermRelinkColumn) {
  const type = String(c.dataType ?? '').trim();
  if (!type) return '';
  if (c.maxLength != null && c.maxLength !== '') return `${type}(${c.maxLength})`;
  if (c.precision != null && c.precision !== '') return `${type}(${c.precision}${c.scale != null && c.scale !== '' ? `,${c.scale}` : ''})`;
  return type;
}

const norm = (value: string | null | undefined) => String(value ?? '').replace(/\s+/g, '').toUpperCase();

/**
 * 기존 컬럼의 표준 용어 재연결 (COL-TERM) : 용어 검색 → 고르기 → 지금 연결과 비교·영향(유지되는 속성, 다른 점 경고) → 확정.
 * 연결만 바꾼다 — 컬럼명·타입·길이·설명·업무 설명은 그대로. 서버가 권한·승인 여부·프로젝트·동시 변경(화면이 읽은 연결)을 다시 확인한다.
 */
export function TermRelink({ column, onCancel, onChanged }: { column: TermRelinkColumn; onCancel: () => void; onChanged: (changed: boolean) => void }) {
  const current = column.standardTerms;
  const [type, setType] = useState<TermSearchType>('termName');
  const [keyword, setKeyword] = useState('');
  const [results, setResults] = useState<Term[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [picked, setPicked] = useState<Term | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const search = async () => {
    if (!keyword.trim()) return;
    setSearching(true);
    setError(null);
    try {
      setResults(await searchTerms(type, keyword.trim()));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('검색하지 못했습니다.'));
    } finally {
      setSearching(false);
    }
  };

  // 연결·변경 : 고른 용어, 해제(DEC-26) : unlink. 화면이 읽은 연결(없었으면 null)을 함께 보내 그 사이 바뀐 연결을 덮어쓰지 않는다
  const save = async (unlink = false) => {
    if (!picked && !unlink) return;
    setSaving(true);
    setError(null);
    try {
      const body = unlink ? { termId: null, baseTermId: current?.id ?? null, unlink: true } : { termId: picked!.id, baseTermId: current?.id ?? null };
      const result = await api<{ changed: boolean }>(`/api/saveColumn/${enc(column.id)}/term`, { method: 'PUT', body, silent: true });
      onChanged(result.changed);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('표준 용어를 바꾸지 못했습니다.'));
    } finally {
      setSaving(false);
    }
  };

  const same = !!current && picked?.id === current.id;
  const nameDiffers = picked && norm(picked.commonStandardTermAbbreviation) !== norm(column.columnName);
  const typeDiffers = picked && picked.dataType && norm(picked.dataType) !== norm(columnType(column));
  return (
    <div className="term-relink">
      <p className="gh-muted">{t('표준 용어 연결만 바꿉니다. 컬럼명·데이터 타입·길이·설명·업무 설명은 바뀌지 않습니다.')}</p>
      <dl className="dict-detail">
        <dt>{t('대상 컬럼')}</dt><dd className="gh-mono">{column.tableName}.{column.columnName}</dd>
        <dt>{t('현재 표준 용어')}</dt>
        <dd>{current ? <>{termLabel(current)} <span className="gh-mono gh-muted">{current.commonStandardTermAbbreviation}</span></> : <span className="gh-muted">{t('연결 없음')}</span>}</dd>
      </dl>

      <form className="term-relink-search" onSubmit={(e) => { e.preventDefault(); void search(); }}>
        <select className="search-column-select" value={type} onChange={(e) => setType(e.target.value as TermSearchType)} aria-label={t('검색 기준')}>
          <option value="termName">{t('용어명')}</option>
          <option value="termAbbreviation">{t('영문 약어')}</option>
        </select>
        <input className="search-input" value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder={t('새 표준 용어 검색')} aria-label={t('새 표준 용어 검색')} />
        <button type="submit" className="btn btn-sm" disabled={searching || !keyword.trim()}>{searching ? t('검색 중…') : t('검색')}</button>
      </form>

      {results && (results.length === 0 ? <p className="gh-muted">{t('검색 결과가 없습니다.')}</p> : (
        <ul className="term-relink-results" role="listbox" aria-label={t('검색 결과')}>
          {results.map((term) => (
            <li key={term.id}>
              <button type="button" role="option" aria-selected={picked?.id === term.id} className={picked?.id === term.id ? 'is-picked' : undefined} onClick={() => setPicked(term)}>
                <span>{termLabel(term)}</span> <span className="gh-mono gh-muted">{term.commonStandardTermAbbreviation}</span>
                <span className="gh-muted"> · {term.commonStandardDomainName ?? t('도메인 없음')}{term.dataType ? ` · ${term.dataType}` : ''}</span>
                {term.isApprovalYn !== 'Y' && <span className="gh-label gh-label-attention">{t('미승인 용어')}</span>}
                {term.id === current?.id && <span className="gh-label">{t('현재 연결')}</span>}
              </button>
            </li>
          ))}
        </ul>
      ))}

      {picked && (
        <section className="term-relink-compare" aria-label={t('변경 영향')}>
          <h4>{t('변경 영향')}</h4>
          <table className="term-relink-table">
            <thead><tr><th /><th>{t('현재 연결')}</th><th>{t('새 연결')}</th></tr></thead>
            <tbody>
              <tr><th>{t('표준 용어')}</th><td>{current ? termLabel(current) : '-'}</td><td>{termLabel(picked)}</td></tr>
              <tr><th>{t('영문 약어')}</th><td className="gh-mono">{current?.commonStandardTermAbbreviation ?? '-'}</td><td className="gh-mono">{picked.commonStandardTermAbbreviation}</td></tr>
              <tr><th>{t('표준 도메인')}</th><td>{current?.commonStandardDomainName ?? '-'}</td><td>{picked.commonStandardDomainName ?? '-'}</td></tr>
              <tr><th>{t('권장 타입')}</th><td className="gh-mono">{current?.dataType ?? '-'}</td><td className="gh-mono">{picked.dataType ?? '-'}</td></tr>
            </tbody>
          </table>
          <ul className="term-relink-keep">
            <li>{t('컬럼명')} <span className="gh-mono">{column.columnName}</span> — {t('그대로')}</li>
            <li>{t('데이터 타입')} <span className="gh-mono">{columnType(column) || '-'}</span> — {t('그대로')}</li>
            <li>{t('컬럼 설명·업무 설명·PK·NULL·기본값·제약조건')} — {t('그대로')}</li>
            <li>{t('연결된 표준 용어')} — {same ? t('같은 용어 (바뀌지 않음)') : t('바뀜')}</li>
          </ul>
          {!same && (nameDiffers || typeDiffers || picked.isApprovalYn !== 'Y') && (
            <div className="ai-dict-callout is-warn" role="note">
              <ul>
                {nameDiffers && <li>{t('새 표준 용어의 영문 약어({0})와 지금 컬럼명({1})이 다릅니다. 컬럼명은 바뀌지 않습니다.', picked.commonStandardTermAbbreviation, column.columnName ?? '')}</li>}
                {typeDiffers && <li>{t('새 표준 용어의 권장 타입({0})과 지금 컬럼 타입({1})이 다릅니다. 타입은 바뀌지 않습니다.', picked.dataType ?? '', columnType(column) || '-')}</li>}
                {picked.isApprovalYn !== 'Y' && <li>{t('승인되지 않은 표준 용어입니다.')}</li>}
              </ul>
              <p className="ai-dict-small">{t('차이는 표준화 품질 진단에 나타날 수 있습니다. 이 컬럼을 근거로 만든 AI 설명 제안은 재검토가 필요해질 수 있습니다.')}</p>
            </div>
          )}
        </section>
      )}

      {error && <div className="ai-dict-callout is-error" role="alert">{error}</div>}
      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={onCancel} disabled={saving}>{t('취소')}</button>
        {current && <button type="button" className="btn btn-danger" onClick={() => void save(true)} disabled={saving}
                            title={t('표준 용어 연결만 끊습니다. 컬럼은 그대로이고 표준화 품질 진단에 나타날 수 있습니다.')}>{t('연결 해제')}</button>}
        <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={!picked || same || saving}>
          {saving ? t('저장 중…') : current ? t('이 표준 용어로 변경') : t('이 표준 용어로 연결')}
        </button>
      </div>
    </div>
  );
}
