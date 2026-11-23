import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { Grid, type GridColumn, type Row } from '../../components/grid/Grid';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { termLabel } from '../column/terms';
import { TableLinks } from '../dictionary/DictionaryUsage';
import { RECOMMENDATION_COLUMN_GRID_MIN_WIDTH, RECOMMENDATION_TABLE_GRID_MIN_WIDTH, recommendationColumnColumns, recommendationInheritedColumns, recommendationTableColumns } from './columns';
import {
  inheritanceLabel, mappingPath, RECOMMENDATION_SOURCES, RECOMMENDATION_STATUSES,
  useColumnRecommendation, type ColumnRecommendation, type ColumnRecommendationSummary, type RecommendationStatus, type TableRecommendation,
} from './model';
import {
  ApplicabilityDetail, ApplicabilityLabel, listSelectable, pickKey, RENAME_MAX, RenamePreview, renameResultMessage, type Applicability, type RenamePick, type RenameTarget,
} from './physicalRename';
import '../../styles/logical.css';
import '../../styles/mapping.css';

export function RecommendationStatusLabel({ status }: { status: RecommendationStatus }) {
  const item = RECOMMENDATION_STATUSES.find((e) => e.status === status);
  return <Label variant={item?.variant} dot>{item?.label ?? status}</Label>;
}

/** inherited (M7-NAME-1) : 상속 매핑으로 이어진 컬럼 — B형 상속 PK · C형 복제 컬럼, 읽기 전용 (고를 수 없다) */
type Target = 'column' | 'table' | 'inherited';

