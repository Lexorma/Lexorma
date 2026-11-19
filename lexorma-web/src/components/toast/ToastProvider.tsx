import {
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { AlertIcon, CheckCircleIcon, InfoIcon, XCircleIcon, XIcon } from '@primer/octicons-react';
import { useAuth } from '../../hooks/useAuth';
import { t } from '../../lib/i18n';
import { motionMs } from '../../lib/motion';
import { ToastStore, type ToastAnnouncement, type ToastItem, type ToastOptions, type ToastVariant } from './toastStore';

/**
 * 공통 토스트 (UI-1 후속) : 업무를 막지 않는 짧은 결과 안내. 사용자 확인·판단이 필요한 것, 오류 상세·여러 건의 처리 결과는 기존 대화상자(useDialog)를 쓴다.
 * 정책은 docs/reference/ui-motion-guide.md §7.
 *
 * - useToast() 가 돌려주는 함수는 그 화면이 그려진 때의 범위(사용자·프로젝트·로그인 차례)에 묶인다. 로그아웃·재로그인·세션 만료로 범위가 바뀐 뒤
 *   이전 범위의 늦은 응답이 부르면 아무것도 보이지 않는다. 범위가 바뀌는 순간 보이는 것·대기열·타이머는 모두 지운다.
 * - 토스트 목록이 바뀌어도 업무 화면은 다시 그리지 않는다 (목록은 ToastViewport 만 구독한다).
 * - 토스트 목록은 최상위 레이어(popover)라 열린 대화상자 위에도 보이지만, 대화상자가 열린 동안은 inert(포커스·클릭·보조 기술 제외)라
 *   포커스 가두기·Esc 가 그대로이고, 대화상자 단추를 가리지 않게 오른쪽 위로 옮긴다 (toast.css).
 * - 스크린 리더 안내는 토스트 목록과 따로 둔 live region 으로 한다. 대화상자가 열려 있으면 그 바깥이 모두 inert 라 맨 위 대화상자 안으로 옮긴다 (Announcer).
 */

export interface ToastApi {
  success: (message: string, options?: ToastOptions) => void;
  info: (message: string, options?: ToastOptions) => void;
  warning: (message: string, options?: ToastOptions) => void;
  error: (message: string, options?: ToastOptions) => void;
  dismiss: (id: number) => void;
  /** 모두 지운다 : 세션 만료 안내처럼 공통 처리에서만 쓴다 (화면이 다른 화면의 알림을 지우지 않는다) */
  clear: () => void;
}

const ToastContext = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [store] = useState(() => new ToastStore());
  const { user, recovery, loading } = useAuth();
  const key = loading ? 'pending' : user ? `${user.id}:${user.projectId}:${recovery ? 'recovery' : 'work'}` : 'anonymous';
  // 같은 사용자·프로젝트로 다시 로그인해도 이전 로그인의 호출과 섞이지 않게 바뀔 때마다 차례를 올린다
  const generation = useRef({ key, count: 0 });
  if (generation.current.key !== key) generation.current = { key, count: generation.current.count + 1 };
  const scope = `${key}#${generation.current.count}`;

  useLayoutEffect(() => store.setScope(scope), [store, scope]);

  useEffect(() => {
    const onVisibility = () => store.setHidden(document.visibilityState === 'hidden');
    onVisibility();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      store.clear();
    };
  }, [store]);

  const api = useMemo<ToastApi>(() => {
    const show = (variant: ToastVariant) => (message: string, options?: ToastOptions) => {
      store.show(scope, variant, message, options);
    };
    return {
      success: show('success'),
      info: show('info'),
      warning: show('warning'),
      error: show('error'),
      dismiss: (id) => store.dismiss(id),
      clear: () => store.clear(),
    };
  }, [store, scope]);

  // 검증 스크립트용 : 개발 서버(npm run dev)와 `vite build --mode test` 빌드에만 있다 (운영 빌드에서는 코드째 빠진다).
  // 개수(보이는 것·대기열·타이머)만 읽는다 — 내용은 내보내지 않는다
  useEffect(() => {
    if (!(import.meta.env.DEV || import.meta.env.MODE === 'test')) return;
    const target = window as unknown as { __lexormaToastStats?: () => unknown };
    target.__lexormaToastStats = () => store.stats();
    return () => {
      delete target.__lexormaToastStats;
    };
  }, [store]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <ToastViewport store={store} />
    </ToastContext.Provider>
  );
}

const SUPPORTS_POPOVER = typeof HTMLElement !== 'undefined' && 'showPopover' in HTMLElement.prototype;
const ICONS: Record<ToastVariant, typeof CheckCircleIcon> = { success: CheckCircleIcon, info: InfoIcon, warning: AlertIcon, error: XCircleIcon };
const LABELS: Record<ToastVariant, () => string> = { success: () => t('성공'), info: () => t('안내'), warning: () => t('경고'), error: () => t('오류') };

