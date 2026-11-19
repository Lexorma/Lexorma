import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { BellIcon, BookIcon, ColumnsIcon, NumberIcon, TableIcon, TypographyIcon } from '@primer/octicons-react';
import { useAuth, type MenuId } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { formatDateTime, formatRelative, initial } from '../../lib/format';
import { useNoticeDialogs } from '../notice/noticeDialogs';
import { t, tv, tx } from '../../lib/i18n';
import { Bar, CountUp, Donut, Legend, StackedBar, percentText } from './charts';

interface Dashboard {
  notices: { id: number; title: string; createdBy: string; created: string; isHit: string }[];
}

type ApprovalType = 'WORD' | 'DOMAIN' | 'TERM' | 'TABLE' | 'COLUMN';

/** GET /api/dashboard/stats (DashboardStatsDto) */
interface Stats {
  counts: {
    words: number; domains: number; terms: number; schemas: number; tables: number; columns: number;
    relations: number; fkRelations: number; logicalRelations: number; indexes: number; constraints: number; uniqueConstraints: number; checkConstraints: number;
  };
  approvals: { type: ApprovalType; approved: number; pending: number }[];
  canApproveDictionary: boolean;
  canApproveModel: boolean;
  standard: { totalColumns: number; linkedColumns: number; linkedApprovedTermColumns: number; compliantColumns: number };
  quality: {
    issueColumns: number; termNotLinked: number; columnNameMismatch: number; domainDefinitionMismatch: number;
    tablesWithoutComment: number; columnsWithoutComment: number; tablesWithoutBizDescription: number; columnsWithoutBizDescription: number;
  };
  model: { schemas: { schemaId: number; schemaName: string; tables: number }[]; columnsPerTable: { label: string; tables: number }[]; tablesWithoutRelation: number };
  generatedAt: string;
}

/** 유형별 이름·화면·메뉴 (승인 대기·승인 상태 분포가 같이 쓴다) */
const TYPES: Record<ApprovalType, { label: string; to: string; menu: MenuId; dictionary: boolean }> = {
  WORD: { label: t('표준 단어'), to: '/standardWord', menu: 'standard-word', dictionary: true },
  DOMAIN: { label: t('표준 도메인'), to: '/standardDomain', menu: 'standard-domain', dictionary: true },
  TERM: { label: t('표준 용어'), to: '/standardTerm', menu: 'standard-term', dictionary: true },
  TABLE: { label: t('테이블'), to: '/tableManage', menu: 'table-manage', dictionary: false },
  COLUMN: { label: t('컬럼'), to: '/columnManage', menu: 'column-manage', dictionary: false },
};

const APPROVED_COLOR = 'var(--gh-success)';
const PENDING_COLOR = 'var(--gh-attention)';
const fmt = (n: number) => n.toLocaleString();

/**
 * 대시보드 : 프로젝트 현황(등록 수) · 표준 적용률 · 승인 대기 · 승인 상태 분포 · 데이터 모델 현황 · 데이터 품질 요약 · 공지사항.
 * 통계는 GET /api/dashboard/stats (VIEWER, DB 집계 — 메뉴 dashboard-stats). 승인 전 회원(NOT_APPROVE)은 인사와 공지만 본다.
 * 승인 처리는 여기서 하지 않는다 — 각 관리 화면(서버가 권한을 다시 확인)으로 이동한다. 최근 활동은 2026-11-15 개편에서 뺐다(변경 이력은 모델 변경 이력 화면).
 */
export function DashboardPage() {
  const { displayName, canView } = useAuth();
  const { openNoticeDetail } = useNoticeDialogs();
  const showStats = canView('dashboard-stats');
  const base = useQuery({ queryKey: ['dashboard'], queryFn: () => api<Dashboard>('/api/dashboard') });
  const stats = useQuery({ queryKey: ['dashboard-stats'], queryFn: () => api<Stats>('/api/dashboard/stats', { silent: true }), enabled: showStats });

  return (
    <section className="dash">
      <div className="dash-hero">
        <div className="dash-avatar dash-avatar-lg">{initial(displayName)}</div>
        <div>
          <h1 className="dash-hero-title">
            {tx('{0}님, 안녕하세요', <span>{displayName}</span>)}
          </h1>
          <p className="dash-hero-sub">
            {showStats ? t('프로젝트의 데이터 표준화·데이터 모델·승인·품질 현황을 한눈에 확인하세요.') : t('프로젝트 공지사항을 확인하세요.')}
          </p>
        </div>
      </div>

      {showStats && (stats.isPending ? <StatsSkeleton />
        : stats.isError ? (
          <div className="dash-error" role="alert">
            {t('현황을 불러오지 못했습니다.')} <button type="button" className="dash-btn" onClick={() => void stats.refetch()}>{t('다시 시도')}</button>
          </div>
        ) : <StatsView s={stats.data} />)}

      <NoticeBox data={base.data} loading={base.isPending} failed={base.isError} onOpen={(id) => openNoticeDetail(id, { onClose: () => void base.refetch() })} />
    </section>
  );
}

