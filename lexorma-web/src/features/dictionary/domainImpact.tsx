import { useCallback, useEffect, useState } from 'react';
import type { DialogApi, DialogControls } from '../../components/dialog/DialogProvider';
import { api, ApiError } from '../../lib/api';
import { t } from '../../lib/i18n';

/** `GET /api/dictionaryUsage/domain/{id}/impact` 응답 (M8-C4c-1 C4c-D05) */
export interface DomainImpact {
  domainId: number;
  domainName: string;
  isApproval: boolean | null;
  termCount: number;
  approvedTermCount: number;
  attributeCount: number;
  columnCount: number;
  approvedColumnCount: number;
}

/** 한 번에 보내는 조회 수 (그리드 한 페이지는 최대 100행 — 100건을 한꺼번에 보내지 않는다) */
const BATCH = 10;

/**
 * 표준 도메인 승인 취소 전 영향 범위 안내 (M8-C4c-2 C4c-2E). 승인 취소는 참조(용어·속성·컬럼)와 그 승인 상태를 바꾸지 않는다 — 안내용이고,
 * 조회가 끝나야 [승인 취소] 를 누를 수 있다 (조회 실패를 0건으로 보이지 않고, 확인하지 못한 채 승인을 취소하지 않는다). 건수는 종류마다 따로 보이고 합치지 않는다
 */
export function openDomainRevokeImpact(dialog: DialogApi, domains: { id: number; name: string }[], onProceed: () => void) {
  dialog.open({
    title: t('도메인 승인 취소 영향 범위'),
    className: 'dialog-wide',
    content: (controls) => <DomainRevokeImpact domains={domains} controls={controls} onProceed={onProceed} />,
  });
}

function DomainRevokeImpact({ domains, controls, onProceed }: { domains: { id: number; name: string }[]; controls: DialogControls; onProceed: () => void }) {
  const [state, setState] = useState<{ kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'loaded'; impacts: DomainImpact[] }>({ kind: 'loading' });

  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    try {
      const impacts: DomainImpact[] = [];
      for (let from = 0; from < domains.length; from += BATCH) {
        impacts.push(...await Promise.all(domains.slice(from, from + BATCH).map((d) => api<DomainImpact>(`/api/dictionaryUsage/domain/${d.id}/impact`, { silent: true }))));
      }
      setState({ kind: 'loaded', impacts });
    } catch (error) {
      setState({ kind: 'failed', message: error instanceof ApiError ? t('영향 범위를 확인하지 못했습니다 : {0}', error.message) : t('영향 범위를 확인하지 못했습니다.') });
    }
  }, [domains]);

  useEffect(() => { void load(); }, [load]);

  return (
    <div className="conflict-review">
      <div className="conflict-notice">
        <strong>{t('승인을 취소해도 이 도메인을 참조하는 표준 용어·논리 속성·물리 컬럼과 그 승인 상태는 그대로 유지됩니다.')}</strong>
        <p>{t('참조는 삭제되지 않습니다. 아래 건수는 지금 시점의 참고 정보이며, 종류마다 따로 셉니다 (논리 속성·물리 컬럼은 이 도메인의 표준 용어를 통해 간접 참조합니다).')}</p>
      </div>
      {state.kind === 'loading' && <p role="status">{t('영향 범위를 확인하는 중입니다.')}</p>}
      {state.kind === 'failed' && <p className="conflict-error" role="alert">{state.message}</p>}
      {state.kind === 'loaded' && (
        <div className="conflict-table-wrap">
          <table className="conflict-table">
            <thead>
              <tr>
                <th scope="col">{t('도메인')}</th>
                <th scope="col">{t('직접 참조 표준 용어')}</th>
                <th scope="col">{t('간접 참조 논리 속성')}</th>
                <th scope="col">{t('간접 참조 물리 컬럼')}</th>
              </tr>
            </thead>
            <tbody>
              {state.impacts.map((i) => (
                <tr key={i.domainId}>
                  <th scope="row">{i.domainName}</th>
                  <td>{t('{0}개 (승인 {1}개)', i.termCount, i.approvedTermCount)}</td>
                  <td>{t('{0}개', i.attributeCount)}</td>
                  <td>{t('{0}개 (승인 {1}개)', i.columnCount, i.approvedColumnCount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="popup-button-container">
        {state.kind === 'loaded' && <button type="button" className="confirm" onClick={() => { controls.close(); onProceed(); }}>{t('승인 취소')}</button>}
        {state.kind === 'failed' && <button type="button" className="confirm" onClick={() => void load()}>{t('다시 조회')}</button>}
        <button type="button" className="close" onClick={controls.close}>{t('닫기')}</button>
      </div>
    </div>
  );
}
