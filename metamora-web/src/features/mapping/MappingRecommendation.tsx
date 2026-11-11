import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { Grid, type Row } from '../../components/grid/Grid';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { termLabel } from '../column/terms';
import { TableLinks } from '../dictionary/DictionaryUsage';
import { recommendationColumnColumns, recommendationTableColumns } from './columns';
import {
  mappingPath, RECOMMENDATION_SOURCES, RECOMMENDATION_STATUSES,
  useColumnRecommendation, type ColumnRecommendation, type ColumnRecommendationSummary, type RecommendationStatus, type TableRecommendation,
} from './model';
import '../../styles/logical.css';
import '../../styles/mapping.css';

export function RecommendationStatusLabel({ status }: { status: RecommendationStatus }) {
  const item = RECOMMENDATION_STATUSES.find((e) => e.status === status);
  return <Label variant={item?.variant} dot>{item?.label ?? status}</Label>;
}

type Target = 'column' | 'table';

/**
 * 물리명 추천 (M-7 3차, 조회 전용) : 표준 사전을 근거로 한 추천 컬럼명(논리 속성의 표준 용어 영문약어)과
 * 추천 테이블명(엔터티명의 표준 단어 영문약어 + 접두어)을 현재 물리명과 나란히 보여 준다.
 * 추천은 계산 결과일 뿐 이름을 바꾸지 않는다 — 바꾸려면 테이블·컬럼 관리에서 직접 한다.
 */
