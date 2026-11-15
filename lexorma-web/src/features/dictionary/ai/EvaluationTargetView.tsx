import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../../../hooks/useAuth';
import { ApiError } from '../../../lib/api';
import { t } from '../../../lib/i18n';
import { CERTAINTY_LABEL, STATUS_CLASS, STATUS_LABEL } from './aiDictionary';
import {
  aiEvaluation, CATEGORIES, CATEGORY_LABEL, JUDGMENT_LABEL, MEANING_LABEL, RECOMMENDATION_LABEL, UNCERTAINTY_LABEL,
  type Category, type EvaluationProposal, type Judgment, type JudgmentRow, type MeaningStatus, type RecommendationJudgment, type TargetDetail, type UncertaintyJudgment,
} from './aiEvaluation';

/**
 * 평가 대상 상세 (AI-5 품질 평가) : 사람이 확인한 실제 의미·확인 상태·범주, 모델별 제안과 판정. 정확성 판정은 실제 의미를 확인한 대상에만 할 수 있다.
 */
export function EvaluationTargetView({ id, onChanged }: { id: number; onChanged: () => void }) {
  const { canView } = useAuth();
  const canWrite = canView('ai-dictionary-evaluate');
  const query = useQuery({ queryKey: ['ai-eval-target', id], queryFn: () => aiEvaluation.detail(id), gcTime: 0 });
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  const d = query.data;
  if (!d) return <p className="gh-muted">{t('불러오는 중…')}</p>;

  const fail = (e: unknown) => setMessage({ kind: 'error', text: e instanceof ApiError ? e.message : String(e) });
  const done = (text: string) => { setMessage({ kind: 'ok', text }); onChanged(); void query.refetch(); };

  return (
    <div className="ai-dict-detail">
      <div className="ai-dict-detail-head">
        <strong className="ai-dict-name">{d.target.targetName}</strong>
        <span className="Label">{MEANING_LABEL[d.target.meaningStatus]}</span>
        {!d.target.exists && <span className="Label">{t('삭제됨')}</span>}
      </div>
      {message && <div className={`ai-dict-callout is-${message.kind}`} role="status">{message.text}</div>}
      <MeaningForm detail={d} canWrite={canWrite} onSaved={() => done(t('저장했습니다.'))} onError={fail} />

      <section className="ai-dict-section">
        <h4>{t('제안과 판정')} ({d.proposals.length})</h4>
        {d.proposals.length === 0 && <p className="gh-muted">{t('이 대상의 AI 설명 제안이 없습니다. AI 설명 제안 화면에서 만드세요.')}</p>}
        {d.proposals.map((p) => (
          <ProposalJudgment key={p.id} detail={d} proposal={p} canWrite={canWrite} onSaved={() => done(t('판정을 저장했습니다.'))} onError={fail} />
        ))}
      </section>
    </div>
  );
}

function MeaningForm({ detail, canWrite, onSaved, onError }: { detail: TargetDetail; canWrite: boolean; onSaved: () => void; onError: (e: unknown) => void }) {
  const [meaning, setMeaning] = useState(detail.actualMeaning ?? '');
  const [status, setStatus] = useState<MeaningStatus>(detail.target.meaningStatus);
  const [categories, setCategories] = useState<Category[]>(detail.target.categories);
  useEffect(() => { setMeaning(detail.actualMeaning ?? ''); setStatus(detail.target.meaningStatus); setCategories(detail.target.categories); }, [detail]);
  const save = () => aiEvaluation.update(detail.target.id, { version: detail.target.version, categories, actualMeaning: meaning, status }).then(onSaved, onError);
  return (
    <section className="ai-dict-section">
      <h4>{t('실제 업무 의미 (평가 기준)')}</h4>
      <textarea className="ai-dict-textarea" rows={3} maxLength={4000} value={meaning} readOnly={!canWrite} onChange={(e) => setMeaning(e.target.value)}
                aria-label={t('실제 업무 의미 (평가 기준)')} placeholder={t('업무 담당자·검토자가 확인한 실제 의미. 확인할 수 없으면 확인 불가로 둡니다.')} />
      {detail.meaningReviewerId && <p className="ai-dict-small gh-muted">{t('확인 : {0} · {1}', detail.meaningReviewerId, detail.meaningReviewedAt?.replace('T', ' ').slice(0, 16) ?? '')}</p>}
      <div className="ai-dict-toolbar">
        <select className="search-column-select" aria-label={t('확인 상태')} value={status} disabled={!canWrite} onChange={(e) => setStatus(e.target.value as MeaningStatus)}>
          {(Object.keys(MEANING_LABEL) as MeaningStatus[]).map((s) => <option key={s} value={s}>{MEANING_LABEL[s]}</option>)}
        </select>
      </div>
      <fieldset className="ai-dict-checks" disabled={!canWrite}>
        <legend className="ai-dict-small">{t('범주')}</legend>
        {CATEGORIES.map((c) => (
          <label key={c} className="ai-dict-check">
            <input type="checkbox" checked={categories.includes(c)} onChange={() => setCategories(categories.includes(c) ? categories.filter((x) => x !== c) : [...categories, c])} />
            {CATEGORY_LABEL[c]}
          </label>
        ))}
      </fieldset>
      {canWrite && <div className="ai-dict-buttons"><button type="button" className="btn btn-primary" disabled={status === 'CONFIRMED' && !meaning.trim()} onClick={() => void save()}>{t('저장')}</button></div>}
    </section>
  );
}

