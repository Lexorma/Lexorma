import { useState } from 'react';
import type { DialogControls } from '../../components/dialog/DialogProvider';
import { ChevronDownIcon, ChevronUpIcon } from '@primer/octicons-react';
import { api } from '../../lib/api';
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

  const move = (index: number, dir: -1 | 1) => {
    const j = index + dir;
    if (j < 0 || j >= cards.length) return;
    setCards((list) => {
      const next = list.slice();
      [next[index], next[j]] = [next[j], next[index]];
      return next;
    });
  };

  // 순서저장 : 화면에 보이는 순서대로 정렬순서를 저장한다.
  const save = async () => {
    const response = await api<{ result: boolean }>('/api/saveColumn/sortOrder', {
      method: 'PATCH',
      body: cards.map((col, index) => ({ id: col.id, sortOrder: index + 1 })),
    });
    if (response.result) {
      controls.close();
      onSuccess();
    }
  };

  return (
    <div className="popup-container">
      <div className="popup-header">
        <p>{t('화살표 버튼으로 컬럼 순서를 바꾼 뒤 순서저장을 누르면 반영됩니다.')}</p>
      </div>
      <div className="button-container" style={{ justifyContent: 'flex-end', display: 'flex' }}>
        <button type="button" className="btn btn-primary" id="save-sort-order" onClick={save}>{t('순서저장')}</button>
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
