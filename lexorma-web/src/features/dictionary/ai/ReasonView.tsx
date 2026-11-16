import { t } from '../../../lib/i18n';
import { fieldLabel } from '../../history/model';
import { CHANGE_LABEL, OBJECT_TYPE_LABEL } from './aiDictionary';
import { parseReviewReason, reasonKinds, type ReasonPart } from './reviewReason';

/**
 * 재검토 사유 표시 (AI-5-REASON) : 저장된 사유를 reviewReason.ts 로 읽어 사용자 문구로 보인다. 저장 값·API 는 바꾸지 않는다.
 * 대상은 기록할 때의 이름만 보이고(지금 이름과 다를 수 있다) 내부 ID 는 보이지 않는다. 기록에 없는 이전 값·원인은 만들어 보이지 않는다.
 */

/** 사유 종류 → 제목 (상세) */
const TITLE: Record<string, string> = {
  BIZ_DESCRIPTION_CHANGED: t('업무 설명이 변경되어 재검토가 필요합니다.'),
  TERM_LINK_CHANGED: t('표준 용어 연결이 변경되어 재검토가 필요합니다.'),
  MODIFIED: t('근거로 쓴 메타데이터가 변경되어 재검토가 필요합니다.'),
  MODIFIED_UNKNOWN: t('근거가 변경되었지만 바뀐 내용을 알 수 없습니다.'),
  TARGET_DELETED: t('제안 대상이 삭제되었습니다.'),
  OBJECT_DELETED: t('근거로 쓴 객체가 삭제되었습니다.'),
  SOURCE_CHANGED: t('원본 DB 의 값이 변경되어 재검토가 필요합니다.'),
  SOURCE_TABLE_MISSING: t('매핑된 원본 테이블을 찾을 수 없습니다.'),
  generatedChanged: t('AI 가 초안을 만드는 동안 대상이 변경되었습니다.'),
  revalidatedClean: t('근거를 다시 확인했습니다 — 생성 때와 달라진 근거가 없습니다.'),
  revalidatedAck: t('검토자가 근거 변경을 확인하고 계속 검토하기로 했습니다.'),
  superseded: t('새 제안으로 대체되었습니다.'),
  replaced: t('앞 제안을 대체했습니다.'),
  unknown: t('제안의 근거를 다시 확인해야 합니다.'),
};

/** 사유 종류 → 확인할 것 (상세) */
const DETAIL: Record<string, string> = {
  BIZ_DESCRIPTION_CHANGED: t('AI 제안 생성 이후 업무 설명이 수정되었습니다. 현재 업무 설명을 확인하고 AI 제안을 다시 검토해 주세요. 제안이 틀렸다는 뜻은 아닙니다 — 제안을 만들 때의 기준이 바뀌었습니다.'),
  TERM_LINK_CHANGED: t('AI 제안 생성 이후 컬럼에 연결된 표준 용어가 변경되었습니다. 현재 표준 용어와 제안 근거를 확인해 주세요.'),
  MODIFIED: t('AI 제안 생성 이후 제안의 근거로 쓴 테이블·컬럼 등의 정보가 수정되었습니다. 바뀐 항목이 제안 문구에 영향을 주는지 확인해 주세요.'),
  MODIFIED_UNKNOWN: t('근거의 수정 시각은 바뀌었지만 변경 기록이 없어 어떤 값이 바뀌었는지 확인할 수 없습니다. 현재 정보를 직접 확인해 주세요.'),
  TARGET_DELETED: t('이 제안의 대상 테이블·컬럼을 찾을 수 없습니다. 반영할 곳이 없으니 제안을 종결해 주세요.'),
  OBJECT_DELETED: t('제안의 근거로 쓴 객체를 더 이상 찾을 수 없습니다. 새 초안을 만들거나 제안을 종결해 주세요.'),
  SOURCE_CHANGED: t('원본 DB 최신화로 매핑된 원본 테이블의 값(COMMENT·타입 등)이 바뀌었습니다. 바뀐 값이 제안 문구에 영향을 주는지 확인해 주세요.'),
  SOURCE_TABLE_MISSING: t('마지막 원본 DB 최신화 결과에서 매핑된 원본 테이블이나 원본 연결을 찾을 수 없습니다. 원본 매핑을 확인하거나 새 초안을 만들어 주세요.'),
  generatedChanged: t('AI 가 메타데이터를 읽은 뒤 대상(업무 설명 포함)이 수정되어 이 제안은 처음부터 재검토가 필요합니다. 현재 정보로 새 초안을 만들어 주세요.'),
  revalidatedClean: '',
  revalidatedAck: t('아래 변경을 확인했고, 제안의 기준을 지금 상태로 옮겼습니다.'),
  superseded: '',
  replaced: '',
  unknown: t('제안 생성 이후 검토 조건이 변경되었거나 현재 정보를 확인할 수 없습니다. 제안 상세와 근거를 확인해 주세요.'),
};

