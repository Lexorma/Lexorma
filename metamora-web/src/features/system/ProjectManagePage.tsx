import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono, Muted, User } from '../../components/grid/cells';
import { Grid, type GridHandle } from '../../components/grid/Grid';
import { PencilIcon, PlusIcon, RepoIcon, TrashIcon } from '@primer/octicons-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { DeniedPage } from '../auth/DeniedPage';
import { projectColumns } from './columns';
import { t } from '../../lib/i18n';

/** 대상 DB 선택지 (서버 core/project/domain/enums/DbType 과 같은 값) */
const DB_TYPES = [
  { value: 'POSTGRESQL', label: 'PostgreSQL' },
  { value: 'ORACLE', label: 'Oracle' },
  { value: 'MYSQL', label: 'MySQL' },
  { value: 'MARIADB', label: 'MariaDB' },
];

interface ManagedProject {
  id: number;
  projectName: string;
  isActive: boolean;
  isAutoActive: boolean;
}

interface ProjectData {
  id?: number;
  projectName?: string;
  projectManagerId?: number;
  projectManagerName?: string;
  isActive?: boolean;
  isAutoActive?: boolean;
  isDicAbbrUsed?: boolean;
  isModified?: boolean;
  tablePrefix?: string;
  dbType?: string;
  projectMembers?: { accountId: number; username: string; name: string; isActive: string }[];
}

const MANAGED_KEY = ['managed-projects'];

