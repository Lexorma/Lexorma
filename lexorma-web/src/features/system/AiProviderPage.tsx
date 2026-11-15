import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, ListChecks, MessageSquare, Plug, Plus, Star, Stethoscope } from 'lucide-react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import '../../styles/source.css';

/**
 * AI Provider 관리 (AI-3) : 시스템 관리자(ADMIN) 전용. Lexorma 가 호출할 AI 서버(지금은 Ollama)를 등록하고 연결 테스트·모델 목록·테스트 프롬프트로 확인한다.
 * 등록은 저장만 하고 연결하지 않는다. 연결은 외부 연결 보안(SEC-1)의 AI HTTP 허용 규칙이 열어야 한다. Provider 가 없어도 다른 기능은 그대로 쓴다.
 */
type ProviderType = 'OLLAMA';
type Failure = 'BLOCKED' | 'CONNECTION_REFUSED' | 'TIMEOUT' | 'CONNECTION_FAILED' | 'MODEL_NOT_FOUND' | 'MODEL_REQUIRED' | 'SERVER_ERROR' | 'INVALID_RESPONSE' | 'UNSUPPORTED';

interface Provider {
  id: number; name: string; type: ProviderType; baseUrl: string; defaultModel: string | null; enabled: boolean; description: string | null;
  createdBy: string | null; createdAt: string | null; updatedBy: string | null; updatedAt: string | null;
}
interface ProviderRequest { name: string; type: ProviderType; baseUrl: string; defaultModel: string | null; enabled: boolean; description: string | null }
interface TypeInfo { type: ProviderType; defaultBaseUrl: string; modelListSupported: boolean }
type DefaultStatus = 'READY' | 'NOT_CONFIGURED' | 'DISABLED' | 'MODEL_MISSING' | 'UNSUPPORTED';
interface DefaultProvider { status: DefaultStatus; provider: Provider | null; updatedBy: string | null; updatedAt: string | null }
interface CallError { failure: Failure; blockReason: string | null; status: number | null; message: string }
interface Model { name: string; size: number | null; family: string | null; parameterSize: string | null; quantization: string | null; modifiedAt: string | null }
interface ConnectionTest { success: boolean; elapsedMillis: number; modelCount: number | null; defaultModelPresent: boolean | null; error: CallError | null }
interface Models { success: boolean; models: Model[]; error: CallError | null }
interface PromptResult {
  success: boolean; model: string | null; content: string | null; elapsedMillis: number; promptTokens: number | null; completionTokens: number | null; error: CallError | null;
}

const KEY = 'ai-provider';
const TYPE_LABEL: Record<ProviderType, string> = { OLLAMA: 'Ollama' };
const FAILURE: Record<Failure, string> = {
  BLOCKED: t('외부 연결 보안 정책으로 차단'),
  CONNECTION_REFUSED: t('연결 거부'),
  TIMEOUT: t('시간 초과'),
  CONNECTION_FAILED: t('연결 실패'),
  MODEL_NOT_FOUND: t('모델 없음'),
  MODEL_REQUIRED: t('모델 미지정'),
  SERVER_ERROR: t('AI 서버 오류'),
  INVALID_RESPONSE: t('응답 처리 실패'),
  UNSUPPORTED: t('지원하지 않는 기능'),
};
const PROMPT_MAX = 4000;

function sizeText(bytes: number | null) {
  if (bytes == null) return '-';
  const gb = bytes / 1024 ** 3;
  return gb >= 1 ? `${gb.toFixed(1)} GB` : `${(bytes / 1024 ** 2).toFixed(0)} MB`;
}

