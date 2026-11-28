import { useEffect, useRef, useState, type CSSProperties, type FormEvent, type KeyboardEvent } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, Check, Eraser, Info, Send, Sparkles, X } from 'lucide-react';
import { useAiStatus } from '../../hooks/useAiStatus';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError } from '../../lib/api';
import { t, tc } from '../../lib/i18n';
import { NoticeMarkdown } from '../../features/notice/NoticeMarkdown';
import '../../styles/notice.css';
import '../../styles/ai-assistant.css';

/**
 * AI 도우미 (AI-4) : 오른쪽 아래 버튼으로 여는 메타데이터 질의 창. 로그인한 프로젝트의 등록된 메타데이터만 읽기 전용 Tool 로 조회한다.
 * - 보이는 조건 : 메뉴 권한 ai-metadata-query (VIEWER, 서버 MenuAuthorization). 질의 API 도 VIEWER 이고 서버가 매번 다시 확인한다
 * - 질문마다 따로 묻는다 : 앞 대화를 서버로 보내지 않고 저장하지도 않는다. 창 안의 목록은 이 브라우저 화면에만 있다 (새로고침하면 사라진다)
 * - "실행된 Tool" 은 서버에서 실제로 실행된 기록이다. 답변의 Markdown 은 HTML 을 실행하지 않는다 (공지와 같은 렌더러)
 * - 버튼과 창은 푸터를 가리지 않는다 : 푸터가 화면에 보이는 만큼 위로 올린다 ({@link useDock})
 * - 버튼은 화면 요소도 가리지 않는다 : 푸터가 보이면 본문과 푸터 사이에 비워 둔 자리(.has-ai-assistant)에 둥근 버튼으로,
 *   푸터가 보이지 않는 동안(긴 화면을 스크롤하는 중)에는 본문 오른쪽 여백 안의 얇은 탭으로 둔다
 * - 시스템 기본 AI Provider 를 쓸 수 있을 때(READY)만 버튼이 보인다. Provider 가 없거나 미지정·비활성·기본 모델 없음이면 숨긴다
 *   (상태 조회는 AI 서버에 연결하지 않는다. 화면을 불러올 때·브라우저 창으로 돌아올 때 다시 본다)
 * - 기능 범위 (AI-7 3차, AI7-D24·D26·D27) : 메타데이터(METADATA, 기본) / 품질 진단(QUALITY, 저장된 품질 진단 결과). 고른 범위는 이 창이 떠 있는 동안만
 *   기억한다 (DB·브라우저 저장소에 두지 않는다 — 새로고침·프로젝트 전환이면 METADATA). 질문을 보낼 때의 범위를 그 질문에 고정하고 답변 옆에 표시하며,
 *   응답을 기다리는 동안에는 범위를 바꿀 수 없다. 범위마다 서버가 정한 Tool 만 실린다 (화면은 범위 이름만 보낸다)
 */
type Scope = 'METADATA' | 'QUALITY';
type ToolStatus = 'OK' | 'NOT_FOUND' | 'INVALID_ARGUMENT' | 'REJECTED' | 'FAILED';
interface ToolCall { name: string; status: ToolStatus; durationMillis: number }
type EvidenceType = 'SCHEMA' | 'TABLE' | 'COLUMN' | 'RELATION' | 'TERM' | 'WORD' | 'DOMAIN' | 'HISTORY'
  // 논리 모델 (D-35 D35-3b)
  | 'LOGICAL_MODEL' | 'SUBJECT_AREA' | 'LOGICAL_ENTITY' | 'LOGICAL_ATTRIBUTE' | 'LOGICAL_IDENTIFIER' | 'LOGICAL_RELATIONSHIP' | 'SUBTYPE_GROUP'
  // 저장된 품질 진단 결과·실행 (AI-7) : 현재 모델이 아니라 검사가 저장한 기록이다
  | 'QUALITY_FINDING' | 'QUALITY_RUN';
