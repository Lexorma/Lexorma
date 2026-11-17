import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, Muted } from '../../components/grid/cells';
import { api, enc } from '../../lib/api';
import { AI_STATUS_KEY } from '../../hooks/useAiStatus';
import { t } from '../../lib/i18n';

type Status = 'READY' | 'NOT_CONFIGURED' | 'DISABLED' | 'MODEL_MISSING' | 'UNSUPPORTED';
interface Choice { id: number; name: string; type: string; defaultModel: string | null }
interface ProjectProvider {
  projectId: number; scope: 'PROJECT' | 'SYSTEM'; status: Status; provider: Choice | null; model: string | null; systemProvider: Choice | null;
  updatedBy: string | null; updatedAt: string | null; canManage: boolean; choices: Choice[];
}

const STATUS: Record<Status, { text: string; variant: 'success' | 'attention' | 'danger' }> = {
  READY: { text: t('사용 가능'), variant: 'success' },
  NOT_CONFIGURED: { text: t('미지정'), variant: 'attention' },
  DISABLED: { text: t('비활성'), variant: 'danger' },
  MODEL_MISSING: { text: t('모델 없음'), variant: 'danger' },
  UNSUPPORTED: { text: t('지원하지 않는 유형'), variant: 'danger' },
};

/**
 * 프로젝트 AI Provider (DEC-18) : 지정이 없으면 시스템 기본, 지정하면 그 Provider·모델을 이 프로젝트의 AI 기능이 쓴다.
 * 지정한 Provider 를 쓸 수 없게 되면 이 프로젝트의 AI 기능은 멈추고 시스템 기본으로 바뀌지 않는다. 지정·해제는 프로젝트 관리자·ADMIN (서버가 다시 확인)
 */
export function ProjectAiProvider({ projectId }: { projectId: number }) {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const key = ['project-ai-provider', projectId];
  const { data } = useQuery({ queryKey: key, queryFn: () => api<ProjectProvider>(`/api/ai/projects/${enc(projectId)}/provider`) });
  const [providerId, setProviderId] = useState('');
  const [model, setModel] = useState('');
  useEffect(() => {
    setProviderId(data?.scope === 'PROJECT' && data.provider ? String(data.provider.id) : '');
    setModel(data?.scope === 'PROJECT' && data.model && data.model !== data.provider?.defaultModel ? data.model : '');
  }, [data]);
  if (!data) return null;

  const refresh = async (next: ProjectProvider) => {
    queryClient.setQueryData(key, next);
    await queryClient.invalidateQueries({ queryKey: AI_STATUS_KEY }); // 사이드바·AI 도우미의 사용 가능 여부
  };
  const save = () => {
    if (!providerId) return dialog.alert(t('프로젝트에서 쓸 Provider 를 선택해주세요.'));
    dialog.confirm(t('이 프로젝트의 AI 기능이 고른 Provider 를 쓰게 합니다. 저장하시겠습니까?'), async () => {
      await refresh(await api<ProjectProvider>(`/api/ai/projects/${enc(projectId)}/provider`, { method: 'PUT', body: { providerId: Number(providerId), model: model.trim() || null } }));
    });
  };
  const clear = () => dialog.confirm(t('프로젝트 지정을 해제하면 시스템 기본 Provider 를 씁니다. 해제하시겠습니까?'), async () => {
    await refresh(await api<ProjectProvider>(`/api/ai/projects/${enc(projectId)}/provider`, { method: 'DELETE' }));
  });
  const status = STATUS[data.status];
  return (
    <section className="Box" style={{ padding: 12, marginBottom: 16 }} aria-label={t('프로젝트 AI Provider')}>
      <h3 style={{ marginTop: 0 }}>{t('프로젝트 AI Provider')}</h3>
      <p>
        {data.scope === 'PROJECT' ? <Label variant="accent">{t('프로젝트 지정')}</Label> : <Label>{t('시스템 기본 사용')}</Label>}{' '}
        <Label variant={status.variant} dot>{status.text}</Label>{' '}
        {data.provider ? <span>{data.provider.name} · <span className="gh-mono">{data.model ?? '-'}</span></span> : <Muted>{t('지정된 Provider 가 없습니다.')}</Muted>}
      </p>
      {data.scope === 'PROJECT' && data.status !== 'READY' && (
        <p className="gh-muted" role="note">{t('지정한 Provider 를 쓸 수 없어 이 프로젝트의 AI 기능이 멈췄습니다. 시스템 기본으로 자동으로 바꾸지 않습니다.')}</p>
      )}
      {data.canManage && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <select className="form-control" style={{ maxWidth: 260 }} aria-label={t('Provider')} value={providerId} onChange={(e) => setProviderId(e.target.value)}>
            <option value="">{t('Provider 선택')}</option>
            {data.choices.map((c) => <option key={c.id} value={c.id}>{c.name}{c.defaultModel ? ` (${c.defaultModel})` : ''}</option>)}
          </select>
          <input className="form-control gh-mono" style={{ maxWidth: 220 }} aria-label={t('모델')} placeholder={t('모델 (비우면 Provider 기본 모델)')} value={model} onChange={(e) => setModel(e.target.value)} />
          <button type="button" className="btn btn-primary" id="btn-project-ai-save" onClick={save}>{t('지정')}</button>
          {data.scope === 'PROJECT' && <button type="button" className="btn" id="btn-project-ai-clear" onClick={clear}>{t('지정 해제')}</button>}
        </div>
      )}
    </section>
  );
}
