import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Approval, Mono } from '../../components/grid/cells';
import { Grid, type Row } from '../../components/grid/Grid';
import { useAuth } from '../../hooks/useAuth';
import { enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { useSchemas } from '../schema/schemas';
import { useTableDetail } from '../table/tableDetail';
import { usageColumnColumns, usageTermColumns } from './columns';

export type UsageKind = 'standardTerms' | 'standardDomains' | 'standardWords';

const yn = (value: unknown) => (value ? 'Y' : 'N');

/**
 * 표준 항목 사용 현황 (M-5 1차, 조회 전용)
 * - 표준 용어 / 표준 도메인 : 그 용어(도메인의 용어)를 참조하는 컬럼
 * - 표준 단어 : 그 단어로 만든 표준 용어 → 용어의 사용 컬럼 수를 누르면 컬럼 목록
 * 이름이 같은 것이 아니라 실제 참조만 보여 준다.
 */
export function DictionaryUsage({ kind, row, close }: { kind: UsageKind; row: Row; close: () => void }) {
  const dialog = useDialog();
  if (kind === 'standardWords') {
    return (
      <div className="dict-usage">
        <p className="gh-muted">{t('이 표준 단어로 만든 표준 용어입니다. 사용 컬럼 수를 누르면 그 용어를 쓰는 컬럼을 볼 수 있습니다.')}</p>
        <Grid
          id="dictionaryUsageTerms"
          columns={usageTermColumns}
          dataUrl={`/api/dictionaryUsage/word/${enc(row.id)}`}
          defaultSort={{ column: 'termName' }}
          emptyText={t('이 표준 단어를 사용하는 표준 용어가 없습니다.')}
          renderCell={(field, term) => {
            if (field === 'termAbbreviation') return <Mono>{term.termAbbreviation}</Mono>;
            if (field === 'isApproval') return <Approval value={yn(term.isApproval)} />;
            if (field === 'columnCount') {
              if (!term.columnCount) return <span className="gh-muted">0</span>;
              return (
                <button type="button" className="btn btn-sm" title={t('이 용어를 쓰는 컬럼 보기')}
                  onClick={() => dialog.popup({
                    title: t('사용 현황 · {0}', term.termName ?? ''),
                    size: 'large',
                    // 이동할 때는 이 팝업과 아래 사용 현황 대화상자를 모두 닫는다
                    content: (popup: DialogControls) => (
                      <div className="dict-usage">
                        <ColumnUsageGrid kind="term" id={term.id} onNavigate={() => { popup.close(); close(); }} />
                      </div>
                    ),
                  })}>
                  {term.columnCount}
                </button>
              );
            }
            return undefined;
          }}
        />
      </div>
    );
  }
  return (
    <div className="dict-usage">
      {kind === 'standardDomains' && <p className="gh-muted">{t('컬럼은 표준 도메인을 직접 참조하지 않습니다. 이 도메인에 속한 표준 용어를 쓰는 컬럼을 보여 줍니다.')}</p>}
      <ColumnUsageGrid kind={kind === 'standardTerms' ? 'term' : 'domain'} id={row.id} onNavigate={close} />
    </div>
  );
}

/** 표준 용어(term) 또는 표준 도메인(domain)을 쓰는 컬럼 목록 : 스키마·승인 상태 조건, 테이블명·컬럼명 검색, 테이블 상세 대화상자·ERD 이동 */
function ColumnUsageGrid({ kind, id, onNavigate }: { kind: 'term' | 'domain'; id: number; onNavigate: () => void }) {
  const { schemas } = useSchemas();
  const [schemaId, setSchemaId] = useState('');
  const [isApproval, setIsApproval] = useState('');

  const params: Record<string, string> = {};
  if (schemaId) params.schemaId = schemaId;
  if (isApproval) params.isApproval = isApproval;

  return (
    <Grid
      id={`dictionaryUsageColumns-${kind}`}
      columns={usageColumnColumns}
      dataUrl={`/api/dictionaryUsage/${kind}/${enc(id)}`}
      defaultSort={{ column: 'tableName' }}
      params={params}
      searchFilters={
        <>
          <select className="search-column-select" aria-label={t('스키마')} value={schemaId} onChange={(e) => setSchemaId(e.target.value)}>
            <option value="">{t('전체 스키마')}</option>
            {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
          </select>
          <select className="search-column-select" aria-label={t('승인 상태')} value={isApproval} onChange={(e) => setIsApproval(e.target.value)}>
            <option value="">{t('승인 전체')}</option>
            <option value="true">{t('승인')}</option>
            <option value="false">{t('승인 대기')}</option>
          </select>
        </>
      }
      emptyText={kind === 'term' ? t('이 표준 용어를 사용하는 컬럼이 없습니다.') : t('이 표준 도메인을 사용하는 컬럼이 없습니다.')}
      renderCell={(field, row) => {
        if (['schemaName', 'tableName', 'columnName'].includes(field)) return <Mono>{row[field]}</Mono>;
        if (field === 'dataType') return <Mono>{row.dataType ? `${row.dataType}${row.maxLength ? `(${row.maxLength})` : ''}` : ''}</Mono>;
        if (field === 'isApproval') return <Approval value={yn(row.isApproval)} />;
        if (field === 'links') return <TableLinks tableId={row.tableId} schemaId={row.schemaId} columnId={row.id} onNavigate={onNavigate} />;
        return undefined;
      }}
    />
  );
}

/**
 * 행의 이동 버튼 (사용 현황·표준화 품질 진단 공용)
 * - [테이블 상세] : 화면을 옮기지 않고 테이블 관리와 같은 상세 대화상자를 연다 (컬럼 목록·컬럼 편집·DDL·승인). 해당 컬럼을 강조한다
 * - [ERD] : 물리 ERD 로 이동해 그 테이블을 선택·포커스 (M-3 의 URL 파라미터)
 */
export function TableLinks({ tableId, schemaId, columnId, onNavigate, onChanged }: {
  tableId: number;
  schemaId: number;
  columnId?: number;
  /** 이동하기 전 (대화상자 닫기) */
  onNavigate?: () => void;
  /** 테이블 상세에서 테이블·컬럼이 바뀐 뒤 */
  onChanged?: () => void;
}) {
  const navigate = useNavigate();
  const { canView } = useAuth();
  const { openTableDetailById } = useTableDetail();

  const goErd = () => {
    onNavigate?.();
    navigate(`/physicalErd?schemaId=${enc(schemaId)}&tableId=${enc(tableId)}`);
  };

  return (
    <span className="dict-usage-links">
      {canView('table-manage') && (
        <button type="button" className="btn btn-sm"
          onClick={() => void openTableDetailById(tableId, { columnId, onChanged, onErd: canView('physical-erd') ? goErd : undefined })}>
          {t('테이블 상세')}
        </button>
      )}
      {canView('physical-erd') && <button type="button" className="btn btn-sm" onClick={goErd}>ERD</button>}
    </span>
  );
}