type EvidenceStatus = 'VERIFIED' | 'NOT_FOUND' | 'OUT_OF_SCOPE' | 'MISMATCH' | 'STALE' | 'UNVERIFIABLE';
type EvidenceReason = 'NOT_RETRIEVED' | 'MODIFIED' | 'DELETED' | 'NAME' | 'PARENT' | 'KIND' | 'VERSION_UNKNOWN'
  | 'DESCRIPTION_NOT_RETRIEVED' | 'DESCRIPTION_NOT_REGISTERED';
type Overall = 'VERIFIED' | 'PARTIAL' | 'UNVERIFIED' | 'NO_EVIDENCE';
/** 근거와 서버의 검증 결과 (AI-4 후속). id 는 화면에 보이지 않는다 */
interface Evidence { type: EvidenceType; id: number; name: string | null; status: EvidenceStatus; reason: EvidenceReason | null; source: Mode | null }
/** 조회 방식 : 모델의 Tool 호출 / Tool Calling 을 지원하지 않는 모델이라 서버가 질문으로 정해 조회한 맥락 */
type Mode = 'TOOL_CALLING' | 'SERVER_CONTEXT';
/** 토큰 사용량 : prompt/completion 은 AI 서버가 알려 준 값(없으면 null), estimatedInputTokens 는 서버의 추정 (실측 아님) */
interface Usage {
  modelCalls: number; promptTokens: number | null; completionTokens: number | null; unmeasuredCalls: number;
  estimatedInputTokens: number; contextTokens: number; maxOutputTokens: number; totalBudget: number;
}
interface Verification { status: Overall; total: number; verified: number; invalid: number }
/**
 * 실행 완료 상태 (AI 공통 실행 안정성) : 서버가 정한다. PARTIAL 은 오류가 아니다 — 실행 제한·결과 범위 축소가 있었지만 답을 만들었다.
 * 근거 검증 상태(verification)와 따로다. 예전 서버 응답에는 없을 수 있다 (없으면 안내를 보이지 않는다)
 */
interface Completion { status: 'COMPLETE' | 'PARTIAL'; reason: string | null; executedTools: number }
interface QueryResult {
  answer: string; model: string | null; tools: ToolCall[]; durationMillis: number;
  evidence: Evidence[]; verification: Verification; structuredRetries: number;
  mode: Mode; fallback: 'MODEL_CAPABILITIES' | 'TOOLS_REJECTED' | null; usage: Usage | null;
  completion?: Completion | null;
}
interface Turn { id: number; question: string; scope: Scope; result?: QueryResult; error?: string }

