import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { api } from '../../lib/api';
import { searchTerms, termLabel, type Term, type TermSearchType } from './terms';
import { t } from '../../lib/i18n';

const MAX_ITEMS = 50;

export interface CommonColumnItem {
  termId: number;
  term: Term | null;
  isNullable: string;
  isIndex: string;
  defaultValue: string | null;
}

/**
 * 새 항목 기본값 : 여부 → NOT NULL·기본값 N, 등록·생성 일시 → NOT NULL·CURRENT_TIMESTAMP(날짜/시간 타입), 그 외 NULL 허용
 */
function defaultsFor(term: Term): CommonColumnItem {
  const words = String(term.commonStandardTermName ?? '').split('_');
  const first = words[0];
  const last = words[words.length - 1];
  const type = String(term.dataType ?? '').toUpperCase();
  const item: CommonColumnItem = { termId: term.id, term, isNullable: 'Y', isIndex: 'N', defaultValue: '' };

  if (last === '여부' || /_YN$/i.test(term.commonStandardTermAbbreviation ?? '')) {
    Object.assign(item, { isNullable: 'N', defaultValue: 'N' });
  } else if (['등록', '생성', '최초등록', '입력'].includes(first)) {
    item.isNullable = 'N';
    if (['일시', '일자'].includes(last) && /^(DATE|DATETIME|TIMESTAMP)/.test(type)) item.defaultValue = 'CURRENT_TIMESTAMP';
  }
  return item;
}

/**
 * 공통 컬럼 세트 관리 (컬럼 일괄 편집 > 공통 컬럼 세트 관리) - 프로젝트 관리자 / ADMIN / MANAGER
 *
 * 거의 모든 테이블에 들어가는 컬럼(등록일시, 등록자, 수정일시, 수정자, 사용여부 등)을 표준용어로 정해 둔다.
 * 컬럼명/설명/데이터 타입은 표준용어에서 오고, 여기서는 순서와 NULL 허용/기본값/인덱스만 정한다.
 * 저장은 PUT /api/commonColumn 으로 세트를 통째로 바꾼다.
 */
