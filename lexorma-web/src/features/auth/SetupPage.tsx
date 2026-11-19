import { useState, type FormEvent, type ReactNode } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { InfoIcon } from '@primer/octicons-react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { useToast } from '../../components/toast/ToastProvider';
import { api, ApiError } from '../../lib/api';
import { t } from '../../lib/i18n';

export const SETUP_PATH = '/setup';

/** 계정이 하나도 없는지 (GET /api/auth/setup). 확인하지 못하면 평소 화면을 보여 준다 */
function useSetupRequired() {
  const { data, isPending } = useQuery({
    queryKey: ['auth-setup'],
    queryFn: () => api<{ required: boolean }>('/api/auth/setup', { silent: true }),
  });
  return { pending: isPending, required: data?.required === true };
}

/** 로그인·회원가입 화면 : 계정이 하나도 없으면 최초 관리자 계정 만들기로 보낸다 */
export function RequireSetupDone({ children }: { children: ReactNode }) {
  const { pending, required } = useSetupRequired();
  if (pending) return null;
  if (required) return <Navigate to={SETUP_PATH} replace />;
  return <>{children}</>;
}

/**
 * 최초 관리자 계정 만들기 (POST /api/auth/setup) : 계정이 하나도 없을 때만.
 * 만든 계정은 시스템 관리자(ADMIN)이고 모든 프로젝트의 멤버가 된다. 계정이 이미 있으면 로그인 화면으로 보낸다.
 */
export function SetupPage() {
  const dialog = useDialog();
  const toast = useToast();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { pending, required } = useSetupRequired();

  const [form, setForm] = useState({ username: '', password: '', password2: '', name: '' });
  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [key]: e.target.value }));
  const [busy, setBusy] = useState(false);

  if (pending) return null;
  if (!required) return <Navigate to="/login" replace />;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (form.password !== form.password2) return dialog.alert(t('비밀번호가 일치하지 않습니다.'));

    setBusy(true);
    try {
      await api('/api/auth/setup', {
        method: 'POST',
        body: { username: form.username.trim(), password: form.password, name: form.name },
        silent: true,
      });
    } catch (err) {
      setForm((f) => ({ ...f, password: '', password2: '' }));
      // 그 사이 다른 곳에서 계정을 만들었으면 로그인 화면으로
      if (err instanceof ApiError && err.code === 'SETUP_ALREADY_COMPLETED') {
        dialog.alert(err.message, () => queryClient.invalidateQueries({ queryKey: ['auth-setup'] }));
      } else {
        dialog.alert(err instanceof ApiError ? err.message : t('관리자 계정을 만드는 중 문제가 발생했습니다.'));
      }
      return;
    } finally {
      setBusy(false);
    }
    await queryClient.invalidateQueries({ queryKey: ['auth-setup'] });
    navigate('/login', { replace: true });
    toast.success(t('관리자 계정을 만들었습니다. 로그인해주세요.'));
  };

  return (
    <div className="auth">
      <div className="auth-logo">
        <img src="/images/favicon.svg" alt="" width="48" height="48" />
      </div>
      <h1 className="auth-title">{t('Lexorma 시작하기')}</h1>

      <div className="auth-box">
        <form id="setupForm" onSubmit={onSubmit}>
          <div className="gh-flash" id="setupNotice">
            <InfoIcon />
            <span>{t('아직 계정이 없습니다. 시스템 관리자 계정을 만들어 주세요.')}</span>
          </div>
          <div className="auth-field">
            <label htmlFor="username">{t('아이디')}</label>
            <input type="text" className="form-control" id="username" autoComplete="username" autoCapitalize="off" autoFocus required
              value={form.username} onChange={set('username')} />
            <p className="auth-field-note">{t('5~20자의 소문자, 숫자, 밑줄(_)')}</p>
          </div>
          <div className="auth-field">
            <label htmlFor="password">{t('비밀번호')}</label>
            <input type="password" className="form-control" id="password" autoComplete="new-password" required value={form.password} onChange={set('password')} />
          </div>
          <div className="auth-field">
            <label htmlFor="password2">{t('비밀번호 확인')}</label>
            <input type="password" className="form-control" id="password2" autoComplete="new-password" required value={form.password2} onChange={set('password2')} />
            <p className="auth-field-note">{t('8자 이상, 대문자와 특수문자를 각각 하나 이상 포함해야 합니다.')}</p>
          </div>
          <div className="auth-field">
            <label htmlFor="name">{t('사용자명')}</label>
            <input type="text" className="form-control" id="name" autoComplete="name" required value={form.name} onChange={set('name')} />
          </div>
          <button type="submit" className={`btn btn-primary auth-submit${busy ? ' is-busy' : ''}`} disabled={busy} aria-busy={busy}>
            <span className="motion-busy-label">{t('관리자 계정 만들기')}</span>
            {busy && <span className="motion-spinner" aria-hidden="true" />}
          </button>
          <p className="auth-hint">{t('이 계정은 시스템 관리자(ADMIN)이며 모든 프로젝트의 멤버가 됩니다.')}</p>
        </form>
      </div>
    </div>
  );
}
