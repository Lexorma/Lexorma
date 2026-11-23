import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { useToast } from '../../components/toast/ToastProvider';
import { Label } from '../../components/grid/cells';
import { useAuth } from '../../hooks/useAuth';
import type { SubjectArea } from './subjectArea';
import { api, ApiError } from '../../lib/api';
import { useConflictRecovery, useSubmitting } from '../../components/conflict/ConflictRecovery';
import { runWithReview } from '../../components/conflict/conflictActions';
import { deleteAttributeGuarded, deleteEntityGuarded, deleteIdentifierGuarded, entitySnapshot, loadEntity } from './conflictSnapshots';
import { baseQuery, cascadeText, withDeletion } from '../../lib/deletion';
import { t, tc } from '../../lib/i18n';
import { searchTerms, termLabel, type Term } from '../column/terms';
import { historyPath } from '../history/model';
import { mappingPath, useMappingLookup } from '../mapping/model';
import { cardinalityText, type LogicalAttribute, type LogicalEntity, type LogicalIdentifier, type LogicalRelationship } from './model';
import { useInheritance } from './SubtypePanel';
import { completeness, disjointness, disjointSymbol, type Inheritance } from './subtype';
import '../../styles/logical.css';

interface EntityDetailOptions {
  /** 엔터티·속성·식별자·승인 상태가 바뀐 뒤 (목록·ERD 다시 조회) */
  onChanged?: () => void;
  /** [논리 ERD 에서 보기] (ERD 화면에서 열 때는 주지 않는다) */
  onErd?: () => void;
  /** 다른 화면으로 이동 (ERD 는 저장하지 않은 배치를 먼저 확인한다). 없으면 바로 이동한다 */
  onNavigate?: (path: string) => void;
  /** 변경 버튼을 숨긴다 (논리 ERD 의 조회 모드). 승인·승인취소는 편집이 아니라 그대로 둔다 */
  readOnly?: boolean;
  /** 처음 열 탭 (기본 : 속성) */
  initialTab?: EntityDetailTab;
}

/** 엔터티 상세 대화상자 (논리 모델 관리·논리 ERD 공용) : 기본 정보 / 속성 / 식별자 / 관계 탭 */
export function useEntityDetail() {
  const dialog = useDialog();
  return (entityId: number, options: EntityDetailOptions = {}) => {
    dialog.open({
      title: t('엔터티 상세'),
      className: 'dialog-xl',
      content: (controls: DialogControls) => <EntityDetail entityId={entityId} options={options} controls={controls} />,
    });
  };
}

export type EntityDetailTab = 'basic' | 'attributes' | 'identifiers' | 'relationships' | 'inheritance';
type Tab = EntityDetailTab;

