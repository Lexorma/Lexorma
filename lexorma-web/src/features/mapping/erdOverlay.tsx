import { createContext, useContext, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Label, Mono, type LabelVariant } from '../../components/grid/cells';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { checkTypeLabel, useMappingLookup, type CheckType } from './model';

/**
 * ERD 매핑 상태 표시와 논리 ↔ 물리 ERD 상호 이동 (M-7 ERD 연계). 노드는 이 문맥(context)만 읽는다 — 표시를 끄면 문맥이 null 이라 기존 노드 그대로다 (M7-E01).
 * 화면에 보이는 객체 ID 를 한 번에 보내 일괄 조회한다 (노드마다 조회하지 않는다, M7-E04).
 * M7-F02 : 매핑 상태(mappingStatus — 저장된 매핑이 있는가)와 정합성 상태(qualityStatus — 기존 정합성 비교 판정 그대로)를 따로 보인다.
 * 노드·행의 첫 기호는 매핑 상태다 (✓ 매핑됨 · – 미매핑 · ⇡ 상속 PK 매핑). 정합성 불일치만 ≠ 를 덧붙이고, 비교 불가는 설명(title)·상세에서 본다 —
 * 비교 불가를 일치로 바꾸거나 매핑 기호를 ? 로 바꾸지 않는다. 기호·글자·설명으로 보이고 색만으로 구분하지 않는다 (M7-E03).
 */

/** 예전 표시 상태 (서버 status — 뜻을 바꾸지 않고 두며 화면은 아래 두 상태를 쓴다) */
export type OverlayStatus = 'MAPPED' | 'UNMAPPED' | 'MISMATCH' | 'NOT_COMPARABLE';
/** MERGED·DISTRIBUTED (M-7 상속 A·C) : 자기 매핑 없이 A형 통합 테이블·C형 말단 테이블들에 유효하게 대응한다 */
export type MappingStatus = 'MAPPED' | 'UNMAPPED' | 'MERGED' | 'DISTRIBUTED';
/** 정합성 비교 상태 (ModelMappingQualityDto.CheckStatus) */
export type QualityStatus = 'MATCH' | 'MISMATCH' | 'NOT_COMPARABLE' | 'UNMAPPED';
export type LinkKind = 'DIRECT' | 'INHERITED_KEY' | 'INHERITED_ATTRIBUTE';
interface PhysicalTableRef { entityId: number; entityName: string | null; mappingId: number | null; schemaId: number | null; tableId: number | null; tableName: string | null }

/** 정합성 판정 1건 (기존 진단 행의 사유 그대로 — 없으면 null) */
export interface QualityIssue {
  type: CheckType;
  status: QualityStatus;
  reason: string | null;
  attributeName: string | null;
  columnName: string | null;
}

export interface AttributeLink {
  attributeId: number;
  attributeName: string;
  columnId: number;
  columnName: string;
  kind: LinkKind;
  status: OverlayStatus;
  mappingStatus: MappingStatus;
  qualityStatus: QualityStatus;
  issues: QualityIssue[];
}

/** GET /api/modelMapping/erd/entities 의 1건 */
export interface EntityOverlay {
  entityId: number;
  mapped: boolean;
  mappingId: number | null;
  schemaId: number | null;
  schemaName: string | null;
  tableId: number | null;
  tableName: string | null;
  status: OverlayStatus;
  mappingStatus: MappingStatus;
  qualityStatus: QualityStatus;
  attributeCount: number;
  mappedAttributeCount: number;
  mismatches: number;
  notComparable: number;
  attributes: AttributeLink[];
  inheritedKeys: AttributeLink[];
  /** 엔터티 단위 판정(관계·보조 식별자·상속 구조 등)의 불일치·비교 불가 */
  issues: QualityIssue[];
  /** M-7 상속 A·C : 전략과 통합·분산 테이블 (분산은 말단 테이블마다) */
  strategy?: string | null; physicalTables?: PhysicalTableRef[];
}