/** 변경된 항목 이름 : 모델 이력의 필드 이름(연관은 …Id·…Name 키)과 원본 비교 항목(컬럼:comment 등) */
export function reasonFieldLabel(key: string): string {
  if (key === 'bizDescription') return t('업무 설명');
  if (key === 'tableComment') return t('원본 테이블 COMMENT');
  if (key === 'DELETE') return t('삭제');
  const source = /^(.+):(comment|definition|missing)$/.exec(key);
  if (source) {
    const what = source[2] === 'comment' ? t('원본 COMMENT') : source[2] === 'definition' ? t('원본 정의') : t('원본에서 없어짐');
    return `${source[1]} ${what}`;
  }
  const association = /^(.+)(Id|Name)$/.exec(key);
  if (association && fieldLabel(association[1]) !== association[1]) return fieldLabel(association[1]);
  return fieldLabel(key);
}

/** 변경된 항목 이름들 (연관의 ID·이름 키는 하나로) */
export function reasonFieldLabels(keys: string[]): string[] {
  return [...new Set(keys.map(reasonFieldLabel))];
}

const kindOf = (p: ReasonPart) => (p.type === 'change' ? p.kind : p.type);

/** 짧은 사유 (목록) : 종류 이름만 */
export function ReviewReasonSummary({ raw, action }: { raw: string | null | undefined; action: string }) {
  const kinds = reasonKinds(parseReviewReason(raw, action));
  if (kinds.length === 0) return <span>{t('기록 없음')}</span>;
  return <span>{kinds.map((k) => CHANGE_LABEL[k] ?? CHANGE_LABEL.unknown).join(' · ')}</span>;
}

/** 사유 상세 : 사유마다 제목·확인할 것·대상(기록 당시 이름)·바뀐 항목. 사용자가 쓴 글은 그대로 */
export function ReviewReasonDetail({ raw, action, compact = false }: { raw: string | null | undefined; action: string; compact?: boolean }) {
  const parts = parseReviewReason(raw, action);
  if (parts.length === 0) return <span className="gh-muted">{t('기록 없음')}</span>;
  return (
    <ul className="ai-dict-reasons">
      {parts.map((part, i) => <Reason key={i} part={part} compact={compact} />)}
    </ul>
  );
}

function Reason({ part, compact }: { part: ReasonPart; compact: boolean }) {
  if (part.type === 'text') return <li className="ai-dict-reason-text">{part.text}</li>;
  const kind = kindOf(part);
  const detail = DETAIL[kind];
  return (
    <li>
      <strong>{TITLE[kind] ?? TITLE.unknown}</strong>
      {!compact && detail && <div className="ai-dict-small gh-muted">{detail}</div>}
      {part.type === 'change' && (part.objectType || part.name || part.fields.length > 0) && (
        <div className="ai-dict-small">
          {(part.objectType || part.name) && (
            <span>{t('대상')} : {part.objectType ? OBJECT_TYPE_LABEL[part.objectType] ?? t('객체') : t('객체')}{part.name ? ` ${part.name}` : ''}{part.name && <span className="gh-muted"> ({t('기록 당시 이름')})</span>}</span>
          )}
          {part.fields.length > 0 && <span>{(part.objectType || part.name) ? ' · ' : ''}{t('바뀐 항목')} : {reasonFieldLabels(part.fields).join(', ')}</span>}
        </div>
      )}
      {part.type === 'replaced' && part.count > 1 && <div className="ai-dict-small gh-muted">{t('대체한 제안 {0}개', part.count)}</div>}
      {part.type === 'revalidatedAck' && (
        <>
          {part.changes.length > 0 && <ul className="ai-dict-reasons">{part.changes.map((c, i) => <Reason key={i} part={c} compact />)}</ul>}
          {part.note && <div className="ai-dict-small ai-dict-reason-text">{t('검토자 사유')} : {part.note}</div>}
        </>
      )}
    </li>
  );
}

/** 검토 이력의 동작 이름 */
export const ACTION_LABEL: Record<string, string> = {
  GENERATE: t('생성'), REVIEW: t('검토함'), APPROVE: t('승인'), APPROVE_EDITED: t('수정 후 승인'), REJECT: t('반려'), HOLD: t('보류'),
  REVIEW_REQUIRED: t('재검토 필요'), RECHECK_FLAGGED: t('승인 뒤 근거 변경 표시'), SUPERSEDE: t('대체됨'), REVALIDATE: t('근거 재검증'), CLOSE: t('제안 종결'),
};
