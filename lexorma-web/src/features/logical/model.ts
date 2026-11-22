import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';

/** /api/logical (LogicalModelDto) : 논리 모델은 물리 테이블·컬럼과 독립이고 표준 용어만 연결한다 */
export interface LogicalModel {
  id: number;
  modelName: string;
  modelDescription: string | null;
  /** 수정 기준값 (DEC-08) */
  modifiedAt?: string | null;
}

/** 표준 도메인(domainName·domainDataType)은 연결한 표준 용어의 도메인이다 (따로 저장하지 않는다) */
export interface LogicalAttribute {
  id: number;
  entityId: number;
  attributeName: string;
  attributeDescription: string | null;
  dataType: string | null;
  isRequired: boolean | null;
  sortOrder: number | null;
  termId: number | null;
  termName: string | null;
  domainName: string | null;
  domainDataType: string | null;
  /** 수정 기준값 (DEC-08) */
  modifiedAt?: string | null;
}

/** attributeIds : 식별자를 이루는 속성 (순서대로, 복합 가능) */
export interface LogicalIdentifier {
  /** 수정 기준값 (DEC-08) */
  modifiedAt?: string | null;
  id: number;
  entityId: number;
  identifierName: string;
  isPrimary: boolean | null;
  attributeIds: number[];
  attributeNames: string[];
}

/** GET /api/logical/entity/{id} : 속성·식별자 포함 */
export interface LogicalEntity {
  id: number;
  modelId: number;
  entityName: string;
  entityDescription: string | null;
  isApproval: boolean | null;
  attributes: LogicalAttribute[];
  identifiers: LogicalIdentifier[];
  /** 수정 기준값 (DEC-08) */
  modifiedAt?: string | null;
  /** 소속 주제 영역 (M-6 후속 4차 — null = 미분류) */
  subjectAreaId?: number | null;
}

/**
 * 원본 쪽(sourceMin..sourceMax) = 대상 1건에 연결되는 원본 건수, 대상 쪽 = 원본 1건에 연결되는 대상 건수.
 * 최소 0 = 선택 / 1 이상 = 필수, **최대 null = 다(N)** (미입력이 아니다).
 */
export interface LogicalRelationship {
  /** 수정 기준값 (DEC-08) */
  modifiedAt?: string | null;
  id: number;
  modelId: number;
  relationshipName: string | null;
  relationshipDescription: string | null;
  sourceEntityId: number;
  sourceEntityName: string;
  targetEntityId: number;
  targetEntityName: string;
  isIdentifying: boolean | null;
  sourceMin: number;
  sourceMax: number | null;
  targetMin: number;
  targetMax: number | null;
  cardinality: string;
  isApproval: boolean | null;
  /** 속성 짝과 판정 (목록·ERD 조회에만, M-7 보강 3차) */
  attributePairs?: PairState | null;
  /** 양 끝 엔터티의 소속 주제 영역 (관계 목록 페이지에만, M-6 후속 4차 — null = 미분류) */
  sourceSubjectAreaId?: number | null;
  targetSubjectAreaId?: number | null;
}

/** 관계 속성 짝 (M-7 보강 3차) : 부모(출발) 주 식별자 속성 → 자식(도착) 속성, 순서 */
export interface AttributePair { parentAttributeId: number; parentAttributeName: string; childAttributeId: number; childAttributeName: string; sortOrder: number }
export type PairStatus = 'NONE' | 'PARENT_KEY_MISSING' | 'PARENT_KEY_MISMATCH' | 'PARTIAL' | 'COMPLETE';
export type PairWarning = 'ORDER_DIFFERS' | 'SELF_KEY_REUSED' | 'IDENTIFYING_CHILD_NOT_KEY' | 'REQUIRED_CHILD_NULLABLE';
export interface PairState {
  status: PairStatus; warnings: PairWarning[]; pairs: AttributePair[]; parentKeySize: number;
  /** 짝이 가리키는 부모 식별자 (M7-AK-D09) : PRIMARY = 주 식별자, ALTERNATE = 부모 자체의 보조 식별자 전체. 해석되지 않으면 null */
  keyType?: 'PRIMARY' | 'ALTERNATE' | null; keyIdentifierId?: number | null; keyIdentifierName?: string | null;
}
/** 짝으로 참조할 수 있는 부모 식별자 (주 식별자 먼저, 보조 식별자는 식별자 ID 순 — 상속 구조에 참여하는 부모는 주 식별자만) */
export interface ParentKey { identifierId: number | null; identifierName: string | null; keyType: 'PRIMARY' | 'ALTERNATE'; attributeIds: number[]; attributeNames: string[] }
export interface PairCandidate { attributeId: number; attributeName: string; primary: boolean; keyOrder: number | null; isRequired: boolean | null }
/** GET·PUT /api/logical/relationship/{id}/attributes */
export interface RelationshipPairs {
  relationshipId: number; modifiedAt: string; parentEntityId: number; parentEntityName: string; childEntityId: number; childEntityName: string;
  isIdentifying: boolean | null; state: PairState; parentCandidates: PairCandidate[]; childCandidates: PairCandidate[];
  parentKeys?: ParentKey[];
}

export interface GraphAttribute {
  id: number;
  name: string;
  dataType: string | null;
  isRequired: boolean | null;
  sortOrder: number | null;
  termId: number | null;
  termName: string | null;
  domainName: string | null;
  /** 어느 식별자에든 속함 */
  identifier: boolean;
  /** 주 식별자에 속함 */
  primaryIdentifier: boolean;
}