/** GET /api/modelMapping/erd/tables 의 1건 */
export interface TableOverlay {
  tableId: number;
  mapped: boolean;
  mappingId: number | null;
  modelId: number | null;
  modelName: string | null;
  entityId: number | null;
  entityName: string | null;
  status: OverlayStatus;
  mappingStatus: MappingStatus;
  qualityStatus: QualityStatus;
  columnCount: number;
  mappedColumnCount: number;
  mismatches: number;
  notComparable: number;
  columns: AttributeLink[];
  issues: QualityIssue[];
  /** M-7 상속 A : 통합 테이블이면 전략과 통합된 엔터티 (노드에는 수만) */
  strategy?: string | null; mergedEntities?: PhysicalTableRef[];
}

export interface MappingOverlay {
  entities: Map<number, EntityOverlay>;
  /** 논리 속성 ID → 직접 매핑 */
  attributes: Map<number, AttributeLink>;
  tables: Map<number, TableOverlay>;
  /** 물리 컬럼 ID → 매핑 (직접 · 상속 PK) */
  columns: Map<number, AttributeLink>;
}

export const MappingOverlayContext = createContext<MappingOverlay | null>(null);
export const useMappingOverlay = () => useContext(MappingOverlayContext);

export const mappingLabel = (status: MappingStatus) => (status === 'MAPPED' ? t('매핑됨') : status === 'MERGED' ? t('A형 통합') : status === 'DISTRIBUTED' ? t('C형 분산') : t('미매핑'));
const MAPPING_SYMBOL: Record<MappingStatus, string> = { MAPPED: '✓', MERGED: '⊂', DISTRIBUTED: '⋔', UNMAPPED: '–' };
export const qualityLabel = (status: QualityStatus) => ({
  MATCH: t('일치'),
  MISMATCH: t('불일치'),
  NOT_COMPARABLE: t('비교 불가'),
  UNMAPPED: t('미매핑'),
})[status];
const QUALITY_VARIANT: Record<QualityStatus, LabelVariant> = { MATCH: 'success', MISMATCH: 'danger', NOT_COMPARABLE: '', UNMAPPED: 'attention' };

const empty = (): MappingOverlay => ({ entities: new Map(), attributes: new Map(), tables: new Map(), columns: new Map() });
/** URL 길이를 줄이려고 ID 를 정렬한 쉼표 목록으로 보낸다 (서버 상한 300 = ERD 범위 상한) */
const idList = (ids: number[]) => [...new Set(ids)].sort((a, b) => a - b).slice(0, 300);

/** 논리 ERD : 보이는 엔터티의 매핑. enabled false(표시 꺼짐)면 조회하지 않고 null */
export function useEntityOverlay(modelId: number, entityIds: number[], enabled: boolean, version: number) {
  const ids = idList(entityIds);
  const query = useQuery({
    queryKey: ['erd-mapping-overlay', 'logical', modelId, ids.join(','), version],
    queryFn: ({ signal }) => api<EntityOverlay[]>(`/api/modelMapping/erd/entities?modelId=${enc(modelId)}&entityIds=${ids.join(',')}`, { signal, silent: true }),
    enabled: enabled && ids.length > 0,
    placeholderData: (prev) => prev,
    gcTime: 0,
  });
  const data = query.data;
  const overlay = useMemo(() => {
    if (!enabled || !data) return null;
    const result = empty();
    data.forEach((e) => {
      result.entities.set(e.entityId, e);
      e.attributes.forEach((a) => result.attributes.set(a.attributeId, a));
    });
    return result;
  }, [enabled, data]);
  return { overlay, isError: query.isError, isFetching: query.isFetching };
}

