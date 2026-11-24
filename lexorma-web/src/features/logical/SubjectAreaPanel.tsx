import { useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { ChevronDownIcon, ChevronRightIcon, PlusIcon } from '@primer/octicons-react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { useToast } from '../../components/toast/ToastProvider';
import { api, ApiError } from '../../lib/api';
import { useConflictRecovery, useSubmitting } from '../../components/conflict/ConflictRecovery';
import { runListWithReview, runWithReview } from '../../components/conflict/conflictActions';
import { t } from '../../lib/i18n';
import {
  areaHeight, areaPath, MAX_AREA_DEPTH, subtreeIds, type AreaFilter, type SubjectArea, type SubjectAreaTree,
} from './subjectArea';

/** 트리 한 줄 : 가상 항목(전체·미분류) 또는 영역 */
interface TreeRow {
  key: string;
  filter: AreaFilter;
  label: string;
  count: number;
  level: number;
  area?: SubjectArea;
  expandable: boolean;
  expanded: boolean;
}

const sameFilter = (a: AreaFilter, b: AreaFilter) =>
  a.kind === b.kind && (a.kind !== 'area' || (b.kind === 'area' && a.areaId === b.areaId));

/**
 * 주제 영역 패널 (M-6 후속 4차) : 전체 · 미분류 · 영역 계층(펼침·접힘). 선택은 영역 ID 로 한다 (같은 이름이 다른 상위 아래 있을 수 있다).
 * 키보드 : ↑↓ 이동, → 펼침/첫 하위, ← 접힘/상위, Home·End, Enter·Space 선택 (WAI-ARIA tree). 검색하면 계층 대신 전체 경로 목록을 보인다 (D-27)
 */
export function SubjectAreaPanel({ tree, filter, onFilter, canEdit, onCreate, loading, error }: {
  tree: SubjectAreaTree | undefined;
  filter: AreaFilter;
  onFilter: (filter: AreaFilter) => void;
  canEdit: boolean;
  onCreate: () => void;
  loading: boolean;
  error: boolean;
}) {
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set());
  const [opened, setOpened] = useState<number | null>(null); // 방금 펼친 영역만 짧게 나타낸다 (트리 전체를 다시 움직이지 않는다)
  const [query, setQuery] = useState('');
  const [focusKey, setFocusKey] = useState<string | null>(null);
  const list = useRef<HTMLUListElement>(null);
  const areas = tree?.areas ?? [];

  const rows = useMemo<TreeRow[]>(() => {
    const result: TreeRow[] = [
      { key: 'all', filter: { kind: 'all' }, label: t('전체'), count: tree?.entityCount ?? 0, level: 1, expandable: false, expanded: false },
      { key: 'unclassified', filter: { kind: 'unclassified' }, label: t('미분류'), count: tree?.unclassifiedCount ?? 0, level: 1, expandable: false, expanded: false },
    ];
    const hidden = new Set<number>();
    for (const area of areas) {
      if (area.parentId != null && (hidden.has(area.parentId) || collapsed.has(area.parentId))) {
        hidden.add(area.id);
        continue;
      }
      const includeChildren = filter.kind === 'area' && filter.areaId === area.id ? filter.includeChildren : true;
      result.push({
        key: `area-${area.id}`, filter: { kind: 'area', areaId: area.id, includeChildren }, label: area.areaName, count: area.totalEntityCount, level: area.depth,
        area, expandable: area.childCount > 0, expanded: area.childCount > 0 && !collapsed.has(area.id),
      });
    }
    return result;
  }, [tree, collapsed, filter]);

  const needle = query.trim().toLowerCase();
  const hits = needle ? areas.filter((a) => a.areaName.toLowerCase().includes(needle)) : [];
  const selectedKey = rows.find((r) => sameFilter(r.filter, filter))?.key ?? (filter.kind === 'area' ? `area-${filter.areaId}` : filter.kind);
  const activeKey = focusKey && rows.some((r) => r.key === focusKey) ? focusKey : selectedKey;

  const toggle = (area: SubjectArea) => {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(area.id)) {
        next.delete(area.id);
        setOpened(area.id);
      } else {
        next.add(area.id);
      }
      return next;
    });
  };
  const focusRow = (key: string) => {
    setFocusKey(key);
    requestAnimationFrame(() => list.current?.querySelector<HTMLElement>(`[data-key="${key}"]`)?.focus());
  };
  const onKey = (event: KeyboardEvent<HTMLElement>, row: TreeRow, index: number) => {
    const move = (to: number) => {
      event.preventDefault();
      const target = rows[Math.max(0, Math.min(rows.length - 1, to))];
      if (target) focusRow(target.key);
    };
    switch (event.key) {
      case 'ArrowDown': return move(index + 1);
      case 'ArrowUp': return move(index - 1);
      case 'Home': return move(0);
      case 'End': return move(rows.length - 1);
      case 'ArrowRight':
        event.preventDefault();
        if (row.area && row.expandable && !row.expanded) toggle(row.area);
        else if (row.expanded) move(index + 1);
        return;
      case 'ArrowLeft':
        event.preventDefault();
        if (row.area && row.expanded) toggle(row.area);
        else if (row.area?.parentId != null) focusRow(`area-${row.area.parentId}`);
        return;
      case 'Enter':
      case ' ':
        event.preventDefault();
        onFilter(row.filter);
        return;
    }
  };

  return (
    <section className="subject-panel" aria-label={t('주제 영역')}>
      <div className="subject-panel-head">
        <h2>{t('주제 영역')}</h2>
        {canEdit && (
          <button type="button" className="btn btn-sm" onClick={onCreate} aria-label={t('주제 영역 등록')} title={t('주제 영역 등록')}><PlusIcon /></button>
        )}
      </div>
      <input type="search" className="form-control subject-search" aria-label={t('주제 영역 검색')} placeholder={t('영역명 검색')} maxLength={100}
        value={query} onChange={(e) => setQuery(e.target.value)} />
      {error && <p className="erd-muted" role="alert">{t('주제 영역을 불러오지 못했습니다.')}</p>}
      {!tree && loading && <p className="erd-muted">{t('불러오는 중입니다.')}</p>}
      {needle ? (
        <ul className="subject-hits" aria-label={t('주제 영역 검색 결과')}>
          {hits.length === 0 && <li className="erd-muted">{t('찾는 주제 영역이 없습니다.')}</li>}
          {hits.map((area) => {
            const selected = filter.kind === 'area' && filter.areaId === area.id;
            return (
              <li key={area.id}>
                <button type="button" className={`subject-hit${selected ? ' is-selected' : ''}`} aria-current={selected || undefined}
                  onClick={() => onFilter({ kind: 'area', areaId: area.id, includeChildren: true })}>
                  <span className="subject-hit-path">{areaPath(area)}</span>
                  <span className="subject-count">{area.totalEntityCount}</span>
                </button>
              </li>
            );
          })}
        </ul>
      ) : (
        <ul className="subject-tree" role="tree" aria-label={t('주제 영역')} ref={list}>
          {rows.map((row, index) => {
            const selected = row.key === selectedKey;
            return (
              <li key={row.key} role="treeitem" data-key={row.key} aria-level={row.level} aria-selected={selected}
                aria-expanded={row.expandable ? row.expanded : undefined} tabIndex={row.key === activeKey ? 0 : -1}
                className={`subject-node${selected ? ' is-selected' : ''}${row.area && row.area.parentId === opened ? ' motion-fade-in' : ''}`}
                style={{ paddingInlineStart: 4 + (row.level - 1) * 16 }}
                onKeyDown={(e) => onKey(e, row, index)} onFocus={() => setFocusKey(row.key)}
                onClick={() => onFilter(row.filter)}>
                {row.expandable ? (
                  <button type="button" className="subject-toggle" tabIndex={-1} aria-hidden
                    onClick={(e) => { e.stopPropagation(); if (row.area) toggle(row.area); }}>
                    {row.expanded ? <ChevronDownIcon size={12} /> : <ChevronRightIcon size={12} />}
                  </button>
                ) : <span className="subject-toggle" aria-hidden />}
                <span className="subject-name" title={row.area ? areaPath(row.area) : undefined}>{row.label}</span>
                <span className="subject-count" aria-label={t('엔터티 {0}개', row.count)}>{row.count}</span>
              </li>
            );
          })}
          {tree && areas.length === 0 && <li className="erd-muted subject-empty" role="none">{canEdit ? t('주제 영역이 없습니다. [+] 로 업무 영역을 만들어 엔터티를 분류할 수 있습니다.') : t('주제 영역이 없습니다.')}</li>}
        </ul>
      )}
    </section>
  );
}

