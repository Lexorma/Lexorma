import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { useToast } from '../../components/toast/ToastProvider';
import { Label, Mono, Muted } from '../../components/grid/cells';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { termLabel } from '../column/terms';
import { useEntityDetail } from '../logical/entityDetail';
import { useTableDetail } from '../table/tableDetail';
import {
  CHECK_LEVELS, INHERITANCE_ROLES, INHERITANCE_STATUS, KEY_CHECKS, STRUCTURE_CHECKS, STRUCTURE_RESULTS,
  type KeyColumnCandidate, type MappingInheritance, type StructureCheck, type SubtypeKeyDetail, type SubtypeKeyRow,
} from './model';

const CANDIDATE_PAGE_SIZE = 10;

const columnType = (c: { dataType: string | null; maxLength: number | null; precision: number | null; scale: number | null }) =>
  !c.dataType ? null : c.maxLength != null ? `${c.dataType}(${c.maxLength})` : c.precision != null ? `${c.dataType}(${c.precision}${c.scale != null ? `,${c.scale}` : ''})` : c.dataType;

/** 서브타입이거나 유효하지 않은 기존 상속 PK 매핑이 남아 있어 상속 PK 매핑 영역을 보여야 하는가 */
export const needsSubtypeKeys = (value?: MappingInheritance | null) => !!value && (value.subtype || value.invalidKeys > 0);

/**
 * 목록의 상속 역할 표시 (M-7 4차) : 생성 미리보기·논리 ERD 와 같은 이름 (루트 슈퍼타입 · 중간 슈퍼타입 · 서브타입). 중간 엔터티는 상위의 서브타입이면서 하위 그룹의 슈퍼타입이다.
 * 여러 그룹의 슈퍼타입이어도 역할은 하나다. 상속이 풀렸는데 상속 PK 매핑이 남은 엔터티는 [상속 해제됨]
 */
export function InheritanceRoleLabel({ value }: { value?: MappingInheritance | null }) {
  if (!value) return null;
  if (!value.role) return <Label variant="danger" title={t('지금 상속 구조에 참여하지 않지만 상속 PK 매핑이 남아 있습니다.')}>{t('상속 해제됨')}</Label>;
  const title = value.role === 'INTERMEDIATE' ? t('상위 {0} 의 서브타입이면서 하위 그룹의 슈퍼타입입니다.', value.parentEntityName ?? '')
    : value.role === 'SUBTYPE' ? t('상위 {0} 의 서브타입입니다.', value.parentEntityName ?? '') : t('하위 서브타입 그룹이 있는 루트 엔터티입니다.');
  return <Label variant="done" title={title}>{INHERITANCE_ROLES[value.role]}</Label>;
}

/**
 * 목록의 상속 PK 현황 : 연결 수(필요한 수 기준 — 유효하지 않은 기존 매핑은 세지 않는다)와 구조 정합성을 따로 보인다.
 * 루트·일반 엔터티는 상속 PK 대상이 아니다 (미매핑으로 세지 않는다)
 */
export function InheritedKeySummary({ value, mapped, structure = true }: { value?: MappingInheritance | null; mapped: boolean;
  /** false : B형 상속 구조 판정을 보이지 않는다 (C형 말단 — 구조는 정합성 비교의 [상속 구조] 에서 C형 기준으로 판정) */
  structure?: boolean }) {
  if (!value) return null;
  const parts = [];
  if (value.subtype) {
    const complete = value.requiredKeys > 0 && value.mappedKeys === value.requiredKeys;
    parts.push(<Label key="keys" variant={!mapped ? '' : complete ? 'success' : 'attention'}
      title={t('상속 PK(루트 주 식별자) {0}개 중 {1}개 연결', value.requiredKeys, value.mappedKeys)}>{t('상속 PK {0} / {1}', value.mappedKeys, value.requiredKeys)}</Label>);
  }
  if (value.invalidKeys > 0) parts.push(<Label key="invalid" variant="danger">{t('무효 매핑 {0}', value.invalidKeys)}</Label>);
  if (structure && mapped && value.result && value.subtype) {
    const result = STRUCTURE_RESULTS[value.result];
    parts.push(<Label key="result" variant={result.variant} dot title={value.status ? INHERITANCE_STATUS[value.status] ?? value.status : undefined}>{result.label}</Label>);
  }
  return parts.length ? <span className="mapping-inheritance-cell">{parts}</span> : null;
}