const QUESTION_MAX = 1000;
const TOOL_LABEL: Record<string, string> = {
  listSchemas: t('스키마 목록'),
  searchTables: t('테이블 검색'),
  getTableDetail: t('테이블 상세'),
  getTableColumns: t('컬럼 목록'),
  getTableRelations: t('테이블 관계'),
  searchStandardTerms: t('표준 용어 검색'),
  searchStandardWords: t('표준 단어 검색'),
  searchStandardDomains: t('표준 도메인 검색'),
  findSimilarColumns: t('유사 컬럼 찾기'),
  getMetadataHistory: t('변경 이력'),
  getLogicalModelOverview: t('논리 모델 개요'),
  searchLogicalEntities: t('논리 엔터티 검색'),
  getLogicalEntityDetail: t('논리 엔터티 상세'),
  getQualityInspectionOverview: t('품질 진단 요약 조회'),
  searchQualityFindings: t('품질 진단 결과 검색'),
  getQualityFindingDetail: t('품질 진단 결과 상세 조회'),
};
const EVIDENCE_TYPE: Record<EvidenceType, string> = {
  SCHEMA: t('스키마'), TABLE: t('테이블'), COLUMN: t('컬럼'), RELATION: t('관계'), TERM: t('용어'), WORD: t('단어'), DOMAIN: t('도메인'), HISTORY: t('변경 이력'),
  LOGICAL_MODEL: t('논리 모델'), SUBJECT_AREA: t('주제 영역'), LOGICAL_ENTITY: t('엔터티'), LOGICAL_ATTRIBUTE: t('속성'), LOGICAL_IDENTIFIER: t('식별자'),
  LOGICAL_RELATIONSHIP: t('논리 관계'), SUBTYPE_GROUP: t('서브타입 그룹'),
  QUALITY_FINDING: t('저장된 품질 진단 결과'), QUALITY_RUN: tc('AI 근거', '품질 진단 실행'),
};
const QUALITY_EVIDENCE: ReadonlySet<EvidenceType> = new Set(['QUALITY_FINDING', 'QUALITY_RUN']);
const EVIDENCE_STATUS: Record<EvidenceStatus, string> = {
  VERIFIED: t('확인됨'),
  NOT_FOUND: t('없음'),
  OUT_OF_SCOPE: t('범위 밖'),
  MISMATCH: t('불일치'),
  STALE: t('조회 후 변경됨'),
  UNVERIFIABLE: t('확인 불가'),
};
const EVIDENCE_REASON: Record<EvidenceReason, string> = {
  NOT_RETRIEVED: t('AI 가 조회하지 않은 정보'),
  MODIFIED: t('조회 후 수정됨'),
  DELETED: t('조회 후 삭제됨'),
  NAME: t('이름이 다름'),
  PARENT: t('소속 테이블이 다름'),
  KIND: t('관계 종류가 다름'),
  VERSION_UNKNOWN: t('버전 정보 없음'),
  DESCRIPTION_NOT_RETRIEVED: t('AI 가 설명을 조회하지 않음'),
  DESCRIPTION_NOT_REGISTERED: t('등록된 설명 없음'),
};
/** 부분 답변 사유별 안내 (서버 enum 값을 그대로 보이지 않는다). 모르는 사유는 공통 안내 */
const PARTIAL_REASON: Record<string, string> = {
  INPUT_BUDGET_EXCEEDED: t('입력 토큰 한도에 도달하여 현재까지 확인한 정보를 제공합니다.'),
  QUERY_BUDGET_EXCEEDED: t('질의 전체 토큰 한도에 도달하여 현재까지 확인한 정보를 제공합니다.'),
  TOTAL_TOOL_LIMIT_REACHED: t('전체 조회 호출 한도에 도달하여 현재까지 확인한 정보를 제공합니다.'),
  SAME_TOOL_LIMIT_REACHED: t('동일 조회 기능의 호출 한도에 도달하여 현재까지 확인한 정보를 제공합니다.'),
  DRAFT_OUTPUT_LIMIT_REACHED: t('답변 초안의 출력 한도에 도달하여 확인된 조회 결과를 바탕으로 답변을 제공합니다.'),
  CONTEXT_RESULT_LIMIT_REACHED: t('답변 생성에 사용할 수 있는 조회 결과 크기 한도에 도달하여 일부 정보만 제공합니다.'),
};
const PARTIAL_DEFAULT = t('실행 한도에 도달하여 확인된 범위의 정보만 제공합니다.');

/** 범위별 이름·안내·질문 예시. 예시는 입력란만 채운다 (보내거나 검사를 실행하지 않는다) */
const SCOPES: { scope: Scope; label: string; note: string; other: string; placeholder: string; examples: string[] }[] = [
  {
    scope: 'METADATA', label: tc('AI 범위', '메타데이터'),
    note: t('현재 프로젝트에 등록된 메타데이터(스키마·테이블·컬럼·관계·표준 단어·용어·도메인·논리 모델)에 대해 AI 에게 묻습니다. AI 는 읽기 전용 조회만 할 수 있습니다.'),
    other: t('저장된 품질 진단 결과는 품질 진단 범위에서 물어보세요.'),
    placeholder: t('예: 회원 정보를 저장하는 테이블과 주요 컬럼을 알려줘.'),
    examples: [t('이 테이블의 참조 관계를 설명해 줘.'), t('고객 엔터티의 식별자는 무엇이야?'), t('주문 관련 표준 용어를 찾아줘.')],
  },
  {
    scope: 'QUALITY', label: tc('AI 범위', '품질 진단'),
    note: t('저장된 품질 진단 결과(진단 규칙·판정 근거·실행 이력·집계)에 대해 묻고 개선 방향 설명을 듣습니다. 검사를 새로 실행하지 않으며, 결과는 마지막 검사 기준이라 현재 모델과 다를 수 있습니다. 개선 방향은 참고용 제안이며 저장되거나 적용되지 않습니다.'),
    other: t('논리 모델 등 그 밖의 메타데이터는 메타데이터 범위에서 물어보세요.'),
    placeholder: t('예: 현재 저장된 주요 품질 문제를 알려줘.'),
    examples: [t('현재 저장된 주요 품질 문제를 알려줘.'), t('최근 검사에서 새로 발견된 오류는 무엇이야?'), t('표준 용어 불일치 문제의 개선 방향을 설명해 줘.'),
      t('최근 검사에서 일부 규칙이 실패했는지 알려줘.')],
  },
];
const SCOPE_LABEL: Record<Scope, string> = { METADATA: tc('AI 범위', '메타데이터'), QUALITY: tc('AI 범위', '품질 진단') };

