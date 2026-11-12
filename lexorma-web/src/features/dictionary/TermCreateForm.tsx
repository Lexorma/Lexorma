import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { api, enc } from '../../lib/api';
import { blockNonNumericKeys } from '../../lib/format';
import { t } from '../../lib/i18n';

/**
 * 표준용어 등록 폼 (데이터 사전 > 표준용어 추가, 컬럼 관리 > 컬럼 등록 > 새 표준용어 등록, 컬럼 일괄 편집에서 함께 사용)
 *
 * - 표준용어명을 입력하면 표준 단어를 바로 조회해 영문약어명/이음동의어를 채우고, 마지막 단어의 도메인 분류로 도메인을 고른다.
 * - 저장은 기존 등록 API(/api/insertDataDictionary/standardTerms)를 그대로 사용한다.
 *   승인/미승인 여부는 서버에서 권한(AuthUtil.isApprovalAvailable)에 따라 정한다.
 */

interface FieldDef {
  key: string;
  label: string;
  type?: 'number' | 'textarea';
  readonly?: boolean;
  required?: boolean;
  mono?: boolean;
}

const FIELDS: FieldDef[] = [
  { key: 'revisionNumber', label: t('제정차수'), type: 'number' },
  // commonStandardTermName : 단어 입력 그룹으로 따로 그린다
  { key: 'commonStandardTermDescription', label: t('표준용어설명'), type: 'textarea', required: true },
  { key: 'commonStandardTermAbbreviation', label: t('표준용어영문약어명'), readonly: true, mono: true },
  { key: 'commonStandardDomainName', label: t('표준도메인명'), readonly: true, required: true },
  { key: 'allowedValues', label: t('허용값'), type: 'textarea', readonly: true, required: true },
  { key: 'storageFormat', label: t('저장 형식'), readonly: true, required: true },
  { key: 'displayFormat', label: t('표현 형식'), readonly: true, required: true },
  { key: 'administrativeStandardCodeName', label: t('행정표준코드명') },
  { key: 'responsibleOrganization', label: t('소관기관명') },
  { key: 'synonyms', label: t('용어 이음동의어 목록'), readonly: true },
];

interface StandardWord {
  commonStandardWordName: string;
  commonStandardWordAbbreviation: string;
  commonStandardWordEnglishName?: string;
  commonStandardDomainCategory?: string;
  synonyms?: string;
  isApprovalYn: string;
}

interface StandardDomain {
  commonStandardDomainName: string;
  allowedValues?: string;
  storageFormat?: string;
  displayFormat?: string;
}

type WordStatus = 'APPROVED' | 'NOT_APPROVED' | 'NOT_FOUND' | 'NOT_DOMAIN';

const WORD_STATUS: Record<WordStatus, { variant: string; text?: string }> = {
  APPROVED: { variant: 'success' },
  NOT_APPROVED: { variant: 'attention', text: t('미승인') },
  NOT_FOUND: { variant: 'danger', text: t('미등록') },
  NOT_DOMAIN: { variant: 'danger', text: t('도메인 분류 없음') },
};

interface WordResult {
  word: string;
  matched?: StandardWord;
  isLast: boolean;
  isDomainWord: boolean;
  status: WordStatus;
}

export interface SavedTerm {
  commonStandardTermName: string;
  commonStandardTermAbbreviation: string;
  commonStandardDomainName: string;
  [key: string]: unknown;
}

const AUTO_FIELDS = ['commonStandardTermAbbreviation', 'commonStandardDomainName', 'allowedValues', 'storageFormat', 'displayFormat', 'synonyms'];

function splitWords(value: string) {
  return String(value ?? '').trim().split(/[_\s]+/).filter(Boolean);
}

/** 마지막(도메인) 단어의 이음동의어로 용어 이음동의어를 만든다. (예: 사용자_명 → 사용자_이름, 사용자_성명) */
function buildSynonyms(results: WordResult[]) {
  const last = results[results.length - 1].matched!;
  const synonyms = String(last.synonyms ?? '').trim();
  if (!synonyms || synonyms === '-') return '';
  const prefix = results.slice(0, -1).map((r) => r.word).join('_');
  return synonyms.split(',').map((s) => s.trim()).filter(Boolean).map((s) => (prefix ? `${prefix}_${s}` : s)).join(',');
}

