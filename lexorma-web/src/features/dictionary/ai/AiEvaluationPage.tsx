import { useAuth } from '../../../hooks/useAuth';
import { PageHeader } from '../../../components/layout/PageHeader';
import { t } from '../../../lib/i18n';
import { AiStatusNotice } from './AiStatusNotice';
import { EvaluationPanel } from './EvaluationPanel';
import '../../../styles/ai-dictionary.css';

/**
 * AI 관리 › AI 품질 평가 (AI-5 후속) : 기준 데이터(평가 대상·사람이 확인한 실제 의미)와 사람의 판정, 모델별 지표. 화면은 기존 품질 평가 컴포넌트 그대로다.
 * 예전 주소 /aiDictionary?tab=evaluation 은 여기로 옮긴다.
 */
export default function AiEvaluationPage() {
  const { user } = useAuth();
  return (
    <div className="ai-dict-page">
      <PageHeader category={t('AI 관리')} title={t('AI 품질 평가')}
                  description={t('업무 담당자·검토자가 확인한 실제 의미를 기준으로 AI 설명을 판정하고, 모델·프롬프트별 지표를 봅니다. AI 가 판정하지 않습니다.')} />
      <AiStatusNotice />
      {/* 프로젝트가 바뀌면 고른 테이블·대상이 섞이지 않게 새로 만든다 */}
      <EvaluationPanel key={user?.projectId ?? 0} />
    </div>
  );
}