/** 상위 영역 선택지 : 최상위 + 영역(들여쓰기). 자기·자기 하위, 옮기면 3단계를 넘는 영역은 고를 수 없다 (서버가 다시 검사한다) */
function parentOptions(areas: SubjectArea[], area?: SubjectArea) {
  const blocked = area ? subtreeIds(areas, area.id) : new Set<number>();
  const height = area ? areaHeight(areas, area.id) : 0;
  return areas.map((candidate) => {
    const reason = blocked.has(candidate.id) ? t('자기 자신 또는 하위 영역')
      : candidate.depth + 1 + height > MAX_AREA_DEPTH ? t('{0}단계를 넘습니다', MAX_AREA_DEPTH) : '';
    return { area: candidate, reason };
  });
}

/** 주제 영역 등록·수정·이동 (공통 대화상자). 성공하면 onSaved, 실패(이름 중복·깊이·409)는 공통 오류 안내 — 입력은 그대로 둔다 */
export function AreaForm({ modelId, tree, area, parentId, onSaved }: {
  modelId: number;
  tree: SubjectAreaTree;
  area?: SubjectArea;
  /** 새 영역의 처음 상위 (하위 영역 추가) */
  parentId?: number | null;
  onSaved: (saved: SubjectArea) => void;
}) {
  const dialog = useDialog();
  const [name, setName] = useState(area?.areaName ?? '');
  const [description, setDescription] = useState(area?.areaDescription ?? '');
  const [parent, setParent] = useState<string>(String(area ? area.parentId ?? '' : parentId ?? ''));
  const [order, setOrder] = useState(area ? String(area.sortOrder) : '');
  const options = parentOptions(tree.areas, area);
  const [busy, run] = useSubmitting();
  type Values = { name: string; parent: string; description: string; order: string };
  const valuesOf = (a: SubjectArea): Values => ({ name: a.areaName, parent: String(a.parentId ?? ''), description: a.areaDescription ?? '', order: String(a.sortOrder) });
  const areaName = (id: string) => (id ? tree.areas.find((a) => String(a.id) === id)?.areaName ?? `#${id}` : t('(최상위)'));
  // 409 복구 (M8-C4c-2 — M8-C3 공통) : 모델의 주제 영역 목록에서 이 영역의 최신 값을 읽어 이름·상위·설명·순서를 비교한다. 없어졌으면 다시 저장할 수 없다
  const recovery = useConflictRecovery<Values>({
    initial: area ? valuesOf(area) : { name: '', parent: '', description: '', order: '' },
    base: area?.modifiedAt,
    fields: [
      { key: 'name', label: t('주제 영역명'), get: (v) => v.name },
      { key: 'parent', label: t('상위 주제 영역'), get: (v) => v.parent, show: (v) => areaName(v.parent) },
      { key: 'description', label: t('설명'), get: (v) => v.description },
      { key: 'order', label: t('표시 순서'), get: (v) => v.order },
    ],
    load: async () => {
      const latest = (await api<SubjectAreaTree>(`/api/logical/model/${modelId}/subjectArea`, { silent: true })).areas.find((a) => a.id === area?.id);
      if (!latest) throw new ApiError(400, t('주제 영역을 찾을 수 없습니다.'), 'LOGICAL_MODEL_IS_NOT_DEFINED');
      return { values: valuesOf(latest), base: latest.modifiedAt ?? null };
    },
  });

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return dialog.alert(t('주제 영역명은(는) 필수 입력 항목입니다.'));
    dialog.confirm(area ? t('수정하시겠습니까?') : t('저장하시겠습니까?'), () => void run(async () => {
      let saved: SubjectArea;
      try {
        saved = await api<SubjectArea>('/api/logical/subjectArea', {
          method: 'POST',
          conflict: 'handled',
          body: {
            id: area?.id ?? null, modelId, parentId: parent ? Number(parent) : null, areaName: name.trim(), areaDescription: description.trim() || null,
            sortOrder: order.trim() === '' ? null : Number(order), modifiedAt: area ? recovery.base : null, // 409 복구 뒤에는 최신 기준값
          },
        });
      } catch (error) {
        if (recovery.catchConflict(error, { name, parent, description, order })) return;
        return; // 그 밖의 오류(이름 중복·깊이·잠금 충돌)는 공통 안내 — 입력은 그대로
      }
      onSaved(saved);
    }));
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      {recovery.notice((latest, picks) => {
        if (!picks.has('name')) setName(latest.name);
        if (!picks.has('parent')) setParent(latest.parent);
        if (!picks.has('description')) setDescription(latest.description);
        if (!picks.has('order')) setOrder(latest.order);
      })}
      <div className="form-group">
        <label htmlFor="areaName">{t('주제 영역명')}</label>
        <input type="text" className="form-control" id="areaName" maxLength={255} required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="form-group">
        <label htmlFor="areaParent">{t('상위 주제 영역')}</label>
        <select className="form-control" id="areaParent" value={parent} onChange={(e) => setParent(e.target.value)}>
          <option value="">{t('(최상위)')}</option>
          {options.map(({ area: candidate, reason }) => (
            <option key={candidate.id} value={candidate.id} disabled={!!reason}>
              {`${'  '.repeat(candidate.depth - 1)}${candidate.areaName}${reason ? ` — ${reason}` : ''}`}
            </option>
          ))}
        </select>
        <small className="gh-muted">{t('주제 영역은 최상위를 포함해 {0}단계까지 만들 수 있습니다. 같은 상위 아래에서는 이름이 겹칠 수 없습니다.', MAX_AREA_DEPTH)}</small>
      </div>
      <div className="form-group">
        <label htmlFor="areaDescription">{t('설명')}</label>
        <textarea className="form-control" id="areaDescription" maxLength={1000} rows={3} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="areaOrder">{t('표시 순서')}</label>
        <input type="number" className="form-control" id="areaOrder" min={0} max={1000000} step={1} value={order} placeholder={t('비우면 맨 뒤')}
          onChange={(e) => setOrder(e.target.value)} />
      </div>
      <button type="submit" className="btn btn-primary" disabled={busy || recovery.blocked}>{busy ? t('처리 중…') : t('저장')}</button>
    </form>
  );
}

