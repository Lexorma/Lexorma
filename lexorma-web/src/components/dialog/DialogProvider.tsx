import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { t, tc } from '../../lib/i18n';

/**
 * 공통 다이얼로그 (예전 layout.html 의 #myAlert / #myConfirm / #mainConfirm / #popupDialog)
 *
 * - alert / confirm : 알림, 확인
 * - open            : 등록/수정/상세 다이얼로그 (예전 openDialog("div", ...))
 * - popup           : 다이얼로그 위에 겹쳐 띄우는 팝업 (예전 openPopupDialog)
 * 네이티브 <dialog>.showModal() 을 쓰므로 나중에 연 다이얼로그가 위에 뜬다.
 */

export interface DialogControls {
  close: () => void;
  setTitle: (title: ReactNode) => void;
}

type Content = ReactNode | ((controls: DialogControls) => ReactNode);

interface OpenOptions {
  title: ReactNode;
  content: Content;
  /** dialog 요소 클래스 (dialog-wide / dialog-xl / dialog-table-detail) */
  className?: string;
  onClose?: () => void;
}

interface PopupOptions {
  title: ReactNode;
  content: Content;
  /** wide(기본) | large */
  size?: 'wide' | 'large';
  onClose?: () => void;
}

export interface DialogApi {
  alert: (message: string, onClose?: () => void) => void;
  confirm: (message: string, onOk: () => void, onCancel?: () => void) => void;
  open: (options: OpenOptions) => () => void;
  popup: (options: PopupOptions) => () => void;
}

type Entry =
  | { id: number; kind: 'alert'; message: string; onClose?: () => void }
  | { id: number; kind: 'confirm'; message: string; onOk: () => void; onCancel?: () => void }
  | { id: number; kind: 'main'; title: ReactNode; content: Content; className?: string; onClose?: () => void }
  | { id: number; kind: 'popup'; title: ReactNode; content: Content; size?: 'wide' | 'large'; onClose?: () => void };

const DialogContext = createContext<DialogApi | null>(null);

let nextId = 0;

export function DialogProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [titles, setTitles] = useState<Record<number, ReactNode>>({});

  const remove = useCallback((id: number) => {
    setEntries((list) => list.filter((e) => e.id !== id));
    setTitles((t) => {
      if (!(id in t)) return t;
      const next = { ...t };
      delete next[id];
      return next;
    });
  }, []);

  const api = useMemo<DialogApi>(() => {
    const push = (entry: Entry) => setEntries((list) => [...list, entry]);
    return {
      alert: (message, onClose) => push({ id: ++nextId, kind: 'alert', message, onClose }),
      confirm: (message, onOk, onCancel) => push({ id: ++nextId, kind: 'confirm', message, onOk, onCancel }),
      open: (options) => {
        const id = ++nextId;
        push({ id, kind: 'main', ...options });
        return () => remove(id);
      },
      popup: (options) => {
        const id = ++nextId;
        push({ id, kind: 'popup', ...options });
        return () => remove(id);
      },
    };
  }, [remove]);

  return (
    <DialogContext.Provider value={api}>
      {children}
      {createPortal(
        entries.map((entry) => (
          <DialogView
            key={entry.id}
            entry={entry}
            title={titles[entry.id]}
            onRemove={remove}
            onTitle={(title) => setTitles((t) => ({ ...t, [entry.id]: title }))}
          />
        )),
        document.body,
      )}
    </DialogContext.Provider>
  );
}

function DialogView({
  entry,
  title,
  onRemove,
  onTitle,
}: {
  entry: Entry;
  title: ReactNode | undefined;
  onRemove: (id: number) => void;
  onTitle: (title: ReactNode) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const closedRef = useRef(false);

  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
    return () => {
      if (dialog?.open) dialog.close();
    };
  }, []);

  useDraggable(ref, entry.kind !== 'popup');

  // 닫기 : 목록에서 빼고 콜백 호출 (한 번만)
  const finish = useCallback(
    (callback?: () => void) => {
      if (closedRef.current) return;
      closedRef.current = true;
      onRemove(entry.id);
      if (callback) callback();
    },
    [entry.id, onRemove],
  );

  const controls = useMemo<DialogControls>(
    () => ({
      close: () => finish('onClose' in entry ? entry.onClose : undefined),
      setTitle: onTitle,
    }),
    [entry, finish, onTitle],
  );

  // ESC
  const onCancel = (e: React.SyntheticEvent) => {
    e.preventDefault();
    if (entry.kind === 'confirm') finish(entry.onCancel);
    else controls.close();
  };

  if (entry.kind === 'alert') {
    return (
      <dialog ref={ref} className="dialog-message" onCancel={onCancel}>
        <h3>{t('알림')}</h3>
        <p style={{ whiteSpace: 'pre-line' }}>{entry.message}</p>
        <div className="popup-button-container">
          <button type="button" className="close" onClick={() => controls.close()} autoFocus>
            {t('닫기')}
          </button>
        </div>
      </dialog>
    );
  }

  if (entry.kind === 'confirm') {
    return (
      <dialog ref={ref} className="dialog-message" onCancel={onCancel}>
        <h3>{t('알림')}</h3>
        <p style={{ whiteSpace: 'pre-line' }}>{entry.message}</p>
        <div className="popup-button-container">
          <button type="button" className="confirm" onClick={() => finish(entry.onOk)} autoFocus>
            {tc('버튼', '확인')}
          </button>
          <button type="button" className="close" onClick={() => finish(entry.onCancel)}>
            {t('닫기')}
          </button>
        </div>
      </dialog>
    );
  }

  const body = typeof entry.content === 'function' ? entry.content(controls) : entry.content;

  if (entry.kind === 'main') {
    return (
      <dialog ref={ref} className={entry.className} onCancel={onCancel}>
        <div className="dialog-header">
          <h3>{title ?? entry.title}</h3>
          <button type="button" onClick={controls.close} className="close-btn" aria-label={t('닫기')}>
            &times;
          </button>
        </div>
        <div className="main-dialog-content">{body}</div>
      </dialog>
    );
  }

  return (
    <dialog
      ref={ref}
      className={`popup-dialog${entry.size === 'large' ? ' popup-dialog-large' : ''}`}
      onCancel={onCancel}
    >
      <div className="dialog-header">
        <h3 className="popup-dialog-title">{title ?? entry.title}</h3>
        <button type="button" onClick={controls.close} className="close-btn" aria-label={t('닫기')}>
          &times;
        </button>
      </div>
      <div className="popup-dialog-body">{body}</div>
    </dialog>
  );
}

