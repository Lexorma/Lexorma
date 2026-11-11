import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Approval, Label, Mono, Muted, type LabelVariant } from '../../components/grid/cells';
import { Grid, type GridHandle } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { TableLinks } from '../dictionary/DictionaryUsage';
import { useSchemas } from '../schema/schemas';
import { qualityIssueColumns } from './columns';

type IssueType = 'TERM_NOT_LINKED' | 'COLUMN_NAME_MISMATCH' | 'DOMAIN_DEFINITION_MISMATCH';

interface Summary {
  totalColumns: number;
  issueColumns: number;
  termNotLinked: number;
  columnNameMismatch: number;
  domainDefinitionMismatch: number;
}

const TYPES: { type: IssueType; label: string; variant: LabelVariant; count: keyof Summary }[] = [
  { type: 'TERM_NOT_LINKED', label: t('표준 용어 미연결'), variant: 'attention', count: 'termNotLinked' },
  { type: 'COLUMN_NAME_MISMATCH', label: t('컬럼명 불일치'), variant: 'danger', count: 'columnNameMismatch' },
  { type: 'DOMAIN_DEFINITION_MISMATCH', label: t('도메인 정의 불일치'), variant: 'done', count: 'domainDefinitionMismatch' },
];

/**
 * 표준화 품질 진단 (M-5 2차, 조회 전용) : 표준 용어 미연결·컬럼명 불일치·도메인 정의 불일치.
 * 요약 카드를 누르면 그 진단 유형만 보고, 행에서 테이블 상세 대화상자·물리 ERD 로 간다.
 */
export function StandardizationQualityPage() {
  const grid = useRef<GridHandle>(null);
  const { user } = useAuth();
  const { schemas } = useSchemas();
  const [type, setType] = useState<IssueType | ''>('');
  const [schemaId, setSchemaId] = useState('');
  const [isApproval, setIsApproval] = useState('');
  const summary = useQuery({
    queryKey: ['standardization-quality-summary', user?.projectId],
    queryFn: () => api<Summary>('/api/standardizationQuality/summary'),
  });

  const params: Record<string, string> = {};
  if (type) params.type = type;
  if (schemaId) params.schemaId = schemaId;
  if (isApproval) params.isApproval = isApproval;

  // 테이블 상세에서 컬럼·승인 상태를 바꾸면 진단 결과도 달라진다
  const reload = () => {
    grid.current?.reload();
    void summary.refetch();
  };

  const value = (count: keyof Summary) => (summary.data ? summary.data[count] : '-');

  return (
    <>
      <PageHeader category={t('데이터 관리')} title={t('표준화 품질 진단')}
        description={t('물리 컬럼이 표준 용어·도메인을 따르는지 진단합니다. 조회만 하며 컬럼이나 표준 항목을 바꾸지 않습니다.')} />

      {summary.isError && <p className="gh-muted" role="alert">{t('진단 요약을 불러오지 못했습니다.')}</p>}
      <div className="dash dash-stats quality-stats">
        <div className="dash-stat">
          <div className="dash-stat-label">{t('검사 대상 컬럼')}</div>
          <div className="dash-stat-value">{value('totalColumns')}</div>
        </div>
        {/* 한 컬럼에 여러 문제가 있을 수 있어 유형별 건수의 합과 다르다 */}
        <button type="button" className="dash-stat" aria-pressed={type === ''} onClick={() => setType('')}>
          <div className="dash-stat-label">{t('문제가 있는 컬럼')}</div>
          <div className={`dash-stat-value${summary.data?.issueColumns ? ' is-pending' : ''}`}>{value('issueColumns')}</div>
        </button>
        {TYPES.map((item) => (
          <button key={item.type} type="button" className="dash-stat" aria-pressed={type === item.type} onClick={() => setType(item.type)}>
            <div className="dash-stat-label">{item.label}</div>
            <div className={`dash-stat-value${summary.data?.[item.count] ? ' is-pending' : ''}`}>{value(item.count)}</div>
          </button>
        ))}
      </div>

      <Grid
        ref={grid}
        id="standardizationQualityGrid"
        columns={qualityIssueColumns}
        dataUrl="/api/standardizationQuality"
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
        emptyText={type || schemaId || isApproval ? t('조건에 맞는 진단 결과가 없습니다.') : t('진단 결과가 없습니다. 모든 컬럼이 표준을 따르고 있습니다.')}
        renderCell={(field, row) => {
          if (field === 'type') {
            const item = TYPES.find((e) => e.type === row.type);
            return <Label variant={item?.variant}>{item?.label ?? row.type}</Label>;
          }
          if (['schemaName', 'tableName', 'columnName'].includes(field)) return <Mono>{row[field]}</Mono>;
          if (field === 'actualValue' || field === 'expectedValue') return row[field] ? <Mono>{row[field]}</Mono> : <Muted>-</Muted>;
          if (field === 'isApproval') return <Approval value={row.isApproval ? 'Y' : 'N'} />;
          if (field === 'links') return <TableLinks tableId={row.tableId} schemaId={row.schemaId} columnId={row.columnId} onChanged={reload} />;
          return undefined;
        }}
      />
    </>
  );
}