/** 분류 대상 엔터티 (지금 소속 포함 — 서버가 그 사이 바뀌었는지 비교한다) */
export interface ClassifyTarget {
  id: number;
  name: string;
  subjectAreaId: number | null;
}

/**
 * 분류 변경 (D-20·D-21) : 고른 엔터티 수·지금 소속·옮길 영역(미분류 포함)을 보여 주고 확인 뒤 한 번에 보낸다.
 * 하나라도 그 사이 소속이 바뀌었으면 서버가 409 로 아무것도 바꾸지 않는다 → 공통 오류 안내 뒤 최신 상태를 다시 읽는다 (자동으로 다시 시도하지 않는다)
 */
export function ClassifyForm({ modelId, tree, targets, initialArea, onDone }: {
  modelId: number;
  tree: SubjectAreaTree;
  targets: ClassifyTarget[];
  initialArea: number | null;
  onDone: (result: { changed: number; unchanged: number } | null) => void;
}) {
  const dialog = useDialog();
  const [target, setTarget] = useState(initialArea == null ? '' : String(initialArea));
  const byId = new Map(tree.areas.map((a) => [a.id, a]));
  const current = new Map<string, number>();
  targets.forEach((e) => {
    const label = e.subjectAreaId == null ? t('미분류') : areaPath(byId.get(e.subjectAreaId)) || `#${e.subjectAreaId}`;
    current.set(label, (current.get(label) ?? 0) + 1);
  });
  const targetLabel = target ? areaPath(byId.get(Number(target))) : t('미분류');

  const [busy, run] = useSubmitting();
  const areaLabel = (id: number | null) => (id == null ? t('미분류') : areaPath(byId.get(id)) || `#${id}`);
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    dialog.confirm(t('엔터티 {0}개를 {1} (으)로 분류하시겠습니까?', targets.length, targetLabel), () => void run(async () => {
      // 409 (그 사이 소속이 바뀐 엔터티가 있다 — 서버는 아무것도 바꾸지 않았다, M8-C4c-2) : 엔터티마다 확인한 영역 ↔ 지금 영역을 보여 주고,
      // 사용자가 [최신 소속으로 다시 분류] 를 눌러야 지금 소속을 기준값으로 다시 보낸다 (자동 재시도 없음). 잠금 충돌은 공통 안내 뒤 그대로 다시 누른다
      const seenAreas = new Map(targets.map((x) => [x.id, x.subjectAreaId]));
      let latestAreas = seenAreas;
      let reviewed = false;
      let result: { changed: number; unchanged: number } | null = null;
      const snapshot = (id: number | null) => ({ modifiedAt: null, fields: [{ key: 'area', label: t('주제 영역'), value: areaLabel(id) }] });
      try {
        const ok = await runListWithReview({
          dialog, title: t('분류 변경'), intro: t('선택한 엔터티 중 다른 사용자가 소속을 바꾼 엔터티가 있어 아무것도 바꾸지 않았습니다.'),
          items: targets.map((x) => ({ id: x.id, label: x.name, seen: snapshot(x.subjectAreaId) })),
          load: async () => {
            const found = new Map<number, number | null>();
            for (let from = 0; from < targets.length; from += 10) {
              const batch = await Promise.all(targets.slice(from, from + 10).map((x) => api<{ id: number; subjectAreaId?: number | null }>(`/api/logical/entity/${x.id}`, { silent: true }).catch(() => null)));
              batch.forEach((entity) => { if (entity) found.set(entity.id, entity.subjectAreaId ?? null); });
            }
            latestAreas = found;
            reviewed = true;
            return new Map([...found].map(([id, areaId]) => [id, snapshot(areaId)]));
          },
          act: async () => {
            const expected = reviewed ? latestAreas : seenAreas;
            result = await api<{ changed: number; unchanged: number }>(`/api/logical/model/${modelId}/subjectArea/entities`, {
              method: 'PUT', conflict: 'handled',
              body: { subjectAreaId: target ? Number(target) : null, entities: targets.map((x) => ({ entityId: x.id, expectedSubjectAreaId: expected.get(x.id) ?? null })) },
            });
          },
          note: t('엔터티마다 확인했던 주제 영역과 지금 영역입니다. 지금 소속을 기준으로 다시 분류할지 정해 주세요.'),
          retryLabel: t('최신 소속으로 다시 분류'), onDone: () => onDone(result),
        });
        if (ok) onDone(result);
      } catch { /* 잠금 충돌·권한 등 : 공통 안내 — 대화상자와 선택은 그대로 */ }
    }));
  };

  return (
    <form className="edit-form" onSubmit={onSubmit}>
      <p>{t('선택한 엔터티 {0}개', targets.length)}</p>
      <ul className="subject-classify-current">
        {[...current].map(([label, n]) => <li key={label}><span>{label}</span> <span className="gh-muted">{t('{0}개', n)}</span></li>)}
      </ul>
      <div className="form-group" style={{ marginBottom: 10 }}>
        <label htmlFor="classifyTarget">{t('옮길 주제 영역')}</label>
        <select className="form-control" id="classifyTarget" value={target} autoFocus onChange={(e) => setTarget(e.target.value)}>
          <option value="">{t('미분류 (분류 해제)')}</option>
          {tree.areas.map((a) => <option key={a.id} value={a.id}>{`${'  '.repeat(a.depth - 1)}${a.areaName}`}</option>)}
        </select>
        <small className="gh-muted">{t('승인된 엔터티도 분류할 수 있습니다. 엔터티의 정의·승인 상태는 바뀌지 않습니다.')}</small>
      </div>
      <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? t('처리 중…') : t('분류 변경')}</button>
    </form>
  );
}

