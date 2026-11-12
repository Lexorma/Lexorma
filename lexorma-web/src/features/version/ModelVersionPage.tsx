import { useRef } from 'react';
import { DateTime, Muted, User } from '../../components/grid/cells';
import { Grid, type GridHandle } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { t } from '../../lib/i18n';
import '../../styles/history.css';
import { modelVersionColumns } from './columns';
import { scaleText } from './model';
import { useVersionDialogs } from './versionDialogs';

/**
 * 모델 버전 관리 (M-9 3차) : 사용자가 명시적으로 저장한 시점의 모델 전체 구조를 조회하고 비교한다.
 * 저장한 버전은 고치거나 지울 수 없다 (화면에도, 서버에도 그런 기능이 없다).
 */
export function ModelVersionPage() {
  const grid = useRef<GridHandle>(null);
  const { canView } = useAuth();
  const { openCreate, openDetail, openCompare } = useVersionDialogs();

  return (
    <>
      <PageHeader category={t('데이터 품질 관리')} title={t('모델 버전 관리')}
        description={t('지금의 모델 전체(스키마, 물리·논리 모델, 논리·물리 매핑)를 이름을 붙여 저장하고, 저장한 버전끼리 또는 현재 모델과 비교합니다. 행을 누르면 저장된 내용을 볼 수 있습니다.')} />

      <div id="gridContainer">
        <Grid
          ref={grid}
          id="modelVersionGrid"
          columns={modelVersionColumns}
          dataUrl="/api/modelVersion"
          emptyText={t('아직 저장한 버전이 없습니다. [버전 생성] 으로 현재 모델을 저장하면 여기에 남습니다.')}
          extraActions={
            <>
              <button type="button" className="btn" onClick={() => openCompare()}>{t('버전 비교')}</button>
              {canView('model-version-create') && (
                <button type="button" className="grd-add btn btn-primary" onClick={() => openCreate(() => grid.current?.reload())}>{t('버전 생성')}</button>
              )}
            </>
          }
          onRowClick={(row) => openDetail(row.id)}
          renderCell={(field, row) => {
            switch (field) {
              case 'versionName':
                return <span className="version-clamp" title={row.versionName}>{row.versionName}</span>;
              case 'description':
                return row.description ? <span className="version-clamp" title={row.description}>{row.description}</span> : <Muted>-</Muted>;
              case 'counts':
                return scaleText(row.counts);
              case 'createdBy':
                return <User name={row.createdBy} />;
              case 'createdAt':
                return <DateTime value={row.createdAt} />;
              default:
                return undefined;
            }
          }}
        />
      </div>
    </>
  );
}
