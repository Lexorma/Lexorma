import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { PencilIcon } from '@primer/octicons-react';
import { api, enc } from '../../lib/api';
import { formatDateTime, initial } from '../../lib/format';

interface NoticeAccount {
  id: string;
  username: string;
  name: string;
}

interface Notice {
  id: number;
  title: string;
  content: string;
  username: string;
  updatedTime: string | null;
  accounts: NoticeAccount[];
}

interface NoticeProject {
  id: number;
  projectName: string;
  isSelected?: boolean;
}

/**
 * 공지사항 상세 / 등록·수정 팝업 (대시보드, 공지사항 목록에서 사용)
 */
export function useNoticeDialogs() {
  const dialog = useDialog();

  /** 상세 보기 : 열면 조회자로 기록된다. onClose 에서 목록을 다시 읽으면 읽음 표시가 갱신된다. */
  const openNoticeDetail = (id: number, { onClose }: { onClose?: () => void } = {}) => {
    dialog.popup({ title: '공지사항', size: 'large', onClose, content: <NoticeDetail id={id} /> });
  };

  /** 등록(add) / 수정(modify) : 저장하면 닫힌 뒤 onSuccess */
  const openNoticeSave = ({ type, id, onSuccess }: { type: 'add' | 'modify'; id?: number; onSuccess?: () => void }) => {
    dialog.popup({
      title: type === 'modify' ? '공지사항 수정' : '공지사항 등록',
      size: 'large',
      content: (controls: DialogControls) => <NoticeSaveForm type={type} id={id} controls={controls} onSuccess={onSuccess} />,
    });
  };

  return { openNoticeDetail, openNoticeSave };
}

