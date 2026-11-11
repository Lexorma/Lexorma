import { useQuery } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import type { Row } from '../../components/grid/Grid';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError, enc } from '../../lib/api';
import { isBlank } from '../../lib/format';
import { t, tc } from '../../lib/i18n';
import { useColumnBulkEditor } from '../column/ColumnBulkEditor';
import { TableDdlView } from './TableDdl';
import { TableForm } from './TableForm';

interface TableDetailOptions {
  /** 테이블·컬럼·승인 상태가 바뀐 뒤 (목록·ERD 다시 조회) */
  onChanged?: () => void;
  /** [ERD 에서 보기] (ERD 화면에서 열 때는 주지 않는다) */
  onErd?: () => void;
}

/**
 * 테이블 상세 대화상자 (테이블 관리·물리 ERD 공용) : 테이블 정보 + 컬럼 목록, 컬럼 편집 / DDL / 승인·승인취소 / 수정.
 * 화면 컴포넌트에서 호출한다 (대화상자 안은 AuthProvider 밖이라 권한은 여기서 읽어 넘긴다).
 */
export function useTableDetail() {
  const dialog = useDialog();
  const { approvalAvailable } = useAuth();
  const openColumnBulkEditor = useColumnBulkEditor();

  /** 테이블 ID 로 최신 정보를 받아 상세를 연다 (ERD 에서 열 때, 변경 후 다시 열 때) */
  const openTableDetailById = async (tableId: number, options: TableDetailOptions = {}) => {
    openTableDetail(await api<Row>(`/api/selectTable/detail/${enc(tableId)}`), options);
  };

  const openTableDetail = (row: Row, options: TableDetailOptions = {}) => {
    const approved = row.isApproval === 'Y';
    const changed = () => {
      options.onChanged?.();
      void openTableDetailById(row.id, options); // 바뀐 내용으로 상세를 다시 연다
    };

    // 승인 상태면 승인취소, 미승인 상태면 승인 (목록의 승인·승인취소 버튼과 같은 API)
    const toggleApproval = (close: () => void) => {
      dialog.confirm(approved ? t('이 테이블을 승인취소하시겠습니까?') : t('이 테이블을 승인하시겠습니까?'), async () => {
        const response = await api<{ result: boolean }>(`/api/saveTable/${!approved}`, { method: 'PATCH', body: [row] });
        if (!response.result) return;
        close();
        dialog.alert(approved ? t('정상적으로 승인취소처리 되었습니다.') : t('정상적으로 승인처리 되었습니다.'), changed);
      });
    };

    dialog.open({
      title: t('테이블 상세 · {0}', row.tableName ?? ''),
      // 컬럼 목록 표가 들어가므로 넓게 연다
      className: 'dialog-table-detail',
      content: ({ close }: DialogControls) => (
        <TableDetail
          row={row}
          onEditColumns={() => {
            close();
            void openColumnBulkEditor(row, { onSaved: changed });
          }}
          onDdl={() => dialog.popup({ title: `DDL · ${row.tableName ?? ''}`, size: 'large', content: <TableDdlView tableId={row.id} /> })}
          onApproval={approvalAvailable ? () => toggleApproval(close) : undefined}
          onEdit={approved ? undefined : () => {
            close();
            dialog.open({
              title: t('테이블 수정'),
              // 수정은 스키마를 표시만 하므로 스키마 목록이 필요 없다
              content: (form: DialogControls) => <TableForm type="U" row={row} schemas={[]} onSaved={() => { form.close(); changed(); }} />,
            });
          }}
          onErd={options.onErd ? () => { close(); options.onErd?.(); } : undefined}
        />
      ),
    });
  };

  return { openTableDetail, openTableDetailById };
}

interface ColumnInfo {
  id: number;
  columnName: string;
  columnDesc?: string;
  dataType?: string;
  maxLength?: number;
  isPk?: string;
  isNullable?: string;
  isApproval?: string;
  sortOrder?: number;
}

function ynLabel(value: unknown, text: string, variant = 'accent') {
  return value === 'Y' ? <span className={`gh-label gh-label-${variant}`}>{text}</span> : <span className="gh-muted">-</span>;
}

