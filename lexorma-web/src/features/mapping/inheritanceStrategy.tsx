import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { useDialog } from '../../components/dialog/DialogProvider';
import { useToast } from '../../components/toast/ToastProvider';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { termLabel } from '../column/terms';
import type { KeyColumnCandidate, MappingPhysical, MappingRow } from './model';

/**
 * 상속 A형·C형 매핑 화면 조각 (M-7 상속 A·C, docs/completed/inheritance-strategy-ac-m7.md) :
 * 목록의 물리 문맥 표시(직접·통합·분산), C형 말단의 상속 일반 속성 매핑, 상속 계층의 변환 기록(조회·삭제 — D-AC-18).
 */

/** A형 통합·C형 분산 표시 (목록·상세) : 유효하지 않으면 사유와 함께 미매핑으로 보인다 (정상으로 위장하지 않는다) */
export function PhysicalKindLabel({ value }: { value?: MappingPhysical | null }) {
  if (!value || value.kind === 'DIRECT' || value.kind === 'UNMAPPED') return null;
  const label = value.kind === 'MERGED' ? t('A형 통합') : t('C형 분산');
  return value.valid
    ? <Label variant="done" dot>{label}</Label>
    : <Label variant="attention" title={value.reason ? PHYSICAL_REASONS[value.reason] ?? value.reason : undefined}>{t('{0} (불완전)', label)}</Label>;
}

const PHYSICAL_REASONS: Record<string, string> = {
  INTEGRATED_TABLE_UNMAPPED: t('A형 통합 테이블에 루트 엔터티가 매핑되어 있지 않습니다.'),
  CONCRETE_TABLE_UNMAPPED: t('C형 말단 엔터티 중 테이블에 매핑되지 않은 것이 있습니다.'),
  STRATEGY_STRUCTURE_MISMATCH: t('상속 변환 기록과 매핑 구조가 맞지 않습니다.'),
};

/** 통합·분산 엔터티의 물리 테이블 (분산은 말단 테이블마다 — 대표 하나를 고르지 않는다) */
export function PhysicalTables({ value, onOpen }: { value?: MappingPhysical | null; onOpen: (mappingId: number) => void }) {
  if (!value || value.tables.length === 0) return <Muted>-</Muted>;
  return (
    <span className="logical-actions">
      {value.tables.map((table) => table.mappingId != null
        ? <button key={table.entityId} type="button" className="logical-link" onClick={() => onOpen(table.mappingId!)}
          title={value.kind === 'DISTRIBUTED' ? t('말단 {0} 의 테이블', table.entityName ?? '') : t('루트 {0} 의 통합 테이블', table.entityName ?? '')}><Mono>{table.tableName}</Mono></button>
        : <Mono key={table.entityId}>{table.tableName ?? '-'}</Mono>)}
    </span>
  );
}

interface SubtypeAttributeRow {
  id: number | null; current: boolean; attributeId: number; attributeName: string; ownerEntityId: number; ownerEntityName: string;
  columnId: number | null; columnName: string | null; dataType: string | null; maxLength: number | null; attributeTermName: string | null; attributeDataType: string | null;
  required: boolean | null; notNull: boolean | null; check: 'OK' | 'UNMAPPED' | 'OTHER_TABLE' | 'STALE';
}
interface SubtypeAttributeDetail { mapping: MappingRow; concrete: boolean; strategy: string | null; attributes: SubtypeAttributeRow[]; message: string | null }

const CHECKS: Record<SubtypeAttributeRow['check'], { label: string; variant: 'success' | 'attention' | 'danger' }> = {
  OK: { label: t('일치'), variant: 'success' },
  UNMAPPED: { label: t('미매핑'), variant: 'attention' },
  OTHER_TABLE: { label: t('다른 테이블 컬럼'), variant: 'danger' },
  STALE: { label: t('유효하지 않은 기존 매핑'), variant: 'danger' },
};

