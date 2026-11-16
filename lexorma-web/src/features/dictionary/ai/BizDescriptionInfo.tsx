import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Pencil, Sparkles } from 'lucide-react';
import { useAuth } from '../../../hooks/useAuth';
import { api, ApiError } from '../../../lib/api';
import { t } from '../../../lib/i18n';
import { aiDictionary, proposalPath, STATUS_CLASS, STATUS_LABEL, type ObjectStatus } from './aiDictionary';
import '../../../styles/ai-dictionary.css';

/**
 * 업무 설명 길이 상한 : 서버(BizDescriptions.MAX)와 같은 4,000자. 길이는 JS 문자열 length(UTF-16 단위) — 입력칸 maxLength·서버의 String.length 와 같은 기준이다
 * (이모지 등은 2자, 줄바꿈은 1자). 서버는 앞뒤 공백을 뺀 값으로 센다
 */
export const BIZ_DESCRIPTION_MAX = 4000;

/** 업무 설명 입력칸 아래의 글자 수 (상한에 닿으면 강조) — 모든 업무 설명 입력칸이 같이 쓴다 */
export function BizLength({ value }: { value: string }) {
  const n = value.length;
  return (
    <span className={`biz-length${n >= BIZ_DESCRIPTION_MAX ? ' is-full' : ''}`}>
      {t('{0} / {1}자', n.toLocaleString(), BIZ_DESCRIPTION_MAX.toLocaleString())}
    </span>
  );
}

/** 테이블의 업무 설명·AI 제안 상태 (테이블·컬럼 관리 화면, ERD 상세 패널). 한 테이블에 한 번 조회해 테이블·컬럼이 같이 쓴다 */
export function useObjectStatuses(tableId: number | null | undefined) {
  const { canView } = useAuth();
  const enabled = tableId != null && canView('ai-dictionary');
  return useQuery({ queryKey: ['ai-dict-objects', tableId], queryFn: () => aiDictionary.objects(tableId!), enabled, staleTime: 10_000 });
}

/**
 * 확정 업무 설명과 AI 제안 상태, 그 제안(없으면 그 대상)으로 이동하는 링크. 업무 설명은 AI 제안을 승인하거나 사람이 직접 쓴 값이다.
 * 업무 설명은 여기서 바로 쓰고 고치고 지울 수 있다 (AI 없이, biz-description-edit = USER). 저장할 때 화면이 읽은 값을 함께 보내
 * 그 사이 다른 사용자나 AI 제안 승인이 바꿨으면 덮어쓰지 않는다 (서버가 409 로 막는다)
 */
export function BizDescriptionInfo({ status, tableId, columnId, compact = false }: { status: ObjectStatus | undefined; tableId: number; columnId?: number | null; compact?: boolean }) {
  const { canView } = useAuth();
  const queryClient = useQueryClient();
  const canEdit = canView('biz-description-edit');
  const [editing, setEditing] = useState<string | null>(null);
  const [base, setBase] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!canView('ai-dictionary')) return null;
  const link = proposalPath({ proposalId: status?.proposalId, tableId, columnId: columnId ?? null });
  const current = status?.bizDescription ?? '';

  const begin = () => {
    setBase(current);
    setEditing(current);
    setError(null);
  };
  const save = async (value: string) => {
    setBusy(true);
    setError(null);
    try {
      await api('/api/bizDescription', { method: 'PUT', silent: true, body: { tableId, columnId: columnId ?? null, bizDescription: value, base } });
      setEditing(null);
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'BIZ_DESCRIPTION_CONFLICT'
        ? t('화면을 연 뒤 다른 사용자(또는 AI 제안 승인)가 업무 설명을 바꿨습니다. 최신 내용을 불러왔으니 다시 확인해 주세요.')
        : e instanceof ApiError ? e.message : t('저장하지 못했습니다.'));
      setEditing(null);
    } finally {
      setBusy(false);
      void queryClient.invalidateQueries({ queryKey: ['ai-dict-objects', tableId] });
      void queryClient.invalidateQueries({ queryKey: ['ai-dict-targets'] });
    }
  };

  return (
    <div className={`ai-dict-info${compact ? ' is-compact' : ''}`}>
      <div className="ai-dict-info-row">
        <span className="ai-dict-info-label">{t('업무 설명')}</span>
        {editing == null && (current ? <span className="ai-dict-info-text">{current}</span> : <span className="gh-muted">{t('없음')}</span>)}
        {editing == null && canEdit && (
          <button type="button" className="btn btn-sm ai-dict-info-edit" onClick={begin} disabled={busy}>
            <Pencil size={12} aria-hidden="true" /> {current ? t('수정') : t('작성')}
          </button>
        )}
      </div>
      {editing != null && (
        <div className="ai-dict-info-editor">
          <textarea className="ai-dict-textarea" rows={compact ? 3 : 4} maxLength={BIZ_DESCRIPTION_MAX} value={editing} autoFocus
                    aria-label={t('업무 설명')} placeholder={t('테이블·컬럼의 업무적 의미 (DDL COMMENT·원본 COMMENT 와 따로 관리합니다)')}
                    onChange={(e) => setEditing(e.target.value)} />
          <BizLength value={editing} />
          <div className="ai-dict-buttons">
            <button type="button" className="btn btn-sm btn-primary" disabled={busy || editing.trim() === base.trim()} onClick={() => void save(editing)}>{t('저장')}</button>
            <button type="button" className="btn btn-sm" disabled={busy} onClick={() => setEditing(null)}>{t('취소')}</button>
            {base && <button type="button" className="btn btn-sm" disabled={busy} onClick={() => void save('')}>{t('업무 설명 지우기')}</button>}
          </div>
        </div>
      )}
      {error && <div className="ai-dict-callout is-warn" role="alert">{error}</div>}
      <div className="ai-dict-info-row">
        <span className="ai-dict-info-label"><Sparkles size={12} aria-hidden="true" /> {t('AI 제안')}</span>
        {status?.proposalStatus ? <span className={`gh-label ${STATUS_CLASS[status.proposalStatus]}`}>{STATUS_LABEL[status.proposalStatus]}</span> : <span className="gh-muted">{t('없음')}</span>}
        {status?.recheckNeeded && <span className="gh-label gh-label-attention">{t('근거 변경')}</span>}
        <Link className="ai-dict-info-link" to={link}>{status?.proposalId ? t('제안 보기') : t('AI 설명 제안으로')}</Link>
      </div>
    </div>
  );
}

/** 테이블 또는 컬럼 하나의 업무 설명·제안 상태 (자기 테이블의 상태를 조회해 고른다) */
export function ObjectBizInfo({ tableId, columnId, compact }: { tableId: number | null | undefined; columnId?: number | null; compact?: boolean }) {
  const statuses = useObjectStatuses(tableId);
  if (tableId == null) return null;
  const status = statuses.data?.find((s) => s.tableId === tableId && (s.columnId ?? null) === (columnId ?? null));
  return <BizDescriptionInfo status={status} tableId={tableId} columnId={columnId} compact={compact} />;
}
