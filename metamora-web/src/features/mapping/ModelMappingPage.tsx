import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { useLogicalModels } from '../logical/model';
import { useSchemas } from '../schema/schemas';
import { mappingEntityColumns } from './columns';
import { MappingQuality } from './MappingQuality';
import { MappingRecommendation } from './MappingRecommendation';
import { AttributeMappingDetail, EntityMappingForm } from './mappingDialogs';
import { rate, useMappingLookup, type MappingRow, type MappingStatus, type MappingTab } from './model';
import '../../styles/logical.css';
import '../../styles/mapping.css';

type Tab = MappingTab;

const initialTab = (value: string | null): Tab => (value === 'quality' || value === 'recommendation' ? value : 'mapping');

/**
 * 논리·물리 매핑 (M-7) : 논리 모델을 골라 엔터티 ↔ 테이블, 속성 ↔ 컬럼 매핑을 관리하고(매핑 관리 탭) 매핑된 쌍의 차이를 보고(정합성 비교 탭)
 * 표준 사전 기준의 추천 물리명을 본다(물리명 추천 탭).
 * 조회는 VIEWER, 매핑 등록·변경·해제 버튼은 USER 이상(model-mapping-edit) — 서버 API 가 같은 기준으로 다시 검사한다.
 * 주소 : /modelMapping?modelId=&entityId=&attributeId=&tab= — entityId 가 있으면 세 탭 모두 그 엔터티(ID)로 좁혀 보여 주고,
 * 매핑 관리 탭으로 들어왔고 매핑되어 있으면 속성 매핑 상세를 한 번 연다. attributeId 는 물리명 추천 탭을 그 속성으로 좁힌다.
 */
