import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { useAuth } from '../../../hooks/useAuth';
import { t } from '../../../lib/i18n';
import { aiDictionary, proposalPath, STATUS_CLASS, STATUS_LABEL, type ObjectStatus } from './aiDictionary';
import '../../../styles/ai-dictionary.css';

/** 테이블의 업무 설명·AI 제안 상태 (테이블·컬럼 관리 화면, ERD 상세 패널). 한 테이블에 한 번 조회해 테이블·컬럼이 같이 쓴다 */
export function useObjectStatuses(tableId: number | null | undefined) {
  const { canView } = useAuth();
  const enabled = tableId != null && canView('ai-dictionary');
  return useQuery({ queryKey: ['ai-dict-objects', tableId], queryFn: () => aiDictionary.objects(tableId!), enabled, staleTime: 10_000 });
}

/**
 * 확정 업무 설명과 AI 제안 상태, 그 제안(없으면 그 대상)으로 이동하는 링크. 업무 설명은 AI 제안을 승인하거나 사람이 직접 쓴 값이다
 */
export function BizDescriptionInfo({ status, tableId, columnId, compact = false }: { status: ObjectStatus | undefined; tableId: number; columnId?: number | null; compact?: boolean }) {
  const { canView } = useAuth();
  if (!canView('ai-dictionary')) return null;
  const link = proposalPath({ proposalId: status?.proposalId, tableId, columnId: columnId ?? null });
  return (
    <div className={`ai-dict-info${compact ? ' is-compact' : ''}`}>
      <div className="ai-dict-info-row">
        <span className="ai-dict-info-label">{t('업무 설명')}</span>
        {status?.bizDescription ? <span className="ai-dict-info-text">{status.bizDescription}</span> : <span className="gh-muted">{t('없음')}</span>}
      </div>
      <div className="ai-dict-info-row">
        <span className="ai-dict-info-label"><Sparkles size={12} aria-hidden="true" /> {t('AI 제안')}</span>
        {status?.proposalStatus ? <span className={`gh-label ${STATUS_CLASS[status.proposalStatus]}`}>{STATUS_LABEL[status.proposalStatus]}</span> : <span className="gh-muted">{t('없음')}</span>}
        {status?.recheckNeeded && <span className="gh-label gh-label-attention">{t('근거 변경')}</span>}
        <Link className="ai-dict-info-link" to={link}>{status?.proposalId ? t('제안 보기') : t('AI 설명 제안으로')}</Link>
      </div>
    </div>
  );
}

/** 테이블 또는 컬럼 하나의 업무 설명·제안 상태 (자기 테이블의 상태를 조회해 고른다) */
export function ObjectBizInfo({ tableId, columnId, compact }: { tableId: number | null | undefined; columnId?: number | null; compact?: boolean }) {
  const statuses = useObjectStatuses(tableId);
  if (tableId == null) return null;
  const status = statuses.data?.find((s) => s.tableId === tableId && (s.columnId ?? null) === (columnId ?? null));
  return <BizDescriptionInfo status={status} tableId={tableId} columnId={columnId} compact={compact} />;
}
