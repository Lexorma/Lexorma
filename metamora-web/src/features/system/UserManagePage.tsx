import { useQuery } from '@tanstack/react-query';
import { userColumns } from './columns';
import { useMemo, useRef } from 'react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, Mono, User } from '../../components/grid/cells';
import { Grid, type GridHandle } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { api } from '../../lib/api';

/** 사용자 관리 : 권한 변경 / 비밀번호 초기화 / 삭제 */
export function UserManagePage() {
  const dialog = useDialog();
  const grid = useRef<GridHandle>(null);
  const roles = useQuery({
    queryKey: ['user-roles'],
    queryFn: () => api<string[]>('/api/selectUser/roles'),
    staleTime: Infinity,
  });
  const columns = useMemo(() => roles.data
    ? userColumns.map((column) => column.column === 'role' ? { ...column, options: roles.data } : column)
    : [], [roles.data]);

  const save = () => {
    dialog.confirm('변경 데이터를 저장하시겠습니까?', async () => {
      const data = grid.current?.getEditedRows() ?? [];
      const response = await api<{ result: boolean }>('/api/saveUser/update', { method: 'PUT', body: data });
      if (response.result) dialog.alert('정상적으로 저장되었습니다.', () => grid.current?.reload());
    });
  };

  const remove = () => {
    const checked = grid.current?.getCheckedOrAlert();
    if (!checked) return;
    dialog.confirm('선택한 정보를 삭제하시겠습니까?', async () => {
      const response = await api<{ result: boolean }>('/api/deleteUser/user', { method: 'DELETE', body: checked });
      if (response.result) dialog.alert('정상적으로 삭제되었습니다.', () => grid.current?.reload());
    });
  };

  // 초기화 버튼 : 아이디와 같은 값으로 비밀번호 초기화
  const resetPassword = (field: string, row: Record<string, any>) => {
    if (field !== 'resetButton') return;
    dialog.confirm(`해당 ${row.username}의 비밀번호를 초기화 하시겠습니까? 아이디와 동일한 값으로 비밀번호가 초기화됩니다.`, async () => {
      const response = await api<{ result: boolean }>('/api/saveUser/password', { method: 'PUT', body: row });
      if (response.result) dialog.alert('정상적으로 초기화되었습니다.', () => grid.current?.reload());
    });
  };

  return (
    <>
      <PageHeader category="시스템 관리" title="사용자 관리" description="사용자의 권한을 변경하거나 비밀번호를 초기화합니다." />
      <div id="gridContainer">
        <Grid
          ref={grid}
          id="userTable"
          columns={columns}
          dataUrl="/api/selectUser"
          crudActions="ud"
          onAction={{ save, delete: remove }}
          onCellButton={resetPassword}
          renderCell={(field, row) => {
            if (field === 'username') return <Mono>{row.username}</Mono>;
            if (field === 'name') return <User name={row.name} />;
            if (field === 'roleName' && row.roleName) return <Label variant="done">{row.roleName}</Label>;
            return undefined;
          }}
        />
      </div>
    </>
  );
}
