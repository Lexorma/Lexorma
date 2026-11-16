import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, Check, Sparkles } from 'lucide-react';
import { useAiStatus } from '../../../hooks/useAiStatus';
import { useAuth } from '../../../hooks/useAuth';
import { ApiError } from '../../../lib/api';
import { t, tc } from '../../../lib/i18n';
import { aiDictionary, APPROVAL_HOLD_LABEL, BLIND_NOTICE, CERTAINTY_LABEL, CHANGE_LABEL, OBJECT_TYPE_LABEL, STATUS_CLASS, STATUS_LABEL, type Change, type ProposalDetail, type ReviewResult } from './aiDictionary';
import { ACTION_LABEL, ReviewReasonDetail, reasonFieldLabel, reasonFieldLabels } from './ReasonView';

const EVIDENCE_STATUS: Record<string, string> = {
  VERIFIED: t('확인됨'), NOT_FOUND: t('없음'), OUT_OF_SCOPE: t('범위 밖'), MISMATCH: t('불일치'), STALE: t('조회 후 변경됨'), UNVERIFIABLE: t('확인 불가'),
};
const OPEN = new Set(['PROPOSED', 'REVIEWED', 'ON_HOLD', 'REVIEW_REQUIRED']);

/**
 * AI 설명 제안 상세 (AI-5) : 원본 COMMENT · 지금 설명(DDL COMMENT) · 지금 업무 설명 · AI 제안을 나란히 보이고, 근거(검증 결과) · 불확실한 부분 · 확인 질문 ·
 * 표준 용어·도메인 추천 · 생성 뒤 변경 · 이력과 함께 승인 · 수정 후 승인 · 반려 · 보류 · 검토함 · 다시 만들기를 한다.
 * "확인됨" 은 근거 객체의 존재·범위·버전을 확인했다는 뜻이고 설명 문장 전체가 맞다는 뜻이 아니다.
 * 평가 블라인드(AI-5-EVAL 2차) : 평가 대상의 실제 의미를 확인하기 전에는 서버가 AI 내용을 보내지 않는다 — 안내와 메타데이터·상태·승인 제한 사유만 보인다.
 */
