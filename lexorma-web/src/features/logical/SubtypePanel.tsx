import { useEffect, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { useToast } from '../../components/toast/ToastProvider';
import { Label, SkeletonRows } from '../../components/grid/cells';
import { useAuth } from '../../hooks/useAuth';
import { api, ApiError, enc } from '../../lib/api';
import { useConflictRecovery, useSubmitting } from '../../components/conflict/ConflictRecovery';
import { runWithReview, type Snapshot } from '../../components/conflict/conflictActions';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import { ListStatus, PageNav, SortHeader, useLastPage } from './listParts';
import { EntityPicker, type EntityOption } from './relationshipForm';
import {
  completeness, disjointness, disjointSymbol, inheritanceQueryKey, subtypeQueryKey, SUBTYPE_MIN,
  type EffectiveAttribute, type Inheritance, type SubtypeGroup, type SubtypeGroupRow,
} from './subtype';
import type { EntityHits, GridPage, LogicalModel } from './model';

type GroupSortColumn = 'groupName' | 'supertypeEntityName' | 'modifiedAt';

/**
 * 서브타입 그룹 탭 (M-6 후속 5차) : 논리 모델 관리 안에서 그룹을 서버 페이지로 보고(그룹명·슈퍼타입명 검색, 정렬) 등록·수정·삭제한다 (별도 메뉴 없음).
 * 조회는 VIEWER, 변경은 logical-model-edit(USER 이상) — 서버가 같은 기준과 상속 규칙(단일 상속·3단계·승인·참조)을 다시 검사한다.
 */
export function SubtypeGroupTab({ model, onChanged, openEntity }: { model: LogicalModel; onChanged: () => void; openEntity: (entityId: number) => void }) {
  const dialog = useDialog();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { user, canView } = useAuth();
  const canEdit = canView('logical-model-edit');
  const key = subtypeQueryKey(user?.projectId, model.id);
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<{ column: GroupSortColumn; desc: boolean } | null>(null);
  const [page, setPage] = useState({ page: 0, size: 10 });
  const query = new URLSearchParams({ page: String(page.page), size: String(page.size), searchQuery: search });
  if (sort) query.set('sort', `${sort.column},${sort.desc ? 'desc' : 'asc'}`);
  const groups = useQuery({
    queryKey: [...key, 'groups', query.toString()],
    queryFn: ({ signal }) => api<GridPage<SubtypeGroupRow>>(`/api/logical/model/${model.id}/subtypeGroup/data?${query}`, { signal, silent: true }),
    placeholderData: (previous) => previous,
  });
  useLastPage(groups.data, page, setPage);
  const rows = groups.data?.data ?? [];

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: key });
    void queryClient.invalidateQueries({ queryKey: ['logical-inheritance'] });
    onChanged();
  };
  const toggleSort = (column: GroupSortColumn) => {
    setSort((s) => ({ column, desc: s?.column === column ? !s.desc : false }));
    setPage((p) => ({ ...p, page: 0 }));
  };
  const applySearch = (value: string) => {
    setSearch(value.trim());
    setPage((p) => ({ ...p, page: 0 }));
  };

  const openForm = async (row?: SubtypeGroupRow) => {
    // 수정은 지금 상세(구성원·기준값)를 받아 연다 — 목록의 값으로 저장하지 않는다
    const group = row ? await api<SubtypeGroup>(`/api/logical/subtypeGroup/${row.id}`) : undefined;
    dialog.open({
      title: group ? (canEdit ? t('서브타입 그룹 수정') : t('서브타입 그룹 상세')) : t('서브타입 그룹 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => (
        <SubtypeGroupForm modelId={model.id} group={group} readOnly={!canEdit} onSaved={() => {
          close();
          refresh();
          toast.success(group ? t('정상적으로 수정되었습니다.') : t('정상적으로 저장되었습니다.'));
        }} />
      ),
    });
  };

  const remove = (row: SubtypeGroupRow) => {
    dialog.confirm(t('{0} 서브타입 그룹을 삭제하시겠습니까? 엔터티는 지우지 않고 상속만 해제합니다.', row.groupName), async () => {
      // 409 (확인한 뒤 그룹·구성원이 바뀌었다) : 확인한 그룹 ↔ 최신 그룹을 보여 주고 사용자가 다시 삭제를 고른다 (M8-C4c-2)
      const done = () => { refresh(); toast.success(t('정상적으로 삭제되었습니다.')); };
      let seen: SubtypeGroup;
      try {
        seen = await api<SubtypeGroup>(`/api/logical/subtypeGroup/${row.id}`, { silent: true });
      } catch {
        seen = { groupName: row.groupName, modifiedAt: row.modifiedAt, subtypes: [] } as unknown as SubtypeGroup;
      }
      try {
        const ok = await runWithReview({
          dialog, title: t('서브타입 그룹 삭제'), intro: t('다른 사용자가 이 항목을 변경했습니다.'), seen: { ...groupSnapshot(seen), modifiedAt: row.modifiedAt },
          load: async () => groupSnapshot(await api<SubtypeGroup>(`/api/logical/subtypeGroup/${row.id}`, { silent: true })),
          act: async (state) => { await api(`/api/logical/subtypeGroup/${row.id}?modifiedAt=${enc(state.modifiedAt)}`, { method: 'DELETE', conflict: 'handled' }); },
          retryLabel: t('최신 내용으로 삭제'), onDone: done, onClose: refresh,
        });
        if (ok) done();
      } catch { /* 공통 안내 */ }
    });
  };

  const headers: [string, GroupSortColumn | null][] = [[t('그룹명'), 'groupName'], [t('슈퍼타입'), 'supertypeEntityName'], [t('서브타입 수'), null], [t('배타성'), null],
    [t('완전성'), null], [t('분류 속성'), null], [t('수정 일시'), 'modifiedAt'], [t('관리'), null]];

  return (
    <>
      <div className="grid-header mt-3">
        <form className="search-container" role="search" onSubmit={(e) => { e.preventDefault(); applySearch(searchInput); }}>
          <input type="search" className="search-input search-input-text" aria-label={t('서브타입 그룹 검색')} placeholder={t('그룹명 / 슈퍼타입명')} maxLength={100}
            value={searchInput} onChange={(e) => setSearchInput(e.target.value)} />
          <button type="submit" className="grd-select">{t('조회')}</button>
          {search && <button type="button" className="btn" onClick={() => { setSearchInput(''); applySearch(''); }}>{t('초기화')}</button>}
        </form>
        <div className="button-container">
          {canEdit && <button type="button" className="grd-add btn btn-primary" onClick={() => void openForm()}>{t('서브타입 그룹 등록')}</button>}
        </div>
      </div>
      <div className="dt-container">
        <ListStatus size={page.size} onSize={(size) => setPage({ page: 0, size })} text={groups.data ? t('서브타입 그룹 {0}개', groups.data.recordsTotal) : ''} loading={groups.isFetching} />
        <div className="dt-layout-row dt-layout-table">
          <div className="dt-layout-cell dt-layout-full">
            <table id="logicalSubtypeGrid" className="table table-striped table-bordered dataTable grid-fixed" style={{ minWidth: 1040 }}>
              <colgroup>{['17%', '15%', '9%', '13%', '11%', '13%', '12%', '10%'].map((width, index) => <col key={index} style={{ width }} />)}</colgroup>
              <thead>
                <tr>
                  {headers.map(([label, column]) => (
                    <SortHeader key={label} label={label} ordering={column && sort?.column === column ? (sort.desc ? 'desc' : 'asc') : null} onSort={column ? () => toggleSort(column) : undefined} />
                  ))}
                </tr>
              </thead>
              <tbody className={groups.isPlaceholderData ? 'is-refreshing' : 'is-refresh-ready'} aria-busy={groups.isFetching || undefined}>
                {groups.isPending && <SkeletonRows columns={headers.length} label={t('불러오는 중입니다.')} />}
                {rows.map((row) => (
                  <tr key={row.id}>
                    <td><button type="button" className="logical-link" onClick={() => void openForm(row)}>{row.groupName}</button></td>
                    <td><button type="button" className="logical-link" onClick={() => openEntity(row.supertypeEntityId)}>{row.supertypeEntityName}</button></td>
                    <td>{row.subtypeCount}</td>
                    <td><span className="subtype-symbol" aria-hidden>{disjointSymbol(row.overlapping)}</span> {disjointness(row.overlapping)}</td>
                    <td>{completeness(row.total)}</td>
                    <td>{row.classificationAttributeName ?? <span className="gh-muted">-</span>}</td>
                    <td>{formatDateTime(row.modifiedAt)}</td>
                    <td>
                      <div className="logical-actions">
                        <button type="button" className="btn btn-sm" onClick={() => void openForm(row)}>{canEdit ? t('수정') : t('상세')}</button>
                        {canEdit && <button type="button" className="btn btn-sm btn-danger" onClick={() => remove(row)}>{t('삭제')}</button>}
                      </div>
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && !groups.isPending && (
                  <tr><td colSpan={headers.length} className="dt-empty" role={groups.isError ? 'alert' : undefined}>
                    {groups.isError ? t('서브타입 그룹 목록을 불러오지 못했습니다.') : search ? t('검색된 데이터가 없습니다.') : t('등록된 서브타입 그룹이 없습니다.')}
                  </td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
        <PageNav data={groups.data} state={page} onPage={(next) => setPage((p) => ({ ...p, page: next }))} />
      </div>
      <p className="gh-muted subtype-note">{t('배타성·완전성·분류 값은 모델링 정보입니다. 실제 업무 데이터로 검사하거나 강제하지 않습니다.')}</p>
    </>
  );
}

interface MemberDraft {
  entityId: number;
  entityName: string;
  value: string;
}

/**
 * 서브타입 그룹 등록·수정 : 배타성·완전성은 고르지 않은 상태에서 시작한다 (기본값 없음). 서브타입은 같은 모델의 엔터티를 찾아 2개 이상 (순서·분류 값).
 * 분류 속성은 슈퍼타입의 유효 속성(직접 정의 또는 상속 — 정의 엔터티 표시) 중 하나 (선택). 서버가 모든 조건을 다시 검사하고, 409 면 입력한 내용은 그대로 둔다
 */
/** 그룹 비교 상태 : 그룹 자체 칸 + 구성원(서브타입) 한 줄씩 (추가·삭제·분류 값 변경이 줄 단위로 보인다 — M8-C4c-2) */
function groupSnapshot(g: SubtypeGroup): Snapshot {
  return {
    modifiedAt: g.modifiedAt ?? null,
    fields: [
      { key: 'name', label: t('그룹명'), value: g.groupName },
      { key: 'supertype', label: t('슈퍼타입'), value: g.supertypeEntityName ?? '' },
      { key: 'overlapping', label: t('배타성'), value: g.overlapping == null ? '' : g.overlapping ? 'Overlapping' : 'Disjoint' },
      { key: 'total', label: t('완전성'), value: g.total == null ? '' : g.total ? 'Total' : 'Partial' },
      { key: 'classification', label: t('분류 속성'), value: g.classificationAttributeName ?? '' },
      ...(g.subtypes ?? []).map((m) => ({ key: `member:${m.entityId}`, label: t('서브타입 : {0}', m.entityName), value: m.classificationValue ?? '—', removable: true })),
    ],
  };
}

export function SubtypeGroupForm({ modelId, group, readOnly, onSaved }: { modelId: number; group?: SubtypeGroup; readOnly?: boolean; onSaved: () => void }) {
  const dialog = useDialog();
  const [name, setName] = useState(group?.groupName ?? '');
  const [description, setDescription] = useState(group?.groupDescription ?? '');
  const [supertype, setSupertype] = useState<EntityOption | null>(group ? { id: group.supertypeEntityId, name: group.supertypeEntityName } : null);
  const [overlapping, setOverlapping] = useState<boolean | null>(group ? group.overlapping : null);
  const [total, setTotal] = useState<boolean | null>(group ? group.total : null);
  const [classification, setClassification] = useState<number | null>(group?.classificationAttributeId ?? null);
  const [members, setMembers] = useState<MemberDraft[]>(group?.subtypes.map((m) => ({ entityId: m.entityId, entityName: m.entityName, value: m.classificationValue ?? '' })) ?? []);
  const [checking, setChecking] = useState(false);
  const [busy, run] = useSubmitting();
  type Values = { name: string; description: string; supertype: EntityOption | null; overlapping: boolean | null; total: boolean | null; classification: number | null;
    classificationName: string | null; members: MemberDraft[] };
  const valuesOf = (g: SubtypeGroup): Values => ({
    name: g.groupName, description: g.groupDescription ?? '', supertype: { id: g.supertypeEntityId, name: g.supertypeEntityName }, overlapping: g.overlapping, total: g.total,
    classification: g.classificationAttributeId, classificationName: g.classificationAttributeName,
    members: g.subtypes.map((m) => ({ entityId: m.entityId, entityName: m.entityName, value: m.classificationValue ?? '' })),
  });
  const membersText = (list: MemberDraft[]) => list.map((m) => `${m.entityName}${m.value.trim() ? ` (${m.value.trim()})` : ''}`).join(', ');
  // 409 복구 (M8-C4c-2 — M8-C3 공통) : 그룹 상세의 최신 값과 칸마다 비교한다. 구성원은 목록 전체를 한 칸으로 보고, 내 목록과 최신 목록 중 하나를 고른다 (줄 단위 자동 병합 없음)
  const recovery = useConflictRecovery<Values>({
    initial: group ? valuesOf(group) : { name: '', description: '', supertype: null, overlapping: null, total: null, classification: null, classificationName: null, members: [] },
    base: group?.modifiedAt,
    fields: [
      { key: 'name', label: t('그룹명'), get: (v) => v.name },
      { key: 'description', label: t('설명 (분류 기준)'), get: (v) => v.description },
      { key: 'supertype', label: t('슈퍼타입'), get: (v) => v.supertype?.id ?? null, show: (v) => v.supertype?.name ?? null },
      { key: 'overlapping', label: t('배타성'), get: (v) => v.overlapping, show: (v) => (v.overlapping == null ? null : v.overlapping ? 'Overlapping' : 'Disjoint') },
      { key: 'total', label: t('완전성'), get: (v) => v.total, show: (v) => (v.total == null ? null : v.total ? 'Total' : 'Partial') },
      { key: 'classification', label: t('분류 속성'), get: (v) => v.classification, show: (v) => v.classificationName },
      { key: 'members', label: t('서브타입 (순서·분류 값)'), get: (v) => v.members.map((m) => [m.entityId, m.value.trim()]), show: (v) => membersText(v.members) },
    ],
    load: async () => {
      if (!group) throw new ApiError(400, t('서브타입 그룹을 찾을 수 없습니다.'), 'LOGICAL_MODEL_IS_NOT_DEFINED');
      const latest = await api<SubtypeGroup>(`/api/logical/subtypeGroup/${group.id}`, { silent: true });
      return { values: valuesOf(latest), base: latest.modifiedAt ?? null };
    },
  });

  // 분류 속성 후보 : 슈퍼타입의 유효 속성 (직접 정의 + 상속)
  const attributes = useQuery({
    queryKey: ['logical-effective-attributes', supertype?.id],
    queryFn: ({ signal }) => api<{ entityId: number; attributes: EffectiveAttribute[] }>(`/api/logical/entity/${supertype!.id}/effectiveAttributes`, { signal, silent: true }),
    enabled: supertype != null,
  });
  useEffect(() => {
    // 슈퍼타입을 바꾸면 그 엔터티의 유효 속성이 아닌 분류 속성은 비운다 (다른 엔터티의 속성을 몰래 남기지 않는다)
    if (attributes.data && classification != null && !attributes.data.attributes.some((a) => a.attributeId === classification)) setClassification(null);
  }, [attributes.data]);
  const own = attributes.data?.attributes.filter((a) => !a.inherited) ?? [];
  const inherited = attributes.data?.attributes.filter((a) => a.inherited) ?? [];

  /** 서브타입 후보 확인 : 자기 자신·이미 넣은 엔터티·다른 그룹의 서브타입·자체 주 식별자·승인된 엔터티는 넣지 않는다 (서버도 같은 조건으로 거절한다) */
  const addMember = async (hit: EntityHits['items'][number]) => {
    if (supertype && hit.id === supertype.id) return dialog.alert(t('슈퍼타입 자신은 서브타입이 될 수 없습니다.'));
    if (members.some((m) => m.entityId === hit.id)) return dialog.alert(t('이미 추가한 엔터티입니다.'));
    setChecking(true);
    try {
      const info = await api<Inheritance>(`/api/logical/entity/${hit.id}/inheritance`);
      if (info.parent && info.parent.groupId !== group?.id) return dialog.alert(t('{0} 은(는) 이미 그룹 {1} 의 서브타입입니다. 엔터티 하나는 하나의 슈퍼타입만 가질 수 있습니다.', hit.name, info.parent.groupName));
      const ownKey = info.identifiers.find((i) => i.isPrimary && !i.inherited);
      if (ownKey) return dialog.alert(t('{0} 에 자체 주 식별자 {1} 이(가) 있어 서브타입으로 연결할 수 없습니다. 서브타입은 슈퍼타입의 주 식별자를 상속합니다.', hit.name, ownKey.identifierName));
      if (info.isApproval) return dialog.alert(t('승인된 엔터티 {0} 은(는) 상속에 연결할 수 없습니다. 승인을 취소한 뒤 다시 시도해주세요.', hit.name));
      setMembers((current) => [...current, { entityId: hit.id, entityName: hit.name, value: '' }]);
    } finally {
      setChecking(false);
    }
  };
  const move = (index: number, delta: number) => setMembers((current) => {
    const next = [...current];
    [next[index], next[index + delta]] = [next[index + delta], next[index]];
    return next;
  });

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('그룹명은(는) 필수 입력 항목입니다.'));
    if (!supertype) return dialog.alert(t('슈퍼타입 엔터티를 골라주세요.'));
    if (overlapping == null) return dialog.alert(t('배타성(Disjoint·Overlapping)을 선택해주세요.'));
    if (total == null) return dialog.alert(t('완전성(Total·Partial)을 선택해주세요.'));
    if (members.length < SUBTYPE_MIN) return dialog.alert(t('서브타입을 {0}개 이상 지정해주세요.', SUBTYPE_MIN));
    dialog.confirm(group ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), () => void run(async () => {
      try {
        await api('/api/logical/subtypeGroup', {
          method: 'POST',
          conflict: 'handled',
          body: {
            id: group?.id ?? null, modelId, supertypeEntityId: supertype.id, groupName: name.trim(), groupDescription: description.trim() || null, overlapping, total,
            classificationAttributeId: classification, subtypes: members.map((m) => ({ entityId: m.entityId, classificationValue: m.value.trim() || null })),
            modifiedAt: group ? recovery.base : null, // 409 복구 뒤에는 최신 기준값
          },
        });
      } catch (error) {
        const classificationName = attributes.data?.attributes.find((a) => a.attributeId === classification)?.attributeName ?? null;
        recovery.catchConflict(error, { name, description, supertype, overlapping, total, classification, classificationName, members });
        return; // 그 밖의 오류(업무 규칙·잠금 충돌)는 공통 안내 — 입력은 그대로
      }
      onSaved();
    }));
  };

  return (
    <form className="edit-form subtype-form" onSubmit={onSubmit}>
      {recovery.notice((latest, picks) => {
        if (!picks.has('name')) setName(latest.name);
        if (!picks.has('description')) setDescription(latest.description);
        if (!picks.has('supertype')) setSupertype(latest.supertype);
        if (!picks.has('overlapping')) setOverlapping(latest.overlapping);
        if (!picks.has('total')) setTotal(latest.total);
        if (!picks.has('classification')) setClassification(latest.classification);
        if (!picks.has('members')) setMembers(latest.members);
      })}
      <div className="form-group">
        <label htmlFor="subtypeGroupName">{t('그룹명')}</label>
        <input type="text" className="form-control" id="subtypeGroupName" maxLength={255} required autoFocus readOnly={readOnly} value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-group">
        <label htmlFor="subtypeGroupDescription">{t('설명 (분류 기준)')}</label>
        <textarea className="form-control" id="subtypeGroupDescription" maxLength={1000} rows={2} readOnly={readOnly} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      {readOnly ? (
        <p><strong>{t('슈퍼타입')}</strong> : {supertype?.name}</p>
      ) : (
        <EntityPicker id="subtypeSupertype" label={t('슈퍼타입')} modelId={modelId} value={supertype} onChange={setSupertype} />
      )}
      <fieldset className="form-group subtype-choice" disabled={readOnly}>
        <legend>{t('배타성')}</legend>
        <label><input type="radio" name="subtypeOverlapping" checked={overlapping === false} onChange={() => setOverlapping(false)} /> <b className="subtype-symbol">D</b> {t('배타 (Disjoint) — 한 인스턴스는 그룹의 서브타입 하나에만')}</label>
        <label><input type="radio" name="subtypeOverlapping" checked={overlapping === true} onChange={() => setOverlapping(true)} /> <b className="subtype-symbol">O</b> {t('중복 가능 (Overlapping) — 한 인스턴스가 여러 서브타입에')}</label>
      </fieldset>
      <fieldset className="form-group subtype-choice" disabled={readOnly}>
        <legend>{t('완전성')}</legend>
        <label><input type="radio" name="subtypeTotal" checked={total === true} onChange={() => setTotal(true)} /> {t('완전 (Total) — 모든 슈퍼타입 인스턴스가 서브타입 하나 이상에')}</label>
        <label><input type="radio" name="subtypeTotal" checked={total === false} onChange={() => setTotal(false)} /> {t('부분 (Partial) — 어느 서브타입에도 속하지 않을 수 있음')}</label>
      </fieldset>
      <div className="form-group">
        <label htmlFor="subtypeClassification">{t('분류 속성 (선택)')}</label>
        <select className="form-control" id="subtypeClassification" disabled={readOnly || !supertype || !attributes.data} value={classification ?? ''}
          onChange={(e) => setClassification(e.target.value ? Number(e.target.value) : null)}>
          <option value="">{supertype ? (attributes.isPending ? t('불러오는 중입니다.') : t('분류 속성 없음')) : t('슈퍼타입을 먼저 골라주세요.')}</option>
          {own.length > 0 && <optgroup label={t('직접 정의')}>{own.map((a) => <option key={a.attributeId} value={a.attributeId}>{a.attributeName}</option>)}</optgroup>}
          {inherited.length > 0 && (
            <optgroup label={t('상속')}>
              {inherited.map((a) => <option key={a.attributeId} value={a.attributeId}>{t('{0} (정의 : {1})', a.attributeName, a.definedEntityName)}</option>)}
            </optgroup>
          )}
        </select>
        {group?.classificationAttributeName && readOnly && <p className="form-help">{group.classificationAttributeName}</p>}
      </div>

      <div className="form-group">
        <label>{t('서브타입 ({0}개, {1}개 이상)', members.length, SUBTYPE_MIN)}</label>
        {members.length === 0 && <p className="gh-muted">{t('서브타입을 {0}개 이상 지정해주세요.', SUBTYPE_MIN)}</p>}
        <ol className="logical-order-list subtype-members">
          {members.map((m, index) => (
            <li key={m.entityId}>
              <span>{m.entityName}</span>
              <input type="text" className="form-control form-control-sm" aria-label={t('{0} 의 분류 값', m.entityName)} placeholder={t('분류 값 (선택)')} maxLength={255}
                readOnly={readOnly} value={m.value} onChange={(e) => setMembers((current) => current.map((x) => (x.entityId === m.entityId ? { ...x, value: e.target.value } : x)))} />
              {!readOnly && (
                <span className="logical-actions">
                  <button type="button" className="btn btn-sm" aria-label={t('위로')} title={t('위로')} disabled={index === 0} onClick={() => move(index, -1)}>↑</button>
                  <button type="button" className="btn btn-sm" aria-label={t('아래로')} title={t('아래로')} disabled={index === members.length - 1} onClick={() => move(index, 1)}>↓</button>
                  <button type="button" className="btn btn-sm" onClick={() => setMembers((current) => current.filter((x) => x.entityId !== m.entityId))}>{t('빼기')}</button>
                </span>
              )}
            </li>
          ))}
        </ol>
        {!readOnly && <MemberFinder modelId={modelId} busy={checking} excluded={new Set([...members.map((m) => m.entityId), ...(supertype ? [supertype.id] : [])])} onPick={(hit) => void addMember(hit)} />}
      </div>
      {!readOnly && <button type="submit" className="btn btn-primary" disabled={checking || busy || recovery.blocked}>{busy ? t('처리 중…') : t('저장')}</button>}
    </form>
  );
}

