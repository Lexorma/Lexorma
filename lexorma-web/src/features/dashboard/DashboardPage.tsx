import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { BellIcon, ClockIcon, ColumnsIcon, NoteIcon, TableIcon } from '@primer/octicons-react';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { formatDateTime, formatRelative, initial } from '../../lib/format';
import { useNoticeDialogs } from '../notice/noticeDialogs';
import { t, tv, tx } from '../../lib/i18n';

interface Dashboard {
  pendingStandardCount: number;
  pendingTableCount: number;
  pendingColumnCount: number;
  notices: { id: number; title: string; createdBy: string; created: string; isHit: string }[];
  recentChanges: { type: string; name: string; modifiedBy: string | null; modifiedAt: string | null }[];
}

/** 대시보드 : 승인 대기 현황 / 최근 활동 / 프로젝트 공지사항 */
export function DashboardPage() {
  const { displayName, canView } = useAuth();
  const { openNoticeDetail } = useNoticeDialogs();
  const { data, refetch } = useQuery({ queryKey: ['dashboard'], queryFn: () => api<Dashboard>('/api/dashboard') });

  if (!data) return null;
  const pendingTotal = data.pendingStandardCount + data.pendingTableCount + data.pendingColumnCount;
  const openNotice = (id: number) => openNoticeDetail(id, { onClose: () => refetch() });

  return (
    <section className="dash">
      <div className="dash-hero">
        <div className="dash-avatar dash-avatar-lg">{initial(displayName)}</div>
        <div>
          <h1 className="dash-hero-title">
            {tx('{0}님, 안녕하세요', <span>{displayName}</span>)}
          </h1>
          <p className="dash-hero-sub">{t('프로젝트의 승인 대기 현황과 최근 활동을 한눈에 확인하세요.')}</p>
        </div>
      </div>

      {/* 승인 대기 현황 : 볼 수 있는 화면(메뉴 권한)만 표시 */}
      {(canView('standard-domain') || canView('table-manage') || canView('column-manage')) && (
        <>
          <div className="dash-section-head">
            <h2 className="dash-section-title">{t('승인 대기')}</h2>
            <span className="dash-counter">{pendingTotal}</span>
          </div>

          <div className="dash-stats">
            {canView('standard-domain') && (
              <Stat to="/standardDomain" icon={<NoteIcon />} color="icon-blue" label={t('표준 도메인/단어/용어')} value={data.pendingStandardCount} foot={t('데이터사전 바로가기')} />
            )}
            {canView('table-manage') && (
              <Stat to="/tableManage" icon={<TableIcon />} color="icon-green" label={t('테이블 관리')} value={data.pendingTableCount} foot={t('테이블 관리 바로가기')} />
            )}
            {canView('column-manage') && (
              <Stat to="/columnManage" icon={<ColumnsIcon />} color="icon-purple" label={t('컬럼 관리')} value={data.pendingColumnCount} foot={t('컬럼 관리 바로가기')} />
            )}
          </div>
        </>
      )}

      <div className="dash-grid">
        <div className="dash-box">
          <div className="dash-box-header">
            <h2 className="dash-box-title">{t('최근 활동')}</h2>
          </div>
          {data.recentChanges.length > 0 ? (
            <ul className="dash-timeline">
              {data.recentChanges.map((item, i) => (
                <li className="dash-timeline-item" key={i}>
                  <div className="dash-avatar">{initial(item.modifiedBy)}</div>
                  <div className="dash-timeline-body">
                    <div className="dash-timeline-text">
                      {tx(
                        '{0} 님이 {1} {2} 을(를) 수정했습니다.',
                        <strong>{item.modifiedBy ?? t('알 수 없음')}</strong>,
                        <span className="dash-label" data-type={item.type}>{tv(item.type)}</span>,
                        <code className="dash-name">{item.name}</code>,
                      )}
                    </div>
                    {item.modifiedAt && <RelativeTime value={item.modifiedAt} className="dash-time" />}
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <div className="dash-blank">
              <ClockIcon size={24} />
              <p>{t('최근 변경된 항목이 없습니다.')}</p>
            </div>
          )}
        </div>

        <div className="dash-box">
          <div className="dash-box-header">
            <h2 className="dash-box-title">{t('프로젝트 공지사항')}</h2>
            <Link to="/notice" className="dash-btn">{t('전체 보기')}</Link>
          </div>
          {data.notices.length > 0 ? (
            <ul className="dash-list" id="noticeList">
              {data.notices.map((notice) => {
                const read = notice.isHit === '확인';
                return (
                  <li key={notice.id} className="dash-list-item notice-item" tabIndex={0} role="button"
                    onClick={() => openNotice(notice.id)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        openNotice(notice.id);
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
      </div>
    </section>
  );
}

function Stat({ to, icon, color, label, value, foot }: { to: string; icon: React.ReactNode; color: string; label: string; value: number; foot: string }) {
  return (
    <Link to={to} className="dash-stat">
      <div className="dash-stat-head">
        <span className={`dash-stat-icon ${color}`}>{icon}</span>
        <span className="dash-stat-label">{label}</span>
      </div>
      <div className={`dash-stat-value${value > 0 ? ' is-pending' : ''}`}>{value}</div>
      <div className="dash-stat-foot">
        {foot} <span aria-hidden="true">→</span>
      </div>
    </Link>
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
