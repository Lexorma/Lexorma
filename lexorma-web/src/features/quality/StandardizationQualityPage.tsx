import { Fragment, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Approval, Label, Mono, Muted } from '../../components/grid/cells';
import { Grid, type GridHandle, type Row } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { TableLinks } from '../dictionary/DictionaryUsage';
import { useSchemas } from '../schema/schemas';
import { QUALITY_IDENTIFIER_GRID_MIN_WIDTH, QUALITY_MODEL_GRID_MIN_WIDTH, QUALITY_TERM_GRID_MIN_WIDTH, qualityIdentifierColumns, qualityModelColumns, qualityTermColumns } from './columns';
import {
  IDENTIFIER_KINDS, IDENTIFIER_RULES, MODEL_RULES, SEVERITIES, TARGET_TYPES, TERM_RULES,
  type ColumnSummary, type IdentifierKind, type IdentifierRule, type IdentifierSummary, type ModelRule, type ModelSummary, type Severity, type TargetType,
  type DomainDuplicateGroup, type TermGroup, type TermRule, type TermSummary,
} from './model';
import { ColumnSuggestionDetail, SeverityLabel, TermGroupDetail } from './qualityDialogs';

type Tab = 'model' | 'terms' | 'identifiers';

const COLUMN_COUNTS: Partial<Record<ModelRule, keyof ColumnSummary>> = {
  TERM_NOT_LINKED: 'termNotLinked', COLUMN_NAME_MISMATCH: 'columnNameMismatch', DOMAIN_DEFINITION_MISMATCH: 'domainDefinitionMismatch',
};
const TABLE_COUNTS: Partial<Record<ModelRule, 'tablePrefixMissing' | 'tableWordNotStandard'>> = {
  TABLE_PREFIX_MISSING: 'tablePrefixMissing', TABLE_WORD_NOT_STANDARD: 'tableWordNotStandard',
};
/** 개선 후보를 계산하는 컬럼 진단 유형 */
const SUGGESTION_TYPES: ModelRule[] = ['TERM_NOT_LINKED', 'COLUMN_NAME_MISMATCH'];

/**
 * 표준화 품질 진단 (M-5, 조회 전용). 탭 두 개 :
 * - 모델 품질 진단 : 컬럼(표준 용어 미연결·컬럼명 불일치·도메인 정의 불일치) + 테이블명(프로젝트 접두어·승인된 표준 단어 약어). 컬럼 행에서 개선 후보 대화상자
 * - 표준 용어 진단 : 중복·유사어 그룹 (약어·도메인 중복, 용어명 중복, 같은 약어 다른 도메인, 같은 단어 구성, 이음동의어, 금칙어)
 * - DBMS 식별자 진단 (M-7 보강 1차) : 프로젝트 DBMS 기준 이름 길이·문자, DDL 제약조건명 잘림·충돌. 모델 진단·대시보드 수치와 따로 집계한다
 * 주소 : ?tab=terms 로 용어 탭, ?tab=identifiers 로 식별자 탭, ?type= 으로 모델 탭의 처음 유형 (대시보드의 품질 항목)
 */
export function StandardizationQualityPage() {
  const [searchParams] = useSearchParams();
  const initialTab = searchParams.get('tab');
  const [tab, setTab] = useState<Tab>(initialTab === 'terms' || initialTab === 'identifiers' ? initialTab : 'model');
  const tabs: [Tab, string][] = [['model', t('모델 품질 진단')], ['terms', t('표준 용어 진단')], ['identifiers', t('DBMS 식별자 진단')]];

  return (
    <>
      <PageHeader category={t('데이터 품질 관리')} title={t('표준화 품질 진단')}
        description={t('물리 테이블·컬럼이 표준 사전을 따르는지, 표준 용어에 중복·유사어가 있는지 진단합니다. 조회만 하며 테이블·컬럼이나 표준 항목을 바꾸지 않습니다.')} />
      <ul className="nav nav-tabs" role="tablist">
        {tabs.map(([id, label]) => (
          <li className="nav-item" key={id}>
            <a href={`#quality-${id}`} role="tab" aria-selected={tab === id} className={`nav-link${tab === id ? ' active' : ''}`}
              onClick={(e) => { e.preventDefault(); setTab(id); }}>{label}</a>
          </li>
        ))}
      </ul>
      <div className="quality-tab">
        {tab === 'model' ? <ModelQuality initialType={MODEL_RULES.find((item) => item.type === searchParams.get('type'))?.type ?? ''} />
          : tab === 'terms' ? <TermQuality /> : <IdentifierQuality />}
      </div>
    </>
  );
}

