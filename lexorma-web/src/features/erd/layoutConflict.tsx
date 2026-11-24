import { useState } from 'react';
import type { DialogApi } from '../../components/dialog/DialogProvider';
import { t } from '../../lib/i18n';

/**
 * ERD 배치 저장 409 (ERD_LAYOUT_CONFLICT — 같은 사용자의 다른 탭이 먼저 저장) 복구 (M8-C3 9장).
 * 배치는 사용자별이라 다른 사용자와는 충돌하지 않는다. 저장하지 않은 이동은 버리지 않고, 최신 서버 배치와 비교해
 * - 내가만 옮긴 노드 : 내 위치 유지
 * - 다른 탭만 옮긴 노드 : 최신 위치로 화면을 맞춘다 (논리 ERD 는 보이는 노드 좌표를 함께 보내므로, 그대로 두면 다른 탭의 이동을 덮어쓴다)
 * - 둘 다 옮긴 노드 : 노드마다 사용자가 고른다 (기본 내 위치)
 * 를 정한 뒤 최신 배치 버전으로 새 저장을 준비한다. 저장은 사용자가 [배치 저장] 을 다시 눌러야 한다 (자동 저장·자동 덮어쓰기 없음). 저장 형식·버전 필드는 그대로
 */

export type Point = { x: number; y: number };
export type Positions = Record<string, Point>;

const same = (a: Point | undefined, b: Point | undefined) => (!a && !b) || (!!a && !!b && Math.abs(a.x - b.x) < 0.5 && Math.abs(a.y - b.y) < 0.5);

export interface LayoutResolution {
  version: number | null;
  /** 저장할 내 위치 (계속 저장 대기) */
  keep: Positions;
  /** 화면에 맞출 최신 위치 (저장 대기에서 뺀다) */
  adopt: Positions;
  /** 다음 비교의 기준 = 최신 서버 배치 */
  base: Positions;
}

export function openLayoutConflict(dialog: DialogApi, input: {
  /** 화면이 불러온 배치 (편집 시작 시) */
  base: Positions;
  /** 저장하지 않은 내 이동 */
  mine: Positions;
  /** 지금 화면에 있는 노드 (다른 탭만 옮긴 노드를 화면에 맞출지 판단) */
  shown: Set<string>;
  latest: { version: number | null; nodes: Positions };
  name: (id: string) => string;
  onResolve: (resolution: LayoutResolution) => void;
}) {
  const moved = Object.keys(input.mine);
  const changedThere = (id: string) => !same(input.base[id], input.latest.nodes[id]);
  const conflicts = moved.filter((id) => changedThere(id) && !same(input.mine[id], input.latest.nodes[id]));
  const onlyMine = moved.filter((id) => !conflicts.includes(id));
  const onlyThere = [...input.shown].filter((id) => !moved.includes(id) && changedThere(id) && input.latest.nodes[id]);

  dialog.open({
    title: t('배치 저장 충돌'),
    className: 'dialog-wide',
    content: ({ close }) => <LayoutConflictView input={input} conflicts={conflicts} onlyMine={onlyMine} onlyThere={onlyThere} close={close} />,
  });
}

function LayoutConflictView({ input, conflicts, onlyMine, onlyThere, close }: {
  input: Parameters<typeof openLayoutConflict>[1];
  conflicts: string[];
  onlyMine: string[];
  onlyThere: string[];
  close: () => void;
}) {
  // 노드 ID → true 면 내 위치 유지 (기본)
  const [keepMine, setKeepMine] = useState<Record<string, boolean>>(() => Object.fromEntries(conflicts.map((id) => [id, true])));
  const fmt = (p: Point | undefined) => (p ? `(${Math.round(p.x)}, ${Math.round(p.y)})` : '—');

  const apply = () => {
    const keep: Positions = {};
    const adopt: Positions = {};
    onlyMine.forEach((id) => { keep[id] = input.mine[id]; });
    conflicts.forEach((id) => {
      if (keepMine[id]) keep[id] = input.mine[id];
      else if (input.latest.nodes[id]) adopt[id] = input.latest.nodes[id];
    });
    onlyThere.forEach((id) => { adopt[id] = input.latest.nodes[id]; });
    input.onResolve({ version: input.latest.version, keep, adopt, base: input.latest.nodes });
    close();
  };

  return (
    <div className="conflict-review">
      <div className="conflict-notice" role="alert">
        <strong>{t('다른 탭(또는 창)에서 이 배치를 먼저 저장했습니다.')}</strong>
        <p>{t('옮긴 노드 위치는 유지됩니다. 최신 배치와 비교해 유지할 위치를 고른 뒤 [배치 저장] 을 다시 눌러 주세요.')}</p>
      </div>
      <p>{t('내가 옮긴 노드 {0}개 (다른 탭도 옮긴 노드 {1}개) · 다른 탭에서만 옮긴 노드 {2}개', onlyMine.length + conflicts.length, conflicts.length, onlyThere.length)}</p>
      {conflicts.length > 0 && (
        <div className="conflict-table-wrap">
          <table className="conflict-table">
            <thead><tr><th scope="col">{t('노드')}</th><th scope="col">{t('편집 시작 시')}</th><th scope="col">{t('최신 저장 위치')}</th><th scope="col">{t('내 위치')}</th><th scope="col">{t('사용할 위치')}</th></tr></thead>
            <tbody>
              {conflicts.map((id) => (
                <tr key={id}>
                  <th scope="row">{input.name(id)}</th>
                  <td>{fmt(input.base[id])}</td>
                  <td className="conflict-changed">{fmt(input.latest.nodes[id])}</td>
                  <td className="conflict-mine">{fmt(input.mine[id])}</td>
                  <td>
                    <label className="conflict-pick"><input type="radio" name={`layout-${id}`} checked={!!keepMine[id]} onChange={() => setKeepMine({ ...keepMine, [id]: true })} /> {t('내 위치')}</label>{' '}
                    <label className="conflict-pick"><input type="radio" name={`layout-${id}`} checked={!keepMine[id]} onChange={() => setKeepMine({ ...keepMine, [id]: false })} /> {t('최신 위치')}</label>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {onlyThere.length > 0 && <p className="gh-muted">{t('다른 탭에서만 옮긴 노드는 최신 위치로 화면을 맞춥니다 (내 저장이 덮어쓰지 않도록).')}</p>}
      <div className="popup-button-container">
        <button type="button" className="confirm" onClick={apply}>{t('최신 배치 기준으로 다시 준비')}</button>
        <button type="button" className="close" onClick={close}>{t('닫기')}</button>
      </div>
    </div>
  );
}
