import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Approval, Label, Mono, Muted } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { TableLinks } from '../dictionary/DictionaryUsage';
import { useSchemas } from '../schema/schemas';

type Severity = 'ERROR' | 'WARNING';
type IssueType = 'FK_TARGET_NOT_FOUND' | 'FK_TARGET_AMBIGUOUS' | 'FK_TARGET_NO_PK' | 'FK_TARGET_COMPOSITE_PK' | 'FK_RELATION_NOT_KEY'
  | 'INDEX_SAME_DEFINITION' | 'INDEX_SAME_AS_PK' | 'INDEX_PREFIX_OF_OTHER';

interface Issue {
  id: string; type: IssueType; severity: Severity; schemaId: number; schemaName: string; tableId: number; tableName: string;
  objectName: string; related: string | null; isApproval: boolean | null;
}
interface Result { summary: { tables: number; errors: number; warnings: number }; issues: Issue[] }

const TYPE_LABEL: Record<IssueType, string> = {
  FK_TARGET_NOT_FOUND: t('참조 테이블 없음'),
  FK_TARGET_AMBIGUOUS: t('참조 테이블이 여러 스키마에 있음'),
  FK_TARGET_NO_PK: t('참조 테이블에 PK 없음'),
  FK_TARGET_COMPOSITE_PK: t('참조 테이블 PK 가 복합키'),
  FK_RELATION_NOT_KEY: t('관계 대상이 PK·고유 키가 아님'),
  INDEX_SAME_DEFINITION: t('같은 정의의 인덱스'),
  INDEX_SAME_AS_PK: t('PK 와 같은 인덱스'),
  INDEX_PREFIX_OF_OTHER: t('다른 인덱스의 앞부분과 같음'),
};

/**
 * 모델 구조 진단 (DEC-09 무효 FK · DEC-12 중복 인덱스, 조회 전용). 무효 FK 는 오류(DDL 에서 FK 를 만들지 않는다), 중복 인덱스는 경고.
 * 모델 수정을 막지 않는다 — 행의 링크로 테이블 상세·물리 ERD 에서 고친다.
 */
export function ModelDiagnosticsPage() {
  const { user } = useAuth();
  const { schemas } = useSchemas();
  const [schemaId, setSchemaId] = useState('');
  const [severity, setSeverity] = useState<Severity | ''>('');
  const result = useQuery({
    queryKey: ['model-diagnostics', user?.projectId, schemaId],
    queryFn: () => api<Result>(`/api/physical/diagnostics${schemaId ? `?schemaId=${encodeURIComponent(schemaId)}` : ''}`),
  });
  const issues = (result.data?.issues ?? []).filter((i) => !severity || i.severity === severity);
  const value = (n: number | undefined) => (n == null ? '-' : n);

  return (
    <>
      <PageHeader category={t('데이터 품질 관리')} title={t('모델 구조 진단')}
        description={t('무효 FK(오류)와 중복 인덱스(경고)를 진단합니다. 조회만 하며 모델을 바꾸지 않습니다. 무효 FK 는 DDL 에서 만들어지지 않습니다.')} />
      {result.isError && <p className="gh-muted" role="alert">{t('진단 결과를 불러오지 못했습니다.')}</p>}
      <div className="dash dash-stats quality-stats">
        <div className="dash-stat">
          <div className="dash-stat-label">{t('검사 대상 테이블')}</div>
          <div className="dash-stat-value">{value(result.data?.summary.tables)}</div>
        </div>
        <button type="button" className="dash-stat" aria-pressed={severity === ''} onClick={() => setSeverity('')}>
          <div className="dash-stat-label">{t('전체')}</div>
          <div className="dash-stat-value">{value(result.data?.issues.length)}</div>
        </button>
        <button type="button" className="dash-stat" aria-pressed={severity === 'ERROR'} onClick={() => setSeverity('ERROR')}>
          <div className="dash-stat-label">{t('무효 FK (오류)')}</div>
          <div className={`dash-stat-value${result.data?.summary.errors ? ' is-pending' : ''}`}>{value(result.data?.summary.errors)}</div>
        </button>
        <button type="button" className="dash-stat" aria-pressed={severity === 'WARNING'} onClick={() => setSeverity('WARNING')}>
          <div className="dash-stat-label">{t('중복 인덱스 (경고)')}</div>
          <div className="dash-stat-value">{value(result.data?.summary.warnings)}</div>
        </button>
      </div>

      <div className="Box" style={{ padding: 12 }}>
        <select className="search-column-select" aria-label={t('스키마')} value={schemaId} onChange={(e) => setSchemaId(e.target.value)}>
          <option value="">{t('전체 스키마')}</option>
          {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
        </select>
        {result.isLoading ? <p className="gh-muted">{t('불러오는 중…')}</p> : issues.length === 0 ? (
          <p className="gh-muted">{severity || schemaId ? t('조건에 맞는 진단 결과가 없습니다.') : t('진단 결과가 없습니다. 무효 FK·중복 인덱스가 없습니다.')}</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>{t('구분')}</th><th>{t('진단 유형')}</th><th>{t('스키마')}</th><th>{t('테이블')}</th><th>{t('대상')}</th><th>{t('관련 객체')}</th><th>{t('승인')}</th><th>{t('바로가기')}</th></tr>
              </thead>
              <tbody>
                {issues.map((i) => (
                  <tr key={i.id}>
                    <td><Label variant={i.severity === 'ERROR' ? 'danger' : 'attention'}>{i.severity === 'ERROR' ? t('오류') : t('경고')}</Label></td>
                    <td>{TYPE_LABEL[i.type] ?? i.type}</td>
                    <td><Mono>{i.schemaName}</Mono></td>
                    <td><Mono>{i.tableName}</Mono></td>
                    <td><Mono>{i.objectName}</Mono></td>
                    <td>{i.related ? <Mono>{i.related}</Mono> : <Muted>-</Muted>}</td>
                    <td>{i.isApproval == null ? <Muted>-</Muted> : <Approval value={i.isApproval ? 'Y' : 'N'} />}</td>
                    <td><TableLinks tableId={i.tableId} schemaId={i.schemaId} onChanged={() => void result.refetch()} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}
