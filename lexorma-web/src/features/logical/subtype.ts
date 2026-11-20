import { t } from '../../lib/i18n';
import type { LogicalRelationship } from './model';

/**
 * 슈퍼타입·서브타입 (M-6 후속 5차, LogicalSubtypeDto). overlapping 참 = Overlapping(중복 가능)·거짓 = Disjoint(배타), total 참 = Total(완전)·거짓 = Partial(부분).
 * 둘 다 기본값이 없다 — 등록 화면도 고르지 않은 상태에서 시작한다. 분류 속성·분류 값은 실행하지 않는 메타데이터다.
 */
export interface SubtypeMember {
  entityId: number;
  entityName: string;
  isApproval: boolean | null;
  classificationValue: string | null;
  sortOrder: number;
}

/** GET /api/logical/subtypeGroup/{id} */
export interface SubtypeGroup {
  id: number;
  modelId: number;
  supertypeEntityId: number;
  supertypeEntityName: string;
  supertypeApproval: boolean | null;
  groupName: string;
  groupDescription: string | null;
  overlapping: boolean;
  total: boolean;
  classificationAttributeId: number | null;
  classificationAttributeName: string | null;
  classificationAttributeEntityId: number | null;
  classificationAttributeEntityName: string | null;
  sortOrder: number;
  subtypes: SubtypeMember[];
  modifiedAt: string;
}

/** GET /api/logical/model/{id}/subtypeGroup/data 의 행 (subtypeCount : 그룹 전체 구성원 수) */
export interface SubtypeGroupRow {
  id: number;
  groupName: string;
  supertypeEntityId: number;
  supertypeEntityName: string;
  subtypeCount: number;
  overlapping: boolean;
  total: boolean;
  classificationAttributeId: number | null;
  classificationAttributeName: string | null;
  modifiedAt: string;
}

/** 유효 속성 : attributeId 는 원본 속성 ID, depth 0 = 직접 정의 · 1 = 직접 슈퍼타입 · 2 = 그 위 */
export interface EffectiveAttribute {
  attributeId: number;
  contextEntityId: number;
  definedEntityId: number;
  definedEntityName: string;
  inherited: boolean;
  depth: number;
  editable: boolean;
  attributeName: string;
  attributeDescription: string | null;
  dataType: string | null;
  isRequired: boolean | null;
  sortOrder: number | null;
  termId: number | null;
  termName: string | null;
  domainName: string | null;
  primary: boolean;
  keyOrder: number | null;
  modifiedAt: string | null;
}

export interface EffectiveIdentifier {
  identifierId: number;
  identifierName: string;
  isPrimary: boolean | null;
  definedEntityId: number;
  definedEntityName: string;
  inherited: boolean;
  attributeIds: number[];
  attributeNames: string[];
}

export interface InheritedRelationship {
  definedEntityId: number;
  definedEntityName: string;
  role: 'SOURCE' | 'TARGET' | 'SELF';
  relationship: LogicalRelationship;
}

/** GET /api/logical/entity/{id}/inheritance */
export interface Inheritance {
  entityId: number;
  entityName: string;
  isApproval: boolean | null;
  modifiedAt: string | null;
  level: number;
  rootEntityId: number;
  rootEntityName: string;
  parent: {
    groupId: number; groupName: string; supertypeEntityId: number; supertypeEntityName: string; overlapping: boolean; total: boolean;
    classificationAttributeId: number | null; classificationAttributeName: string | null; classificationValue: string | null;
  } | null;
  ancestors: { entityId: number; entityName: string; isApproval: boolean | null; level: number }[];
  groups: SubtypeGroup[];
  descendantCount: number;
  rootPrimaryKeyMissing: boolean;
  attributes: EffectiveAttribute[];
  identifiers: EffectiveIdentifier[];
  inheritedRelationships: InheritedRelationship[];
}

/** 상속의 최대 단계 (루트 = 1) — 서버 LogicalHierarchy.MAX_LEVEL 과 같다 */
export const MAX_SUBTYPE_LEVEL = 3;
/** 그룹 하나의 최소 서브타입 수 (서버와 같다). 최대 수는 없다 (D-51) */
export const SUBTYPE_MIN = 2;

/** 기호 : D = 배타(Disjoint), O = 중복 가능(Overlapping) */
export const disjointness = (overlapping: boolean) => (overlapping ? t('중복 가능 (Overlapping)') : t('배타 (Disjoint)'));
export const completeness = (total: boolean) => (total ? t('완전 (Total)') : t('부분 (Partial)'));
export const disjointSymbol = (overlapping: boolean) => (overlapping ? 'O' : 'D');

export const subtypeQueryKey = (projectId: number | undefined, modelId: number | undefined) => ['logical-subtype', projectId, modelId];
export const inheritanceQueryKey = (entityId: number) => ['logical-inheritance', entityId];