export function CommonColumnSet({ controls, onSaved }: { controls: DialogControls; onSaved?: () => void }) {
  const dialog = useDialog();
  const { data } = useQuery({
    queryKey: ['common-column'],
    queryFn: () => api<{ items?: CommonColumnItem[] }>('/api/commonColumn'),
    gcTime: 0,
  });
  const [items, setItems] = useState<CommonColumnItem[] | null>(null);
  const [searchType, setSearchType] = useState<TermSearchType>('termName');
  const [keyword, setKeyword] = useState('');
  const [results, setResults] = useState<Term[]>([]);
  const seq = useRef(0);

  useEffect(() => {
    if (data && items === null) {
      setItems((data.items || []).map((item) => ({
        termId: item.termId,
        term: item.term,
        isNullable: item.isNullable === 'Y' ? 'Y' : 'N',
        isIndex: item.isIndex === 'Y' ? 'Y' : 'N',
        defaultValue: item.defaultValue ?? '',
      })));
    }
  }, [data, items]);

  useEffect(() => {
    const value = keyword.trim();
    const mySeq = ++seq.current;
    if (!value) {
      setResults([]);
      return;
    }
    const timer = setTimeout(async () => {
      const terms = await searchTerms(searchType, value);
      if (mySeq === seq.current) setResults(terms);
    }, 300);
    return () => clearTimeout(timer);
  }, [keyword, searchType]);

  if (!items) return <div className="popup-container common-set-popup"><p className="gh-muted">{t('불러오는 중입니다.')}</p></div>;

  const used = new Set(items.map((item) => item.term?.commonStandardTermAbbreviation).filter(Boolean));
  const update = (index: number, patch: Partial<CommonColumnItem>) => setItems((list) => list!.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  const swap = (i: number, j: number) => setItems((list) => {
    const next = list!.slice();
    [next[i], next[j]] = [next[j], next[i]];
    return next;
  });

  const save = () => {
    const missing = items.filter((item) => !item.term).length;
    const message = missing ? t('표준용어가 지워진 {0}개는 빼고 저장합니다. 저장하시겠습니까?', missing) : t('공통 컬럼 세트를 저장하시겠습니까?');
    dialog.confirm(message, async () => {
      const payload = items.filter((item) => item.term).map((item) => ({
        termId: item.termId,
        isNullable: item.isNullable,
        isIndex: item.isIndex,
        defaultValue: String(item.defaultValue ?? '').trim() || null,
      }));
      const response = await api<{ result: boolean }>('/api/commonColumn', { method: 'PUT', body: payload });
      if (!response.result) return;
      controls.close();
      dialog.alert(t('공통 컬럼 세트를 저장했습니다.'), () => onSaved?.());
    });
  };

  return (
    <div className="popup-container common-set-popup">
      <p className="form-help">
        {t('거의 모든 테이블에 들어가는 컬럼을 표준용어로 정해 두면, 컬럼 편집에서 [공통 컬럼 추가]로 한 번에 넣을 수 있습니다. 컬럼명·설명·데이터 타입은 표준용어를 따르고, 여기서는 순서와 NULL 허용·기본값·인덱스를 정합니다.')}
      </p>

      <div className="common-set-search">
        <div className="lookup-search-row">
          <select className="form-control" aria-label={t('표준용어 검색 기준')} value={searchType} onChange={(e) => setSearchType(e.target.value as TermSearchType)}>
            <option value="termName">{t('표준용어명')}</option>
            <option value="termAbbreviation">{t('영문약어명')}</option>
          </select>
          <input type="text" className="form-control" autoComplete="off" placeholder={t('예: 등록 일시 / REG_')} autoFocus value={keyword}
            onChange={(e) => setKeyword(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && e.preventDefault()} />
        </div>
        <div className="common-set-results" aria-live="polite">
          {keyword.trim() && results.length === 0 && (
            <p className="table-name-error">{t('조건에 맞는 표준용어가 없습니다. 데이터 사전 > 표준용어에서 먼저 등록해주세요.')}</p>
          )}
          {keyword.trim() && results.map((term) => {
            const already = used.has(term.commonStandardTermAbbreviation);
            return (
              <button key={term.id} type="button" className="common-set-result" disabled={already}
                onClick={() => {
                  if (items.length >= MAX_ITEMS) return dialog.alert(t('공통 컬럼은 {0}개까지 등록할 수 있습니다.', MAX_ITEMS));
                  setItems((list) => [...list!, defaultsFor(term)]);
                }}>
                <span className="gh-mono">{term.commonStandardTermAbbreviation}</span>
                <span>{termLabel(term)}</span>
                <span className="gh-muted">{term.dataType ?? '-'}</span>
                <span className="gh-muted">{already ? t('이미 있음') : t('+ 추가')}</span>
              </button>
            );
          })}
        </div>
      </div>

      <div className="common-set-head">
        <span className="gh-form-label">{t('공통 컬럼')}</span>
        <span className="gh-muted">{t('{0}개', items.length)}</span>
      </div>
      <div className="common-set-table-wrap">
        <table className="common-set-table">
          <thead>
            <tr>
              <th>{t('순서')}</th><th>{t('컬럼명')}</th><th>{t('설명')}</th><th>{t('데이터 타입')}</th><th>{t('NULL 허용')}</th><th>{t('기본값')}</th><th>{t('인덱스')}</th><th></th>
            </tr>
          </thead>
          <tbody>
            {items.length === 0 ? (
              <tr>
                <td colSpan={8} className="common-set-empty">
                  {t('공통 컬럼이 없습니다. 위에서 표준용어를 검색해 추가하세요. (예: 등록 일시, 등록자 아이디, 수정 일시, 수정자 아이디, 사용 여부)')}
                </td>
              </tr>
            ) : items.map((item, index) => {
              const missing = !item.term;
              return (
                <tr key={`${item.termId}-${index}`} className={missing ? 'is-missing' : undefined}>
                  <td className="common-set-move">
                    <button type="button" className="btn btn-sm" title={t('위로')} aria-label={t('위로')} disabled={index === 0} onClick={() => swap(index, index - 1)}>▲</button>
                    <button type="button" className="btn btn-sm" title={t('아래로')} aria-label={t('아래로')} disabled={index === items.length - 1} onClick={() => swap(index, index + 1)}>▼</button>
                  </td>
                  <td>
                    {missing
                      ? <span className="gh-label gh-label-danger">{t('용어 없음 (ID {0})', item.termId)}</span>
                      : <span className="gh-mono">{item.term!.commonStandardTermAbbreviation}</span>}
                  </td>
                  <td>{missing ? '-' : termLabel(item.term!)}</td>
                  <td className="gh-mono">{missing ? '-' : (item.term!.dataType ?? '-')}</td>
                  <td className="text-center">
                    <input type="checkbox" className="form-check-input" title={t('NULL 허용')} aria-label={t('NULL 허용')} checked={item.isNullable === 'Y'} disabled={missing}
                      onChange={(e) => update(index, { isNullable: e.target.checked ? 'Y' : 'N' })} />
                  </td>
                  <td>
                    <input type="text" className="form-control gh-mono" aria-label={t('기본값')} placeholder={t('예: N / CURRENT_TIMESTAMP')} value={item.defaultValue ?? ''} disabled={missing}
                      onChange={(e) => update(index, { defaultValue: e.target.value })} />
                  </td>
                  <td className="text-center">
                    <input type="checkbox" className="form-check-input" title={t('인덱스')} aria-label={t('인덱스')} checked={item.isIndex === 'Y'} disabled={missing}
                      onChange={(e) => update(index, { isIndex: e.target.checked ? 'Y' : 'N' })} />
                  </td>
                  <td className="text-center">
                    <button type="button" className="btn btn-sm" title={t('삭제')} aria-label={t('삭제')} onClick={() => setItems((list) => list!.filter((_, i) => i !== index))}>✕</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="common-set-actions">
        <button type="button" className="btn" onClick={controls.close}>{t('닫기')}</button>
        <button type="button" className="btn btn-primary" onClick={save}>{t('저장')}</button>
      </div>
    </div>
  );
}