/** 물리 ERD : 보이는 테이블의 매핑 */
export function useTableOverlay(tableIds: number[], enabled: boolean, version: number) {
  const ids = idList(tableIds);
  const query = useQuery({
    queryKey: ['erd-mapping-overlay', 'physical', ids.join(','), version],
    queryFn: ({ signal }) => api<TableOverlay[]>(`/api/modelMapping/erd/tables?tableIds=${ids.join(',')}`, { signal, silent: true }),
    enabled: enabled && ids.length > 0,
    placeholderData: (prev) => prev,
    gcTime: 0,
  });
  const data = query.data;
  const overlay = useMemo(() => {
    if (!enabled || !data) return null;
    const result = empty();
    data.forEach((table) => {
      result.tables.set(table.tableId, table);
      table.columns.forEach((c) => result.columns.set(c.columnId, c));
    });
    return result;
  }, [enabled, data]);
  return { overlay, isError: query.isError, isFetching: query.isFetching };
}

/** 판정 사유 한 줄 : 진단 유형 · 상태 : 사유 (사유가 없으면 붙이지 않는다) */
const issueLine = (i: QualityIssue) => `${checkTypeLabel(i.type)} · ${qualityLabel(i.status)}${i.reason ? ` : ${i.reason}` : ''}`;

/** 설명(title) : 매핑 정보와 정합성 정보를 줄을 나눠 따로 적는다 */
function tooltip(mapping: string[], quality: string[]) {
  return [...mapping, ...quality].join('\n');
}

const counts = (o: { mismatches: number; notComparable: number }) => [
  o.mismatches ? t('불일치 {0}', o.mismatches) : '',
  o.notComparable ? t('비교 불가 {0}', o.notComparable) : '',
].filter(Boolean).join(' · ');

/** 노드 헤더 : 매핑 여부 표시 + 불일치가 있으면 불일치 건수만 덧붙인다 (비교 불가는 설명에서) */
export function MappingBadge({ overlay, kind }: { overlay: EntityOverlay | TableOverlay; kind: 'entity' | 'table' }) {
  const mapped = overlay.mappingStatus !== 'UNMAPPED';
  const context = kind === 'entity' ? (overlay as EntityOverlay).physicalTables ?? [] : [];
  const merged = kind === 'table' ? (overlay as TableOverlay).mergedEntities ?? [] : [];
  const target = kind === 'entity'
    ? (overlay as EntityOverlay).tableName ? `${(overlay as EntityOverlay).schemaName}.${(overlay as EntityOverlay).tableName}` : context.map((c) => c.tableName).filter(Boolean).join(', ')
    : (overlay as TableOverlay).entityName ? `${(overlay as TableOverlay).modelName} · ${(overlay as TableOverlay).entityName}${merged.length ? ` ${t('외 통합 엔터티 {0}개', merged.length)}` : ''}` : '';
  const ratio = kind === 'entity'
    ? t('속성 {0} / {1} 매핑', (overlay as EntityOverlay).mappedAttributeCount, (overlay as EntityOverlay).attributeCount)
    : t('컬럼 {0} / {1} 매핑', (overlay as TableOverlay).mappedColumnCount, (overlay as TableOverlay).columnCount);
  const title = mapped
    ? tooltip(
      [`${t('매핑 상태')} : ${mappingLabel(overlay.mappingStatus)}`, `${kind === 'entity' ? t('대응 테이블') : t('대응 엔터티')} : ${target}`, ratio],
      [`${t('정합성')} : ${qualityLabel(overlay.qualityStatus)}${counts(overlay) ? ` (${counts(overlay)})` : ''}`])
    : tooltip([`${t('매핑 상태')} : ${mappingLabel('UNMAPPED')}`, kind === 'entity' ? t('대응하는 물리 테이블이 없습니다.') : t('대응하는 논리 엔터티가 없습니다.')], []);
  return (
    // div : 헤더의 span 규칙(블록·설명 줄 여백)을 받지 않게 한다
    <div className="erd-map-badges" title={title}>
      {mapped && overlay.mismatches > 0 && <em className="erd-map-issue" aria-label={t('불일치 {0}', overlay.mismatches)}><b aria-hidden>≠</b>{overlay.mismatches}</em>}
      <em className={`erd-map-badge ${mapped ? 'is-mapped' : 'is-unmapped'}`}><b aria-hidden>{MAPPING_SYMBOL[overlay.mappingStatus] ?? '–'}</b>{mappingLabel(overlay.mappingStatus)}{merged.length ? ` +${merged.length}` : ''}</em>
    </div>
  );
}

