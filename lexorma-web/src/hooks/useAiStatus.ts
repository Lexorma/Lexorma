import { useQuery } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useAuth } from './useAuth';

/** 시스템 기본 AI Provider 상태 (AI-3) : 서버 설정(DB)만으로 정한다 — AI 서버에 연결하지 않으므로 서버가 잠시 멈춰도 바뀌지 않는다 */
export type AiDefaultStatus = 'READY' | 'NOT_CONFIGURED' | 'DISABLED' | 'MODEL_MISSING' | 'UNSUPPORTED';

/** 사이드바(AI 관리)·AI 도우미·AI 화면이 함께 쓰는 상태 조회 키 (Provider 설정을 바꾸면 이 키를 무효화한다) */
export const AI_STATUS_KEY = ['ai-metadata-query', 'status'] as const;

/**
 * 기본 AI Provider 상태.
 * - configured : 기본 Provider 가 정해져 있다 (READY·DISABLED·MODEL_MISSING·UNSUPPORTED). 기존 제안·평가 이력을 볼 수 있게 AI 메뉴를 보인다
 * - ready : 지금 새 AI 요청을 보낼 수 있다 (READY). AI 도우미와 생성 버튼의 기준
 * 화면 표시용일 뿐이고 권한·생성 가능 여부는 서버가 다시 확인한다.
 */
export function useAiStatus() {
  const { user, canView } = useAuth();
  const enabled = !!user && canView('ai-metadata-query');
  const query = useQuery({
    queryKey: AI_STATUS_KEY,
    queryFn: () => api<{ status: AiDefaultStatus }>('/api/ai/metadata/status', { silent: true }),
    staleTime: 60_000,
    refetchOnWindowFocus: true,
    enabled,
  });
  const status = enabled ? query.data?.status : undefined;
  return { status, configured: !!status && status !== 'NOT_CONFIGURED', ready: status === 'READY', isError: query.isError, refetch: query.refetch };
}
