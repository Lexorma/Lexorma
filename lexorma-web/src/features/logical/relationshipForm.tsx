import { useState, type FormEvent } from 'react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { participation, type LogicalRelationship } from './model';
import '../../styles/logical.css';

/** 관계 표시명 : 관계명이 없으면 "출발 → 도착" */
export const relationLabel = (r: LogicalRelationship) => r.relationshipName || `${r.sourceEntityName} → ${r.targetEntityName}`;

/** 한쪽 참여 수 선택값 "최소|최대" (최대 N = 서버의 null = 다). 서버 값이 목록에 없으면(예 2..5) 그 값을 그대로 보존한다 */
const SIDE_OPTIONS = ['1|1', '0|1', '1|N', '0|N'];
const sideValue = (min: number, max: number | null) => `${min}|${max ?? 'N'}`;
const sideLabel = (value: string) => {
  const [min, max] = value.split('|');
  const text = participation(Number(min), max === 'N' ? null : Number(max));
  return `${text} · ${min === '0' ? t('선택') : t('필수')}${max === 'N' ? t(' · 여러 건') : ''}`;
};

/**
 * 관계 등록·수정 (논리 모델 관리·논리 ERD 편집 공용). 출발 = 참조되는 쪽(부모), 도착 = 참조하는 쪽(자식).
 * initial : ERD 에서 연결점을 이어 열 때의 출발·도착 — 연결한 방향이 곧 관계 방향이라고 보지 않고 여기서 확인·교체한다.
 * 같은 엔터티 쌍의 여러 관계, 자기 참조를 막지 않는다 (서버 정책과 같다). 같은 모델의 엔터티인지, 승인된 관계인지는 서버가 검사한다.
 */
export function RelationshipForm({ modelId, entities, relationship, initial, onSaved }: {
  modelId: number;
  entities: { id: number; name: string }[];
  relationship?: LogicalRelationship;
  initial?: { sourceId?: number; targetId?: number };
  onSaved: (saved: LogicalRelationship) => void;
}) {
  const dialog = useDialog();
  const [name, setName] = useState(relationship?.relationshipName ?? '');
  const [description, setDescription] = useState(relationship?.relationshipDescription ?? '');
  const [sourceId, setSourceId] = useState(String(relationship?.sourceEntityId ?? initial?.sourceId ?? ''));
  const [targetId, setTargetId] = useState(String(relationship?.targetEntityId ?? initial?.targetId ?? ''));
  const [identifying, setIdentifying] = useState(!!relationship?.isIdentifying);
  // 기본 1 : 0..N (원본 1건이 대상 여러 건과 연결)
  const [source, setSource] = useState(relationship ? sideValue(relationship.sourceMin, relationship.sourceMax) : '1|1');
  const [target, setTarget] = useState(relationship ? sideValue(relationship.targetMin, relationship.targetMax) : '0|N');

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!sourceId || !targetId) return dialog.alert(t('출발 엔터티와 도착 엔터티를 선택해주세요.'));
    const side = (value: string) => {
      const [min, max] = value.split('|');
      return { min: Number(min), max: max === 'N' ? null : Number(max) }; // N → null (다)
    };
    dialog.confirm(relationship ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), async () => {
      const saved = await api<LogicalRelationship>('/api/logical/relationship', {
        method: 'POST',
        body: {
          id: relationship?.id ?? null, modelId, relationshipName: name.trim() || null, relationshipDescription: description.trim() || null,
          sourceEntityId: Number(sourceId), targetEntityId: Number(targetId), isIdentifying: identifying,
          sourceMin: side(source).min, sourceMax: side(source).max, targetMin: side(target).min, targetMax: side(target).max,
        },
      });
      onSaved(saved);
    });
  };

  const entitySelect = (id: string, label: string, value: string, onChange: (value: string) => void) => (
    <div className="form-group">
      <label htmlFor={id}>{label}</label>
      <select className="form-control" id={id} required value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">{t('엔터티 선택')}</option>
        {entities.map((entity) => <option key={entity.id} value={entity.id}>{entity.name}</option>)}
      </select>
    </div>
  );
  const sideSelect = (id: string, label: string, value: string, onChange: (value: string) => void) => (
    <div className="form-group">
      <label htmlFor={id}>{label}</label>
      <select className="form-control" id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        {[...new Set([...SIDE_OPTIONS, value])].map((option) => <option key={option} value={option}>{sideLabel(option)}</option>)}
      </select>
    </div>
  );

  // 방향 바꾸기 : 출발·도착 엔터티와 양쪽 참여 수를 함께 맞바꾼다
  const swap = () => {
    setSourceId(targetId);
    setTargetId(sourceId);
    setSource(target);
    setTarget(source);
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      <div className="form-group">
        <label htmlFor="relationshipName">{t('관계명')}</label>
        <input type="text" className="form-control" id="relationshipName" maxLength={255} autoFocus placeholder={t('예 : 주문한다 (선택)')} value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="logical-form-pair">
        {entitySelect('relationshipSource', t('출발 엔터티'), sourceId, setSourceId)}
        {entitySelect('relationshipTarget', t('도착 엔터티'), targetId, setTargetId)}
      </div>
      <p className="form-help">
        {t('출발 엔터티가 참조되는 쪽(부모), 도착 엔터티가 참조하는 쪽(자식)입니다.')} <button type="button" className="logical-link" onClick={swap}>{t('방향 바꾸기')}</button>
      </p>
      <div className="logical-form-pair">
        {sideSelect('relationshipSourceSide', t('출발 쪽 참여 수 (도착 1건에 연결되는 출발 건수)'), source, setSource)}
        {sideSelect('relationshipTargetSide', t('도착 쪽 참여 수 (출발 1건에 연결되는 도착 건수)'), target, setTarget)}
      </div>
      <p className="form-help">{t('같은 엔터티를 양쪽에 고르면 자기 참조 관계입니다. 논리 ERD 에서는 각 엔터티 옆에 그 쪽의 참여 수를 표시합니다.')}</p>
      <div className="form-check">
        <input type="checkbox" className="form-check-input" id="relationshipIdentifying" checked={identifying} onChange={(e) => setIdentifying(e.target.checked)} />
        <label className="form-check-label" htmlFor="relationshipIdentifying">{t('식별 관계 (출발 엔터티의 식별자가 도착 엔터티 식별자의 일부)')}</label>
      </div>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="relationshipDescription">{t('설명')}</label>
        <textarea className="form-control" id="relationshipDescription" maxLength={1000} rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <button type="submit" className="btn btn-primary">{t('저장')}</button>
    </form>
  );
}
