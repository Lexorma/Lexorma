import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Approval, Label, Mono, Muted } from '../../components/grid/cells';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import { TableLinks } from '../dictionary/DictionaryUsage';
import {
  ATTRIBUTE_LABELS, MODEL_RULES, SEVERITIES, SUGGESTION_ACTIONS, SUGGESTION_SOURCES, TERM_RULES,
  type ColumnSuggestions, type Severity, type SuggestionCandidate, type TermGroup,
} from './model';

const READ_ONLY_NOTE = t('추천·진단은 조회할 때 계산한 참고 정보입니다. 컬럼명·표준 용어·도메인은 자동으로 바뀌지 않으며, 고치려면 관리 화면에서 직접 수정합니다.');

export function SeverityLabel({ severity }: { severity: Severity }) {
  const item = SEVERITIES.find((e) => e.severity === severity);
  return <Label variant={item?.variant} dot>{item?.label ?? severity}</Label>;
}

function DetailList({ items }: { items: [string, string | null | undefined, boolean?][] }) {
  return (
    <dl className="dict-detail">
      {items.map(([label, value, mono]) => [
        <dt key={`t-${label}`}>{label}</dt>,
        <dd key={`d-${label}`} className={value == null || value === '' ? 'gh-muted' : mono ? 'gh-mono' : undefined}>{value == null || value === '' ? '-' : value}</dd>,
      ])}
    </dl>
  );
}

/** 표준 용어 관리 화면으로 (그 용어명으로 검색) */
function useTermLink() {
  const navigate = useNavigate();
  const { canView } = useAuth();
  if (!canView('standard-term')) return null;
  return (termName: string, close: () => void) => {
    close();
    navigate(`/standardTerm?searchColumn=commonStandardTermName&searchQuery=${enc(termName)}`);
  };
}

// ---------------------------------------------------------------- 컬럼명 개선 후보

/**
 * 컬럼 1개의 개선 후보 (GET /api/standardizationQuality/columns/{id}/suggestions). 추천만 보여 주고 적용 버튼은 없다 —
 * 테이블 상세(컬럼 편집)·컬럼 관리·표준 용어 관리로 이동해 사용자가 직접 고친다.
 */
export function ColumnSuggestionDetail({ columnId, close, onChanged }: { columnId: number; close: () => void; onChanged?: () => void }) {
  const navigate = useNavigate();
  const { canView } = useAuth();
  const termLink = useTermLink();
  const query = useQuery({
    queryKey: ['standardization-quality-suggestions', columnId],
    queryFn: () => api<ColumnSuggestions>(`/api/standardizationQuality/columns/${enc(columnId)}/suggestions`),
    staleTime: 0,
  });

  if (query.isPending) return <p className="gh-muted">{t('불러오는 중…')}</p>;
  if (query.isError) return <p className="gh-muted" role="alert">{t('개선 후보를 불러오지 못했습니다.')}</p>;
  const data = query.data;
  const issueLabels = data.issueTypes.map((type) => MODEL_RULES.find((e) => e.type === type)?.label ?? type).join(', ');

  return (
    <div className="quality-suggestion">
      <DetailList items={[
        [t('테이블'), data.tableName, true],
        [t('컬럼명'), data.columnName, true],
        [t('논리명'), data.columnDesc],
        [t('데이터 타입'), data.dataType, true],
        [t('연결된 표준 용어'), data.termName ? `${data.termName}${data.termAbbreviation ? ` (${data.termAbbreviation})` : ''}` : null],
        [t('진단 유형'), issueLabels],
      ]} />

      <h4 className="quality-subtitle">{t('개선 후보 ({0})', data.candidates.length)}</h4>
      {data.candidates.length === 0 ? <p className="gh-muted" role="status">{data.reason}</p> : (
        <ol className="quality-candidates">
          {data.candidates.map((candidate, index) => (
            <CandidateItem key={`${candidate.source}-${candidate.termId ?? index}`} candidate={candidate} rank={index + 1}
              onTerm={termLink ? (name) => termLink(name, close) : undefined} />
          ))}
        </ol>
      )}
      {data.truncated && <p className="form-help">{t('점수가 높은 5건만 보여 줍니다.')}</p>}
      <p className="form-help">{READ_ONLY_NOTE}</p>
      <div className="gh-dialog-actions">
        <TableLinks tableId={data.tableId} schemaId={data.schemaId} columnId={data.columnId} onNavigate={close} onChanged={onChanged} />
        {canView('column-manage') && data.tableName && (
          <button type="button" className="btn" onClick={() => { close(); navigate(`/columnManage?searchColumn=tableName&searchQuery=${enc(data.tableName)}`); }}>{t('컬럼 관리에서 보기')}</button>
        )}
        <button type="button" className="btn" onClick={close}>{t('닫기')}</button>
      </div>
    </div>
  );
}

