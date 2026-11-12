import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t, tc } from '../../lib/i18n';
import { EntityForm, useEntityDetail } from './entityDetail';
import { relationLabel, RelationshipForm } from './relationshipForm';
import { cardinalityText, participation, useLogicalGraph, useLogicalModels, type GraphEntity, type LogicalModel, type LogicalRelationship } from './model';
import '../../styles/logical.css';

/**
 * 논리 모델 관리 : 모델 선택·등록·수정·삭제, 모델의 엔터티(상세 대화상자에서 속성·식별자)와 관계 관리.
 * 조회는 VIEWER, 변경 버튼은 USER 이상(logical-model-edit), 승인은 승인 권한자 — 서버 API 가 같은 기준으로 다시 검사한다.
 */
export function LogicalModelPage() {
  const dialog = useDialog();
  const navigate = useNavigate();
  const { canView, approvalAvailable } = useAuth();
  const canEdit = canView('logical-model-edit');
  const { models, selected, select, refresh: refreshModels, isPending, isError } = useLogicalModels();
  const graph = useLogicalGraph(selected?.id);
  const openEntityDetail = useEntityDetail();
  const [tab, setTab] = useState<'entities' | 'relationships'>('entities');
  const [params, setParams] = useSearchParams();

  const entities = graph.data?.entities ?? [];
  const relationships = graph.data?.relationships ?? [];
  const relationCount = (entityId: number) => relationships.filter((r) => r.sourceEntityId === entityId || r.targetEntityId === entityId).length;
  const refresh = () => void graph.refresh();
  const erdPath = (entityId?: number) => `/logicalErd?modelId=${selected?.id}${entityId != null ? `&entityId=${entityId}` : ''}`;

  const openEntity = (entityId: number) => openEntityDetail(entityId, { onChanged: refresh, onErd: canView('logical-erd') ? () => navigate(erdPath(entityId)) : undefined });

  // 논리 ERD 에서 넘어온 경우 (?modelId=&entityId=) : 그 엔터티의 상세를 한 번 연다
  const linkedEntity = params.get('entityId');
  useEffect(() => {
    if (!linkedEntity || !graph.data) return;
    setParams({ modelId: String(graph.data.modelId) }, { replace: true });
    const entity = graph.data.entities.find((e) => String(e.id) === linkedEntity);
    if (entity) openEntity(entity.id);
  }, [linkedEntity, graph.data]);

  const openModelForm = (model?: LogicalModel) => {
    dialog.open({
      title: model ? t('논리 모델 수정') : t('논리 모델 등록'),
      content: ({ close }: DialogControls) => (
        <ModelForm model={model} onSaved={(saved) => {
          close();
          void refreshModels();
          select(saved.id);
        }} />
      ),
    });
  };

  const removeModel = (model: LogicalModel) => {
    dialog.confirm(t('{0} 논리 모델을 삭제하시겠습니까?', model.modelName), async () => {
      await api(`/api/logical/model/${model.id}`, { method: 'DELETE' });
      setParams({}, { replace: true });
      dialog.alert(t('정상적으로 삭제되었습니다.'), () => void refreshModels());
    });
  };

  const openEntityForm = () => {
    dialog.open({
      title: t('엔터티 등록'),
      content: ({ close }: DialogControls) => (
        <EntityForm modelId={selected!.id} onSaved={(entityId) => {
          close();
          refresh();
          openEntity(entityId); // 이어서 속성·식별자를 등록한다
        }} />
      ),
    });
  };

  const openRelationshipForm = (relationship?: LogicalRelationship) => {
    if (entities.length === 0) return dialog.alert(t('엔터티를 먼저 등록해주세요.'));
    dialog.open({
      title: relationship ? t('관계 수정') : t('관계 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => (
        <RelationshipForm modelId={selected!.id} entities={entities} relationship={relationship} onSaved={() => { close(); refresh(); }} />
      ),
    });
  };

  const removeRelationship = (relationship: LogicalRelationship) => {
    dialog.confirm(t('{0} 관계를 삭제하시겠습니까?', relationLabel(relationship)), async () => {
      await api(`/api/logical/relationship/${relationship.id}`, { method: 'DELETE' });
      refresh();
    });
  };

  const toggleRelationshipApproval = (relationship: LogicalRelationship) => {
    const approved = !!relationship.isApproval;
    dialog.confirm(approved ? t('이 관계를 승인취소하시겠습니까?') : t('이 관계를 승인하시겠습니까?'), async () => {
      await api(`/api/logical/relationship/${relationship.id}/approval/${!approved}`, { method: 'PATCH' });
      refresh();
    });
  };

  const modelDeleteBlocked = entities.length ? t('엔터티가 있는 논리 모델은 삭제할 수 없습니다. 엔터티를 먼저 삭제해주세요.') : '';
  const entityHeaders = [t('엔터티명'), t('설명'), t('속성 수'), t('관계 수'), t('승인 상태'), t('관리')];
  const relationshipHeaders = [t('관계명'), t('출발 엔터티'), t('도착 엔터티'), t('식별 관계'), t('카디널리티'), t('승인 상태'), ...(canEdit || approvalAvailable ? [t('관리')] : [])];
  const emptyText = graph.isPending ? t('불러오는 중입니다.') : graph.isError ? t('논리 모델을 불러오지 못했습니다.') : '';

  return (
    <>
      <PageHeader category={t('논리 모델 관리')} title={t('논리 모델 관리')}
        description={t('업무 관점의 논리 모델을 관리합니다. 모델을 고른 뒤 엔터티·속성·식별자·관계를 등록하며, 물리 테이블·컬럼과는 독립입니다.')} />

      <div className="grid-header">
        <div className="search-container">
          <select className="search-column-select" aria-label={t('논리 모델')} value={selected?.id ?? ''} disabled={!models.length} onChange={(e) => select(Number(e.target.value))}>
            {!models.length && <option value="">{isPending ? t('불러오는 중입니다.') : t('논리 모델 없음')}</option>}
            {models.map((m) => <option key={m.id} value={m.id}>{m.modelName}</option>)}
          </select>
        </div>
        <div className="button-container">
          {canEdit && <button type="button" className="btn btn-primary" onClick={() => openModelForm()}>{t('모델 등록')}</button>}
          {canEdit && selected && <button type="button" className="btn" onClick={() => openModelForm(selected)}>{t('모델 수정')}</button>}
          {canEdit && selected && (
            <button type="button" className="btn btn-danger" disabled={!graph.data || !!modelDeleteBlocked} title={modelDeleteBlocked || undefined} onClick={() => removeModel(selected)}>{t('모델 삭제')}</button>
          )}
          {selected && canView('logical-erd') && <button type="button" className="btn" onClick={() => navigate(erdPath())}>{t('논리 ERD')}</button>}
        </div>
      </div>

      {isError && <p role="alert">{t('논리 모델 목록을 불러오지 못했습니다.')}</p>}
      {!isPending && !isError && !selected && (
        <p className="logical-empty">{canEdit ? t('등록된 논리 모델이 없습니다. [모델 등록] 으로 첫 논리 모델을 만들어주세요.') : t('등록된 논리 모델이 없습니다.')}</p>
      )}

      {selected && (
        <>
          {selected.modelDescription && <p className="page-desc">{selected.modelDescription}</p>}
          <ul className="nav nav-tabs">
            <li className="nav-item">
              <a href="#logical-entities" className={`nav-link${tab === 'entities' ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab('entities'); }}>{t('엔터티 ({0})', entities.length)}</a>
            </li>
            <li className="nav-item">
              <a href="#logical-relationships" className={`nav-link${tab === 'relationships' ? ' active' : ''}`} onClick={(e) => { e.preventDefault(); setTab('relationships'); }}>{t('관계 ({0})', relationships.length)}</a>
            </li>
          </ul>

          <div className="grid-header mt-3">
            <div className="button-container">
              {canEdit && tab === 'entities' && <button type="button" className="grd-add btn btn-primary" onClick={openEntityForm}>{t('엔터티 등록')}</button>}
              {canEdit && tab === 'relationships' && <button type="button" className="grd-add btn btn-primary" onClick={() => openRelationshipForm()}>{t('관계 등록')}</button>}
            </div>
          </div>

          <div className="dt-container">
            <div className="dt-layout-row dt-layout-table">
              <div className="dt-layout-cell dt-layout-full">
                {tab === 'entities' ? (
                  <table id="logicalEntityGrid" className="table table-striped table-bordered dataTable grid-fixed">
                    <colgroup>{ENTITY_WIDTHS.map((width, index) => <col key={index} style={{ width }} />)}</colgroup>
                    <thead><tr>{entityHeaders.map((h) => <th key={h}>{h}</th>)}</tr></thead>
                    <tbody>
                      {entities.map((entity: GraphEntity) => (
                        <tr key={entity.id}>
                          <td><button type="button" className="logical-link" onClick={() => openEntity(entity.id)}>{entity.name}</button></td>
                          <td>{entity.description || '-'}</td>
                          <td>{entity.attributes.length}</td>
                          <td>{relationCount(entity.id)}</td>
                          <td><ApprovalLabel value={entity.isApproval} /></td>
                          <td>
                            <div className="logical-actions">
                              <button type="button" className="btn btn-sm" onClick={() => openEntity(entity.id)}>{t('상세')}</button>
                              {canView('logical-erd') && <button type="button" className="btn btn-sm" onClick={() => navigate(erdPath(entity.id))}>{t('ERD')}</button>}
                            </div>
                          </td>
                        </tr>
                      ))}
                      {entities.length === 0 && <tr><td colSpan={entityHeaders.length} className="dt-empty">{emptyText || t('등록된 엔터티가 없습니다.')}</td></tr>}
                    </tbody>
                  </table>
                ) : (
                  <table id="logicalRelationshipGrid" className="table table-striped table-bordered dataTable grid-fixed">
                    <colgroup>{(canEdit || approvalAvailable ? RELATIONSHIP_WIDTHS_MANAGED : RELATIONSHIP_WIDTHS).map((width, index) => <col key={index} style={{ width }} />)}</colgroup>
                    <thead><tr>{relationshipHeaders.map((h) => <th key={h}>{h}</th>)}</tr></thead>
                    <tbody>
                      {relationships.map((r) => (
                        <tr key={r.id}>
                          <td>{r.relationshipName || '-'}</td>
                          <td>{r.sourceEntityName} <span className="gh-muted gh-mono">({participation(r.sourceMin, r.sourceMax)})</span></td>
                          <td>{r.targetEntityName} <span className="gh-muted gh-mono">({participation(r.targetMin, r.targetMax)})</span></td>
                          <td>{r.isIdentifying ? <Label variant="accent">{t('식별')}</Label> : <span className="gh-muted">{t('비식별')}</span>}</td>
                          <td className="gh-mono" title={cardinalityText(r)}>{r.cardinality}</td>
                          <td><ApprovalLabel value={r.isApproval} /></td>
                          {(canEdit || approvalAvailable) && (
                            <td>
                              <div className="logical-actions">
                                {canEdit && (
                                  <>
                                    <button type="button" className="btn btn-sm" disabled={!!r.isApproval} title={r.isApproval ? t('승인된 관계는 승인취소 후에 변경할 수 있습니다.') : undefined} onClick={() => openRelationshipForm(r)}>{t('수정')}</button>
                                    <button type="button" className="btn btn-sm btn-danger" disabled={!!r.isApproval} title={r.isApproval ? t('승인된 관계는 승인취소 후에 변경할 수 있습니다.') : undefined} onClick={() => removeRelationship(r)}>{t('삭제')}</button>
                                  </>
                                )}
                                {approvalAvailable && <button type="button" className="btn btn-sm" onClick={() => toggleRelationshipApproval(r)}>{r.isApproval ? t('승인취소') : tc('버튼', '승인')}</button>}
                              </div>
                            </td>
                          )}
                        </tr>
                      ))}
                      {relationships.length === 0 && <tr><td colSpan={relationshipHeaders.length} className="dt-empty">{emptyText || t('등록된 관계가 없습니다.')}</td></tr>}
                    </tbody>
                  </table>
                )}
              </div>
            </div>
          </div>
        </>
      )}
    </>
  );
}

/** 목록 컬럼 폭 (고정) : 엔터티 6열, 관계 6열 + 관리 */
const ENTITY_WIDTHS = ['24%', '34%', '9%', '9%', '11%', '13%'];
const RELATIONSHIP_WIDTHS = ['20%', '22%', '22%', '12%', '12%', '12%'];
const RELATIONSHIP_WIDTHS_MANAGED = ['16%', '19%', '19%', '10%', '10%', '10%', '16%'];


function ApprovalLabel({ value }: { value: boolean | null }) {
  return value ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>;
}

function ModelForm({ model, onSaved }: { model?: LogicalModel; onSaved: (model: LogicalModel) => void }) {
  const dialog = useDialog();
  const [name, setName] = useState(model?.modelName ?? '');
  const [description, setDescription] = useState(model?.modelDescription ?? '');

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('모델명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(model ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), async () => {
      // 이름 중복(프로젝트 안, 대소문자 무시)은 서버에서 검증한다
      const saved = await api<LogicalModel>('/api/logical/model', { method: 'POST', body: { id: model?.id ?? null, modelName: name.trim(), modelDescription: description.trim() || null } });
      dialog.alert(model ? t('정상적으로 수정되었습니다.') : t('정상적으로 저장되었습니다.'), () => onSaved(saved));
    });
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      <div className="form-group">
        <label htmlFor="modelName">{t('모델명')}</label>
        <input type="text" className="form-control" id="modelName" maxLength={255} required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="modelDescription">{t('설명')}</label>
        <textarea className="form-control" id="modelDescription" maxLength={1000} rows={3} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <button type="submit" className="btn btn-primary">{t('저장')}</button>
    </form>
  );
}
