import { api, ApiError } from '../../lib/api';
import { baseQuery, type Deletion } from '../../lib/deletion';
import type { DialogApi } from '../../components/dialog/DialogProvider';
import { t } from '../../lib/i18n';
import { runWithReview, type Snapshot } from '../../components/conflict/conflictActions';
import { cardinalityText, type LogicalAttribute, type LogicalEntity, type LogicalIdentifier, type LogicalRelationship } from './model';

/** 논리 모델 객체의 충돌 검토용 상태 (M8-C3) : 기준값·승인·비교 항목. load* 는 최신 값을 읽는다 (없으면 대상 없음 오류) */

const notFound = (label: string) => new ApiError(400, t('{0}을(를) 찾을 수 없습니다.', label), 'LOGICAL_MODEL_IS_NOT_DEFINED');

export function entitySnapshot(e: LogicalEntity): Snapshot {
  return {
    modifiedAt: e.modifiedAt ?? null,
    approved: !!e.isApproval,
    // 승인 구성 기준값 (M8-C4c-1) : 서버가 준 값 그대로. 속성·식별자가 바뀌었으면 검토 화면이 '하위 구성 변경'으로 보여 준다
    composition: e.composition ?? undefined,
    fields: [
      { key: 'name', label: t('엔터티명'), value: e.entityName },
      { key: 'description', label: t('설명'), value: e.entityDescription ?? '' },
      { key: 'attributes', label: t('속성'), value: e.attributes.map((a) => a.attributeName).join(', ') },
      { key: 'identifiers', label: t('식별자'), value: e.identifiers.map((i) => i.identifierName).join(', ') },
    ],
  };
}

export const loadEntity = async (entityId: number) => api<LogicalEntity>(`/api/logical/entity/${entityId}`, { silent: true });

export function attributeSnapshot(a: LogicalAttribute): Snapshot {
  return {
    modifiedAt: a.modifiedAt ?? null,
    fields: [
      { key: 'name', label: t('속성명'), value: a.attributeName },
      { key: 'dataType', label: t('데이터 유형'), value: a.dataType ?? '' },
      { key: 'term', label: t('표준 용어'), value: a.termName ?? '' },
      { key: 'description', label: t('설명'), value: a.attributeDescription ?? '' },
    ],
  };
}

export async function loadAttribute(entityId: number, attributeId: number): Promise<LogicalAttribute> {
  const found = (await loadEntity(entityId)).attributes.find((a) => a.id === attributeId);
  if (!found) throw notFound(t('속성'));
  return found;
}

export function identifierSnapshot(i: LogicalIdentifier): Snapshot {
  return {
    modifiedAt: i.modifiedAt ?? null,
    fields: [
      { key: 'name', label: t('식별자명'), value: i.identifierName },
      { key: 'primary', label: t('주 식별자'), value: i.isPrimary ? t('예') : t('아니요') },
      { key: 'attributes', label: t('구성 속성 (순서대로)'), value: (i.attributeNames ?? []).join(', ') },
    ],
  };
}

export async function loadIdentifier(entityId: number, identifierId: number): Promise<LogicalIdentifier> {
  const found = (await loadEntity(entityId)).identifiers.find((i) => i.id === identifierId);
  if (!found) throw notFound(t('식별자'));
  return found;
}

export function relationshipSnapshot(r: LogicalRelationship): Snapshot {
  return {
    modifiedAt: r.modifiedAt ?? null,
    approved: !!r.isApproval,
    fields: [
      { key: 'name', label: t('관계명'), value: r.relationshipName ?? '' },
      { key: 'ends', label: t('출발 → 도착'), value: `${r.sourceEntityName} → ${r.targetEntityName}` },
      { key: 'cardinality', label: t('카디널리티'), value: cardinalityText(r) },
      { key: 'identifying', label: t('식별 관계'), value: r.isIdentifying ? t('식별') : t('비식별') },
      { key: 'pairs', label: t('속성 짝'), value: String(r.attributePairs?.pairs?.length ?? 0) },
    ],
  };
}

/** 관계의 최신 값 : 출발 엔터티의 관계 목록에서 찾는다 (관계 단건 조회 API 가 없다) */
export async function loadRelationship(sourceEntityId: number, relationshipId: number): Promise<LogicalRelationship> {
  const found = (await api<LogicalRelationship[]>(`/api/logical/entity/${sourceEntityId}/relationship`, { silent: true })).find((r) => r.id === relationshipId);
  if (!found) throw notFound(t('관계'));
  return found;
}

/** 삭제 확인 조회 결과 → 상태 (modifiedAt 은 화면이 처음 읽은 값을 쓸 수 있다) */
export function deletionSnapshot(d: Deletion, modifiedAt?: string | null): Snapshot {
  return { modifiedAt: modifiedAt === undefined ? d.modifiedAt : modifiedAt, composition: d.composition, cascade: d.cascade, fields: [{ key: 'name', label: t('이름'), value: d.name ?? '' }] };
}