/**
 * 현황 첫 조회 중 (UI-1) : 실제 카드·상자와 같은 자리·높이의 스켈레톤 (조회가 끝나도 레이아웃이 크게 움직이지 않게). 빠른 응답에서는
 * 늦게 나타나 깜빡이지 않는다(motion.css). 상태 문구는 보조 기술에만 알리고 덩어리는 감춘다. 다시 조회할 때는 쓰지 않는다(앞 값을 둔다)
 */
function StatsSkeleton() {
  return (
    <div role="status">
      <span className="visually-hidden">{t('현황을 불러오는 중…')}</span>
      <div aria-hidden="true">
        <div className="dash-kpis">
          {[0, 1, 2, 3, 4].map((i) => (
            <div key={i} className="dash-stat is-static is-skeleton"><span className="skeleton skeleton-line is-mid" /><span className="skeleton skeleton-value" /></div>
          ))}
        </div>
        <div className="dash-grid">
          <SkeletonBox />
          <SkeletonBox />
        </div>
        <SkeletonBox />
        <div className="dash-grid dash-grid-even">
          <SkeletonBox tall />
          <SkeletonBox tall />
        </div>
      </div>
    </div>
  );
}

/** 스켈레톤 상자 : 실제 상자(머리글 + 본문)와 같은 테두리·여백, 본문 높이는 실제 상자와 비슷하게 (tall = 모델·품질 상자) */
function SkeletonBox({ tall = false }: { tall?: boolean }) {
  return (
    <div className="dash-box is-skeleton">
      <div className="dash-box-header"><span className="skeleton skeleton-line is-short" /></div>
      <div className="dash-box-body"><span className={`skeleton dash-skeleton-body${tall ? ' is-tall' : ''}`} /></div>
    </div>
  );
}

function StatsView({ s }: { s: Stats }) {
  const { canView } = useAuth();
  const c = s.counts;
  return (
    <>
      <div className="dash-kpis">
        <Kpi menu="standard-word" to="/standardWord" icon={<TypographyIcon />} color="icon-blue" label={t('표준 단어')} value={c.words} />
        <Kpi menu="standard-domain" to="/standardDomain" icon={<NumberIcon />} color="icon-blue" label={t('표준 도메인')} value={c.domains} />
        <Kpi menu="standard-term" to="/standardTerm" icon={<BookIcon />} color="icon-blue" label={t('표준 용어')} value={c.terms} />
        <Kpi menu="table-manage" to="/tableManage" icon={<TableIcon />} color="icon-green" label={t('테이블')} value={c.tables} />
        <Kpi menu="column-manage" to="/columnManage" icon={<ColumnsIcon />} color="icon-purple" label={t('컬럼')} value={c.columns} />
      </div>

      <div className="dash-grid">
        <StandardBox s={s} canOpen={canView('standardization-quality')} />
        <PendingBox s={s} />
      </div>

      <ApprovalBox s={s} />

      <div className="dash-grid dash-grid-even">
        <ModelBox s={s} />
        <QualityBox s={s} canOpen={canView('standardization-quality')} />
      </div>
      <p className="dash-foot">{t('조회 시각 {0} · 저장된 데이터를 조회할 때 센 값입니다.', formatDateTime(s.generatedAt))}</p>
    </>
  );
}

/** 등록 수 카드 : 볼 수 있는 화면이면 그 화면으로 */
function Kpi({ menu, to, icon, color, label, value }: { menu: MenuId; to: string; icon: ReactNode; color: string; label: string; value: number }) {
  const { canView } = useAuth();
  const body = (
    <>
      <div className="dash-stat-head">
        <span className={`dash-stat-icon ${color}`}>{icon}</span>
        <span className="dash-stat-label">{label}</span>
      </div>
      <div className="dash-stat-value"><CountUp value={value} /></div>
    </>
  );
  return canView(menu) ? <Link to={to} className="dash-stat">{body}</Link> : <div className="dash-stat is-static">{body}</div>;
}