export interface GraphEntity {
  id: number;
  name: string;
  description: string | null;
  isApproval: boolean | null;
  attributes: GraphAttribute[];
  /** 서브타입이면 직접 슈퍼타입 (M-6 후속 5차, 부분 조회·이웃 탐색에만) */
  supertypeEntityId?: number | null;
  /** 서브타입의 상속 주 식별자 (루트의 주 식별자, 원본 속성 ID — 루트에 없으면 빈 목록) */
  inheritedKey?: InheritedKey[];
}

export interface InheritedKey { attributeId: number; name: string; dataType: string | null; keyOrder: number; definedEntityId: number; definedEntityName: string | null }

/** 논리 ERD 의 서브타입 그룹 (M-6 후속 5차) : 슈퍼타입이 범위 안이고 서브타입이 하나라도 범위 안인 그룹. subtypes 는 범위 안의 것만, subtypeCount 는 전체 수 */
export interface GraphSubtypeGroup {
  id: number;
  groupName: string;
  supertypeEntityId: number;
  overlapping: boolean;
  total: boolean;
  classificationAttributeName: string | null;
  subtypeCount: number;
  subtypes: { entityId: number; classificationValue: string | null }[];
}

/** GET /api/logical/model/{id}/erd */
export interface LogicalGraph {
  modelId: number;
  modelName: string;
  entities: GraphEntity[];
  relationships: LogicalRelationship[];
}

/**
 * 논리 ERD 부분 조회 (M-6 후속 1차) : 범위 조회·이웃 탐색. relationships 는 양 끝이 모두 entities 에 있는 관계만.
 * totalCount : 범위 조회는 조건에 맞는 전체 엔터티 수, 이웃 탐색은 담은 수. outsideRelationships : 엔터티 ID → 상대가 범위 밖이라 빠진 관계 수
 */
export interface LogicalGraphPart extends LogicalGraph {
  totalCount: number;
  truncated: boolean;
  modelEntityCount: number;
  outsideRelationships: Record<string, number>;
  /** 범위 안 서브타입 그룹·범위 밖 직접 상속 연결 수 (M-6 후속 5차 — 일반 관계 수와 따로) */
  subtypeGroups?: GraphSubtypeGroup[];
  outsideInheritances?: Record<string, number>;
}

/** 논리 ERD 엔터티 찾기 (ERD 범위와 무관, 속성 없이) */
export interface EntityHits {
  items: { id: number; name: string; isApproval: boolean | null }[];
  totalCount: number;
}

/** 한쪽 참여 수 표기 : 1, 0..1, 0..N, 1..N (최대 null = N) */
export function participation(min: number, max: number | null) {
  if (max == null) return `${min}..N`;
  return min === max ? String(max) : `${min}..${max}`;
}

/** 관계 양쪽 표기 : "원본 쪽 : 대상 쪽" (예 1 : 0..N) */
export const cardinalityText = (r: LogicalRelationship) => `${participation(r.sourceMin, r.sourceMax)} : ${participation(r.targetMin, r.targetMax)}`;

function readStored(key: string) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

/**
 * 현재 프로젝트의 논리 모델 목록과 선택한 모델 (논리 모델 관리·논리 ERD 공용).
 * 선택 = URL 의 ?modelId= → 이 브라우저에서 마지막으로 고른 모델(프로젝트별) → 첫 모델. 목록에 없는 ID 는 무시한다.
 */
export function useLogicalModels() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const queryKey = ['logical-models', user?.projectId];
  const query = useQuery({ queryKey, queryFn: () => api<LogicalModel[]>('/api/logical/model') });
  const models = query.data ?? [];
  // 프로젝트마다 따로 기억한다 (다른 프로젝트의 선택을 쓰지 않는다)
  const storageKey = `logicalModel:${user?.projectId}`;
  const find = (id: string | null) => (id ? models.find((m) => String(m.id) === id) : undefined);
  const selected = find(params.get('modelId')) ?? find(readStored(storageKey)) ?? models[0];

  const select = (modelId: number) => {
    try {
      localStorage.setItem(storageKey, String(modelId));
    } catch {
      // 저장소를 쓸 수 없으면 URL 로만 유지
    }
    setParams({ modelId: String(modelId) }, { replace: true });
  };

  return { ...query, models, selected, select, refresh: () => queryClient.invalidateQueries({ queryKey }) };
}

export const graphQueryKey = (projectId: number | undefined, modelId: number | undefined) => ['logical-erd', projectId, modelId];

/**
 * 논리 모델 관리 화면의 조회 키 (M-6 후속 3차) : 요약·엔터티 페이지·관계 페이지가 이 아래에 있다.
 * 변경 뒤에는 이 키를 무효화해 지금 모델의 요약·목록만 다시 받는다 (전체 그래프를 다시 받지 않는다)
 */
export const manageQueryKey = (projectId: number | undefined, modelId: number | undefined) => ['logical-manage', projectId, modelId];

/** GET /api/logical/model/{id}/summary : 모델 전체 수 (검색과 무관) */
export interface LogicalModelSummary {
  modelId: number;
  entityCount: number;
  relationshipCount: number;
}

/** GET /api/logical/model/{id}/entity/data 의 행 : 속성 수·관계 수(출발·도착 어느 쪽이든, 자기 참조 1번)는 서버가 센다 */
export interface LogicalEntityRow {
  id: number;
  entityName: string;
  entityDescription: string | null;
  isApproval: boolean | null;
  attributeCount: number;
  relationshipCount: number;
  modifiedAt?: string | null;
  /** 소속 주제 영역 (M-6 후속 4차 — null = 미분류) */
  subjectAreaId?: number | null;
}

/** 공용 그리드 페이지 응답 { data, recordsTotal } */
export interface GridPage<T> {
  data: T[];
  recordsTotal: number;
}
