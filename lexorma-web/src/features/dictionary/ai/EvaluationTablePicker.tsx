import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { t } from '../../../lib/i18n';
import { aiDictionary } from './aiDictionary';

export interface PickedTable { tableId: number; name: string }

/**
 * 품질 평가 › 평가 대상 고르기의 테이블 찾기 (AI-5-EVAL). 예전 드롭다운(쪽 없는 /targets, 앞 500개)을 대신해 대상 선택 화면과 같은
 * 서버 쪽 나누기 목록(/targets/tables, 30개씩·이름과 테이블 코멘트 부분 일치·이름 → ID 순)에서 한 테이블을 고른다.
 * 고른 테이블은 이름과 함께 따로 기억하므로 검색어를 바꾸거나 쪽을 넘겨 목록에서 사라져도 그대로다. 프로젝트가 바뀌면 화면이 새로 만들어진다(AiEvaluationPage 의 key).
 */
export function EvaluationTablePicker({ picked, onPick }: { picked: PickedTable | null; onPick: (table: PickedTable) => void }) {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(keyword.trim()), 300);
    return () => window.clearTimeout(timer);
  }, [keyword]);
  const [page, setPage] = useState(0);
  useEffect(() => setPage(0), [query]);
  // 대상 선택 화면과 같은 캐시 키 (같은 목록이라 생성·승인 뒤 무효화도 함께 된다)
  const list = useQuery({
    queryKey: ['ai-dict-targets', 'tables', false, query, page],
    queryFn: () => aiDictionary.targetTables({ onlyMissing: false, keyword: query, page }),
    placeholderData: keepPreviousData,
  });
  const rows = list.data?.content ?? [];
  const total = list.data?.totalElements ?? 0;
  const pages = Math.max(1, list.data?.totalPages ?? 1);
  useEffect(() => {
    if (list.data && !list.isPlaceholderData && page > 0 && page >= list.data.totalPages) setPage(Math.max(0, list.data.totalPages - 1));
  }, [list.data, list.isPlaceholderData, page]);

  return (
    <div className="ai-eval-picker">
      <div className="ai-dict-toolbar">
        <input className="search-input" placeholder={t('테이블 이름·코멘트')} aria-label={t('평가할 테이블 찾기')} value={keyword} onChange={(e) => setKeyword(e.target.value)} />
        <span className="gh-muted ai-dict-small" role="status">{list.data ? t('테이블 {0}개', total.toLocaleString()) : list.isError ? t('테이블 목록을 불러오지 못했습니다.') : ''}</span>
      </div>
      <p className="ai-dict-small">
        {t('평가할 테이블')} : {picked ? <strong className="ai-dict-name">{picked.name}</strong> : <span className="gh-muted">{t('테이블을 고르세요')}</span>}
      </p>
      {list.data && rows.length === 0 && <p className="gh-muted ai-dict-small">{t('검색 결과가 없습니다.')}</p>}
      {rows.length > 0 && (
        <ul className="ai-eval-picker-list" role="listbox" aria-label={t('테이블')} aria-busy={list.isFetching}>
          {rows.map((r) => (
            <li key={r.tableId}>
              <button type="button" role="option" aria-selected={picked?.tableId === r.tableId} className={picked?.tableId === r.tableId ? 'is-picked' : undefined}
                      onClick={() => onPick({ tableId: r.tableId, name: r.name })}>
                <span className="ai-dict-name">{r.name}</span>
                {r.description && <span className="gh-muted"> · {r.description}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
      {pages > 1 && (
        <div className="ai-dict-buttons">
          <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage(0)}>{t('처음')}</button>
          <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>{t('이전')}</button>
          <span className="gh-muted">{page + 1} / {pages}</span>
          <button type="button" className="btn btn-sm" disabled={page + 1 >= pages} onClick={() => setPage((p) => Math.min(pages - 1, p + 1))}>{t('다음')}</button>
          <button type="button" className="btn btn-sm" disabled={page + 1 >= pages} onClick={() => setPage(pages - 1)}>{t('마지막')}</button>
        </div>
      )}
    </div>
  );
}