/**
 * 상속 PK 매핑 영역 (M-7 4차, 속성 ↔ 컬럼 매핑 대화상자 안) : 지금 루트 주 식별자 속성마다 1행(원본 정의 엔터티·용어·타입·매핑 컬럼·PK·점검) + 유효하지 않은 기존 매핑.
 * 등록·변경은 그 테이블 컬럼 중 속성 매핑·다른 상속 PK 매핑에 안 쓰인 후보(서버 페이징)에서 고른다. 구조 불일치(PK 아님·타입)는 막지 않고 점검으로 보인다 — 서버 계약 그대로.
 * 해제는 연결만 지운다. 판정은 서버(SubtypePlanContext)가 하고 화면은 다시 계산하지 않는다. 상태는 이 엔터티 매핑(mappingId) 문맥으로만 관리한다
 */
export function SubtypeKeySection({ mappingId, canEdit, onChanged, onNavigate }: {
  mappingId: number;
  canEdit: boolean;
  /** 상속 PK 매핑이 바뀐 뒤 (직접 매핑 후보·목록 다시 조회) */
  onChanged: () => void;
  /** 다른 화면으로 이동 (대화상자를 닫고) */
  onNavigate: (path: string) => void;
}) {
  const dialog = useDialog();
  const toast = useToast();
  const detail = useQuery({
    queryKey: ['mapping-subtype-keys', mappingId],
    queryFn: () => api<SubtypeKeyDetail>(`/api/modelMapping/entity/${enc(mappingId)}/subtypeKeys`, { silent: true }),
    gcTime: 0,
  });
  /** 컬럼을 고르는 중인 행 (원본 속성 ID — 이 매핑 안에서만 유일) */
  const [editing, setEditing] = useState<SubtypeKeyRow | null>(null);
  const [busy, setBusy] = useState(false);

  if (detail.isError) return <p role="alert">{t('상속 PK 매핑을 불러오지 못했습니다.')}</p>;
  if (!detail.data) return <p className="gh-muted" role="status">{t('불러오는 중입니다.')}</p>;
  const data = detail.data;

  const changed = () => {
    setEditing(null);
    void detail.refetch();
    onChanged();
  };

  const remove = (row: SubtypeKeyRow) => {
    dialog.confirm(t('{0} 의 상속 PK 매핑({1})을 해제하시겠습니까? 연결 정보만 지웁니다. 논리 속성·물리 컬럼은 삭제되지 않고, 물리 PK·FK 와 상속 관계도 바뀌지 않습니다.',
      row.attributeName, row.columnName ?? ''), async () => {
      setBusy(true);
      try {
        await api(`/api/modelMapping/subtypeKey/${enc(row.id)}`, { method: 'DELETE' });
        changed();
        toast.success(t('상속 PK 매핑을 해제했습니다.'));
      } finally {
        setBusy(false);
      }
    });
  };

  const save = async (row: SubtypeKeyRow, column: KeyColumnCandidate) => {
    setBusy(true);
    try {
      await api('/api/modelMapping/subtypeKey', { method: 'POST', body: { id: row.id, mappingId, attributeId: row.attributeId, columnId: column.columnId } });
      changed();
      toast.success(row.id != null ? t('상속 PK 매핑을 변경했습니다.') : t('상속 PK 매핑을 등록했습니다.'));
    } catch {
      // 오류(409·400)는 공통 알림이 보여 준다. 고르던 행은 그대로 두고 서버 상태를 다시 읽는다 (화면과 저장 상태가 어긋나지 않게)
      void detail.refetch();
    } finally {
      setBusy(false);
    }
  };

  const current = data.keys.filter((k) => k.current);
  const stale = data.keys.filter((k) => !k.current);
  const headers = ['#', t('상속 속성'), t('원본 정의 엔터티'), t('표준 용어'), t('속성 타입'), t('물리 컬럼'), t('컬럼 타입'), 'PK', t('매핑 상태'), t('점검'), ...(canEdit ? [t('관리')] : [])];
  const row = (k: SubtypeKeyRow) => {
    const check = k.check ? KEY_CHECKS[k.check] : null;
    return (
      <tr key={`${k.current ? 'c' : 's'}-${k.attributeId}`} className={editing?.attributeId === k.attributeId ? 'mapping-focus-row' : undefined}>
        <td className="text-center">{k.current ? k.order : <Muted>-</Muted>}</td>
        <td>{k.attributeName}</td>
        <td>{k.attributeEntityName}</td>
        <td>{k.attributeTermName ? termLabel({ commonStandardTermName: k.attributeTermName }) : <Muted>-</Muted>}</td>
        <td>{k.attributeDataType ? <Mono>{k.attributeDataType}</Mono> : <Muted>-</Muted>}</td>
        <td>{k.columnName ? <Mono>{k.columnName}</Mono> : <Muted>-</Muted>}</td>
        <td>{columnType(k) ? <Mono>{columnType(k)}</Mono> : <Muted>-</Muted>}</td>
        <td>{k.columnId == null ? <Muted>-</Muted> : k.isPk ? 'Y' : 'N'}</td>
        <td>{k.id != null ? <Label variant="success" dot>{t('매핑 완료')}</Label> : <Label variant="attention" dot>{t('미매핑')}</Label>}</td>
        <td>{check ? <Label variant={check.variant} title={k.parentColumnName ? t('상위 키 컬럼 {0}', k.parentColumnName) : undefined}>{check.label}</Label> : <Muted>-</Muted>}</td>
        {canEdit && (
          <td>
            <div className="logical-actions">
              {k.current && (
                <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={() => setEditing(editing?.attributeId === k.attributeId ? null : k)}
                  aria-expanded={editing?.attributeId === k.attributeId}>
                  {k.id != null ? t('변경') : t('매핑')}
                </button>
              )}
              {k.id != null && <button type="button" className="btn btn-sm btn-danger" disabled={busy} onClick={() => remove(k)}>{t('해제')}</button>}
            </div>
          </td>
        )}
      </tr>
    );
  };

  const statusLabel = data.subtype && data.structure ? STRUCTURE_RESULTS[data.structure.result] : null;
  return (
    <section className="mapping-subtype" aria-labelledby={`subtype-keys-${mappingId}`}>
      <h4 className="mapping-subtitle" id={`subtype-keys-${mappingId}`}>
        {t('상속 PK 매핑')}{' '}
        {data.subtype && <Label variant={data.complete ? 'success' : 'attention'}>{t('상속 PK {0} / {1}', current.filter((k) => k.id != null).length, current.length)}</Label>}
        {stale.length > 0 && <> <Label variant="danger">{t('무효 매핑 {0}', stale.length)}</Label></>}
        {statusLabel && <> <Label variant={statusLabel.variant} dot>{statusLabel.label}</Label></>}
      </h4>
      <p className="gh-muted">
        {data.subtype && data.status === 'CONCRETE' ? data.statusMessage : data.subtype
          ? t('{0} 은(는) {1} 의 서브타입입니다. 루트 {2} 의 주 식별자를 상속 PK 로 이 테이블 컬럼에 연결합니다. 상속 PK 는 직접 속성이 아니며 직접 속성 매핑률에 들어가지 않습니다.',
            data.mapping.entityName, data.parentEntityName ?? '', data.rootEntityName ?? '')
          : data.statusMessage}
      </p>

      {data.keys.length === 0 ? (
        <p className="gh-muted">{t('루트 엔터티에 주 식별자가 없어 상속 PK 를 정할 수 없습니다.')}</p>
      ) : (
        <div className="dict-detail-columns"><div className="table-wrap">
          <table className="mapping-key-table">
            <thead><tr>{headers.map((h) => <th key={h}>{h}</th>)}</tr></thead>
            <tbody>
              {current.map(row)}
              {stale.length > 0 && <tr className="mapping-key-group"><td colSpan={headers.length}>{t('유효하지 않은 기존 매핑 (지금 루트 주 식별자에 없는 속성 — 해제할 수 있습니다)')}</td></tr>}
              {stale.map(row)}
            </tbody>
          </table>
        </div></div>
      )}

      {canEdit && editing && (
        <KeyColumnPicker key={editing.attributeId} mappingId={mappingId} row={editing} busy={busy} onCancel={() => setEditing(null)} onPick={(column) => void save(editing, column)} />
      )}

      {data.subtype && data.structure && <SubtypeStructureView detail={data} onNavigate={onNavigate} />}
    </section>
  );
}

