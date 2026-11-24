import { t } from '../../lib/i18n';

/** 순서 비교의 한 항목 (컬럼·속성) */
export interface OrderItem {
  id: number;
  label: string;
}

/**
 * 순서 변경 409 복구 (M8-C4c-2 10.6~10.8 — M8-C3 원칙을 순서에 맞춘 것). 순서는 칸 하나씩 다시 적용하면 전체 순서가 틀어지므로 자동 병합하지 않는다 :
 * 항목마다 편집 시작 시 · 현재 서버 · 내 순서를 나란히 보여 주고(추가·삭제된 항목 표시), 사용자가 [최신 순서에서 다시 정하기] 를 누르면 최신 구성·최신 기준값으로
 * 다시 편집한다 (내 순서는 참고로만 보이고, 지워진 항목을 되살리거나 새 항목을 빼지 않는다). 저장은 사용자가 다시 누른다
 */
export function OrderConflict({ title, stage, message, gone, before, latest, mine, onLoad, onRestart }: {
  title: string;
  stage: 'conflict' | 'loading' | 'loaded' | 'failed';
  message?: string;
  /** 대상이 없어졌거나 권한이 없다 — 다시 저장할 수 없다 */
  gone?: boolean;
  before: OrderItem[];
  latest: OrderItem[];
  mine: OrderItem[];
  onLoad: () => void;
  onRestart?: () => void;
}) {
  const position = (list: OrderItem[], id: number) => {
    const index = list.findIndex((i) => i.id === id);
    return index < 0 ? null : index + 1;
  };
  // 행 = 지금 서버 구성 순서 + (편집 시작 시에만 있던) 그 사이 지워진 항목
  const rows = [...latest, ...before.filter((b) => !latest.some((l) => l.id === b.id))];
  return (
    <div className="conflict-notice" role="alert">
      <strong>{title}</strong>
      <p>{t('바꾼 순서는 그대로입니다. 최신 순서를 확인한 뒤 최신 구성에서 다시 정해 저장해 주세요.')}</p>
      {stage === 'conflict' && <div className="conflict-actions"><button type="button" className="btn btn-sm btn-primary" onClick={onLoad}>{t('최신 내용 확인')}</button></div>}
      {stage === 'loading' && <p role="status">{t('최신 내용을 불러오는 중입니다.')}</p>}
      {stage === 'failed' && (
        <>
          <p className="conflict-error">{message}</p>
          {!gone && <div className="conflict-actions"><button type="button" className="btn btn-sm" onClick={onLoad}>{t('다시 불러오기')}</button></div>}
        </>
      )}
      {stage === 'loaded' && (
        <>
          <div className="conflict-table-wrap">
            <table className="conflict-table">
              <thead>
                <tr>
                  <th scope="col">{t('항목')}</th>
                  <th scope="col">{t('편집 시작 시')}</th>
                  <th scope="col">{t('현재 서버 값')}</th>
                  <th scope="col">{t('내 변경')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const b = position(before, row.id);
                  const l = position(latest, row.id);
                  const m = position(mine, row.id);
                  const changed = b !== l;
                  return (
                    <tr key={row.id}>
                      <th scope="row">{row.label}</th>
                      <td>{b ?? <span className="gh-muted">{t('없음 (새로 추가됨)')}</span>}</td>
                      <td className={changed ? 'conflict-changed' : undefined}>
                        {l ?? t('삭제됨')}
                        {changed && <span className="conflict-mark">{b == null ? t('추가됨') : l == null ? t('삭제됨') : t('다른 사용자 변경')}</span>}
                      </td>
                      <td className={m !== b ? 'conflict-mine' : undefined}>{m ?? <span className="gh-muted">—</span>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="gh-muted">{t('순서는 자동으로 합치지 않습니다. 최신 순서에서 다시 정하면 지금 서버의 항목과 순서로 다시 시작하고, 내가 정했던 순서는 참고로만 보입니다.')}</p>
          {onRestart && <div className="conflict-actions"><button type="button" className="btn btn-sm btn-primary" onClick={onRestart}>{t('최신 순서에서 다시 정하기')}</button></div>}
        </>
      )}
    </div>
  );
}