/**
 * 행(속성·컬럼) 이름 앞 : 매핑 기호(✓ 일반 매핑 · ⇡ 상속 PK 매핑 · – 미매핑) + 불일치면 ≠.
 * 비교 불가는 기호를 바꾸지 않고 설명(title)에 판정 사유를 적는다
 */
export function MappingMark({ link, other }: { link?: AttributeLink; other: 'column' | 'attribute' }) {
  if (!link) {
    return <i className="erd-map-mark is-unmapped" title={`${t('매핑 상태')} : ${mappingLabel('UNMAPPED')}`}>–</i>;
  }
  const inherited = link.kind === 'INHERITED_KEY' || link.kind === 'INHERITED_ATTRIBUTE';
  const title = tooltip(
    [`${t('매핑 상태')} : ${mappingLabel('MAPPED')} (${link.kind === 'INHERITED_KEY' ? t('상속 PK 매핑') : link.kind === 'INHERITED_ATTRIBUTE' ? t('상속 일반 속성 매핑') : t('일반 매핑')})`,
      `${other === 'column' ? t('대응 컬럼') : t('대응 속성')} : ${other === 'column' ? link.columnName : link.attributeName}`],
    [`${t('정합성')} : ${qualityLabel(link.qualityStatus)}`, ...link.issues.map((i) => `${t('사유')} : ${issueLine(i)}`)]);
  return (
    <>
      <i className={`erd-map-mark is-mapped${inherited ? ' is-inherited' : ''}`} title={title}>{inherited ? '⇡' : '✓'}</i>
      {link.qualityStatus === 'MISMATCH' && <i className="erd-map-issue-mark" title={title} aria-label={qualityLabel('MISMATCH')}>≠</i>}
    </>
  );
}

/** 표시 토글 (기본 꺼짐) */
export function MappingToggle({ checked, onChange, loading, error }: { checked: boolean; onChange: (value: boolean) => void; loading?: boolean; error?: boolean }) {
  return (
    <label className="erd-view-toggle erd-map-toggle">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      {t('매핑 표시')}
      {checked && loading && <span className="erd-muted">{t('불러오는 중…')}</span>}
      {checked && error && <span role="alert" className="erd-map-error">{t('매핑 정보를 불러오지 못했습니다.')}</span>}
    </label>
  );
}

/** 범례 (표시가 켜졌을 때만) */
export function MappingLegend() {
  return (
    <p className="erd-map-legend">
      <strong>{t('매핑 표시')}</strong>{' '}
      <span><i className="erd-map-mark is-mapped" aria-hidden>✓</i>{t('매핑됨')}</span>
      <span><i className="erd-map-mark is-mapped is-inherited" aria-hidden>⇡</i>{t('상속 PK·상속 일반 속성 매핑')}</span>
      <span><i className="erd-map-mark is-mapped" aria-hidden>⊂</i>{t('A형 통합')}</span>
      <span><i className="erd-map-mark is-mapped" aria-hidden>⋔</i>{t('C형 분산')}</span>
      <span><i className="erd-map-mark is-unmapped" aria-hidden>–</i>{t('미매핑')}</span>
      <span><i className="erd-map-issue-mark" aria-hidden>≠</i>{t('정합성 불일치')}</span>
      <span className="erd-muted">{t('비교 불가와 판정 사유는 기호의 설명과 상세 패널에서 확인합니다.')}</span>
    </p>
  );
}

export const physicalErdPath = (schemaId: number, tableId: number) => `/physicalErd?schemaId=${enc(schemaId)}&tableId=${enc(tableId)}&mapping=1`;
export const logicalErdPath = (modelId: number, entityId: number) => `/logicalErd?modelId=${enc(modelId)}&entityId=${enc(entityId)}&mapping=1`;