/** 공지사항 상세 (GitHub 이슈 화면 형태) */
function NoticeDetail({ id }: { id: number }) {
  const { data: notice } = useQuery({
    queryKey: ['notice-view', id],
    queryFn: () => api<Notice>(`/api/selectNotice/view/${enc(id)}`),
    gcTime: 0,
  });

  if (!notice) return <div className="popup-container notice-view"><p className="gh-muted">불러오는 중입니다.</p></div>;

  const accounts = notice.accounts ?? [];
  return (
    <div className="popup-container notice-view">
      <header className="notice-view-header">
        <h1 className="notice-view-title">{notice.title}</h1>
        <div className="notice-view-meta">
          <span className="gh-label gh-label-accent gh-label-dot">공지</span>
          <span>
            <strong>{notice.username}</strong>님이
            {notice.updatedTime && <time> {formatDateTime(notice.updatedTime)}</time>}에 작성
          </span>
          <span className="notice-view-sep" aria-hidden="true">·</span>
          <span>조회 <span>{accounts.length}</span>명</span>
        </div>
      </header>

      <div className="notice-view-layout">
        <article className="notice-box">
          <div className="notice-box-header">
            <span className="gh-avatar">{initial(notice.username)}</span>
            <strong>{notice.username}</strong>
            <span className="gh-muted">작성</span>
          </div>
          <div className="notice-box-body">{notice.content}</div>
        </article>

        <aside className="notice-side">
          <h2 className="notice-side-title">
            조회자 <span className="notice-counter">{accounts.length}</span>
          </h2>
          {accounts.length > 0 ? (
            <ul className="notice-readers">
              {accounts.map((account) => (
                <li key={account.id} className="gh-user">
                  <span className="gh-avatar">{initial(account.name)}</span>
                  <span className="notice-reader-name">{account.name}</span>
                  <span className="gh-muted">{account.username}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="notice-side-empty">아직 조회한 사용자가 없습니다.</p>
          )}
        </aside>
      </div>
    </div>
  );
}

/** 공지사항 등록/수정 (GitHub 새 이슈 작성 화면 형태) */
function NoticeSaveForm({ type, id, controls, onSuccess }: { type: 'add' | 'modify'; id?: number; controls: DialogControls; onSuccess?: () => void }) {
  const dialog = useDialog();
  const [editing, setEditing] = useState(type === 'add');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [projectIds, setProjectIds] = useState<Set<number>>(new Set());

  const { data: projects } = useQuery({
    queryKey: ['notice-projects', type, id],
    queryFn: () => api<NoticeProject[]>(`/api/selectNotice/projects?type=${type}${id ? `&id=${enc(id)}` : ''}`),
    gcTime: 0,
  });

  useEffect(() => {
    if (projects) setProjectIds(new Set(projects.filter((p) => p.isSelected).map((p) => p.id)));
  }, [projects]);

  useEffect(() => {
    if (type !== 'modify' || !id) return;
    api<{ result: boolean; board: { title: string; content: string } }>(`/api/selectNotice/detail/${enc(id)}`).then((response) => {
      if (response.result) {
        setTitle(response.board.title ?? '');
        setContent(response.board.content ?? '');
      }
    });
  }, [type, id]);

  const save = async () => {
    if (!title.trim()) return dialog.alert('공지사항 제목은(는) 필수 입력 항목입니다.');
    if (!content.trim()) return dialog.alert('공지사항 내용은(는) 필수 입력 항목입니다.');

    const response = await api<{ result: boolean }>('/api/saveNotice', {
      method: type === 'add' ? 'POST' : 'PUT',
      body: { id: id ?? '', title, content, projectIds: [...projectIds].map(String) },
    });
    if (response.result) {
      controls.close();
      onSuccess?.();
    }
  };

  return (
    <div className="popup-container notice-edit">
      <header className="notice-edit-header">
        <p className="gh-muted">프로젝트 멤버에게 공지할 내용을 작성합니다.</p>
      </header>

      <form id="editNoticeForm" className="notice-form" onSubmit={(e) => e.preventDefault()}>
        {projects && (
          <div className="notice-field">
            <span className="notice-label">공지 대상 프로젝트</span>
            <div id="notice-project" className="notice-project-list">
              {projects.map((project) => (
                <label key={project.id} className="notice-project-item" htmlFor={`project_${project.id}`}>
                  <input
                    className="form-check-input"
                    type="checkbox"
                    id={`project_${project.id}`}
                    checked={projectIds.has(project.id)}
                    disabled={type === 'modify'}
                    onChange={(e) =>
                      setProjectIds((prev) => {
                        const next = new Set(prev);
                        if (e.target.checked) next.add(project.id);
                        else next.delete(project.id);
                        return next;
                      })
                    }
                  />
                  <span>{project.projectName}</span>
                </label>
              ))}
              {projects.length === 0 && <p className="notice-project-empty gh-muted">관리 중인 프로젝트가 없습니다.</p>}
            </div>
          </div>
        )}

        <div className="notice-field">
          <label className="notice-label" htmlFor="title">제목 <span className="notice-required" aria-hidden="true">*</span></label>
          <input type="text" id="title" className="form-control" placeholder="제목을 입력하세요" required readOnly={!editing}
            value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>

        <div className="notice-field">
          <label className="notice-label" htmlFor="content">내용 <span className="notice-required" aria-hidden="true">*</span></label>
          <textarea id="content" className="form-control notice-textarea" placeholder="공지 내용을 입력하세요" readOnly={!editing}
            value={content} onChange={(e) => setContent(e.target.value)} />
        </div>
      </form>

      <div className="notice-actions">
        <button type="button" className="btn" onClick={controls.close}>닫기</button>
        {!editing && (
          <button type="button" className="btn" id="edit-button" onClick={() => { setEditing(true); controls.setTitle('공지사항 수정'); }}>
            <PencilIcon />
            편집
          </button>
        )}
        {editing && (
          <button type="button" className="btn btn-primary" id="save-button" onClick={save}>저장</button>
        )}
      </div>
    </div>
  );
}
