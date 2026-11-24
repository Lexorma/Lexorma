import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import type { DialogApi, DialogControls } from '../dialog/DialogProvider';
import { ApiError, isConflict } from '../../lib/api';
import { formatTimestampPrecise } from '../../lib/format';
import { t } from '../../lib/i18n';

/**
 * 동시 편집 충돌(409) 복구 공통 부분 (M8-C3, docs/completed/modeling-concurrency-m8.md 15장 · 확정 정책 M8-C3-D01~D05).
 * - 자동 병합·자동 재시도·최신 기준값으로 조용히 다시 보내기 없음 (D01). 사용자가 최신 내용을 보고 고른 뒤 직접 다시 저장한다 (D04)
 * - 409 가 나도 입력은 그대로, 폼·대화상자를 닫지 않는다 (D02). 최신 데이터 조회 실패(대상 없음·권한)는 409 와 구분해 보여 주고 다시 저장할 수 없게 한다 (D03)
 * - 중복 제출 방지 (D05) : {@link useSubmitting} — 서버 검증을 대신하지 않는다
 * 하나의 거대한 편집 컴포넌트가 아니라 상태 훅 + 비교 표 + 검토 대화상자만 공통이고, 값 변환·재적용은 화면이 한다.
 */

// ------------------------------------------------------------------ 중복 제출 방지 (D05)

/** 요청 중에는 같은 작업을 다시 시작하지 않는다. 성공·실패·예외 모두 끝나면 풀린다 */
export function useSubmitting(): [boolean, <T>(work: () => Promise<T>) => Promise<T | undefined>] {
  const [busy, setBusy] = useState(false);
  const running = useRef(false);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);
  const run = useCallback(async <T,>(work: () => Promise<T>) => {
    if (running.current) return undefined;
    running.current = true;
    setBusy(true);
    try {
      return await work();
    } finally {
      running.current = false;
      if (mounted.current) setBusy(false);
    }
  }, []);
  return [busy, run];
}

// ------------------------------------------------------------------ 비교 표

/** 한 항목 : 편집 시작 시(또는 확인했던 값) · 지금 서버 값 · 내 변경. mine 이 undefined 면 "내 변경" 칸을 보이지 않는다 */
export interface CompareRow {
  key: string;
  label: string;
  before: ReactNode;
  latest: ReactNode;
  mine?: ReactNode;
  /** 다른 사용자가 바꾼 항목 (편집 시작 시 ≠ 지금 서버 값) */
  changedOnServer: boolean;
  /** 내가 바꾼 항목 (편집 시작 시 ≠ 내 변경) */
  changedByMe?: boolean;
}

const display = (value: ReactNode) => (value === null || value === undefined || value === '' ? <span className="gh-muted">—</span> : value);