/** 영역 삭제 : 소속 엔터티·하위 영역이 있으면 먼저 옮기거나 해제하라고 안내한다 (삭제하지 않는다). 없으면 확인 뒤 삭제 */
export function useAreaRemoval(onDeleted: (area: SubjectArea) => void) {
  const dialog = useDialog();
  const toast = useToast();
  return (area: SubjectArea) => {
    if (area.childCount > 0) return dialog.alert(t('하위 주제 영역이 {0}개 있어 삭제할 수 없습니다. 하위 영역을 먼저 옮기거나 삭제해주세요.', area.childCount));
    if (area.directEntityCount > 0) return dialog.alert(t('주제 영역에 엔터티 {0}개가 소속되어 있어 삭제할 수 없습니다. 엔터티의 분류를 먼저 바꾸거나 해제해주세요.', area.directEntityCount));
    dialog.confirm(t('{0} 주제 영역을 삭제하시겠습니까? 엔터티·관계는 바뀌지 않고, 이 영역의 ERD 배치만 함께 지워집니다.', areaPath(area)), async () => {
      // 409 (확인한 뒤 이름·상위·소속이 바뀌었다) : 확인한 영역 ↔ 최신 영역을 보여 주고 사용자가 다시 삭제를 고른다 (M8-C4c-2). 없어졌거나 하위·소속이 생겼으면 다시 삭제할 수 없다
      const snapshot = (a: SubjectArea) => ({
        modifiedAt: a.modifiedAt ?? null,
        fields: [
          { key: 'name', label: t('주제 영역명'), value: areaPath(a) },
          { key: 'description', label: t('설명'), value: a.areaDescription ?? '' },
          { key: 'children', label: t('하위 주제 영역'), value: t('{0}개', a.childCount) },
          { key: 'entities', label: t('소속 엔터티'), value: t('{0}개', a.directEntityCount) },
        ],
      });
      let latestArea: SubjectArea | undefined = area;
      const done = () => { onDeleted(area); toast.success(t('정상적으로 삭제되었습니다.')); };
      try {
        const ok = await runWithReview({
          dialog, title: t('주제 영역 삭제'), intro: t('다른 사용자가 이 항목을 변경했습니다.'), seen: snapshot(area),
          load: async () => {
            latestArea = (await api<SubjectAreaTree>(`/api/logical/model/${area.modelId}/subjectArea`, { silent: true })).areas.find((a) => a.id === area.id);
            if (!latestArea) throw new ApiError(400, t('주제 영역을 찾을 수 없습니다.'), 'LOGICAL_MODEL_IS_NOT_DEFINED');
            return snapshot(latestArea);
          },
          act: async (state) => { await api(`/api/logical/subjectArea/${area.id}?modifiedAt=${encodeURIComponent(state.modifiedAt ?? '')}`, { method: 'DELETE', conflict: 'handled' }); },
          block: () => (latestArea && (latestArea.childCount > 0 || latestArea.directEntityCount > 0)
            ? t('지금은 하위 주제 영역이나 소속 엔터티가 있어 삭제할 수 없습니다. 먼저 옮기거나 해제해주세요.') : undefined),
          retryLabel: t('최신 내용으로 삭제'), onDone: done,
        });
        if (ok) done();
      } catch { /* 공통 안내 */ }
    });
  };
}

export type { DialogControls };
