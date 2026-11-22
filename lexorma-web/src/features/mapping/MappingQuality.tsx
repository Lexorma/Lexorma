import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { Grid, type GridColumn, type GridHandle, type Row } from '../../components/grid/Grid';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { MAPPING_QUALITY_GRID_MIN_WIDTH, mappingQualityColumns } from './columns';
import { CHECK_GROUPS, CHECK_STATUSES, CHECK_TYPES, checkTypeLabel, type CheckGroup, type CheckStatus, type CheckType, type QualityRow, type QualitySummary, type SubtypeKeyDetail } from './model';
import { StructureView, structureCodeLabel } from './structureDetail';
import { SubtypeStructureView } from './subtypeKeys';
import { CORRECTION_MAX, CorrectionDetail, CorrectionLabel, correctionResultMessage, ForeignKeyPreview, type CorrectionItem } from './foreignKeyCorrection';
import { UNIQUE_CORRECTION_MAX, UniqueDetail, UniqueLabel, UniquePreview, uniqueResultMessage, type UniqueItem } from './uniqueCorrection';
import { INHERITANCE_CORRECTION_MAX, InheritanceDetail, InheritanceLabel, InheritancePreview, inheritanceResultMessage, type InheritanceItem } from './inheritanceFkCorrection';

/** 진단 유형별 논리 측 값 / 물리 측 값의 이름 */
const VALUE_LABELS: Record<CheckType, [string, string]> = {
  MAPPING: [t('논리 측 값'), t('물리 측 값')],
  TERM: [t('논리 표준 용어'), t('물리 표준 용어')],
  NAME: [t('기대 컬럼명 (논리 속성 표준 용어의 영문약어)'), t('컬럼명')],
  DATA_TYPE: [t('표준 도메인 데이터 타입'), t('컬럼 데이터 타입')],
  LENGTH: [t('표준 도메인 정의'), t('컬럼 정의')],
  REQUIRED: [t('논리 필수 여부'), t('물리 NULL 허용')],
  IDENTIFIER: [t('논리 식별자'), t('물리 PK')],
  INHERITANCE: [t('상속 PK 연결'), t('물리 PK')],
  RELATIONSHIP: [t('논리 관계 (부모 → 자식 속성)'), t('물리 FK')],
  ALTERNATE_KEY: [t('논리 보조 식별자'), t('물리 UNIQUE')],
  PHYSICAL_FK: [t('논리 쪽'), t('물리 FK')],
  PHYSICAL_UNIQUE: [t('논리 쪽'), t('물리 UNIQUE')],
};

type Counts = { total: number | null; match: number; mismatch: number; notComparable: number; unmapped: number };

