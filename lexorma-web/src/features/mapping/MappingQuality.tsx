import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { mappingQualityColumns } from './columns';
import { CHECK_STATUSES, CHECK_TYPES, checkTypeLabel, type CheckStatus, type CheckType, type QualityRow, type QualitySummary } from './model';

/** 진단 유형별 논리 측 값 / 물리 측 값의 이름 */
const VALUE_LABELS: Record<CheckType, [string, string]> = {
  MAPPING: [t('논리 측 값'), t('물리 측 값')],
  TERM: [t('논리 표준 용어'), t('물리 표준 용어')],
  NAME: [t('기대 컬럼명 (논리 속성 표준 용어의 영문약어)'), t('컬럼명')],
  DATA_TYPE: [t('표준 도메인 데이터 타입'), t('컬럼 데이터 타입')],
  LENGTH: [t('표준 도메인 정의'), t('컬럼 정의')],
  REQUIRED: [t('논리 필수 여부'), t('물리 NULL 허용')],
  IDENTIFIER: [t('논리 식별자'), t('물리 PK')],
};

/** 진단 결과 표시 : 불일치는 무엇이 다른지 함께 적는다 (표준 용어 불일치 등) */
export function QualityStatus({ row }: { row: Pick<QualityRow, 'type' | 'status'> }) {
  const item = CHECK_STATUSES.find((e) => e.status === row.status);
  return <Label variant={item?.variant} dot>{row.status === 'MISMATCH' ? t('{0} 불일치', checkTypeLabel(row.type)) : item?.label ?? row.status}</Label>;
}

/**
 * 논리·물리 정합성 비교 (M-7 2차, 조회 전용) : 논리 모델 1개의 매핑된 속성 ↔ 컬럼을 진단 유형별로 비교한 결과와 매핑하지 않은 엔터티·속성.
 * 요약 카드를 누르면 그 상태만 보고, 행을 누르면 상세를 본다. 논리·물리 데이터와 표준 사전은 바꾸지 않는다.
 */
