import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { AlertIcon, ArrowRightIcon, RepoIcon } from '@primer/octicons-react';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError, resetCsrf } from '../../lib/api';

interface LoginProject {
  id: number;
  projectName: string;
}

/**
 * 로그인 (2단계)
 * 1. 아이디/비밀번호 확인 → 접근 가능한 프로젝트 조회 (POST /api/login/projects)
 * 2. 프로젝트 선택 → 로그인 (POST /api/auth/login, Security 가 비밀번호와 프로젝트 접근 권한을 다시 확인)
 * 초기 비밀번호 계정은 비밀번호를 먼저 바꿔야 한다 (POST /api/login/password)
 */
export function LoginPage() {
  const dialog = useDialog();
  const { refresh } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  const from = (location.state as { from?: string } | null)?.from;

  const login = async (user: string, pass: string, project: LoginProject) => {
    try {
      await api('/api/auth/login', {
        method: 'POST',
        body: new URLSearchParams({ username: user, password: pass, project_id: String(project.id) }),
        silent: true,
      });
    } catch (e) {
      dialog.alert(e instanceof ApiError ? e.message : '로그인 중 문제가 발생했습니다.');
      return;
    }
    // 로그인하면 세션 ID 와 CSRF 토큰이 바뀐다
    resetCsrf();
    await refresh();
    navigate(from && from !== '/login' ? from : '/', { replace: true });
  };

  const openProjectDialog = async (user: string, pass: string) => {
    if (!user) return dialog.alert('아이디를 입력하세요.');
    if (!pass) return dialog.alert('패스워드를 입력하세요.');

    let projects: LoginProject[];
    try {
      projects = await api<LoginProject[]>('/api/login/projects', { method: 'POST', body: { username: user, password: pass }, silent: true });
    } catch (e) {
      // 초기 비밀번호(admin/admin, 관리자 초기화) 사용 중 : 비밀번호를 바꾸기 전에는 로그인할 수 없다
      if (e instanceof ApiError && e.code === 'PASSWORD_CHANGE_REQUIRED') {
        openChangePasswordDialog(user, pass);
        return;
      }
      dialog.alert(e instanceof ApiError ? e.message : '로그인 정보를 확인하는 중 문제가 발생했습니다.');
      return;
    }

    // 선택할 프로젝트가 하나면 바로 로그인
    if (projects.length === 1) {
      dialog.confirm(`${projects[0].projectName} 프로젝트로 로그인됩니다.`, () => login(user, pass, projects[0]));
      return;
    }

    dialog.open({
      title: '프로젝트 선택',
      content: ({ close }: DialogControls) => (
        <div>
          <p className="gh-dialog-text">로그인할 프로젝트를 선택하세요.</p>
          <ul className="auth-project-list" id="projectSelect">
            {projects.map((project) => (
              <li key={project.id}>
                <button
                  type="button"
                  onClick={() => {
                    close();
                    login(user, pass, project);
                  }}
                >
                  <RepoIcon />
                  <span>{project.projectName}</span>
                  <ArrowRightIcon className="auth-project-go" />
                </button>
              </li>
            ))}
          </ul>
        </div>
      ),
    });
  };

  const openChangePasswordDialog = (user: string, pass: string) => {
    dialog.open({
      title: '비밀번호 변경',
      content: ({ close }: DialogControls) => (
        <InitialPasswordForm
          username={user}
          password={pass}
          onChanged={(newPassword) => {
            close();
            setPassword(newPassword);
            // 새 비밀번호로 로그인 계속 진행 (프로젝트 선택)
            dialog.alert('비밀번호가 변경되었습니다. 로그인할 프로젝트를 선택합니다.', () => openProjectDialog(user, newPassword));
          }}
        />
      ),
    });
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    openProjectDialog(username.trim(), password);
  };

  return (
    <div className="auth">
      <div className="auth-logo">
        <img src="/images/favicon.svg" alt="" width="48" height="48" />
      </div>
      <h1 className="auth-title">MetaMora 로그인</h1>

      <div className="auth-box">
        <form id="loginForm" onSubmit={onSubmit}>
          <div className="auth-field">
            <label htmlFor="username">아이디</label>
            <input type="text" className="form-control" id="username" name="username" autoComplete="username" autoCapitalize="off"
              autoFocus required value={username} onChange={(e) => setUsername(e.target.value)} />
          </div>
          <div className="auth-field">
            <label htmlFor="password">비밀번호</label>
            <input type="password" className="form-control" id="password" name="password" autoComplete="current-password" required
              value={password} onChange={(e) => setPassword(e.target.value)} />
          </div>

          <button type="submit" id="project-btn" className="btn btn-primary auth-submit">로그인</button>
          <p className="auth-hint">로그인할 프로젝트는 다음 단계에서 선택합니다.</p>
        </form>
      </div>

      <div className="auth-callout">
        MetaMora가 처음이신가요? <Link to="/signup">회원가입</Link>
      </div>
    </div>
  );
}

/** 초기 비밀번호 변경 (로그인 전) */
function InitialPasswordForm({ username, password, onChanged }: { username: string; password: string; onChanged: (newPassword: string) => void }) {
  const dialog = useDialog();
  const [newPassword, setNewPassword] = useState('');
  const [newPassword2, setNewPassword2] = useState('');

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!newPassword) return dialog.alert('새 비밀번호를 입력하세요.');
    if (newPassword !== newPassword2) return dialog.alert('새 비밀번호가 일치하지 않습니다.');
    if (newPassword === password || newPassword === username) return dialog.alert('새 비밀번호는 현재 비밀번호 및 아이디와 달라야 합니다.');

    try {
      await api('/api/login/password', { method: 'POST', body: { username, password, newPassword }, silent: true });
    } catch (err) {
      dialog.alert(err instanceof ApiError ? err.message : '비밀번호를 변경하는 중 문제가 발생했습니다.');
      return;
    }
    onChanged(newPassword);
  };

  return (
    <form id="initialPasswordForm" autoComplete="off" onSubmit={onSubmit}>
      <div className="gh-flash gh-flash-attention">
        <AlertIcon />
        <span>초기 비밀번호를 사용 중입니다. 비밀번호를 변경해야 로그인할 수 있습니다.</span>
      </div>
      <div className="form-group">
        <label htmlFor="initialNewPassword">새 비밀번호</label>
        <input type="password" className="form-control" id="initialNewPassword" autoComplete="new-password" required autoFocus
          value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
      </div>
      <div className="form-group">
        <label htmlFor="initialNewPassword2">새 비밀번호 확인</label>
        <input type="password" className="form-control" id="initialNewPassword2" autoComplete="new-password" required
          value={newPassword2} onChange={(e) => setNewPassword2(e.target.value)} />
        <p className="auth-field-note">8자 이상, 대문자와 특수문자를 각각 하나 이상 포함해야 합니다.</p>
      </div>
      <div className="gh-dialog-actions">
        <button type="submit" className="btn btn-primary">변경하고 로그인</button>
      </div>
    </form>
  );
}