function ProposalJudgment({ detail, proposal: p, canWrite, onSaved, onError }: { detail: TargetDetail; proposal: EvaluationProposal; canWrite: boolean; onSaved: () => void; onError: (e: unknown) => void }) {
  const { user } = useAuth();
  const judgments = detail.judgments.filter((j) => j.proposalId === p.id);
  const mine = judgments.find((j) => j.reviewerId === user?.username) ?? null;
  const confirmed = detail.target.meaningStatus === 'CONFIRMED';
  const info = p.detail ?? {};
  return (
    <div className="ai-dict-eval-proposal">
      <div className="ai-dict-proposal-head">
        <span className={`Label ${STATUS_CLASS[p.status]}`}>{STATUS_LABEL[p.status]}{p.edited ? ` · ${t('수정 후 승인')}` : ''}</span>
        <span className="ai-dict-small">{p.model ?? '-'} · {p.promptVersion} · {p.createdAt?.replace('T', ' ').slice(0, 16)}</span>
        <span className="ai-dict-small gh-muted">{t('AI 확실성 {0} (정확성 아님)', CERTAINTY_LABEL[p.certainty])}</span>
        {!p.sameMetadata && <span className="Label">{t('등록 시점과 다른 메타데이터')}</span>}
        {p.invalidReferences > 0 && <span className="Label Label--attention">{t('존재하지 않는 객체 언급 {0}', p.invalidReferences)}</span>}
      </div>
      <p className="ai-dict-text">{p.proposedDescription}</p>
      {p.confirmedDescription && p.confirmedDescription !== p.proposedDescription && <p className="ai-dict-small">{t('확정 문구')} : {p.confirmedDescription}</p>}
      {(info.unknowns ?? []).length > 0 && <p className="ai-dict-small">{t('확인할 수 없는 부분')} : {(info.unknowns ?? []).join(' / ')}</p>}
      {(info.questions ?? []).length > 0 && <p className="ai-dict-small">{t('확인 질문')} : {(info.questions ?? []).join(' / ')}</p>}
      {(info.termRecommendation || info.domainRecommendation) && (
        <p className="ai-dict-small">{t('추천')} : {[info.termRecommendation?.name, info.domainRecommendation?.name].filter(Boolean).join(' · ')}</p>
      )}
      {judgments.filter((j) => j !== mine).map((j) => <JudgmentSummary key={j.id} j={j} />)}
      {canWrite ? <JudgmentForm detail={detail} proposal={p} mine={mine} confirmed={confirmed} onSaved={onSaved} onError={onError} /> : mine && <JudgmentSummary j={mine} />}
    </div>
  );
}

function JudgmentSummary({ j }: { j: JudgmentRow }) {
  return (
    <p className="ai-dict-small">
      {j.reviewerId} : {JUDGMENT_LABEL[j.judgment]} · {t('근거 없는 주장')} {j.unfoundedClaim ? t('있음') : t('없음')} · {UNCERTAINTY_LABEL[j.uncertainty]}
      {j.missingInformation ? ` · ${t('누락')} : ${j.missingInformation}` : ''}{j.requiredEdits ? ` · ${t('수정 필요')} : ${j.requiredEdits}` : ''}
    </p>
  );
}

