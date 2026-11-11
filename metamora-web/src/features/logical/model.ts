import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';

/** /api/logical (LogicalModelDto) : 논리 모델은 물리 테이블·컬럼과 독립이고 표준 용어만 연결한다 */
export interface LogicalModel {
  id: number;
  modelName: string;
  modelDescription: string | null;
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
}

/** attributeIds : 식별자를 이루는 속성 (순서대로, 복합 가능) */
export interface LogicalIdentifier {
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
}

/**
 * 원본 쪽(sourceMin..sourceMax) = 대상 1건에 연결되는 원본 건수, 대상 쪽 = 원본 1건에 연결되는 대상 건수.
 * 최소 0 = 선택 / 1 이상 = 필수, **최대 null = 다(N)** (미입력이 아니다).
 */
export interface LogicalRelationship {
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
}

/** GET /api/logical/model/{id}/erd */
export interface LogicalGraph {
  modelId: number;
  modelName: string;
  entities: GraphEntity[];
  relationships: LogicalRelationship[];
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

/** 모델의 엔터티(속성·식별자 표시)와 관계. 관리 화면의 목록·건수와 논리 ERD 가 같은 조회를 쓴다 */
export function useLogicalGraph(modelId: number | undefined, enabled = true) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const queryKey = graphQueryKey(user?.projectId, modelId);
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => api<LogicalGraph>(`/api/logical/model/${modelId}/erd`, { signal, silent: true }),
    enabled: modelId != null && enabled,
  });
  return { ...query, refresh: () => queryClient.invalidateQueries({ queryKey }) };
}