export function MappingRecommendation({ modelId, entity, attributeId, onClearEntity, onMapping }: {
  modelId: number;
  /** 한 엔터티로 좁혀 볼 때 */
  entity: { id: number; name: string } | null;
  /** 한 속성으로 좁혀 볼 때 (컬럼 상세에서 넘어온 경우) */
  attributeId?: number;
  onClearEntity: () => void;
  /** 상세의 [매핑 관리에서 보기] */
  onMapping: (entityId: number) => void;
}) {
  const dialog = useDialog();
  const [target, setTarget] = useState<Target>('column');
  const [status, setStatus] = useState<RecommendationStatus | ''>('');
  const summary = useQuery({
    queryKey: ['mapping-recommendation-summary', modelId],
    queryFn: () => api<ColumnRecommendationSummary>(`/api/modelMapping/recommendation/column/summary?modelId=${modelId}`, { silent: true }),
    staleTime: 0,
  });

  const params: Record<string, string> = { modelId: String(modelId) };
  if (entity) params.entityId = String(entity.id);
  const columnParams = { ...params };
  if (status) columnParams.status = status;
  if (attributeId != null) columnParams.attributeId = String(attributeId);

  const value = (count: keyof ColumnRecommendationSummary) => (summary.data ? summary.data[count] : '-');

  const openColumn = (row: Row) => {
    const item = row as ColumnRecommendation;
    dialog.open({
      title: t('물리 컬럼명 추천'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <ColumnRecommendationDetail row={item} close={close} onMapping={() => { close(); onMapping(item.entityId); }} />,
    });
  };
  const openTable = (row: Row) => {
    const item = row as TableRecommendation;
    dialog.open({
      title: t('물리 테이블명 추천'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <TableRecommendationDetail row={item} close={close} onMapping={() => { close(); onMapping(item.logicalEntityId); }} />,
    });
  };

  const name = (value: string | null) => (value ? <Mono>{value}</Mono> : <Muted>-</Muted>);
  const targets: [Target, string][] = [['column', t('컬럼명')], ['table', t('테이블명')]];

  return (
    <>
      <p className="page-desc">{t('표준 사전을 근거로 물리 이름을 추천합니다. 컬럼명은 논리 속성에 연결된 표준 용어의 영문약어, 테이블명은 엔터티명을 이루는 승인된 표준 단어의 영문약어입니다. 근거가 없으면 이름을 만들지 않습니다. 조회만 하며 테이블·컬럼의 이름을 바꾸지 않습니다.')}</p>

      <div className="mapping-target" role="tablist" aria-label={t('추천 대상')}>
        {targets.map(([id, label]) => (
          <button key={id} type="button" role="tab" className={`btn btn-sm${target === id ? ' btn-primary' : ''}`} aria-selected={target === id} onClick={() => setTarget(id)}>{label}</button>
        ))}
      </div>

      {(entity || (attributeId != null && target === 'column')) && (
        <p className="mapping-focus-note" role="status">
          {attributeId != null && target === 'column' ? t('선택한 속성의 추천 결과만 보고 있습니다.') : t('{0} 엔터티의 추천 결과만 보고 있습니다.', entity?.name ?? '')}
          {' '}<button type="button" className="logical-link" onClick={onClearEntity}>{t('전체 보기')}</button>
        </p>
      )}

      {target === 'column' ? (
        <>
          {summary.isError && <p className="gh-muted" role="alert">{t('추천 요약을 불러오지 못했습니다.')}</p>}
          <div className="dash dash-stats quality-stats">
            <button type="button" className="dash-stat" aria-pressed={status === ''} onClick={() => setStatus('')}>
              <div className="dash-stat-label">{t('전체 속성')}</div>
              <div className="dash-stat-value">{value('total')}</div>
              <div className="mapping-stat-foot" />
            </button>
            {RECOMMENDATION_STATUSES.map((item) => (
              <button key={item.status} type="button" className="dash-stat" aria-pressed={status === item.status} onClick={() => setStatus(item.status)}>
                <div className="dash-stat-label">{item.label}</div>
                <div className={`dash-stat-value${(item.status === 'AVAILABLE' || item.status === 'CONFLICT') && summary.data?.[item.count] ? ' is-pending' : ''}`}>{value(item.count)}</div>
                <div className="mapping-stat-foot">{RECOMMENDATION_HINTS[item.status]}</div>
              </button>
            ))}
          </div>
          <Grid
            key="column"
            id="recommendationColumnGrid"
            columns={recommendationColumnColumns}
            dataUrl="/api/modelMapping/recommendation/column"
            params={columnParams}
            searchFilters={
              <select className="search-column-select" aria-label={t('추천 상태')} value={status} onChange={(e) => setStatus(e.target.value as RecommendationStatus | '')}>
                <option value="">{t('전체 상태')}</option>
                {RECOMMENDATION_STATUSES.map((item) => <option key={item.status} value={item.status}>{item.label}</option>)}
              </select>
            }
            emptyText={status || entity || attributeId != null ? t('조건에 맞는 추천 결과가 없습니다.') : t('추천 결과가 없습니다. 이 모델에 속성이 없습니다.')}
            onRowClick={openColumn}
            renderCell={(field, row) => {
              if (field === 'termName') return row.termName ? termLabel({ commonStandardTermName: row.termName }) : <Muted>-</Muted>;
              if (field === 'currentPhysicalName' || field === 'recommendedPhysicalName') return name(row[field]);
              if (field === 'status') return <RecommendationStatusLabel status={row.status} />;
              return undefined;
            }}
          />
        </>
      ) : (
        <Grid
          key="table"
          id="recommendationTableGrid"
          columns={recommendationTableColumns}
          dataUrl="/api/modelMapping/recommendation/table"
          params={params}
          emptyText={entity ? t('조건에 맞는 추천 결과가 없습니다.') : t('추천 결과가 없습니다. 이 모델에 엔터티가 없습니다.')}
          onRowClick={openTable}
          renderCell={(field, row) => {
            if (field === 'currentPhysicalName' || field === 'recommendedPhysicalName') return name(row[field]);
            if (field === 'status') return <RecommendationStatusLabel status={row.status} />;
            return undefined;
          }}
        />
      )}
    </>
  );
}

const RECOMMENDATION_HINTS: Record<RecommendationStatus, string> = {
  AVAILABLE: t('추천명이 현재 이름과 다르거나 매핑이 없음'),
  ALREADY_MATCHED: t('현재 이름 = 추천명'),
  UNAVAILABLE: t('표준 용어·영문약어 없음'),
  CONFLICT: t('같은 테이블의 다른 컬럼이 사용 중'),
};

const READ_ONLY_NOTE = t('추천은 조회할 때 계산한 결과입니다. 이름은 자동으로 바뀌지 않으며, 바꾸려면 테이블·컬럼 관리에서 직접 수정합니다.');

function DetailList({ items }: { items: [string, string | null, boolean?][] }) {
  return (
    <dl className="dict-detail">
      {items.map(([label, value, mono]) => [
        <dt key={`t-${label}`}>{label}</dt>,
        <dd key={`d-${label}`} className={value == null || value === '' ? 'gh-muted' : mono ? 'gh-mono' : undefined}>{value == null || value === '' ? '-' : value}</dd>,
      ])}
    </dl>
  );
}

function ColumnRecommendationDetail({ row, close, onMapping }: { row: ColumnRecommendation; close: () => void; onMapping: () => void }) {
  const navigate = useNavigate();
  const { canView } = useAuth();
  return (
    <div>
      <div className="dict-detail-status">
        <span>{t('추천 상태')}</span>
        <RecommendationStatusLabel status={row.status} />
        <span className="gh-mono gh-muted">{row.status}</span>
      </div>
      <DetailList items={[
        [t('논리 모델'), row.modelName],
        [t('논리 엔터티'), row.entityName],
        [t('논리 속성'), row.logicalName],
        [t('표준 용어'), row.termName ? termLabel({ commonStandardTermName: row.termName }) : null],
        [t('표준 용어 영문약어'), row.termAbbreviation, true],
        [t('물리 테이블'), row.tableName ? `${row.schemaName}.${row.tableName}` : null, true],
        [t('현재 물리 컬럼명'), row.currentPhysicalName, true],
        [t('추천 물리 컬럼명'), row.recommendedPhysicalName, true],
        [t('추천 기준'), row.source ? RECOMMENDATION_SOURCES[row.source] : null],
        [t('추천 근거'), row.reason],
      ]} />
      <p className="form-help">{READ_ONLY_NOTE}</p>
      <div className="gh-dialog-actions">
        {row.tableId != null && row.schemaId != null && <TableLinks tableId={row.tableId} schemaId={row.schemaId} columnId={row.physicalColumnId ?? undefined} onNavigate={close} />}
        {canView('column-manage') && row.tableName && (
          <button type="button" className="btn" onClick={() => { close(); navigate(`/columnManage?searchColumn=tableName&searchQuery=${enc(row.tableName)}`); }}>{t('컬럼 관리에서 보기')}</button>
        )}
        <button type="button" className="btn" onClick={onMapping}>{t('매핑 관리에서 보기')}</button>
      </div>
    </div>
  );
}

const WORD_STATUS: Record<TableRecommendation['words'][number]['status'], string> = {
  APPROVED: t('승인된 표준 단어'),
  NOT_APPROVED: t('승인되지 않은 표준 단어'),
  NOT_FOUND: t('등록되지 않은 단어'),
};

function TableRecommendationDetail({ row, close, onMapping }: { row: TableRecommendation; close: () => void; onMapping: () => void }) {
  return (
    <div>
      <div className="dict-detail-status">
        <span>{t('추천 상태')}</span>
        <RecommendationStatusLabel status={row.status} />
        <span className="gh-mono gh-muted">{row.status}</span>
      </div>
      <DetailList items={[
        [t('논리 모델'), row.modelName],
        [t('논리 엔터티'), row.logicalName],
        [t('테이블 접두어'), row.tablePrefix, true],
        [t('현재 물리 테이블명'), row.currentPhysicalName ? `${row.schemaName}.${row.currentPhysicalName}` : null, true],
        [t('추천 물리 테이블명'), row.recommendedPhysicalName, true],
        [t('추천 기준'), row.source ? RECOMMENDATION_SOURCES[row.source] : null],
        [t('추천 근거'), row.reason],
      ]} />
      <h4 className="mapping-subtitle">{t('엔터티명의 단어 ({0})', row.words.length)}</h4>
      {row.words.length === 0 ? <p className="gh-muted">{t('엔터티명에서 단어를 찾지 못했습니다.')}</p> : (
        <p className="mapping-chips">
          {row.words.map((word, index) => (
            <Label key={index} variant={word.status === 'APPROVED' ? 'success' : word.status === 'NOT_APPROVED' ? 'attention' : 'danger'} title={WORD_STATUS[word.status]}>
              {word.word}{word.abbreviation ? <> → <span className="gh-mono">{word.abbreviation}</span></> : <> · {WORD_STATUS[word.status]}</>}
            </Label>
          ))}
        </p>
      )}
      <p className="form-help">{READ_ONLY_NOTE}</p>
      <div className="gh-dialog-actions">
        {row.physicalTableId != null && row.schemaId != null && <TableLinks tableId={row.physicalTableId} schemaId={row.schemaId} onNavigate={close} />}
        <button type="button" className="btn" onClick={onMapping}>{t('매핑 관리에서 보기')}</button>
      </div>
    </div>
  );
}

/**
 * 컬럼 상세에 붙이는 추천 표시 (컬럼 관리·물리 ERD 공용) : 이 컬럼에 매핑된 논리 속성과 추천 컬럼명, 현재 이름과의 비교.
 * 매핑 화면을 볼 수 없는 권한이면 조회하지 않고 아무것도 그리지 않는다.
 */
export function ColumnRecommendationNote({ columnId, onNavigate }: { columnId: number; onNavigate: (path: string) => void }) {
  const { canView } = useAuth();
  const visible = canView('model-mapping');
  const recommendation = useColumnRecommendation(columnId, visible);
  if (!visible || recommendation.isPending || recommendation.isError) return null;
  const row = recommendation.data;
  return (
    <div className="mapping-recommendation-note">
      <strong>{t('물리명 추천')}</strong>
      {row ? (
        <>
          <span>{t('논리 속성 {0} · {1}', row.entityName, row.logicalName)}</span>
          <RecommendationStatusLabel status={row.status} />
          {row.recommendedPhysicalName && <span>{t('추천 컬럼명')} <Mono>{row.recommendedPhysicalName}</Mono>{row.status !== 'ALREADY_MATCHED' && <> ← {t('현재')} <Mono>{row.currentPhysicalName}</Mono></>}</span>}
          <span className="gh-muted">{row.reason}</span>
          <button type="button" className="logical-link" onClick={() => onNavigate(mappingPath(row.modelId, row.entityId, { tab: 'recommendation', attributeId: row.logicalAttributeId }))}>{t('물리명 추천에서 보기')}</button>
        </>
      ) : <span className="gh-muted">{t('논리 속성에 매핑되지 않은 컬럼이라 추천이 없습니다.')}</span>}
    </div>
  );
}
