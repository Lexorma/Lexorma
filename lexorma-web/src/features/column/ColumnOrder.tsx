import { useState } from 'react';
import type { DialogControls } from '../../components/dialog/DialogProvider';
import { ChevronDownIcon, ChevronUpIcon } from '@primer/octicons-react';
import { api, isConflict } from '../../lib/api';
import { isGone, loadFailure, useSubmitting } from '../../components/conflict/ConflictRecovery';
import { OrderConflict, type OrderItem } from '../../components/conflict/OrderConflict';
import { t } from '../../lib/i18n';

const CARD_ROWS = [
  [
    { label: t('컬럼 설명'), key: 'columnDesc' },
    { label: t('데이터 타입'), key: 'dataType' },
    { label: t('최대 길이'), key: 'maxLength' },
    { label: t('PK 여부'), key: 'isPk' },
  ],
  [
    { label: t('기본값'), key: 'defaultValue' },
    { label: t('NULL 허용'), key: 'isNullable' },
    { label: t('필수 입력'), key: 'isRequired' },
    { label: t('암호화 여부'), key: 'isEncrypted' },
  ],
  [
    { label: t('정렬 순서'), key: 'sortOrder' },
    { label: t('참조 테이블명'), key: 'refTableName' },
    { label: t('예시'), key: 'example' },
    { label: t('민감정보 여부'), key: 'isSensitive' },
  ],
];

/**
 * 컬럼 순서 변경 (물리 모델 관리 > 컬럼 관리 > 행 선택 > 컬럼 순서 변경)
 * 같은 테이블의 (승인 전) 컬럼을 카드로 보여주고, 화살표로 순서를 바꿔 저장한다.
 */
