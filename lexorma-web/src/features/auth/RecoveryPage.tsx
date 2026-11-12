import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';

interface RecoveryProject {
  id: number;
  projectName: string;
  isActive: boolean;
  isDefault: boolean;
  projectManagerName: string | null;
  updated: string | null;
}

const RECOVERY_PROJECTS_KEY = ['recovery-projects'];

/**
 * 복구 화면 (복구 세션 전용) : 프로젝트 목록 조회와 활성화만 한다.
 * 복구 세션은 일반 세션으로 바뀌지 않는다 → [복구 완료] 로 로그아웃한 뒤 정상 로그인한다.
 */
export function RecoveryPage() {
  const dialog = useDialog();
  const { logout } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: projects = [] } = useQuery({
    queryKey: RECOVERY_PROJECTS_KEY,
    queryFn: () => api<RecoveryProject[]>('/api/recovery/projects'),
  });

  const activate = (project: RecoveryProject) => {
    dialog.confirm(t('{0} 프로젝트를 활성화하시겠습니까?', project.projectName), async () => {
      await api(`/api/recovery/projects/${enc(project.id)}/activate`, { method: 'POST' });
      await queryClient.invalidateQueries({ queryKey: RECOVERY_PROJECTS_KEY });
    });
  };

  const finish = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  const complete = () => {
    if (projects.some((project) => project.isActive)) {
      dialog.confirm(t('복구를 마치고 로그아웃합니다. 활성화한 프로젝트로 다시 로그인해주세요.'), finish);
    } else {
      dialog.confirm(t('활성화된 프로젝트가 없습니다. 그래도 로그아웃하시겠습니까?'), finish);
    }
  };

  return (
    <>
      <PageHeader category={t('시스템 복구')} title={t('프로젝트 복구')}
        description={t('활성화된 프로젝트가 없어 복구 모드로 로그인했습니다. 프로젝트를 활성화한 뒤 [복구 완료] 를 눌러 다시 로그인해주세요. 복구 모드에서는 다른 기능을 사용할 수 없습니다.')} />

      <div className="grid-header">
        <div className="button-container">
          <button type="button" id="recoveryComplete" className="btn btn-primary" onClick={complete}>{t('복구 완료')}</button>
        </div>
      </div>

      <div className="dt-container">
        <div className="dt-layout-row dt-layout-table">
          <div className="dt-layout-cell dt-layout-full">
            <table id="recoveryProjectGrid" className="table table-striped table-bordered dataTable grid-fixed">
              <thead>
                <tr>{[t('프로젝트 명'), t('상태'), t('프로젝트 관리자'), t('수정일'), ''].map((header, index) => <th key={index}>{header}</th>)}</tr>
              </thead>
              <tbody>
                {projects.map((project) => (
                  <tr key={project.id}>
                    <td>
                      {project.projectName}
                      {project.isDefault && <> <Label variant="accent">{t('기본')}</Label></>}
                    </td>
                    <td>{project.isActive ? <Label variant="success" dot>{t('활성')}</Label> : <Label variant="attention" dot>{t('비활성')}</Label>}</td>
                    <td>{project.projectManagerName || '-'}</td>
                    <td>{formatDateTime(project.updated) || '-'}</td>
                    <td>
                      {!project.isActive && (
                        <button type="button" className="btn btn-sm" onClick={() => activate(project)}>{t('활성화')}</button>
                      )}
                    </td>
                  </tr>
                ))}
                {projects.length === 0 && (
                  <tr><td colSpan={5}>{t('복구할 프로젝트가 없습니다.')}</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </>
  );
}
