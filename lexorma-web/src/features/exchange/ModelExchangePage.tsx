import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { DownloadIcon } from '@primer/octicons-react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import '../../styles/source.css';

/**
 * 모델 Export·Import·복사 (DEC-22). Export 는 지금 프로젝트의 모델을 버전 있는 JSON 파일로 내려받는다 (승인 상태·원본 DB 정보·비밀 값 없음).
 * 형식 2 (7차) : 모델 VIEW 와 내가 저장한 ERD 배치를 함께 넣는다. 형식 1 파일도 그대로 가져온다.
 * 가져오기는 파일 또는 지금 프로젝트의 모델(= 다른 프로젝트로 복사)을, 관리하는 프로젝트에 계획 → 확정으로 넣는다 : 새 객체만 미승인으로 만들고 같은 이름은 덮어쓰지 않는다.
 * 확정하면 서버가 대상 프로젝트를 잠근 뒤 같은 계획인지 다시 확인하고, 직전 모델 버전을 남긴 뒤 한 번에 만든다.
 */
interface Item { id: number; name: string | null; state: Record<string, unknown> }
interface ExchangeDocument { format: string; formatVersion: number; exportedAt: string | null; dbType: string | null; counts: Record<string, number> | null; model: Record<string, Item[]> }
interface TargetProject { id: number; name: string; dbType: string | null; current: boolean }
type Status = 'NEW' | 'CONFLICT' | 'EXCLUDED';
type Reason = 'NAME_EXISTS' | 'NOT_SELECTED' | 'INVALID' | 'UNSAFE_TEXT' | 'RELATED_NOT_IMPORTED' | 'LIMIT_EXCEEDED';
interface PlanItem { kind: string; key: string; name: string | null; status: Status; reason: Reason | null; counts: Record<string, number> | null }
interface Plan { targetProjectId: number; targetProjectName: string; planToken: string; requestId: string; applicable: boolean; items: PlanItem[]; counts: Record<string, number>; warnings: string[] }
interface Result { modelVersionName: string; created: Record<string, number> }
interface Selection { tables: string[]; logicalModels: string[]; views: string[] }

const KIND: Record<string, string> = {
  SCHEMA: t('스키마'), TABLE: 'TABLE', VIEW: 'VIEW', RELATION: t('관계'), LOGICAL_MODEL: t('논리 모델'), TABLE_MAPPING: t('논리·물리 매핑'), ERD_LAYOUT: t('ERD 배치'),
};
const STATUS: Record<Status, string> = { NEW: t('새로 만듦'), CONFLICT: t('이름 충돌 — 가져오지 않음'), EXCLUDED: t('제외') };
const REASON: Record<Reason, string> = {
  NAME_EXISTS: t('같은 이름이 이미 있음 (덮어쓰지 않음)'),
  NOT_SELECTED: t('고르지 않음'),
  INVALID: t('파일의 값이 올바르지 않음'),
  UNSAFE_TEXT: t('안전하지 않은 SQL 식'),
  RELATED_NOT_IMPORTED: t('연결된 객체를 가져오지 않음'),
  LIMIT_EXCEEDED: t('배치 저장 한도(5,000개)를 넘음'),
};
const COUNT_LABEL: Record<string, string> = {
  SCHEMA: t('스키마'), PHYSICAL_TABLE: 'TABLE', PHYSICAL_COLUMN: t('컬럼'), PHYSICAL_RELATION: t('관계'), PHYSICAL_INDEX: t('인덱스'), PHYSICAL_CONSTRAINT: t('제약조건'),
  LOGICAL_MODEL: t('논리 모델'), LOGICAL_ENTITY: t('엔터티'), LOGICAL_ATTRIBUTE: t('속성'), LOGICAL_RELATIONSHIP: t('논리 관계'), TABLE_MAPPING: t('엔터티 매핑'), COLUMN_MAPPING: t('속성 매핑'),
  PHYSICAL_VIEW: 'VIEW', PHYSICAL_VIEW_REFERENCE: t('VIEW 참조'), ERD_LAYOUT_NODE: t('ERD 배치 좌표'),
  SUBTYPE_GROUP: t('서브타입 그룹'), LOGICAL_SUBTYPE: t('서브타입'), SUBTYPE_KEY_MAPPING: t('상속 PK 매핑'),
  SUBTYPE_CONVERSION: t('상속 변환 기록'), SUBTYPE_ATTR_MAPPING: t('상속 일반 속성 매핑'),
  // 결과·계획에 나오지만 이름이 없어 코드 그대로 보이던 종류 (M-7 5차 브라우저 확인에서 발견)
  LOGICAL_RELATIONSHIP_ATTRIBUTE: t('관계 속성 짝'), LOGICAL_SUBJECT_AREA: t('주제 영역'), SUBJECT_AREA_ENTITY: t('주제 영역 분류'),
};
/** 파일 크기 한도 (서버와 같다) */
const MAX_BYTES = 30 * 1024 * 1024;