function Box({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <div className="dash-box">
      <div className="dash-box-header">
        <h2 className="dash-box-title">{title}</h2>
        {action}
      </div>
      <div className="dash-box-body">{children}</div>
    </div>
  );
}

/** 표준 적용률 : 연결률(도넛) + 규칙 준수(M-5 진단에서 문제 없는 컬럼) */
function StandardBox({ s, canOpen }: { s: Stats; canOpen: boolean }) {
  const st = s.standard;
  const unlinked = st.totalColumns - st.linkedColumns;
  return (
    <Box title={t('데이터 표준 적용률')}
         action={canOpen && unlinked > 0 ? <Link to="/standardizationQuality?type=TERM_NOT_LINKED" className="dash-btn">{t('미적용 컬럼 보기')}</Link> : undefined}>
      {st.totalColumns === 0 ? <p className="dash-empty">{t('컬럼이 없어 적용률을 계산할 대상이 없습니다.')}</p> : (
        <div className="dash-standard">
          <Donut part={st.linkedColumns} whole={st.totalColumns} label={t('표준 연결률')} />
          <dl className="dash-figures">
            <dt>{t('표준 연결률')}</dt><dd><strong>{percentText(st.linkedColumns, st.totalColumns)}</strong></dd>
            <dt>{t('적용 컬럼')}</dt><dd>{fmt(st.linkedColumns)}</dd>
            <dt>{t('미적용 컬럼')}</dt><dd className={unlinked > 0 ? 'is-attention' : undefined}>{fmt(unlinked)}</dd>
            <dt>{t('전체 컬럼')}</dt><dd>{fmt(st.totalColumns)}</dd>
            <dt>{t('승인된 용어에 연결')}</dt><dd>{fmt(st.linkedApprovedTermColumns)}</dd>
            <dt>{t('표준 규칙 준수율')}</dt><dd><strong>{percentText(st.compliantColumns, st.totalColumns)}</strong> <span className="dash-muted">({fmt(st.compliantColumns)})</span></dd>
          </dl>
        </div>
      )}
      <p className="dash-note">{t('연결률은 표준 용어가 연결된 컬럼의 비율입니다(용어의 승인 여부와 무관). 규칙 준수율은 표준화 품질 진단에서 문제가 없는 컬럼(용어 연결·컬럼명 = 영문 약어·도메인 정의 일치)의 비율입니다.')}</p>
    </Box>
  );
}

/** 승인 대기 : 유형별 건수와 화면 이동, 처리 가능 여부(서버 판정). 승인은 각 화면에서 한다 */
function PendingBox({ s }: { s: Stats }) {
  const { canView } = useAuth();
  const total = s.approvals.reduce((sum, a) => sum + a.pending, 0);
  return (
    <Box title={t('승인 대기')} action={<span className="dash-counter" aria-label={t('승인 대기 {0}건', total)}><CountUp value={total} /></span>}>
      <ul className="dash-pending">
        {s.approvals.map((a) => {
          const type = TYPES[a.type];
          const canApprove = type.dictionary ? s.canApproveDictionary : s.canApproveModel;
          const name = <span className="dash-pending-name">{type.label}</span>;
          return (
            <li key={a.type} className="dash-pending-item">
              {canView(type.menu) ? <Link to={type.to}>{name}</Link> : name}
              {a.pending > 0 && <span className={`dash-state ${canApprove ? 'is-new' : 'is-read'}`}>{canApprove ? t('승인 가능') : t('조회만')}</span>}
              <span className={`dash-pending-count${a.pending > 0 ? ' is-pending' : ''}`}>{fmt(a.pending)}</span>
            </li>
          );
        })}
      </ul>
      {total === 0 && <p className="dash-empty">{t('승인을 기다리는 항목이 없습니다.')}</p>}
    </Box>
  );
}