/** 진단 범위별 요약 건수 : 범위가 없으면 예전 네 건수(속성·컬럼 + 상속 구조), 속성·컬럼 = 예전 건수 - 상속 구조, 구조 범위는 구조 요약 */
function groupCounts(summary: QualitySummary | undefined, group: CheckGroup | ''): Counts | null {
  if (!summary) return null;
  const legacy: Counts = { total: null, match: summary.match, mismatch: summary.mismatch, notComparable: summary.notComparable, unmapped: summary.unmapped };
  const inh = summary.inheritance;
  const inheritance: Counts = { total: null, match: inh?.valid ?? 0, mismatch: (inh?.mismatch ?? 0) + (inh?.invalidMappings ?? 0), notComparable: inh?.notComparable ?? 0, unmapped: inh?.unmapped ?? 0 };
  const s = summary.structure;
  const pick = (c?: { total: number; match: number; mismatch: number; notComparable: number; unmapped: number }): Counts =>
    ({ total: c?.total ?? 0, match: c?.match ?? 0, mismatch: c?.mismatch ?? 0, notComparable: c?.notComparable ?? 0, unmapped: c?.unmapped ?? 0 });
  switch (group) {
    case '': return legacy;
    case 'INHERITANCE': return inheritance;
    case 'ATTRIBUTE': return { total: null, match: legacy.match - inheritance.match, mismatch: legacy.mismatch - inheritance.mismatch,
      notComparable: legacy.notComparable - inheritance.notComparable, unmapped: legacy.unmapped - inheritance.unmapped };
    case 'RELATIONSHIP': return pick(s?.relationship);
    case 'ALTERNATE_KEY': return pick(s?.alternateKey);
    case 'PHYSICAL_ONLY': return pick(s?.physicalOnly);
    case 'ALL': {
      const parts = [s?.relationship, s?.alternateKey, s?.physicalOnly].map(pick);
      return { total: null, match: legacy.match + parts.reduce((n, c) => n + c.match, 0), mismatch: legacy.mismatch + parts.reduce((n, c) => n + c.mismatch, 0),
        notComparable: legacy.notComparable + parts.reduce((n, c) => n + c.notComparable, 0), unmapped: legacy.unmapped + parts.reduce((n, c) => n + c.unmapped, 0) };
    }
  }
}

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
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { canView } = useAuth();
  /** 누락 FK 보정 (M7-FK-01) : 편집 권한(model-mapping-edit, USER 이상)일 때만 고르기·미리보기 — 서버가 다시 검사한다 */
  const canEdit = canView('model-mapping-edit');
  const grid = useRef<GridHandle>(null);
  const [type, setType] = useState<CheckType | ''>('');
  /** 진단 범위 (M-7 구조 정합성) : 비우면 예전 목록 (속성·컬럼 + 상속 구조) */
  const [group, setGroup] = useState<CheckGroup | ''>('');
  const [status, setStatus] = useState<CheckStatus | ''>('');
  /** 고른 논리 관계 ID : 쪽·검색을 바꿔도 유지하고, 모델이 바뀌거나 [관계] 범위를 벗어나면 비운다 */
  const [picks, setPicks] = useState<Set<number>>(new Set());
  const [pageRows, setPageRows] = useState<Row[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  /**
   * 보정 범위 : [관계] = 누락 FK 보정 (M7-FK-01), [보조 식별자] = 누락 UNIQUE 보정 (M7-AK-02), [상속 구조] = 누락 상속 FK 보정 (M7-INH-03 — 대상 = 서브타입 엔터티 ID).
   * 고르기는 범위마다 따로 (범위를 바꾸면 비운다 — 서로 다른 ID 공간을 한 선택에 섞지 않는다)
   */
  const uniqueView = group === 'ALTERNATE_KEY';
  const inheritanceView = group === 'INHERITANCE';
  const relationshipView = group === 'RELATIONSHIP' || uniqueView || inheritanceView;
  const missingCode = uniqueView ? 'UNIQUE_MISSING' : 'FK_MISSING';
  const pickMax = uniqueView ? UNIQUE_CORRECTION_MAX : inheritanceView ? INHERITANCE_CORRECTION_MAX : CORRECTION_MAX;
  useEffect(() => { setPicks(new Set()); setNotice(null); }, [modelId, group]);
  /** 상속 구조 행 (서브타입 엔터티 단위 — 상속 PK 매핑 행은 제외) */
  const subtypeRow = (row: Row) => (row as QualityRow).type === 'INHERITANCE' && (row as QualityRow).attributeId == null ? (row as QualityRow) : null;
  /** 행 → 보정 대상 ID (상속 구조 = 서브타입 엔터티 ID, 그 밖 = 구조의 논리 ID). 보정 범위의 대상 행이 아니면 null */
  const targetId = (row: Row): number | null => {
    if (inheritanceView) return subtypeRow(row)?.entityId ?? null;
    const st = (row as QualityRow).structure;
    return st?.code === missingCode && st.logicalId != null ? st.logicalId : null;
  };
  /** 이 쪽의 누락 행 : 보정 가능 여부를 한 번에 묻는다 (행마다 부르지 않는다). 상속 구조는 일치하지 않는 서브타입 행만 */
  const missingIds = useMemo(() => (inheritanceView
    ? pageRows.filter((r) => subtypeRow(r) && (r as QualityRow).status !== 'MATCH').map((r) => (r as QualityRow).entityId)
    : pageRows.map((r) => (r as QualityRow).structure)
      .filter((st) => st?.kind === (uniqueView ? 'ALTERNATE_KEY' : 'RELATIONSHIP') && st.code === missingCode && st.logicalId != null).map((st) => st!.logicalId as number)),
  [pageRows, uniqueView, inheritanceView, missingCode]); // eslint-disable-line react-hooks/exhaustive-deps -- subtypeRow 는 순수 함수
  const correction = useQuery({
    queryKey: [uniqueView ? 'uk-correction-status' : inheritanceView ? 'inh-correction-status' : 'fk-correction-status', modelId, missingIds],
    queryFn: async (): Promise<(CorrectionItem | UniqueItem | InheritanceItem)[]> => (uniqueView
      ? api<UniqueItem[]>(`/api/modelMapping/quality/unique/status?modelId=${modelId}&${missingIds.map((id) => `identifierIds=${id}`).join('&')}`, { silent: true })
      : inheritanceView
        ? api<InheritanceItem[]>(`/api/modelMapping/quality/inheritanceFk/status?modelId=${modelId}&${missingIds.map((id) => `entityIds=${id}`).join('&')}`, { silent: true })
        : api<CorrectionItem[]>(`/api/modelMapping/quality/foreignKey/status?modelId=${modelId}&${missingIds.map((id) => `relationshipIds=${id}`).join('&')}`, { silent: true })),
    enabled: relationshipView && missingIds.length > 0,
    staleTime: 0,
  });
  const correctionById = useMemo(() => new Map((correction.data ?? []).map((i) => [
    'identifierId' in i ? i.identifierId : 'subtypeEntityId' in i ? i.subtypeEntityId : i.relationshipId, i] as const)), [correction.data]);
  const summary = useQuery({
    queryKey: ['mapping-quality-summary', modelId],
    queryFn: () => api<QualitySummary>(`/api/modelMapping/quality/summary?modelId=${modelId}`, { silent: true }),
    staleTime: 0,
  });

  const params: Record<string, string> = { modelId: String(modelId) };
  if (type) params.type = type;
  if (group) params.group = group;
  if (status) params.status = status;
  if (entity) params.entityId = String(entity.id);

  const value = (count: keyof QualitySummary) => (summary.data ? summary.data[count] as number | undefined ?? '-' : '-');
  const counts = groupCounts(summary.data, group);
  const structureGroup = group === 'RELATIONSHIP' || group === 'ALTERNATE_KEY' || group === 'PHYSICAL_ONLY';
  const typeOptions = group && group !== 'ALL' ? CHECK_TYPES.filter((item) => CHECK_GROUPS.find((g) => g.group === group)?.types.includes(item.type)) : CHECK_TYPES;
  const changeGroup = (next: CheckGroup | '') => {
    setGroup(next);
    if (type && next && next !== 'ALL' && !CHECK_GROUPS.find((g) => g.group === next)?.types.includes(type)) setType('');
  };

  const correctable = (row: Row) => {
    const id = targetId(row);
    return id != null && correctionById.get(id)?.correctable === true ? id : null;
  };
  const toggle = (row: Row) => {
    const id = correctable(row);
    if (id == null) return;
    setNotice(null);
    setPicks((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else if (next.size >= pickMax) { setNotice(t('한 번에 최대 {0}개까지 고를 수 있습니다. 다른 대상을 먼저 빼주세요.', pickMax)); return prev; }
      else next.add(id);
      return next;
    });
  };
  const pickPage = () => {
    setNotice(null);
    setPicks((prev) => {
      const next = new Set(prev);
      let skipped = 0;
      for (const row of pageRows) {
        const id = correctable(row);
        if (id == null || next.has(id)) continue;
        if (next.size >= pickMax) { skipped++; continue; }
        next.add(id);
      }
      if (skipped > 0) setNotice(t('최대 {0}개까지만 골랐습니다. {1}개는 고르지 않았습니다.', pickMax, skipped));
      return next;
    });
  };
  const pageIds = pageRows.map(targetId).filter((id): id is number => id != null);
  const hiddenPicks = [...picks].filter((id) => !pageIds.includes(id)).length;
  const pageSelectable = pageRows.filter((row) => correctable(row) != null).length;
  /** 적용 뒤·409 뒤 : 목록·요약·ERD·테이블 화면 캐시를 새로 읽는다 */
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['grid-data'] });
    void queryClient.invalidateQueries({ queryKey: ['mapping-quality-summary'] });
    void queryClient.invalidateQueries({ queryKey: ['fk-correction-status'] });
    void queryClient.invalidateQueries({ queryKey: ['uk-correction-status'] });
    void queryClient.invalidateQueries({ queryKey: ['inh-correction-status'] });
    void queryClient.invalidateQueries({ queryKey: ['mapping-subtype-keys'] });
    void queryClient.invalidateQueries({ predicate: (q) => typeof q.queryKey[0] === 'string' && /mapping|table|column|erd|relation/i.test(q.queryKey[0] as string) });
  };
  const openCorrection = () => {
    if (picks.size === 0) return;
    setNotice(null);
    const onRemove = (ids: number[]) => setPicks((prev) => { const next = new Set(prev); ids.forEach((id) => next.delete(id)); return next; });
    dialog.popup({
      title: uniqueView ? t('누락 UNIQUE 보정 미리보기') : inheritanceView ? t('누락 상속 FK 보정 미리보기') : t('누락 FK 보정 미리보기'),
      size: 'large',
      content: ({ close }: DialogControls) => (uniqueView
        ? <UniquePreview modelId={modelId} ids={[...picks]} onClose={close} onRemove={onRemove} onStale={refresh}
            onDone={(result) => { close(); setPicks(new Set()); refresh(); dialog.alert(uniqueResultMessage(result)); }} />
        : inheritanceView
          ? <InheritancePreview modelId={modelId} ids={[...picks]} onClose={close} onRemove={onRemove} onStale={refresh}
              onDone={(result) => { close(); setPicks(new Set()); refresh(); dialog.alert(inheritanceResultMessage(result)); }} />
        : <ForeignKeyPreview modelId={modelId} ids={[...picks]} onClose={close} onRemove={onRemove} onStale={refresh}
            onDone={(result) => { close(); setPicks(new Set()); refresh(); dialog.alert(correctionResultMessage(result)); }} />),
    });
  };
  /** [관계] 범위 : 보정 판정 칸 (편집 권한이면 앞에 고르기 칸). 칸 폭(%)은 다시 나눈다 — 좁은 화면은 가로 스크롤 */
  const gridColumns = useMemo<GridColumn[]>(() => {
    if (!relationshipView) return mappingQualityColumns;
    const widths: Record<string, string> = canEdit
      ? { select: '4%', entityName: '10%', attributeName: '14%', columnName: '12%', type: '7%', status: '10%', fkCorrection: '11%', logicalValue: '10%', physicalValue: '9%', reason: '13%' }
      : { entityName: '11%', attributeName: '15%', columnName: '13%', type: '8%', status: '11%', fkCorrection: '11%', logicalValue: '10%', physicalValue: '9%', reason: '12%' };
    const fk: GridColumn = { columnName: uniqueView ? t('UNIQUE 보정') : inheritanceView ? t('상속 FK 보정') : t('FK 보정'), column: 'fkCorrection', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: widths.fkCorrection };
    const select: GridColumn = { columnName: t('고르기'), column: 'select', columnType: 'STRING', isSearch: false, isFilter: false, rowType: 'TEXT', width: widths.select };
    const base = mappingQualityColumns.flatMap((c) => {
      const sized = { ...c, width: widths[c.column] ?? c.width };
      return c.column === 'status' ? [sized, fk] : [sized];
    });
    return canEdit ? [select, ...base] : base;
  }, [relationshipView, uniqueView, inheritanceView, canEdit]);

  const openDetail = (row: Row) => {
    const issue = row as QualityRow;
    dialog.open({
      title: t('정합성 진단 상세'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <QualityDetail row={issue} onMapping={() => { close(); onMapping(issue.entityId); }}
        onLeave={(path) => { close(); navigate(path); }} onClose={close} />,
    });
  };

  return (
    <>
      <p className="page-desc">{t('매핑된 논리 속성과 물리 컬럼을 표준 용어·명칭·데이터 타입·길이·필수 여부·식별자 기준으로 비교합니다. 매핑률과 정합성은 다릅니다 — 매핑이 끝나도 불일치가 있을 수 있습니다. 조회만 하며 아무것도 고치지 않습니다.')}{' '}
        {t('진단 범위에서 논리 관계 ↔ 물리 FK, 보조 식별자 ↔ UNIQUE, 물리에만 있는 FK·UNIQUE 도 볼 수 있습니다 (저장된 매핑으로만 대응시킵니다).')}</p>
      <div className="mapping-target" role="group" aria-label={t('진단 범위')}>
        <button type="button" className={`btn btn-sm${group === '' ? ' btn-primary' : ''}`} aria-pressed={group === ''} onClick={() => changeGroup('')}>{t('기본 (속성·컬럼·상속)')}</button>
        {CHECK_GROUPS.map((item) => (
          <button key={item.group} type="button" className={`btn btn-sm${group === item.group ? ' btn-primary' : ''}`} aria-pressed={group === item.group}
            onClick={() => changeGroup(item.group)}>{item.label}</button>
        ))}
      </div>

      {summary.isError && <p className="gh-muted" role="alert">{t('정합성 요약을 불러오지 못했습니다.')}</p>}
      {!!summary.data?.inheritanceEntities && (
        <p className="mapping-focus-note" role="note">
          {t('슈퍼타입·서브타입 구조에 참여하는 엔터티 {0}개 : 표준 용어·명칭·타입 등은 직접 정의 속성만 비교하고, 서브타입의 상속 PK·FK 물리 구조는 [상속 구조] 유형으로 따로 비교합니다. 상속 일반 속성은 복제하지 않으므로 비교하지 않습니다.', summary.data.inheritanceEntities)}
        </p>
      )}
      {!!summary.data?.inheritance && (summary.data.inheritance.subtypes > 0 || summary.data.inheritance.invalidMappings > 0) && (
        <p className="mapping-inheritance-summary" role="note">
          <strong>{t('상속 PK 현황')}</strong>{' '}
          {t('서브타입 {0}개 중 매핑 {1}개 · 상속 PK {2}개 중 연결 {3}개 (미매핑 {4}) · 유효하지 않은 기존 매핑 {5}건 · 구조 일치 {6} · 불일치 {7} · 미완성 {8} · 판단 불가 {9}',
            summary.data.inheritance.subtypes, summary.data.inheritance.mappedSubtypes, summary.data.inheritance.requiredKeys, summary.data.inheritance.mappedKeys,
            summary.data.inheritance.unmappedKeys, summary.data.inheritance.invalidMappings, summary.data.inheritance.valid, summary.data.inheritance.mismatch,
            summary.data.inheritance.unmapped, summary.data.inheritance.notComparable)}
          {' '}<button type="button" className="logical-link" onClick={() => { setType('INHERITANCE'); setStatus(''); }}>{t('상속 구조만 보기')}</button>
        </p>
      )}
      <div className="dash dash-stats quality-stats">
        <button type="button" className="dash-stat" aria-pressed={status === ''} onClick={() => setStatus('')}>
          <div className="dash-stat-label">{structureGroup ? t('진단 대상') : t('매핑된 속성')}</div>
          <div className="dash-stat-value">{structureGroup ? counts?.total ?? '-' : value('mappedAttributes')}</div>
          <div className="mapping-stat-foot">{structureGroup
            ? (group === 'PHYSICAL_ONLY' ? t('물리 FK {0} · UNIQUE {1}', summary.data?.structure?.physicalForeignKeys ?? '-', summary.data?.structure?.physicalUniques ?? '-')
              : group === 'RELATIONSHIP' ? t('논리 관계 수') : t('보조 식별자 수'))
            : t('전체 속성 {0}', value('totalAttributes'))}</div>
        </button>
        {CHECK_STATUSES.map((item) => {
          const n = counts ? counts[item.count] : null;
          return (
            <button key={item.status} type="button" className="dash-stat" aria-pressed={status === item.status} onClick={() => setStatus(item.status)}>
              <div className="dash-stat-label">{item.label}</div>
              <div className={`dash-stat-value${(item.status === 'MISMATCH' || item.status === 'UNMAPPED') && n ? ' is-pending' : ''}`}>{n ?? '-'}</div>
              <div className="mapping-stat-foot">
                {item.status === 'UNMAPPED' && (group === '' || group === 'ATTRIBUTE') ? t('엔터티 {0} · 속성 {1}', value('unmappedEntities'), value('unmappedAttributes'))
                  : item.status === 'UNMAPPED' && group === 'PHYSICAL_ONLY' ? t('대응 논리 구조 없음')
                    : item.status === 'UNMAPPED' && structureGroup ? t('대응 물리 구조 또는 매핑 없음')
                      : group === '' ? t('진단 건수 (상속 구조 포함)') : t('진단 건수')}
              </div>
            </button>
          );
        })}
      </div>

      {entity && (
        <p className="mapping-focus-note" role="status">
          {t('{0} 엔터티의 진단 결과만 보고 있습니다.', entity.name)} <button type="button" className="logical-link" onClick={onClearEntity}>{t('전체 보기')}</button>
        </p>
      )}

      {relationshipView && canEdit && (
        <div className="rename-bar" role="group" aria-label={uniqueView ? t('누락 UNIQUE 보정') : inheritanceView ? t('누락 상속 FK 보정') : t('누락 FK 보정')}>
          <span className="rename-count" role="status" aria-live="polite">
            {t('선택 {0} / 최대 {1}', picks.size, pickMax)}
            {hiddenPicks > 0 && <span className="gh-muted"> · {t('다른 쪽·검색 결과에서 고른 {0}개 포함', hiddenPicks)}</span>}
          </span>
          <button type="button" className="btn btn-sm" disabled={pageSelectable === 0} onClick={pickPage}>{uniqueView ? t('이 쪽의 보정 가능 보조 식별자 선택')
            : inheritanceView ? t('이 쪽의 보정 가능 서브타입 선택') : t('이 쪽의 보정 가능 관계 선택')}</button>
          <button type="button" className="btn btn-sm" disabled={picks.size === 0} onClick={() => { setNotice(null); setPicks(new Set()); }}>{t('선택 모두 비우기')}</button>
          <span className="rename-spacer" />
          {uniqueView
            ? <button type="button" id="btn-uk-correction-preview" className="btn btn-sm btn-primary" disabled={picks.size === 0} onClick={openCorrection}>{t('UNIQUE 보정 미리보기')}</button>
            : inheritanceView
              ? <button type="button" id="btn-inh-correction-preview" className="btn btn-sm btn-primary" disabled={picks.size === 0} onClick={openCorrection}>{t('상속 FK 보정 미리보기')}</button>
            : <button type="button" id="btn-fk-correction-preview" className="btn btn-sm btn-primary" disabled={picks.size === 0} onClick={openCorrection}>{t('FK 보정 미리보기')}</button>}
        </div>
      )}
      {relationshipView && notice && <p className="mapping-focus-note" role="status">{notice}</p>}
      {inheritanceView && (
        <p className="form-help">{t('상속 FK 보정은 상속 PK 구성·매핑·타입·상위 경로가 모두 맞고 직계 상위 테이블을 참조하는 상속 FK 만 없는 서브타입이 대상입니다. 단일·복합 모두 미승인 사용자 관계로 만들며 기존 테이블·컬럼·FK 는 바꾸지 않습니다. 다단계 상속은 위 단계부터 한 단계씩 보정합니다. 진단 결과가 불일치라도 모두 보정할 수 있는 것은 아닙니다.')}</p>
      )}
      {relationshipView && !uniqueView && !inheritanceView && (
        <p className="form-help">{t('FK 보정은 진단이 [대응 FK 없음]인 관계만 대상이며 기존 FK 는 고치거나 지우지 않습니다. 부모 PK 를 참조하는 자기 참조가 아닌 단일 FK 는 승인 정책 충돌로 보류합니다 (컬럼 관리의 참조 테이블로 지정할 수 있습니다). 보조 식별자를 참조하는 FK 는 단일도 미승인 사용자 관계로 만들며, 부모 UNIQUE 가 먼저 일치해야 합니다.')}</p>
      )}
      {uniqueView && (
        <p className="form-help">{t('UNIQUE 보정은 진단이 [대응 UNIQUE 없음]인 보조 식별자만 대상이며 식별자 속성 순서대로 미승인 UNIQUE 제약조건을 만듭니다. 기존 UNIQUE·고유 인덱스·컬럼의 고유값·필수 여부는 바꾸지 않습니다. NULL 을 허용하는 구성 컬럼은 경고만 합니다.')}</p>
      )}

      <Grid
        ref={grid}
        id="mappingQualityGrid"
        minWidth={relationshipView ? MAPPING_QUALITY_GRID_MIN_WIDTH + (canEdit ? 200 : 140) : MAPPING_QUALITY_GRID_MIN_WIDTH}
        columns={gridColumns}
        defaultSort={relationshipView && canEdit ? { column: 'entityName' } : undefined}
        onPageData={setPageRows}
        dataUrl="/api/modelMapping/quality"
        params={params}
        searchFilters={
          <>
            <select className="search-column-select" aria-label={t('진단 유형')} value={type} onChange={(e) => setType(e.target.value as CheckType | '')}>
              <option value="">{t('전체 유형')}</option>
              {typeOptions.map((item) => <option key={item.type} value={item.type}>{item.label}</option>)}
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
          if (field === 'select') {
            const target = targetId(row);
            if (target == null || (inheritanceView && (row as QualityRow).status === 'MATCH')) return null;
            const st = (row as QualityRow).structure;
            const id = correctable(row);
            const label = inheritanceView ? (row as QualityRow).entityName ?? '' : st?.logicalName ?? (row as QualityRow).logicalValue ?? '';
            return (
              <input type="checkbox" className="rename-check" checked={id != null && picks.has(id)} disabled={id == null}
                aria-label={id != null ? (uniqueView ? t('{0} UNIQUE 보정 대상으로 선택', label) : inheritanceView ? t('{0} 상속 FK 보정 대상으로 선택', label)
                  : t('{0} FK 보정 대상으로 선택', label)) : t('{0} 은(는) 고를 수 없습니다', label)}
                title={id == null ? correctionById.get(target)?.blockers.map((b) => b.reason).join('\n') : undefined}
                onClick={(e) => e.stopPropagation()} onChange={() => toggle(row)} />
            );
          }
          if (field === 'fkCorrection') {
            const target = targetId(row);
            if (target == null || (inheritanceView && (row as QualityRow).status === 'MATCH')) return <Muted>-</Muted>;
            if (correction.isError) return <Muted>{t('확인하지 못함')}</Muted>;
            const found = correctionById.get(target);
            if (!found) return <Muted>…</Muted>;
            return 'identifierId' in found ? <UniqueLabel item={found} /> : 'subtypeEntityId' in found ? <InheritanceLabel item={found} /> : <CorrectionLabel item={found} />;
          }
          if (field === 'type') return checkTypeLabel(row.type);
          if (field === 'status') return <QualityStatus row={row as QualityRow} />;
          if (field === 'attributeName') {
            const structure = (row as QualityRow).structure;
            if (structure) return structure.logicalName ?? <Muted>{structure.kind === 'PHYSICAL_FK' || structure.kind === 'PHYSICAL_UNIQUE' ? t('(대응 논리 없음)') : t('(이름 없음)')}</Muted>;
            return row.attributeName ?? <Muted>{t('(엔터티)')}</Muted>;
          }
          if (field === 'columnName' && (row as QualityRow).structure) {
            const structure = (row as QualityRow).structure!;
            return structure.physicalName ? <Mono>{structure.physicalName}</Mono> : <Muted>-</Muted>;
          }
          if (field === 'columnName' || field === 'physicalValue') return row[field] ? <Mono>{row[field]}</Mono> : <Muted>-</Muted>;
          if (field === 'logicalValue' || field === 'reason') return row[field] ?? <Muted>-</Muted>;
          return undefined;
        }}
      />
    </>
  );
}