function EntityDetail({ entityId, options, controls }: { entityId: number; options: EntityDetailOptions; controls: DialogControls }) {
  const dialog = useDialog();
  const toast = useToast();
  const navigate = useNavigate();
  const { canView, approvalAvailable } = useAuth();
  const [tab, setTab] = useState<Tab>(options.initialTab ?? 'attributes');
  // 논리·물리 매핑 (M-7) : 이 엔터티에 매핑된 물리 테이블
  const mapping = useMappingLookup({ entityId }, canView('model-mapping'));
  const entity = useQuery({
    queryKey: ['logical-entity', entityId],
    queryFn: () => api<LogicalEntity>(`/api/logical/entity/${entityId}`, { silent: true }),
    gcTime: 0,
  });
  // 이 엔터티의 관계만 (M-6 후속 3차 — 모델 전체 관계를 받아 거르지 않는다)
  const relationships = useQuery({
    queryKey: ['logical-entity-relationships', entityId],
    queryFn: () => api<LogicalRelationship[]>(`/api/logical/entity/${entityId}/relationship`, { silent: true }),
    gcTime: 0,
  });
  // 슈퍼타입·서브타입 (M-6 후속 5차) : 단계·조상·그룹, 유효 속성·식별자(상속 포함), 조상의 관계 — 상속 속성은 복제하지 않고 정의 엔터티의 원본을 보여 준다
  const inheritance = useInheritance(entityId);
  const openEntityDetail = useEntityDetail();
  /** 정의 엔터티로 이동 : 이 대화상자를 닫고 그 엔터티의 상세를 연다 (상속 속성은 정의한 엔터티에서만 고친다) */
  const openDefinition = (definedEntityId: number, initialTab: Tab = 'attributes') => {
    controls.close();
    openEntityDetail(definedEntityId, { ...options, initialTab });
  };

  const [busy, run] = useSubmitting(); // 승인·삭제 요청 중 중복 제출 방지 (M8-C3-D05) — 조기 return 앞 (훅 순서)
  if (entity.isError) return <p role="alert">{t('엔터티를 불러오지 못했습니다.')} {entity.error.message}</p>;
  if (!entity.data) return <p role="status">{t('불러오는 중입니다.')}</p>;

  const data = entity.data;
  const approved = !!data.isApproval;
  // 변경 버튼 (서버도 같은 기준으로 다시 검사한다)
  // - 엔터티 자체(이름·설명·삭제) : 변경 권한(USER 이상)이 있고 승인 전일 때만
  // - 속성·식별자 : 승인된 엔터티는 승인 권한자만 (승인 권한자가 등록한 엔터티는 바로 승인되므로 이어서 편집할 수 있어야 한다)
  const canEdit = canView('logical-model-edit') && !options.readOnly;
  const editable = canEdit && !approved;
  const detailEditable = canEdit && (!approved || approvalAvailable);
  const related = relationships.data ?? [];
  const info = inheritance.data;
  const participates = !!info && (info.parent != null || info.groups.length > 0);
  const changed = () => {
    void entity.refetch();
    void relationships.refetch();
    void inheritance.refetch();
    options.onChanged?.();
  };

  const approvalDone = () => toast.success(approved ? t('정상적으로 승인취소처리 되었습니다.') : t('정상적으로 승인처리 되었습니다.'));
  // 승인 : 화면이 본 엔터티 기준값. 409 면 최신 엔터티를 보여 주고 사용자가 다시 승인한다 (M8-C3 — 자동 재전송 없음)
  const toggleApproval = () => {
    dialog.confirm(approved ? t('이 엔터티를 승인취소하시겠습니까?') : t('이 엔터티를 승인하시겠습니까?'), () => void run(async () => {
      const ok = await runWithReview({
        dialog, title: approved ? t('엔터티 승인 취소') : t('엔터티 승인'), intro: t('다른 사용자가 이 항목을 변경했습니다.'),
        seen: entitySnapshot(data), load: async () => entitySnapshot(await loadEntity(entityId)),
        act: async (state) => { await api(`/api/logical/entity/${entityId}/approval/${!approved}${baseQuery(state.modifiedAt)}`, { method: 'PATCH', conflict: 'handled' }); },
        retryLabel: approved ? t('최신 내용으로 승인 취소') : t('최신 내용으로 승인'), targetApproval: !approved,
        onDone: approvalDone, onClose: changed,
      });
      if (ok) {
        changed();
        approvalDone();
      }
    }));
  };

  // 삭제 확인 조회 : 함께 지워질 속성·식별자 수와 구성 기준값 (M8-C2 — 확인한 뒤 구성이 바뀌면 409, M8-C3 — 최신 구성을 보여 주고 다시 확인)
  const remove = () => withDeletion(`/api/logical/entity/${entityId}/deletion`, (deletion) => {
    const cascade = cascadeText(deletion);
    const deleted = () => {
      controls.close();
      options.onChanged?.();
      toast.success(t('정상적으로 삭제되었습니다.'));
    };
    dialog.confirm(cascade ? `${t('{0} 엔터티를 삭제하시겠습니까?', data.entityName)} ${cascade}` : t('{0} 엔터티를 삭제하시겠습니까?', data.entityName), () => void run(async () => {
      await deleteEntityGuarded(dialog, entityId, deletion, data.modifiedAt, deleted, changed);
    }));
  });

  const inheritedAttributes = info?.attributes.filter((a) => a.inherited).length ?? 0;
  const tabs: [Tab, string][] = [
    ['attributes', inheritedAttributes ? t('속성 ({0} + 상속 {1})', data.attributes.length, inheritedAttributes) : t('속성 ({0})', data.attributes.length)],
    ['identifiers', t('식별자 ({0})', data.identifiers.length)],
    ['relationships', info?.inheritedRelationships.length ? t('관계 ({0} + 상속 {1})', related.length, info.inheritedRelationships.length) : t('관계 ({0})', related.length)],
    ['inheritance', participates ? t('상속 ({0}단계)', info!.level) : t('상속 정보')],
    ['basic', t('기본 정보')],
  ];

  return (
    <div className="logical-detail">
      <div className="dict-detail-status">
        <strong>{data.entityName}</strong>
        {approved ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>}
        {mapping.data && (mapping.data.mappingId != null
          ? <Label variant="accent" title={t('매핑된 물리 테이블')}>{t('물리 테이블 {0} · 속성 {1} / {2} 매핑', mapping.data.tableName ?? '', mapping.data.mappedAttributeCount, mapping.data.attributeCount)}</Label>
          : <Label title={t('물리 테이블에 매핑되지 않았습니다.')}>{t('미매핑')}</Label>)}
        {info?.parent && <Label variant="accent" title={t('슈퍼타입 {0} 의 서브타입 (그룹 {1})', info.parent.supertypeEntityName, info.parent.groupName)}>{t('서브타입 · {0}단계', info.level)}</Label>}
        {info && info.groups.length > 0 && <Label title={t('이 엔터티를 슈퍼타입으로 하는 서브타입 그룹')}>{t('슈퍼타입 · 그룹 {0}개', info.groups.length)}</Label>}
        {approved && <span className="gh-muted">{approvalAvailable ? t('승인된 엔터티입니다. 엔터티명·설명 변경과 삭제는 승인취소 후에 할 수 있습니다.') : t('승인된 엔터티는 승인취소 후에 변경할 수 있습니다.')}</span>}
      </div>

      <ul className="nav nav-tabs">
        {tabs.map(([id, label]) => (
          <li className="nav-item" key={id}>
            <a href={`#entity-${id}`} className={`nav-link${tab === id ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab(id); }}>{label}</a>
          </li>
        ))}
      </ul>

      <div className="logical-detail-body">
        {tab === 'basic' && <BasicTab entity={data} editable={editable} onSaved={changed} />}
        {tab === 'attributes' && (
          <>
            {info && <InheritedAttributes inheritance={info} onOpen={(id) => openDefinition(id, 'attributes')} />}
            <AttributeTab entity={data} editable={detailEditable} onChanged={changed} inherited={info?.attributes.filter((a) => a.inherited).length ?? 0} />
          </>
        )}
        {tab === 'identifiers' && (
          <>
            {info && <InheritedIdentifiers inheritance={info} onOpen={(id) => openDefinition(id, 'identifiers')} />}
            <IdentifierTab entity={data} editable={detailEditable} onChanged={changed} subtype={info?.parent != null} />
          </>
        )}
        {tab === 'relationships' && (
          <>
            <RelationshipTab relationships={related} loading={relationships.isPending} failed={relationships.isError} />
            {info && <InheritedRelationships inheritance={info} onOpen={(id) => openDefinition(id, 'relationships')} />}
          </>
        )}
        {tab === 'inheritance' && <InheritanceTab inheritance={info} loading={inheritance.isPending} failed={inheritance.isError} onOpen={(id) => openDefinition(id, 'inheritance')} />}
      </div>

      <div className="gh-dialog-actions">
        {options.onErd && <button type="button" className="btn" onClick={() => { controls.close(); options.onErd?.(); }}>{t('논리 ERD 에서 보기')}</button>}
        {canView('model-mapping') && (
          <button type="button" className="btn" onClick={() => { controls.close(); (options.onNavigate ?? navigate)(mappingPath(data.modelId, entityId)); }}>{t('매핑 관리')}</button>
        )}
        {canView('model-history') && (
          <button type="button" className="btn" onClick={() => { controls.close(); (options.onNavigate ?? navigate)(historyPath('LOGICAL_ENTITY', entityId)); }}>{t('변경 이력')}</button>
        )}
        {approvalAvailable && (
          <button type="button" className={approved ? 'btn btn-danger' : 'btn btn-primary'} disabled={busy} onClick={toggleApproval}>
            {approved ? t('승인취소') : tc('버튼', '승인')}
          </button>
        )}
        {editable && (
          <button type="button" className="btn btn-danger" disabled={busy || !relationships.data || related.length > 0 || participates} onClick={remove}
            title={related.length ? t('관계에 사용 중인 엔터티는 삭제할 수 없습니다. 관계를 먼저 정리해주세요.') : participates ? t('슈퍼타입·서브타입 엔터티는 삭제할 수 없습니다. 상속을 먼저 해제해주세요.') : undefined}>
            {t('엔터티 삭제')}
          </button>
        )}
      </div>
    </div>
  );
}

function BasicTab({ entity, editable, onSaved }: { entity: LogicalEntity; editable: boolean; onSaved: () => void }) {
  const dialog = useDialog();
  const toast = useToast();
  const [name, setName] = useState(entity.entityName);
  const [description, setDescription] = useState(entity.entityDescription ?? '');
  const [busy, run] = useSubmitting();
  // 409 복구 (M8-C3) : 입력 유지 → 최신 엔터티 비교 → 고른 변경만 다시 적용 → 직접 저장
  const recovery = useConflictRecovery({
    initial: { name: entity.entityName, description: entity.entityDescription ?? '' },
    base: entity.modifiedAt,
    fields: [{ key: 'name', label: t('엔터티명'), get: (v) => v.name }, { key: 'description', label: t('설명'), get: (v) => v.description }],
    load: async () => {
      const latest = await api<LogicalEntity>(`/api/logical/entity/${entity.id}`, { silent: true });
      return { values: { name: latest.entityName, description: latest.entityDescription ?? '' }, base: latest.modifiedAt ?? null };
    },
  });

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('엔터티명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(t('수정하시겠습니까?'), () => void run(async () => {
      // 이름 중복(모델 안, 대소문자 무시)·승인 상태는 서버에서 검증한다
      try {
        await api('/api/logical/entity', { method: 'POST', conflict: 'handled', body: { id: entity.id, entityName: name.trim(), entityDescription: description.trim() || null, modifiedAt: recovery.base } });
      } catch (error) {
        if (recovery.catchConflict(error, { name, description })) return;
        throw error;
      }
      onSaved();
      toast.success(t('정상적으로 수정되었습니다.'));
    }));
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      {recovery.notice((latest, picks) => {
        if (!picks.has('name')) setName(latest.name);
        if (!picks.has('description')) setDescription(latest.description);
      })}
      <div className="form-group">
        <label htmlFor="entityName">{t('엔터티명')}</label>
        <input type="text" className="form-control" id="entityName" maxLength={255} required readOnly={!editable} value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="entityDescription">{t('설명')}</label>
        <textarea className="form-control" id="entityDescription" maxLength={1000} rows={3} readOnly={!editable} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      {editable && <button type="submit" className="btn btn-primary" disabled={busy || recovery.blocked}>{busy ? t('처리 중…') : t('저장')}</button>}
    </form>
  );
}

function AttributeTab({ entity, editable, onChanged, inherited = 0 }: { entity: LogicalEntity; editable: boolean; onChanged: () => void; inherited?: number }) {
  const dialog = useDialog();
  const primary = new Set(entity.identifiers.filter((i) => i.isPrimary).flatMap((i) => i.attributeIds));
  const inIdentifier = new Set(entity.identifiers.flatMap((i) => i.attributeIds));

  const openForm = (attribute?: LogicalAttribute) => {
    dialog.popup({
      title: attribute ? t('속성 수정') : t('속성 추가'),
      size: 'wide',
      content: ({ close }: DialogControls) => <AttributeForm entityId={entity.id} attribute={attribute} onSaved={() => { close(); onChanged(); }} />,
    });
  };

  const remove = (attribute: LogicalAttribute) => {
    dialog.confirm(t('{0} 속성을 삭제하시겠습니까?', attribute.attributeName), async () => {
      await deleteAttributeGuarded(dialog, entity.id, attribute, onChanged);
    });
  };

  // 순서 변경 : 엔터티의 속성 ID 전체를 새 순서로 보낸다
  const move = async (index: number, delta: number) => {
    const ids = entity.attributes.map((a) => a.id);
    [ids[index], ids[index + delta]] = [ids[index + delta], ids[index]];
    await api(`/api/logical/entity/${entity.id}/attribute/order`, { method: 'PUT', body: ids });
    onChanged();
  };

  return (
    <>
      {inherited > 0 && <h4 className="subtype-section-title">{t('직접 정의 속성')}</h4>}
      {editable && <div className="logical-tab-actions"><button type="button" className="btn btn-primary" onClick={() => openForm()}>{t('속성 추가')}</button></div>}
      {entity.attributes.length === 0 ? (
        <p className="gh-muted">{t('등록된 속성이 없습니다.')}</p>
      ) : (
        <div className="dict-detail-columns">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  {['#', t('속성명'), t('식별자'), t('필수'), t('데이터 유형'), t('표준 용어'), t('표준 도메인'), t('설명'), ...(editable ? [t('관리')] : [])].map((h) => <th key={h}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {entity.attributes.map((a, index) => (
                  <tr key={a.id}>
                    <td className="text-center">{index + 1}</td>
                    <td>{a.attributeName}</td>
                    <td className="text-center">
                      {primary.has(a.id) ? <Label variant="accent">{t('주 식별자')}</Label> : inIdentifier.has(a.id) ? <Label>{t('식별자')}</Label> : <span className="gh-muted">-</span>}
                    </td>
                    <td className="text-center">{a.isRequired ? <Label variant="attention">{t('필수')}</Label> : <span className="gh-muted">-</span>}</td>
                    <td className="gh-mono">{a.dataType || '-'}</td>
                    <td>{a.termName ? termLabel({ commonStandardTermName: a.termName }) : <span className="gh-muted">-</span>}</td>
                    <td>{a.domainName ? <>{a.domainName}{a.domainDataType && <span className="gh-muted"> · {a.domainDataType}</span>}</> : <span className="gh-muted">-</span>}</td>
                    <td>{a.attributeDescription || '-'}</td>
                    {editable && (
                      <td>
                        <div className="logical-actions">
                          <button type="button" className="btn btn-sm" aria-label={t('위로')} title={t('위로')} disabled={index === 0} onClick={() => void move(index, -1)}>↑</button>
                          <button type="button" className="btn btn-sm" aria-label={t('아래로')} title={t('아래로')} disabled={index === entity.attributes.length - 1} onClick={() => void move(index, 1)}>↓</button>
                          <button type="button" className="btn btn-sm" onClick={() => openForm(a)}>{t('수정')}</button>
                          <button type="button" className="btn btn-sm btn-danger" disabled={inIdentifier.has(a.id)} onClick={() => remove(a)}
                            title={inIdentifier.has(a.id) ? t('식별자에 포함된 속성은 삭제할 수 없습니다. 식별자에서 먼저 빼주세요.') : undefined}>{t('삭제')}</button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}

interface LinkedTerm {
  id: number;
  name: string;
  domainName: string | null;
  domainDataType: string | null;
}

/** 속성 등록·수정 (엔터티 상세·논리 ERD 편집 공용) : 표준 용어 검색·연결·해제 포함. 도메인은 용어의 도메인을 서버가 내려준다 */
export function AttributeForm({ entityId, attribute, onSaved }: { entityId: number; attribute?: LogicalAttribute; onSaved: () => void }) {
  const dialog = useDialog();
  const [name, setName] = useState(attribute?.attributeName ?? '');
  const [description, setDescription] = useState(attribute?.attributeDescription ?? '');
  const [dataType, setDataType] = useState(attribute?.dataType ?? '');
  const [required, setRequired] = useState(!!attribute?.isRequired);
  const [term, setTerm] = useState<LinkedTerm | null>(attribute?.termId != null
    ? { id: attribute.termId, name: attribute.termName ?? '', domainName: attribute.domainName, domainDataType: attribute.domainDataType } : null);
  const [keyword, setKeyword] = useState('');
  const [results, setResults] = useState<Term[] | null>(null);
  const [busy, run] = useSubmitting();
  type Values = { name: string; description: string; dataType: string; required: boolean; term: LinkedTerm | null };
  const valuesOf = (a?: LogicalAttribute): Values => ({
    name: a?.attributeName ?? '', description: a?.attributeDescription ?? '', dataType: a?.dataType ?? '', required: !!a?.isRequired,
    term: a?.termId != null ? { id: a.termId, name: a.termName ?? '', domainName: a.domainName, domainDataType: a.domainDataType } : null,
  });
  // 409 복구 (M8-C3) : 수정일 때만 — 엔터티 상세에서 이 속성의 최신 값을 읽는다
  const recovery = useConflictRecovery<Values>({
    initial: valuesOf(attribute),
    base: attribute?.modifiedAt,
    fields: [
      { key: 'name', label: t('속성명'), get: (v) => v.name },
      { key: 'dataType', label: t('데이터 유형'), get: (v) => v.dataType },
      { key: 'required', label: t('필수 속성'), get: (v) => v.required, show: (v) => (v.required ? t('예') : t('아니요')) },
      { key: 'term', label: t('표준 용어'), get: (v) => v.term?.id ?? null, show: (v) => v.term?.name ?? null },
      { key: 'description', label: t('설명'), get: (v) => v.description },
    ],
    load: async () => {
      const detail = await api<LogicalEntity>(`/api/logical/entity/${entityId}`, { silent: true });
      const latest = detail.attributes.find((a) => a.id === attribute?.id);
      if (!latest) throw new ApiError(400, t('속성을 찾을 수 없습니다.'), 'LOGICAL_MODEL_IS_NOT_DEFINED');
      return { values: valuesOf(latest), base: latest.modifiedAt ?? null };
    },
  });

  const search = async () => {
    if (!keyword.trim()) return dialog.alert(t('검색할 표준 용어명을 입력해주세요.'));
    setResults(await searchTerms('termName', keyword.trim()));
  };

  const pick = (found: Term) => {
    setTerm({ id: found.id, name: found.commonStandardTermName, domainName: found.commonStandardDomainName ?? null, domainDataType: found.dataType ?? null });
    if (!name.trim()) setName(termLabel(found));
    setResults(null);
    setKeyword('');
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('속성명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(attribute ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), () => void run(async () => {
      // 도메인은 보내지 않는다 : 표준 용어의 도메인을 서버가 조회해 내려준다
      try {
        await api('/api/logical/attribute', {
          method: 'POST',
          conflict: 'handled',
          body: { id: attribute?.id ?? null, entityId, attributeName: name.trim(), attributeDescription: description.trim() || null, dataType: dataType.trim() || null, isRequired: required, termId: term?.id ?? null, modifiedAt: attribute ? recovery.base : null },
        });
      } catch (error) {
        if (recovery.catchConflict(error, { name, description, dataType, required, term })) return;
        throw error;
      }
      onSaved();
    }));
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      {recovery.notice((latest, picks) => {
        if (!picks.has('name')) setName(latest.name);
        if (!picks.has('dataType')) setDataType(latest.dataType);
        if (!picks.has('required')) setRequired(latest.required);
        if (!picks.has('term')) setTerm(latest.term);
        if (!picks.has('description')) setDescription(latest.description);
      })}
      <div className="form-group">
        <label htmlFor="attributeName">{t('속성명')}</label>
        <input type="text" className="form-control" id="attributeName" maxLength={255} required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-group">
        <label htmlFor="attributeDataType">{t('데이터 유형')}</label>
        <input type="text" className="form-control" id="attributeDataType" maxLength={255} placeholder={t('예 : 문자, 숫자, 일시 (선택)')} value={dataType} onChange={(e) => setDataType(e.target.value)} />
      </div>
      <div className="form-check">
        <input type="checkbox" className="form-check-input" id="attributeRequired" checked={required} onChange={(e) => setRequired(e.target.checked)} />
        <label className="form-check-label" htmlFor="attributeRequired">{t('필수 속성')}</label>
      </div>

      <div className="form-group">
        <label htmlFor="attributeTermKeyword">{t('표준 용어')}</label>
        {term ? (
          <p className="logical-term">
            <Label variant="accent">{termLabel({ commonStandardTermName: term.name })}</Label>
            <span>{t('표준 도메인')} : {term.domainName ? `${term.domainName}${term.domainDataType ? ` · ${term.domainDataType}` : ''}` : '-'}</span>
            <button type="button" className="btn btn-sm" onClick={() => setTerm(null)}>{t('연결 해제')}</button>
          </p>
        ) : (
          <p className="gh-muted">{t('연결된 표준 용어가 없습니다.')}</p>
        )}
        <div className="logical-term-search">
          <input type="text" className="form-control" id="attributeTermKeyword" maxLength={100} placeholder={t('표준 용어명으로 검색')} value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void search(); } }} />
          <button type="button" className="btn" onClick={() => void search()}>{t('검색')}</button>
        </div>
        {results && (results.length ? (
          <ul className="logical-term-results">
            {results.map((found) => (
              <li key={found.id}>
                <button type="button" onClick={() => pick(found)}>
                  <strong>{termLabel(found)}</strong>
                  <small>{[found.commonStandardTermAbbreviation, found.commonStandardDomainName, found.dataType].filter(Boolean).join(' · ')}</small>
                </button>
              </li>
            ))}
          </ul>
        ) : <p className="gh-muted">{t('검색된 표준 용어가 없습니다.')}</p>)}
      </div>

      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="attributeDescription">{t('설명')}</label>
        <textarea className="form-control" id="attributeDescription" maxLength={1000} rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <button type="submit" className="btn btn-primary" disabled={busy || recovery.blocked}>{busy ? t('처리 중…') : t('저장')}</button>
    </form>
  );
}

function IdentifierTab({ entity, editable, onChanged, subtype = false }: { entity: LogicalEntity; editable: boolean; onChanged: () => void; subtype?: boolean }) {
  const dialog = useDialog();

  const openForm = (identifier?: LogicalIdentifier) => {
    if (!entity.attributes.length) return dialog.alert(t('속성을 먼저 등록해주세요.'));
    dialog.popup({
      title: identifier ? t('식별자 수정') : t('식별자 추가'),
      size: 'wide',
      content: ({ close }: DialogControls) => <IdentifierForm entity={entity} identifier={identifier} subtype={subtype} onSaved={() => { close(); onChanged(); }} />,
    });
  };

  const remove = (identifier: LogicalIdentifier) => {
    dialog.confirm(t('{0} 식별자를 삭제하시겠습니까?', identifier.identifierName), async () => {
      await deleteIdentifierGuarded(dialog, entity.id, identifier, onChanged);
    });
  };

  return (
    <>
      {subtype && <h4 className="subtype-section-title">{t('직접 정의 보조 식별자')}</h4>}
      {editable && <div className="logical-tab-actions"><button type="button" className="btn btn-primary" onClick={() => openForm()}>{subtype ? t('보조 식별자 추가') : t('식별자 추가')}</button></div>}
      {entity.identifiers.length === 0 ? (
        <p className="gh-muted">{t('등록된 식별자가 없습니다.')}</p>
      ) : (
        <div className="dict-detail-columns">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>{[t('식별자명'), t('구분'), t('구성 속성 (순서대로)'), ...(editable ? [t('관리')] : [])].map((h) => <th key={h}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {entity.identifiers.map((i) => (
                  <tr key={i.id}>
                    <td>{i.identifierName}</td>
                    <td className="text-center">{i.isPrimary ? <Label variant="accent">{t('주 식별자')}</Label> : <Label>{t('보조 식별자')}</Label>}</td>
                    <td>{i.attributeNames.join(' + ')}{i.attributeIds.length > 1 && <span className="gh-muted"> · {t('복합')}</span>}</td>
                    {editable && (
                      <td>
                        <div className="logical-actions">
                          <button type="button" className="btn btn-sm" onClick={() => openForm(i)}>{t('수정')}</button>
                          <button type="button" className="btn btn-sm btn-danger" onClick={() => remove(i)}>{t('삭제')}</button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}

/** 식별자 등록·수정 (엔터티 상세·논리 ERD 편집 공용) : 주/보조 구분, 구성 속성을 고른 순서대로 (복합 가능) */
export function IdentifierForm({ entity, identifier, onSaved, subtype = false }: { entity: LogicalEntity; identifier?: LogicalIdentifier; onSaved: () => void; subtype?: boolean }) {
  const dialog = useDialog();
  const [name, setName] = useState(identifier?.identifierName ?? '');
  // 서브타입은 자체 주 식별자를 둘 수 없다 (루트 슈퍼타입의 주 식별자를 상속 — 서버도 거절한다)
  const [primary, setPrimary] = useState(!subtype && !!identifier?.isPrimary);
  // 구성 속성 : 고른 순서가 식별자 속성 순서다. 같은 엔터티의 속성만 고를 수 있고 한 번씩만 넣는다
  const [ids, setIds] = useState<number[]>(identifier?.attributeIds ?? []);
  const attributeName = new Map(entity.attributes.map((a) => [a.id, a.attributeName]));
  const available = entity.attributes.filter((a) => !ids.includes(a.id));
  const otherPrimary = entity.identifiers.find((i) => i.isPrimary && i.id !== identifier?.id);
  const [busy, run] = useSubmitting();
  type Values = { name: string; primary: boolean; ids: number[]; names: Map<number, string> };
  const recovery = useConflictRecovery<Values>({
    initial: { name: identifier?.identifierName ?? '', primary: !subtype && !!identifier?.isPrimary, ids: identifier?.attributeIds ?? [], names: attributeName },
    base: identifier?.modifiedAt,
    fields: [
      { key: 'name', label: t('식별자명'), get: (v) => v.name },
      { key: 'primary', label: t('주 식별자'), get: (v) => v.primary, show: (v) => (v.primary ? t('예') : t('아니요')) },
      { key: 'ids', label: t('구성 속성 (순서대로)'), get: (v) => v.ids, show: (v) => v.ids.map((id) => v.names.get(id) ?? `#${id}`).join(', ') },
    ],
    load: async () => {
      const detail = await api<LogicalEntity>(`/api/logical/entity/${entity.id}`, { silent: true });
      const latest = detail.identifiers.find((i) => i.id === identifier?.id);
      if (!latest) throw new ApiError(400, t('식별자를 찾을 수 없습니다.'), 'LOGICAL_MODEL_IS_NOT_DEFINED');
      return { values: { name: latest.identifierName, primary: !subtype && !!latest.isPrimary, ids: latest.attributeIds, names: new Map(detail.attributes.map((a) => [a.id, a.attributeName])) }, base: latest.modifiedAt ?? null };
    },
  });

  const move = (index: number, delta: number) => {
    const next = [...ids];
    [next[index], next[index + delta]] = [next[index + delta], next[index]];
    setIds(next);
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('식별자명은(는) 필수 입력 항목입니다.'));
    if (!ids.length) return dialog.alert(t('식별자 속성을 1개 이상 선택해주세요.'));
    dialog.confirm(identifier ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), () => void run(async () => {
      try {
        await api('/api/logical/identifier', { method: 'POST', conflict: 'handled', body: { id: identifier?.id ?? null, entityId: entity.id, identifierName: name.trim(), isPrimary: primary, attributeIds: ids, modifiedAt: identifier ? recovery.base : null } });
      } catch (error) {
        if (recovery.catchConflict(error, { name, primary, ids, names: attributeName })) return;
        throw error;
      }
      onSaved();
    }));
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      {recovery.notice((latest, picks) => {
        if (!picks.has('name')) setName(latest.name);
        if (!picks.has('primary')) setPrimary(latest.primary);
        // 구성 속성 : 지금 엔터티에 없는 속성(그 사이 삭제)은 뺀다
        if (!picks.has('ids')) setIds(latest.ids.filter((id) => attributeName.has(id)));
      })}
      <div className="form-group">
        <label htmlFor="identifierName">{t('식별자명')}</label>
        <input type="text" className="form-control" id="identifierName" maxLength={255} required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-check">
        <input type="checkbox" className="form-check-input" id="identifierPrimary" checked={primary} disabled={subtype} onChange={(e) => setPrimary(e.target.checked)} />
        <label className="form-check-label" htmlFor="identifierPrimary">{t('주 식별자')}</label>
      </div>
      {subtype && <p className="form-help">{t('서브타입은 자체 주 식별자를 둘 수 없습니다. 루트 슈퍼타입의 주 식별자를 상속합니다.')}</p>}
      {primary && otherPrimary && <p className="form-help">{t('주 식별자는 엔터티에 하나만 둘 수 있습니다. 현재 주 식별자 : {0}', otherPrimary.identifierName)}</p>}

      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="identifierAttribute">{t('구성 속성 (순서대로)')}</label>
        {ids.length === 0 && <p className="gh-muted">{t('식별자 속성을 1개 이상 선택해주세요.')}</p>}
        <ol className="logical-order-list">
          {ids.map((id, index) => (
            <li key={id}>
              <span>{attributeName.get(id) ?? `#${id}`}</span>
              <span className="logical-actions">
                <button type="button" className="btn btn-sm" aria-label={t('위로')} title={t('위로')} disabled={index === 0} onClick={() => move(index, -1)}>↑</button>
                <button type="button" className="btn btn-sm" aria-label={t('아래로')} title={t('아래로')} disabled={index === ids.length - 1} onClick={() => move(index, 1)}>↓</button>
                <button type="button" className="btn btn-sm" onClick={() => setIds(ids.filter((v) => v !== id))}>{t('빼기')}</button>
              </span>
            </li>
          ))}
        </ol>
        <select className="form-control" id="identifierAttribute" value="" disabled={!available.length} onChange={(e) => e.target.value && setIds([...ids, Number(e.target.value)])}>
          <option value="">{available.length ? t('속성을 골라 추가') : t('모든 속성을 추가했습니다')}</option>
          {available.map((a) => <option key={a.id} value={a.id}>{a.attributeName}</option>)}
        </select>
      </div>
      <button type="submit" className="btn btn-primary" disabled={busy || recovery.blocked}>{busy ? t('처리 중…') : t('저장')}</button>
    </form>
  );
}