/** 서브타입 찾기 : 같은 모델의 엔터티만 (논리 ERD 엔터티 찾기와 같은 API — 전체 그래프를 받지 않는다) */
function MemberFinder({ modelId, busy, excluded, onPick }: { modelId: number; busy: boolean; excluded: Set<number>; onPick: (hit: EntityHits['items'][number]) => void }) {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setQuery(keyword.trim()), 250);
    return () => clearTimeout(timer);
  }, [keyword]);
  const hits = useQuery({
    queryKey: ['logical-subtype-candidates', modelId, query],
    queryFn: ({ signal }) => api<EntityHits>(`/api/logical/model/${modelId}/erd/entities?${new URLSearchParams({ keyword: query, size: '20' })}`, { signal, silent: true }),
    enabled: query.length > 0,
    placeholderData: (previous) => previous,
  });
  const items = (hits.data?.items ?? []).filter((hit) => !excluded.has(hit.id));
  return (
    <div className="subtype-finder">
      <input type="search" className="form-control" aria-label={t('서브타입으로 추가할 엔터티 찾기')} placeholder={t('엔터티명·설명으로 찾아 추가')} maxLength={100}
        value={keyword} onChange={(e) => setKeyword(e.target.value)} />
      {query && (
        <ul className="logical-term-results">
          {items.map((hit) => (
            <li key={hit.id}>
              <button type="button" disabled={busy || !!hit.isApproval} onClick={() => onPick(hit)} title={hit.isApproval ? t('승인된 엔터티는 상속에 연결할 수 없습니다.') : undefined}>
                <strong>{hit.name}</strong>{hit.isApproval && <> <Label variant="success" dot>{t('승인')}</Label> <small>{t('승인된 엔터티는 상속에 연결할 수 없습니다.')}</small></>}
              </button>
            </li>
          ))}
          {hits.data && items.length === 0 && <li className="gh-muted">{t('추가할 수 있는 엔터티가 없습니다.')}</li>}
        </ul>
      )}
    </div>
  );
}

/** 엔터티 상세의 상속 정보 조회 (상세 대화상자·ERD 패널 공용) */
export function useInheritance(entityId: number) {
  return useQuery({
    queryKey: inheritanceQueryKey(entityId),
    queryFn: ({ signal }) => api<Inheritance>(`/api/logical/entity/${entityId}/inheritance`, { signal, silent: true }),
    gcTime: 0,
  });
}