const STATUS_TEXT: Record<ToolStatus, string> = {
  OK: t('조회함'),
  NOT_FOUND: t('대상 없음'),
  INVALID_ARGUMENT: t('인자 오류'),
  REJECTED: t('허용되지 않은 Tool'),
  FAILED: t('조회 실패'),
};

export function AiAssistant() {
  const { user, canView } = useAuth();
  if (!user || !canView('ai-metadata-query')) return null;
  return <AiAssistantWidget key={user.projectId ?? 'none'} projectName={user.projectName} />;
}

/** 프로젝트가 바뀌면(key) 창의 질문 목록도 비운다 */
function AiAssistantWidget({ projectName }: { projectName: string | null }) {
  const dock = useDock();
  const liftStyle = { '--ai-assistant-lift': `${dock.lift}px` } as CSSProperties;
  const tabStyle = { '--ai-assistant-tab-right': `${dock.tabRight}px`, '--ai-assistant-tab-width': `${dock.tabWidth}px` } as CSSProperties;
  // 토스트(toast.css 모바일)가 올라간 버튼을 가리지 않게 같은 값을 :root 에도 둔다
  useEffect(() => {
    const root = document.documentElement.style;
    root.setProperty('--ai-assistant-lift', `${dock.lift}px`);
    return () => {
      root.removeProperty('--ai-assistant-lift');
    };
  }, [dock.lift]);
  const [open, setOpen] = useState(false);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [turns, setTurns] = useState<Turn[]>([]);
  // 기능 범위 : 이 창(컴포넌트)이 떠 있는 동안만 기억한다 — 저장하지 않는다 (새로고침·프로젝트 전환이면 기본값)
  const [scope, setScope] = useState<Scope>('METADATA');
  const current = SCOPES.find((s) => s.scope === scope) ?? SCOPES[0];
  const nextId = useRef(1);
  const input = useRef<HTMLTextAreaElement>(null);
  const bottom = useRef<HTMLDivElement>(null);
  // 버튼을 보일지 정하는 상태 (AI 서버에 연결하지 않는 조회) : 사이드바 AI 관리와 같은 상태를 쓴다
  const availability = useAiStatus();
  const { status, ready } = availability;

  useEffect(() => {
    if (open) input.current?.focus();
  }, [open]);
  useEffect(() => {
    bottom.current?.scrollIntoView({ block: 'end' });
  }, [turns, busy]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const ask = async (event?: FormEvent) => {
    event?.preventDefault();
    const text = question.trim();
    if (busy || !ready || !text) return;
    const id = nextId.current++;
    // 보내는 순간의 범위를 이 질문에 고정한다 (응답이 늦게 와도 이 질문의 범위로 표시된다)
    const asked = scope;
    setTurns((list) => [...list, { id, question: text, scope: asked }]);
    setQuestion('');
    setBusy(true);
    try {
      const result = await api<QueryResult>('/api/ai/metadata/query', { method: 'POST', body: { question: text, scope: asked }, silent: true });
      setTurns((list) => list.map((turn) => (turn.id === id ? { ...turn, result } : turn)));
    } catch (e) {
      const message = e instanceof ApiError ? e.message : t('AI 질의에 실패했습니다.');
      setTurns((list) => list.map((turn) => (turn.id === id ? { ...turn, error: message } : turn)));
      void availability.refetch(); // 관리자가 기본 Provider 를 바꿨을 수 있다
    } finally {
      setBusy(false);
      input.current?.focus();
    }
  };

  // Enter 로 보내고 Shift+Enter 는 줄바꿈 (한글 조합 중 Enter 는 보내지 않는다)
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void ask();
    }
  };

  // 버튼이 보이는 동안 본문 끝에 버튼 자리를 비운다 : 스크롤을 끝까지 내리면 마지막 요소(페이지네이션 등)가 버튼 위로 올라온다
  const shown = ready || open;
  useEffect(() => {
    if (!shown) return;
    document.documentElement.classList.add('has-ai-assistant');
    return () => document.documentElement.classList.remove('has-ai-assistant');
  }, [shown]);

  // 쓸 수 없으면 버튼을 숨긴다. 열어 둔 창은 안내를 보여 주고, 닫으면 버튼도 사라진다
  if (!shown) return null;

  return (
    <>
      {open && (
        <section className="ai-assistant-panel" style={liftStyle} role="dialog" aria-modal="false" aria-labelledby="aiAssistantTitle">
          <header className="ai-assistant-header">
            <span className="ai-assistant-badge" aria-hidden="true"><Sparkles size={14} /></span>
            <div className="ai-assistant-heading">
              <h2 id="aiAssistantTitle">{t('AI 도우미')}</h2>
              <span>{t('조회 범위 : {0} 프로젝트', projectName ?? '-')}</span>
            </div>
            <button type="button" className="ai-assistant-icon-btn" onClick={() => setTurns([])} disabled={busy || turns.length === 0}
              title={t('대화 지우기')} aria-label={t('대화 지우기')}><Eraser size={15} aria-hidden="true" /></button>
            <button type="button" className="ai-assistant-icon-btn" onClick={() => setOpen(false)} title={t('닫기')} aria-label={t('닫기')}>
              <X size={16} aria-hidden="true" />
            </button>
          </header>

          <div className="ai-assistant-body" aria-live="polite">
            <p className="ai-assistant-note">
              {current.note} {current.other}{' '}
              {t('질문과 AI 가 조회한 메타데이터는 관리자가 지정한 AI 서버로 전송됩니다. 질문과 답변은 저장하지 않습니다.')}
            </p>
            {turns.length === 0 && ready && (
              <div className="ai-assistant-examples">
                <span className="ai-assistant-examples-label">{t('질문 예시')}</span>
                {current.examples.map((example) => (
                  <button key={example} type="button" className="ai-assistant-example" onClick={() => { setQuestion(example); input.current?.focus(); }}>
                    {example}
                  </button>
                ))}
              </div>
            )}
            {status && !ready && (
              <div role="alert" className="ai-assistant-error">
                {status === 'NOT_CONFIGURED'
                  ? t('시스템 기본 AI Provider 가 지정되지 않아 질의할 수 없습니다. 관리자에게 AI Provider 관리에서 기본 Provider 지정을 요청하세요.')
                  : t('시스템 기본 AI Provider 를 지금 사용할 수 없습니다 (비활성화·기본 모델 없음 등). 관리자에게 문의하세요.')}
              </div>
            )}
            {availability.isError && <div role="alert" className="ai-assistant-error">{t('AI 도우미 상태를 확인하지 못했습니다.')}</div>}

            {turns.map((turn) => (
              <div key={turn.id} className="ai-assistant-turn">
                <div className="ai-assistant-question">
                  <span className={`ai-assistant-turn-scope is-${turn.scope.toLowerCase()}`}>{SCOPE_LABEL[turn.scope]}</span>
                  {turn.question}
                </div>
                {turn.result && <Answer result={turn.result} />}
                {turn.error && <div role="alert" className="ai-assistant-error">{turn.error}</div>}
              </div>
            ))}
            {busy && <p className="ai-assistant-waiting" role="status">{t('응답 기다리는 중…')}</p>}
            <div ref={bottom} />
          </div>

          <div className="ai-assistant-scope" role="radiogroup" aria-label={t('기능 범위')}>
            {SCOPES.map((s) => (
              <label key={s.scope} className={scope === s.scope ? 'is-selected' : undefined}
                title={busy ? t('응답을 받은 뒤 범위를 바꿀 수 있습니다.') : undefined}>
                <input type="radio" name="aiAssistantScope" value={s.scope} checked={scope === s.scope} disabled={busy}
                  onChange={() => setScope(s.scope)} />
                {s.label}
              </label>
            ))}
          </div>
          <form className="ai-assistant-input" onSubmit={ask}>
            <textarea ref={input} rows={2} maxLength={QUESTION_MAX} value={question} aria-label={t('질문 ({0})', current.label)}
              onChange={(e) => setQuestion(e.target.value)} onKeyDown={onKeyDown} disabled={!ready && !!status}
              placeholder={current.placeholder} />
            <button type="submit" className="btn btn-primary" disabled={busy || !ready || !question.trim()} aria-label={t('질의')} title={t('질의')}>
              <Send size={14} aria-hidden="true" />
            </button>
          </form>
          <p className="ai-assistant-hint">{t('Enter 로 보내고 Shift+Enter 로 줄을 바꿉니다. 질문마다 따로 답합니다 (앞 대화를 기억하지 않음).')}</p>
        </section>
      )}
      {(ready || open) && <button type="button" className={`ai-assistant-launcher${open ? ' is-open' : ''}${dock.docked ? ' is-docked' : ''}`}
        style={dock.docked ? tabStyle : liftStyle} onClick={() => setOpen((v) => !v)}
        aria-expanded={open} aria-label={open ? t('AI 도우미 닫기') : t('AI 도우미 열기')} title={t('AI 도우미')}>
        {dock.docked
          ? <><Sparkles size={11} aria-hidden="true" /><span className="ai-assistant-tab-text" aria-hidden="true">AI</span></>
          : open ? <X size={18} aria-hidden="true" /> : <Sparkles size={18} aria-hidden="true" />}
      </button>}
    </>
  );
}