/** 프로젝트 관리 : 프로젝트 등록·수정·삭제, 프로젝트 멤버 승인 */
export function ProjectManagePage() {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const { refresh } = useAuth();
  const grid = useRef<GridHandle>(null);
  const { data: projects } = useQuery({ queryKey: MANAGED_KEY, queryFn: () => api<ManagedProject[]>('/api/getProject/managed') });
  const [selectedId, setSelectedId] = useState<number | null>(null);

  useEffect(() => {
    if (projects && projects.length > 0 && !projects.some((p) => p.id === selectedId)) setSelectedId(projects[0].id);
  }, [projects, selectedId]);

  if (!projects) return null;
  // 프로젝트 관리자가 아닌경우 권한 없음
  if (projects.length === 0) return <DeniedPage title={t('접근 불가 페이지')} message={t('프로젝트 관리자만 접근 가능합니다')} />;

  const selected = projects.find((p) => p.id === selectedId) ?? projects[0];
  const isInactive = !selected.isActive;

  const reloadProjects = async () => {
    await queryClient.invalidateQueries({ queryKey: MANAGED_KEY });
    await refresh(); // 헤더의 프로젝트명
  };

  const openProjectForm = (type: 'C' | 'U', projectData: ProjectData) => {
    dialog.open({
      title: type === 'C' ? t('프로젝트 등록') : t('프로젝트 수정'),
      content: ({ close }: DialogControls) => (
        <ProjectForm
          type={type}
          projectData={projectData}
          onSaved={() => {
            dialog.alert(type === 'C' ? t('정상적으로 등록되었습니다.') : t('정상적으로 수정되었습니다.'), () => {
              close();
              reloadProjects();
            });
          }}
        />
      ),
    });
  };

  const editProject = async () => {
    const response = await api<{ result: boolean; project: ProjectData }>(`/api/getProject/${enc(selected.id)}/getEditProjectData`);
    if (response.result) openProjectForm('U', response.project);
  };

  const deleteProject = () => {
    dialog.confirm(t('프로젝트 삭제 시 복구가 불가합니다.\n해당 프로젝트를 삭제하시겠습니까?'), async () => {
      const response = await api<{ result: boolean }>(`/api/deleteProject/project/${enc(selected.id)}`, { method: 'DELETE' });
      if (response.result) dialog.alert(t('정상적으로 삭제 되었습니다.'), () => reloadProjects());
    });
  };

  // 프로젝트 멤버 추가
  const addMember = async () => {
    const response = await api<{ result: boolean; projectMember: { id: string; name: string }[] }>(`/api/getProject/projectMember/${enc(selected.id)}`);
    if (!response.result) return;
    dialog.open({
      title: t('프로젝트 멤버 추가'),
      content: ({ close }: DialogControls) => (
        <MemberAddForm
          users={response.projectMember}
          onSave={async (accountId) => {
            const saved = await api<{ result: boolean }>(`/api/saveProject/projectMember/${enc(selected.id)}`, { method: 'POST', body: { accountId } });
            if (saved.result) {
              dialog.alert(t('프로젝트 멤버가 정상적으로 등록되었습니다.'), () => {
                close();
                grid.current?.reload();
              });
            }
          }}
        />
      ),
    });
  };

  const saveIsActive = (isActive: boolean) => {
    const checked = grid.current?.getCheckedOrAlert();
    if (!checked) return;
    dialog.confirm(isActive ? t('선택한 정보를 승인 하시겠습니까?') : t('선택한 정보를 승인취소 하시겠습니까?'), async () => {
      const response = await api<{ result: boolean }>(`/api/saveProject/active/${isActive}`, { method: 'POST', body: checked });
      if (response.result) {
        dialog.alert(isActive ? t('정상적으로 승인되어 활성화되었습니다.') : t('정상적으로 승인취소되어 비활성화되었습니다.'), () => grid.current?.reload());
      }
    });
  };

  const deleteMembers = () => {
    const checked = grid.current?.getCheckedOrAlert();
    if (!checked) return;
    dialog.confirm(t('선택한 정보를 삭제하시겠습니까?'), async () => {
      const response = await api<{ result: boolean }>('/api/deleteProject/projectMember', { method: 'DELETE', body: checked });
      if (response.result) dialog.alert(t('정상적으로 삭제처리 되었습니다.'), () => grid.current?.reload());
    });
  };

  return (
    <>
      <PageHeader category={t('시스템 관리')} title={t('프로젝트 관리')} description={t('프로젝트를 등록·수정하고, 프로젝트 멤버의 참여를 승인합니다.')} />

      {/* 프로젝트 선택 바 (GitHub 저장소 헤더 형태) */}
      <div className="project-bar">
        <div className="project-bar-main">
          <RepoIcon className="project-bar-icon" />
          <label htmlFor="projectSelector" className="visually-hidden">{t('프로젝트 선택')}</label>
          <select id="projectSelector" className="project-select" value={selected.id} onChange={(e) => setSelectedId(Number(e.target.value))}>
            {projects.map((project) => (
              <option key={project.id} value={project.id}>
                {project.projectName}{project.isActive ? '' : t(' (비활성)')}
              </option>
            ))}
          </select>
          <span className={`gh-label gh-label-dot ${isInactive ? 'gh-label-danger' : 'gh-label-success'}`}>{isInactive ? t('비활성') : t('활성')}</span>
          {selected.isAutoActive && <span className="gh-label">{t('사용자 자동 승인')}</span>}
        </div>
        {/* 프로젝트 버튼 : 멤버 그리드 버튼과 구분되도록 프로젝트 바에 둔다 (수정은 비활성 프로젝트에서도 사용) */}
        <div className="project-bar-actions">
          <button type="button" id="btn-project-add" title={t('프로젝트 등록')} aria-label={t('프로젝트 등록')} className="btn" disabled={isInactive} onClick={() => openProjectForm('C', {})}>
            <PlusIcon />
            {t('등록')}
          </button>
          <button type="button" id="btn-project-edit" title={t('프로젝트 수정')} aria-label={t('프로젝트 수정')} className="btn" onClick={editProject}>
            <PencilIcon />
            {t('수정')}
          </button>
          <button type="button" id="btn-project-delete" title={t('프로젝트 삭제')} aria-label={t('프로젝트 삭제')} className="btn btn-danger" disabled={isInactive} onClick={deleteProject}>
            <TrashIcon />
            {t('삭제')}
          </button>
        </div>
      </div>

      <div id="gridContainer" style={{ pointerEvents: isInactive ? 'none' : 'auto', opacity: isInactive ? 0.5 : 1 }}>
        <Grid
          key={selected.id}
          ref={grid}
          id="projectMemberGrid"
          columns={projectColumns}
          dataUrl={`/api/getProject/${selected.id}`}
          // 대문자 : 이 화면에는 자신이 관리하는 프로젝트만 나오고 서버도 그 기준으로 막는다 (로그인한 프로젝트의 승인 권한과는 무관)
          crudActions={selected.isAutoActive ? 'CD' : 'ANCD'}
          onAction={{ add: addMember, active: () => saveIsActive(true), unactive: () => saveIsActive(false), delete: deleteMembers }}
          renderCell={(field, row) => {
            if (field === 'username') return <Mono>{row.username}</Mono>;
            if (field === 'name') return <User name={row.name} />;
            if (field === 'isActive') {
              if (row.isActive === 'Y') return <Label variant="success" dot>{t('승인')}</Label>;
              if (row.isActive === 'N') return <Label variant="attention" dot>{t('승인 대기')}</Label>;
            }
            if (field === 'projectManagerYn') return row.projectManagerYn === 'O' ? <Label variant="accent">{t('관리자')}</Label> : <Muted>-</Muted>;
            return undefined;
          }}
        />
      </div>
    </>
  );
}

function MemberAddForm({ users, onSave }: { users: { id: string; name: string }[]; onSave: (accountId: string) => void }) {
  const dialog = useDialog();
  const [accountId, setAccountId] = useState('');
  return (
    <div>
      <label className="gh-form-label" htmlFor="addProjectMemberSelect">{t('사용자')}</label>
      <select className="form-control" id="addProjectMemberSelect" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
        <option value="" disabled>{t('추가할 사용자를 선택하세요')}</option>
        {users.map((user) => (
          <option key={user.id} value={user.id}>{user.name}</option>
        ))}
      </select>
      <div className="gh-dialog-actions">
        <button type="button" className="btn btn-primary" onClick={() => (accountId ? onSave(accountId) : dialog.alert(t('추가할 사용자를 선택해주세요.')))}>
          {t('저장')}
        </button>
      </div>
    </div>
  );
}