/**
 * 상속 PK 컬럼 고르기 : 그 매핑 테이블의 컬럼 중 속성 매핑·다른 상속 PK 매핑에 안 쓰인 것 (변경이면 지금 컬럼 포함) — 서버 페이징·검색, 컬럼명 순.
 * PK 가 아니거나 NULL 을 허용하는 컬럼도 고를 수 있다 (막지 않는다) — 저장 뒤 점검에 불일치로 보인다
 */
function KeyColumnPicker({ mappingId, row, busy, onCancel, onPick }: {
  mappingId: number; row: SubtypeKeyRow; busy: boolean; onCancel: () => void; onPick: (column: KeyColumnCandidate) => void;
}) {
  const [input, setInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<KeyColumnCandidate | null>(null);
  const candidates = useQuery({
    queryKey: ['mapping-subtype-candidates', mappingId, row.id, keyword, page],
    queryFn: () => {
      const query = new URLSearchParams({ page: String(page), size: String(CANDIDATE_PAGE_SIZE), sort: 'columnName,asc', searchQuery: keyword });
      if (row.id != null) query.set('currentId', String(row.id));
      return api<{ data: KeyColumnCandidate[]; recordsTotal: number }>(`/api/modelMapping/entity/${enc(mappingId)}/subtypeKeys/candidates?${query}`, { silent: true });
    },
    placeholderData: (prev) => prev,
    gcTime: 0,
  });
  const total = candidates.data?.recordsTotal ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / CANDIDATE_PAGE_SIZE));
  const search = () => { setPage(0); setKeyword(input.trim()); };
  const warn = selected && (!selected.isPk || selected.isNullable)
    ? t('고른 컬럼이 PK 가 아니거나 NULL 을 허용합니다. 저장은 되지만 구조 점검에서 불일치로 보입니다. 물리 PK·NOT NULL 은 자동으로 바꾸지 않습니다.') : null;
  return (
    <div className="mapping-key-picker" role="group" aria-label={t('{0} 의 상속 PK 컬럼 선택', row.attributeName)}>
      <p><strong>{row.attributeName}</strong> <span className="gh-muted">({row.attributeEntityName}{row.attributeDataType ? ` · ${row.attributeDataType}` : ''})</span></p>
      <div className="mapping-search">
        <input type="text" className="form-control" aria-label={t('물리 컬럼 검색')} maxLength={100} placeholder={t('컬럼명 또는 설명')} value={input}
          onChange={(e) => setInput(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); search(); } }} />
        <button type="button" className="btn" onClick={search}>{t('검색')}</button>
      </div>
      <p className="gh-muted">{t('고를 수 있는 컬럼 {0}개 (속성 매핑·다른 상속 PK 매핑에 쓰인 컬럼 제외)', total)}</p>
      {candidates.isError ? (
        <p role="alert">{t('후보 컬럼을 불러오지 못했습니다.')}</p>
      ) : !candidates.data ? (
        <p className="gh-muted" role="status">{t('불러오는 중입니다.')}</p>
      ) : candidates.data.data.length === 0 ? (
        <p className="gh-muted">{keyword ? t('조건에 맞는 후보 컬럼이 없습니다.') : t('고를 수 있는 컬럼이 없습니다.')}</p>
      ) : (
        <ul className={`mapping-candidates${candidates.isFetching ? ' is-refreshing' : ''}`} role="radiogroup" aria-label={t('후보 컬럼')}>
          {candidates.data.data.map((c) => (
            <li key={c.columnId}>
              <label>
                <input type="radio" name={`subtype-key-${mappingId}`} checked={selected?.columnId === c.columnId} onChange={() => setSelected(c)} />
                <span className="gh-mono">{c.columnName}</span>
                {columnType(c) && <span className="gh-mono gh-muted">{columnType(c)}</span>}
                {c.isPk ? <Label variant="accent">PK</Label> : <Label>{t('PK 아님')}</Label>}
                {c.isNullable && !c.isPk && <Label>{t('NULL 허용')}</Label>}
                {c.columnId === row.columnId && <Label variant="success">{t('현재 매핑')}</Label>}
                {c.columnDesc && <span className="gh-muted">{c.columnDesc}</span>}
              </label>
            </li>
          ))}
        </ul>
      )}
      {pageCount > 1 && (
        <div className="mapping-pager">
          <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>{t('이전')}</button>
          <span className="gh-muted">{page + 1} / {pageCount}</span>
          <button type="button" className="btn btn-sm" disabled={page >= pageCount - 1} onClick={() => setPage(page + 1)}>{t('다음')}</button>
        </div>
      )}
      {warn && <p className="form-help" role="status">{warn}</p>}
      <div className="logical-actions">
        <button type="button" className="btn btn-primary" disabled={busy || !selected || selected.columnId === row.columnId}
          title={!selected ? t('연결할 컬럼을 선택해주세요.') : selected.columnId === row.columnId ? t('지금 매핑된 컬럼입니다.') : undefined}
          onClick={() => selected && onPick(selected)}>{t('상속 PK 매핑 저장')}</button>
        <button type="button" className="btn" disabled={busy} onClick={onCancel}>{t('취소')}</button>
      </div>
    </div>
  );
}