/** 상세 패널의 정합성 상세 : 판정 사유를 엔터티 단위·속성 단위로 (기존 판정의 사유만 — 없으면 유형·상태만) */
function QualityDetail({ overlay, links }: { overlay: EntityOverlay | TableOverlay; links: AttributeLink[] }) {
  const lines = [
    ...overlay.issues.map((i) => ({ key: `e-${i.type}-${i.attributeName}-${i.columnName}-${i.reason}`, who: i.attributeName ?? '', text: issueLine(i) })),
    ...links.flatMap((l) => l.issues.map((i) => ({
      key: `${l.kind}-${l.attributeId}-${l.columnId}-${i.type}`,
      who: `${l.attributeName} → ${l.columnName}${l.kind === 'INHERITED_KEY' ? ` (${t('상속 PK 매핑')})` : l.kind === 'INHERITED_ATTRIBUTE' ? ` (${t('상속 일반 속성 매핑')})` : ''}`,
      text: issueLine(i),
    }))),
  ];
  return (
    <>
      <p className="erd-map-status">
        <span>{t('매핑 상태')} <Label variant="success">{mappingLabel('MAPPED')}</Label></span>
        <span>{t('정합성')} <Label variant={QUALITY_VARIANT[overlay.qualityStatus]}>{qualityLabel(overlay.qualityStatus)}</Label></span>
      </p>
      {lines.length > 0 && (
        <details className="erd-map-detail">
          <summary>{t('정합성 상세')}{counts(overlay) ? ` (${counts(overlay)})` : ''}</summary>
          <ul>
            {lines.slice(0, 100).map((l) => <li key={l.key}>{l.who && <strong>{l.who}</strong>} {l.text}</li>)}
          </ul>
        </details>
      )}
    </>
  );
}

/**
 * 논리 ERD 상세 패널의 물리 매핑 : 매핑된 테이블과 [물리 ERD에서 보기]. 표시 토글과 무관하게 선택한 엔터티 1건만 조회한다.
 * 이동 가능 여부는 매핑 여부만 본다 (정합성 비교 불가·불일치여도 매핑이 있으면 이동). 매핑이 없으면 이동 버튼을 내지 않고 안내만 한다
 */
export function EntityMappingSection({ entityId, overlay, onLeave }: { entityId: number; overlay?: EntityOverlay; onLeave: (path: string) => void }) {
  const { canView } = useAuth();
  const lookup = useMappingLookup({ entityId }, canView('model-mapping'));
  if (!canView('model-mapping')) return null;
  const row = lookup.data;
  return (
    <section className="erd-map-section" aria-label={t('물리 매핑')}>
      <h4>{t('물리 매핑')}</h4>
      {lookup.isError ? <p role="alert">{t('매핑 정보를 불러오지 못했습니다.')}</p>
        : !row ? <p className="erd-muted" role="status">{lookup.isPending ? t('불러오는 중…') : t('엔터티를 찾을 수 없습니다.')}</p>
        : (row.mappingId == null || row.tableId == null || row.schemaId == null) && row.physical?.valid && (row.physical.kind === 'MERGED' || row.physical.kind === 'DISTRIBUTED') ? (
          // A형 통합·C형 분산 (M-7 상속 A·C) : 미매핑으로 보이지 않는다. 분산 엔터티는 말단 테이블마다 이동 버튼 (대표 하나를 고르지 않는다)
          <>
            <p><Label variant="done">{mappingLabel(row.physical.kind)}</Label> {row.physical.kind === 'MERGED'
              ? t('루트 {0} 의 통합 테이블에 들어 있습니다.', row.physical.rootEntityName ?? '') : t('자기 테이블 없이 말단 테이블 {0}개에 분산되어 있습니다.', row.physical.tables.length)}</p>
            {canView('physical-erd') && (
              <div className="erd-toolbar erd-links">
                {row.physical.tables.filter((p) => p.tableId != null).map((p) => p.schemaId == null ? <Mono key={p.entityId}>{p.tableName}</Mono>
                  : <button key={p.entityId} type="button" className="btn btn-sm" onClick={() => onLeave(physicalErdPath(p.schemaId!, p.tableId!))}>{t('{0} 물리 ERD에서 보기', p.tableName ?? '')}</button>)}
              </div>
            )}
          </>
        ) : row.mappingId == null || row.tableId == null || row.schemaId == null ? (
          <p className="erd-muted"><Label variant="attention">{mappingLabel('UNMAPPED')}</Label> {t('대응하는 물리 테이블이 없습니다. 매핑 관리에서 매핑할 수 있습니다.')}</p>
        ) : (
          <>
            <p><Mono>{row.schemaName}.{row.tableName}</Mono></p>
            <p className="erd-muted">
              {t('속성 {0} / {1} 매핑', row.mappedAttributeCount, row.attributeCount)}
              {overlay && overlay.inheritedKeys.length > 0 ? ` · ${t('상속 PK 매핑 {0}', overlay.inheritedKeys.length)}` : ''}
            </p>
            {overlay?.mappingStatus === 'MAPPED' && <QualityDetail overlay={overlay} links={[...overlay.inheritedKeys, ...overlay.attributes]} />}
            {canView('physical-erd') && (
              <div className="erd-toolbar erd-links">
                <button type="button" className="btn btn-sm" onClick={() => onLeave(physicalErdPath(row.schemaId!, row.tableId!))}>{t('물리 ERD에서 보기')}</button>
              </div>
            )}
          </>
        )}
    </section>
  );
}