function SeveritySelect({ value, onChange, counts }: { value: Severity | ''; onChange: (value: Severity | '') => void; counts?: Record<Severity, number> }) {
  return (
    <select className="search-column-select" aria-label={t('심각도')} value={value} onChange={(e) => onChange(e.target.value as Severity | '')}>
      <option value="">{t('전체 심각도')}</option>
      {SEVERITIES.map((item) => <option key={item.severity} value={item.severity}>{counts ? `${item.label} (${counts[item.severity]})` : item.label}</option>)}
    </select>
  );
}

// ---------------------------------------------------------------- 모델 품질 진단

function ModelQuality({ initialType }: { initialType: ModelRule | '' }) {
  const grid = useRef<GridHandle>(null);
  const dialog = useDialog();
  const { user } = useAuth();
  const { schemas } = useSchemas();
  const [type, setType] = useState<ModelRule | ''>(initialType);
  const [targetType, setTargetType] = useState<TargetType | ''>('');
  const [severity, setSeverity] = useState<Severity | ''>('');
  const [schemaId, setSchemaId] = useState('');
  const [isApproval, setIsApproval] = useState('');
  const summary = useQuery({
    queryKey: ['standardization-quality-model-summary', user?.projectId],
    queryFn: () => api<ModelSummary>('/api/standardizationQuality/model/summary'),
  });

  const params: Record<string, string> = {};
  if (type) params.type = type;
  if (targetType) params.targetType = targetType;
  if (severity) params.severity = severity;
  if (schemaId) params.schemaId = schemaId;
  if (isApproval) params.isApproval = isApproval;

  // 테이블 상세에서 테이블·컬럼·승인 상태를 바꾸면 진단 결과도 달라진다
  const reload = () => {
    grid.current?.reload();
    void summary.refetch();
  };
  // 같은 카드를 다시 누르면 전체로 돌아간다
  const pick = (nextType: ModelRule | '', nextTarget: TargetType | '') => {
    const same = type === nextType && targetType === nextTarget;
    setType(same ? '' : nextType);
    setTargetType(same ? '' : nextTarget);
  };
  const openSuggestions = (row: Row) => {
    dialog.open({
      title: t('컬럼명 개선 후보 — {0}.{1}', row.tableName ?? '', row.columnName ?? ''),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <ColumnSuggestionDetail columnId={row.columnId} close={close} onChanged={reload} />,
    });
  };

  const data = summary.data;
  const count = (value: number | undefined) => (value == null ? '-' : value);
  const card = (label: string, value: number | undefined, pressed: boolean, onClick: () => void, foot?: string, pending = true) => (
    <button type="button" className="dash-stat" aria-pressed={pressed} onClick={onClick}>
      <div className="dash-stat-label">{label}</div>
      <div className={`dash-stat-value${pending && value ? ' is-pending' : ''}`}>{count(value)}</div>
      {foot && <div className="quality-stat-foot">{foot}</div>}
    </button>
  );

  return (
    <>
      {summary.isError && <p className="gh-muted" role="alert">{t('진단 요약을 불러오지 못했습니다.')}</p>}
      <div className="dash dash-stats quality-stats" aria-label={t('컬럼 진단 요약')}>
        <div className="dash-stat">
          <div className="dash-stat-label">{t('검사 대상 컬럼')}</div>
          <div className="dash-stat-value">{count(data?.columns.totalColumns)}</div>
        </div>
        {/* 한 컬럼에 여러 문제가 있을 수 있어 유형별 건수의 합과 다르다 */}
        {card(t('문제가 있는 컬럼'), data?.columns.issueColumns, type === '' && targetType === 'COLUMN', () => pick('', 'COLUMN'))}
        {MODEL_RULES.filter((item) => item.target === 'COLUMN').map((item) => (
          <Fragment key={item.type}>
            {card(item.label, data ? data.columns[COLUMN_COUNTS[item.type]!] : undefined, type === item.type, () => pick(item.type, ''))}
          </Fragment>
        ))}
      </div>
      <div className="dash dash-stats quality-stats" aria-label={t('테이블 진단 요약')}>
        <div className="dash-stat">
          <div className="dash-stat-label">{t('검사 대상 테이블')}</div>
          <div className="dash-stat-value">{count(data?.totalTables)}</div>
          <div className="quality-stat-foot">{data ? (data.tablePrefix ? t('접두어 {0}', data.tablePrefix) : t('접두어 미설정 (접두어 규칙 미적용)')) : ''}</div>
        </div>
        {card(t('문제가 있는 테이블'), data?.issueTables, type === '' && targetType === 'TABLE', () => pick('', 'TABLE'))}
        {MODEL_RULES.filter((item) => item.target === 'TABLE').map((item) => (
          <Fragment key={item.type}>
            {card(item.label, data ? data[TABLE_COUNTS[item.type]!] : undefined, type === item.type, () => pick(item.type, ''))}
          </Fragment>
        ))}
      </div>

      <Grid
        ref={grid}
        id="standardizationQualityGrid"
        minWidth={QUALITY_MODEL_GRID_MIN_WIDTH}
        columns={qualityModelColumns}
        dataUrl="/api/standardizationQuality/model"
        params={params}
        searchFilters={
          <>
            <select className="search-column-select" aria-label={t('진단 유형')} value={type} onChange={(e) => setType(e.target.value as ModelRule | '')}>
              <option value="">{t('전체 유형')}</option>
              {MODEL_RULES.map((item) => <option key={item.type} value={item.type}>{item.label}</option>)}
            </select>
            <SeveritySelect value={severity} onChange={setSeverity} counts={data ? { ERROR: data.errors, WARNING: data.warnings, INFO: data.infos } : undefined} />
            <select className="search-column-select" aria-label={t('대상')} value={targetType} onChange={(e) => setTargetType(e.target.value as TargetType | '')}>
              <option value="">{t('전체 대상')}</option>
              {TARGET_TYPES.map((item) => <option key={item.targetType} value={item.targetType}>{item.label}</option>)}
            </select>
            <select className="search-column-select" aria-label={t('스키마')} value={schemaId} onChange={(e) => setSchemaId(e.target.value)}>
              <option value="">{t('전체 스키마')}</option>
              {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
            </select>
            <select className="search-column-select" aria-label={t('승인 상태')} value={isApproval} onChange={(e) => setIsApproval(e.target.value)}>
              <option value="">{t('승인 전체')}</option>
              <option value="true">{t('승인')}</option>
              <option value="false">{t('승인 대기')}</option>
            </select>
          </>
        }
        emptyText={type || targetType || severity || schemaId || isApproval ? t('조건에 맞는 진단 결과가 없습니다.') : t('진단 결과가 없습니다. 모든 테이블·컬럼이 표준을 따르고 있습니다.')}
        renderCell={(field, row) => {
          if (field === 'type') {
            const item = MODEL_RULES.find((e) => e.type === row.type);
            return <Label variant={item?.variant}>{item?.label ?? row.type}</Label>;
          }
          if (field === 'severity') return <SeverityLabel severity={row.severity} />;
          if (field === 'targetType') return TARGET_TYPES.find((e) => e.targetType === row.targetType)?.label ?? row.targetType;
          if (field === 'schemaName' || field === 'tableName') return <Mono>{row[field]}</Mono>;
          if (field === 'columnName') return row.columnName ? <Mono>{row.columnName}</Mono> : <Muted>-</Muted>;
          if (field === 'columnDesc') return row.columnDesc ? undefined : <Muted>-</Muted>;
          if (field === 'actualValue' || field === 'expectedValue') return row[field] ? <Mono>{row[field]}</Mono> : <Muted>-</Muted>;
          if (field === 'isApproval') return <Approval value={row.isApproval ? 'Y' : 'N'} />;
          if (field === 'links') {
            return (
              <span className="quality-links">
                <TableLinks tableId={row.tableId} schemaId={row.schemaId} columnId={row.columnId ?? undefined} onChanged={reload} />
                {row.targetType === 'COLUMN' && SUGGESTION_TYPES.includes(row.type) && (
                  <button type="button" className="btn btn-sm" onClick={() => openSuggestions(row)}>{t('개선 후보')}</button>
                )}
              </span>
            );
          }
          return undefined;
        }}
      />
    </>
  );
}

// ---------------------------------------------------------------- 표준 용어 진단

function TermQuality() {
  const dialog = useDialog();
  const { user } = useAuth();
  const [type, setType] = useState<TermRule | ''>('');
  const [severity, setSeverity] = useState<Severity | ''>('');
  const summary = useQuery({
    queryKey: ['standardization-quality-term-summary', user?.projectId],
    queryFn: () => api<TermSummary>('/api/standardizationQuality/terms/summary'),
  });

  const params: Record<string, string> = {};
  if (type) params.type = type;
  if (severity) params.severity = severity;

  const data = summary.data;
  const count = (value: number | undefined) => (value == null ? '-' : value);
  const severityCount = (value: Severity) => (data ? { ERROR: data.errors, WARNING: data.warnings, INFO: data.infos }[value] : undefined);
  const openGroup = (row: Row) => {
    const group = row as TermGroup;
    dialog.open({
      title: t('표준 용어 진단 상세'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <TermGroupDetail group={group} close={close} />,
    });
  };

  return (
    <>
      {summary.isError && <p className="gh-muted" role="alert">{t('진단 요약을 불러오지 못했습니다.')}</p>}
      <div className="dash dash-stats quality-stats" aria-label={t('표준 용어 진단 요약')}>
        <div className="dash-stat">
          <div className="dash-stat-label">{t('검사 대상 용어')}</div>
          <div className="dash-stat-value">{count(data?.totalTerms)}</div>
        </div>
        <button type="button" className="dash-stat" aria-pressed={type === '' && severity === ''} onClick={() => { setType(''); setSeverity(''); }}>
          <div className="dash-stat-label">{t('진단된 용어')}</div>
          <div className={`dash-stat-value${data?.issueTerms ? ' is-pending' : ''}`}>{count(data?.issueTerms)}</div>
          <div className="quality-stat-foot">{t('한 그룹 이상에 속한 용어')}</div>
        </button>
        {SEVERITIES.map((item) => (
          <button key={item.severity} type="button" className="dash-stat" aria-pressed={severity === item.severity && type === ''}
            onClick={() => { setType(''); setSeverity(severity === item.severity && type === '' ? '' : item.severity); }}>
            <div className="dash-stat-label">{t('{0} 그룹', item.label)}</div>
            <div className={`dash-stat-value${item.severity !== 'INFO' && severityCount(item.severity) ? ' is-pending' : ''}`}>{count(severityCount(item.severity))}</div>
          </button>
        ))}
      </div>
      <div className="dash dash-stats quality-stats quality-rule-stats" aria-label={t('진단 유형별 그룹 수')}>
        {TERM_RULES.map((item) => (
          <button key={item.type} type="button" className="dash-stat" aria-pressed={type === item.type}
            onClick={() => { setSeverity(''); setType(type === item.type ? '' : item.type); }}>
            <div className="dash-stat-label">{item.label}</div>
            <div className="dash-stat-value">{count(data?.groups[item.type])}</div>
            <div className="quality-stat-foot">{item.hint}</div>
          </button>
        ))}
      </div>

      <Grid
        id="standardizationQualityTermGrid"
        minWidth={QUALITY_TERM_GRID_MIN_WIDTH}
        columns={qualityTermColumns}
        dataUrl="/api/standardizationQuality/terms"
        params={params}
        searchFilters={
          <>
            <select className="search-column-select" aria-label={t('진단 유형')} value={type} onChange={(e) => setType(e.target.value as TermRule | '')}>
              <option value="">{t('전체 유형')}</option>
              {TERM_RULES.map((item) => <option key={item.type} value={item.type}>{item.label}</option>)}
            </select>
            <SeveritySelect value={severity} onChange={setSeverity} />
          </>
        }
        emptyText={type || severity ? t('조건에 맞는 진단 결과가 없습니다.') : t('진단 결과가 없습니다. 중복·유사어 후보가 없습니다.')}
        onRowClick={openGroup}
        renderCell={(field, row) => {
          const group = row as TermGroup;
          if (field === 'severity') return <SeverityLabel severity={group.severity} />;
          if (field === 'type') return TERM_RULES.find((e) => e.type === group.type)?.label ?? group.type;
          if (field === 'groupKey') return group.groupKey ? <Mono>{group.groupKey}</Mono> : <Muted>-</Muted>;
          if (field === 'members') {
            return (
              <span className="quality-chips">
                {group.members.map((member) => (
                  <Label key={member.termId} variant={member.isApproval ? '' : 'attention'} title={member.domainName ?? undefined}>
                    {member.termName}{member.abbreviation && <> · <span className="gh-mono">{member.abbreviation}</span></>}
                  </Label>
                ))}
              </span>
            );
          }
          return undefined;
        }}
      />
      <DomainDuplicates />
    </>
  );
}

/**
 * 표준 도메인 이름 중복 ([DICT-2]) : 같은 프로젝트에서 대소문자를 무시하고 이름이 같은 도메인 묶음. 예전 데이터 진단만 한다 — 정리(삭제·합치기)는 사람이 표준 도메인 화면에서.
 * 이 이름을 쓰는 표준 용어 등록·수정은 409 로 거절된다
 */
function DomainDuplicates() {
  const { user } = useAuth();
  const query = useQuery({
    queryKey: ['standardization-quality-domain-duplicates', user?.projectId],
    queryFn: () => api<{ groups: DomainDuplicateGroup[] }>('/api/standardizationQuality/domains/duplicates'),
  });
  const groups = query.data?.groups ?? [];
  return (
    <section aria-labelledby="quality-domain-duplicates-title">
      <h2 id="quality-domain-duplicates-title" className="quality-subtitle">{t('표준 도메인 이름 중복 (대소문자 무시)')}</h2>
      {query.isError ? <p className="gh-muted" role="alert">{t('도메인 이름 중복 진단을 불러오지 못했습니다.')}</p>
        : query.isLoading ? <p className="gh-muted">{t('불러오는 중…')}</p>
          : groups.length === 0 ? <p className="gh-muted">{t('같은 이름의 표준 도메인이 없습니다.')}</p>
            : (
              <>
                <p className="gh-muted">{t('같은 이름의 표준 도메인이 {0}묶음 있습니다. 이 이름을 쓰는 표준 용어는 등록·수정할 수 없습니다. 자동으로 정리하지 않으니 표준 도메인 화면에서 확인해주세요.', groups.length)}</p>
                <ul className="quality-candidates">
                  {groups.map((group) => (
                    <li key={group.normalizedName} className="quality-candidate">
                      <div className="quality-candidate-head">
                        <Mono>{group.normalizedName}</Mono>
                        <Muted>{t('도메인 {0}개', group.count)}</Muted>
                      </div>
                      <span className="quality-chips">
                        {group.members.map((member) => (
                          <Label key={member.domainId} variant={member.isApproval ? '' : 'attention'}>
                            {member.domainName} · #{member.domainId} · {member.isApproval ? t('승인') : t('미승인')} · {t('용어 {0} · 컬럼 {1}', member.termCount, member.columnCount)}
                          </Label>
                        ))}
                      </span>
                    </li>
                  ))}
                </ul>
              </>
            )}
    </section>
  );
}

// ---------------------------------------------------------------- DBMS 식별자 진단

function IdentifierQuality() {
  const grid = useRef<GridHandle>(null);
  const { user } = useAuth();
  const { schemas } = useSchemas();
  const [type, setType] = useState<IdentifierRule | ''>('');
  const [severity, setSeverity] = useState<Severity | ''>('');
  const [kind, setKind] = useState<IdentifierKind | ''>('');
  const [schemaId, setSchemaId] = useState('');
  const summary = useQuery({
    queryKey: ['standardization-quality-identifier-summary', user?.projectId],
    queryFn: () => api<IdentifierSummary>('/api/standardizationQuality/identifiers/summary'),
  });

  const params: Record<string, string> = {};
  if (type) params.type = type;
  if (severity) params.severity = severity;
  if (kind) params.kind = kind;
  if (schemaId) params.schemaId = schemaId;

  const reload = () => {
    grid.current?.reload();
    void summary.refetch();
  };
  const data = summary.data;
  const count = (value: number | undefined) => (value == null ? '-' : value);

  return (
    <>
      {summary.isError && <p className="gh-muted" role="alert">{t('진단 요약을 불러오지 못했습니다.')}</p>}
      <p className="gh-muted quality-identifier-basis">
        {data ? t('프로젝트 DBMS {0} 기준 — {1}. 예약어는 검사하지 않습니다.', data.dbLabel, data.lengthBasis) : ''}
      </p>
      <div className="dash dash-stats quality-stats" aria-label={t('DBMS 식별자 진단 요약')}>
        <div className="dash-stat">
          <div className="dash-stat-label">{t('검사 대상')}</div>
          <div className="dash-stat-value">{count(data?.totalTables)}</div>
          <div className="quality-stat-foot">{data ? t('테이블 {0} · 컬럼 {1} · 스키마 {2}', data.totalTables, data.totalColumns, data.totalSchemas) : ''}</div>
        </div>
        {SEVERITIES.filter((item) => item.severity !== 'INFO').map((item) => (
          <button key={item.severity} type="button" className="dash-stat" aria-pressed={severity === item.severity && type === ''}
            onClick={() => { setType(''); setSeverity(severity === item.severity && type === '' ? '' : item.severity); }}>
            <div className="dash-stat-label">{item.label}</div>
            <div className={`dash-stat-value${data && (item.severity === 'ERROR' ? data.errors : data.warnings) ? ' is-pending' : ''}`}>
              {count(data ? (item.severity === 'ERROR' ? data.errors : data.warnings) : undefined)}
            </div>
          </button>
        ))}
      </div>
      <div className="dash dash-stats quality-stats quality-rule-stats" aria-label={t('진단 유형별 건수')}>
        {IDENTIFIER_RULES.map((item) => (
          <button key={item.type} type="button" className="dash-stat" aria-pressed={type === item.type}
            onClick={() => { setSeverity(''); setType(type === item.type ? '' : item.type); }}>
            <div className="dash-stat-label">{item.label}</div>
            <div className="dash-stat-value">{count(data?.rules[item.type])}</div>
            <div className="quality-stat-foot">{item.hint}</div>
          </button>
        ))}
      </div>

      <Grid
        ref={grid}
        id="standardizationQualityIdentifierGrid"
        minWidth={QUALITY_IDENTIFIER_GRID_MIN_WIDTH}
        columns={qualityIdentifierColumns}
        dataUrl="/api/standardizationQuality/identifiers"
        params={params}
        searchFilters={
          <>
            <select className="search-column-select" aria-label={t('진단 유형')} value={type} onChange={(e) => setType(e.target.value as IdentifierRule | '')}>
              <option value="">{t('전체 유형')}</option>
              {IDENTIFIER_RULES.map((item) => <option key={item.type} value={item.type}>{item.label}</option>)}
            </select>
            <SeveritySelect value={severity} onChange={setSeverity} />
            <select className="search-column-select" aria-label={t('대상')} value={kind} onChange={(e) => setKind(e.target.value as IdentifierKind | '')}>
              <option value="">{t('전체 대상')}</option>
              {IDENTIFIER_KINDS.map((item) => <option key={item.kind} value={item.kind}>{item.label}</option>)}
            </select>
            <select className="search-column-select" aria-label={t('스키마')} value={schemaId} onChange={(e) => setSchemaId(e.target.value)}>
              <option value="">{t('전체 스키마')}</option>
              {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
            </select>
          </>
        }
        emptyText={type || severity || kind || schemaId ? t('조건에 맞는 진단 결과가 없습니다.') : t('진단 결과가 없습니다. 모든 이름이 프로젝트 DBMS 식별자 기준을 따릅니다.')}
        renderCell={(field, row) => {
          if (field === 'type') {
            const item = IDENTIFIER_RULES.find((e) => e.type === row.type);
            return <Label variant={item?.variant}>{item?.label ?? row.type}</Label>;
          }
          if (field === 'severity') return <SeverityLabel severity={row.severity} />;
          if (field === 'kind') return IDENTIFIER_KINDS.find((e) => e.kind === row.kind)?.label ?? row.kind;
          if (field === 'schemaName' || field === 'tableName' || field === 'name') return row[field] ? <Mono>{row[field]}</Mono> : <Muted>-</Muted>;
          if (field === 'ddlName') return row.ddlName ? <Mono>{row.ddlName}</Mono> : <Muted>-</Muted>;
          if (field === 'length') return row.length != null ? `${row.length} / ${row.maxLength}` : <Muted>-</Muted>;
          if (field === 'message') {
            return (
              <span className="quality-identifier-message">
                {row.message}
                <Muted>{t('기준')} : {row.basis}</Muted>
                <Muted>{row.guide}</Muted>
              </span>
            );
          }
          if (field === 'links') {
            return row.tableId ? <TableLinks tableId={row.tableId} schemaId={row.schemaId} columnId={row.columnId ?? undefined} onChanged={reload} /> : <Muted>-</Muted>;
          }
          return undefined;
        }}
      />
    </>
  );
}
