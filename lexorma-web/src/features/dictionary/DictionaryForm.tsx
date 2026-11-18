import { useState, type FormEvent } from 'react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { api } from '../../lib/api';
import { blockNonNumericKeys } from '../../lib/format';
import { t } from '../../lib/i18n';

/**
 * 표준 도메인 / 단어 / 용어 등록·수정 폼 (예전 add/openEditDialog 의 동적 폼)
 * 필드 목록과 입력 형태, 읽기 전용 / 필수 여부는 탭마다 정의한다.
 */

export type DictionaryTable = 'standardDomains' | 'standardWords' | 'standardTerms';

export interface FieldSpec {
  key: string;
  label: string;
  value?: unknown;
}

export interface FormConfig {
  control?: (key: string) => 'text' | 'textarea' | 'number' | { options: { value: string; text: string }[] };
  readonly?: (key: string) => boolean;
  required: (key: string) => boolean;
  /** 저장 전 추가 확인 (오류 문구를 반환하면 저장하지 않는다) */
  validate?: (data: Record<string, unknown>) => string | null;
}

export function DictionaryForm({
  tableId,
  type,
  fields,
  config,
  submitText,
  onSaved,
  base,
}: {
  tableId: DictionaryTable;
  type: 'C' | 'U';
  fields: FieldSpec[];
  config: FormConfig;
  submitText: string;
  onSaved: () => void;
  /** 화면에 그리지 않고 그대로 돌려보내는 값 : 표준 용어 수정 기준값 modifiedAt (불러온 때의 값, 입력과 무관 — M-5 8차) */
  base?: Record<string, unknown>;
}) {
  const dialog = useDialog();
  // 읽기 전용 칸은 사용자가 입력하지 않으므로 필수 검사를 하지 않는다 (용어 수정의 도메인 허용값처럼 비어 있어도 정상인 표시 값 — M-5 6차)
  const isReadOnly = (key: string) => key === 'id' || key === 'projectId' || !!config.readonly?.(key);
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(fields.map((f) => {
      const control = config.control?.(f.key);
      const initial = f.value ?? (typeof control === 'object' ? control.options[0]?.value : '');
      return [f.key, initial === null || initial === undefined ? '' : String(initial)];
    })),
  );

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();

    for (const f of fields) {
      if (!isReadOnly(f.key) && config.required(f.key) && values[f.key].trim() === '') {
        dialog.alert(t('필수값 누락: {0}', f.label));
        document.getElementById(`dict-${f.key}`)?.focus();
        return;
      }
    }

    // 숫자 항목은 숫자로 보내고, 비어 있으면 보내지 않는다 ("" 는 서버의 int 로 변환되지 않음 → 기본값)
    const data: Record<string, unknown> = {};
    fields.forEach((f) => {
      const value = values[f.key];
      if (config.control?.(f.key) === 'number') {
        if (value.trim() !== '') data[f.key] = Number(value);
        return;
      }
      // 수정 폼 : 불러올 때 NULL 이던 칸을 건드리지 않았으면 NULL 그대로 보낸다 (빈 문자열로 바꿔 저장하지 않는다)
      data[f.key] = type === 'U' && (f.value === null || f.value === undefined) && value === '' ? null : value;
    });

    const message = config.validate?.(data);
    if (message) return dialog.alert(message);

    Object.assign(data, base);

    const url = `/api/${type === 'C' ? 'insertDataDictionary' : 'updateDataDictionary'}/${tableId}`;
    let response: { result: boolean };
    try {
      response = await api<{ result: boolean }>(url, { method: type === 'C' ? 'POST' : 'PUT', body: data });
    } catch {
      // 오류는 공통 알림이 보여 준다. 폼은 닫지 않고 입력을 그대로 둔다 (409 충돌도 — 자동 재전송·덮어쓰기 없음)
      return;
    }
    if (response.result) dialog.alert(type === 'C' ? t('정상적으로 등록되었습니다.') : t('정상적으로 수정되었습니다.'), onSaved);
  };

  return (
    <form id={`${type === 'C' ? 'add' : 'edit'}-${tableId}`} onSubmit={onSubmit} noValidate>
      {fields.map((f) => {
        const control = config.control?.(f.key) ?? 'text';
        const readOnly = isReadOnly(f.key);
        const common = {
          id: `dict-${f.key}`,
          name: f.key,
          value: values[f.key],
          readOnly,
          style: { width: '100%', ...(readOnly ? { backgroundColor: '#f0f0f0' } : {}) },
          onChange: (e: { target: { value: string } }) => setValues((v) => ({ ...v, [f.key]: e.target.value })),
        };
        return [
          <label key={`l-${f.key}`} htmlFor={common.id} style={{ display: 'block', marginTop: 10 }}>{f.label}</label>,
          control === 'textarea' ? (
            <textarea key={f.key} {...common} style={{ ...common.style, height: 60 }} />
          ) : control === 'number' ? (
            <input key={f.key} type="number" {...common} onKeyDown={blockNonNumericKeys} />
          ) : typeof control === 'object' ? (
            <select key={f.key} {...common} disabled={readOnly} style={{ ...common.style, height: 30 }}>
              {control.options.map((opt) => (
                <option key={opt.value} value={opt.value}>{opt.text}</option>
              ))}
            </select>
          ) : (
            <input key={f.key} type="text" {...common} />
          ),
        ];
      })}
      <button type="submit" className="btn btn-primary" style={{ marginTop: 20 }}>{submitText}</button>
    </form>
  );
}

/** 그리드 행 데이터로 수정 폼 필드 목록 (그리드 컬럼명이 없으면 키 이름) */
export function fieldsFromRow(row: Record<string, unknown>, columns: { column: string; columnName: string }[], skip: string[] = []): FieldSpec[] {
  const labels = new Map(columns.map((c) => [c.column, c.columnName]));
  return Object.entries(row)
    .filter(([key]) => !skip.includes(key))
    .map(([key, value]) => ({ key, label: labels.get(key) || key, value }));
}

/** 그리드 컬럼 정의로 등록 폼 필드 목록 (id / 승인여부 제외) */
export function fieldsFromColumns(columns: { column: string; columnName: string }[]): FieldSpec[] {
  return columns
    .filter((c) => c.column !== 'id' && c.column !== 'isApprovalYn' && c.column !== 'usageCount')
    .map((c) => ({ key: c.column, label: c.columnName || c.column }));
}
