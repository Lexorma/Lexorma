import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t, tc } from '../../lib/i18n';
import { searchTerms, termLabel, type Term } from '../column/terms';
import { historyPath } from '../history/model';
import { mappingPath, useMappingLookup } from '../mapping/model';
import { cardinalityText, type LogicalAttribute, type LogicalEntity, type LogicalIdentifier, type LogicalRelationship } from './model';
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

export type EntityDetailTab = 'basic' | 'attributes' | 'identifiers' | 'relationships';
type Tab = EntityDetailTab;

function EntityDetail({ entityId, options, controls }: { entityId: number; options: EntityDetailOptions; controls: DialogControls }) {
  const dialog = useDialog();
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
  const modelId = entity.data?.modelId;
  const relationships = useQuery({
    queryKey: ['logical-relationships', modelId],
    queryFn: () => api<LogicalRelationship[]>(`/api/logical/model/${modelId}/relationship`, { silent: true }),
    enabled: modelId != null,
    gcTime: 0,
  });

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
  const related = (relationships.data ?? []).filter((r) => r.sourceEntityId === entityId || r.targetEntityId === entityId);
  const changed = () => {
    void entity.refetch();
    void relationships.refetch();
    options.onChanged?.();
  };

  const toggleApproval = () => {
    dialog.confirm(approved ? t('이 엔터티를 승인취소하시겠습니까?') : t('이 엔터티를 승인하시겠습니까?'), async () => {
      await api(`/api/logical/entity/${entityId}/approval/${!approved}`, { method: 'PATCH' });
      dialog.alert(approved ? t('정상적으로 승인취소처리 되었습니다.') : t('정상적으로 승인처리 되었습니다.'), changed);
    });
  };

  const remove = () => {
    dialog.confirm(t('{0} 엔터티를 삭제하시겠습니까? 속성과 식별자도 함께 삭제됩니다.', data.entityName), async () => {
      await api(`/api/logical/entity/${entityId}`, { method: 'DELETE' });
      controls.close();
      dialog.alert(t('정상적으로 삭제되었습니다.'), options.onChanged);
    });
  };

  const tabs: [Tab, string][] = [
    ['attributes', t('속성 ({0})', data.attributes.length)],
    ['identifiers', t('식별자 ({0})', data.identifiers.length)],
    ['relationships', t('관계 ({0})', related.length)],
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
        {tab === 'attributes' && <AttributeTab entity={data} editable={detailEditable} onChanged={changed} />}
        {tab === 'identifiers' && <IdentifierTab entity={data} editable={detailEditable} onChanged={changed} />}
        {tab === 'relationships' && <RelationshipTab relationships={related} loading={relationships.isPending} />}
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
          <button type="button" className={approved ? 'btn btn-danger' : 'btn btn-primary'} onClick={toggleApproval}>
            {approved ? t('승인취소') : tc('버튼', '승인')}
          </button>
        )}
        {editable && (
          <button type="button" className="btn btn-danger" disabled={related.length > 0} onClick={remove}
            title={related.length ? t('관계에 사용 중인 엔터티는 삭제할 수 없습니다. 관계를 먼저 정리해주세요.') : undefined}>
            {t('엔터티 삭제')}
          </button>
        )}
      </div>
    </div>
  );
}

function BasicTab({ entity, editable, onSaved }: { entity: LogicalEntity; editable: boolean; onSaved: () => void }) {
  const dialog = useDialog();
  const [name, setName] = useState(entity.entityName);
  const [description, setDescription] = useState(entity.entityDescription ?? '');

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('엔터티명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(t('수정하시겠습니까?'), async () => {
      // 이름 중복(모델 안, 대소문자 무시)·승인 상태는 서버에서 검증한다
      await api('/api/logical/entity', { method: 'POST', body: { id: entity.id, entityName: name.trim(), entityDescription: description.trim() || null } });
      dialog.alert(t('정상적으로 수정되었습니다.'), onSaved);
    });
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      <div className="form-group">
        <label htmlFor="entityName">{t('엔터티명')}</label>
        <input type="text" className="form-control" id="entityName" maxLength={255} required readOnly={!editable} value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="entityDescription">{t('설명')}</label>
        <textarea className="form-control" id="entityDescription" maxLength={1000} rows={3} readOnly={!editable} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      {editable && <button type="submit" className="btn btn-primary">{t('저장')}</button>}
    </form>
  );
}

