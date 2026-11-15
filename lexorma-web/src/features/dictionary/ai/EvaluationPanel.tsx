import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ClipboardCheck } from 'lucide-react';
import { useDialog } from '../../../components/dialog/DialogProvider';
import { useAuth } from '../../../hooks/useAuth';
import { t, tv } from '../../../lib/i18n';
import { aiEvaluation, CATEGORIES, CATEGORY_LABEL, MEANING_LABEL, rateText, type Category, type ModelResult } from './aiEvaluation';
import { EvaluationTablePicker, type PickedTable } from './EvaluationTablePicker';
import { EvaluationTargetView } from './EvaluationTargetView';
import { SelectAll } from './SelectAll';

/**
 * AI 설명 제안 › 품질 평가 (AI-5 후속). 평가 대상(기준 데이터)을 고르고, 사람이 실제 의미를 확인해 적은 뒤 모델별 제안을 판정한다.
 * AI 가 판정하지 않는다 — 지표는 사람의 판정과 서버의 근거 검증만으로 계산한다. 판정이 없으면 "평가 미완료" 다.
 */
export function EvaluationPanel() {
  const { canView } = useAuth();
  const canWrite = canView('ai-dictionary-evaluate');
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const [table, setTable] = useState<PickedTable | null>(null);
  const tableId = table?.tableId ?? null;
  const [selected, setSelected] = useState<Map<string, Category[]>>(new Map());
  const [notice, setNotice] = useState<string | null>(null);
  const candidates = useQuery({ queryKey: ['ai-eval-candidates', tableId], queryFn: () => aiEvaluation.candidates(tableId), enabled: tableId != null });
  const targets = useQuery({ queryKey: ['ai-eval-targets'], queryFn: aiEvaluation.targets });
  const metrics = useQuery({ queryKey: ['ai-eval-metrics'], queryFn: aiEvaluation.metrics });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['ai-eval-targets'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-eval-metrics'] });
    void queryClient.invalidateQueries({ queryKey: ['ai-eval-candidates'] });
  };

  const open = (id: number) => dialog.open({ title: t('평가 대상'), className: 'dialog-wide', content: () => <EvaluationTargetView id={id} onChanged={refresh} /> });

  const register = async () => {
    const items = [...selected.entries()].map(([k, categories]) => {
      const [table, column] = k.split(':');
      return { tableId: Number(table), columnId: column ? Number(column) : null, categories };
    });
    const result = await aiEvaluation.register(items);
    setSelected(new Map());
    setNotice(t('평가 대상 {0}개를 등록했습니다 (이미 있는 {1}개는 건너뜀).', result.created, result.skipped));
    refresh();
  };

  const toggle = (key: string, categories: Category[]) => {
    const next = new Map(selected);
    if (next.has(key)) next.delete(key);
    else next.set(key, categories);
    setSelected(next);
  };

  const m = metrics.data;
  return (
    <>
      <section className="ai-dict-card">
        <h3>{t('품질 평가')}</h3>
        <p className="ai-dict-small">{t('업무 담당자·검토자가 확인한 실제 의미를 기준으로 AI 설명을 판정합니다. AI 의 확실성과 근거 검증(확인됨)은 정확성으로 보지 않습니다. 실제 의미는 AI 에 보내지 않습니다.')}</p>
        {m && (
          <>
            <p className="ai-dict-small">
              {t('평가 대상 {0}개 · 실제 의미 확인 {1} · 확인 불가 {2} · 미확인 {3}', m.dataset.targets, m.dataset.confirmed, m.dataset.unconfirmable, m.dataset.pending)}
              {CATEGORIES.some((c) => m.dataset.categories[c] > 0) && ` · ${CATEGORIES.filter((c) => m.dataset.categories[c] > 0).map((c) => `${CATEGORY_LABEL[c]} ${m.dataset.categories[c]}`).join(', ')}`}
            </p>
            {!m.evaluated && <div className="ai-dict-callout is-warn" role="status">{t('사람이 판정한 정확성 결과가 아직 없어 품질 평가가 완료되지 않았습니다.')}</div>}
            {m.models.length > 0 && <MetricsTable models={m.models} />}
            <p className="ai-dict-small gh-muted">{tv(m.note)}</p>
          </>
        )}
      </section>

      {canWrite && (
        <section className="ai-dict-card">
          <h3>{t('평가 대상 고르기')}</h3>
          <EvaluationTablePicker picked={table} onPick={(next) => { if (next.tableId !== tableId) setSelected(new Map()); setTable(next); }} />
          <div className="ai-dict-toolbar">
            <button type="button" className="btn btn-primary" disabled={selected.size === 0} onClick={() => void register()}>
              <ClipboardCheck size={14} aria-hidden="true" /> {t('고른 {0}개 등록', selected.size)}
            </button>
          </div>
          <p className="ai-dict-small gh-muted">{t('범주는 이름·타입·연결 정보로 고른 제안값입니다. 등록한 뒤 고칠 수 있습니다.')}</p>
          {notice && <div className="ai-dict-callout is-ok" role="status">{notice}</div>}
          {candidates.data && (
            <div className="ai-dict-table-wrap">
              <table className="ai-dict-table">
                <thead><tr><th className="ai-dict-col-check">
                  <SelectAll label={t('전체 선택')} total={candidates.data.filter((c) => !c.registered).length} selected={selected.size}
                             onChange={(all) => setSelected(all ? new Map(candidates.data!.filter((c) => !c.registered).map((c) => [`${c.tableId}:${c.columnId ?? ''}`, c.categories])) : new Map())} />
                </th><th>{t('대상')}</th><th>{t('범주')}</th><th>{t('제안')}</th></tr></thead>
                <tbody>
                  {candidates.data.map((c) => {
                    const k = `${c.tableId}:${c.columnId ?? ''}`;
                    return (
                      <tr key={k}>
                        <td><input type="checkbox" aria-label={c.name} disabled={c.registered} checked={selected.has(k)} onChange={() => toggle(k, c.categories)} /></td>
                        <td className="ai-dict-name">{c.name}{c.registered && <span className="gh-muted"> · {t('등록됨')}</span>}</td>
                        <td>{c.categories.map((x) => CATEGORY_LABEL[x]).join(', ') || '-'}</td>
                        <td>{c.proposalCount}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      <section className="ai-dict-card">
        <h3>{t('평가 대상')} ({targets.data?.length ?? 0})</h3>
        {(targets.data ?? []).length === 0 ? <p className="gh-muted">{t('등록된 평가 대상이 없습니다.')}</p> : (
          <div className="ai-dict-table-wrap">
            <table className="ai-dict-table">
              <thead><tr><th>{t('대상')}</th><th>{t('범주')}</th><th>{t('실제 의미')}</th><th>{t('제안')}</th><th>{t('판정')}</th></tr></thead>
              <tbody>
                {(targets.data ?? []).map((r) => (
                  <tr key={r.id}>
                    <td><button type="button" className="ai-dict-link ai-dict-name" onClick={() => open(r.id)}>{r.targetName}</button>{!r.exists && <span className="gh-muted"> · {t('삭제됨')}</span>}</td>
                    <td>{r.categories.map((x) => CATEGORY_LABEL[x]).join(', ') || '-'}</td>
                    <td>{MEANING_LABEL[r.meaningStatus]}</td>
                    <td>{r.proposalCount}</td>
                    <td>{r.judgmentCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

function MetricsTable({ models }: { models: ModelResult[] }) {
  const rows: [string, (m: ModelResult) => string][] = [
    [t('판정 수 (제안 / 검토자)'), (m) => `${m.judgments} (${m.proposals} / ${m.reviewers})`],
    [t('업무적 정확성 (맞음)'), (m) => rateText(m.accuracy)],
    [t('일부 맞음'), (m) => rateText(m.partial)],
    [t('평가할 수 없음'), (m) => String(m.notEvaluable)],
    [t('근거 없는 주장'), (m) => rateText(m.unfounded)],
    [t('존재하지 않는 객체 언급 (서버 검증)'), (m) => rateText(m.fabricated)],
    [t('표준 용어 추천 적절'), (m) => rateText(m.termFit)],
    [t('표준 도메인 추천 적절'), (m) => rateText(m.domainFit)],
    [t('불확실성 처리 적절'), (m) => rateText(m.uncertainty)],
    [t('알 수 없는 것을 단정'), (m) => rateText(m.missedUnknown)],
    [t('알 수 있는 것을 알 수 없다고 함'), (m) => rateText(m.excessiveUnknown)],
    [t('수정 후 승인'), (m) => rateText(m.editRate)],
    [t('승인율'), (m) => rateText(m.approvalRate)],
    [t('검토 / 직접 작성 시간 중앙값 (분)'), (m) => m.timeBase === 0 ? '-' : `${m.medianReviewMinutes} / ${m.medianWritingMinutes} (${m.timeBase})`],
    [t('시간 절감'), (m) => m.timeSaving == null ? '-' : `${(m.timeSaving * 100).toFixed(1)}%`],
    [t('등록 시점 메타데이터로 만든 판정'), (m) => `${m.sameMetadata} / ${m.judgments}`],
  ];
  return (
    <div className="ai-dict-table-wrap">
      <table className="ai-dict-table">
        <thead><tr><th>{t('지표')}</th>{models.map((m) => <th key={`${m.model}:${m.promptVersion}`}>{m.model ?? '-'}<br /><span className="ai-dict-type">{m.promptVersion}</span></th>)}</tr></thead>
        <tbody>{rows.map(([label, value]) => <tr key={label}><th scope="row">{label}</th>{models.map((m) => <td key={`${m.model}:${m.promptVersion}`}>{value(m)}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}
