import { useEffect, useId, useRef, useState } from 'react';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';

interface TableInfo {
  id: number;
  tableName: string;
  tableKorName?: string;
}

/**
 * 참조 테이블명 입력칸 : 입력하는 동안 승인된 테이블을 조회해 자동완성(datalist) 목록으로 보여준다.
 * (컬럼 등록 팝업, 컬럼 일괄 편집에서 사용)
 *
 * - 목록은 제안일 뿐이라 직접 입력도 그대로 저장된다. (서버가 대문자로 맞춘다)
 * - 입력값이 승인된 테이블과 같으면 "선택됨", 없으면 DDL 에서 FK 가 만들어지지 않는다고 안내한다.
 */
export function RefTableLookup({ value, onChange, disabled, id, className = 'form-control gh-mono', placeholder, ariaLabel }: {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  id?: string;
  className?: string;
  placeholder?: string;
  ariaLabel?: string;
}) {
  const listId = `refTableOptions-${useId().replace(/:/g, '')}`;
  const [tables, setTables] = useState<TableInfo[] | null>(null);
  const seq = useRef(0);

  useEffect(() => {
    const keyword = value.trim();
    const mySeq = ++seq.current;
    if (!keyword || disabled) {
      setTables(null);
      return;
    }
    const timer = setTimeout(async () => {
      const response = await api<{ tableInfos?: TableInfo[] }>(`/api/selectColumn/table/${enc(keyword)}`, { silent: true }).catch(() => null);
      if (mySeq !== seq.current || !response) return; // 늦게 도착한 이전 응답 무시
      setTables(response.tableInfos || []);
    }, 300);
    return () => clearTimeout(timer);
  }, [value, disabled]);

  const name = value.trim().toUpperCase();
  const matched = tables?.find((t) => t.tableName === name);

  return (
    <>
      <input type="text" id={id} className={className} list={listId} autoComplete="off" placeholder={placeholder} aria-label={ariaLabel}
        value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)} />
      <datalist id={listId}>
        {(tables ?? []).map((t) => <option key={t.id} value={t.tableName}>{t.tableKorName || ''}</option>)}
      </datalist>
      {!disabled && (
        <div className="table-name-words ref-table-status" aria-live="polite">
          {name && tables && (matched ? (
            <span className="gh-label gh-label-success">
              <span className="table-name-abbr">{matched.tableName}</span>
              <span>{matched.tableKorName ? t('{0} · 선택됨', matched.tableKorName) : t('선택됨')}</span>
            </span>
          ) : (
            <p className="form-help">{tables.length ? t('후보 {0}건 · 목록에서 선택하세요.', tables.length) : t('승인된 테이블에 없습니다 · DDL 에서 FK 를 만들지 않습니다.')}</p>
          ))}
        </div>
      )}
    </>
  );
}