/** 물리 ERD 상세 패널의 논리 매핑 : 매핑된 엔터티와 [논리 ERD에서 보기] (이동은 매핑 여부만 본다) */
export function TableMappingSection({ tableId, overlay, onLeave }: { tableId: number; overlay?: TableOverlay; onLeave: (path: string) => void }) {
  const { canView } = useAuth();
  const lookup = useMappingLookup({ tableId }, canView('model-mapping'));
  if (!canView('model-mapping')) return null;
  const row = lookup.data;
  return (
    <section className="erd-map-section" aria-label={t('논리 매핑')}>
      <h4>{t('논리 매핑')}</h4>
      {lookup.isError ? <p role="alert">{t('매핑 정보를 불러오지 못했습니다.')}</p>
        : lookup.isPending ? <p className="erd-muted" role="status">{t('불러오는 중…')}</p>
        : !row || row.mappingId == null ? (
          <p className="erd-muted"><Label variant="attention">{mappingLabel('UNMAPPED')}</Label> {t('대응하는 논리 엔터티가 없습니다. 매핑 관리에서 매핑할 수 있습니다.')}</p>
        ) : (
          <>
            <p><strong>{row.entityName}</strong> <span className="erd-muted">· {row.modelName}</span></p>
            <p className="erd-muted">
              {t('속성 {0} / {1} 매핑', row.mappedAttributeCount, row.attributeCount)}
              {overlay && overlay.columns.some((c) => c.kind === 'INHERITED_KEY') ? ` · ${t('상속 PK 매핑 {0}', overlay.columns.filter((c) => c.kind === 'INHERITED_KEY').length)}` : ''}
              {overlay && overlay.columns.some((c) => c.kind === 'INHERITED_ATTRIBUTE') ? ` · ${t('상속 일반 속성 매핑 {0}', overlay.columns.filter((c) => c.kind === 'INHERITED_ATTRIBUTE').length)}` : ''}
            </p>
            {(overlay?.mergedEntities?.length ?? 0) > 0 && (
              <details className="erd-map-detail">
                <summary>{t('A형 통합 테이블 : 통합된 엔터티 {0}개', overlay!.mergedEntities!.length)}</summary>
                <ul>{overlay!.mergedEntities!.slice(0, 100).map((m) => <li key={m.entityId}>{m.entityName}</li>)}</ul>
              </details>
            )}
            {overlay?.mappingStatus === 'MAPPED' && <QualityDetail overlay={overlay} links={overlay.columns} />}
            {canView('logical-erd') && (
              <div className="erd-toolbar erd-links">
                <button type="button" className="btn btn-sm" onClick={() => onLeave(logicalErdPath(row.modelId, row.entityId))}>{t('논리 ERD에서 보기')}</button>
              </div>
            )}
          </>
        )}
    </section>
  );
}