interface Dock {
  /** 푸터가 화면 아래쪽을 차지하는 높이(px) : 버튼·창을 이만큼 올린다 */
  lift: number;
  /** 푸터 윗부분이 화면 밖이다 : 둥근 버튼이 놓일 빈자리도 화면 밖이라 탭으로 줄인다 */
  docked: boolean;
  /** 탭 위치 : 본문 오른쪽 여백(스크롤바 안쪽)의 오른쪽 끝과 폭 */
  tabRight: number;
  tabWidth: number;
}

/**
 * 푸터(.app-footer)와 본문(.content) 위치로 버튼 모양·위치를 정한다. 푸터는 스크롤되는 본문의 맨 아래에 있어 스크롤·창 크기·내용 높이에 따라
 * 보이는 위치가 바뀌므로 계속 다시 잰다
 */
function useDock(): Dock {
  const [dock, setDock] = useState<Dock>({ lift: 0, docked: false, tabRight: 0, tabWidth: 16 });
  useEffect(() => {
    let frame = 0;
    const measure = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const footer = document.querySelector('.app-footer');
        const content = document.querySelector<HTMLElement>('.content');
        const top = footer ? footer.getBoundingClientRect().top : window.innerHeight;
        let tabRight = 0;
        let tabWidth = 16;
        if (content) {
          const rect = content.getBoundingClientRect();
          const padding = parseFloat(getComputedStyle(content).paddingRight) || 0;
          // clientWidth 는 스크롤바를 뺀 폭 : 여백 오른쪽 끝 = 왼쪽 + 테두리 + clientWidth
          const innerRight = rect.left + content.clientLeft + content.clientWidth;
          tabRight = Math.max(0, Math.round(window.innerWidth - innerRight + 2));
          tabWidth = Math.max(10, Math.round(padding - 4));
        }
        const next = { lift: Math.max(0, Math.round(window.innerHeight - top)), docked: top > window.innerHeight, tabRight, tabWidth };
        setDock((prev) => (prev.lift === next.lift && prev.docked === next.docked && prev.tabRight === next.tabRight && prev.tabWidth === next.tabWidth ? prev : next));
      });
    };
    measure();
    // 본문의 스크롤은 window 로 올라오지 않으므로 capture 로 받는다
    document.addEventListener('scroll', measure, true);
    window.addEventListener('resize', measure);
    // 데이터가 늦게 불러와져 본문 높이가 바뀌면 스크롤 없이 푸터가 움직인다
    const observer = new ResizeObserver(measure);
    const section = document.querySelector('.content > section');
    if (section) observer.observe(section);
    const footer = document.querySelector('.app-footer');
    if (footer) observer.observe(footer);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener('scroll', measure, true);
      window.removeEventListener('resize', measure);
      observer.disconnect();
    };
  }, []);
  return dock;
}

