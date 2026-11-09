import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { api, ApiError } from '../../lib/api';

interface SignupProject {
  id: number;
  projectName: string;
}

/** 회원가입 : 가입한 계정은 NOT_APPROVE 권한, 프로젝트 설정에 따라 승인 후 사용 */
export function SignupPage() {
  const dialog = useDialog();
  const navigate = useNavigate();
  const { data: projects = [] } = useQuery({
    queryKey: ['signup-projects'],
    queryFn: () => api<SignupProject[]>('/api/auth/signup/projects'),
  });

  const [form, setForm] = useState({ username: '', password: '', name: '', projectId: '' });
  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [key]: e.target.value }));

  useEffect(() => {
    if (!form.projectId && projects.length > 0) setForm((f) => ({ ...f, projectId: String(projects[0].id) }));
  }, [projects, form.projectId]);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await api('/api/auth/signup', {
        method: 'POST',
        body: { username: form.username, password: form.password, name: form.name, projectId: Number(form.projectId) },
        silent: true,
      });
    } catch (err) {
      // 오류 시 입력한 내역 유지 (패스워드 제외)
      setForm((f) => ({ ...f, password: '' }));
      dialog.alert(err instanceof ApiError ? err.message : '회원가입 중 문제가 발생했습니다.');
      return;
    }
    dialog.alert('회원가입이 완료되었습니다. 로그인해주세요.', () => navigate('/login', { replace: true }));
  };

  return (
    <div className="auth">
      <div className="auth-logo">
        <img src="/images/favicon.svg" alt="" width="48" height="48" />
      </div>
      <h1 className="auth-title">MetaMora 회원가입</h1>

      <div className="auth-box">
        <form onSubmit={onSubmit}>
          <div className="auth-field">
            <label htmlFor="username">아이디</label>
            <input type="text" className="form-control" id="username" autoComplete="username" autoCapitalize="off" autoFocus required
              value={form.username} onChange={set('username')} />
          </div>
          <div className="auth-field">
            <label htmlFor="password">비밀번호</label>
            <input type="password" className="form-control" id="password" autoComplete="new-password" required value={form.password} onChange={set('password')} />
          </div>
          <div className="auth-field">
            <label htmlFor="name">사용자명</label>
            <input type="text" className="form-control" id="name" autoComplete="name" required value={form.name} onChange={set('name')} />
          </div>
          <div className="auth-field">
            <label htmlFor="projectId">참여할 프로젝트</label>
            <select className="form-control" id="projectId" required value={form.projectId} onChange={set('projectId')}>
              {projects.map((project) => (
                <option key={project.id} value={project.id}>{project.projectName}</option>
              ))}
            </select>
            <p className="auth-field-note">프로젝트 설정에 따라 관리자 승인 후 사용할 수 있습니다.</p>
          </div>
          <button type="submit" className="btn btn-primary auth-submit">회원가입</button>
        </form>
      </div>

      <div className="auth-callout">
        이미 계정이 있으신가요? <Link to="/login">로그인</Link>
      </div>
    </div>
  );
}