function QualityDetail({ row, onMapping, onLeave, onClose }: { row: QualityRow; onMapping: () => void; onLeave: (path: string) => void; onClose: () => void }) {
  const [logicalLabel, physicalLabel] = VALUE_LABELS[row.type];
  // 상속 구조 행 (M-7 4차) : 구조 상세는 이 상세를 열 때만 읽는다 (목록 응답에 넣지 않는다) — 매핑 화면의 상속 PK 매핑과 같은 API·판정
  // FK 누락 관계 (M7-FK-01) : 보정 가능 여부·사유는 상세를 열 때 서버에 묻는다
  const fkMissing = row.structure?.kind === 'RELATIONSHIP' && row.structure.code === 'FK_MISSING' && row.structure.logicalId != null;
  const correction = useQuery({
    queryKey: ['fk-correction-status', row.modelId, [row.structure?.logicalId]],
    queryFn: () => api<CorrectionItem[]>(`/api/modelMapping/quality/foreignKey/status?modelId=${row.modelId}&relationshipIds=${row.structure?.logicalId}`, { silent: true }),
    enabled: fkMissing,
    gcTime: 0,
  });
  // UNIQUE 누락 보조 식별자 (M7-AK-02) : 같은 방식으로 상세를 열 때 묻는다
  const ukMissing = row.structure?.kind === 'ALTERNATE_KEY' && row.structure.code === 'UNIQUE_MISSING' && row.structure.logicalId != null;
  const uniqueCorrection = useQuery({
    queryKey: ['uk-correction-status', row.modelId, [row.structure?.logicalId]],
    queryFn: () => api<UniqueItem[]>(`/api/modelMapping/quality/unique/status?modelId=${row.modelId}&identifierIds=${row.structure?.logicalId}`, { silent: true }),
    enabled: ukMissing,
    gcTime: 0,
  });
  // 상속 구조 서브타입 행 (M7-INH-03) : 누락 상속 FK 보정 가능 여부를 상세를 열 때 묻는다 (일치하면 묻지 않는다)
  const inhTarget = row.type === 'INHERITANCE' && row.attributeId == null && row.status !== 'MATCH';
  const inheritanceCorrection = useQuery({
    queryKey: ['inh-correction-status', row.modelId, [row.entityId]],
    queryFn: () => api<InheritanceItem[]>(`/api/modelMapping/quality/inheritanceFk/status?modelId=${row.modelId}&entityIds=${row.entityId}`, { silent: true }),
    enabled: inhTarget,
    gcTime: 0,
  });
  const structure = useQuery({
    queryKey: ['mapping-subtype-keys', row.mappingId],
    queryFn: () => api<SubtypeKeyDetail>(`/api/modelMapping/entity/${enc(row.mappingId)}/subtypeKeys`, { silent: true }),
    enabled: row.type === 'INHERITANCE' && row.mappingId != null,
    gcTime: 0,
  });
  const items: [string, string | null, boolean?][] = [
    [t('진단 유형'), checkTypeLabel(row.type)],
    [t('논리 모델'), row.modelName],
    [t('논리 엔터티'), row.entityName],
    ...(row.structure ? [] : [[t('논리 속성'), row.attributeName] as [string, string | null]]),
    [t('물리 테이블'), row.tableName ? `${row.schemaName}.${row.tableName}` : null, true],
    ...(row.structure ? [] : [[t('물리 컬럼'), row.columnName, true] as [string, string | null, boolean]]),
    ...(row.structure ? [[t('상세 사유'), `${structureCodeLabel(row.structure.code)} (${row.structure.code})`] as [string, string]] : []),
    [logicalLabel, row.logicalValue],
    [physicalLabel, row.physicalValue, true],
    [t('사유'), row.reason],
    ...(row.inheritance && row.type !== 'INHERITANCE' ? [[t('상속 구조'), row.structure
      ? t('이 엔터티는 슈퍼타입·서브타입 구조에 참여합니다. 서브타입 → 슈퍼타입 상속 FK 는 [상속 관계] 진단에서 확인합니다.')
      : t('이 엔터티는 슈퍼타입·서브타입 구조에 참여합니다. 이 진단은 직접 정의 속성만 비교했습니다. 상속 PK·FK 구조는 [상속 구조] 진단에서 확인합니다.')] as [string, string]] : []),
  ];
  return (
    <div className="quality-detail">
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
      {row.structure && <StructureView row={row} structure={row.structure} onLeave={onLeave} onClose={onClose} />}
      {fkMissing && (correction.isError ? <p className="gh-muted" role="alert">{t('FK 보정 가능 여부를 확인하지 못했습니다.')}</p>
        : <CorrectionDetail item={correction.data?.[0]} />)}
      {ukMissing && (uniqueCorrection.isError ? <p className="gh-muted" role="alert">{t('UNIQUE 보정 가능 여부를 확인하지 못했습니다.')}</p>
        : <UniqueDetail item={uniqueCorrection.data?.[0]} />)}
      {inhTarget && (inheritanceCorrection.isError ? <p className="gh-muted" role="alert">{t('상속 FK 보정 가능 여부를 확인하지 못했습니다.')}</p>
        : <InheritanceDetail item={inheritanceCorrection.data?.[0]} />)}
      {row.type === 'INHERITANCE' && (
        structure.isError ? <p role="alert">{t('상속 구조 상세를 불러오지 못했습니다.')}</p>
          : !structure.data ? <p className="gh-muted" role="status">{t('불러오는 중입니다.')}</p>
            : structure.data.structure ? <SubtypeStructureView detail={structure.data} onNavigate={onLeave} />
              : <p className="gh-muted">{structure.data.statusMessage}</p>
      )}
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