export function MappingQuality({ modelId, entity, onClearEntity, onMapping }: {
  modelId: number;
  /** 한 엔터티로 좁혀 볼 때 */
  entity: { id: number; name: string } | null;
  onClearEntity: () => void;
  /** 상세의 [매핑 관리] */
  onMapping: (entityId: number) => void;
}) {
  const dialog = useDialog();
  const grid = useRef<GridHandle>(null);
  const [type, setType] = useState<CheckType | ''>('');
  const [status, setStatus] = useState<CheckStatus | ''>('');
  const summary = useQuery({
    queryKey: ['mapping-quality-summary', modelId],
    queryFn: () => api<QualitySummary>(`/api/modelMapping/quality/summary?modelId=${modelId}`, { silent: true }),
    staleTime: 0,
  });

  const params: Record<string, string> = { modelId: String(modelId) };
  if (type) params.type = type;
  if (status) params.status = status;
  if (entity) params.entityId = String(entity.id);

  const value = (count: keyof QualitySummary) => (summary.data ? summary.data[count] : '-');

  const openDetail = (row: Row) => {
    const issue = row as QualityRow;
    dialog.open({
      title: t('정합성 진단 상세'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <QualityDetail row={issue} onMapping={() => { close(); onMapping(issue.entityId); }} />,
    });
  };

  return (
    <>
      <p className="page-desc">{t('매핑된 논리 속성과 물리 컬럼을 표준 용어·명칭·데이터 타입·길이·필수 여부·식별자 기준으로 비교합니다. 매핑률과 정합성은 다릅니다 — 매핑이 끝나도 불일치가 있을 수 있습니다. 조회만 하며 아무것도 고치지 않습니다.')}</p>

      {summary.isError && <p className="gh-muted" role="alert">{t('정합성 요약을 불러오지 못했습니다.')}</p>}
      <div className="dash dash-stats quality-stats">
        <button type="button" className="dash-stat" aria-pressed={status === ''} onClick={() => setStatus('')}>
          <div className="dash-stat-label">{t('매핑된 속성')}</div>
          <div className="dash-stat-value">{value('mappedAttributes')}</div>
          <div className="mapping-stat-foot">{t('전체 속성 {0}', value('totalAttributes'))}</div>
        </button>
        {CHECK_STATUSES.map((item) => (
          <button key={item.status} type="button" className="dash-stat" aria-pressed={status === item.status} onClick={() => setStatus(item.status)}>
            <div className="dash-stat-label">{item.label}</div>
            <div className={`dash-stat-value${(item.status === 'MISMATCH' || item.status === 'UNMAPPED') && summary.data?.[item.count] ? ' is-pending' : ''}`}>{value(item.count)}</div>
            <div className="mapping-stat-foot">
              {item.status === 'UNMAPPED' ? t('엔터티 {0} · 속성 {1}', value('unmappedEntities'), value('unmappedAttributes')) : t('속성 × 진단 유형 건수')}
            </div>
          </button>
        ))}
      </div>

      {entity && (
        <p className="mapping-focus-note" role="status">
          {t('{0} 엔터티의 진단 결과만 보고 있습니다.', entity.name)} <button type="button" className="logical-link" onClick={onClearEntity}>{t('전체 보기')}</button>
        </p>
      )}

      <Grid
        ref={grid}
        id="mappingQualityGrid"
        columns={mappingQualityColumns}
        dataUrl="/api/modelMapping/quality"
        params={params}
        searchFilters={
          <>
            <select className="search-column-select" aria-label={t('진단 유형')} value={type} onChange={(e) => setType(e.target.value as CheckType | '')}>
              <option value="">{t('전체 유형')}</option>
              {CHECK_TYPES.map((item) => <option key={item.type} value={item.type}>{item.label}</option>)}
            </select>
            <select className="search-column-select" aria-label={t('진단 결과')} value={status} onChange={(e) => setStatus(e.target.value as CheckStatus | '')}>
              <option value="">{t('전체 결과')}</option>
              {CHECK_STATUSES.map((item) => <option key={item.status} value={item.status}>{item.label}</option>)}
            </select>
          </>
        }
        emptyText={type || status || entity ? t('조건에 맞는 진단 결과가 없습니다.') : t('진단 결과가 없습니다. 이 모델에 엔터티가 없습니다.')}
        onRowClick={openDetail}
        renderCell={(field, row) => {
          if (field === 'type') return checkTypeLabel(row.type);
          if (field === 'status') return <QualityStatus row={row as QualityRow} />;
          if (field === 'attributeName') return row.attributeName ?? <Muted>{t('(엔터티)')}</Muted>;
          if (field === 'columnName' || field === 'physicalValue') return row[field] ? <Mono>{row[field]}</Mono> : <Muted>-</Muted>;
          if (field === 'logicalValue' || field === 'reason') return row[field] ?? <Muted>-</Muted>;
          return undefined;
        }}
      />
    </>
  );
}

function QualityDetail({ row, onMapping }: { row: QualityRow; onMapping: () => void }) {
  const [logicalLabel, physicalLabel] = VALUE_LABELS[row.type];
  const items: [string, string | null, boolean?][] = [
    [t('진단 유형'), checkTypeLabel(row.type)],
    [t('논리 모델'), row.modelName],
    [t('논리 엔터티'), row.entityName],
    [t('논리 속성'), row.attributeName],
    [t('물리 테이블'), row.tableName ? `${row.schemaName}.${row.tableName}` : null, true],
    [t('물리 컬럼'), row.columnName, true],
    [logicalLabel, row.logicalValue],
    [physicalLabel, row.physicalValue, true],
    [t('사유'), row.reason],
  ];
  return (
    <div>
      <div className="dict-detail-status">
        <span>{t('판정')}</span>
        <QualityStatus row={row} />
        <span className="gh-mono gh-muted">{row.status}</span>
      </div>
      <dl className="dict-detail">
        {items.map(([label, value, mono]) => [
          <dt key={`t-${label}`}>{label}</dt>,
          <dd key={`d-${label}`} className={value == null ? 'gh-muted' : mono ? 'gh-mono' : undefined}>{value ?? '-'}</dd>,
        ])}
      </dl>
      <p className="form-help">
        {row.status === 'NOT_COMPARABLE' ? t('비교 기준이 되는 정보가 없어 판정하지 않았습니다. 불일치로 세지 않습니다.')
          : t('진단 결과는 조회할 때 계산합니다. 논리·물리 모델과 표준 사전은 자동으로 바뀌지 않습니다.')}
      </p>
      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={onMapping}>{t('매핑 관리에서 보기')}</button>
      </div>
    </div>
  );
}