function ToastViewport({ store }: { store: ToastStore }) {
  const { items, announcements } = useSyncExternalStore(store.subscribe, store.getSnapshot);
  const latest = announcements.length ? announcements[announcements.length - 1].id : null;
  const listRef = useRef<HTMLDivElement>(null);
  // 토스트로 들어오기 전 포커스 자리 : 포커스가 있던 토스트를 닫으면 다음 토스트 또는 이 자리로 돌려준다
  const returnTo = useRef<HTMLElement | null>(null);
  const positions = useRef(new Map<number, number>());
  const raisedFor = useRef<number | null>(null);
  const listBox = useRef<{ top: number; bottom: number } | null>(null);

  // 최상위 레이어(popover)에 둔다 : 열린 대화상자 위에도 보이지만 대화상자가 열린 동안은 inert 라 포커스·클릭을 가져가지 않는다 (누르면 아래 대화상자로 간다).
  // 새 토스트가 보일 때 대화상자가 열려 있으면 그 위로 다시 올린다. popover 를 지원하지 않는 브라우저에서는 일반 fixed 요소(z-index)다
  useLayoutEffect(() => {
    const list = listRef.current;
    if (!list || !SUPPORTS_POPOVER) return;
    const open = list.matches(':popover-open');
    if (items.length === 0) {
      if (open) list.hidePopover();
      return;
    }
    if (!open) list.showPopover();
    else if (latest !== null && raisedFor.current !== latest && document.querySelector('dialog:modal:not([data-closing])')) {
      list.hidePopover();
      list.showPopover();
    }
    raisedFor.current = latest;
  }, [items, latest]);

  // 중간 토스트가 빠지면 나머지가 제자리로 짧게 미끄러진다 (FLIP, 줄인 모션이면 바로). popover 를 연 뒤에 잰다 (닫힌 popover 는 크기가 없다).
  // 목록이 기준 가장자리째 옮겨졌으면(대화상자·AI 도우미 창이 열려 위로 갔거나 창 크기 변경) 움직이지 않고 자리만 새로 잰다
  useLayoutEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const next = new Map<number, number>();
    const box = list.getBoundingClientRect();
    const prev = listBox.current;
    listBox.current = { top: box.top, bottom: box.bottom };
    const sameAnchor = !!prev && (prev.top === box.top || prev.bottom === box.bottom);
    const duration = sameAnchor ? motionMs(180) : 0;
    for (const element of list.querySelectorAll<HTMLElement>('[data-toast-id]')) {
      const id = Number(element.dataset.toastId);
      const top = element.getBoundingClientRect().top; // 화면 기준 (목록 자체도 아래 기준으로 늘고 준다)
      next.set(id, top);
      const before = positions.current.get(id);
      if (before !== undefined && before !== top && duration > 0 && typeof element.animate === 'function') {
        element.animate([{ translate: `0 ${before - top}px` }, { translate: '0 0' }], { duration, easing: 'cubic-bezier(0.2, 0, 0, 1)' });
      }
    }
    positions.current = next;
  }, [items]);

  const close = (item: ToastItem, element: HTMLElement) => {
    if (element.contains(document.activeElement)) {
      const others = [...(listRef.current?.querySelectorAll<HTMLElement>('[data-toast-id]:not([data-leaving]) .toast-close') ?? [])]
        .filter((button) => !element.contains(button));
      const target = others[0] ?? (returnTo.current?.isConnected ? returnTo.current : null);
      target?.focus();
    }
    store.dismiss(item.id);
  };

  return createPortal(
    <>
      <Announcer announcements={announcements} />
      <div
        ref={listRef}
        className="toast-viewport"
        popover={SUPPORTS_POPOVER ? 'manual' : undefined}
        hidden={!SUPPORTS_POPOVER && items.length === 0}
        role="region"
        aria-label={t('알림 메시지')}
        onFocusCapture={(e) => {
          const from = e.relatedTarget as HTMLElement | null;
          if (from && !e.currentTarget.contains(from)) returnTo.current = from;
        }}
      >
        {items.map((item) => (
          <Toast key={item.id} item={item} store={store} onClose={close} />
        ))}
      </div>
    </>,
    document.body,
  );
}

/** 안내를 넣는 사이 간격 : 영역이 새 자리(대화상자)에 먼저 붙은 뒤 넣고, 짧은 사이에 여러 개가 보여도 하나씩 읽게 한다 */
const ANNOUNCE_GAP_MS = 120;
/** 읽은 뒤 영역을 비운다 (가장 짧은 토스트 시간) — 남겨 두면 영역을 다시 지나갈 때 지난 안내를 또 읽을 수 있다 */
const ANNOUNCE_CLEAR_MS = 3000;

interface AnnounceHost {
  element: HTMLElement;
  /** 닫는 중인 대화상자가 아직 맨 위에 있어 그 아래가 모두 inert 다 (빠질 때까지 기다린다) */
  blocked: boolean;
}

