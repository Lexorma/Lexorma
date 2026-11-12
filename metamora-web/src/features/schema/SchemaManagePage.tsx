import { useState, type FormEvent } from 'react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import { useSchemas, type Schema } from './schemas';

/** 스키마 관리 : 프로젝트의 스키마 목록 조회, 승인 권한자(관리자·매니저·프로젝트 관리자)의 등록·수정·삭제 */
export function SchemaManagePage() {
  const dialog = useDialog();
  const { approvalAvailable } = useAuth();
  const { schemas, isPending, isError, refresh } = useSchemas();

  const openForm = (schema?: Schema) => {
    dialog.open({
      title: schema ? t('스키마 수정') : t('스키마 등록'),
      content: ({ close }: DialogControls) => <SchemaForm schema={schema} onSaved={() => { close(); void refresh(); }} />,
    });
  };

  const remove = (schema: Schema) => {
    dialog.confirm(t('{0} 스키마를 삭제하시겠습니까?', schema.schemaName), async () => {
      const response = await api<{ result: boolean }>(`/api/schema/${enc(schema.id)}`, { method: 'DELETE' });
      if (response.result) dialog.alert(t('정상적으로 삭제되었습니다.'), () => void refresh());
    });
  };

  // 삭제할 수 없는 이유 (서버에서도 다시 확인한다)
  const deleteBlocked = (schema: Schema) =>
    schema.isDefault ? t('기본 스키마는 삭제할 수 없습니다.') : schema.tableCount ? t('테이블이 있는 스키마는 삭제할 수 없습니다.') : '';

  const headers = [t('스키마명'), t('설명'), t('테이블 수'), t('등록일'), t('수정일'), t('등록자'), t('수정자'), ...(approvalAvailable ? [t('관리')] : [])];

  return (
    <>
      <PageHeader category={t('물리 모델 관리')} title={t('스키마 관리')}
        description={t('프로젝트의 스키마를 관리합니다. 테이블은 하나의 스키마에 속하며, 기본 스키마의 테이블은 DDL 에 스키마명을 붙이지 않습니다.')} />

      <div className="grid-header">
        <div className="button-container">
          {approvalAvailable && <button type="button" className="grd-add btn btn-primary" onClick={() => openForm()}>{t('추가')}</button>}
        </div>
      </div>

      <div className="dt-container">
        <div className="dt-layout-row dt-layout-table">
          <div className="dt-layout-cell dt-layout-full">
            <table id="schemaManageGrid" className="table table-striped table-bordered dataTable grid-fixed">
              {/* 컬럼 폭 고정 (공통 Grid 의 width 와 같은 방식) */}
              <colgroup>
                {(approvalAvailable ? ['16%', '24%', '8%', '13%', '13%', '8%', '8%', '10%'] : ['18%', '28%', '8%', '14%', '14%', '9%', '9%']).map((width, index) => <col key={index} style={{ width }} />)}
              </colgroup>
              <thead>
                <tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr>
              </thead>
              <tbody>
                {schemas.map((schema) => {
                  const blocked = deleteBlocked(schema);
                  return (
                    <tr key={schema.id}>
                      <td>
                        <Mono>{schema.schemaName}</Mono>
                        {schema.isDefault && <> <Label variant="accent">{t('기본')}</Label></>}
                      </td>
                      <td>{schema.schemaDescription || '-'}</td>
                      <td>{schema.tableCount ?? 0}</td>
                      <td>{formatDateTime(schema.created) || '-'}</td>
                      <td>{formatDateTime(schema.updated) || '-'}</td>
                      <td>{schema.createdBy || '-'}</td>
                      <td>{schema.updatedBy || '-'}</td>
                      {approvalAvailable && (
                        <td>
                          <div className="schema-actions">
                            <button type="button" className="btn btn-sm" onClick={() => openForm(schema)}>{t('수정')}</button>
                            <button type="button" className="btn btn-sm btn-danger" disabled={!!blocked} title={blocked || undefined} onClick={() => remove(schema)}>{t('삭제')}</button>
                          </div>
                        </td>
                      )}
                    </tr>
                  );
                })}
                {schemas.length === 0 && (
                  <tr>
                    <td colSpan={headers.length} className="dt-empty">
                      {isPending ? t('불러오는 중입니다.') : isError ? t('스키마 목록을 불러오지 못했습니다.') : t('등록된 스키마가 없습니다.')}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </>
  );
}

/** 스키마 등록 / 수정 : 수정은 이름·설명만 바꾼다 (내부 ID 는 그대로라 소속 테이블·관계·ERD 배치가 유지된다) */
function SchemaForm({ schema, onSaved }: { schema?: Schema; onSaved: () => void }) {
  const dialog = useDialog();
  const [name, setName] = useState(schema?.schemaName ?? '');
  const [description, setDescription] = useState(schema?.schemaDescription ?? '');

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    const schemaName = name.trim();
    if (!schemaName) return dialog.alert(t('스키마명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(schema ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), async () => {
      // 이름 형식·중복·권한은 서버에서 검증한다
      await api('/api/schema', { method: 'POST', body: { id: schema?.id ?? null, schemaName, schemaDescription: description.trim() || null } });
      dialog.alert(schema ? t('정상적으로 수정되었습니다.') : t('정상적으로 저장되었습니다.'), onSaved);
    });
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      <div className="form-group">
        <label htmlFor="schemaName">{t('스키마명')}</label>
        <input type="text" className="form-control gh-mono" id="schemaName" maxLength={63} autoComplete="off" required autoFocus={!schema?.isDefault}
          readOnly={schema?.isDefault} value={name} onChange={(e) => setName(e.target.value)} />
        <p className="table-name-help">
          {schema?.isDefault
            ? t('기본 스키마의 이름은 바꿀 수 없습니다.')
            : t('영문 또는 _ 로 시작하고 영문/숫자/_/$ 만 쓸 수 있습니다. (최대 63자) 이름을 바꾸면 소속 테이블의 DDL 접두어에 바로 반영됩니다.')}
        </p>
      </div>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="schemaDescription">{t('설명')}</label>
        <input type="text" className="form-control" id="schemaDescription" maxLength={255} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <button type="submit" className="btn btn-primary">{t('저장')}</button>
    </form>
  );
}
