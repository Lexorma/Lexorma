import { DateTime, Label, Mono, Muted, User, type LabelVariant } from '../../components/grid/cells';
import { Grid } from '../../components/grid/Grid';
import { useDialog } from '../../components/dialog/DialogProvider';
import { PageHeader } from '../../components/layout/PageHeader';
import { isBlank } from '../../lib/format';
import { systemLogColumns } from './columns';

const METHOD_VARIANT: Record<string, LabelVariant> = { GET: 'accent', POST: 'success', PUT: 'attention', PATCH: 'attention', DELETE: 'danger' };

/** HTTP 상태코드 → 라벨 색상 (2xx 초록, 3xx 파랑, 4xx 노랑, 5xx 빨강) */
function statusVariant(code: unknown): LabelVariant {
  const n = Number(code);
  if (n >= 500) return 'danger';
  if (n >= 400) return 'attention';
  if (n >= 300) return 'accent';
  if (n >= 200) return 'success';
  return '';
}

// 상세보기에 표시할 항목
const DETAIL_FIELDS = [
  { field: 'requestBody', label: '요청 데이터' },
  { field: 'responseBody', label: '응답 데이터' },
  { field: 'errorMessage', label: '에러 메시지' },
  { field: 'userAgent', label: '브라우저 정보' },
];

/** JSON 문자열이면 보기 좋게 정렬하고, 값이 없으면 '-' */
function formatValue(value: unknown): string {
  if (isBlank(value)) return '-';
  try {
    return JSON.stringify(JSON.parse(String(value)), null, 2);
  } catch {
    return String(value);
  }
}

/** 시스템 로그 : 요청 이력 조회, 상세보기에서 요청/응답 데이터 확인 */
export function SystemLogPage() {
  const dialog = useDialog();

  const openDetail = (field: string, row: Record<string, any>) => {
    if (field !== 'detailButton') return;
    dialog.open({
      title: `${row.method ?? ''} ${row.logUrl ?? ''}`,
      className: 'dialog-wide',
      content: (
        <div>
          {DETAIL_FIELDS.map((item) => (
            <div key={item.field}>
              <label className="dialog-detail-label">{item.label}</label>
              <pre className="dialog-detail-value">{formatValue(row[item.field])}</pre>
            </div>
          ))}
        </div>
      ),
    });
  };

  return (
    <>
      <PageHeader category="시스템 관리" title="시스템 로그" description="프로젝트에서 발생한 요청 이력을 조회합니다. 상세보기에서 요청/응답 데이터를 확인할 수 있습니다." />
      <div id="gridContainer">
        <Grid
          id="logTable"
          columns={systemLogColumns}
          dataUrl="/api/getSystemLog"
          defaultSort={{ column: 'id', desc: true }}
          onCellButton={openDetail}
          renderCell={(field, row) => {
            switch (field) {
              case 'method': {
                const method = String(row.method ?? '').toUpperCase();
                return method ? <Label variant={METHOD_VARIANT[method] ?? ''}>{method}</Label> : undefined;
              }
              case 'statusCode':
                return isBlank(row.statusCode) ? undefined : <Label variant={statusVariant(row.statusCode)} dot>{String(row.statusCode)}</Label>;
              case 'logUrl':
                return <Mono>{row.logUrl}</Mono>;
              case 'ip':
                return <Mono>{row.ip}</Mono>;
              case 'username':
                return <User name={row.username} />;
              case 'roleName':
                return row.roleName ? <Label>{row.roleName}</Label> : <Muted>-</Muted>;
              case 'timestamp':
                return <DateTime value={row.timestamp} />;
              default:
                return undefined;
            }
          }}
        />
      </div>
    </>
  );
}