/**
 * 물리명 추천 (M-7 3차, 조회 전용) : 표준 사전을 근거로 한 추천 컬럼명(논리 속성의 표준 용어 영문약어)과
 * 추천 테이블명(엔터티명의 표준 단어 영문약어 + 접두어)을 현재 물리명과 나란히 보여 준다.
 * 추천 목록은 계산 결과일 뿐이다. 편집 권한(model-mapping-edit)이면 행을 골라 일괄 적용 미리보기 → 확인 → 적용으로 물리명을 바꿀 수 있다 (M-7 3차, physicalRename.tsx).
 * 선택은 종류 + 물리 ID 키로 페이지·검색·탭을 넘어 유지하고, 모델이 바뀌면 이 컴포넌트가 다시 만들어져 비워진다 (ModelMappingPage 의 key).
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
  const queryClient = useQueryClient();
  const { canView } = useAuth();
  /** 일괄 적용(M-7 3차) : 편집 권한(model-mapping-edit, USER 이상)일 때만 선택·미리보기를 보인다 — 서버가 다시 검사한다 */
  const canEdit = canView('model-mapping-edit');
  const [target, setTarget] = useState<Target>('column');
  /** 고른 대상 : 종류 + 물리 ID 키. 페이지·검색·탭을 바꿔도 유지하고, 모델이 바뀌면(이 컴포넌트를 모델 ID 로 다시 만든다) 비워진다 */
  const [picks, setPicks] = useState<Map<string, RenamePick>>(new Map());
  /** 지금 보이는 쪽의 행 (탭별) */
  const [pageRows, setPageRows] = useState<Row[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
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

  const value = (count: 'total' | 'available' | 'alreadyMatched' | 'unavailable' | 'conflict') => (summary.data ? summary.data[count] : '-');

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
  const targets: [Target, string][] = [['column', t('컬럼명')], ['table', t('테이블명')], ['inherited', t('상속 컬럼 (읽기 전용)')]];
  const inheritedParams = { ...columnParams, scope: 'INHERITED' };

  // ---------------------------------------------------------------- 일괄 적용 선택
  const pickOf = (row: Row): RenamePick | null => {
    if (target === 'inherited' || row.inheritance) return null; // 상속 컬럼은 읽기 전용 (서버도 INHERITED_READ_ONLY 로 막는다)
    const type: RenameTarget = target === 'column' ? 'COLUMN' : 'TABLE';
    const id = target === 'column' ? row.physicalColumnId : row.physicalTableId;
    if (id == null || !row.recommendedPhysicalName || !listSelectable(row.applicability as Applicability)) return null;
    const label = target === 'column' ? `${row.tableName ?? ''}.${row.currentPhysicalName ?? ''}` : String(row.currentPhysicalName ?? '');
    return { key: pickKey(type, id), type, id, recommendedName: row.recommendedPhysicalName, currentName: row.currentPhysicalName ?? '', label };
  };
  const rowKey = (row: Row) => {
    if (target === 'inherited') return null;
    const id = target === 'column' ? row.physicalColumnId : row.physicalTableId;
    return id == null ? null : pickKey(target === 'column' ? 'COLUMN' : 'TABLE', id);
  };
  const toggle = (row: Row) => {
    const pick = pickOf(row);
    if (!pick) return;
    setNotice(null);
    setPicks((prev) => {
      const next = new Map(prev);
      if (next.has(pick.key)) next.delete(pick.key);
      else if (next.size >= RENAME_MAX) { setNotice(t('한 번에 최대 {0}개까지 고를 수 있습니다. 다른 대상을 먼저 빼주세요.', RENAME_MAX)); return prev; }
      else next.set(pick.key, pick);
      return next;
    });
  };
  /** 이 쪽의 고를 수 있는 행만 (검색 결과 전체를 고르지 않는다) */
  const pickPage = () => {
    setNotice(null);
    setPicks((prev) => {
      const next = new Map(prev);
      let skipped = 0;
      for (const row of pageRows) {
        const pick = pickOf(row);
        if (!pick || next.has(pick.key)) continue;
        if (next.size >= RENAME_MAX) { skipped++; continue; }
        next.set(pick.key, pick);
      }
      if (skipped > 0) setNotice(t('최대 {0}개까지만 골랐습니다. {1}개는 고르지 않았습니다.', RENAME_MAX, skipped));
      return next;
    });
  };
  const unpickPage = () => {
    setNotice(null);
    setPicks((prev) => {
      const next = new Map(prev);
      pageRows.forEach((row) => { const key = rowKey(row); if (key) next.delete(key); });
      return next;
    });
  };
  const visibleKeys = useMemo(() => new Set(pageRows.map((row) => rowKey(row)).filter((k): k is string => k != null)), [pageRows, target]); // eslint-disable-line react-hooks/exhaustive-deps
  const hiddenPicks = [...picks.keys()].filter((key) => !visibleKeys.has(key)).length;
  const pageSelectable = pageRows.filter((row) => pickOf(row) != null).length;
  const pageAllPicked = pageSelectable > 0 && pageRows.every((row) => { const p = pickOf(row); return !p || picks.has(p.key); });

  /** 목록·요약을 새로 읽는다 (적용 뒤, 409 뒤) — 매핑·테이블·컬럼 화면의 캐시도 함께 */
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['grid-data'] });
    void queryClient.invalidateQueries({ queryKey: ['mapping-recommendation-summary'] });
    void queryClient.invalidateQueries({ predicate: (q) => typeof q.queryKey[0] === 'string' && /mapping|table|column|erd/i.test(q.queryKey[0] as string) });
  };
  const openPreview = () => {
    if (picks.size === 0) return;
    setNotice(null);
    dialog.popup({
      title: t('물리명 일괄 적용 미리보기'),
      size: 'large',
      content: ({ close }: DialogControls) => (
        <RenamePreview picks={[...picks.values()]} onClose={close}
          onRemove={(keys) => setPicks((prev) => { const next = new Map(prev); keys.forEach((k) => next.delete(k)); return next; })}
          onStale={refresh}
          onDone={(result) => { close(); setPicks(new Map()); refresh(); dialog.alert(renameResultMessage(result)); }} />
      ),
    });
  };

  // 선택 칸이 맨 앞에 오므로 처음 정렬을 예전 첫 칸(엔터티명·논리 엔터티)으로 정해 준다 (Grid 는 정하지 않으면 첫 칸으로 정렬 — 선택 칸은 서버 정렬 항목이 아니다)
  const selectColumn: GridColumn = { columnName: t('고르기'), column: 'select', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' };
  const applyColumn: GridColumn = { columnName: t('일괄 적용'), column: 'applicability', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT' };
  /** 편집 권한일 때 : 앞에 선택, 상태 뒤에 일괄 적용 판정. 칸 폭(%)은 합 100 이 되게 다시 나눈다 (표 최소 폭도 넓힌다 — 좁은 화면은 가로 스크롤) */
  const withRename = (columns: GridColumn[], widths: Record<string, string>) => canEdit
    ? [{ ...selectColumn, width: widths.select }, ...columns.flatMap((c) => {
      const sized = { ...c, width: widths[c.column] ?? c.width };
      return c.column === 'status' ? [sized, { ...applyColumn, width: widths.applicability }] : [sized];
    })]
    : columns;
  const columnGridColumns = useMemo(() => withRename(recommendationColumnColumns,
    { select: '4%', entityName: '11%', logicalName: '12%', termName: '11%', currentPhysicalName: '13%', recommendedPhysicalName: '13%', status: '9%', applicability: '12%', reason: '15%' }), [canEdit]); // eslint-disable-line react-hooks/exhaustive-deps
  const tableGridColumns = useMemo(() => withRename(recommendationTableColumns,
    { select: '4%', logicalName: '15%', currentPhysicalName: '16%', recommendedPhysicalName: '16%', status: '10%', applicability: '13%', reason: '26%' }), [canEdit]); // eslint-disable-line react-hooks/exhaustive-deps

  const renderRename = (field: string, row: Row) => {
    if (field === 'select') {
      const pick = pickOf(row);
      const key = rowKey(row);
      const label = target === 'column' ? `${row.tableName ?? ''}.${row.currentPhysicalName ?? row.logicalName}` : String(row.currentPhysicalName ?? row.logicalName);
      return (
        <input type="checkbox" className="rename-check" checked={key != null && picks.has(key)} disabled={!pick}
          aria-label={pick ? t('{0} 일괄 적용 대상으로 선택', label) : t('{0} 은(는) 고를 수 없습니다', label)}
          title={pick ? undefined : (row.applicability as Applicability | undefined)?.blockers.map((b) => b.reason).join('\n')}
          onChange={() => toggle(row)} />
      );
    }
    if (field === 'applicability') return <ApplicabilityLabel value={row.applicability as Applicability} />;
    return undefined;
  };

  const renameBar = canEdit && (
    <div className="rename-bar" role="group" aria-label={t('물리명 일괄 적용')}>
      <span className="rename-count" role="status" aria-live="polite">
        {t('선택 {0} / 최대 {1}', picks.size, RENAME_MAX)}
        {hiddenPicks > 0 && <> · {t('지금 목록에 보이지 않는 선택 {0}개', hiddenPicks)}</>}
      </span>
      <button type="button" className="btn btn-sm" disabled={pageSelectable === 0 || pageAllPicked} onClick={pickPage}>{t('이 쪽의 적용 가능 항목 선택')}</button>
      <button type="button" className="btn btn-sm" disabled={!pageRows.some((row) => { const k = rowKey(row); return k != null && picks.has(k); })} onClick={unpickPage}>{t('이 쪽 선택 해제')}</button>
      <button type="button" className="btn btn-sm" disabled={picks.size === 0} onClick={() => { setNotice(null); setPicks(new Map()); }}>{t('선택 모두 비우기')}</button>
      <span className="rename-spacer" />
      <button type="button" id="btn-rename-preview" className="btn btn-sm btn-primary" disabled={picks.size === 0} onClick={openPreview}>{t('일괄 적용 미리보기')}</button>
      {notice && <span className="gh-muted" role="alert">{notice}</span>}
    </div>
  );

  return (
    <>
      <p className="page-desc">{t('표준 사전을 근거로 물리 이름을 추천합니다. 컬럼명은 논리 속성에 연결된 표준 용어의 영문약어, 테이블명은 엔터티명을 이루는 승인된 표준 단어의 영문약어입니다. 근거가 없으면 이름을 만들지 않습니다.')}{' '}
        {canEdit ? t('적용할 대상을 고르고 미리보기에서 확인한 뒤에만 물리명이 바뀝니다 (테이블 + 컬럼 최대 {0}개, 전체 성공 또는 전체 취소).', RENAME_MAX) : t('조회만 하며 테이블·컬럼의 이름을 바꾸지 않습니다.')}</p>

      <div className="mapping-target" role="tablist" aria-label={t('추천 대상')}>
        {targets.map(([id, label]) => (
          <button key={id} type="button" role="tab" className={`btn btn-sm${target === id ? ' btn-primary' : ''}`} aria-selected={target === id} onClick={() => { if (id !== target) setPageRows([]); setTarget(id); }}>{label}</button>
        ))}
      </div>

      {(entity || (attributeId != null && target === 'column')) && (
        <p className="mapping-focus-note" role="status">
          {attributeId != null && target === 'column' ? t('선택한 속성의 추천 결과만 보고 있습니다.') : t('{0} 엔터티의 추천 결과만 보고 있습니다.', entity?.name ?? '')}
          {' '}<button type="button" className="logical-link" onClick={onClearEntity}>{t('전체 보기')}</button>
        </p>
      )}

      {target !== 'inherited' && renameBar}

      {target === 'inherited' ? (
        <>
          <p className="form-help">{t('B형 서브타입 테이블의 상속 PK 와 C형 말단 테이블에 복제한 루트 PK·조상 속성 컬럼입니다. 원본 속성의 표준 용어 영문약어로 추천·진단만 보이며, 이 목록에서는 이름을 바꿀 수 없습니다.')}</p>
          {summary.data?.inherited && (
            <p className="gh-muted" role="status">
              {t('상속 컬럼 {0}개 · 추천 가능 {1} · 일치 {2} · 추천 불가 {3} · 충돌 {4}', summary.data.inherited.total, summary.data.inherited.available,
                summary.data.inherited.alreadyMatched, summary.data.inherited.unavailable, summary.data.inherited.conflict)}
            </p>
          )}
          <Grid
            key="inherited"
            id="recommendationInheritedGrid"
            minWidth={RECOMMENDATION_COLUMN_GRID_MIN_WIDTH + 120}
            columns={recommendationInheritedColumns}
            defaultSort={{ column: 'entityName' }}
            onPageData={setPageRows}
            dataUrl="/api/modelMapping/recommendation/column"
            params={inheritedParams}
            searchFilters={
              <select className="search-column-select" aria-label={t('추천 상태')} value={status} onChange={(e) => setStatus(e.target.value as RecommendationStatus | '')}>
                <option value="">{t('전체 상태')}</option>
                {RECOMMENDATION_STATUSES.map((item) => <option key={item.status} value={item.status}>{item.label}</option>)}
              </select>
            }
            emptyText={status || entity ? t('조건에 맞는 추천 결과가 없습니다.') : t('상속 매핑으로 이어진 컬럼이 없습니다.')}
            onRowClick={openColumn}
            renderCell={(field, row) => {
              const inheritance = row.inheritance as ColumnRecommendation['inheritance'];
              if (field === 'originEntityName') return inheritance ? inheritance.originEntityName : <Muted>-</Muted>;
              if (field === 'tableName') return name(row.tableName);
              if (field === 'strategy') return inheritance ? <Label title={inheritance.readOnlyReason}>{inheritanceLabel(inheritance)}</Label> : <Muted>-</Muted>;
              if (field === 'currentPhysicalName' || field === 'recommendedPhysicalName') return name(row[field]);
              if (field === 'status') return <RecommendationStatusLabel status={row.status} />;
              return undefined;
            }}
          />
        </>
      ) : target === 'column' ? (
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
            minWidth={canEdit ? RECOMMENDATION_COLUMN_GRID_MIN_WIDTH + 200 : RECOMMENDATION_COLUMN_GRID_MIN_WIDTH}
            columns={columnGridColumns}
            defaultSort={{ column: 'entityName' }}
            onPageData={setPageRows}
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
              const rename = renderRename(field, row);
              if (rename !== undefined) return rename;
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
          minWidth={canEdit ? RECOMMENDATION_TABLE_GRID_MIN_WIDTH + 200 : RECOMMENDATION_TABLE_GRID_MIN_WIDTH}
          columns={tableGridColumns}
          defaultSort={{ column: 'logicalName' }}
          onPageData={setPageRows}
          dataUrl="/api/modelMapping/recommendation/table"
          params={params}
          emptyText={entity ? t('조건에 맞는 추천 결과가 없습니다.') : t('추천 결과가 없습니다. 이 모델에 엔터티가 없습니다.')}
          onRowClick={openTable}
          renderCell={(field, row) => {
            const rename = renderRename(field, row);
            if (rename !== undefined) return rename;
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

const READ_ONLY_NOTE = t('추천은 조회할 때 계산한 결과입니다. 이름은 자동으로 바뀌지 않습니다 — 편집 권한이 있으면 목록에서 골라 미리보기로 확인한 뒤 일괄 적용할 수 있습니다.');

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
        ...(row.inheritance ? [
          [t('상속 전략'), inheritanceLabel(row.inheritance)],
          [t('원본 엔터티'), row.inheritance.originEntityName],
          [t('읽기 전용 사유'), row.inheritance.readOnlyReason],
        ] as [string, string | null][] : []),
      ]} />
      <ApplicabilityDetail value={row.applicability} />
      {/* 상속 컬럼은 위 [읽기 전용 사유]·일괄 적용 판정에 이미 사유가 있다 — 같은 문장을 세 번 보이지 않는다 */}
      {!row.inheritance && <p className="form-help">{READ_ONLY_NOTE}</p>}
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
      <ApplicabilityDetail value={row.applicability} />
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
