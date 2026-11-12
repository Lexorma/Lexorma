import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';

/** GET /api/schema (SchemaDto) : 기본 스키마 먼저. tableCount·등록 정보는 목록 조회에만 있다 */
export interface Schema {
  id: number;
  schemaName: string;
  schemaDescription: string | null;
  isDefault: boolean;
  tableCount: number | null;
  created: string | null;
  updated: string | null;
  createdBy: string | null;
  updatedBy: string | null;
}

/**
 * 현재 프로젝트의 스키마 목록. 스키마 관리·테이블 관리·물리 ERD 가 같은 캐시 키를 쓴다.
 * 스키마나 테이블을 바꾼 뒤에는 refresh 로 목록(테이블 수 포함)을 다시 받는다.
 */
export function useSchemas() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const queryKey = ['schemas', user?.projectId];
  const query = useQuery({ queryKey, queryFn: () => api<Schema[]>('/api/schema') });
  return { ...query, schemas: query.data ?? [], refresh: () => queryClient.invalidateQueries({ queryKey }) };
}
