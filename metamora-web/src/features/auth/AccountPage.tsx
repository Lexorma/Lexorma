import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDialog } from '../../components/dialog/DialogProvider';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError } from '../../lib/api';
import { initial } from '../../lib/format';
import { t } from '../../lib/i18n';
import { roleLabel } from '../../lib/roles';

/** 내 정보 (GitHub 설정 화면 형태 : 왼쪽 프로필 / 오른쪽 설정 항목) */
export function AccountPage() {
  const dialog = useDialog();
  const navigate = useNavigate();
  const { user, displayName, refresh, logout } = useAuth();

  const [name, setName] = useState(user?.name ?? '');
  const [password, setPassword] = useState('');
  const [changedPasswd, setChangedPasswd] = useState('');
  const [changedPasswd2, setChangedPasswd2] = useState('');

  useEffect(() => setName(user?.name ?? ''), [user?.name]);

  if (!user) return null;

  const changeName = async (e: FormEvent) => {
    e.preventDefault();
    if (name === '') return dialog.alert(t('사용자명을 입력해주세요.'));
    try {
      const response = await api<{ result: string }>('/api/updateName', { method: 'POST', body: { name }, silent: true });
      if (response.result === 'success') dialog.alert(t('사용자명이 성공적으로 변경되었습니다.'), () => refresh());
    } catch (err) {
      dialog.alert(err instanceof ApiError ? err.message : t('사용자명 변경 중 문제가 발생했습니다.'));
    }
  };

  const changePassword = async () => {
    if (changedPasswd !== changedPasswd2) return dialog.alert(t('비밀번호가 일치하지 않습니다.'));
    if (changedPasswd === password) return dialog.alert(t('동일한 비밀번호로 변경은 불가능합니다.'));
    if (password.trim() === '') return dialog.alert(t('비밀번호를 입력해주세요.'));

    try {
      // 서버 필드명 : password = 현재 비밀번호, currentPassword = 새 비밀번호 (기존 API 그대로)
      const response = await api<{ result: string }>('/api/updatePwd', {
        method: 'POST',
        body: { password, currentPassword: changedPasswd },
        silent: true,
      });
      if (response.result === 'success') {
        dialog.alert(t('비밀번호가 성공적으로 변경되었습니다.'), async () => {
          await logout();
          navigate('/login', { replace: true });
        });
      }
    } catch (err) {
      dialog.alert(err instanceof ApiError ? err.message : t('비밀번호 변경 중 문제가 발생했습니다.'));
    }
  };

  return (
    <>
      <PageHeader category={t('사용자')} title={t('내 정보')} />

      <div className="account-layout">
        <aside className="account-profile">
          <div className="account-avatar">{initial(displayName)}</div>
          <div className="account-names">
            <strong>{displayName}</strong>
            <span>{user.username}</span>
          </div>
          <span className="gh-label gh-label-done">{roleLabel(user.role || user.roleName)}</span>
        </aside>

        <div className="account-main">
          <section className="account-section">
            <h2 className="account-subhead">{t('계정 정보')}</h2>
            <dl className="account-info">
              <div>
                <dt>{t('아이디')}</dt>
                <dd>{user.username}</dd>
              </div>
              <div>
                <dt>{t('권한')}</dt>
                <dd>{roleLabel(user.role || user.roleName)}</dd>
              </div>
            </dl>
          </section>

          <section className="account-section">
            <h2 className="account-subhead">{t('사용자명')}</h2>
            <form id="chnageNameForm" onSubmit={changeName}>
              <div className="account-field">
                <label htmlFor="name">{t('사용자명')}</label>
                <input type="text" className="form-control" id="name" autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} />
                <p className="account-note">{t('공지사항 등 화면에 표시되는 이름입니다.')}</p>
              </div>
              <button id="changeNameBtn" type="submit" className="btn">{t('사용자명 변경')}</button>
            </form>
          </section>

          <section className="account-section">
            <h2 className="account-subhead">{t('비밀번호 변경')}</h2>
            <form id="changedPasswdForm" onSubmit={(e) => { e.preventDefault(); changePassword(); }}>
              <div className="account-field">
                <label htmlFor="password">{t('현재 비밀번호')}</label>
                <input type="password" className="form-control" id="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
              </div>
              <div className="account-field">
                <label htmlFor="changedPasswd">{t('새 비밀번호')}</label>
                <input type="password" className="form-control" id="changedPasswd" autoComplete="new-password" value={changedPasswd} onChange={(e) => setChangedPasswd(e.target.value)} />
              </div>
              <div className="account-field">
                <label htmlFor="changedPasswd2">{t('새 비밀번호 확인')}</label>
                <input type="password" className="form-control" id="changedPasswd2" autoComplete="new-password" value={changedPasswd2} onChange={(e) => setChangedPasswd2(e.target.value)} />
                <p className="account-note">{t('비밀번호를 변경하면 로그아웃되며, 새 비밀번호로 다시 로그인해야 합니다.')}</p>
              </div>
              <button id="changePasswdBtn" type="submit" className="btn btn-primary">{t('비밀번호 변경')}</button>
            </form>
          </section>
        </div>
      </div>
    </>
  );
}