export function TermCreateForm({ initialTermName = '', submitText = t('추가'), onSaved }: { initialTermName?: string; submitText?: string; onSaved: (saved: SavedTerm) => void }) {
  const dialog = useDialog();
  const [wordInput, setWordInput] = useState(initialTermName);
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(FIELDS.map((f) => [f.key, ''])));
  const [lookup, setLookup] = useState<{ termName: string; valid: boolean; results: WordResult[]; domains: StandardDomain[] }>({ termName: '', valid: false, results: [], domains: [] });
  const [pending, setPending] = useState(false);
  const [domainName, setDomainName] = useState('');
  const [duplicate, setDuplicate] = useState<{ checking: boolean; duplicated: boolean; message?: string; ok?: boolean }>({ checking: false, duplicated: false });
  const seq = useRef(0);
  const dupSeq = useRef(0);
  const saving = useRef(false);
  const descriptionRef = useRef<HTMLTextAreaElement>(null);

  const setField = (key: string, value: string) => setValues((v) => ({ ...v, [key]: value }));

  // 표준용어명 입력 → 300ms 뒤 표준 단어 조회
  useEffect(() => {
    const words = splitWords(wordInput);
    seq.current++;
    dupSeq.current++;
    setLookup({ termName: '', valid: false, results: [], domains: [] });
    setDomainName('');
    setDuplicate({ checking: false, duplicated: false });
    setValues((v) => ({ ...v, ...Object.fromEntries(AUTO_FIELDS.map((k) => [k, ''])) }));
    if (words.length === 0) {
      setPending(false);
      return;
    }
    setPending(true);
    const mySeq = seq.current;
    const timer = setTimeout(async () => {
      // 표준용어명은 단어를 "_" 로 잇는다 : 영문약어명(USER_NM)과 단어 수가 같아야 한다 (서버에서도 검증)
      const termName = words.join('_');
      try {
        const response = await api<{ list?: StandardWord[]; standardDomains?: StandardDomain[] }>(`/api/getStandardTerms/popup/${enc(termName)}`);
        if (mySeq !== seq.current) return; // 이전 입력에 대한 응답은 무시
        const standardWords = response.list || [];
        const domains = response.standardDomains || [];

        const results: WordResult[] = words.map((word, index) => {
          const matched = standardWords.find((w) => w.commonStandardWordName === word);
          const isLast = index === words.length - 1;
          const isDomainWord = !!matched && !!matched.commonStandardDomainCategory && matched.commonStandardDomainCategory !== '-';
          let status: WordStatus = 'APPROVED';
          if (!matched) status = 'NOT_FOUND';
          else if (isLast && !isDomainWord) status = 'NOT_DOMAIN';
          else if (matched.isApprovalYn === 'N') status = 'NOT_APPROVED';
          return { word, matched, isLast, isDomainWord, status };
        });

        // 미승인 단어는 등록 가능(승인 대기)하므로 경고만 하고, 미등록/도메인 분류 없음은 등록할 수 없다.
        const valid = results.every((r) => r.status === 'APPROVED' || r.status === 'NOT_APPROVED') && domains.length > 0;
        setLookup({ termName, valid, results, domains });
        setPending(false);
        if (!valid) return;

        setValues((v) => ({
          ...v,
          commonStandardTermAbbreviation: results.map((r) => r.matched!.commonStandardWordAbbreviation).join('_'),
          synonyms: buildSynonyms(results),
        }));
        // 선택지가 하나뿐이면 바로 선택
        if (domains.length === 1) setDomainName(domains[0].commonStandardDomainName);
      } catch {
        if (mySeq === seq.current) setPending(false);
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [wordInput]);

  // 도메인 선택 → 도메인 항목 채우고 영문약어명 + 도메인 중복 확인 (저장 시 서버 중복 기준과 동일)
  const abbreviation = values.commonStandardTermAbbreviation;
  useEffect(() => {
    const domain = lookup.domains.find((d) => d.commonStandardDomainName === domainName);
    setValues((v) => ({
      ...v,
      commonStandardDomainName: domain?.commonStandardDomainName ?? '',
      allowedValues: domain?.allowedValues ?? '',
      storageFormat: domain?.storageFormat ?? '',
      displayFormat: domain?.displayFormat ?? '',
    }));
    const mySeq = ++dupSeq.current;
    if (!domain || !abbreviation) {
      setDuplicate({ checking: false, duplicated: false });
      return;
    }
    setDuplicate({ checking: true, duplicated: false });
    api<{ duplicated: boolean; terms?: { commonStandardTermName: string; isApprovalYn: string }[] }>(
      `/api/getStandardTerms/duplicate?abbreviation=${enc(abbreviation)}&domainName=${enc(domain.commonStandardDomainName)}`,
    )
      .then((response) => {
        if (mySeq !== dupSeq.current) return; // 이전 선택에 대한 응답은 무시
        if (response.duplicated === true) {
          const names = (response.terms || []).map((term) => `${term.commonStandardTermName}${term.isApprovalYn === 'Y' ? '' : t(' (승인 대기)')}`).join(', ');
          setDuplicate({
            checking: false,
            duplicated: true,
            message: t('이미 같은 영문약어명({0})과 도메인({1})으로 등록된 용어가 있습니다: {2}', abbreviation, domain.commonStandardDomainName, names),
          });
        } else {
          setDuplicate({ checking: false, duplicated: false, ok: true });
        }
      })
      .catch(() => {
        if (mySeq === dupSeq.current) setDuplicate({ checking: false, duplicated: false });
      });
  }, [domainName, lookup.domains, abbreviation]);

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (saving.current) return;

    if (pending) return dialog.alert(t('표준 단어를 조회하고 있습니다. 잠시 후 다시 시도해주세요.'));
    if (!lookup.termName) return dialog.alert(t('표준용어명을 입력해주세요.'));
    if (!lookup.valid) return dialog.alert(t('표준용어명에 등록할 수 없는 단어가 있습니다. 단어별 안내를 확인해주세요.'));
    if (!values.commonStandardDomainName) return dialog.alert(t('도메인을 선택해주세요.'));
    if (duplicate.checking) return dialog.alert(t('중복 여부를 확인하고 있습니다. 잠시 후 다시 시도해주세요.'));
    if (duplicate.duplicated) return dialog.alert(t('이미 같은 영문약어명과 도메인으로 등록된 용어가 있습니다.'));

    const missing = FIELDS.find((f) => f.required && !String(values[f.key] ?? '').trim());
    if (missing) {
      dialog.alert(t('{0}은(는) 필수 입력 항목입니다.', missing.label));
      if (missing.key === 'commonStandardTermDescription') descriptionRef.current?.focus();
      return;
    }

    // 숫자 항목(제정차수)은 숫자로 보내고, 비어 있으면 보내지 않는다. ("" 는 서버의 int 로 변환되지 않음 → 기본값 0)
    const data: Record<string, unknown> = { commonStandardTermName: lookup.termName };
    FIELDS.forEach((f) => {
      const value = values[f.key];
      if (f.type === 'number') {
        if (String(value ?? '').trim() !== '') data[f.key] = Number(value);
        return;
      }
      data[f.key] = value;
    });

    saving.current = true;
    try {
      const response = await api<{ result: boolean }>('/api/insertDataDictionary/standardTerms', { method: 'POST', body: data });
      if (response.result) onSaved(data as SavedTerm);
    } finally {
      saving.current = false;
    }
  };

  const { results, valid, domains } = lookup;
  const messages: [string, string][] = [];
  if (results.some((r) => r.status === 'NOT_FOUND')) messages.push(['table-name-error', t('미등록 단어는 데이터 사전 > 표준 단어에서 먼저 등록해야 합니다.')]);
  if (results.some((r) => r.status === 'NOT_DOMAIN')) messages.push(['table-name-error', t('마지막 단어는 도메인 분류가 있는 표준 단어여야 합니다.')]);
  if (results.length > 0 && results.every((r) => r.status !== 'NOT_FOUND' && r.status !== 'NOT_DOMAIN') && domains.length === 0) {
    messages.push(['table-name-error', t('마지막 단어의 도메인 분류에 등록된 표준 도메인이 없습니다.')]);
  }
  if (valid && results.some((r) => r.status === 'NOT_APPROVED')) messages.push(['table-name-warning', t('미승인 단어가 포함되어 있습니다. 등록은 가능하지만 단어 승인이 필요합니다.')]);

  const field = (f: FieldDef) => {
    const id = `termForm-${f.key}`;
    const props = {
      id,
      name: f.key,
      className: `form-control${f.mono ? ' gh-mono' : ''}`,
      readOnly: f.readonly,
      placeholder: f.readonly ? t('표준용어명/도메인을 선택하면 자동으로 입력됩니다.') : undefined,
      value: values[f.key],
      onChange: (e: { target: { value: string } }) => setField(f.key, e.target.value),
    };
    return (
      <div className="form-group" key={f.key}>
        <label htmlFor={id}>{f.label}</label>
        {f.type === 'textarea' ? (
          <textarea rows={2} {...props} ref={f.key === 'commonStandardTermDescription' ? descriptionRef : undefined} />
        ) : (
          <input type={f.type === 'number' ? 'number' : 'text'} min={f.type === 'number' ? 0 : undefined} {...props}
            onKeyDown={f.type === 'number' ? blockNonNumericKeys : undefined} />
        )}
      </div>
    );
  };

  return (
    <form className="edit-form term-create-form" noValidate onSubmit={save}>
      {field(FIELDS[0])}

      <div className="form-group term-word-group">
        <label htmlFor="termForm-termWordInput">{t('표준용어명')}</label>
        <input type="text" id="termForm-termWordInput" autoComplete="off" placeholder={t('예) 사용자_이름 또는 사용자 이름')} className="form-control term-word-input"
          autoFocus value={wordInput} onChange={(e) => setWordInput(e.target.value)} />
        <p className="form-help">{t('표준 단어를 _ 또는 공백으로 구분해 입력하면 바로 조회합니다. 마지막 단어는 도메인 분류가 있는 단어(예: 명, 일자)여야 합니다.')}</p>
        <div aria-live="polite" className="table-name-words term-words">
          {results.map((r, i) => {
            const status = WORD_STATUS[r.status];
            return (
              <span key={i} className={`gh-label gh-label-${status.variant}`} title={r.matched?.commonStandardWordEnglishName || undefined}>
                <span>{r.word}</span>
                {r.matched && <span className="table-name-abbr">{r.matched.commonStandardWordAbbreviation ?? ''}</span>}
                {status.text && <span>{status.text}</span>}
                {r.isLast && r.isDomainWord && <span>· {r.matched!.commonStandardDomainCategory}</span>}
              </span>
            );
          })}
          {messages.map(([cls, text]) => (
            <p key={text} className={cls}>{text}</p>
          ))}
        </div>
        <label htmlFor="termForm-domainSelect" style={{ marginTop: 12 }}>{t('도메인 선택')}</label>
        <select id="termForm-domainSelect" className="form-control term-domain-select" disabled={!valid} value={domainName} onChange={(e) => setDomainName(e.target.value)}>
          <option value="">{valid ? t('도메인을 선택하세요') : t('표준용어명을 먼저 입력하세요')}</option>
          {valid && domains.map((domain) => (
            <option key={domain.commonStandardDomainName} value={domain.commonStandardDomainName}>
              {domain.commonStandardDomainName} ({domain.storageFormat || '-'})
            </option>
          ))}
        </select>
        <div aria-live="polite" className="table-name-words term-duplicate">
          {duplicate.duplicated && <p className="table-name-error">{duplicate.message}</p>}
          {duplicate.ok && <span className="gh-label gh-label-success">{t('등록 가능 · 중복 없음')}</span>}
        </div>
      </div>

      {FIELDS.slice(1).map(field)}

      <button type="submit" className="btn btn-primary">{submitText}</button>
    </form>
  );
}