/** 편집 시작 시 · 현재 서버 값 · 내 변경 (+ 선택 칸). 바뀐 칸은 표시한다 */
export function CompareTable({ rows, showMine, picks, onPick }: {
  rows: CompareRow[];
  showMine: boolean;
  picks?: Set<string>;
  onPick?: (key: string, keep: boolean) => void;
}) {
  return (
    <div className="conflict-table-wrap">
      <table className="conflict-table">
        <thead>
          <tr>
            <th scope="col">{t('항목')}</th>
            <th scope="col">{t('편집 시작 시')}</th>
            <th scope="col">{t('현재 서버 값')}</th>
            {showMine && <th scope="col">{t('내 변경')}</th>}
            {onPick && <th scope="col">{t('내 변경 적용')}</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key}>
              <th scope="row">{row.label}</th>
              <td>{display(row.before)}</td>
              <td className={row.changedOnServer ? 'conflict-changed' : undefined}>
                {display(row.latest)}
                {row.changedOnServer && <span className="conflict-mark">{t('다른 사용자 변경')}</span>}
              </td>
              {showMine && <td className={row.changedByMe ? 'conflict-mine' : undefined}>{row.changedByMe ? display(row.mine) : <span className="gh-muted">{t('바꾸지 않음')}</span>}</td>}
              {onPick && (
                <td>
                  {row.changedByMe ? (
                    <label className="conflict-pick">
                      <input type="checkbox" checked={!!picks?.has(row.key)} onChange={(e) => onPick(row.key, e.target.checked)} />
                      <span>{row.changedOnServer ? t('내 값으로') : t('유지')}</span>
                    </label>
                  ) : <span className="gh-muted">{t('최신 값 사용')}</span>}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const timeRow = (before: string | null | undefined, latest: string | null | undefined): CompareRow => ({
  // 충돌 검토는 초·서버 소수 자리까지 (M8-C4c-2 — 같은 분 안의 변경도 구분)
  key: '__modifiedAt', label: t('수정 일시'), before: before ? formatTimestampPrecise(before) : null, latest: latest ? formatTimestampPrecise(latest) : null,
  changedOnServer: (before ?? null) !== (latest ?? null),
});

/** 다시 저장할 수 없는 조회 오류 : 권한 없음, 대상 없음(그 사이 삭제·다른 프로젝트) */
export function isGone(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403 || error.status === 404
    || (error.status === 400 && /IS_NOT_DEFINED$|NOT_FOUND/.test(error.code ?? '')));
}

/** 최신 데이터 조회 오류 분류 : 대상 없음(그 사이 삭제·다른 프로젝트)·권한·그 밖 — 409 와 구분한다 (D03) */
export function loadFailure(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401 || error.status === 403) return t('최신 내용을 볼 권한이 없습니다. 다시 저장할 수 없습니다.');
    if (error.status === 404 || (error.status === 400 && /IS_NOT_DEFINED$|NOT_FOUND/.test(error.code ?? ''))) {
      return t('대상이 삭제되었거나 더 이상 접근할 수 없습니다. 다시 저장할 수 없습니다.');
    }
    return t('최신 내용을 불러오지 못했습니다 : {0}', error.message);
  }
  return t('최신 내용을 불러오지 못했습니다.');
}

// ------------------------------------------------------------------ 수정 폼 복구 (D02~D04)

/** 비교·재적용할 폼 필드. get 은 폼 값(V)에서 그 필드의 비교 값을, show 는 표시를 돌려준다 */
export interface ConflictField<V> {
  key: string;
  label: string;
  get: (values: V) => unknown;
  show?: (values: V) => ReactNode;
}

type Stage<V> =
  | { kind: 'idle' }
  | { kind: 'conflict'; mine: V }
  | { kind: 'loading'; mine: V }
  | { kind: 'loaded'; mine: V; latest: V; latestBase: string | null; picks: Set<string> }
  | { kind: 'failed'; mine: V; message: string; gone: boolean };

const norm = (value: unknown) => (value === undefined || value === null ? '' : typeof value === 'string' ? value.trim() : JSON.stringify(value));

/**
 * 수정 폼의 409 복구. 폼은 저장 요청에 {@link base} 를 싣고 `conflict: 'handled'` 로 보낸 뒤, 오류를 {@link catchConflict} 에 넘긴다 (409 가 아니면 false — 폼이 그대로 다룬다).
 * 409 면 입력을 그대로 둔 채 안내를 보이고(D02), 사용자가 [최신 내용 확인] 을 누르면 load 로 최신 값을 읽어 비교한다(D03). [다시 편집] 은 내가 바꾼 항목 중 고른 것만
 * 내 값으로, 나머지(다른 사용자가 바꾼 항목 포함)는 최신 값으로 새 편집 상태를 만들고 기준값을 최신으로 바꾼다(D04). 저장은 사용자가 다시 누른다
 */
export function useConflictRecovery<V>({ initial, base, fields, load }: {
  /** 편집을 시작할 때의 서버 값 (폼이 처음 연 값) */
  initial: V;
  base: string | null | undefined;
  fields: ConflictField<V>[];
  /** 최신 서버 값과 기준값 */
  load: () => Promise<{ values: V; base: string | null }>;
}) {
  const [start, setStart] = useState<{ values: V; base: string | null }>({ values: initial, base: base ?? null });
  const [stage, setStage] = useState<Stage<V>>({ kind: 'idle' });

  const catchConflict = useCallback((error: unknown, mine: V): boolean => {
    if (!isConflict(error)) return false;
    setStage({ kind: 'conflict', mine });
    return true;
  }, []);

  const changedByMe = (field: ConflictField<V>, mine: V) => norm(field.get(mine)) !== norm(field.get(start.values));

  const fetchLatest = async () => {
    if (stage.kind === 'idle' || stage.kind === 'loading') return;
    const mine = stage.mine;
    setStage({ kind: 'loading', mine });
    try {
      const latest = await load();
      // 기본 선택 : 내가 바꾼 항목은 모두 내 값 유지 (사용자가 끌 수 있다)
      setStage({ kind: 'loaded', mine, latest: latest.values, latestBase: latest.base, picks: new Set(fields.filter((f) => changedByMe(f, mine)).map((f) => f.key)) });
    } catch (error) {
      const message = loadFailure(error);
      setStage({ kind: 'failed', mine, message, gone: isGone(error) });
    }
  };

  /** 고른 내 변경 + 최신 값으로 새 편집 상태를 만든다. apply 가 폼 상태를 바꾼다 */
  const reapply = (apply: (merged: V, pickedKeys: Set<string>) => void) => {
    if (stage.kind !== 'loaded') return;
    apply(stage.latest, stage.picks);
    setStart({ values: stage.latest, base: stage.latestBase });
    setStage({ kind: 'idle' });
  };

  const notice = (apply: (latest: V, pickedKeys: Set<string>) => void): ReactNode => {
    if (stage.kind === 'idle') return null;
    const rows: CompareRow[] = stage.kind === 'loaded' ? [
      ...fields.map((f) => ({
        key: f.key, label: f.label,
        before: (f.show ?? ((v: V) => norm(f.get(v)) as ReactNode))(start.values),
        latest: (f.show ?? ((v: V) => norm(f.get(v)) as ReactNode))(stage.latest),
        mine: (f.show ?? ((v: V) => norm(f.get(v)) as ReactNode))(stage.mine),
        changedOnServer: norm(f.get(start.values)) !== norm(f.get(stage.latest)),
        changedByMe: changedByMe(f, stage.mine),
      })).filter((r) => r.changedOnServer || r.changedByMe),
      timeRow(start.base, stage.latestBase),
    ] : [];
    return (
      <div className="conflict-notice" role="alert">
        <strong>{t('다른 사용자가 이 항목을 변경했습니다.')}</strong>
        <p>{t('작성한 내용은 유지됩니다. 최신 내용을 확인한 뒤 다시 저장해 주세요.')}</p>
        {stage.kind === 'conflict' && (
          <div className="conflict-actions">
            <button type="button" className="btn btn-sm btn-primary" onClick={() => void fetchLatest()}>{t('최신 내용 확인')}</button>
          </div>
        )}
        {stage.kind === 'loading' && <p role="status">{t('최신 내용을 불러오는 중입니다.')}</p>}
        {stage.kind === 'failed' && (
          <>
            <p className="conflict-error">{stage.message}</p>
            {!stage.gone && <div className="conflict-actions"><button type="button" className="btn btn-sm" onClick={() => void fetchLatest()}>{t('다시 불러오기')}</button></div>}
          </>
        )}
        {stage.kind === 'loaded' && (
          <>
            <CompareTable rows={rows} showMine picks={stage.picks}
              onPick={(key, keep) => setStage((s) => {
                if (s.kind !== 'loaded') return s;
                const picks = new Set(s.picks);
                if (keep) picks.add(key); else picks.delete(key);
                return { ...s, picks };
              })} />
            <p className="gh-muted">{t('고른 내 변경만 최신 내용 위에 다시 적용합니다. 나머지 항목은 현재 서버 값을 씁니다. 적용한 뒤 내용을 확인하고 직접 저장해 주세요.')}</p>
            <div className="conflict-actions">
              <button type="button" className="btn btn-sm btn-primary" onClick={() => reapply(apply)}>{t('최신 내용에 내 변경 다시 적용')}</button>
            </div>
          </>
        )}
      </div>
    );
  };

  return {
    /** 저장 요청에 실을 기준값 (다시 적용하면 최신으로 바뀐다) */
    base: start.base,
    /** 편집 시작 값 (다시 적용 뒤에는 그때의 최신 값) */
    start: start.values,
    catchConflict,
    notice,
    /** 대상이 없어졌거나 권한이 없어 다시 저장할 수 없다 */
    blocked: stage.kind === 'failed' && stage.gone,
    /** 409 를 받은 뒤 아직 다시 적용하지 않았다 */
    pending: stage.kind !== 'idle',
  };
}

// ------------------------------------------------------------------ 삭제·승인·매핑 검토 대화상자 (6~8장)

/** 검토 결과 : 비교 표, 다시 실행할 수 있는지(없으면 사유), 다시 실행할 때 쓸 값 */
export interface Review<P> {
  rows: CompareRow[];
  /** 다시 실행할 수 없는 사유 (대상 없음·이미 그 상태 등). 없으면 다시 실행 가능 */
  blocked?: string;
  /** 안내 (예 : 하위 구성이 바뀌었습니다) */
  note?: string;
  payload: P;
}

export interface ReviewOptions<P> {
  title: string;
  /** 409 직후의 안내 */
  intro: string;
  /** 최신 데이터 조회 → 비교 */
  load: () => Promise<Review<P>>;
  retryLabel: string;
  /** 최신 기준값으로 다시 실행 (409 면 검토를 다시 읽는다 — 자동 재실행 없음) */
  retry: (payload: P) => Promise<void>;
  /** 다시 실행에 성공한 뒤 */
  onDone?: () => void;
  /** 대화상자를 닫을 때 (성공 여부와 관계없이 — 목록 다시 읽기 등) */
  onClose?: () => void;
}

/** 409 를 받은 삭제·승인·매핑 해제 : 검토 대화상자를 열어 최신 내용을 다시 읽고 비교한 뒤, 사용자가 명시적으로 다시 실행하게 한다 */
export function openConflictReview<P>(dialog: DialogApi, options: ReviewOptions<P>) {
  dialog.open({
    title: options.title,
    className: 'dialog-wide',
    onClose: options.onClose,
    content: (controls) => <ConflictReview options={options} controls={controls} />,
  });
}

function ConflictReview<P>({ options, controls }: { options: ReviewOptions<P>; controls: DialogControls }) {
  const [state, setState] = useState<{ kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'loaded'; review: Review<P>; again: boolean }>({ kind: 'loading' });
  const [busy, run] = useSubmitting();

  const load = useCallback(async (again: boolean) => {
    setState({ kind: 'loading' });
    try {
      setState({ kind: 'loaded', review: await options.load(), again });
    } catch (error) {
      setState({ kind: 'failed', message: loadFailure(error) });
    }
  }, [options]);

  useEffect(() => { void load(false); }, [load]);

  const retry = (payload: P) => void run(async () => {
    try {
      await options.retry(payload);
    } catch (error) {
      if (isConflict(error)) {
        await load(true); // 또 바뀌었다 : 같은 절차를 반복한다
        return;
      }
      return; // 그 밖의 오류는 공통 알림이 보여 준다 — 대화상자는 그대로
    }
    controls.close();
    options.onDone?.();
  });

  return (
    <div className="conflict-review">
      <div className="conflict-notice" role="alert">
        <strong>{options.intro}</strong>
        {state.kind === 'loaded' && state.again && <p>{t('확인하는 사이 다시 바뀌었습니다. 최신 내용을 다시 확인해 주세요.')}</p>}
        {state.kind === 'loaded' && state.review.note && <p>{state.review.note}</p>}
      </div>
      {state.kind === 'loading' && <p role="status">{t('최신 내용을 불러오는 중입니다.')}</p>}
      {state.kind === 'failed' && <p className="conflict-error" role="alert">{state.message}</p>}
      {state.kind === 'loaded' && <CompareTable rows={state.review.rows} showMine={state.review.rows.some((r) => r.mine !== undefined)} />}
      {state.kind === 'loaded' && state.review.blocked && <p className="conflict-error">{state.review.blocked}</p>}
      <div className="popup-button-container">
        {state.kind === 'loaded' && !state.review.blocked && (
          <button type="button" className="confirm" disabled={busy} onClick={() => retry(state.review.payload)}>{busy ? t('처리 중…') : options.retryLabel}</button>
        )}
        {state.kind === 'failed' && <button type="button" className="confirm" onClick={() => void load(false)}>{t('다시 불러오기')}</button>}
        <button type="button" className="close" disabled={busy} onClick={controls.close}>{t('닫기')}</button>
      </div>
    </div>
  );
}

export { timeRow as modifiedAtRow };

// ------------------------------------------------------------------ 매핑 변경 충돌 안내 (8장)

/** 매핑 변경 409 : 기존(내가 본) 매핑 · 지금 매핑 · 내 선택을 보여 준다. 내 선택은 그대로 두고, 사용자가 최신 상태를 보고 다시 저장한다 */
export interface MappingConflict {
  label: string;
  before: ReactNode;
  latest: ReactNode;
  mine: ReactNode;
  /** 지금 매핑이 없어졌다(다른 사용자가 해제) */
  gone?: boolean;
}

export function MappingConflictNotice({ conflict, onDismiss }: { conflict: MappingConflict; onDismiss?: () => void }) {
  const rows: CompareRow[] = [{ key: 'mapping', label: conflict.label, before: conflict.before, latest: conflict.gone ? t('해제됨') : conflict.latest, mine: conflict.mine, changedOnServer: true, changedByMe: true }];
  return (
    <div className="conflict-notice" role="alert">
      <strong>{t('다른 사용자가 이 매핑을 변경했습니다.')}</strong>
      <p>{t('고른 내용은 유지됩니다. 최신 매핑을 확인한 뒤 다시 저장해 주세요.')}</p>
      <CompareTable rows={rows} showMine />
      {conflict.gone && <p>{t('매핑이 해제되어 다시 등록해야 합니다. 후보를 다시 확인해 주세요.')}</p>}
      {onDismiss && <div className="conflict-actions"><button type="button" className="btn btn-sm" onClick={onDismiss}>{t('안내 닫기')}</button></div>}
    </div>
  );
}