/**
 * 다이얼로그 끌어서 옮기기 (예전 makeDialogDraggable) : 입력 요소 위에서는 끌지 않는다
 * <p>
 * 위치는 CSS 그대로 뷰포트 기준(`position: fixed`, 가운데 + translate)이다. 예전처럼 열 때 `absolute` 로 바꾸면 최상위 레이어(showModal)의
 * absolute 는 문서 첫 화면 기준이라, 스크롤한 화면에서 연 다이얼로그가 화면 밖에 놓였다. 끌기를 시작할 때만 지금 보이는 자리(getBoundingClientRect,
 * 뷰포트 좌표)를 left/top 으로 옮기고 translate 를 끈다 — 같은 좌표계(clientX/Y)로 옮기므로 튀지 않는다.
 * 옮긴 다이얼로그는 뷰포트 안에 머물게 하고(창 크기가 바뀌어도), 새로 연 다이얼로그는 늘 가운데에서 시작한다 (요소마다 인라인 위치).
 */
function useDraggable(ref: React.RefObject<HTMLDialogElement | null>, enabled: boolean) {
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog || !enabled) return;

    let dragging = false;
    let moved = false;
    let offsetX = 0;
    let offsetY = 0;

    dialog.style.cursor = 'move';

    // 뷰포트 안으로 : 다이얼로그가 뷰포트보다 크면 왼쪽·위를 맞춘다 (제목·닫기 버튼이 보이게, 내용은 안에서 스크롤)
    const place = (left: number, top: number) => {
      // 뷰포트 = 스크롤바를 뺀 보이는 영역 (fixed 의 기준과 같다)
      const maxLeft = Math.max(0, document.documentElement.clientWidth - dialog.offsetWidth);
      const maxTop = Math.max(0, document.documentElement.clientHeight - dialog.offsetHeight);
      dialog.style.left = `${Math.min(Math.max(0, left), maxLeft)}px`;
      dialog.style.top = `${Math.min(Math.max(0, top), maxTop)}px`;
    };

    const onDown = (e: MouseEvent) => {
      if (e.button !== 0) return;
      const tag = (e.target as HTMLElement).tagName.toLowerCase();
      if (['input', 'textarea', 'select', 'button', 'label', 'option'].includes(tag)) return;
      if ((e.target as HTMLElement).closest('input, textarea, select, button, label, table, a')) return;
      const rect = dialog.getBoundingClientRect();
      if (!moved) {
        // 가운데 정렬(translate)에서 지금 보이는 자리의 좌표로 바꾼다
        dialog.style.transform = 'none';
        dialog.style.left = `${rect.left}px`;
        dialog.style.top = `${rect.top}px`;
        moved = true;
      }
      dragging = true;
      offsetX = e.clientX - rect.left;
      offsetY = e.clientY - rect.top;
      document.body.style.userSelect = 'none';
    };
    const onMove = (e: MouseEvent) => {
      if (!dragging) return;
      place(e.clientX - offsetX, e.clientY - offsetY);
    };
    const onUp = () => {
      if (!dragging) return;
      dragging = false;
      document.body.style.userSelect = '';
    };
    const onResize = () => {
      if (!moved) return;
      const rect = dialog.getBoundingClientRect();
      place(rect.left, rect.top);
    };

    dialog.addEventListener('mousedown', onDown);
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    window.addEventListener('resize', onResize);
    return () => {
      dialog.removeEventListener('mousedown', onDown);
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      window.removeEventListener('resize', onResize);
      if (dragging) document.body.style.userSelect = '';
    };
  }, [ref, enabled]);
}

export function useDialog(): DialogApi {
  const ctx = useContext(DialogContext);
  if (!ctx) throw new Error('DialogProvider 안에서 사용해야 합니다.');
  return ctx;
}