/**
 * 상속 물리 구조 상세 (매핑 대화상자·정합성 비교 상세 공용) : 비교 대상·테이블·루트·직계 상위와 그 테이블, 원본 주 식별자, 실제 물리 PK·직계 상위 키·부모 FK,
 * 확인이 필요한 점검과 정상 점검. 조회만 하며 물리 모델을 고치지 않는다. 문구는 서버 판정 메시지 그대로 (SQL·예외 문구는 오지 않는다)
 */
export function SubtypeStructureView({ detail, onNavigate }: { detail: SubtypeKeyDetail; onNavigate: (path: string) => void }) {
  const { canView } = useAuth();
  const openEntity = useEntityDetail();
  const { openTableDetailById } = useTableDetail();
  const structure = detail.structure!;
  const issues = structure.checks.filter((c) => c.level !== 'OK');
  const passed = structure.checks.filter((c) => c.level === 'OK');
  const keyText = (list: { columnName: string; dataType: string }[]) => (list.length ? list.map((c) => `${c.columnName} ${c.dataType}`).join(', ') : null);
  const items: [string, string | null, boolean?][] = [
    [t('비교 대상 엔터티'), detail.mapping.entityName],
    [t('매핑된 물리 테이블'), `${structure.schemaName ? `${structure.schemaName}.` : ''}${structure.tableName}`, true],
    [t('루트 엔터티'), detail.rootEntityName],
    [t('직계 상위 엔터티'), detail.parentEntityName],
    [t('직계 상위 물리 테이블'), structure.parentTableName ? `${structure.parentSchemaName ? `${structure.parentSchemaName}.` : ''}${structure.parentTableName}` : null, true],
    [t('원본 주 식별자 구성'), detail.keys.filter((k) => k.current).map((k) => k.attributeName).join(', ') || null],
    [t('현재 상속 PK 매핑'), detail.keys.filter((k) => k.current && k.columnName).map((k) => `${k.attributeName} → ${k.columnName}`).join(', ') || null],
    [t('실제 물리 PK 구성'), keyText(structure.tableKey), true],
    [t('직계 상위 테이블 키'), keyText(structure.parentKey), true],
  ];
  const canEntity = canView('logical-model');
  const canTable = canView('table-manage');
  const checkList = (list: StructureCheck[]) => (
    <ul className="mapping-checks">
      {list.map((c) => (
        <li key={c.code}>
          <Label variant={CHECK_LEVELS[c.level]?.variant} dot>{CHECK_LEVELS[c.level]?.label ?? c.level}</Label>{' '}
          <strong>{STRUCTURE_CHECKS[c.code] ?? INHERITANCE_STATUS[c.code] ?? c.code}</strong> <span>{c.message}</span>
        </li>
      ))}
    </ul>
  );
  return (
    <div className="mapping-structure">
      <h4 className="mapping-subtitle">{t('상속 물리 구조 정합성')} <Label variant={STRUCTURE_RESULTS[structure.result].variant} dot>{STRUCTURE_RESULTS[structure.result].label}</Label>
        {detail.status !== 'VALID' && <> <span className="gh-mono gh-muted">{detail.status}</span></>}</h4>
      <dl className="dict-detail">
        {items.map(([label, value, mono]) => [
          <dt key={`t-${label}`}>{label}</dt>,
          <dd key={`d-${label}`} className={value == null ? 'gh-muted' : mono ? 'gh-mono' : undefined}>{value ?? '-'}</dd>,
        ])}
        <dt>{t('부모 FK')}</dt>
        <dd>
          {structure.foreignKeys.length === 0 ? <Muted>{t('상속 PK 컬럼을 쓰거나 직계 상위 테이블을 참조하는 FK 가 없습니다.')}</Muted> : (
            <ul className="mapping-fk-list">
              {structure.foreignKeys.map((fk) => (
                <li key={fk.relationId}>
                  <Label variant={fk.parentLink ? 'success' : 'danger'}>{fk.parentLink ? t('상속 FK') : t('상속 PK 와 다름')}</Label>{' '}
                  <span className="gh-mono">{structure.tableName} ({fk.childColumns.join(', ')}) → {fk.targetTableName ?? '?'} ({fk.parentColumns.join(', ')})</span>
                  {fk.relationName && <span className="gh-muted"> {fk.relationName}</span>}
                </li>
              ))}
            </ul>
          )}
        </dd>
      </dl>
      {issues.length > 0 && <><p className="mapping-checks-title">{t('확인이 필요한 항목 ({0})', issues.length)}</p>{checkList(issues)}</>}
      {passed.length > 0 && (
        <details className="mapping-checks-passed">
          <summary>{t('정상 항목 ({0})', passed.length)}</summary>
          {checkList(passed)}
        </details>
      )}
      {structure.constraintNote && <p className="mapping-focus-note" role="note">{structure.constraintNote}</p>}
      <p className="form-help">{t('조회할 때 계산한 결과입니다. 물리 PK·FK·컬럼은 자동으로 바뀌지 않으며, 고치려면 테이블·컬럼 관리에서 직접 수정합니다.')}</p>
      <div className="logical-actions mapping-structure-links">
        {canEntity && <button type="button" className="btn btn-sm" onClick={() => openEntity(detail.mapping.entityId, { initialTab: 'inheritance' })}>{t('현재 엔터티 상세')}</button>}
        {canEntity && detail.rootEntityId != null && detail.rootEntityId !== detail.mapping.entityId
          && <button type="button" className="btn btn-sm" onClick={() => openEntity(detail.rootEntityId!, { initialTab: 'identifiers' })}>{t('원본 엔터티 상세')}</button>}
        {canEntity && detail.parentEntityId != null && detail.parentEntityId !== detail.rootEntityId
          && <button type="button" className="btn btn-sm" onClick={() => openEntity(detail.parentEntityId!, { initialTab: 'inheritance' })}>{t('직계 상위 엔터티 상세')}</button>}
        {canTable && <button type="button" className="btn btn-sm" onClick={() => void openTableDetailById(structure.tableId)}>{t('테이블 상세')}</button>}
        {canTable && structure.parentTableId != null && <button type="button" className="btn btn-sm" onClick={() => void openTableDetailById(structure.parentTableId!)}>{t('상위 테이블 상세')}</button>}
        {canView('physical-erd') && <button type="button" className="btn btn-sm" onClick={() => onNavigate(`/physicalErd?schemaId=${enc(detail.mapping.schemaId ?? '')}&tableId=${enc(structure.tableId)}`)}>{t('물리 ERD 에서 보기')}</button>}
      </div>
    </div>
  );
}