/** 안내를 둘 자리 : 열린 대화상자(닫는 중 제외) 중 맨 위 — 나중에 연 것이 body 뒤쪽에 붙는다 (DialogProvider) — 없으면 body */
function readHost(): AnnounceHost {
  const modals = [...document.querySelectorAll<HTMLDialogElement>('dialog:modal')];
  const open = modals.filter((dialog) => !dialog.hasAttribute('data-closing'));
  return { element: open[open.length - 1] ?? document.body, blocked: open.length !== modals.length };
}

function useAnnounceHost(): AnnounceHost {
  const [host, setHost] = useState(readHost);
  useEffect(() => {
    const update = () => {
      const next = readHost();
      setHost((prev) => (prev.element === next.element && prev.blocked === next.blocked ? prev : next));
    };
    // 대화상자는 body 바로 아래에 붙고 빠진다 (childList). 열림·닫는 중은 속성으로 바뀐다 (본문 전체의 자식 변경은 보지 않는다)
    const children = new MutationObserver(update);
    children.observe(document.body, { childList: true });
    const states = new MutationObserver(update);
    states.observe(document.body, { subtree: true, attributes: true, attributeFilter: ['open', 'data-closing'] });
    update();
    return () => {
      children.disconnect();
      states.disconnect();
    };
  }, []);
  return host;
}

/**
 * 스크린 리더 안내 : 늘 있는 두 영역 — 성공·안내·경고는 status(polite), 오류만 alert(assertive). 토스트 자체는 live region 이 아니다 (두 번 읽지 않게).
 * 대화상자가 열려 있으면 바깥은 inert 라 읽지 않으므로 영역을 맨 위 대화상자 안으로 옮긴다. 안내마다 한 번만 넣는다 (읽은 id 를 기억) —
 * 영역이 옮겨지거나 다시 그려져도, 같은 키로 시간만 다시 센 토스트도 다시 읽지 않는다
 */
function Announcer({ announcements }: { announcements: ToastAnnouncement[] }) {
  const host = useAnnounceHost();
  const [spoken, setSpoken] = useState<ToastAnnouncement | null>(null);
  const delivered = useRef(0);
  const next = announcements.find((a) => a.id > delivered.current) ?? null;

  // 자리가 바뀌면 이전 자리의 안내는 새 자리에 다시 넣지 않는다
  useLayoutEffect(() => setSpoken(null), [host.element]);
  // 범위가 바뀌어 모두 지워지면 영역도 비운다
  useLayoutEffect(() => {
    if (announcements.length === 0) setSpoken(null);
  }, [announcements.length]);

  useEffect(() => {
    if (!next || host.blocked) return;
    const timer = window.setTimeout(() => {
      delivered.current = next.id;
      setSpoken(next);
    }, ANNOUNCE_GAP_MS);
    return () => window.clearTimeout(timer);
  }, [next, host]);

  useEffect(() => {
    if (!spoken) return;
    const timer = window.setTimeout(() => setSpoken(null), ANNOUNCE_CLEAR_MS);
    return () => window.clearTimeout(timer);
  }, [spoken]);

  return createPortal(
    <>
      <div className="visually-hidden" role="status" aria-live="polite" aria-atomic="true">
        {spoken && !spoken.assertive && <span key={spoken.id}>{spoken.text}</span>}
      </div>
      <div className="visually-hidden" role="alert" aria-live="assertive" aria-atomic="true">
        {spoken?.assertive && <span key={spoken.id}>{spoken.text}</span>}
      </div>
    </>,
    host.element,
  );
}

function Toast({ item, store, onClose }: { item: ToastItem; store: ToastStore; onClose: (item: ToastItem, element: HTMLElement) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const Icon = ICONS[item.variant];
  return (
    <div
      ref={ref}
      className={`toast toast-${item.variant}`}
      data-toast-id={item.id}
      data-leaving={item.leaving ? '' : undefined}
      inert={item.leaving}
      onPointerEnter={() => store.pause(item.id, 'hover')}
      onPointerLeave={() => store.resume(item.id, 'hover')}
      onFocus={() => store.pause(item.id, 'focus')}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) store.resume(item.id, 'focus');
      }}
    >
      <span className="toast-icon" aria-hidden="true">
        <Icon size={16} />
      </span>
      <div className="toast-body">
        <span className="visually-hidden">{LABELS[item.variant]()}: </span>
        {item.title && <strong className="toast-title">{item.title}</strong>}
        <p className="toast-message">{item.message}</p>
      </div>
      <button type="button" className="toast-close" aria-label={t('알림 닫기')} onClick={() => ref.current && onClose(item, ref.current)}>
        <XIcon size={16} />
      </button>
    </div>
  );
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('ToastProvider 안에서 사용해야 합니다.');
  return ctx;
}