export function ProposalDetailView({ id, onChanged, onRegenerated }: { id: number; onChanged: () => void; onRegenerated: (newId: number) => void }) {
  const { canView, approvalAvailable } = useAuth();
  const canReview = canView('ai-dictionary-review') && approvalAvailable;
  const canGenerate = canView('ai-dictionary-generate');
  const ai = useAiStatus();
  const [acknowledgeable, setAcknowledgeable] = useState(false);
  const query = useQuery({ queryKey: ['ai-dict-proposal', id], queryFn: () => aiDictionary.detail(id), gcTime: 0 });
  const [text, setText] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'ok' | 'warn' | 'error'; text: string; changes?: Change[] } | null>(null);

  if (query.isLoading) return <p className="gh-muted">{t('불러오는 중…')}</p>;
  if (query.isError || !query.data) return <p className="gh-muted">{t('제안을 불러오지 못했습니다.')}</p>;
  const d: ProposalDetail = query.data;
  const p = d.proposal;
  const proposed = p.proposedDescription ?? '';
  const draft = text ?? proposed;
  const open = OPEN.has(p.status);
  // 생성 뒤 다른 사용자가 업무 설명을 고쳤다 : 서버가 승인을 막는다 (확인을 다시 눌러도 반영되지 않는다) → 최신 설명으로 새 초안
  const bizConflict = open && d.changes.some((c) => c.kind === 'BIZ_DESCRIPTION_CHANGED');
  // 지금 업무 설명이 있고 승인할 문구와 다르면 승인은 교체다
  const replaces = open && !p.blind && !bizConflict && !!d.current.bizDescription && d.current.bizDescription.trim() !== draft.trim();

  const run = async (action: 'approve' | 'reject' | 'hold' | 'review' | 'close') => {
    if (action === 'reject' && !reason.trim()) {
      setMessage({ kind: 'error', text: t('반려 사유를 입력해주세요.') });
      return;
    }
    if (action === 'close' && !reason.trim()) {
      setMessage({ kind: 'error', text: t('종결 사유를 입력해주세요.') });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const edited = action === 'approve' && draft.trim() !== proposed.trim() ? draft.trim() : null;
      const result: ReviewResult = await aiDictionary.review(p.id, action, { version: p.version, description: edited, reason: reason.trim() || null });
      if (result.outcome === 'REVIEW_REQUIRED') {
        const biz = result.changes.some((c) => c.kind === 'BIZ_DESCRIPTION_CHANGED');
        setMessage({ kind: 'warn', text: biz
          ? t('제안을 만든 뒤 다른 사용자가 업무 설명을 수정해 반영하지 않았습니다. 최신 업무 설명은 그대로입니다. 새 초안을 만들어 다시 검토하세요.')
          : t('제안을 만든 뒤 근거가 바뀌어 반영하지 않았습니다. 재검토가 필요합니다.'), changes: result.changes });
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

  // 근거 재검증 (재검토 필요) : 남은 변경이 없으면 검토 중으로, 내용 변경뿐이면 확인(acknowledge + 사유) 후 기준을 옮겨 검토 중으로. 승인은 따로 한다
  const revalidate = async (acknowledge: boolean) => {
    if (acknowledge && !reason.trim()) {
      setMessage({ kind: 'error', text: t('확인한 변경과 계속 검토하는 이유를 입력해주세요.') });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const result = await aiDictionary.revalidate(p.id, { version: p.version, acknowledge, reason: reason.trim() || null });
      if (result.outcome === 'REVIEWABLE') {
        setMessage({ kind: 'ok', text: t('근거를 다시 확인해 검토할 수 있는 상태가 되었습니다. 내용을 보고 승인·반려하세요.') });
        setAcknowledgeable(false);
        setReason('');
      } else {
        setAcknowledgeable(result.acknowledgeable);
        setMessage({ kind: 'warn', text: result.acknowledgeable
          ? t('근거가 된 메타데이터의 내용이 바뀌었습니다. 아래 변경을 확인하고 이 제안을 그대로 검토하려면 사유를 적고 "변경 확인 후 계속 검토"를 누르세요. 아니면 새 초안을 만드세요.')
          : t('대상·근거·원본 테이블이 없어졌거나 업무 설명이 바뀌어 이 제안은 검토할 수 없습니다. 새 초안을 만들거나 종결하세요.'), changes: result.changes });
      }
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
        <dd>{d.bizDescriptionHidden
          ? <span className="gh-muted">{t('AI 제안 승인으로 들어간 값이라 실제 의미를 확인하기 전에는 보이지 않습니다.')}</span>
          : (d.current.bizDescription ?? <span className="gh-muted">{t('없음')}</span>)}{!d.current.exists && <span className="gh-label gh-label-danger">{t('대상이 삭제됨')}</span>}</dd>
        <dt><Sparkles size={13} aria-hidden="true" /> {t('AI 제안')}</dt>
        <dd>
          {p.blind ? (
            <div className="ai-dict-callout is-warn" role="note"><AlertTriangle size={14} aria-hidden="true" /> {BLIND_NOTICE}</div>
          ) : <>
            {open && canReview ? (
              <textarea className="ai-dict-textarea" value={draft} maxLength={4000} rows={5} aria-label={t('AI 제안 (고치면 수정 후 승인)')}
                        onChange={(e) => setText(e.target.value)} />
            ) : <p className="ai-dict-text">{p.confirmedDescription ?? p.proposedDescription}</p>}
            <div className="gh-muted ai-dict-small">
              {t('확실성')} : {p.certainty ? CERTAINTY_LABEL[p.certainty] : '-'}
              {detail.claimedCertainty && detail.claimedCertainty !== p.certainty && ` (${t('AI 는 {0} 라고 했지만 근거 검증으로 낮춤', CERTAINTY_LABEL[detail.claimedCertainty])})`}
            </div>
          </>}
        </dd>
      </dl>
      {replaces && canReview && (
        <div className="ai-dict-callout is-warn" role="note">
          <AlertTriangle size={14} aria-hidden="true" /> {t('이 제안을 승인하면 현재 업무 설명이 AI 제안 설명으로 교체됩니다.')}
        </div>
      )}
      {bizConflict && (
        <section className="ai-dict-review-required" aria-labelledby={`ai-dict-conflict-${p.id}`}>
          <h4 id={`ai-dict-conflict-${p.id}`}><AlertTriangle size={14} aria-hidden="true" /> {t('업무 설명 충돌 — 승인할 수 없습니다')}</h4>
          <dl className="ai-dict-compare">
            <dt>{t('제안 생성 당시 업무 설명')}</dt><dd>{d.baseBizDescription ?? <span className="gh-muted">{t('없음')}</span>}</dd>
            <dt>{t('현재 업무 설명')}</dt><dd>{d.current.bizDescription ?? <span className="gh-muted">{t('없음')}</span>}</dd>
            <dt>{t('AI 제안 설명')}</dt><dd>{p.blind ? <span className="gh-muted">{BLIND_NOTICE}</span> : p.proposedDescription}</dd>
          </dl>
          <p className="ai-dict-small">{t('제안을 만든 뒤 다른 사용자가 업무 설명을 수정했습니다. 이 제안으로 승인하면 그 수정이 사라지므로 반영하지 않습니다. 최신 업무 설명을 기준으로 새 초안을 만들거나 제안을 종결하세요.')}</p>
          {canGenerate && d.current.exists && p.status !== 'REVIEW_REQUIRED' && (
            <div className="ai-dict-buttons">
              <button type="button" className="btn" disabled={busy || !ai.ready} onClick={regenerate} title={ai.ready ? undefined : t('AI Provider 를 지금 쓸 수 없습니다')}>
                {busy ? t('만드는 중…') : t('새 초안 생성')}
              </button>
            </div>
          )}
        </section>
      )}

      {d.changes.length > 0 && (
        <div className="ai-dict-callout is-warn" role="status">
          <AlertTriangle size={14} aria-hidden="true" /> {t('제안을 만든 뒤 바뀐 것')}
          <ChangeList changes={d.changes} />
        </div>
      )}
      {p.status === 'REVIEW_REQUIRED' && (() => {
        // 재검토가 필요해진 때와 그때 남긴 사유 (기록이 없으면 알 수 있는 것만)
        const flagged = [...d.history].reverse().find((h) => h.action === 'REVIEW_REQUIRED' || h.action === 'GENERATE');
        return (
          <section className="ai-dict-review-required">
            <h4><AlertTriangle size={14} aria-hidden="true" /> {t('재검토가 필요합니다')}</h4>
            <dl className="ai-dict-compare">
              {d.changes.length > 0 && <><dt>{t('사유')}</dt><dd>{[...new Set(d.changes.map((c) => CHANGE_LABEL[c.kind] ?? c.kind))].join(', ')}</dd></>}
              <dt>{t('재검토 사유 (기록)')}</dt><dd><ReviewReasonDetail raw={p.reviewReason} action="REVIEW_REQUIRED" /></dd>
              <dt>{t('재검토 전환')}</dt><dd>{flagged ? `${flagged.processedAt.replace('T', ' ').slice(0, 16)} · ${flagged.processorId ?? '-'}` : '-'}</dd>
              <dt>{t('제안 생성')}</dt><dd>{p.createdAt?.replace('T', ' ').slice(0, 16) ?? '-'}</dd>
              <dt>{t('지금 남은 변경')}</dt><dd>{d.changes.length === 0 ? t('없음 — 근거 재검증으로 검토 중으로 돌릴 수 있습니다') : t('{0}건 (위 목록)', d.changes.length)}</dd>
            </dl>
            <p className="ai-dict-small gh-muted">{t('할 수 있는 조치 : 근거 재검증(서버가 지금 메타데이터와 다시 비교 — 승인은 따로), 새 초안 생성(앞 제안은 대체됨으로 남음), 제안 종결(적용하지 않음, 사유 필수).')}</p>
            <div className="ai-dict-buttons">
              {canReview && <button type="button" className="btn" disabled={busy} onClick={() => void revalidate(false)}>{t('근거 재검증')}</button>}
              {canReview && acknowledgeable && <button type="button" className="btn btn-primary" disabled={busy || !reason.trim()} onClick={() => void revalidate(true)}>{t('변경 확인 후 계속 검토')}</button>}
              {canGenerate && d.current.exists && (
                <button type="button" className="btn" disabled={busy || !ai.ready} onClick={regenerate} title={ai.ready ? undefined : t('AI Provider 를 지금 쓸 수 없습니다')}>
                  {busy ? t('만드는 중…') : t('새 초안 생성')}
                </button>
              )}
              {canReview && <button type="button" className="btn" disabled={busy || !reason.trim()} onClick={() => void run('close')}>{t('제안 종결')}</button>}
            </div>
            {canReview && <p className="ai-dict-small gh-muted">{t('계속 검토·종결의 사유는 아래 사유 칸에 적습니다.')}</p>}
          </section>
        );
      })()}

      {!p.blind && <>
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
                <span className="ai-dict-type">{OBJECT_TYPE_LABEL[e.type] ?? e.type}</span> <span className="ai-dict-name">{e.name ?? '-'}</span>
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
      </>}

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
              <span className="gh-muted">{h.processedAt.replace('T', ' ').slice(0, 19)}</span> {h.processorId ?? '-'} · {ACTION_LABEL[h.action] ?? t('처리')}
              {h.before && ` (${STATUS_LABEL[h.before]} → ${STATUS_LABEL[h.after]})`}
              {h.reason && <ReviewReasonDetail raw={h.reason} action={h.action} compact />}
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
      {open && d.evaluationHold && <div className="ai-dict-callout is-warn" role="note">{APPROVAL_HOLD_LABEL[d.evaluationHold]} {t('AI 관리 › AI 품질 평가에서 평가를 마치세요.')}</div>}
      {(open && canReview) && (
        <div className="ai-dict-actions">
          <textarea className="ai-dict-textarea ai-dict-reason" rows={2} value={reason} maxLength={1000} onChange={(e) => setReason(e.target.value)}
                    placeholder={t('사유 (반려·종결·계속 검토는 필수, 보류는 선택)')} aria-label={t('사유')} />
          <div className="ai-dict-buttons">
            {p.status !== 'REVIEW_REQUIRED' && (
              <button type="button" className="btn btn-primary" disabled={busy || !draft.trim() || bizConflict || !!d.evaluationHold} onClick={() => run('approve')}
                      title={d.evaluationHold ? APPROVAL_HOLD_LABEL[d.evaluationHold] : bizConflict ? t('제안을 만든 뒤 업무 설명이 바뀌어 승인할 수 없습니다') : undefined}>
                {draft.trim() !== proposed.trim()
                  ? (replaces ? t('수정 후 승인 및 교체') : t('수정 후 승인'))
                  : (replaces ? t('승인 및 교체') : tc('버튼', '승인'))}
              </button>
            )}
            <button type="button" className="btn" disabled={busy} onClick={() => run('reject')}>{tc('버튼', '반려')}</button>
            {p.status !== 'ON_HOLD' && <button type="button" className="btn" disabled={busy} onClick={() => run('hold')}>{t('보류')}</button>}
            {(p.status === 'PROPOSED' || p.status === 'ON_HOLD') && <button type="button" className="btn" disabled={busy} onClick={() => run('review')}>{t('검토함')}</button>}
            {p.status !== 'REVIEW_REQUIRED' && <button type="button" className="btn" disabled={busy} onClick={() => run('close')} title={t('적용하지 않고 닫습니다 (반려와 달리 품질 지표에 넣지 않음)')}>{t('제안 종결')}</button>}
          </div>
        </div>
      )}
      {canGenerate && (p.status === 'REJECTED' || p.recheckNeeded || p.status === 'APPROVED' || p.status === 'CLOSED') && d.current.exists && (
        <div className="ai-dict-buttons">
          <button type="button" className="btn" disabled={busy || !ai.ready} onClick={regenerate}>{busy ? t('만드는 중…') : t('지금 메타데이터로 다시 만들기')}</button>
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
      {changes.map((c, i) => c.kind === 'TERM_LINK_CHANGED' ? <TermLinkChange key={i} change={c} /> : (
        <li key={i}>
          {CHANGE_LABEL[c.kind] ?? CHANGE_LABEL.unknown}{c.name ? ` · ${c.name}` : ''}{c.fields.length > 0 ? ` (${reasonFieldLabels(c.fields).join(', ')})` : ''}
          {c.diffs && c.diffs.length > 0 && (
            <ul className="ai-dict-diffs">
              {c.diffs.filter((x) => !internalIdField(x.field)).map((x, j) => (
                <li key={j}><strong>{reasonFieldLabel(x.field)}</strong> : {t('변경 전')} <q>{x.before ?? t('없음')}</q> → {t('변경 후')} <q>{x.after ?? t('없음')}</q></li>
              ))}
            </ul>
          )}
        </li>
      ))}
    </ul>
  );
}

/** 연관의 ID 값(…Id)은 내부 ID 라 보이지 않는다 — 같은 연관의 이름(…Name) 변경이 함께 있다 */
function internalIdField(field: string) {
  const m = /^(.+)Id$/.exec(field);
  return !!m && reasonFieldLabel(field) !== field;
}

/** 표준 용어 연결 변경 : 이력에 남은 용어 이름(커밋 때 이름)으로 변경 전 → 후. 이름을 알 수 없으면 만들어 보이지 않는다 */
function TermLinkChange({ change }: { change: Change }) {
  const names = change.diffs?.find((x) => x.field === 'standardTermsName');
  return (
    <li>
      {CHANGE_LABEL.TERM_LINK_CHANGED}{change.name ? ` · ${change.name}` : ''}
      {change.changedAt && <span className="gh-muted"> · {change.changedAt.replace('T', ' ').slice(0, 16)}</span>}
      {names ? (
        <ul className="ai-dict-diffs">
          <li>{t('변경 전 표준 용어')} : <q>{names.before ?? t('없음')}</q></li>
          <li>{t('변경 후 표준 용어')} : <q>{names.after ?? t('없음')}</q></li>
          <li className="gh-muted">{t('기존 AI 제안이 이전 표준 용어를 근거로 작성되었습니다.')}</li>
        </ul>
      ) : <div className="gh-muted ai-dict-small">{t('변경 전후 표준 용어를 확인할 수 없습니다.')}</div>}
    </li>
  );
}