function CandidateItem({ candidate, rank, onTerm }: { candidate: SuggestionCandidate; rank: number; onTerm?: (termName: string) => void }) {
  const registered = candidate.termId != null;
  return (
    <li className="quality-candidate">
      <div className="quality-candidate-head">
        <span className="quality-candidate-rank" aria-label={t('순위 {0}', rank)}>{rank}</span>
        <strong className="quality-candidate-name">
          {registered ? candidate.termName : candidate.proposedTermName}
          {candidate.termAbbreviation && <> <Mono>{candidate.termAbbreviation}</Mono></>}
        </strong>
        {registered
          ? (candidate.termApproved ? <Label variant="success">{t('승인된 용어')}</Label> : <Label variant="attention">{t('미승인 용어')}</Label>)
          : <Label variant="attention">{t('미등록 용어')}</Label>}
        <Label variant="accent">{SUGGESTION_ACTIONS[candidate.action]}</Label>
        <span className="gh-muted quality-candidate-score">{t('점수 {0}', candidate.score)}</span>
      </div>
      <dl className="quality-candidate-body">
        <dt>{t('추천 컬럼명')}</dt>
        <dd>{candidate.recommendedColumnName ? <Mono>{candidate.recommendedColumnName}</Mono> : <Muted>{t('영문약어가 없어 알 수 없음')}</Muted>}</dd>
        <dt>{t('근거')}</dt>
        <dd>
          {candidate.reason}
          <span className="quality-chips">{candidate.sources.map((source) => <Label key={source}>{SUGGESTION_SOURCES[source]}</Label>)}</span>
          {candidate.words.length > 0 && (
            <span className="quality-chips">
              {candidate.words.map((word, index) => (
                <Label key={index} variant={word.approved ? 'success' : 'attention'} title={word.approved ? t('승인된 표준 단어') : t('승인되지 않은 표준 단어')}>
                  <span className="gh-mono">{word.abbreviation}</span> → {word.wordName}
                </Label>
              ))}
            </span>
          )}
        </dd>
        <dt>{t('도메인 적합성')}</dt>
        <dd>
          {candidate.domainFit === 'NO_DOMAIN' ? <Muted>{registered ? t('용어에 도메인이 없어 비교하지 않음') : t('새 용어라 도메인이 없음')}</Muted>
            : candidate.domainFit === 'MATCH' ? <><Label variant="success">{t('일치')}</Label> {candidate.domainName}</>
              : <><Label variant="attention">{t('불일치')}</Label> {candidate.domainName} · {candidate.attributes.map((a) => ATTRIBUTE_LABELS[a]).join(', ')}
                {' '}(<Mono>{candidate.actualType}</Mono> → <Mono>{candidate.expectedType}</Mono>)</>}
        </dd>
        <dt>{t('이름 충돌')}</dt>
        <dd>
          {!candidate.nameConflict && !candidate.candidateConflict ? <Muted>{t('없음')}</Muted> : (
            <>
              {candidate.nameConflict && <div><Label variant="danger">{t('같은 테이블에 같은 이름의 컬럼이 있음')}</Label> <Mono>{candidate.conflictColumns.join(', ')}</Mono></div>}
              {candidate.candidateConflict && <div><Label variant="attention">{t('같은 테이블의 다른 컬럼도 이 이름을 기대함')}</Label> <Mono>{candidate.candidateConflictColumns.join(', ')}</Mono></div>}
            </>
          )}
        </dd>
      </dl>
      {registered && onTerm && candidate.termName && (
        <button type="button" className="btn btn-sm" onClick={() => onTerm(candidate.termName!)}>{t('표준 용어 관리에서 보기')}</button>
      )}
    </li>
  );
}

// ---------------------------------------------------------------- 용어 그룹 상세

export function TermGroupDetail({ group, close }: { group: TermGroup; close: () => void }) {
  const termLink = useTermLink();
  const rule = TERM_RULES.find((e) => e.type === group.type);
  return (
    <div>
      <div className="dict-detail-status">
        <span>{t('심각도')}</span>
        <SeverityLabel severity={group.severity} />
        <span>{rule?.label ?? group.type}</span>
        <span className="gh-mono gh-muted">{group.type}</span>
      </div>
      <DetailList items={[
        [t('묶은 값'), group.groupKey],
        [t('판정 근거'), group.message],
      ]} />
      <h4 className="quality-subtitle">{t('관련 용어 ({0})', group.memberCount)}</h4>
      <div className="table-wrap">
        <table className="quality-members">
          <thead>
            <tr><th>{t('표준용어명')}</th><th>{t('영문약어')}</th><th>{t('도메인')}</th><th>{t('승인')}</th><th>{t('사용 컬럼')}</th><th>{t('근거')}</th><th>{t('이동')}</th></tr>
          </thead>
          <tbody>
            {group.members.map((member) => (
              <tr key={member.termId}>
                <td>{member.termName}</td>
                <td>{member.abbreviation ? <Mono>{member.abbreviation}</Mono> : <Muted>-</Muted>}</td>
                <td>{member.domainName ?? <Muted>-</Muted>}</td>
                <td><Approval value={member.isApproval ? 'Y' : 'N'} /></td>
                <td>{member.columnCount ?? <Muted>-</Muted>}</td>
                <td>{member.note ?? <Muted>-</Muted>}</td>
                <td>{termLink && member.termName && <button type="button" className="btn btn-sm" onClick={() => termLink(member.termName!, close)}>{t('표준 용어 관리')}</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="form-help">{t('같은 뜻이라고 확정하지 않습니다. 용어를 병합·삭제하거나 이름을 바꾸지 않으며, 필요하면 표준 용어 관리에서 직접 정리합니다.')}</p>
      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={close}>{t('닫기')}</button>
      </div>
    </div>
  );
}
