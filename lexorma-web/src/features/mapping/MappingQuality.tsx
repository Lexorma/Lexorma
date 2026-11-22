import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { MAPPING_QUALITY_GRID_MIN_WIDTH, mappingQualityColumns } from './columns';
import { CHECK_GROUPS, CHECK_STATUSES, CHECK_TYPES, checkTypeLabel, type CheckGroup, type CheckStatus, type CheckType, type QualityRow, type QualitySummary, type SubtypeKeyDetail } from './model';
import { StructureView, structureCodeLabel } from './structureDetail';
import { SubtypeStructureView } from './subtypeKeys';

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
  const grid = useRef<GridHandle>(null);
  const [type, setType] = useState<CheckType | ''>('');
  /** 진단 범위 (M-7 구조 정합성) : 비우면 예전 목록 (속성·컬럼 + 상속 구조) */
  const [group, setGroup] = useState<CheckGroup | ''>('');
  const [status, setStatus] = useState<CheckStatus | ''>('');
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

      <Grid
        ref={grid}
        id="mappingQualityGrid"
                  minWidth={MAPPING_QUALITY_GRID_MIN_WIDTH}
        columns={mappingQualityColumns}
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