/** 승인 상태 분포 : 유형별 누적 막대 (승인 / 승인 대기). 반려 상태는 이 데이터에 없다 */
function ApprovalBox({ s }: { s: Stats }) {
  return (
    <Box title={t('승인 상태별 분포')} action={<Legend items={[{ label: t('승인'), color: APPROVED_COLOR }, { label: t('승인 대기'), color: PENDING_COLOR }]} />}>
      <table className="dash-approvals">
        <thead><tr><th scope="col">{t('유형')}</th><th scope="col" className="dash-col-chart"><span className="sr-only">{t('분포')}</span></th><th scope="col">{t('승인')}</th><th scope="col">{t('승인 대기')}</th><th scope="col">{t('전체')}</th></tr></thead>
        <tbody>
          {s.approvals.map((a) => {
            const total = a.approved + a.pending;
            return (
              <tr key={a.type}>
                <th scope="row">{TYPES[a.type].label}</th>
                <td className="dash-col-chart">
                  <StackedBar label={TYPES[a.type].label} segments={[
                    { key: 'approved', label: t('승인'), value: a.approved, color: APPROVED_COLOR },
                    { key: 'pending', label: t('승인 대기'), value: a.pending, color: PENDING_COLOR },
                  ]} />
                </td>
                <td>{fmt(a.approved)}</td>
                <td className={a.pending > 0 ? 'is-attention' : undefined}>{fmt(a.pending)}</td>
                <td>{fmt(total)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="dash-note">{t('승인 상태는 승인 / 승인 대기 두 가지입니다(반려 상태는 없음).')}</p>
    </Box>
  );
}

/** 데이터 모델 현황 : 구성 수, 스키마별 테이블, 컬럼 수 구간별 테이블, 관계 없는 테이블(참고) */
function ModelBox({ s }: { s: Stats }) {
  const c = s.counts;
  const maxSchema = Math.max(0, ...s.model.schemas.map((x) => x.tables));
  const maxBucket = Math.max(0, ...s.model.columnsPerTable.map((x) => x.tables));
  return (
    <Box title={t('데이터 모델 현황')}>
      <dl className="dash-model">
        <div><dt>{t('스키마')}</dt><dd>{fmt(c.schemas)}</dd></div>
        <div><dt>{t('테이블')}</dt><dd>{fmt(c.tables)}</dd></div>
        <div><dt>{t('컬럼')}</dt><dd>{fmt(c.columns)}</dd></div>
        <div><dt>{t('관계')}</dt><dd>{fmt(c.relations)}<span className="dash-muted">{t('FK {0} · 논리 {1}', fmt(c.fkRelations), fmt(c.logicalRelations))}</span></dd></div>
        <div><dt>{t('인덱스')}</dt><dd>{fmt(c.indexes)}</dd></div>
        <div><dt>{t('제약조건')}</dt><dd>{fmt(c.constraints)}<span className="dash-muted">{t('UNIQUE {0} · CHECK {1}', fmt(c.uniqueConstraints), fmt(c.checkConstraints))}</span></dd></div>
      </dl>
      {c.tables === 0 ? <p className="dash-empty">{t('등록된 테이블이 없습니다.')}</p> : (
        <>
          <h3 className="dash-sub">{t('스키마별 테이블 수')}</h3>
          <ul className="dash-bars">
            {s.model.schemas.map((x) => (
              <li key={x.schemaId}><span className="dash-bars-label dash-mono">{x.schemaName}</span><Bar value={x.tables} max={maxSchema} label={x.schemaName} /><span className="dash-bars-value">{fmt(x.tables)}</span></li>
            ))}
          </ul>
          <h3 className="dash-sub">{t('컬럼 수별 테이블 수')}</h3>
          <ul className="dash-bars">
            {s.model.columnsPerTable.map((x) => (
              <li key={x.label}><span className="dash-bars-label">{t('컬럼 {0}개', x.label)}</span><Bar value={x.tables} max={maxBucket} color="var(--gh-done)" label={t('컬럼 {0}개', x.label)} /><span className="dash-bars-value">{fmt(x.tables)}</span></li>
            ))}
          </ul>
          <p className="dash-note">{t('등록된 관계가 없는 테이블 {0}개 — 관계가 없어도 잘못된 모델은 아닙니다(참고).', fmt(s.model.tablesWithoutRelation))}</p>
        </>
      )}
    </Box>
  );
}

/** 데이터 품질 요약 : 실제로 판정하는 항목만 (표준화 품질 진단 M-5 + 비어 있는 설명). 항목은 겹칠 수 있어 합계를 내지 않는다 */
function QualityBox({ s, canOpen }: { s: Stats; canOpen: boolean }) {
  const q = s.quality;
  const columns = s.counts.columns;
  const tables = s.counts.tables;
  const rows: { key: string; label: string; value: number; base: number; to?: string }[] = [
    { key: 'TERM_NOT_LINKED', label: t('표준 용어 미연결 컬럼'), value: q.termNotLinked, base: columns, to: '/standardizationQuality?type=TERM_NOT_LINKED' },
    { key: 'COLUMN_NAME_MISMATCH', label: t('컬럼명 불일치 컬럼'), value: q.columnNameMismatch, base: columns, to: '/standardizationQuality?type=COLUMN_NAME_MISMATCH' },
    { key: 'DOMAIN_DEFINITION_MISMATCH', label: t('도메인 정의 불일치 컬럼'), value: q.domainDefinitionMismatch, base: columns, to: '/standardizationQuality?type=DOMAIN_DEFINITION_MISMATCH' },
    { key: 'tableComment', label: t('코멘트가 없는 테이블'), value: q.tablesWithoutComment, base: tables },
    { key: 'columnComment', label: t('코멘트(컬럼 설명)가 없는 컬럼'), value: q.columnsWithoutComment, base: columns },
    { key: 'tableBiz', label: t('업무 설명이 없는 테이블'), value: q.tablesWithoutBizDescription, base: tables },
    { key: 'columnBiz', label: t('업무 설명이 없는 컬럼'), value: q.columnsWithoutBizDescription, base: columns },
  ];
  return (
    <Box title={t('데이터 품질 요약')} action={canOpen ? <Link to="/standardizationQuality" className="dash-btn">{t('품질 진단')}</Link> : undefined}>
      {columns === 0 && tables === 0 ? <p className="dash-empty">{t('진단할 테이블·컬럼이 없습니다.')}</p> : (
        <>
          <p className="dash-quality-head">{tx('표준화 진단에서 문제가 있는 컬럼 {0}개 / 전체 {1}개', <strong className={q.issueColumns > 0 ? 'is-attention' : undefined}>{fmt(q.issueColumns)}</strong>, fmt(columns))}</p>
          <ul className="dash-bars dash-quality">
            {rows.map((r) => {
              const label = canOpen && r.to ? <Link to={r.to}>{r.label}</Link> : r.label;
              return (
                <li key={r.key}>
                  <span className="dash-bars-label">{label}</span>
                  <Bar value={r.value} max={r.base} color="var(--gh-attention)" label={r.label} />
                  <span className="dash-bars-value">{fmt(r.value)}<span className="dash-muted"> / {fmt(r.base)}</span></span>
                </li>
              );
            })}
          </ul>
        </>
      )}
      <p className="dash-note">{t('한 컬럼에 여러 문제가 있을 수 있어 항목별 건수를 더하지 않습니다. 관계가 없는 테이블과 AI 추정 관계는 품질 문제로 세지 않습니다.')}</p>
    </Box>
  );
}

/** 프로젝트 공지사항 (기존 그대로 — 최근 등록 순, 읽음 표시) */
function NoticeBox({ data, loading, failed, onOpen }: { data?: Dashboard; loading: boolean; failed: boolean; onOpen: (id: number) => void }) {
  return (
    <div className="dash-box dash-notices">
      <div className="dash-box-header">
        <h2 className="dash-box-title">{t('프로젝트 공지사항')}</h2>
        <Link to="/notice" className="dash-btn">{t('전체 보기')}</Link>
      </div>
      {loading ? <p className="dash-loading" role="status">{t('불러오는 중…')}</p>
        : failed ? <p className="dash-error" role="alert">{t('공지사항을 불러오지 못했습니다.')}</p>
        : data && data.notices.length > 0 ? (
          <ul className="dash-list" id="noticeList">
            {data.notices.map((notice) => {
              const read = notice.isHit === '확인';
              return (
                <li key={notice.id} className="dash-list-item notice-item" tabIndex={0} role="button"
                  onClick={() => onOpen(notice.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onOpen(notice.id);
                    }
                  }}
                >
                  <span className={`dash-unread-dot${read ? ' is-read' : ''}`} title={tv(notice.isHit)} />
                  <div className="dash-list-body">
                    <div className="dash-list-title">{notice.title}</div>
                    <div className="dash-list-meta">
                      <span>{notice.createdBy}</span>
                      <span aria-hidden="true">·</span>
                      <RelativeTime value={notice.created} />
                    </div>
                  </div>
                  <span className={`dash-state ${read ? 'is-read' : 'is-new'}`}>{read ? t('읽음') : t('새 글')}</span>
                </li>
              );
            })}
          </ul>
        ) : (
          <div className="dash-blank">
            <BellIcon size={24} />
            <p>{t('공지사항이 없습니다.')}</p>
          </div>
        )}
    </div>
  );
}

/** 상대 시간 (마우스를 올리면 원래 일시) */
function RelativeTime({ value, className }: { value: string; className?: string }) {
  return (
    <time className={className} dateTime={value} title={formatDateTime(value)}>
      {formatRelative(value)}
    </time>
  );
}