export const loadDeletion = async (url: string) => deletionSnapshot(await api<Deletion>(url, { silent: true }));

// ------------------------------------------------------------------ 화면 공용 실행 (논리 모델 관리 · 논리 ERD)

/** 관계 삭제 : 화면이 본 관계 기준값 → 409 면 최신 관계 비교 뒤 사용자가 다시 삭제. after = 성공·검토 닫기 뒤 다시 읽기 */
export async function deleteRelationshipGuarded(dialog: DialogApi, r: LogicalRelationship, after: () => unknown): Promise<boolean> {
  const ok = await runWithReview({
    dialog, title: t('관계 삭제'), intro: t('다른 사용자가 이 항목을 변경했습니다.'),
    seen: relationshipSnapshot(r), load: async () => relationshipSnapshot(await loadRelationship(r.sourceEntityId, r.id)),
    act: async (state) => { await api(`/api/logical/relationship/${r.id}${baseQuery(state.modifiedAt)}`, { method: 'DELETE', conflict: 'handled' }); },
    retryLabel: t('최신 내용으로 삭제'), onClose: () => void after(),
  });
  if (ok) await after();
  return ok;
}

/** 관계 승인·승인 취소 (C1 의 "409 면 목록 다시 읽기" → C3 : 최신 관계를 보여 주고 사용자가 다시 승인) */
export async function approveRelationshipGuarded(dialog: DialogApi, r: LogicalRelationship, after: () => unknown): Promise<boolean> {
  const target = !r.isApproval;
  const ok = await runWithReview({
    dialog, title: target ? t('관계 승인') : t('관계 승인 취소'), intro: t('다른 사용자가 이 항목을 변경했습니다.'),
    seen: relationshipSnapshot(r), load: async () => relationshipSnapshot(await loadRelationship(r.sourceEntityId, r.id)),
    act: async (state) => { await api(`/api/logical/relationship/${r.id}/approval/${target}${baseQuery(state.modifiedAt)}`, { method: 'PATCH', conflict: 'handled' }); },
    retryLabel: target ? t('최신 내용으로 승인') : t('최신 내용으로 승인 취소'), targetApproval: target, onClose: () => void after(),
  });
  if (ok) await after();
  return ok;
}

/** 엔터티 삭제 (삭제 확인 조회의 구성 기준값) : 409 면 최신 구성을 다시 읽어 바뀐 하위 항목을 보여 준다 */
export async function deleteEntityGuarded(dialog: DialogApi, entityId: number, deletion: Deletion, seenModifiedAt: string | null | undefined,
                                          done: () => unknown, closed: () => unknown): Promise<boolean> {
  const ok = await runWithReview({
    dialog, title: t('엔터티 삭제'), intro: t('삭제 대상 또는 함께 삭제될 항목이 그 사이 바뀌었습니다.'),
    seen: deletionSnapshot(deletion, seenModifiedAt === undefined ? deletion.modifiedAt : seenModifiedAt), load: () => loadDeletion(`/api/logical/entity/${entityId}/deletion`),
    act: async (state) => { await api(`/api/logical/entity/${entityId}${baseQuery(state.modifiedAt, state.composition)}`, { method: 'DELETE', conflict: 'handled' }); },
    retryLabel: t('최신 구성으로 삭제'), onDone: () => void done(), onClose: () => void closed(),
  });
  if (ok) await done();
  return ok;
}

export async function deleteAttributeGuarded(dialog: DialogApi, entityId: number, attribute: LogicalAttribute, after: () => unknown): Promise<boolean> {
  const ok = await runWithReview({
    dialog, title: t('속성 삭제'), intro: t('다른 사용자가 이 항목을 변경했습니다.'),
    seen: attributeSnapshot(attribute), load: async () => attributeSnapshot(await loadAttribute(entityId, attribute.id)),
    act: async (state) => { await api(`/api/logical/attribute/${attribute.id}${baseQuery(state.modifiedAt)}`, { method: 'DELETE', conflict: 'handled' }); },
    retryLabel: t('최신 내용으로 삭제'), onClose: () => void after(),
  });
  if (ok) await after();
  return ok;
}

export async function deleteIdentifierGuarded(dialog: DialogApi, entityId: number, identifier: LogicalIdentifier, after: () => unknown): Promise<boolean> {
  const ok = await runWithReview({
    dialog, title: t('식별자 삭제'), intro: t('다른 사용자가 이 항목을 변경했습니다.'),
    seen: identifierSnapshot(identifier), load: async () => identifierSnapshot(await loadIdentifier(entityId, identifier.id)),
    act: async (state) => { await api(`/api/logical/identifier/${identifier.id}${baseQuery(state.modifiedAt)}`, { method: 'DELETE', conflict: 'handled' }); },
    retryLabel: t('최신 내용으로 삭제'), onClose: () => void after(),
  });
  if (ok) await after();
  return ok;
}