/** 이 엔터티가 걸린 관계 (조회 전용). 관계 등록·수정은 논리 모델 관리의 관계 탭에서 한다 */
function RelationshipTab({ relationships, loading, failed }: { relationships: LogicalRelationship[]; loading: boolean; failed: boolean }) {
  if (failed) return <p role="alert">{t('관계를 불러오지 못했습니다.')}</p>;
  if (loading) return <p className="gh-muted">{t('불러오는 중입니다.')}</p>;
  if (!relationships.length) return <p className="gh-muted">{t('관계가 없습니다.')}</p>;
  return (
    <div className="dict-detail-columns">
      <div className="table-wrap">
        <table>
          <thead>
            <tr>{[t('관계명'), t('출발 엔터티'), t('도착 엔터티'), t('식별 관계'), t('카디널리티'), t('승인 상태')].map((h) => <th key={h}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {relationships.map((r) => (
              <tr key={r.id}>
                <td>{r.relationshipName || '-'}</td>
                <td>{r.sourceEntityName}</td>
                <td>{r.targetEntityName}</td>
                <td className="text-center">{r.isIdentifying ? t('식별') : t('비식별')}</td>
                <td className="text-center gh-mono" title={r.cardinality}>{cardinalityText(r)}</td>
                <td className="text-center">{r.isApproval ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="gh-muted">{t('관계는 논리 모델 관리 화면의 관계 탭에서 등록·수정합니다.')}</p>
    </div>
  );
}

/** 엔터티 등록 (논리 모델 관리·논리 ERD 편집 공용). 이름 중복(모델 안, 대소문자 무시)·권한은 서버에서 검증한다 */
/**
 * 엔터티 등록. 주제 영역(M-6 후속 4차, D-17·D-24) : areas 를 주면 처음 소속을 고른다 — 기본값은 지금 고른 영역(defaultAreaId, 전체·미분류에서 열면 미분류),
 * 등록 전에 다른 영역·미분류로 바꿀 수 있다. 엔터티 등록과 소속 지정은 서버에서 한 트랜잭션이다
 */
export function EntityForm({ modelId, onSaved, areas, defaultAreaId }: {
  modelId: number;
  onSaved: (entityId: number) => void;
  areas?: SubjectArea[];
  defaultAreaId?: number | null;
}) {
  const dialog = useDialog();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [area, setArea] = useState(defaultAreaId == null ? '' : String(defaultAreaId));

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('엔터티명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(t('저장하시겠습니까?'), async () => {
      const saved = await api<{ id: number }>('/api/logical/entity', {
        method: 'POST', body: { modelId, entityName: name.trim(), entityDescription: description.trim() || null, subjectAreaId: area ? Number(area) : null },
      });
      onSaved(saved.id);
    });
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      <div className="form-group">
        <label htmlFor="newEntityName">{t('엔터티명')}</label>
        <input type="text" className="form-control" id="newEntityName" maxLength={255} required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="newEntityDescription">{t('설명')}</label>
        <textarea className="form-control" id="newEntityDescription" maxLength={1000} rows={3} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      {areas && areas.length > 0 && (
        <div className="form-group" style={{ marginBottom: 10 }}>
          <label htmlFor="newEntityArea">{t('주제 영역')}</label>
          <select className="form-control" id="newEntityArea" value={area} onChange={(e) => setArea(e.target.value)}>
            <option value="">{t('미분류')}</option>
            {areas.map((a) => <option key={a.id} value={a.id}>{`${'\u00a0\u00a0'.repeat(a.depth - 1)}${a.areaName}`}</option>)}
          </select>
        </div>
      )}
      <button type="submit" className="btn btn-primary">{t('저장')}</button>
    </form>
  );
}

// ------------------------------------------------------------------ 슈퍼타입·서브타입 (M-6 후속 5차) : 상속 정보는 읽기 전용 — 정의 엔터티로 이동해 고친다

/** 상속 속성 (조상에 정의된 원본 속성, 루트부터) : 원본 속성 ID·정의 엔터티를 보여 주고 편집하지 않는다. 색만이 아니라 "상속" 글자로 구분한다 */
function InheritedAttributes({ inheritance, onOpen }: { inheritance: Inheritance; onOpen: (entityId: number) => void }) {
  const rows = inheritance.attributes.filter((a) => a.inherited);
  if (!rows.length && !inheritance.rootPrimaryKeyMissing) return null;
  return (
    <section className="subtype-inherited" aria-label={t('상속 속성')}>
      <h4 className="subtype-section-title">{t('상속 속성 (읽기 전용)')}</h4>
      {inheritance.rootPrimaryKeyMissing && <p className="form-help" role="note">{t('루트 슈퍼타입 {0} 에 주 식별자가 아직 없습니다. 서브타입 승인·상속 주 식별자 속성 짝·물리 생성이 제한됩니다.', inheritance.rootEntityName)}</p>}
      {rows.length > 0 && (
        <div className="dict-detail-columns">
          <div className="table-wrap">
            <table>
              <thead><tr>{[t('속성명'), t('식별자'), t('정의 출처'), t('상태'), t('필수'), t('표준 용어'), t('표준 도메인')].map((h) => <th key={h}>{h}</th>)}</tr></thead>
              <tbody>
                {rows.map((a) => (
                  <tr key={a.attributeId}>
                    <td>{a.attributeName}</td>
                    <td className="text-center">{a.primary ? <Label variant="accent">{t('주 식별자 {0}', a.keyOrder ?? '')}</Label> : <span className="gh-muted">-</span>}</td>
                    <td><button type="button" className="logical-link" title={t('정의 엔터티로 이동')} onClick={() => onOpen(a.definedEntityId)}>{a.definedEntityName}</button></td>
                    <td className="text-center"><Label>{t('상속')}{a.depth > 1 ? ` · ${t('{0}단계 위', a.depth)}` : ''}</Label></td>
                    <td className="text-center">{a.isRequired ? <Label variant="attention">{t('필수')}</Label> : <span className="gh-muted">-</span>}</td>
                    <td>{a.termName ? termLabel({ commonStandardTermName: a.termName }) : <span className="gh-muted">-</span>}</td>
                    <td>{a.domainName ?? <span className="gh-muted">-</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  );
}

/** 상속 식별자 : 루트의 주 식별자(구성 순서 그대로)와 조상의 보조 식별자 */
function InheritedIdentifiers({ inheritance, onOpen }: { inheritance: Inheritance; onOpen: (entityId: number) => void }) {
  const rows = inheritance.identifiers.filter((i) => i.inherited);
  if (!rows.length && !(inheritance.parent && inheritance.rootPrimaryKeyMissing)) return null;
  return (
    <section className="subtype-inherited" aria-label={t('상속 식별자')}>
      <h4 className="subtype-section-title">{t('상속 식별자 (읽기 전용)')}</h4>
      {inheritance.rootPrimaryKeyMissing && <p className="form-help" role="note">{t('상속할 주 식별자가 아직 정의되지 않았습니다 (루트 슈퍼타입 {0}).', inheritance.rootEntityName)}</p>}
      {rows.length > 0 && (
        <div className="dict-detail-columns">
          <div className="table-wrap">
            <table>
              <thead><tr>{[t('식별자명'), t('구분'), t('구성 속성 (순서대로)'), t('정의 출처')].map((h) => <th key={h}>{h}</th>)}</tr></thead>
              <tbody>
                {rows.map((i) => (
                  <tr key={i.identifierId}>
                    <td>{i.identifierName}</td>
                    <td className="text-center">{i.isPrimary ? <Label variant="accent">{t('상속 주 식별자')}</Label> : <Label>{t('상속 보조 식별자')}</Label>}</td>
                    <td>{i.attributeNames.join(' + ')}</td>
                    <td><button type="button" className="logical-link" title={t('정의 엔터티로 이동')} onClick={() => onOpen(i.definedEntityId)}>{i.definedEntityName}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  );
}

/** 상속 관계 : 조상에 정의된 일반 관계 (이 엔터티에 복제하지 않는다 — 고치거나 지우려면 정의 엔터티에서) */
function InheritedRelationships({ inheritance, onOpen }: { inheritance: Inheritance; onOpen: (entityId: number) => void }) {
  if (!inheritance.inheritedRelationships.length) return null;
  const role = (r: Inheritance['inheritedRelationships'][number]) => (r.role === 'SOURCE' ? t('출발') : r.role === 'TARGET' ? t('도착') : t('자기 참조'));
  return (
    <section className="subtype-inherited" aria-label={t('상속 관계')}>
      <h4 className="subtype-section-title">{t('상속 관계 (읽기 전용)')}</h4>
      <div className="dict-detail-columns">
        <div className="table-wrap">
          <table>
            <thead><tr>{[t('관계명'), t('출발 엔터티'), t('도착 엔터티'), t('카디널리티'), t('정의 출처'), t('역할')].map((h) => <th key={h}>{h}</th>)}</tr></thead>
            <tbody>
              {inheritance.inheritedRelationships.map((r) => (
                <tr key={r.relationship.id}>
                  <td>{r.relationship.relationshipName || '-'}</td>
                  <td>{r.relationship.sourceEntityName}</td>
                  <td>{r.relationship.targetEntityName}</td>
                  <td className="text-center gh-mono">{cardinalityText(r.relationship)}</td>
                  <td><button type="button" className="logical-link" title={t('정의 엔터티로 이동')} onClick={() => onOpen(r.definedEntityId)}>{r.definedEntityName}</button></td>
                  <td className="text-center"><Label>{t('상속')} · {role(r)}</Label></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <p className="gh-muted">{t('참여 수는 슈퍼타입에 정의한 값이며, 서브타입별로 따로 적용되는 제약이라고 단정하지 않습니다.')}</p>
    </section>
  );
}

/** 상속 탭 : 단계·루트·조상, 직접 슈퍼타입 그룹(배타성·완전성·분류 값), 이 엔터티를 슈퍼타입으로 하는 그룹 */
function InheritanceTab({ inheritance, loading, failed, onOpen }: { inheritance?: Inheritance; loading: boolean; failed: boolean; onOpen: (entityId: number) => void }) {
  if (failed) return <p role="alert">{t('상속 정보를 불러오지 못했습니다.')}</p>;
  if (loading || !inheritance) return <p className="gh-muted">{t('불러오는 중입니다.')}</p>;
  const parent = inheritance.parent;
  if (!parent && !inheritance.groups.length) {
    return <p className="gh-muted">{t('슈퍼타입·서브타입 구조에 속하지 않은 엔터티입니다. 서브타입 그룹은 논리 모델 관리의 [서브타입 그룹] 탭에서 등록합니다.')}</p>;
  }
  return (
    <div className="subtype-inheritance">
      <dl className="subtype-facts">
        <dt>{t('상속 단계')}</dt><dd>{t('{0}단계 (루트 = 1단계, 최대 3단계)', inheritance.level)}</dd>
        <dt>{t('루트 슈퍼타입')}</dt>
        <dd>{inheritance.rootEntityId === inheritance.entityId ? t('이 엔터티') : <button type="button" className="logical-link" onClick={() => onOpen(inheritance.rootEntityId)}>{inheritance.rootEntityName}</button>}</dd>
        {inheritance.ancestors.length > 0 && (
          <>
            <dt>{t('상속 경로')}</dt>
            <dd>
              {inheritance.ancestors.map((a) => <span key={a.entityId}><button type="button" className="logical-link" onClick={() => onOpen(a.entityId)}>{a.entityName}</button> → </span>)}
              <strong>{inheritance.entityName}</strong>
            </dd>
          </>
        )}
        {parent && (
          <>
            <dt>{t('직접 슈퍼타입')}</dt>
            <dd><button type="button" className="logical-link" onClick={() => onOpen(parent.supertypeEntityId)}>{parent.supertypeEntityName}</button> · {t('그룹 {0}', parent.groupName)}</dd>
            <dt>{t('분류 정책')}</dt>
            <dd><b className="subtype-symbol">{disjointSymbol(parent.overlapping)}</b> {disjointness(parent.overlapping)} · {completeness(parent.total)}</dd>
            <dt>{t('분류 속성 · 분류 값')}</dt>
            <dd>{parent.classificationAttributeName ?? '-'} · {parent.classificationValue ?? '-'}</dd>
          </>
        )}
        <dt>{t('하위 엔터티')}</dt><dd>{t('{0}개 (모든 단계)', inheritance.descendantCount)}</dd>
      </dl>
      {inheritance.groups.length > 0 && (
        <>
          <h4 className="subtype-section-title">{t('이 엔터티를 슈퍼타입으로 하는 서브타입 그룹')}</h4>
          {inheritance.groups.map((g) => (
            <section key={g.id} className="subtype-group-card">
              <p>
                <strong>{g.groupName}</strong> <b className="subtype-symbol">{disjointSymbol(g.overlapping)}</b> {disjointness(g.overlapping)} · {completeness(g.total)}
                {g.classificationAttributeName && <span className="gh-muted"> · {t('분류 속성 {0}', g.classificationAttributeName)}</span>}
              </p>
              <ul>
                {g.subtypes.map((m) => (
                  <li key={m.entityId}>
                    <button type="button" className="logical-link" onClick={() => onOpen(m.entityId)}>{m.entityName}</button>
                    {m.classificationValue && <span className="gh-muted gh-mono"> = {m.classificationValue}</span>}
                    {m.isApproval && <> <Label variant="success" dot>{t('승인')}</Label></>}
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </>
      )}
      <p className="gh-muted">{t('그룹 등록·수정·삭제는 논리 모델 관리의 [서브타입 그룹] 탭에서 합니다. 배타성·완전성은 모델링 정보이며 실제 업무 데이터로 검사하지 않습니다.')}</p>
    </div>
  );
}