/** 근거 검증 결과 : 상태는 서버가 정한다. 확인되지 않은 근거는 이름·사유와 함께 따로 표시한다 */
function EvidenceCheck({ result }: { result: QueryResult }) {
  const { canView } = useAuth();
  const v = result.verification;
  const quality = result.evidence.some((e) => QUALITY_EVIDENCE.has(e.type));
  const message = v.status === 'VERIFIED' ? t('근거 {0}개를 Lexorma 메타데이터와 대조해 확인했습니다.', v.total)
    : v.status === 'PARTIAL' ? t('일부 근거를 확인할 수 없습니다 ({0}/{1}개 확인). 확인되지 않은 항목은 아래와 같습니다.', v.verified, v.total)
      : v.status === 'UNVERIFIED' ? t('근거를 확인하지 못했습니다. 이 답변은 확인되지 않은 정보입니다.')
        : t('근거가 없는 답변입니다. 등록된 메타데이터로 확인되지 않았습니다.');
  return (
    <div className={`ai-assistant-check is-${v.status.toLowerCase()}`}>
      <div className="ai-assistant-check-title" role="status">{v.status === 'VERIFIED' ? <Check size={13} aria-hidden="true" /> : <AlertTriangle size={13} aria-hidden="true" />} {message}</div>
      {result.evidence.length > 0 && (
        <ul className="ai-assistant-evidence">
          {result.evidence.map((e, index) => (
            <li key={index} className={`is-${e.status.toLowerCase()}`} title={e.reason ? EVIDENCE_REASON[e.reason] : EVIDENCE_STATUS[e.status]}>
              <span className="ai-assistant-evidence-mark" aria-hidden="true">{e.status === 'VERIFIED' ? '✓' : '!'}</span>
              <span className="ai-assistant-evidence-type">{EVIDENCE_TYPE[e.type] ?? e.type}</span>
              {(() => {
                const to = canView('standardization-quality') ? qualityLink(e) : null;
                return to
                  ? <Link className="ai-assistant-evidence-name" to={to} title={t('품질 진단 화면에서 보기 (지금 상태는 답변 당시와 다를 수 있습니다)')}>{e.name ?? '-'}</Link>
                  : <span className="ai-assistant-evidence-name">{e.name ?? '-'}</span>;
              })()}
              {e.status !== 'VERIFIED' && (
                <span className="ai-assistant-evidence-status">{EVIDENCE_STATUS[e.status]}{e.reason && ` · ${EVIDENCE_REASON[e.reason]}`}</span>
              )}
            </li>
          ))}
        </ul>
      )}
      {v.total > 0 && <p className="ai-assistant-check-note">{t('확인됨은 근거의 존재·프로젝트 범위·조회 결과와의 일치·버전을 확인했다는 뜻이며, 답변 문장 전체의 정확성을 뜻하지 않습니다.')}</p>}
      {quality && <p className="ai-assistant-check-note">{t('품질 진단 근거는 저장된 검사 결과·실행 기록과 대조한 것이며, 현재 모델이 그 결과와 같다는 뜻이 아닙니다.')}</p>}
    </div>
  );
}