function ProjectForm({ type, projectData, onSaved }: { type: 'C' | 'U'; projectData: ProjectData; onSaved: () => void }) {
  const dialog = useDialog();
  const activeMembers = (projectData.projectMembers ?? []).filter((m) => m.isActive === 'Y');
  const [form, setForm] = useState({
    projectName: projectData.projectName ?? '',
    projectManagerId: String(
      projectData.projectManagerId ?? activeMembers.find((m) => m.username === projectData.projectManagerName || m.name === projectData.projectManagerName)?.accountId ?? activeMembers[0]?.accountId ?? '',
    ),
    isActive: !!projectData.isActive,
    isAutoActive: !!projectData.isAutoActive,
    isDicAbbrUsed: !!projectData.isDicAbbrUsed,
    tablePrefix: projectData.tablePrefix ?? '',
    dbType: projectData.dbType || 'POSTGRESQL',
  });
  const set = <K extends keyof typeof form>(key: K, value: (typeof form)[K]) => setForm((f) => ({ ...f, [key]: value }));
  const canSave = type === 'C' || projectData.isModified;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (form.projectName === '') return dialog.alert(t('프로젝트 명은 필수값입니다.'));

    const body: Record<string, unknown> = {
      id: projectData.id ?? '',
      projectName: form.projectName,
      isActive: form.isActive,
      isAutoActive: form.isAutoActive,
      isDicAbbrUsed: form.isDicAbbrUsed,
      tablePrefix: form.tablePrefix.trim(),
      dbType: form.dbType,
    };
    if (type === 'U') body.projectManagerId = form.projectManagerId;

    const response = await api<{ result: boolean }>('/api/saveProject/project', { method: type === 'C' ? 'POST' : 'PUT', body });
    if (response.result) onSaved();
  };

  return (
    <form id="editProjectForm" className="edit-form" onSubmit={onSubmit}>
      <div className="form-group">
        <label htmlFor="project-name">{t('프로젝트 명')}</label>
        <input type="text" id="project-name" className="form-control" required value={form.projectName} onChange={(e) => set('projectName', e.target.value)} />
      </div>

      {type === 'U' && (
        <div className="form-group">
          <label htmlFor="project-manager">{t('프로젝트 관리자')}</label>
          <select id="project-manager" className="form-control" value={form.projectManagerId} onChange={(e) => set('projectManagerId', e.target.value)}>
            {activeMembers.map((member) => (
              <option key={member.accountId} value={member.accountId}>{member.name}</option>
            ))}
          </select>
        </div>
      )}

      <div className="gh-form-label">{t('설정')}</div>
      {([
        ['isActive', 'project-active', t('활성화 여부')],
        ['isAutoActive', 'project-autoactive', t('사용자 자동 승인 여부')],
        ['isDicAbbrUsed', 'project-dicAbbrUsed', t('데이터 사전 약어 사용 여부')],
      ] as const).map(([key, id, label]) => (
        <div className="form-check" style={{ marginTop: 10, marginBottom: 10 }} key={key}>
          <input type="checkbox" id={id} className="form-check-input" checked={form[key]} onChange={(e) => set(key, e.target.checked)} />
          <label className="form-check-label" htmlFor={id}>{label}</label>
        </div>
      ))}

      <div className="form-group">
        <label htmlFor="project-tablePrefix">{t('테이블명 접두어')}</label>
        <input type="text" id="project-tablePrefix" className="form-control gh-mono" maxLength={20} placeholder={t('예) TB_')} autoComplete="off"
          value={form.tablePrefix} onChange={(e) => set('tablePrefix', e.target.value)} />
        <p className="form-help">{t('테이블 등록 시 표준 단어로 만든 테이블명 앞에 붙습니다. (예: TB_ + USE_HIST_MGMT) 비워두면 붙이지 않습니다.')}</p>
      </div>

      <div className="form-group">
        <label htmlFor="project-dbType">{t('대상 DB')}</label>
        <select id="project-dbType" className="form-control" value={form.dbType} onChange={(e) => set('dbType', e.target.value)}>
          {DB_TYPES.map((db) => (
            <option key={db.value} value={db.value}>{db.label}</option>
          ))}
        </select>
        <p className="form-help">{t('테이블 관리 > 테이블 상세의 DDL 을 이 DB 문법으로 생성합니다.')}</p>
      </div>

      {canSave && (
        <div className="gh-dialog-actions">
          <button type="submit" id="btn-save-project" className="btn btn-primary">{t('저장')}</button>
        </div>
      )}
    </form>
  );
}
