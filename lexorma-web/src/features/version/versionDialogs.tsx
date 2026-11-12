import { useMemo, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { DateTime, Label, Muted } from '../../components/grid/cells';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import '../../styles/history.css';
import { DiffTable } from '../history/historyDialogs';
import { diffRows } from '../history/model';
import { ChangeItem, changeKey } from './changeItem';
import { erdKindOf } from './erdDiff';
import { CHANGES, scaleText, VERSION_TYPES, type VersionComparison, type VersionSnapshot, type VersionSummary } from './model';
import { VersionErd, type ErdFocus } from './VersionErd';

/** 한 번에 그리는 줄 수 : 큰 모델도 목록이 멈추지 않게 나눠 보여 준다 (조건으로 좁히거나 [더 보기]) */
const PAGE = 100;

/** 모델 버전 대화상자 : 생성, 저장된 내용 조회(읽기 전용), 비교 */
export function useVersionDialogs() {
  const dialog = useDialog();

  const openCompare = (baseId?: number) => {
    dialog.open({
      title: t('버전 비교'),
      className: 'dialog-xl version-compare-dialog',
      content: () => <CompareView initialBaseId={baseId} />,
    });
  };

  const openCreate = (onCreated: () => void) => {
    dialog.open({
      title: t('버전 생성'),
      content: ({ close }: DialogControls) => <CreateForm onCreated={() => { close(); onCreated(); }} />,
    });
  };

  const openDetail = (id: number) => {
    dialog.open({
      title: t('저장된 버전'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <SnapshotView id={id} onCompare={() => { close(); openCompare(id); }} />,
    });
  };

  return { openCreate, openDetail, openCompare };
}

function CreateForm({ onCreated }: { onCreated: () => void }) {
  const dialog = useDialog();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    const versionName = name.trim();
    if (!versionName) return dialog.alert(t('버전명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(t('현재 모델을 {0} 버전으로 저장하시겠습니까? 저장한 버전은 수정하거나 삭제할 수 없습니다.', versionName), async () => {
      // 이름 중복·길이·권한은 서버에서 검증한다
      await api('/api/modelVersion', { method: 'POST', body: { versionName, description: description.trim() || null } });
      dialog.alert(t('정상적으로 저장되었습니다.'), onCreated);
    });
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      <p className="table-name-help">
        {t('지금 이 프로젝트의 모델 전체(스키마, 테이블·컬럼·관계·인덱스·제약조건, 논리 모델, 논리·물리 매핑)가 그대로 저장됩니다. 이후 모델을 바꿔도 저장한 버전은 달라지지 않습니다.')}
      </p>
      <div className="form-group">
        <label htmlFor="versionName">{t('버전명')}</label>
        <input type="text" className="form-control" id="versionName" maxLength={100} autoComplete="off" required autoFocus placeholder={t('예: v1.0, 배포 전 모델')}
          value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="versionDescription">{t('설명')}</label>
        <textarea className="form-control" id="versionDescription" rows={3} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <button type="submit" className="btn btn-primary">{t('저장')}</button>
    </form>
  );
}

/** 저장된 내용 : 대상 종류별 객체 목록과 객체 하나의 상태. 조회만 한다 (지금의 모델이 아니라 저장 시점의 값이다) */
function SnapshotView({ id, onCompare }: { id: number; onCompare: () => void }) {
  const snapshot = useQuery({ queryKey: ['model-version-snapshot', id], queryFn: () => api<VersionSnapshot>(`/api/modelVersion/${enc(id)}/snapshot`), gcTime: 0 });
  const [type, setType] = useState('PHYSICAL_TABLE');
  const [keyword, setKeyword] = useState('');
  const [limit, setLimit] = useState(PAGE);
  const [openId, setOpenId] = useState<number | null>(null);

  const objects = snapshot.data?.objects[type] ?? [];
  const found = useMemo(() => {
    const word = keyword.trim().toLowerCase();
    return word ? objects.filter((object) => (object.name ?? '').toLowerCase().includes(word)) : objects;
  }, [objects, keyword]);

  if (!snapshot.data) return <p className="gh-muted">{snapshot.isError ? t('버전을 불러오지 못했습니다.') : t('불러오는 중입니다.')}</p>;
  const { version } = snapshot.data;

  return (
    <div className="history-detail">
      <dl className="dict-detail">
        <dt>{t('버전명')}</dt>
        <dd style={{ overflowWrap: 'anywhere' }}>{version.versionName} <Label>{t('읽기 전용')}</Label></dd>
        <dt>{t('설명')}</dt>
        <dd className="version-description">{version.description ?? <Muted>-</Muted>}</dd>
        <dt>{t('생성자')}</dt>
        <dd>{version.createdBy ?? <Muted>-</Muted>}</dd>
        <dt>{t('생성 일시')}</dt>
        <dd><DateTime value={version.createdAt} /></dd>
        <dt>{t('모델 규모')}</dt>
        <dd>{scaleText(version.counts)}</dd>
      </dl>

      <div className="history-diff-head">
        <h4>{t('저장된 모델')} <Muted>{t('{0}건', found.length)}</Muted></h4>
      </div>
      <div className="history-filters">
        <select className="search-column-select" aria-label={t('대상 유형')} value={type} onChange={(e) => { setType(e.target.value); setLimit(PAGE); setOpenId(null); }}>
          {VERSION_TYPES.map(([value, label]) => <option key={value} value={value}>{label} ({version.counts[value] ?? 0})</option>)}
        </select>
        <input type="text" className="search-input" placeholder={t('이름으로 찾기')} aria-label={t('이름으로 찾기')} value={keyword}
          onChange={(e) => { setKeyword(e.target.value); setLimit(PAGE); }} />
      </div>
      {found.length === 0 ? (
        <p className="gh-muted">{objects.length === 0 ? t('이 버전에는 해당 유형의 객체가 없습니다.') : t('조건에 맞는 객체가 없습니다.')}</p>
      ) : (
        <ol className="history-group-list version-list">
          {found.slice(0, limit).map((object) => (
            <li key={object.id}>
              <button type="button" className="history-group-item" aria-expanded={openId === object.id} onClick={() => setOpenId(openId === object.id ? null : object.id)}>
                <span className="history-group-name">{object.name ?? `#${object.id}`}</span>
                <Muted>#{object.id}</Muted>
              </button>
              {openId === object.id && <DiffTable rows={diffRows({ summary: { targetType: type }, before: null, after: object.state })} hasBefore={false} hasAfter />}
            </li>
          ))}
        </ol>
      )}
      {found.length > limit && <button type="button" className="btn btn-sm" onClick={() => setLimit(limit + PAGE)}>{t('더 보기 ({0}건 남음)', found.length - limit)}</button>}

      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={onCompare}>{t('이 버전을 기준으로 비교')}</button>
      </div>
    </div>
  );
}

const CURRENT = 'current';

/**
 * 비교 : 기준 버전 ↔ 대상(다른 버전 또는 현재 모델). 달라진 객체만 목록으로 보여 주고, 누르면 변경 전후를 편다.
 * [ERD 비교] (M-10 ③) 는 같은 비교를 변경 전후 ERD 로 보여 준다 : 목록의 객체에서 [ERD 에서 보기] 로 그 객체의 자리로 간다
 */
function CompareView({ initialBaseId }: { initialBaseId?: number }) {
  const versions = useQuery({
    queryKey: ['model-versions'],
    queryFn: () => api<{ data: VersionSummary[] }>('/api/modelVersion/data?page=0&size=500').then((page) => page.data),
    gcTime: 0,
  });
  const [baseId, setBaseId] = useState(initialBaseId ? String(initialBaseId) : '');
  const [targetId, setTargetId] = useState(CURRENT);
  const [type, setType] = useState('');
  const [kind, setKind] = useState('');
  const [keyword, setKeyword] = useState('');
  const [limit, setLimit] = useState(PAGE);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [view, setView] = useState<'list' | 'erd'>('list');
  const [focus, setFocus] = useState<ErdFocus | null>(null);

  const ready = !!baseId && baseId !== targetId;
  const comparison = useQuery({
    queryKey: ['model-version-compare', baseId, targetId],
    queryFn: () => api<VersionComparison>(`/api/modelVersion/compare?baseId=${enc(baseId)}${targetId === CURRENT ? '' : `&targetId=${enc(targetId)}`}`),
    enabled: ready,
    gcTime: 0,
  });

  const changes = comparison.data?.changes ?? [];
  const found = useMemo(() => {
    const word = keyword.trim().toLowerCase();
    return changes.filter((change) => (!type || change.targetType === type) && (!kind || change.change === kind) && (!word || (change.name ?? '').toLowerCase().includes(word)));
  }, [changes, type, kind, keyword]);
  const reset = () => { setLimit(PAGE); setOpenKey(null); };

  if (!versions.data) return <p className="gh-muted">{versions.isError ? t('버전을 불러오지 못했습니다.') : t('불러오는 중입니다.')}</p>;
  if (versions.data.length === 0) return <p className="gh-muted">{t('아직 저장한 버전이 없습니다. [버전 생성] 으로 현재 모델을 저장하면 여기에 남습니다.')}</p>;

  return (
    <div className="history-detail">
      <div className="history-filters">
        <select className="search-column-select" aria-label={t('기준 버전')} value={baseId} onChange={(e) => { setBaseId(e.target.value); reset(); setFocus(null); }}>
          <option value="">{t('기준 버전 선택')}</option>
          {versions.data.map((version) => <option key={version.id} value={version.id}>{version.versionName}</option>)}
        </select>
        <span className="gh-muted">→</span>
        <select className="search-column-select" aria-label={t('비교 대상')} value={targetId} onChange={(e) => { setTargetId(e.target.value); reset(); setFocus(null); }}>
          <option value={CURRENT}>{t('현재 모델')}</option>
          {versions.data.map((version) => <option key={version.id} value={version.id} disabled={String(version.id) === baseId}>{version.versionName}</option>)}
        </select>
      </div>

      {!ready ? (
        <p className="gh-muted">{t('기준 버전과 비교 대상을 선택하면 달라진 객체를 보여 줍니다.')}</p>
      ) : !comparison.data ? (
        <p className="gh-muted">{comparison.isError ? t('비교 결과를 불러오지 못했습니다.') : t('비교하는 중입니다.')}</p>
      ) : (
        <>
          <div className="history-chips">
            {CHANGES.map(([value, label, variant]) => <Label key={value} variant={variant}>{label} {comparison.data.totals[value] ?? 0}</Label>)}
            <Label>{t('변경 없음')} {comparison.data.totals.UNCHANGED ?? 0}</Label>
            {changes.length > 0 && (
              <div className="erd-mode version-view" role="group" aria-label={t('보기 방식')}>
                <button type="button" className={`btn btn-sm${view === 'list' ? ' btn-primary' : ''}`} aria-pressed={view === 'list'} onClick={() => setView('list')}>{t('변경 목록')}</button>
                <button type="button" className={`btn btn-sm${view === 'erd' ? ' btn-primary' : ''}`} aria-pressed={view === 'erd'} onClick={() => setView('erd')}>{t('ERD 비교')}</button>
              </div>
            )}
          </div>

          {changes.length === 0 ? (
            <p className="gh-muted">{t('두 모델 사이에 달라진 객체가 없습니다.')}</p>
          ) : view === 'erd' ? (
            <VersionErd baseId={baseId} targetId={targetId === CURRENT ? null : targetId} byType={comparison.data.byType} focus={focus} />
          ) : (
            <>
              <div className="history-filters">
                <select className="search-column-select" aria-label={t('대상 유형')} value={type} onChange={(e) => { setType(e.target.value); reset(); }}>
                  <option value="">{t('전체 대상')}</option>
                  {VERSION_TYPES.filter(([value]) => { const count = comparison.data.byType[value]; return count && count.ADDED + count.REMOVED + count.MODIFIED > 0; })
                    .map(([value, label]) => { const count = comparison.data.byType[value]; return <option key={value} value={value}>{label} ({count.ADDED + count.REMOVED + count.MODIFIED})</option>; })}
                </select>
                <select className="search-column-select" aria-label={t('변경 유형')} value={kind} onChange={(e) => { setKind(e.target.value); reset(); }}>
                  <option value="">{t('전체 변경')}</option>
                  {CHANGES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                </select>
                <input type="text" className="search-input" placeholder={t('이름으로 찾기')} aria-label={t('이름으로 찾기')} value={keyword}
                  onChange={(e) => { setKeyword(e.target.value); reset(); }} />
                <Muted>{t('{0}건', found.length)}</Muted>
              </div>
              {found.length === 0 && <p className="gh-muted">{t('조건에 맞는 객체가 없습니다.')}</p>}
              <ol className="history-group-list version-list">
                {found.slice(0, limit).map((change) => (
                  <ChangeItem key={changeKey(change)} change={change} open={openKey === changeKey(change)} onToggle={() => setOpenKey(openKey === changeKey(change) ? null : changeKey(change))}>
                    {erdKindOf(change.targetType) && (
                      <div className="version-erd-link">
                        <button type="button" className="btn btn-sm" onClick={() => { setFocus({ targetType: change.targetType, targetId: change.targetId, seq: (focus?.seq ?? 0) + 1 }); setView('erd'); }}>{t('ERD 에서 보기')}</button>
                      </div>
                    )}
                  </ChangeItem>
                ))}
              </ol>
              {found.length > limit && <button type="button" className="btn btn-sm" onClick={() => setLimit(limit + PAGE)}>{t('더 보기 ({0}건 남음)', found.length - limit)}</button>}
            </>
          )}
        </>
      )}
    </div>
  );
}