function AttributeTab({ entity, editable, onChanged }: { entity: LogicalEntity; editable: boolean; onChanged: () => void }) {
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
      await api(`/api/logical/attribute/${attribute.id}`, { method: 'DELETE' });
      onChanged();
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
    dialog.confirm(attribute ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), async () => {
      // 도메인은 보내지 않는다 : 표준 용어의 도메인을 서버가 조회해 내려준다
      await api('/api/logical/attribute', {
        method: 'POST',
        body: { id: attribute?.id ?? null, entityId, attributeName: name.trim(), attributeDescription: description.trim() || null, dataType: dataType.trim() || null, isRequired: required, termId: term?.id ?? null },
      });
      onSaved();
    });
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
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
      <button type="submit" className="btn btn-primary">{t('저장')}</button>
    </form>
  );
}

function IdentifierTab({ entity, editable, onChanged }: { entity: LogicalEntity; editable: boolean; onChanged: () => void }) {
  const dialog = useDialog();

  const openForm = (identifier?: LogicalIdentifier) => {
    if (!entity.attributes.length) return dialog.alert(t('속성을 먼저 등록해주세요.'));
    dialog.popup({
      title: identifier ? t('식별자 수정') : t('식별자 추가'),
      size: 'wide',
      content: ({ close }: DialogControls) => <IdentifierForm entity={entity} identifier={identifier} onSaved={() => { close(); onChanged(); }} />,
    });
  };

  const remove = (identifier: LogicalIdentifier) => {
    dialog.confirm(t('{0} 식별자를 삭제하시겠습니까?', identifier.identifierName), async () => {
      await api(`/api/logical/identifier/${identifier.id}`, { method: 'DELETE' });
      onChanged();
    });
  };

  return (
    <>
      {editable && <div className="logical-tab-actions"><button type="button" className="btn btn-primary" onClick={() => openForm()}>{t('식별자 추가')}</button></div>}
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
export function IdentifierForm({ entity, identifier, onSaved }: { entity: LogicalEntity; identifier?: LogicalIdentifier; onSaved: () => void }) {
  const dialog = useDialog();
  const [name, setName] = useState(identifier?.identifierName ?? '');
  const [primary, setPrimary] = useState(!!identifier?.isPrimary);
  // 구성 속성 : 고른 순서가 식별자 속성 순서다. 같은 엔터티의 속성만 고를 수 있고 한 번씩만 넣는다
  const [ids, setIds] = useState<number[]>(identifier?.attributeIds ?? []);
  const attributeName = new Map(entity.attributes.map((a) => [a.id, a.attributeName]));
  const available = entity.attributes.filter((a) => !ids.includes(a.id));
  const otherPrimary = entity.identifiers.find((i) => i.isPrimary && i.id !== identifier?.id);

  const move = (index: number, delta: number) => {
    const next = [...ids];
    [next[index], next[index + delta]] = [next[index + delta], next[index]];
    setIds(next);
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('식별자명은(는) 필수 입력 항목입니다.'));
    if (!ids.length) return dialog.alert(t('식별자 속성을 1개 이상 선택해주세요.'));
    dialog.confirm(identifier ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), async () => {
      await api('/api/logical/identifier', { method: 'POST', body: { id: identifier?.id ?? null, entityId: entity.id, identifierName: name.trim(), isPrimary: primary, attributeIds: ids } });
      onSaved();
    });
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      <div className="form-group">
        <label htmlFor="identifierName">{t('식별자명')}</label>
        <input type="text" className="form-control" id="identifierName" maxLength={255} required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-check">
        <input type="checkbox" className="form-check-input" id="identifierPrimary" checked={primary} onChange={(e) => setPrimary(e.target.checked)} />
        <label className="form-check-label" htmlFor="identifierPrimary">{t('주 식별자')}</label>
      </div>
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
      <button type="submit" className="btn btn-primary">{t('저장')}</button>
    </form>
  );
}

/** 이 엔터티가 걸린 관계 (조회 전용). 관계 등록·수정은 논리 모델 관리의 관계 탭에서 한다 */
function RelationshipTab({ relationships, loading }: { relationships: LogicalRelationship[]; loading: boolean }) {
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
export function EntityForm({ modelId, onSaved }: { modelId: number; onSaved: (entityId: number) => void }) {
  const dialog = useDialog();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('엔터티명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(t('저장하시겠습니까?'), async () => {
      const saved = await api<{ id: number }>('/api/logical/entity', { method: 'POST', body: { modelId, entityName: name.trim(), entityDescription: description.trim() || null } });
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
      <button type="submit" className="btn btn-primary">{t('저장')}</button>
    </form>
  );
}