/** C형 말단 매핑의 상속 일반 속성 매핑 (조상 일반 속성 ↔ 말단 테이블의 복제 컬럼 — 말단마다 따로) */
export function InheritedAttributeSection({ mappingId, canEdit, onChanged }: { mappingId: number; canEdit: boolean; onChanged: () => void }) {
  const dialog = useDialog();
  const toast = useToast();
  const detail = useQuery({
    queryKey: ['mapping-subtype-attributes', mappingId],
    queryFn: () => api<SubtypeAttributeDetail>(`/api/modelMapping/entity/${enc(mappingId)}/subtypeAttributes`, { silent: true }),
    gcTime: 0,
  });
  const candidates = useQuery({
    queryKey: ['mapping-subtype-attribute-candidates', mappingId],
    queryFn: () => api<{ data: KeyColumnCandidate[] }>(`/api/modelMapping/entity/${enc(mappingId)}/subtypeAttributes/candidates?page=0&size=100`, { silent: true }),
    enabled: canEdit,
    gcTime: 0,
  });
  const [drafts, setDrafts] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);
  if (detail.isError) return <p role="alert">{t('상속 일반 속성 매핑을 불러오지 못했습니다.')}</p>;
  if (!detail.data) return <p className="gh-muted" role="status">{t('불러오는 중입니다.')}</p>;
  const data = detail.data;
  if (!data.concrete && data.attributes.length === 0) return null;

  const changed = () => { setDrafts({}); void detail.refetch(); void candidates.refetch(); onChanged(); };
  const save = async (row: SubtypeAttributeRow) => {
    setBusy(true);
    try {
      await api('/api/modelMapping/subtypeAttribute', { method: 'POST', body: { id: row.id, mappingId, attributeId: row.attributeId, columnId: Number(drafts[row.attributeId]) } });
      changed();
      toast.success(row.id != null ? t('상속 일반 속성 매핑을 변경했습니다.') : t('상속 일반 속성 매핑을 등록했습니다.'));
    } catch {
      void detail.refetch();
    } finally {
      setBusy(false);
    }
  };
  const remove = (row: SubtypeAttributeRow) => {
    dialog.confirm(t('{0} 의 상속 일반 속성 매핑({1})을 해제하시겠습니까? 연결 정보만 지웁니다. 논리 속성·물리 컬럼은 삭제되지 않습니다.', row.attributeName, row.columnName ?? ''), async () => {
      setBusy(true);
      try {
        await api(`/api/modelMapping/subtypeAttribute/${enc(row.id)}`, { method: 'DELETE' });
        changed();
        toast.success(t('상속 일반 속성 매핑을 해제했습니다.'));
      } finally {
        setBusy(false);
      }
    });
  };
  const options = candidates.data?.data ?? [];
  const mapped = data.attributes.filter((a) => a.current && a.id != null).length;
  const current = data.attributes.filter((a) => a.current).length;
  return (
    <section className="mapping-subtype" aria-label={t('상속 일반 속성 매핑')}>
      <h4 className="mapping-subtitle">{t('상속 일반 속성 매핑 (C형)')}{' '}
        <Label variant={mapped === current ? 'success' : 'attention'}>{t('조상 속성 {0} / {1} 매핑', mapped, current)}</Label></h4>
      <p className="gh-muted">{data.message ?? t('조상(루트·중간) 엔터티의 일반 속성을 이 말단 테이블에 복제한 컬럼과 연결합니다. 같은 원본 속성을 말단마다 따로 연결하며, 컬럼명으로 추론하지 않습니다.')}</p>
      <div className="dict-detail-columns"><div className="table-wrap">
        <table className="mapping-key-table">
          <thead><tr><th>{t('조상 속성')}</th><th>{t('원본 정의 엔터티')}</th><th>{t('표준 용어')}</th><th>{t('물리 컬럼')}</th><th>{t('점검')}</th>{canEdit && <th>{t('관리')}</th>}</tr></thead>
          <tbody>
            {data.attributes.map((a) => {
              const draft = drafts[a.attributeId] ?? (a.columnId != null ? String(a.columnId) : '');
              return (
                <tr key={`${a.current ? 'c' : 's'}-${a.attributeId}`}>
                  <td>{a.attributeName}{a.required && <> <Label>{t('필수')}</Label></>}</td>
                  <td>{a.ownerEntityName}</td>
                  <td>{a.attributeTermName ? termLabel({ commonStandardTermName: a.attributeTermName }) : <Muted>-</Muted>}</td>
                  <td>
                    {canEdit && a.current ? (
                      <select className="form-control mapping-column-select" aria-label={t('{0} 속성의 물리 컬럼', a.attributeName)} value={draft} disabled={busy}
                        onChange={(e) => setDrafts({ ...drafts, [a.attributeId]: e.target.value })}>
                        <option value="">{t('컬럼 선택')}</option>
                        {a.columnId != null && <option value={a.columnId}>{a.columnName}</option>}
                        {options.map((c) => <option key={c.columnId} value={c.columnId}>{c.columnName}{c.columnDesc ? ` · ${c.columnDesc}` : ''}</option>)}
                      </select>
                    ) : a.columnName ? <Mono>{a.columnName}</Mono> : <Muted>-</Muted>}
                  </td>
                  <td><Label variant={CHECKS[a.check].variant}>{CHECKS[a.check].label}</Label></td>
                  {canEdit && (
                    <td>
                      <div className="logical-actions">
                        {a.current && <button type="button" className="btn btn-sm btn-primary" disabled={busy || draft === '' || draft === String(a.columnId ?? '')}
                          onClick={() => void save(a)}>{a.id != null ? t('변경') : t('매핑')}</button>}
                        {a.id != null && <button type="button" className="btn btn-sm btn-danger" disabled={busy} onClick={() => remove(a)}>{t('해제')}</button>}
                      </div>
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div></div>
    </section>
  );
}

interface Conversion { id: number; groupId: number; groupName: string; supertypeEntityName: string; strategy: string; tableName: string | null; classificationColumnName: string | null }
interface ConversionTree { rootEntityId: number; rootEntityName: string | null; kind: string; conversions: Conversion[]; dependencies: string[]; deletable: boolean }

const KIND_LABELS: Record<string, string> = {
  SINGLE_TABLE: t('A형(단일 테이블 통합)'), TABLE_PER_CONCRETE: t('C형(말단 서브타입별 테이블)'), INCONSISTENT: t('맞지 않는 변환 기록'),
};

/**
 * 논리 모델의 상속 변환 기록 (D-AC-18) : 계층마다 전략·그룹별 기록, 기록 삭제를 막는 매핑. 매핑을 먼저 해제해야 지울 수 있다 (매핑 해제와 물리 객체 삭제는 다른 일이다)
 */
export function ConversionRecords({ modelId, canEdit, onChanged }: { modelId: number; canEdit: boolean; onChanged: () => void }) {
  const dialog = useDialog();
  const toast = useToast();
  const list = useQuery({
    queryKey: ['mapping-conversions', modelId],
    queryFn: () => api<ConversionTree[]>(`/api/modelMapping/conversions?modelId=${enc(modelId)}`, { silent: true }),
    gcTime: 0,
  });
  const [busy, setBusy] = useState(false);
  if (list.isError) return <p role="alert">{t('변환 기록을 불러오지 못했습니다.')}</p>;
  if (!list.data) return <p className="gh-muted" role="status">{t('불러오는 중입니다.')}</p>;
  if (list.data.length === 0) return <p className="gh-muted">{t('이 논리 모델에는 상속 A·C형 변환 기록이 없습니다. (B형과 아직 만들지 않은 계층에는 기록이 없습니다.)')}</p>;
  const remove = (tree: ConversionTree) => {
    dialog.confirm(t('상속 계층 {0} 의 변환 기록 {1}건을 지우시겠습니까? 기록만 지우며 물리 테이블·컬럼·FK·UNIQUE·논리 엔터티는 바뀌지 않습니다.', tree.rootEntityName ?? '', tree.conversions.length), async () => {
      setBusy(true);
      try {
        await api(`/api/modelMapping/conversions/${enc(tree.rootEntityId)}`, { method: 'DELETE' });
        void list.refetch();
        onChanged();
        toast.success(t('변환 기록을 지웠습니다.'));
      } finally {
        setBusy(false);
      }
    });
  };
  return (
    <ul className="gen-issues">
      {list.data.map((tree) => (
        <li key={tree.rootEntityId}>
          <strong>{tree.rootEntityName}</strong> · <Label variant={tree.kind === 'INCONSISTENT' ? 'danger' : 'done'}>{KIND_LABELS[tree.kind] ?? tree.kind}</Label>
          <ul>
            {tree.conversions.map((c) => (
              <li key={c.id}>{c.groupName} ({c.supertypeEntityName}){c.tableName && <> · <Mono>{c.tableName}{c.classificationColumnName ? `.${c.classificationColumnName}` : ''}</Mono></>}</li>
            ))}
          </ul>
          {tree.dependencies.length > 0
            ? <div className="gh-muted">{t('먼저 해제해야 하는 매핑 : {0}', tree.dependencies.join(', '))}</div>
            : <div className="gh-muted">{t('관련 매핑이 없어 기록을 지울 수 있습니다.')}</div>}
          {canEdit && (
            <div className="logical-actions">
              <button type="button" className="btn btn-sm btn-danger" disabled={busy || !tree.deletable} onClick={() => remove(tree)}
                title={tree.deletable ? undefined : t('관련 매핑을 먼저 해제해주세요.')}>{t('변환 기록 삭제')}</button>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
