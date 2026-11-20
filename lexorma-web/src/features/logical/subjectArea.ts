import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';

/**
 * 논리 모델 주제 영역 (M-6 후속 4차, docs/completed/logical-subject-area.md) : 모델 안의 업무 분류 (최대 3단계).
 * 엔터티는 영역 하나에만 속하고, 속하지 않으면 미분류다. 영역은 엔터티의 정의·승인 상태를 바꾸지 않는다.
 */
export interface SubjectArea {
  id: number;
  modelId: number;
  parentId: number | null;
  areaName: string;
  areaDescription: string | null;
  sortOrder: number;
  /** 최상위 = 1 */
  depth: number;
  /** 최상위부터 자기까지의 이름 (서버가 만든다) */
  path: string[];
  directEntityCount: number;
  /** 하위 영역 포함 */
  totalEntityCount: number;
  childCount: number;
  modifiedAt: string;
}

/** GET /api/logical/model/{id}/subjectArea : 계층 순서(상위 → 하위) */
export interface SubjectAreaTree {
  modelId: number;
  entityCount: number;
  unclassifiedCount: number;
  areas: SubjectArea[];
}

/** 최대 계층 (서버와 같다 — 서버가 다시 검사한다) */
export const MAX_AREA_DEPTH = 3;

/** 목록·ERD 의 조회 범위 : 전체 / 미분류 / 영역(기본 하위 포함) */
export type AreaFilter = { kind: 'all' } | { kind: 'unclassified' } | { kind: 'area'; areaId: number; includeChildren: boolean };
export const ALL_AREAS: AreaFilter = { kind: 'all' };

/** 기존 목록·ERD API 의 선택 조건 (전체면 아무것도 붙이지 않는다 — 예전 요청 그대로) */
export function applyAreaFilter(params: URLSearchParams, filter: AreaFilter) {
  if (filter.kind === 'unclassified') params.set('unclassified', 'true');
  if (filter.kind === 'area') {
    params.set('subjectAreaId', String(filter.areaId));
    if (!filter.includeChildren) params.set('includeChildren', 'false');
  }
  return params;
}

export const subjectAreaQueryKey = (projectId: number | undefined, modelId: number) => ['logical-subject-areas', projectId, modelId];

/** 모델의 영역 목록 (관리 화면·ERD 공용, 조회 키는 모델별) */
export function useSubjectAreas(modelId: number) {
  const { user } = useAuth();
  return useQuery({
    queryKey: subjectAreaQueryKey(user?.projectId, modelId),
    queryFn: ({ signal }) => api<SubjectAreaTree>(`/api/logical/model/${modelId}/subjectArea`, { signal, silent: true }),
  });
}

/** 경로 표시 : 상위 / 하위 / … */
export const areaPath = (area: Pick<SubjectArea, 'path'> | undefined) => (area ? area.path.join(' / ') : '');

/** 영역 ID → 영역 (경로 표시·선택 상태 확인) */
export function areaMap(tree: SubjectAreaTree | undefined) {
  return new Map((tree?.areas ?? []).map((a) => [a.id, a]));
}

/** 자기와 하위 영역 전체의 ID (이동할 때 상위로 고를 수 없는 영역) */
export function subtreeIds(areas: SubjectArea[], id: number) {
  const result = new Set<number>([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const area of areas) {
      if (area.parentId != null && result.has(area.parentId) && !result.has(area.id)) {
        result.add(area.id);
        grew = true;
      }
    }
  }
  return result;
}

/** 영역 아래로 몇 단계가 더 있는가 (하위 없음 = 0) */
export function areaHeight(areas: SubjectArea[], id: number): number {
  const children = areas.filter((a) => a.parentId === id);
  return children.length ? Math.max(...children.map((c) => areaHeight(areas, c.id) + 1)) : 0;
}