function warningText(code: string) {
  if (code === 'DB_TYPE_DIFFERS') return t('파일의 DBMS 가 대상 프로젝트와 다릅니다. 데이터 타입을 확인하세요.');
  if (code.startsWith('TERM_NOT_FOUND:')) return t('표준 용어 연결 {0}개는 대상 프로젝트에 같은 이름의 용어가 없어 연결 없이 가져옵니다.', code.split(':')[1]);
  if (code.startsWith('LAYOUT_IGNORED:')) return t('파일에 없는 객체를 가리키는 ERD 배치 좌표 {0}개는 무시합니다.', code.split(':')[1]);
  return code;
}

/** 계획 한 줄의 함께 오는 것 (서버는 수만 준다) */
function itemText(item: PlanItem) {
  const c = item.counts;
  if (!c) return '';
  if (item.kind === 'TABLE') {
    const text = t('컬럼 {0} · 인덱스 {1} · 제약조건 {2}', c.columns ?? 0, c.indexes ?? 0, c.constraints ?? 0);
    return c.unsafe ? `${text} · ${t('안전하지 않은 식 {0}개 제외', c.unsafe)}` : text;
  }
  if (item.kind === 'LOGICAL_MODEL') return t('엔터티 {0} · 관계 {1}', c.entities ?? 0, c.relationships ?? 0);
  if (item.kind === 'VIEW') {
    const text = t('출력 컬럼 {0} · 참조 {1}', c.columns ?? 0, c.references ?? 0);
    const extra = [c.linked ? t('기존 객체에 연결 {0}', c.linked) : '', c.unresolved ? t('모델 밖 참조 {0}', c.unresolved) : ''].filter(Boolean);
    return extra.length ? `${text} · ${extra.join(' · ')}` : text;
  }
  if (item.kind === 'ERD_LAYOUT') return c.ignored ? t('좌표 {0} · 가져오지 않는 객체 {1}', c.nodes ?? 0, c.ignored) : t('좌표 {0}', c.nodes ?? 0);
  return '';
}

function countText(counts: Record<string, number>) {
  return Object.entries(counts).filter(([, n]) => n > 0).map(([k, n]) => `${COUNT_LABEL[k] ?? k} ${n}`).join(' · ') || '-';
}

