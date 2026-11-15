import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, Check, Sparkles } from 'lucide-react';
import { useAuth } from '../../../hooks/useAuth';
import { ApiError } from '../../../lib/api';
import { t, tc } from '../../../lib/i18n';
import { aiDictionary, CERTAINTY_LABEL, CHANGE_LABEL, STATUS_CLASS, STATUS_LABEL, type Change, type ProposalDetail, type ReviewResult } from './aiDictionary';

const EVIDENCE_TYPE: Record<string, string> = {
  SCHEMA: t('스키마'), TABLE: t('테이블'), COLUMN: t('컬럼'), RELATION: t('관계'), TERM: t('용어'), WORD: t('단어'), DOMAIN: t('도메인'), HISTORY: t('변경 이력'),
};
const EVIDENCE_STATUS: Record<string, string> = {
  VERIFIED: t('확인됨'), NOT_FOUND: t('없음'), OUT_OF_SCOPE: t('범위 밖'), MISMATCH: t('불일치'), STALE: t('조회 후 변경됨'), UNVERIFIABLE: t('확인 불가'),
};
const OPEN = new Set(['PROPOSED', 'REVIEWED', 'ON_HOLD', 'REVIEW_REQUIRED']);

/**
 * AI 설명 제안 상세 (AI-5) : 원본 COMMENT · 지금 설명(DDL COMMENT) · 지금 업무 설명 · AI 제안을 나란히 보이고, 근거(검증 결과) · 불확실한 부분 · 확인 질문 ·
 * 표준 용어·도메인 추천 · 생성 뒤 변경 · 이력과 함께 승인 · 수정 후 승인 · 반려 · 보류 · 검토함 · 다시 만들기를 한다.
 * "확인됨" 은 근거 객체의 존재·범위·버전을 확인했다는 뜻이고 설명 문장 전체가 맞다는 뜻이 아니다.
 */
