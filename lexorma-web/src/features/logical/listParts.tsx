import { useEffect } from 'react';
import { Label } from '../../components/grid/cells';
import { PAGE_SIZES, Paging } from '../../components/grid/Grid';
import { t } from '../../lib/i18n';
import type { GridPage } from './model';

/** 논리 모델 관리의 서버 페이지 목록 부품 (엔터티·관계·서브타입 그룹 탭 공용) */

export interface PageState {
  page: number;
  size: number;
}

/** 빈 페이지인데 앞에 데이터가 있으면 마지막 페이지로 (삭제·검색으로 줄어든 경우) */
export function useLastPage(data: GridPage<unknown> | undefined, state: PageState, set: (update: (p: PageState) => PageState) => void) {
  useEffect(() => {
    if (!data || data.data.length > 0 || state.page === 0) return;
    const last = Math.max(0, Math.ceil(data.recordsTotal / state.size) - 1);
    if (last !== state.page) set((p) => ({ ...p, page: last }));
  }, [data, state.page, state.size]);
}

/** 목록 위 줄 : 페이지 크기(공용 그리드와 같은 선택지)와 건수 */
export function ListStatus({ size, onSize, text, loading }: { size: number; onSize: (size: number) => void; text: string; loading: boolean }) {
  return (
    <div className="dt-layout-row">
      <div className="dt-layout-cell dt-layout-start">
        <div className="dt-length">
          <label>
            {t('페이지당')}{' '}
            <select className="dt-input" value={size} onChange={(e) => onSize(Number(e.target.value))}>
              {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>{' '}
            {t('행 표시')}
          </label>
          <span className="gh-muted logical-list-count" role="status">{text}{loading ? ` · ${t('불러오는 중입니다.')}` : ''}</span>
        </div>
      </div>
    </div>
  );
}

export function PageNav({ data, state, onPage }: { data: GridPage<unknown> | undefined; state: PageState; onPage: (page: number) => void }) {
  const pageCount = Math.max(1, Math.ceil((data?.recordsTotal ?? 0) / state.size));
  return (
    <div className="dt-layout-row">
      <div className="dt-layout-cell dt-layout-end">
        <Paging page={Math.min(state.page, pageCount - 1)} pageCount={pageCount} onChange={onPage} />
      </div>
    </div>
  );
}

/** 정렬할 수 있는 머리글 (공용 그리드와 같은 표시·aria-sort). onSort 가 없으면 정렬하지 않는 열 */
export function SortHeader({ label, ordering, onSort }: { label: string; ordering: 'asc' | 'desc' | null; onSort?: () => void }) {
  if (!onSort) return <th className="text-start no-sort">{label}</th>;
  return (
    <th className={`text-start dt-orderable-asc dt-orderable-desc${ordering ? ` dt-ordering-${ordering}` : ''}`} onClick={onSort}
      aria-sort={ordering === 'asc' ? 'ascending' : ordering === 'desc' ? 'descending' : undefined}>
      <div className="dt-column-header"><span className="dt-column-title">{label}</span><span className="dt-column-order" /></div>
    </th>
  );
}

export function ApprovalLabel({ value }: { value: boolean | null }) {
  return value ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>;
}
