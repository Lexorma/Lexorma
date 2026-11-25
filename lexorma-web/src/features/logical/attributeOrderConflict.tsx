import { useCallback, useEffect, useState } from 'react';
import type { DialogApi, DialogControls } from '../../components/dialog/DialogProvider';
import { isGone, loadFailure } from '../../components/conflict/ConflictRecovery';
import { OrderConflict, type OrderItem } from '../../components/conflict/OrderConflict';
import { api, isConflict } from '../../lib/api';
import { t } from '../../lib/i18n';
import { attributeOrderBody, type LogicalEntity } from './model';

type Attr = { id: number; sortOrder: number | null; label: string };

/**
 * 논리 속성 순서 변경 (M8-C4b L-02 — 본문 [{id, baseSortOrder}] 그대로) + 409 복구 (M8-C4c-2 10.7). ↑/↓ 한 번이 저장이라, 409 면 대화상자에서
 * 처음 순서 · 현재 서버 순서 · 내 순서를 보여 주고 사용자가 [최신 순서에서 다시 정하기] 를 누르면 화면을 최신으로 다시 읽는다 (내 순서를 자동으로 다시 보내지 않는다).
 * 잠금 충돌 등 그 밖의 오류는 공통 안내 — 화면의 순서는 그대로 두고 같은 버튼으로 다시 시도한다
 */
export async function moveAttributeWithReview(dialog: DialogApi, entityId: number, attributes: Attr[], index: number, delta: number, refresh: () => void | Promise<unknown>) {
  try {
    await api(`/api/logical/entity/${entityId}/attribute/order`, { method: 'PUT', conflict: 'handled', body: attributeOrderBody(attributes, index, delta) });
  } catch (error) {
    if (!isConflict(error)) return;
    const mine = [...attributes];
    [mine[index], mine[index + delta]] = [mine[index + delta], mine[index]];
    dialog.open({
      title: t('속성 순서 변경'),
      className: 'dialog-wide',
      onClose: () => void refresh(),
      content: (controls) => <AttributeOrderReview entityId={entityId} before={attributes} mine={mine} controls={controls} />,
    });
    return;
  }
  await refresh();
}

function AttributeOrderReview({ entityId, before, mine, controls }: { entityId: number; before: Attr[]; mine: Attr[]; controls: DialogControls }) {
  const [state, setState] = useState<{ kind: 'loading' } | { kind: 'loaded'; latest: OrderItem[] } | { kind: 'failed'; message: string; gone: boolean }>({ kind: 'loading' });
  const item = (a: Attr): OrderItem => ({ id: a.id, label: a.label });
  const load = useCallback(async () => {
    setState({ kind: 'loading' });
    try {
      const latest = await api<LogicalEntity>(`/api/logical/entity/${entityId}`, { silent: true });
      setState({ kind: 'loaded', latest: latest.attributes.map((a) => ({ id: a.id, label: a.attributeName })) });
    } catch (error) {
      setState({ kind: 'failed', message: loadFailure(error), gone: isGone(error) });
    }
  }, [entityId]);
  useEffect(() => { void load(); }, [load]);
  return (
    <div className="conflict-review">
      <OrderConflict title={t('다른 사용자가 이 엔터티의 속성 순서나 구성을 변경해 순서를 바꾸지 않았습니다.')} stage={state.kind}
        message={state.kind === 'failed' ? state.message : undefined} gone={state.kind === 'failed' && state.gone}
        before={before.map(item)} latest={state.kind === 'loaded' ? state.latest : []} mine={mine.map(item)}
        onLoad={() => void load()} onRestart={state.kind === 'loaded' ? controls.close : undefined} />
      <div className="popup-button-container">
        <button type="button" className="close" onClick={controls.close}>{t('닫기')}</button>
      </div>
    </div>
  );
}