export function ProposalDetailView({ id, onChanged, onRegenerated }: { id: number; onChanged: () => void; onRegenerated: (newId: number) => void }) {
  const { canView, approvalAvailable } = useAuth();
  const canReview = canView('ai-dictionary-review') && approvalAvailable;
  const canGenerate = canView('ai-dictionary-generate');
  const query = useQuery({ queryKey: ['ai-dict-proposal', id], queryFn: () => aiDictionary.detail(id), gcTime: 0 });
  const [text, setText] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'ok' | 'warn' | 'error'; text: string; changes?: Change[] } | null>(null);

  if (query.isLoading) return <p className="gh-muted">{t('불러오는 중…')}</p>;
  if (query.isError || !query.data) return <p className="gh-muted">{t('제안을 불러오지 못했습니다.')}</p>;
  const d: ProposalDetail = query.data;
  const p = d.proposal;
  const draft = text ?? p.proposedDescription;
  const open = OPEN.has(p.status);

  const run = async (action: 'approve' | 'reject' | 'hold' | 'review') => {
    if (action === 'reject' && !reason.trim()) {
      setMessage({ kind: 'error', text: t('반려 사유를 입력해주세요.') });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const edited = action === 'approve' && draft.trim() !== p.proposedDescription.trim() ? draft.trim() : null;
      const result: ReviewResult = await aiDictionary.review(p.id, action, { version: p.version, description: edited, reason: reason.trim() || null });
      if (result.outcome === 'REVIEW_REQUIRED') {
        setMessage({ kind: 'warn', text: t('제안을 만든 뒤 근거가 바뀌어 반영하지 않았습니다. 재검토가 필요합니다.'), changes: result.changes });
      } else {
        setMessage({ kind: 'ok', text: result.outcome === 'APPROVED' ? t('업무 설명에 반영했습니다.') : t('처리했습니다.') });
      }
      setText(null);
      onChanged();
      await query.refetch();
    } catch (e) {
      setMessage({ kind: 'error', text: e instanceof ApiError ? e.message : t('처리하지 못했습니다.') });
      await query.refetch();
    } finally {
      setBusy(false);
    }
  };

  const regenerate = async () => {
    setBusy(true);
    setMessage(null);
    try {
      const result = await aiDictionary.generate(p.tableId, p.columnId, true);
      onChanged();
      onRegenerated(result.proposal.id);
    } catch (e) {
      setMessage({ kind: 'error', text: e instanceof ApiError ? e.message : t('처리하지 못했습니다.') });
    } finally {
      setBusy(false);
    }
  };

  const detail = d.detail ?? {};
  return (
    <div className="ai-dict-detail">
      <div className="ai-dict-detail-head">
        <span className={`gh-label ${STATUS_CLASS[p.status]}`}>{STATUS_LABEL[p.status]}</span>
        <span className="gh-label">{p.targetType === 'TABLE' ? t('테이블') : t('컬럼')}</span>
        <strong className="ai-dict-name">{p.targetName}</strong>
        <span className="gh-muted">{t('{0}차 제안', p.cycle)}</span>
        {p.recheckNeeded && <span className="gh-label gh-label-attention">{t('승인 뒤 근거 변경')}</span>}
      </div>

      <dl className="ai-dict-compare">
        <dt>{t('원본 COMMENT')}</dt>
        <dd>{d.current.sourceComment ?? <span className="gh-muted">{t('없음')}</span>}</dd>
        <dt>{p.targetType === 'TABLE' ? t('테이블 코멘트') : t('컬럼 설명')}</dt>
        <dd>{d.current.description ?? <span className="gh-muted">{t('없음')}</span>}</dd>
        <dt>{t('현재 업무 설명')}</dt>
        <dd>{d.current.bizDescription ?? <span className="gh-muted">{t('없음')}</span>}{!d.current.exists && <span className="gh-label gh-label-danger">{t('대상이 삭제됨')}</span>}</dd>
        <dt><Sparkles size={13} aria-hidden="true" /> {t('AI 제안')}</dt>
        <dd>
          {open && canReview ? (
            <textarea className="ai-dict-textarea" value={draft} maxLength={4000} rows={5} aria-label={t('AI 제안 (고치면 수정 후 승인)')}
                      onChange={(e) => setText(e.target.value)} />
          ) : <p className="ai-dict-text">{p.confirmedDescription ?? p.proposedDescription}</p>}
          <div className="gh-muted ai-dict-small">
            {t('확실성')} : {CERTAINTY_LABEL[p.certainty]}
            {detail.claimedCertainty && detail.claimedCertainty !== p.certainty && ` (${t('AI 는 {0} 라고 했지만 근거 검증으로 낮춤', CERTAINTY_LABEL[detail.claimedCertainty])})`}
          </div>
        </dd>
      </dl>

      {d.changes.length > 0 && (
        <div className="ai-dict-callout is-warn" role="status">
          <AlertTriangle size={14} aria-hidden="true" /> {t('제안을 만든 뒤 바뀐 것')}
          <ChangeList changes={d.changes} />
        </div>
      )}

      {detail.nameInterpretation && <Section title={t('이름 해석')}><p>{detail.nameInterpretation}</p></Section>}
      {(detail.termRecommendation || detail.domainRecommendation) && (
        <Section title={t('표준 추천 (참고 — 승인해도 연결을 바꾸지 않습니다)')}>
          <ul className="ai-dict-list">
            {detail.termRecommendation && <Rec label={t('표준 용어')} rec={detail.termRecommendation} />}
            {detail.domainRecommendation && <Rec label={t('표준 도메인')} rec={detail.domainRecommendation} />}
          </ul>
        </Section>
      )}
      <Section title={t('근거 ({0}/{1} 확인)', p.evidenceVerified, p.evidenceTotal)}>
        {(d.evidence ?? []).length === 0 ? <p className="gh-muted">{t('근거가 없습니다.')}</p> : (
          <ul className="ai-dict-list">
            {(d.evidence ?? []).map((e, i) => (
              <li key={i} className={e.status === 'VERIFIED' ? 'is-ok' : 'is-bad'}>
                {e.status === 'VERIFIED' ? <Check size={12} aria-hidden="true" /> : <AlertTriangle size={12} aria-hidden="true" />}
                <span className="ai-dict-type">{EVIDENCE_TYPE[e.type] ?? e.type}</span> <span className="ai-dict-name">{e.name ?? '-'}</span>
                {e.status !== 'VERIFIED' && <span className="gh-muted"> · {EVIDENCE_STATUS[e.status] ?? e.status}</span>}
              </li>
            ))}
          </ul>
        )}
        <p className="gh-muted ai-dict-small">{t('확인됨은 근거 객체의 존재·프로젝트 범위·조회 결과와의 일치·버전을 확인했다는 뜻이며, 설명 문장 전체의 정확성을 뜻하지 않습니다.')}</p>
      </Section>
      {(detail.related ?? []).length > 0 && (
        <Section title={t('연관 테이블·컬럼')}>
          <ul className="ai-dict-list">{(detail.related ?? []).map((r, i) => <li key={i} className={r.status === 'VERIFIED' ? 'is-ok' : 'is-bad'}><span className="ai-dict-name">{r.name}</span> — {r.note}</li>)}</ul>
        </Section>
      )}
      <Section title={t('확인할 수 없는 부분')}>
        {(detail.unknowns ?? []).length === 0 ? <p className="gh-muted">{t('없음')}</p> : <ul className="ai-dict-list">{(detail.unknowns ?? []).map((u, i) => <li key={i}>{u}</li>)}</ul>}
      </Section>
      <Section title={t('담당자에게 확인할 질문')}>
        {(detail.questions ?? []).length === 0 ? <p className="gh-muted">{t('없음')}</p> : <ul className="ai-dict-list">{(detail.questions ?? []).map((q, i) => <li key={i}>{q}</li>)}</ul>}
      </Section>

      <Section title={t('생성 정보')}>
        <p className="gh-muted ai-dict-small">
          {p.model ?? '-'} · {p.promptVersion} · {p.createdAt?.replace('T', ' ').slice(0, 19)}
          {p.jobId != null && ` · ${t('대량 생성 #{0}', p.jobId)}`}
          {detail.usage?.promptTokens != null && ` · ${t('토큰 {0} + {1}', detail.usage.promptTokens, detail.usage.completionTokens ?? '-')}`}
        </p>
      </Section>
      <Section title={t('검토 이력')}>
        <ul className="ai-dict-list ai-dict-history">
          {d.history.map((h) => (
            <li key={h.id}>
              <span className="gh-muted">{h.processedAt.replace('T', ' ').slice(0, 19)}</span> {h.processorId ?? '-'} · {h.action}
              {h.before && ` (${STATUS_LABEL[h.before]} → ${STATUS_LABEL[h.after]})`}{h.reason && ` · ${h.reason}`}
            </li>
          ))}
        </ul>
      </Section>

      {message && (
        <div className={`ai-dict-callout is-${message.kind}`} role="status">
          {message.text}
          {message.changes && <ChangeList changes={message.changes} />}
        </div>
      )}
      {(open && canReview) && (
        <div className="ai-dict-actions">
          <input className="search-input ai-dict-reason" value={reason} maxLength={1000} onChange={(e) => setReason(e.target.value)}
                 placeholder={t('사유 (반려는 필수, 보류는 선택)')} aria-label={t('사유')} />
          <div className="ai-dict-buttons">
            {p.status !== 'REVIEW_REQUIRED' && (
              <button type="button" className="btn btn-primary" disabled={busy || !draft.trim()} onClick={() => run('approve')}>
                {draft.trim() !== p.proposedDescription.trim() ? t('수정 후 승인') : t('승인')}
              </button>
            )}
            <button type="button" className="btn" disabled={busy} onClick={() => run('reject')}>{tc('버튼', '반려')}</button>
            {p.status !== 'ON_HOLD' && <button type="button" className="btn" disabled={busy} onClick={() => run('hold')}>{t('보류')}</button>}
            {(p.status === 'PROPOSED' || p.status === 'ON_HOLD') && <button type="button" className="btn" disabled={busy} onClick={() => run('review')}>{t('검토함')}</button>}
          </div>
        </div>
      )}
      {canGenerate && (p.status === 'REVIEW_REQUIRED' || p.status === 'REJECTED' || p.recheckNeeded || p.status === 'APPROVED') && d.current.exists && (
        <div className="ai-dict-buttons">
          <button type="button" className="btn" disabled={busy} onClick={regenerate}>{busy ? t('만드는 중…') : t('지금 메타데이터로 다시 만들기')}</button>
        </div>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="ai-dict-section">
      <h4>{title}</h4>
      {children}
    </section>
  );
}

function Rec({ label, rec }: { label: string; rec: { name: string; reason: string; status: string } }) {
  return (
    <li className={rec.status === 'VERIFIED' ? 'is-ok' : 'is-bad'}>
      <span className="ai-dict-type">{label}</span> <span className="ai-dict-name">{rec.name}</span> — {rec.reason}
      {rec.status !== 'VERIFIED' && <span className="gh-muted"> · {EVIDENCE_STATUS[rec.status] ?? rec.status}</span>}
    </li>
  );
}

/** 생성 뒤 바뀐 것 : 종류 · 객체 (필드), 값을 알면 생성 때 값 → 지금 값 */
function ChangeList({ changes }: { changes: Change[] }) {
  return (
    <ul>
      {changes.map((c, i) => (
        <li key={i}>
          {CHANGE_LABEL[c.kind] ?? c.kind}{c.name ? ` · ${c.name}` : ''}{c.fields.length > 0 ? ` (${c.fields.join(', ')})` : ''}
          {c.diffs && c.diffs.length > 0 && (
            <ul className="ai-dict-diffs">
              {c.diffs.map((x, j) => (
                <li key={j}><code>{x.field}</code> : {t('변경 전')} <q>{x.before ?? t('없음')}</q> → {t('변경 후')} <q>{x.after ?? t('없음')}</q></li>
              ))}
            </ul>
          )}
        </li>
      ))}
    </ul>
  );
}