function TableDetail({ row, onEditColumns, onDdl, onEdit, onErd, onApproval }: {
  row: Row;
  onEditColumns: () => void;
  onDdl: () => void;
  onEdit?: () => void;
  onErd?: () => void;
  /** 승인(미승인 상태) / 승인취소(승인 상태). 승인 권한이 없으면 undefined */
  onApproval?: () => void;
}) {
  const approved = row.isApproval === 'Y';
  const { data, error } = useQuery({
    queryKey: ['table-columns', row.id],
    queryFn: () => api<{ columnInfos?: ColumnInfo[] }>(`/api/selectColumn/tableColumn/${enc(row.id)}`, { silent: true }),
    gcTime: 0,
  });

  const order = (v?: number | null) => v ?? Number.MAX_SAFE_INTEGER;
  const columns = (data?.columnInfos ?? []).slice().sort((a, b) => order(a.sortOrder) - order(b.sortOrder));

  const items: [string, unknown, boolean?][] = [
    [t('스키마'), row.schemaName, true],
    [t('테이블명'), row.tableName, true],
    [t('테이블 한글명'), row.tableKorName],
    [t('테이블 설명'), row.tableDescription],
    [t('마스터 테이블'), row.isMasterTable === 'Y' ? 'Y' : 'N'],
    [t('정렬 순서'), row.sortOrder],
  ];

  return (
    <div>
      <div className="dict-detail-status">
        <span className={`gh-label gh-label-dot ${approved ? 'gh-label-success' : 'gh-label-attention'}`}>{approved ? t('승인') : t('승인 대기')}</span>
        <span className="gh-muted">{approved ? t('승인된 테이블은 수정할 수 없습니다.') : ''}</span>
      </div>
      <dl className="dict-detail">
        {items.map(([label, value, mono]) => {
          const empty = isBlank(value);
          return [
            <dt key={`t-${label}`}>{label}</dt>,
            <dd key={`d-${label}`} className={[empty ? 'gh-muted' : '', !empty && mono ? 'gh-mono' : ''].filter(Boolean).join(' ') || undefined}>
              {empty ? '-' : String(value)}
            </dd>,
          ];
        })}
      </dl>

      <div className="dict-detail-columns">
        <h4>{data && columns.length > 0 ? t('컬럼 목록 ({0})', columns.length) : t('컬럼 목록')}</h4>
        {error ? (
          <p className="gh-muted">{error instanceof ApiError && error.status === 403 ? t('컬럼 목록을 조회할 권한이 없습니다.') : t('컬럼 목록을 불러오지 못했습니다.')}</p>
        ) : !data ? (
          <p className="gh-muted">{t('컬럼을 조회하고 있습니다.')}</p>
        ) : columns.length === 0 ? (
          <p className="gh-muted">{t('등록된 컬럼이 없습니다.')}</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  {['#', t('컬럼명'), t('컬럼 설명'), t('데이터 타입'), 'PK', 'NULL', t('승인')].map((h) => <th key={h}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {columns.map((col, idx) => (
                  <tr key={col.id}>
                    <td className="text-center">{idx + 1}</td>
                    <td className="gh-mono">{col.columnName ?? '-'}</td>
                    <td>{col.columnDesc ?? '-'}</td>
                    <td className="gh-mono">{col.dataType ? `${col.dataType}${col.maxLength ? `(${col.maxLength})` : ''}` : '-'}</td>
                    <td className="text-center">{ynLabel(col.isPk, 'PK')}</td>
                    <td className="text-center">{ynLabel(col.isNullable, 'Y')}</td>
                    <td className="text-center">{ynLabel(col.isApproval, t('승인'), 'success')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="gh-dialog-actions">
        {/* 컬럼 일괄 편집 : 승인된 테이블에만 컬럼을 등록할 수 있다 */}
        <button type="button" className="btn" disabled={!approved} onClick={onEditColumns}
          title={approved ? t('표준용어로 컬럼을 한 번에 추가/수정/삭제합니다.') : t('승인된 테이블에만 컬럼을 등록할 수 있습니다.')}>
          {t('컬럼 편집')}
        </button>
        {/* DDL 은 승인 여부와 관계없이 볼 수 있다 */}
        <button type="button" className="btn" onClick={onDdl}>{t('DDL 보기')}</button>
        {onErd && <button type="button" className="btn" onClick={onErd}>{t('ERD 에서 보기')}</button>}
        {onApproval && (
          <button type="button" className={approved ? 'btn btn-danger' : 'btn btn-primary'} onClick={onApproval}>
            {approved ? t('승인취소') : tc('버튼', '승인')}
          </button>
        )}
        {onEdit && <button type="button" className="btn" onClick={onEdit}>{t('수정')}</button>}
      </div>
    </div>
  );
}
