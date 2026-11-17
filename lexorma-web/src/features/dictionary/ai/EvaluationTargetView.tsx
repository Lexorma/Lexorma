import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../../../hooks/useAuth';
import { ApiError } from '../../../lib/api';
import { t } from '../../../lib/i18n';
import { APPROVAL_HOLD_LABEL, CERTAINTY_LABEL, STATUS_CLASS, STATUS_LABEL } from './aiDictionary';
import {
  aiEvaluation, CATEGORIES, CATEGORY_LABEL, JUDGMENT_LABEL, MEANING_LABEL, RECOMMENDATION_LABEL, UNCERTAINTY_LABEL,
  type Category, type EvaluationProposal, type Judgment, type JudgmentRow, type MeaningStatus, type RecommendationJudgment, type TargetDetail, type TargetMetadata,
  type UncertaintyJudgment,
} from './aiEvaluation';

/**
 * 평가 대상 상세 (AI-5 품질 평가) : 대상의 지금 메타데이터, 사람이 확인한 실제 의미·확인 상태·범주, 모델별 제안과 판정.
 * <ul>
 *   <li>블라인드 : 실제 의미를 확인(확인함)하기 전에는 AI 제안을 보이지 않는다 (서버도 보내지 않는다). 판정도 확인한 대상에만</li>
 *   <li>판정이 있는 대상은 실제 의미·확인 상태를 고칠 수 없다 (서버가 막는다)</li>
 *   <li>판정 입력은 정확성·불확실성·추천을 고르지 않은 상태로 시작한다 (추천이 없으면 '추천 없음')</li>
 * </ul>
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
      <MetadataView metadata={d.metadata} />
      <MeaningForm detail={d} canWrite={canWrite} onSaved={() => done(t('저장했습니다.'))} onError={fail} />

      <section className="ai-dict-section">
        <h4>{t('제안과 판정')} ({d.target.proposalCount})</h4>
        {d.blind ? (
          <div className="ai-dict-callout is-warn" role="note">
            {t('실제 의미를 확인하기 전에는 AI 제안을 보이지 않습니다. AI 설명을 먼저 보고 기준 의미를 적지 않도록 하기 위해서입니다. 실제 의미를 적고 확인함으로 저장하면 제안 {0}개를 보고 판정할 수 있습니다.', d.target.proposalCount)}
          </div>
        ) : (
          <>
            {d.proposals.length === 0 && <p className="gh-muted">{t('이 대상의 AI 설명 제안이 없습니다. AI 설명 제안 화면에서 만드세요.')}</p>}
            {d.proposals.map((p) => (
              <ProposalJudgment key={p.id} detail={d} proposal={p} canWrite={canWrite} onSaved={() => done(t('판정을 저장했습니다.'))} onError={fail} />
            ))}
          </>
        )}
      </section>
    </div>
  );
}

/** 실제 의미를 적을 때 보는 대상의 지금 메타데이터. AI 승인으로 들어간 업무 설명은 표시하고, 확인 전에는 값을 가린다 */
function MetadataView({ metadata: m }: { metadata: TargetMetadata }) {
  if (!m.exists) return <p className="gh-muted">{t('대상이 삭제되어 지금 메타데이터를 볼 수 없습니다.')}</p>;
  const none = <span className="gh-muted">{t('없음')}</span>;
  return (
    <section className="ai-dict-section">
      <h4>{t('지금 메타데이터')}</h4>
      <dl className="ai-dict-compare">
        <dt>{t('테이블')}</dt><dd className="ai-dict-name">{m.tableName}</dd>
        {m.columnName && <><dt>{t('컬럼')}</dt><dd className="ai-dict-name">{m.columnName}</dd></>}
        <dt>{m.columnName ? t('컬럼 설명') : t('테이블 코멘트')}</dt><dd>{m.comment ?? none}</dd>
        {!m.columnName && <><dt>{t('테이블 설명')}</dt><dd>{m.description ?? none}</dd></>}
        {m.columnName && <><dt>{t('데이터 타입')}</dt><dd>{m.dataType ?? none}</dd></>}
        {m.columnName && <><dt>{t('표준 용어')}</dt><dd>{m.standardTerm ?? none}</dd></>}
        <dt>{t('원본 COMMENT')}</dt><dd>{m.sourceComment ?? none}</dd>
        <dt>{t('현재 업무 설명')}</dt>
        <dd>
          {m.bizDescriptionHidden ? <span className="gh-muted">{t('AI 제안 승인으로 들어간 값이라 실제 의미를 확인하기 전에는 보이지 않습니다.')}</span> : (m.bizDescription ?? none)}
          {m.bizDescriptionFromAi && !m.bizDescriptionHidden && <span className="Label"> {t('AI 제안 승인')}</span>}
        </dd>
      </dl>
    </section>
  );
}

