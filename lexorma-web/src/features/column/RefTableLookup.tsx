import { useEffect, useId, useRef, useState } from 'react';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';

interface TableInfo {
  id: number;
  tableName: string;
  tableKorName?: string;
  schemaName?: string | null;
}

/** 이름이 여러 스키마에 있으면 "스키마명.테이블명" 으로 적어야 정해진다 (테이블명은 스키마 안에서만 하나다) */
export function refName(table: TableInfo, list: TableInfo[]) {
  const duplicated = list.filter((other) => other.tableName === table.tableName).length > 1;
  return duplicated && table.schemaName ? `${table.schemaName.toUpperCase()}.${table.tableName}` : table.tableName;
}

/**
 * 참조 테이블명 입력칸 : 입력하는 동안 승인된 테이블을 조회해 자동완성(datalist) 목록으로 보여준다.
 * (컬럼 등록 팝업, 컬럼 일괄 편집에서 사용)
 *
 * - 목록은 제안일 뿐이라 직접 입력도 그대로 저장된다. (서버가 대문자로 맞춘다)
 * - 입력값이 승인된 테이블과 같으면 "선택됨", 없으면 DDL 에서 FK 가 만들어지지 않는다고 안내한다.
 * - 같은 이름이 여러 스키마에 있으면 목록은 "스키마명.테이블명" 으로 보여 주고, 이름만 적으면 어느 것인지 고르라고 안내한다.
 *   (스키마를 적지 않은 이름은 서버가 이 컬럼의 테이블과 같은 스키마를 먼저 찾는다)
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
  const options = (tables ?? []).map((table) => ({ table, value: refName(table, tables ?? []) }));
  const matched = (options.find((o) => o.value === name) ?? options.find((o) => o.table.schemaName && `${o.table.schemaName.toUpperCase()}.${o.table.tableName}` === name))?.table ?? null;
  const ambiguous = !matched && options.filter((o) => o.table.tableName === name).length > 1;

  return (
    <>
      <input type="text" id={id} className={className} list={listId} autoComplete="off" placeholder={placeholder} aria-label={ariaLabel}
        value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)} />
      <datalist id={listId}>
        {options.map((o) => <option key={o.table.id} value={o.value}>{o.table.tableKorName || ''}</option>)}
      </datalist>
      {!disabled && (
        <div className="table-name-words ref-table-status" aria-live="polite">
          {name && tables && (matched ? (
            <span className="gh-label gh-label-success">
              <span className="table-name-abbr">{name.includes('.') ? name : refName(matched, tables ?? [])}</span>
              <span>{matched.tableKorName ? t('{0} · 선택됨', matched.tableKorName) : t('선택됨')}</span>
            </span>
          ) : ambiguous ? (
            <p className="form-help">{t('여러 스키마에 같은 이름이 있습니다 · 목록에서 스키마명.테이블명을 선택하세요.')}</p>
          ) : (
            <p className="form-help">{tables.length ? t('후보 {0}건 · 목록에서 선택하세요.', tables.length) : t('승인된 테이블에 없습니다 · DDL 에서 FK 를 만들지 않습니다.')}</p>
          ))}
        </div>
      )}
    </>
  );
}