export function AiProviderPage() {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const reload = () => queryClient.invalidateQueries({ queryKey: [KEY] });
  // 활성화·비활성화 중인 Provider (같은 버튼을 다시 눌러 요청이 겹치지 않게)
  const [toggling, setToggling] = useState<number | null>(null);
  const providers = useQuery({ queryKey: [KEY, 'list'], queryFn: () => api<Provider[]>('/api/ai/providers') });
  const types = useQuery({ queryKey: [KEY, 'types'], queryFn: () => api<TypeInfo[]>('/api/ai/providers/types') });
  // 시스템 기본 Provider : 업무 AI 기능이 쓸 Provider 하나 (관리자가 명시적으로 정한다, 없어도 된다)
  const systemDefault = useQuery({ queryKey: [KEY, 'default'], queryFn: () => api<DefaultProvider>('/api/ai/default-provider') });
  const [defaultBusy, setDefaultBusy] = useState(false);
  const defaultId = systemDefault.data?.provider?.id ?? null;

  const openForm = (provider?: Provider) => {
    dialog.open({
      title: provider ? t('AI Provider 수정 · {0}', provider.name) : t('AI Provider 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <ProviderForm provider={provider} types={types.data ?? []} onCancel={close} onSaved={() => { close(); void reload(); }} />,
    });
  };
  const openTools = (provider: Provider) => {
    dialog.open({
      title: t('AI Provider 점검 · {0}', provider.name),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <ProviderTools provider={provider} onChanged={() => void reload()} onClose={close} />,
    });
  };
  const toggle = async (p: Provider) => {
    setToggling(p.id);
    try {
      await api(`/api/ai/providers/${enc(p.id)}`, {
        method: 'PUT', body: { name: p.name, type: p.type, baseUrl: p.baseUrl, defaultModel: p.defaultModel, enabled: !p.enabled, description: p.description },
      });
      await reload();
    } finally {
      setToggling(null);
    }
  };
  const changeDefault = async (p: Provider | null) => {
    setDefaultBusy(true);
    try {
      await api('/api/ai/default-provider', p ? { method: 'PUT', body: { providerId: p.id } } : { method: 'DELETE' });
      await reload();
    } finally {
      setDefaultBusy(false);
    }
  };
  // 기본으로 지정할 수 없는 이유 (서버도 같은 조건으로 거부한다)
  const defaultBlocker = (p: Provider) => (!p.enabled ? t('비활성화된 Provider 는 기본으로 지정할 수 없습니다.')
    : !p.defaultModel ? t('기본 모델이 없는 Provider 는 기본으로 지정할 수 없습니다. Provider 의 기본 모델을 먼저 정하세요.') : null);
  const remove = (p: Provider) => {
    if (p.id === defaultId) {
      dialog.alert(t('시스템 기본 AI Provider 로 지정된 Provider 라 삭제할 수 없습니다. 기본 지정을 해제하거나 다른 Provider 를 기본으로 지정한 뒤 삭제하세요.'));
      return;
    }
    dialog.confirm(t('{0} Provider 를 삭제하시겠습니까? 다음 AI 호출부터 이 Provider 를 쓸 수 없습니다.', p.name), async () => {
      await api(`/api/ai/providers/${enc(p.id)}`, { method: 'DELETE' });
      await reload();
    });
  };

  const list = providers.data ?? [];
  return (
    <>
      <PageHeader category={t('시스템 관리')} title={t('AI Provider 관리')}
        description={t('Lexorma 가 호출할 AI 서버(Ollama)를 등록하고 연결을 확인합니다. AI 를 쓰지 않으면 등록하지 않아도 됩니다.')} />

      <div className="gh-flash source-flash" role="note">
        <span>
          {t('AI 서버 연결은 외부 연결 보안의 AI HTTP 허용 규칙이 있어야 합니다 (기본 차단).')}{' '}
          <Link to="/connectionSecurity">{t('외부 연결 보안')}</Link>{' '}
          {t('Lexorma 를 Docker 로 실행하면 127.0.0.1 은 Lexorma 컨테이너 자신입니다. 호스트나 다른 컨테이너의 Ollama 주소를 등록하세요.')}
        </span>
      </div>

      {systemDefault.data && <DefaultNotice value={systemDefault.data} hasProviders={list.length > 0} />}

      <section className="source-card" aria-label={t('AI Provider')}>
        <h3 className="source-title-icon"><Bot size={16} aria-hidden="true" /> {t('AI Provider')}</h3>
        <div className="source-toolbar">
          <span className="source-grow" />
          <button type="button" className="btn btn-primary" onClick={() => openForm()}><Plus size={14} aria-hidden="true" /> {t('Provider 등록')}</button>
        </div>
        {providers.isError ? <p role="alert">{providers.error.message}</p>
          : providers.isPending ? <p role="status">{t('불러오는 중입니다.')}</p>
          : list.length === 0 ? <p className="gh-muted" role="status">{t('등록된 AI Provider 가 없습니다. AI 기능을 쓰지 않으면 그대로 두어도 됩니다.')}</p>
          : (
            <div className="source-table-wrap">
              <table className="source-table">
                <thead>
                  <tr><th>{t('이름')}</th><th>{t('유형')}</th><th>{t('AI 서버 주소')}</th><th>{t('기본 모델')}</th><th>{t('상태')}</th><th>{t('시스템 기본')}</th><th>{t('수정')}</th><th>{t('작업')}</th></tr>
                </thead>
                <tbody>
                  {list.map((p) => (
                    <tr key={p.id}>
                      <td className="source-break">
                        {p.name}
                        {p.description && <div className="gh-muted source-sub">{p.description}</div>}
                      </td>
                      <td>{TYPE_LABEL[p.type] ?? p.type}</td>
                      <td className="gh-mono source-break">{p.baseUrl}</td>
                      <td className="gh-mono source-break">{p.defaultModel ?? <span className="gh-muted">-</span>}</td>
                      <td><Label variant={p.enabled ? 'success' : ''} dot>{p.enabled ? t('활성') : t('비활성')}</Label></td>
                      <td>
                        {p.id === defaultId
                          ? <Label variant={systemDefault.data?.status === 'READY' ? 'accent' : 'attention'}><Star size={12} aria-hidden="true" /> {t('기본')}</Label>
                          : <span className="gh-muted">-</span>}
                      </td>
                      <td className="source-sub">
                        <div>{p.updatedBy ?? p.createdBy ?? '-'}</div>
                        <div className="gh-muted">{formatDateTime(p.updatedAt ?? p.createdAt ?? '')}</div>
                      </td>
                      <td>
                        <div className="source-actions">
                          <button type="button" className="btn btn-sm" onClick={() => openTools(p)}><Stethoscope size={14} aria-hidden="true" /> {t('점검')}</button>
                          {p.id === defaultId
                            ? <button type="button" className="btn btn-sm" disabled={defaultBusy} onClick={() => void changeDefault(null)}>{t('기본 해제')}</button>
                            : <button type="button" className="btn btn-sm" disabled={defaultBusy || defaultBlocker(p) !== null} title={defaultBlocker(p) ?? undefined}
                              onClick={() => void changeDefault(p)}>{defaultId == null ? t('기본으로 지정') : t('기본으로 변경')}</button>}
                          <button type="button" className="btn btn-sm" disabled={toggling === p.id} onClick={() => void toggle(p)}>{p.enabled ? t('비활성화') : t('활성화')}</button>
                          <button type="button" className="btn btn-sm" onClick={() => openForm(p)}>{t('수정')}</button>
                          <button type="button" className="btn btn-sm btn-danger" onClick={() => remove(p)}>{t('삭제')}</button>
                        </div>
                      </td>
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

/** 시스템 기본 Provider 안내 : 쓸 수 없는 상태면 그 이유. 자동으로 다른 Provider 로 바꾸지 않는다 */
function DefaultNotice({ value, hasProviders }: { value: DefaultProvider; hasProviders: boolean }) {
  const p = value.provider;
  if (value.status === 'READY' && p) {
    return (
      <p className="form-help" role="status">
        <Star size={12} aria-hidden="true" /> {t('시스템 기본 AI Provider : {0} · 모델 {1}', p.name, p.defaultModel ?? '-')}
      </p>
    );
  }
  if (value.status === 'NOT_CONFIGURED') {
    return hasProviders
      ? <div className="gh-flash source-flash" role="note">{t('시스템 기본 AI Provider 가 지정되지 않았습니다. 업무 AI 기능을 쓰려면 활성이고 기본 모델이 있는 Provider 하나를 기본으로 지정하세요.')}</div>
      : null;
  }
  const message = value.status === 'DISABLED'
    ? t('현재 기본 AI Provider({0})가 비활성화되어 있습니다. AI 기능을 사용하려면 Provider 를 활성화하거나 다른 Provider 를 기본으로 지정하세요.', p?.name ?? '-')
    : value.status === 'MODEL_MISSING'
      ? t('현재 기본 AI Provider({0})에 기본 모델이 없습니다. AI 기능을 사용하려면 기본 모델을 정하거나 다른 Provider 를 기본으로 지정하세요.', p?.name ?? '-')
      : t('현재 기본 AI Provider({0})의 유형을 지원하지 않습니다. 다른 Provider 를 기본으로 지정하세요.', p?.name ?? '-');
  return <div role="alert" className="source-error">{message}</div>;
}

/** 실패 안내 : 분류 + 서버 문구. SEC-1 차단이면 허용 규칙 화면으로 안내한다 */
function CallFailure({ error }: { error: CallError }) {
  return (
    <div role="alert" className="source-error">
      <strong>{FAILURE[error.failure] ?? error.failure}</strong>{error.status != null && <> (HTTP {error.status})</>} — {error.message}
      {error.failure === 'BLOCKED' && <> <Link to="/connectionSecurity">{t('외부 연결 보안에서 허용 규칙 확인')}</Link></>}
    </div>
  );
}

/** 연결 테스트 · 모델 목록 · 테스트 프롬프트 : 누를 때만 AI 서버에 연결한다 */
function ProviderTools({ provider, onChanged, onClose }: { provider: Provider; onChanged: () => void; onClose: () => void }) {
  const [defaultModel, setDefaultModel] = useState(provider.defaultModel);
  const [testing, setTesting] = useState(false);
  const [test, setTest] = useState<ConnectionTest | null>(null);
  const [loadingModels, setLoadingModels] = useState(false);
  const [models, setModels] = useState<Models | null>(null);
  const [prompt, setPrompt] = useState('');
  const [model, setModel] = useState('');
  const [sending, setSending] = useState(false);
  const [answer, setAnswer] = useState<PromptResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async <T,>(set: (v: boolean) => void, action: () => Promise<T>, apply: (value: T) => void) => {
    set(true);
    setError(null);
    try {
      apply(await action());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      set(false);
    }
  };
  const connectionTest = () => run(setTesting, () => api<ConnectionTest>(`/api/ai/providers/${enc(provider.id)}/test`, { method: 'POST', silent: true }), setTest);
  const loadModels = () => run(setLoadingModels, () => api<Models>(`/api/ai/providers/${enc(provider.id)}/models`, { silent: true }), setModels);
  const send = (e: FormEvent) => {
    e.preventDefault();
    void run(setSending, () => api<PromptResult>(`/api/ai/providers/${enc(provider.id)}/prompt`, {
      method: 'POST', body: { prompt, model: model.trim() || null }, silent: true,
    }), setAnswer);
  };
  const makeDefault = (name: string) => run(setLoadingModels, () => api<Provider>(`/api/ai/providers/${enc(provider.id)}`, {
    method: 'PUT', silent: true,
    body: { name: provider.name, type: provider.type, baseUrl: provider.baseUrl, defaultModel: name, enabled: provider.enabled, description: provider.description },
  }), (saved) => {
    setDefaultModel(saved.defaultModel);
    onChanged();
  });

  return (
    <div className="source-form">
      <p className="form-help">
        <span className="gh-mono">{provider.baseUrl}</span> · {t('기본 모델')} <span className="gh-mono">{defaultModel ?? '-'}</span>
        {!provider.enabled && <> · <Label>{t('비활성')}</Label> {t('비활성화된 Provider 는 연결 테스트와 모델 목록만 확인할 수 있습니다.')}</>}
      </p>
      {error && <p role="alert" className="source-error">{error}</p>}

      <section className="source-card" aria-label={t('연결 테스트')}>
        <h4 className="source-title-icon"><Plug size={14} aria-hidden="true" /> {t('연결 테스트')}</h4>
        <div className="source-toolbar">
          <button type="button" className="btn" disabled={testing} onClick={() => void connectionTest()}>{testing ? t('확인 중…') : t('연결 테스트')}</button>
          <span className="form-help">{t('AI 서버의 모델 목록 응답을 받아 확인합니다.')}</span>
        </div>
        {test && (test.success
          ? (
            <p role="status">
              <Label variant="success">{t('연결됨')}</Label> {t('{0} ms · 모델 {1}개', test.elapsedMillis, test.modelCount ?? '-')}
              {test.defaultModelPresent === false && <> · <Label variant="attention">{t('기본 모델이 서버에 없습니다')}</Label></>}
            </p>
          )
          : test.error && <CallFailure error={test.error} />)}
      </section>

      <section className="source-card" aria-label={t('모델 목록')}>
        <h4 className="source-title-icon"><ListChecks size={14} aria-hidden="true" /> {t('모델 목록')}</h4>
        <div className="source-toolbar">
          <button type="button" className="btn" disabled={loadingModels} onClick={() => void loadModels()}>{loadingModels ? t('불러오는 중…') : t('모델 목록 조회')}</button>
          <span className="form-help">{t('Lexorma 는 모델을 내려받지 않습니다. 필요한 모델은 AI 서버에서 준비하세요.')}</span>
        </div>
        {models && !models.success && models.error && <CallFailure error={models.error} />}
        {models?.success && (models.models.length === 0
          ? <p className="gh-muted" role="status">{t('AI 서버에 모델이 없습니다.')}</p>
          : (
            <div className="source-table-wrap">
              <table className="source-table">
                <thead><tr><th>{t('모델')}</th><th>{t('계열')}</th><th>{t('크기')}</th><th>{t('수정 시각')}</th><th>{t('작업')}</th></tr></thead>
                <tbody>
                  {models.models.map((m) => (
                    <tr key={m.name}>
                      <td className="gh-mono source-break">{m.name}</td>
                      <td className="source-sub">{[m.family, m.parameterSize, m.quantization].filter(Boolean).join(' · ') || '-'}</td>
                      <td className="text-end source-sub">{sizeText(m.size)}</td>
                      <td className="source-sub">{m.modifiedAt ? formatDateTime(m.modifiedAt) : '-'}</td>
                      <td>
                        {m.name === defaultModel ? <Label variant="accent">{t('기본 모델')}</Label>
                          : <button type="button" className="btn btn-sm" disabled={loadingModels} onClick={() => void makeDefault(m.name)}>{t('기본 모델로 지정')}</button>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
      </section>

      <section className="source-card" aria-label={t('테스트 프롬프트')}>
        <h4 className="source-title-icon"><MessageSquare size={14} aria-hidden="true" /> {t('테스트 프롬프트')}</h4>
        <form onSubmit={send}>
          <div className="source-form-grid">
            <div className="form-group source-span-2">
              <label htmlFor="aiPrompt">{t('프롬프트')}</label>
              <textarea id="aiPrompt" className="form-control" rows={3} maxLength={PROMPT_MAX} required value={prompt} onChange={(e) => setPrompt(e.target.value)}
                placeholder={t('예: 데이터 표준화가 무엇인지 한 문장으로 설명해 주세요.')} />
            </div>
            <div className="form-group source-span-2">
              <label htmlFor="aiModel">{t('모델 (비우면 기본 모델)')}</label>
              <input id="aiModel" type="text" className="form-control gh-mono" maxLength={255} value={model} onChange={(e) => setModel(e.target.value)} placeholder={defaultModel ?? ''} />
            </div>
          </div>
          <div className="source-toolbar">
            <button type="submit" className="btn btn-primary" disabled={sending || !provider.enabled || !prompt.trim()}>{sending ? t('응답 기다리는 중…') : t('보내기')}</button>
            <span className="form-help">{t('대화 이력은 저장하지 않습니다. 응답은 한 번에 받습니다 (스트리밍 없음).')}</span>
          </div>
        </form>
        {answer && (answer.success
          ? (
            <div role="status">
              <p className="source-sub gh-muted">
                {answer.model} · {t('{0} ms', answer.elapsedMillis)}
                {answer.promptTokens != null && <> · {t('입력 {0} · 출력 {1} 토큰', answer.promptTokens, answer.completionTokens ?? '-')}</>}
              </p>
              <pre className="ai-answer">{answer.content}</pre>
            </div>
          )
          : answer.error && <CallFailure error={answer.error} />)}
      </section>

      <div className="gh-dialog-actions">
        <span className="source-grow" />
        <button type="button" className="btn" onClick={onClose}>{t('닫기')}</button>
      </div>
    </div>
  );
}

function ProviderForm({ provider, types, onSaved, onCancel }: { provider?: Provider; types: TypeInfo[]; onSaved: () => void; onCancel: () => void }) {
  const firstType = types[0];
  const [name, setName] = useState(provider?.name ?? '');
  const [type, setType] = useState<ProviderType>(provider?.type ?? firstType?.type ?? 'OLLAMA');
  const [baseUrl, setBaseUrl] = useState(provider?.baseUrl ?? '');
  const [defaultModel, setDefaultModel] = useState(provider?.defaultModel ?? '');
  const [description, setDescription] = useState(provider?.description ?? '');
  const [enabled, setEnabled] = useState(provider?.enabled ?? true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const suggested = types.find((info) => info.type === type)?.defaultBaseUrl ?? '';

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    const body: ProviderRequest = {
      name: name.trim(), type, baseUrl: baseUrl.trim(), defaultModel: defaultModel.trim() || null, enabled, description: description.trim() || null,
    };
    try {
      await api(provider ? `/api/ai/providers/${enc(provider.id)}` : '/api/ai/providers', { method: provider ? 'PUT' : 'POST', body, silent: true });
      onSaved();
    } catch (e2) {
      setError((e2 as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="source-form" onSubmit={(e) => void submit(e)} autoComplete="off">
      <div className="source-form-grid">
        <div className="form-group">
          <label htmlFor="aiName">{t('이름')}</label>
          <input id="aiName" type="text" className="form-control" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="form-group">
          <label htmlFor="aiType">{t('유형')}</label>
          <select id="aiType" className="form-control" value={type} onChange={(e) => setType(e.target.value as ProviderType)}>
            {(types.length ? types : [{ type: 'OLLAMA' as ProviderType }]).map((info) => <option key={info.type} value={info.type}>{TYPE_LABEL[info.type] ?? info.type}</option>)}
          </select>
        </div>
        <div className="form-group source-span-2">
          <label htmlFor="aiUrl">{t('AI 서버 주소')}</label>
          <div className="source-toolbar">
            <input id="aiUrl" type="text" className="form-control gh-mono source-grow" required maxLength={255} placeholder={suggested} value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)} />
            {suggested && !baseUrl && <button type="button" className="btn btn-sm" onClick={() => setBaseUrl(suggested)}>{t('기본 주소 넣기')}</button>}
          </div>
          <p className="form-help">{t('Lexorma 서버에서 접근할 수 있는 주소입니다 (로컬 Ollama : {0}). Docker 에서는 127.0.0.1 대신 호스트·컨테이너 주소를 쓰세요.', suggested || 'http://127.0.0.1:11434')}</p>
        </div>
        <div className="form-group source-span-2">
          <label htmlFor="aiModelId">{t('기본 모델 (선택)')}</label>
          <input id="aiModelId" type="text" className="form-control gh-mono" maxLength={255} placeholder={t('예: llama3.2 · qwen3:8b (등록 후 모델 목록에서 고를 수 있습니다)')}
            value={defaultModel} onChange={(e) => setDefaultModel(e.target.value)} />
        </div>
        <div className="form-group source-span-2">
          <label htmlFor="aiDescription">{t('설명')}</label>
          <input id="aiDescription" type="text" className="form-control" maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <div className="form-group source-span-2 source-check">
          <label><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> {t('활성 (끄면 이 Provider 로 AI 를 호출하지 않습니다)')}</label>
        </div>
      </div>
      <p className="form-help">{t('저장만 하고 연결하지 않습니다. 저장한 뒤 점검에서 연결 테스트를 하세요. API Key 등 인증 정보는 받지 않습니다 (Ollama 는 필요 없습니다).')}</p>
      {error && <p role="alert" className="source-error">{error}</p>}
      <div className="gh-dialog-actions">
        <span className="source-grow" />
        <button type="button" className="btn" onClick={onCancel}>{t('닫기')}</button>
        <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? t('저장 중…') : provider ? t('저장') : t('등록')}</button>
      </div>
    </form>
  );
}