/**
 * 품질 근거 → 품질 진단 화면 (결과 상세 / 실행 상세 대화상자). 서버가 이 프로젝트에서 확인한 근거만 연결한다 :
 * 확인됨·이름 불일치·조회 뒤 수정됨. 없음·범위 밖(다른 프로젝트 ID)·조회하지 않은 ID·삭제됨은 연결하지 않는다
 */
function qualityLink(e: Evidence): string | null {
  if (!QUALITY_EVIDENCE.has(e.type)) return null;
  const linkable = e.status === 'VERIFIED' || e.status === 'MISMATCH' || (e.status === 'STALE' && e.reason === 'MODIFIED');
  if (!linkable || !Number.isSafeInteger(e.id) || e.id <= 0) return null;
  return e.type === 'QUALITY_FINDING' ? `/standardizationQuality?finding=${e.id}` : `/standardizationQuality?tab=runs&run=${e.id}`;
}

/** 사용량 한 줄 : 실측이 있으면 그 값, 없으면 "미측정". 추정치는 제목(마우스 올림)에만 "추정" 으로 */
function usageText(usage: Usage | null | undefined): { text: string; title: string } | null {
  if (!usage) return null;
  const measured = usage.promptTokens != null || usage.completionTokens != null;
  const text = measured
    ? t('토큰 {0} + {1}', usage.promptTokens ?? '-', usage.completionTokens ?? '-') + (usage.unmeasuredCalls > 0 ? ` (${t('일부 미측정')})` : '')
    : t('토큰 미측정');
  const title = t('AI 서버가 알려 준 입력 + 출력 토큰 (모델 호출 {0}번). 보내기 전 추정 입력 {1} 토큰(실측 아님) · 컨텍스트 {2} · 출력 한도 {3} · 질의 한도 {4}',
    usage.modelCalls, usage.estimatedInputTokens, usage.contextTokens, usage.maxOutputTokens, usage.totalBudget);
  return { text, title };
}

