import { useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, User } from '../../components/grid/cells';
import { Grid, type GridHandle } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { api } from '../../lib/api';
import { useNoticeDialogs } from './noticeDialogs';
import { noticeColumns } from './columns';

/** 공지사항 목록 : 안 읽은 글은 파란 점 + 굵은 제목 (GitHub 알림 목록 형태) */
export function NoticeListPage() {
  const dialog = useDialog();
  const grid = useRef<GridHandle>(null);
  const { openNoticeDetail, openNoticeSave } = useNoticeDialogs();

  // 현재 프로젝트의 프로젝트 관리자만 공지사항 추가/삭제 가능
  const { data: permission } = useQuery({
    queryKey: ['notice-permission'],
    queryFn: () => api<{ isProjectManager: boolean }>('/api/selectNotice/permission'),
  });
  if (!permission) return null;

  const saved = () => dialog.alert('정상적으로 공지사항이 저장되었습니다.', () => grid.current?.reload());

  const remove = () => {
    const checked = grid.current?.getCheckedOrAlert();
    if (!checked) return;
    dialog.confirm('선택한 공지사항을 삭제하시겠습니까?', async () => {
      const response = await api<{ result: boolean }>('/api/deleteNotice', { method: 'DELETE', body: checked });
      if (response.result) dialog.alert('선택한 공지사항이 삭제되었습니다.', () => grid.current?.reload());
    });
  };

  return (
    <>
      <PageHeader category="운영 관리" title="공지사항" description="프로젝트 공지사항을 확인합니다. 제목을 누르면 상세 내용을 볼 수 있습니다." />
      <Grid
        ref={grid}
        id="noticeList"
        columns={noticeColumns}
        dataUrl="/api/selectNotice"
        crudActions={permission.isProjectManager ? 'cd' : ''}
        onAction={{ add: () => openNoticeSave({ type: 'add', onSuccess: saved }), delete: remove }}
        onRowClick={(row, _columns, isManager) => {
          // 매니저일 경우 수정, 아니면 상세보기 (닫으면 읽음 표시를 갱신한다)
          if (isManager) openNoticeSave({ type: 'modify', id: row.id, onSuccess: saved });
          else openNoticeDetail(row.id, { onClose: () => grid.current?.reload() });
        }}
        rowClassName={(row) => (row.isHit !== '확인' ? 'notice-unread' : undefined)}
        renderCell={(field, row) => {
          const isUnread = row.isHit !== '확인';
          if (field === 'title') {
            return (
              <span className="notice-title">
                <span className="notice-dot" title={isUnread ? '미확인' : '확인'} />
                <span className="notice-title-text">{row.title ?? ''}</span>
              </span>
            );
          }
          if (field === 'username') return <User name={row.username} />;
          if (field === 'isHit') return isUnread ? <Label variant="accent">새 글</Label> : <Label>읽음</Label>;
          return undefined;
        }}
      />
    </>
  );
}