function download(text: string, name: string) {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function ModelExchangePage() {
  const { canView, user } = useAuth();
  const dialog = useDialog();
  const canImport = canView('model-exchange-import');
  const targets = useQuery({ queryKey: ['model-exchange-targets'], queryFn: () => api<TargetProject[]>('/api/modelExchange/targets', { silent: true }), enabled: canImport });
  const [exporting, setExporting] = useState(false);
  const [source, setSource] = useState<'file' | 'current'>('file');
  const [document, setDocument] = useState<ExchangeDocument | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [target, setTarget] = useState<number | null>(null);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [busy, setBusy] = useState(false);
  const targetId = target ?? targets.data?.find((p) => p.current)?.id ?? null;

  const exportModel = async () => {
    setExporting(true);
    try {
      const doc = await api<ExchangeDocument>('/api/modelExchange/export');
      const stamp = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '');
      download(JSON.stringify(doc, null, 2), `lexorma-model_${(user?.projectName ?? 'project').replace(/[\\/:*?"<>|\s]+/g, '-')}_${stamp}.json`);
    } catch { /* 공통 알림 */ } finally {
      setExporting(false);
    }
  };

  const reset = () => {
    setPlan(null);
    setSelection(null);
  };

  const readFile = (file: File | undefined) => {
    reset();
    setDocument(null);
    setFileError(null);
    setFileName(file?.name ?? null);
    if (!file) return;
    if (file.size > MAX_BYTES) {
      setFileError(t('파일이 너무 큽니다 (최대 30MB).'));
      return;
    }
    file.text().then((text) => {
      try {
        const parsed = JSON.parse(text) as ExchangeDocument;
        if (parsed?.format !== 'lexorma-model') throw new Error();
        setDocument(parsed);
      } catch {
        setFileError(t('Lexorma 모델 파일(JSON)이 아닙니다.'));
      }
    }, () => setFileError(t('파일을 읽지 못했습니다.')));
  };

  const loadDocument = async (): Promise<ExchangeDocument | null> => {
    if (source === 'file') return document;
    return api<ExchangeDocument>('/api/modelExchange/export');
  };

  const makePlan = async (chosen: Selection | null) => {
    setBusy(true);
    try {
      const doc = await loadDocument();
      if (!doc || targetId == null) return;
      if (source === 'current') setDocument(doc);
      setPlan(await api<Plan>('/api/modelExchange/plan', { method: 'POST', body: { targetProjectId: targetId, document: doc, selection: chosen } }));
      setSelection(chosen);
    } catch { /* 공통 알림 */ } finally {
      setBusy(false);
    }
  };

  /** 고를 수 있는 것 : TABLE·VIEW·논리 모델 중 새로 만들 수 있었던 것 (고르지 않아 빠진 것 포함). ERD 배치는 고른 TABLE·논리 모델을 따라온다 */
  const choosable = useMemo(() => (plan?.items ?? []).filter((i) => (i.kind === 'TABLE' || i.kind === 'VIEW' || i.kind === 'LOGICAL_MODEL') && (i.status === 'NEW' || i.reason === 'NOT_SELECTED')), [plan]);
  const isChosen = (item: PlanItem) => item.status === 'NEW';
  const toggle = (item: PlanItem) => {
    const current: Selection = selection ?? {
      tables: choosable.filter((i) => i.kind === 'TABLE').map((i) => i.key),
      logicalModels: choosable.filter((i) => i.kind === 'LOGICAL_MODEL').map((i) => i.key),
      views: choosable.filter((i) => i.kind === 'VIEW').map((i) => i.key),
    };
    const field = item.kind === 'TABLE' ? 'tables' : item.kind === 'VIEW' ? 'views' : 'logicalModels';
    const list = current[field];
    const next = { ...current, [field]: list.includes(item.key) ? list.filter((k) => k !== item.key) : [...list, item.key] };
    void makePlan(next);
  };

  const apply = () => {
    if (!plan || !document) return;
    const project = targets.data?.find((p) => p.id === plan.targetProjectId);
    dialog.confirm(t('{0} 프로젝트에 {1} 을(를) 미승인으로 만드시겠습니까? 같은 이름의 객체는 바꾸지 않고, 만들기 직전 모델 버전이 남습니다.', project?.name ?? plan.targetProjectName, countText(plan.counts)), async () => {
      const result = await api<Result>('/api/modelExchange/apply', {
        method: 'POST',
        body: { targetProjectId: plan.targetProjectId, document, selection, planToken: plan.planToken, requestId: plan.requestId },
      });
      reset();
      dialog.alert(t('가져왔습니다 : {0}. 직전 상태는 모델 버전 {1} 에 있습니다.', countText(result.created), result.modelVersionName));
    });
  };

  const ready = source === 'current' || document != null;
  return (
    <>
      <PageHeader category={t('데이터 품질 관리')} title={t('모델 Export·Import')}
        description={t('모델(스키마, TABLE·컬럼·키·인덱스·제약조건·관계, VIEW, 논리 모델, 논리·물리 매핑, 표준 용어 참조, ERD 배치)을 JSON 파일로 내보내고, 파일이나 지금 프로젝트의 모델을 관리하는 다른 프로젝트로 가져옵니다.')} />

      <section className="source-card" aria-label={t('모델 내보내기')}>
        <h3>{t('모델 내보내기')}</h3>
        <p className="gh-muted">{t('지금 프로젝트의 모델 전체와 내가 저장한 ERD 배치를 버전 있는 JSON 파일로 내려받습니다. 모델 VIEW 는 정의 SQL 과 함께 넣습니다. 승인 상태, 원본 DB 연결·원본 카탈로그·원본에서 수집한 VIEW 정의, 비밀번호·키·토큰, 사용자 정보는 넣지 않습니다.')}</p>
        <div className="source-actions">
          <button type="button" className="btn" disabled={exporting} onClick={() => void exportModel()}><DownloadIcon />{exporting ? t('만드는 중…') : t('JSON 내려받기')}</button>
        </div>
      </section>

      {canImport && (
        <section className="source-card" aria-label={t('모델 가져오기')}>
          <h3>{t('모델 가져오기 · 다른 프로젝트로 복사')}</h3>
          <p className="gh-muted">{t('새 객체만 미승인으로 만들고 ID 는 새로 받습니다. 같은 이름의 TABLE·VIEW·논리 모델은 덮어쓰지 않습니다. VIEW 정의 SQL 은 저장만 하고 실행하지 않습니다. ERD 배치는 새로 만든 객체의 좌표만 내 배치에 더하고 기존 좌표는 바꾸지 않습니다. 관리하는 프로젝트만 대상으로 고를 수 있습니다.')}</p>
          <div className="source-form-row">
            <label className="source-sub"><input type="radio" name="exchange-source" checked={source === 'file'} onChange={() => { setSource('file'); reset(); }} /> {t('파일에서')}</label>
            <label className="source-sub"><input type="radio" name="exchange-source" checked={source === 'current'} onChange={() => { setSource('current'); setDocument(null); reset(); }} /> {t('지금 프로젝트의 모델 (복사)')}</label>
          </div>
          {source === 'file' && (
            <div className="source-form-row">
              <input type="file" accept="application/json,.json" aria-label={t('모델 파일')} onChange={(e) => readFile(e.target.files?.[0])} />
              {fileName && document && <span className="gh-muted">{t('{0} · 형식 {1} · {2}', fileName, document.formatVersion, document.dbType ?? '-')}</span>}
              {fileError && <span className="source-warn" role="alert">{fileError}</span>}
            </div>
          )}
          <div className="source-form-row">
            <label htmlFor="exchange-target">{t('대상 프로젝트')}</label>
            <select id="exchange-target" className="form-control" value={targetId ?? ''} onChange={(e) => { setTarget(Number(e.target.value)); reset(); }}>
              {(targets.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}{p.current ? ` (${t('지금 프로젝트')})` : ''}</option>)}
            </select>
            <button type="button" className="btn" disabled={busy || !ready || targetId == null} onClick={() => void makePlan(null)}>{t('계획 미리보기')}</button>
          </div>
          {targets.data && targets.data.length === 0 && <p className="source-warn">{t('관리하는 프로젝트가 없어 가져올 수 없습니다.')}</p>}

          {plan && (
            <>
              <p role="status" className={plan.applicable ? 'gh-muted' : 'source-warn'}>
                {plan.applicable ? t('계획 : {0} 에 {1}', plan.targetProjectName, countText(plan.counts)) : t('가져올 새 객체가 없습니다.')}
              </p>
              {plan.warnings.map((w) => <p key={w} className="source-warn">{warningText(w)}</p>)}
              <div className="source-table-wrap">
                <table className="source-table">
                  <thead><tr><th /><th>{t('종류')}</th><th>{t('대상')}</th><th>{t('결과')}</th><th>{t('내용')}</th></tr></thead>
                  <tbody>
                    {plan.items.map((item) => {
                      const selectable = choosable.includes(item);
                      return (
                        <tr key={`${item.kind}:${item.key}`}>
                          <td>{selectable && <input type="checkbox" aria-label={item.key} disabled={busy} checked={isChosen(item)} onChange={() => toggle(item)} />}</td>
                          <td><Label>{KIND[item.kind] ?? item.kind}</Label></td>
                          <td className="gh-mono source-break">{item.key}</td>
                          <td>
                            <Label variant={item.status === 'NEW' ? 'done' : item.status === 'CONFLICT' ? 'attention' : undefined}>{STATUS[item.status]}</Label>
                            {item.reason && item.status !== 'CONFLICT' && <span className="gh-muted source-sub"> {REASON[item.reason]}</span>}
                          </td>
                          <td className="gh-muted">{itemText(item)}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              <div className="source-actions">
                <button type="button" className="btn btn-primary" disabled={busy || !plan.applicable} onClick={apply}>{t('가져오기 확정')}</button>
              </div>
            </>
          )}
        </section>
      )}
    </>
  );
}