export function ModelMappingPage() {
  const dialog = useDialog();
  const { canView, user } = useAuth();
  const canEdit = canView('model-mapping-edit');
  const { models, selected, select, isPending, isError } = useLogicalModels();
  const { schemas } = useSchemas();
  const grid = useRef<GridHandle>(null);
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState<Tab>(() => initialTab(params.get('tab')));
  const [mapped, setMapped] = useState('');
  const [schemaId, setSchemaId] = useState('');

  const modelId = selected?.id;
  const status = useQuery({
    queryKey: ['mapping-status', user?.projectId, modelId],
    queryFn: () => api<MappingStatus[]>(`/api/modelMapping/status?modelId=${modelId}`, { silent: true }).then((list) => list[0] ?? null),
    enabled: modelId != null,
    staleTime: 0,
  });

  // 좁혀 볼 엔터티 (?entityId=) : 고른 모델의 엔터티일 때만 그 엔터티(ID)로 좁힌다. 잘못된 ID·다른 프로젝트·다른 모델의 엔터티는 무시한다
  const digits = (value: string | null) => (/^\d+$/.test(value ?? '') ? Number(value) : undefined);
  const focusId = digits(params.get('entityId'));
  const focusAttributeId = digits(params.get('attributeId'));
  const lookup = useMappingLookup({ entityId: focusId }, focusId != null);
  const focus = focusId != null && lookup.data && lookup.data.modelId === modelId ? lookup.data : null;
  const focusPending = focusId != null && lookup.isPending;
  const clearFocus = () => setParams(modelId != null ? { modelId: String(modelId) } : {}, { replace: true });

  const reload = () => {
    grid.current?.reload();
    void status.refetch();
  };

  const openDetail = (mappingId: number) => {
    dialog.open({
      title: t('속성 ↔ 컬럼 매핑'),
      className: 'dialog-xl',
      content: (controls: DialogControls) => (
        <AttributeMappingDetail mappingId={mappingId} canEdit={canEdit} controls={controls} onChanged={reload}
          onQuality={(mapping) => scope(mapping.entityId, 'quality')}
          onRecommendation={(mapping) => scope(mapping.entityId, 'recommendation')} />
      ),
    });
  };

  // 화면 안에서 한 엔터티로 좁히며 탭을 옮긴다 (주소에 남겨 새로 고쳐도 유지된다). 이미 보던 엔터티라 상세를 다시 열지 않는다
  const opened = useRef<number | null>(null);
  const scope = (entityId: number, next: Tab) => {
    if (modelId == null) return;
    opened.current = next === 'mapping' ? null : entityId;
    setTab(next);
    setParams({ modelId: String(modelId), entityId: String(entityId) }, { replace: true });
  };

  // 다른 화면에서 매핑 관리 탭으로 넘어온 엔터티가 매핑되어 있으면 상세를 한 번 연다
  useEffect(() => {
    if (!focus || tab !== 'mapping' || opened.current === focus.entityId) return;
    opened.current = focus.entityId;
    if (focus.mappingId != null) openDetail(focus.mappingId);
  }, [focus, tab]);

  const openEntityForm = (row: MappingRow) => {
    // 서버도 거절하지만(INVALID_MODEL_MAPPING) 먼저 이유와 해결 방법을 알려 준다
    if (row.mappingId != null && row.mappedAttributeCount > 0) {
      return dialog.alert(t('속성 매핑이 {0}건 있어 물리 테이블을 바꿀 수 없습니다. [속성 매핑] 에서 속성 매핑을 먼저 해제해주세요.', row.mappedAttributeCount));
    }
    dialog.open({
      title: row.mappingId != null ? t('엔터티 매핑 변경') : t('엔터티 매핑'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <EntityMappingForm row={row} onSaved={() => { close(); reload(); }} />,
    });
  };

  const removeMapping = (row: MappingRow) => {
    if (row.mappedAttributeCount > 0) {
      return dialog.alert(t('속성 매핑이 {0}건 있어 엔터티 매핑을 해제할 수 없습니다. [속성 매핑] 에서 속성 매핑을 먼저 해제해주세요.', row.mappedAttributeCount));
    }
    dialog.confirm(t('{0} 엔터티와 {1} 테이블의 매핑을 해제하시겠습니까? 엔터티와 테이블은 삭제되지 않습니다.', row.entityName, row.tableName ?? ''), async () => {
      await api(`/api/modelMapping/entity/${row.mappingId}`, { method: 'DELETE' });
      dialog.alert(t('매핑을 해제했습니다.'), reload);
    });
  };

  const goMapping = (entityId: number) => scope(entityId, 'mapping');

  const gridParams: Record<string, string> = { modelId: String(modelId ?? '') };
  if (mapped) gridParams.mapped = mapped;
  if (schemaId) gridParams.schemaId = schemaId;
  if (focus) gridParams.entityId = String(focus.entityId);
  const focusEntity = focus ? { id: focus.entityId, name: focus.entityName } : null;

  const count = (key: keyof MappingStatus) => (status.data ? status.data[key] : '-');
  const filterCard = (label: string, key: keyof MappingStatus, value: string, foot?: string) => (
    <button type="button" className="dash-stat" aria-pressed={mapped === value} onClick={() => setMapped(value)}>
      <div className="dash-stat-label">{label}</div>
      <div className={`dash-stat-value${value === 'false' && status.data?.unmappedEntities ? ' is-pending' : ''}`}>{count(key)}</div>
      <div className="mapping-stat-foot">{foot}</div>
    </button>
  );
  const plainCard = (label: string, key: keyof MappingStatus, pending = false, foot?: string) => (
    <div className="dash-stat">
      <div className="dash-stat-label">{label}</div>
      <div className={`dash-stat-value${pending && status.data?.[key] ? ' is-pending' : ''}`}>{count(key)}</div>
      <div className="mapping-stat-foot">{foot}</div>
    </div>
  );

  const tabs: [Tab, string][] = [['mapping', t('매핑 관리')], ['quality', t('정합성 비교')], ['recommendation', t('물리명 추천')]];

  return (
    <>
      <PageHeader category={t('논리 모델 관리')} title={t('논리·물리 매핑')}
        description={t('논리 엔터티·속성을 물리 테이블·컬럼에 1:1 로 연결하고, 연결된 쌍의 차이를 확인합니다. 매핑은 연결만 저장하며 논리 모델·물리 모델·표준 사전을 바꾸지 않습니다.')} />

      <div className="grid-header">
        <div className="search-container">
          <select className="search-column-select" aria-label={t('논리 모델')} value={selected?.id ?? ''} disabled={!models.length}
            onChange={(e) => select(Number(e.target.value))}>
            {!models.length && <option value="">{isPending ? t('불러오는 중입니다.') : t('논리 모델 없음')}</option>}
            {models.map((m) => <option key={m.id} value={m.id}>{m.modelName}</option>)}
          </select>
        </div>
      </div>

      {isError && <p role="alert">{t('논리 모델 목록을 불러오지 못했습니다.')}</p>}
      {!isPending && !isError && !selected && <p className="logical-empty">{t('등록된 논리 모델이 없습니다.')}</p>}

      {selected && (
        <>
          <ul className="nav nav-tabs">
            {tabs.map(([id, label]) => (
              <li className="nav-item" key={id}>
                <a href={`#mapping-${id}`} className={`nav-link${tab === id ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab(id); }}>{label}</a>
              </li>
            ))}
          </ul>

          {tab === 'mapping' ? (
            <div className="mapping-tab">
              {status.isError && <p className="gh-muted" role="alert">{t('매핑 현황을 불러오지 못했습니다.')}</p>}
              <div className="dash dash-stats quality-stats mapping-stats">
                {filterCard(t('전체 엔터티'), 'totalEntities', '')}
                {filterCard(t('매핑 완료 엔터티'), 'mappedEntities', 'true', status.data ? t('매핑률 {0}', rate(status.data.mappedEntities, status.data.totalEntities)) : undefined)}
                {filterCard(t('미매핑 엔터티'), 'unmappedEntities', 'false')}
                {plainCard(t('전체 속성'), 'totalAttributes')}
                {plainCard(t('매핑된 속성'), 'mappedAttributes', false, status.data ? t('매핑률 {0}', rate(status.data.mappedAttributes, status.data.totalAttributes)) : undefined)}
                {plainCard(t('미매핑 속성'), 'unmappedAttributes', true)}
              </div>
              <p className="gh-muted mapping-note">{t('매핑률은 연결된 비율일 뿐 정합성 점수가 아닙니다. 연결된 쌍의 차이는 [정합성 비교] 에서 확인합니다.')}</p>

              {focus && (
                <p className="mapping-focus-note" role="status">
                  {t('{0} 엔터티만 보고 있습니다.', focus.entityName)} <button type="button" className="logical-link" onClick={clearFocus}>{t('전체 보기')}</button>
                </p>
              )}

              {!focusPending && (
                <Grid
                  key={modelId}
                  ref={grid}
                  id="modelMappingGrid"
                  columns={mappingEntityColumns}
                  dataUrl="/api/modelMapping/entity"
                  params={gridParams}
                  searchFilters={
                    <>
                      <select className="search-column-select" aria-label={t('매핑 여부')} value={mapped} onChange={(e) => setMapped(e.target.value)}>
                        <option value="">{t('매핑 여부 전체')}</option>
                        <option value="true">{t('매핑 완료')}</option>
                        <option value="false">{t('미매핑')}</option>
                      </select>
                      <select className="search-column-select" aria-label={t('물리 스키마')} value={schemaId} onChange={(e) => setSchemaId(e.target.value)}>
                        <option value="">{t('전체 스키마')}</option>
                        {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
                      </select>
                    </>
                  }
                  emptyText={mapped || schemaId || focus ? t('조건에 맞는 엔터티가 없습니다.') : t('이 논리 모델에 엔터티가 없습니다.')}
                  rowClassName={(row) => (focus && row.entityId === focus.entityId ? 'mapping-focus-row' : undefined)}
                  onRowClick={(row) => { if (row.mappingId != null) openDetail(row.mappingId); }}
                  renderCell={(field, row: Row) => {
                    const item = row as MappingRow;
                    if (field === 'entityName') {
                      return <>{item.entityName}{!item.entityApproval && <> <Label variant="attention">{t('승인 대기')}</Label></>}</>;
                    }
                    if (field === 'tableName') {
                      return item.tableName ? <><Mono>{item.tableName}</Mono>{!item.tableApproval && <> <Label variant="attention">{t('승인 대기')}</Label></>}</> : <Muted>-</Muted>;
                    }
                    if (field === 'schemaName') return item.schemaName ? <Mono>{item.schemaName}</Mono> : <Muted>-</Muted>;
                    if (field === 'mapped') {
                      return item.mappingId != null ? <Label variant="success" dot>{t('매핑 완료')}</Label> : <Label variant="attention" dot>{t('미매핑')}</Label>;
                    }
                    if (field === 'mappedAttributeCount') {
                      return item.mappingId != null ? `${item.mappedAttributeCount} / ${item.attributeCount}` : <Muted>{`- / ${item.attributeCount}`}</Muted>;
                    }
                    if (field === 'actions') {
                      return (
                        <span className="logical-actions">
                          {item.mappingId != null && <button type="button" className="btn btn-sm" onClick={() => openDetail(item.mappingId!)}>{t('속성 매핑')}</button>}
                          {canEdit && item.mappingId == null && <button type="button" className="btn btn-sm btn-primary" onClick={() => openEntityForm(item)}>{t('매핑')}</button>}
                          {canEdit && item.mappingId != null && <button type="button" className="btn btn-sm" onClick={() => openEntityForm(item)}>{t('테이블 변경')}</button>}
                          {canEdit && item.mappingId != null && <button type="button" className="btn btn-sm btn-danger" onClick={() => removeMapping(item)}>{t('해제')}</button>}
                          {!canEdit && item.mappingId == null && <Muted>-</Muted>}
                        </span>
                      );
                    }
                    return undefined;
                  }}
                />
              )}
            </div>
          ) : tab === 'quality' ? (
            <div className="mapping-tab">
              <MappingQuality key={selected.id} modelId={selected.id} entity={focusPending ? null : focusEntity} onClearEntity={clearFocus} onMapping={goMapping} />
            </div>
          ) : (
            <div className="mapping-tab">
              <MappingRecommendation key={selected.id} modelId={selected.id} entity={focusPending ? null : focusEntity}
                attributeId={focus ? focusAttributeId : undefined} onClearEntity={clearFocus} onMapping={goMapping} />
            </div>
          )}
        </>
      )}
    </>
  );
}
