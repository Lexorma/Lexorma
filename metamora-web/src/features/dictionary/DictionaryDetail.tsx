import type { GridColumn, Row } from '../../components/grid/Grid';
import { isBlank } from '../../lib/format';
import { t } from '../../lib/i18n';

/**
 * 데이터 사전(표준 도메인/단어/용어) 상세보기 : 그리드에서 숨긴 컬럼까지 모든 항목을 읽기 전용으로 보여준다.
 * 승인 전 항목은 [수정] 버튼으로 수정 팝업을 연다.
 */
export function DictionaryDetail({ row, columns, onEdit }: { row: Row; columns: GridColumn[]; onEdit?: () => void }) {
  const approved = row.isApprovalYn === 'Y';
  return (
    <div>
      <div className="dict-detail-status">
        <span className={`gh-label gh-label-dot ${approved ? 'gh-label-success' : 'gh-label-attention'}`}>{approved ? t('승인') : t('승인 대기')}</span>
        <span className="gh-muted">{approved ? t('승인된 항목은 수정할 수 없습니다.') : ''}</span>
      </div>
      <dl className="dict-detail">
        {columns
          .filter((col) => col.rowType !== 'CHECKBOX' && col.column !== 'isApprovalYn')
          .map((col) => {
            const value = row[col.column];
            const empty = isBlank(value);
            return [
              <dt key={`t-${col.column}`}>{col.columnName || col.column}</dt>,
              <dd key={`d-${col.column}`} className={empty ? 'gh-muted' : undefined}>{empty ? '-' : String(value)}</dd>,
            ];
          })}
      </dl>
      {onEdit && (
        <div className="gh-dialog-actions">
          <button type="button" className="btn btn-primary" onClick={onEdit}>{t('수정')}</button>
        </div>
      )}
    </div>
  );
}