function JudgmentForm({ detail, proposal, mine, confirmed, onSaved, onError }: {
  detail: TargetDetail; proposal: EvaluationProposal; mine: JudgmentRow | null; confirmed: boolean; onSaved: () => void; onError: (e: unknown) => void;
}) {
  const [judgment, setJudgment] = useState<Judgment>(mine?.judgment ?? (confirmed ? 'CORRECT' : 'NOT_EVALUABLE'));
  const [unfounded, setUnfounded] = useState(mine?.unfoundedClaim ?? false);
  const [uncertainty, setUncertainty] = useState<UncertaintyJudgment>(mine?.uncertainty ?? 'APPROPRIATE');
  const [term, setTerm] = useState<RecommendationJudgment>(mine?.termRecommendation ?? (proposal.detail?.termRecommendation ? 'APPROPRIATE' : 'NO_RECOMMENDATION'));
  const [domain, setDomain] = useState<RecommendationJudgment>(mine?.domainRecommendation ?? (proposal.detail?.domainRecommendation ? 'APPROPRIATE' : 'NO_RECOMMENDATION'));
  const [missing, setMissing] = useState(mine?.missingInformation ?? '');
  const [edits, setEdits] = useState(mine?.requiredEdits ?? '');
  const [review, setReview] = useState(mine?.reviewMinutes?.toString() ?? '');
  const [writing, setWriting] = useState(mine?.writingMinutes?.toString() ?? '');
  const minutes = (v: string) => (v.trim() === '' ? null : Math.max(0, Math.round(Number(v))));
  const save = () => aiEvaluation.judge({
    targetId: detail.target.id, proposalId: proposal.id, version: mine?.version ?? null, judgment, unfoundedClaim: unfounded, missingInformation: missing || null,
    uncertainty, termRecommendation: term, domainRecommendation: domain, requiredEdits: edits || null, reviewMinutes: minutes(review), writingMinutes: minutes(writing),
  }).then(onSaved, onError);
  const select = <T extends string>(label: string, value: T, labels: Record<T, string>, set: (v: T) => void, allowed?: (v: T) => boolean) => (
    <label className="ai-dict-field">
      <span>{label}</span>
      <select className="search-column-select" value={value} onChange={(e) => set(e.target.value as T)}>
        {(Object.keys(labels) as T[]).filter((v) => !allowed || allowed(v)).map((v) => <option key={v} value={v}>{labels[v]}</option>)}
      </select>
    </label>
  );
  return (
    <form className="ai-dict-judgment" onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <div className="ai-dict-judgment-grid">
        {select(t('업무적 정확성'), judgment, JUDGMENT_LABEL, setJudgment, (v) => confirmed || v === 'NOT_EVALUABLE')}
        {select(t('불확실성 처리'), uncertainty, UNCERTAINTY_LABEL, setUncertainty)}
        {select(t('표준 용어 추천'), term, RECOMMENDATION_LABEL, setTerm)}
        {select(t('표준 도메인 추천'), domain, RECOMMENDATION_LABEL, setDomain)}
        <label className="ai-dict-field"><span>{t('검토 시간 (분)')}</span><input className="search-input" type="number" min={0} value={review} onChange={(e) => setReview(e.target.value)} /></label>
        <label className="ai-dict-field"><span>{t('직접 작성 시간 (분)')}</span><input className="search-input" type="number" min={0} value={writing} onChange={(e) => setWriting(e.target.value)} /></label>
      </div>
      <label className="ai-dict-check"><input type="checkbox" checked={unfounded} onChange={(e) => setUnfounded(e.target.checked)} /> {t('메타데이터로 뒷받침되지 않는 주장이 있음')}</label>
      <textarea className="ai-dict-textarea" rows={2} value={missing} onChange={(e) => setMissing(e.target.value)} placeholder={t('누락된 중요 정보')} aria-label={t('누락된 중요 정보')} />
      <textarea className="ai-dict-textarea" rows={2} value={edits} onChange={(e) => setEdits(e.target.value)} placeholder={t('수정이 필요한 부분')} aria-label={t('수정이 필요한 부분')} />
      {!confirmed && <p className="ai-dict-small gh-muted">{t('실제 의미를 확인하지 않은 대상은 정확성을 판정할 수 없습니다 (평가할 수 없음).')}</p>}
      <div className="ai-dict-buttons"><button type="submit" className="btn btn-primary">{mine ? t('판정 고치기') : t('판정 저장')}</button></div>
    </form>
  );
}
