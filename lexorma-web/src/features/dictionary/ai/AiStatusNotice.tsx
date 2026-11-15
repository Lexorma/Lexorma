import { useAiStatus } from '../../../hooks/useAiStatus';
import { t } from '../../../lib/i18n';

/**
 * 기본 AI Provider 를 지금 쓸 수 없을 때의 안내 (AI 설명 제안·품질 평가 화면 위). 기존 제안·이력·평가는 그대로 볼 수 있고,
 * 새 초안 생성은 서버가 상태별 오류로 거절한다. AI 서버의 일시 장애는 여기서 알 수 없다 (상태는 설정만 본다 — 생성할 때 오류로 알린다).
 */
export function AiStatusNotice() {
  const { status } = useAiStatus();
  if (!status || status === 'READY') return null;
  const reason = status === 'NOT_CONFIGURED' ? t('시스템 기본 AI Provider 가 지정되지 않았습니다.')
    : status === 'DISABLED' ? t('시스템 기본 AI Provider 가 비활성화되어 있습니다.')
      : status === 'MODEL_MISSING' ? t('시스템 기본 AI Provider 에 기본 모델이 없습니다.')
        : t('시스템 기본 AI Provider 의 유형을 지원하지 않습니다.');
  return <div className="ai-dict-callout is-warn" role="status">{reason} {t('새 AI 초안은 만들 수 없고, 기존 제안·검토 이력·평가는 볼 수 있습니다.')}</div>;
}
