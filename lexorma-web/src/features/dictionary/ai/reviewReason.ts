/**
 * AI 설명 제안의 재검토 사유 해석 (AI-5-REASON). 저장된 사유 문자열은 그대로 두고, 화면에 보일 때만 이 규칙으로 읽는다 (React·i18n 에 의존하지 않는 순수 함수).
 * <p>
 * 서버가 남기는 형식 (`AiDescriptionReviewService.reason`, 형식 계약은 `ProposalReasonFormatTest`) :
 * 변경마다 `종류 유형:ID 이름 [필드, …]` (유형·이름·필드는 없을 수 있다) 을 `; ` 로 잇고 1000자를 넘으면 뒤 변경을 뺀다.
 * 그 밖에 서버가 쓰는 고정 문구 : 생성 중 변경("생성 중 대상이 바뀌었습니다"), 근거 재검증("근거 재검증 : …"), 대체("새 제안 #N 으로 대체", "제안 #… 을 대체").
 * 반려·보류·종결·검토함·승인의 사유는 사용자가 쓴 글이라 그대로 보인다 — 어떤 글이 시스템 사유인지는 이력의 동작(action)으로 정한다 (글 모양으로 추측하지 않는다).
 * 알려진 형식만 해석하고, 모르는 코드는 내용을 꾸며내지 않고 "알 수 없는 사유" 로 둔다. 내부 ID 는 결과에 담지 않는다.
 */

export const CHANGE_KINDS = [
  'BIZ_DESCRIPTION_CHANGED', 'TERM_LINK_CHANGED', 'MODIFIED', 'MODIFIED_UNKNOWN', 'TARGET_DELETED', 'OBJECT_DELETED', 'SOURCE_CHANGED', 'SOURCE_TABLE_MISSING',
] as const;
export type ChangeKind = (typeof CHANGE_KINDS)[number];

export type ReasonPart =
  /** 근거·대상의 변경 하나. name 은 기록할 때의 이름 (지금 이름과 다를 수 있다) */
  | { type: 'change'; kind: ChangeKind; objectType: string | null; name: string | null; fields: string[] }
  /** 서버 사유인데 이 화면이 모르는 형식 */
  | { type: 'unknown' }
  /** AI 가 초안을 만드는 동안 대상(업무 설명 포함)이 바뀌었다 */
  | { type: 'generatedChanged' }
  /** 근거 재검증 : 남은 변경 없음 */
  | { type: 'revalidatedClean' }
  /** 근거 재검증 : 검토자가 변경을 확인하고 계속 검토 (그때의 변경 + 검토자가 쓴 이유) */
  | { type: 'revalidatedAck'; changes: ReasonPart[]; note: string | null }
  /** 새 제안으로 대체됨 */
  | { type: 'superseded' }
  /** 앞 제안(들)을 대체함 */
  | { type: 'replaced'; count: number }
  /** 사용자가 쓴 글 */
  | { type: 'text'; text: string };

/** 사용자가 사유를 직접 쓰는 이력 동작 — 이 사유는 글 그대로 보인다. 그 밖의 동작(모르는 새 동작 포함)의 사유는 서버가 쓴 것으로 본다 */
const USER_ACTIONS = new Set(['REVIEW', 'APPROVE', 'APPROVE_EDITED', 'REJECT', 'HOLD', 'CLOSE']);
const GENERATED_CHANGED = '생성 중 대상이 바뀌었습니다';
const REVALIDATE_CLEAN = '근거 재검증 : 생성 때와 달라진 근거가 없습니다';
const REVALIDATE_ACK = '근거 재검증 : 변경 확인 후 계속 검토 (';
const SEGMENT = new RegExp(`^(${CHANGE_KINDS.join('|')})(?: ([A-Z]+):(\\S+))?(?: +(.*?))?(?: \\[([^\\]]*)\\])?$`);

/**
 * 사유 하나를 해석한다.
 *
 * @param action 이력의 동작. 제안의 재검토 사유(상태 REVIEW_REQUIRED)는 'REVIEW_REQUIRED' 로 넘긴다. 모르는 동작·없음은 사용자 글로 본다
 */
export function parseReviewReason(raw: string | null | undefined, action?: string | null): ReasonPart[] {
  const text = typeof raw === 'string' ? raw.trim() : '';
  if (!text) return [];
  switch (action) {
    case 'REVIEW_REQUIRED':
    case 'RECHECK_FLAGGED':
      return parseChanges(text);
    case 'GENERATE':
      return text.split(' · ').map((s) => s.trim()).filter(Boolean).map((s): ReasonPart => {
        if (s === GENERATED_CHANGED) return { type: 'generatedChanged' };
        const replaced = /^제안 (#\d+(?:, #\d+)*) 을 대체$/.exec(s);
        return replaced ? { type: 'replaced', count: replaced[1].split(',').length } : { type: 'unknown' };
      });
    case 'SUPERSEDE':
      return [/^새 제안 #\d+ 으로 대체$/.test(text) ? { type: 'superseded' } : { type: 'unknown' }];
    case 'REVALIDATE': {
      if (text === REVALIDATE_CLEAN) return [{ type: 'revalidatedClean' }];
      if (text.startsWith(REVALIDATE_ACK)) {
        const rest = text.slice(REVALIDATE_ACK.length);
        const end = rest.indexOf(') — ');
        // 1000자에서 잘려 닫는 괄호가 없으면 변경 목록만 (검토자 이유는 잘렸다)
        const changes = end < 0 ? rest : rest.slice(0, end);
        const note = end < 0 ? null : rest.slice(end + 4).trim() || null;
        return [{ type: 'revalidatedAck', changes: parseChanges(changes), note }];
      }
      return [{ type: 'unknown' }];
    }
    default:
      // 동작을 모르면(없음) 사용자 글로 본다. 아는 사용자 동작도 글 그대로. 모르는 새 동작은 서버 사유로 보고 해석하지 못하면 기본 안내
      return !action || USER_ACTIONS.has(action) ? [{ type: 'text', text }] : parseChanges(text);
  }
}

/** 서버의 변경 목록 (`; ` 로 이은 세그먼트). 생성 중 변경 문구도 받는다 */
function parseChanges(text: string): ReasonPart[] {
  const parts: ReasonPart[] = [];
  for (const segment of text.split('; ')) {
    const s = segment.trim();
    if (!s) continue;
    if (s === GENERATED_CHANGED) {
      parts.push({ type: 'generatedChanged' });
      continue;
    }
    const m = SEGMENT.exec(s);
    if (!m) {
      parts.push({ type: 'unknown' });
      continue;
    }
    const fields = m[5] ? m[5].split(',').map((f) => f.trim()).filter(Boolean) : [];
    parts.push({ type: 'change', kind: m[1] as ChangeKind, objectType: m[2] ?? null, name: m[4]?.trim() || null, fields });
  }
  // 같은 해석이 여러 번이면 한 번만 (모르는 사유가 여러 개여도 안내는 하나)
  return parts.filter((p, i) => p.type !== 'unknown' || parts.findIndex((q) => q.type === 'unknown') === i);
}

/** 사유의 짧은 이름들 (목록용) : 변경 종류·고정 문구의 종류만, 순서대로 겹치지 않게 */
export function reasonKinds(parts: ReasonPart[]): string[] {
  const kinds = parts.flatMap((p) => (p.type === 'change' ? [p.kind] : p.type === 'revalidatedAck' ? reasonKinds(p.changes) : p.type === 'text' ? [] : [p.type]));
  return [...new Set(kinds)];
}