/** 부분 답변 안내 (비차단 인라인) : 서버가 PARTIAL 로 정했을 때만. 답변·근거 표시는 그대로 둔다 */
function PartialNotice({ completion }: { completion: Completion | null | undefined }) {
  if (completion?.status !== 'PARTIAL') return null;
  const reason = (completion.reason && PARTIAL_REASON[completion.reason]) || PARTIAL_DEFAULT;
  return (
    <div className="ai-assistant-partial" role="note">
      <Info size={13} aria-hidden="true" />
      <div><strong>{t('부분 답변')}</strong> · {reason}</div>
    </div>
  );
}

function Answer({ result }: { result: QueryResult }) {
  const serverContext = result.mode === 'SERVER_CONTEXT';
  const usage = usageText(result.usage);
  return (
    <div className="ai-assistant-answer">
      <PartialNotice completion={result.completion} />
      {serverContext && (
        <p className="ai-assistant-mode" role="note">
          {t('현재 모델은 Tool Calling 을 지원하지 않아 서버가 조회한 메타데이터를 바탕으로 답변했습니다. 서버는 질문의 낱말로 필요한 만큼만 조회하므로 찾는 범위가 좁을 수 있습니다.')}
        </p>
      )}
      <NoticeMarkdown content={result.answer} />
      <EvidenceCheck result={result} />
      <div className="ai-assistant-tools">
        {serverContext && result.tools.length > 0 && <span className="ai-assistant-tools-label">{t('서버 조회')}</span>}
        {result.tools.length === 0
          ? <span className="ai-assistant-warn">{serverContext
            ? t('서버가 질문에서 조회할 대상을 찾지 못해 메타데이터를 조회하지 않았습니다. 테이블·컬럼·용어 이름을 넣어 다시 물어보세요.')
            : t('AI 가 Tool 을 실행하지 않았습니다. 이 답변은 등록된 메타데이터를 조회하지 않고 만든 것일 수 있습니다.')}</span>
          : result.tools.map((tool, index) => (
            <span key={index} className={`ai-assistant-tool is-${tool.status.toLowerCase()}`} title={`${tool.name} · ${STATUS_TEXT[tool.status] ?? tool.status} · ${tool.durationMillis} ms`}>
              {TOOL_LABEL[tool.name] ?? t('허용 목록 밖')}{tool.status !== 'OK' && ` · ${STATUS_TEXT[tool.status] ?? tool.status}`}
            </span>
          ))}
      </div>
      <div className="ai-assistant-meta">
        {result.model ?? '-'} · {t('{0} ms', result.durationMillis)}
        {usage && <> · <span title={usage.title}>{usage.text}</span></>}
      </div>
    </div>
  );
}