function MeaningForm({ detail, canWrite, onSaved, onError }: { detail: TargetDetail; canWrite: boolean; onSaved: () => void; onError: (e: unknown) => void }) {
  // 판정이 있으면 그 판정의 기준이므로 의미·확인 상태를 고칠 수 없다 (범주는 고칠 수 있다)
  const locked = detail.target.judgmentCount > 0;
  const [meaning, setMeaning] = useState(detail.actualMeaning ?? '');
  const [status, setStatus] = useState<MeaningStatus>(detail.target.meaningStatus);
  const [categories, setCategories] = useState<Category[]>(detail.target.categories);
  useEffect(() => { setMeaning(detail.actualMeaning ?? ''); setStatus(detail.target.meaningStatus); setCategories(detail.target.categories); }, [detail]);
  const save = () => aiEvaluation.update(detail.target.id, { version: detail.target.version, categories, actualMeaning: meaning, status }).then(onSaved, onError);
  return (
    <section className="ai-dict-section">
      <h4>{t('실제 업무 의미 (평가 기준)')}</h4>
      <textarea className="ai-dict-textarea" rows={3} maxLength={4000} value={meaning} readOnly={!canWrite || locked} onChange={(e) => setMeaning(e.target.value)}
                aria-label={t('실제 업무 의미 (평가 기준)')} placeholder={t('업무 담당자·검토자가 확인한 실제 의미. 확인할 수 없으면 확인 불가로 둡니다.')} />
      {detail.meaningReviewerId && <p className="ai-dict-small gh-muted">{t('확인 : {0} · {1}', detail.meaningReviewerId, detail.meaningReviewedAt?.replace('T', ' ').slice(0, 16) ?? '')}</p>}
      {locked && <p className="ai-dict-small gh-muted">{t('판정이 있어 실제 의미와 확인 상태를 바꿀 수 없습니다 (판정의 기준이 바뀌지 않게). 범주는 고칠 수 있습니다.')}</p>}
      <div className="ai-dict-toolbar">
        <select className="search-column-select" aria-label={t('확인 상태')} value={status} disabled={!canWrite || locked} onChange={(e) => setStatus(e.target.value as MeaningStatus)}>
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
        {p.sameMetadata && !p.sameContext && <span className="Label" title={t('평가 대상 등록과 이 제안 생성 사이에 같은 테이블(다른 컬럼·업무 설명 포함)이 바뀌었습니다. 같은 입력 조건의 판정으로 세지 않습니다.')}>{t('입력 맥락 변경')}</span>}
        {p.invalidReferences > 0 && <span className="Label Label--attention">{t('존재하지 않는 객체 언급 {0}', p.invalidReferences)}</span>}
      </div>
      {p.approvalHold && <p className="ai-dict-small gh-muted">{APPROVAL_HOLD_LABEL[p.approvalHold]}</p>}
      <p className="ai-dict-text">{p.proposedDescription}</p>
      {p.confirmedDescription && p.confirmedDescription !== p.proposedDescription && <p className="ai-dict-small">{t('확정 문구')} : {p.confirmedDescription}</p>}
      {(info.unknowns ?? []).length > 0 && <p className="ai-dict-small">{t('확인할 수 없는 부분')} : {(info.unknowns ?? []).join(' / ')}</p>}
      {(info.questions ?? []).length > 0 && <p className="ai-dict-small">{t('확인 질문')} : {(info.questions ?? []).join(' / ')}</p>}
      {(info.termRecommendation || info.domainRecommendation) && (
        <p className="ai-dict-small">{t('추천')} : {[info.termRecommendation?.name, info.domainRecommendation?.name].filter(Boolean).join(' · ')}</p>
      )}
      {judgments.filter((j) => j !== mine).map((j) => <JudgmentSummary key={j.id} j={j} />)}
      {canWrite ? <JudgmentForm detail={detail} proposal={p} mine={mine} confirmed={confirmed} onSaved={onSaved} onError={onError} /> : mine && <JudgmentSummary j={mine} />}
      {judgments.map((j) => <JudgmentHistory key={`h${j.id}`} j={j} />)}
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

const HISTORY_FIELD_LABEL: Record<string, string> = {
  judgment: t('업무적 정확성'), unfoundedClaim: t('근거 없는 주장'), missingInformation: t('누락된 중요 정보'), uncertainty: t('불확실성 처리'),
  termRecommendation: t('표준 용어 추천'), domainRecommendation: t('표준 도메인 추천'), requiredEdits: t('수정이 필요한 부분'),
  reviewMinutes: t('검토 시간 (분)'), writingMinutes: t('직접 작성 시간 (분)'),
};

/** 판정 변경 이력 (DEC-27, 추가만) : 펼칠 때 한 번 읽는다. 지표는 최종 판정만 센다 */
function JudgmentHistory({ j }: { j: JudgmentRow }) {
  const [open, setOpen] = useState(false);
  const history = useQuery({ queryKey: ['ai-eval-judgment-history', j.id, j.version], queryFn: () => aiEvaluation.judgmentHistory(j.id), enabled: open });
  const show = (field: string, value: unknown) => {
    if (value === null || value === undefined || value === '') return '-';
    if (field === 'judgment') return JUDGMENT_LABEL[value as Judgment] ?? String(value);
    if (field === 'uncertainty') return UNCERTAINTY_LABEL[value as UncertaintyJudgment] ?? String(value);
    if (field === 'termRecommendation' || field === 'domainRecommendation') return RECOMMENDATION_LABEL[value as RecommendationJudgment] ?? String(value);
    if (field === 'unfoundedClaim') return value ? t('있음') : t('없음');
    return String(value);
  };
  return (
    <div className="ai-dict-small">
      <button type="button" className="btn btn-sm" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {open ? t('판정 변경 이력 닫기') : t('판정 변경 이력 ({0})', j.reviewerId)}
      </button>
      {open && (
        <ul className="ai-dict-list">
          {(history.data ?? []).map((h) => {
            const changed = Object.keys(h.after).filter((k) => !h.before || JSON.stringify(h.before[k]) !== JSON.stringify(h.after[k]));
            return (
              <li key={h.id}>
                <span className="gh-muted">{h.changedAt.replace('T', ' ').slice(0, 19)}</span> {h.reviewerId} · {h.changeType === 'INSERT' ? t('처음 판정') : t('판정 수정')}
                {h.reason && <> · {t('사유')} : {h.reason}</>}
                {h.before && changed.length > 0 && (
                  <span> — {changed.map((k) => `${HISTORY_FIELD_LABEL[k] ?? k} : ${show(k, h.before?.[k])} → ${show(k, h.after[k])}`).join(' / ')}</span>
                )}
              </li>
            );
          })}
          {history.data?.length === 0 && <li className="gh-muted">{t('이력이 없습니다 (이력 기록 전의 판정).')}</li>}
        </ul>
      )}
    </div>
  );
}

const UNFOUNDED_LABEL: Record<'YES' | 'NO', string> = { YES: t('메타데이터로 뒷받침되지 않는 주장이 있음'), NO: t('없음') };

function JudgmentForm({ detail, proposal, mine, confirmed, onSaved, onError }: {
  detail: TargetDetail; proposal: EvaluationProposal; mine: JudgmentRow | null; confirmed: boolean; onSaved: () => void; onError: (e: unknown) => void;
}) {
  // 새 판정은 고르지 않은 상태로 시작한다 (유리한 값을 미리 고르지 않는다 — 근거 없는 주장 포함, AI-5-EVAL 2차). 저장한 판정은 그 값 그대로. 추천이 없으면 '추천 없음' (해당 없음 규칙)
  const noTerm = !proposal.detail?.termRecommendation;
  const noDomain = !proposal.detail?.domainRecommendation;
  const [judgment, setJudgment] = useState<Judgment | ''>(mine?.judgment ?? '');
  const [unfounded, setUnfounded] = useState<'YES' | 'NO' | ''>(mine ? (mine.unfoundedClaim ? 'YES' : 'NO') : '');
  const [uncertainty, setUncertainty] = useState<UncertaintyJudgment | ''>(mine?.uncertainty ?? '');
  const [term, setTerm] = useState<RecommendationJudgment | ''>(mine?.termRecommendation ?? (noTerm ? 'NO_RECOMMENDATION' : ''));
  const [domain, setDomain] = useState<RecommendationJudgment | ''>(mine?.domainRecommendation ?? (noDomain ? 'NO_RECOMMENDATION' : ''));
  const [missing, setMissing] = useState(mine?.missingInformation ?? '');
  const [edits, setEdits] = useState(mine?.requiredEdits ?? '');
  const [review, setReview] = useState(mine?.reviewMinutes?.toString() ?? '');
  const [writing, setWriting] = useState(mine?.writingMinutes?.toString() ?? '');
  // 저장한 판정을 고칠 때의 사유 (DEC-27 — 서버가 값이 바뀐 수정에 필수로 검사한다)
  const [changeReason, setChangeReason] = useState('');
  const minutes = (v: string) => (v.trim() === '' ? null : Math.max(0, Math.round(Number(v))));
  const complete = confirmed && judgment !== '' && unfounded !== '' && uncertainty !== '' && term !== '' && domain !== '' && (!mine || changeReason.trim() !== '');
  const save = () => {
    if (!complete) return;
    void aiEvaluation.judge({
      targetId: detail.target.id, proposalId: proposal.id, version: mine?.version ?? null, targetVersion: detail.target.version, judgment, unfoundedClaim: unfounded === 'YES',
      missingInformation: missing || null, uncertainty, termRecommendation: term, domainRecommendation: domain, requiredEdits: edits || null,
      reviewMinutes: minutes(review), writingMinutes: minutes(writing), changeReason: mine ? changeReason.trim() : null,
    }).then(() => { setChangeReason(''); onSaved(); }, onError);
  };
  const select = <T extends string>(label: string, value: T | '', labels: Record<T, string>, set: (v: T | '') => void) => (
    <label className="ai-dict-field">
      <span>{label} *</span>
      <select className="search-column-select" value={value} required onChange={(e) => set(e.target.value as T | '')}>
        <option value="" disabled>{t('선택하세요')}</option>
        {(Object.keys(labels) as T[]).map((v) => <option key={v} value={v}>{labels[v]}</option>)}
      </select>
    </label>
  );
  return (
    <form className="ai-dict-judgment" onSubmit={(e) => { e.preventDefault(); save(); }}>
      <div className="ai-dict-judgment-grid">
        {select(t('업무적 정확성'), judgment, JUDGMENT_LABEL, setJudgment)}
        {select(t('근거 없는 주장'), unfounded, UNFOUNDED_LABEL, setUnfounded)}
        {select(t('불확실성 처리'), uncertainty, UNCERTAINTY_LABEL, setUncertainty)}
        {select(t('표준 용어 추천'), term, RECOMMENDATION_LABEL, setTerm)}
        {select(t('표준 도메인 추천'), domain, RECOMMENDATION_LABEL, setDomain)}
        <label className="ai-dict-field"><span>{t('검토 시간 (분)')}</span><input className="search-input" type="number" min={0} value={review} onChange={(e) => setReview(e.target.value)} /></label>
        <label className="ai-dict-field"><span>{t('직접 작성 시간 (분)')}</span><input className="search-input" type="number" min={0} value={writing} onChange={(e) => setWriting(e.target.value)} /></label>
      </div>
      <textarea className="ai-dict-textarea" rows={2} value={missing} onChange={(e) => setMissing(e.target.value)} placeholder={t('누락된 중요 정보')} aria-label={t('누락된 중요 정보')} />
      <textarea className="ai-dict-textarea" rows={2} value={edits} onChange={(e) => setEdits(e.target.value)} placeholder={t('수정이 필요한 부분')} aria-label={t('수정이 필요한 부분')} />
      {mine && <textarea className="ai-dict-textarea" rows={2} value={changeReason} onChange={(e) => setChangeReason(e.target.value)} maxLength={1000}
                         placeholder={t('판정을 고친 사유 (필수 — 판정 변경 이력에 남습니다)')} aria-label={t('판정을 고친 사유')} />}
      {judgment === 'NOT_EVALUABLE' && <p className="ai-dict-small gh-muted">{t('평가할 수 없음은 평가 완료로 보지 않습니다 — 이 제안의 승인 제한이 유지됩니다.')}</p>}
      {!complete && <p className="ai-dict-small gh-muted">{t('* 표시 항목을 모두 골라야 저장할 수 있습니다.')}</p>}
      <div className="ai-dict-buttons"><button type="submit" className="btn btn-primary" disabled={!complete}>{mine ? t('판정 고치기') : t('판정 저장')}</button></div>
    </form>
  );
}