export function ColumnOrder({ columnInfos, controls, onSuccess }: { columnInfos: Record<string, any>[]; controls: DialogControls; onSuccess: () => void }) {
  const [cards, setCards] = useState(columnInfos);
  // 편집을 시작한 구성 (기준값 = 각 컬럼의 sortOrder). 409 뒤 [최신 순서에서 다시 정하기] 를 누르면 최신 구성으로 바뀐다 (M8-C4c-2)
  const [start, setStart] = useState(columnInfos);
  const [busy, run] = useSubmitting();
  const [conflict, setConflict] = useState<null | { kind: 'conflict' } | { kind: 'loading' } | { kind: 'loaded'; latest: Record<string, any>[] } | { kind: 'failed'; message: string; gone: boolean }>(null);
  // 다시 정하는 동안 참고로 보이는 내 순서 (자동으로 적용하지 않는다)
  const [previousMine, setPreviousMine] = useState<string[] | null>(null);
  const item = (col: Record<string, any>): OrderItem => ({ id: Number(col.id), label: String(col.columnName ?? col.id) });

  const move = (index: number, dir: -1 | 1) => {
    const j = index + dir;
    if (j < 0 || j >= cards.length) return;
    setCards((list) => {
      const next = list.slice();
      [next[index], next[j]] = [next[j], next[index]];
      return next;
    });
  };

  // 순서저장 : 화면에 보이는 순서대로 정렬순서를 저장한다. 행마다 팝업을 연 때의 정렬 순서(baseSortOrder, 없던 컬럼은 0)를 함께 보낸다 (M8-C1) —
  // 그 사이 다른 사용자가 순서를 바꿨거나 컬럼이 더해지거나 지워졌으면 서버가 409 로 전체를 거절한다 (공통 알림, 팝업과 바꾼 순서는 그대로 둔다)
  // M8-C4c-2 : 409 (순서·컬럼 구성이 바뀌었다) 면 처음 순서 · 현재 서버 순서 · 내 순서를 비교해 보여 주고, 사용자가 최신 구성에서 직접 다시 정한다 (자동 병합 없음).
  // 잠금 충돌은 공통 안내 — 바꾼 순서와 기준값은 그대로, 같은 버튼으로 다시 저장한다
  const save = () => void run(async () => {
    let response: { result: boolean };
    try {
      response = await api<{ result: boolean }>('/api/saveColumn/sortOrder', {
        method: 'PATCH', conflict: 'handled',
        body: cards.map((col, index) => ({ id: col.id, sortOrder: index + 1, baseSortOrder: col.sortOrder ?? 0 })),
      });
    } catch (error) {
      if (isConflict(error)) setConflict({ kind: 'conflict' });
      return;
    }
    if (response.result) {
      controls.close();
      onSuccess();
    }
  });
  // 최신 구성 : 이 테이블의 승인 전 컬럼 (팝업을 연 API 와 같은 조회 — 지워진 컬럼으로는 읽을 수 없으니 남은 컬럼 ID 를 차례로 쓴다)
  const fetchLatest = async () => {
    setConflict({ kind: 'loading' });
    let lastError: unknown = null;
    for (const col of start) {
      try {
        const response = await api<{ result: boolean; columnInfos?: Record<string, any>[] }>(`/api/selectColumn/detail/${encodeURIComponent(String(col.id))}`, { silent: true });
        if (response.result) { setConflict({ kind: 'loaded', latest: response.columnInfos ?? [] }); return; }
      } catch (error) {
        lastError = error;
      }
    }
    setConflict({ kind: 'failed', message: loadFailure(lastError), gone: lastError == null || isGone(lastError) });
  };
  const restart = (latest: Record<string, any>[]) => {
    setPreviousMine(cards.map((c) => String(c.columnName ?? c.id)));
    setStart(latest);
    setCards(latest);
    setConflict(null);
  };

  return (
    <div className="popup-container">
      <div className="popup-header">
        <p>{t('화살표 버튼으로 컬럼 순서를 바꾼 뒤 순서저장을 누르면 반영됩니다.')}</p>
      </div>
      {conflict && (
        <OrderConflict
          title={t('다른 사용자가 이 테이블의 컬럼 순서나 구성을 변경했습니다.')}
          stage={conflict.kind}
          message={conflict.kind === 'failed' ? conflict.message : undefined}
          gone={conflict.kind === 'failed' && conflict.gone}
          before={start.map(item)} latest={conflict.kind === 'loaded' ? conflict.latest.map(item) : []} mine={cards.map(item)}
          onLoad={() => void fetchLatest()}
          onRestart={conflict.kind === 'loaded' ? () => restart(conflict.latest) : undefined}
        />
      )}
      {previousMine && !conflict && (
        <p className="gh-muted" role="status">{t('최신 순서에서 다시 정하는 중입니다. 내가 정했던 순서 (참고) : {0}', previousMine.join(' → '))}</p>
      )}
      <div className="button-container" style={{ justifyContent: 'flex-end', display: 'flex' }}>
        <button type="button" className="btn btn-primary" id="save-sort-order" disabled={busy || (conflict?.kind === 'failed' && conflict.gone)} onClick={save}>{busy ? t('처리 중…') : t('순서저장')}</button>
      </div>

      <div id="column-table-container">
        <div className="column-card-list">
          {cards.map((col, index) => (
            <div className="column-card" key={col.id} data-id={col.id}>
              <div className="column-row full">
                <span className="label">{col.columnName ?? ''}</span>
                <div className="sort-buttons">
                  <button type="button" className="btn-move-up" title={t('위로')} aria-label={t('위로')} onClick={() => move(index, -1)}><ChevronUpIcon /></button>
                  <button type="button" className="btn-move-down" title={t('아래로')} aria-label={t('아래로')} onClick={() => move(index, 1)}><ChevronDownIcon /></button>
                </div>
              </div>
              {CARD_ROWS.map((rowSet, r) => (
                <div className="column-grid-row" key={r}>
                  {rowSet.map((field) => {
                    const value = field.key === 'sortOrder' ? index + 1 : (col[field.key] ?? '-');
                    const highlight = value === 'Y' && ['isRequired', 'isPk', 'isEncrypted'].includes(field.key);
                    return (
                      <div className="pair" data-field={field.key} key={field.key}>
                        <span className="label">{field.label}</span>
                        <span className={`value${highlight ? ' highlight' : ''}`}>{String(value)}</span>
                      </div>
                    );
                  })}
                </div>
              ))}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
