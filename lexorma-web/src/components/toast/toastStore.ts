import { prefersReducedMotion } from '../../lib/motion';

/**
 * 토스트 상태 (UI-1 후속) : React 밖의 작은 저장소. 화면은 useSyncExternalStore 로 이 목록만 구독한다 (업무 화면은 다시 그리지 않는다).
 *
 * - 동시에 보이는 토스트는 MAX_VISIBLE 개. 넘치면 대기열(MAX_QUEUED)에 두고, 자리가 나면 순서대로 보인다.
 *   대기열이 차면 가장 오래된 성공·안내부터 버린다 (오류·경고는 성공·안내가 없을 때만 버린다).
 * - 자동 종료 시간은 화면에 보인 때부터 센다 (대기 중에는 줄지 않는다). 마우스·키보드 포커스가 토스트 위에 있거나 브라우저 탭이 가려지면 멈추고,
 *   멈춘 이유가 모두 풀리면 남은 시간만큼 다시 센다.
 * - 범위(scope = 사용자·프로젝트) : 범위가 바뀌면 보이는 것·사라지는 중인 것·대기열·타이머를 모두 바로 지운다. 이전 범위에서 만든 호출(늦게 온 응답)은 버린다.
 * - 상태는 메모리에만 둔다 (새로고침·재로그인하면 남지 않는다).
 */

export type ToastVariant = 'success' | 'info' | 'warning' | 'error';

export interface ToastOptions {
  title?: string;
  /** 밀리초. 생략하면 유형별 기본값 (긴 문장은 조금 더 길게) */
  duration?: number;
  /** 같은 키의 토스트가 보이거나 기다리는 중이면 새로 만들지 않고 그 토스트의 시간을 처음부터 다시 센다.
   *  키가 없으면 문장이 같아도 따로 보인다 (같은 문장을 돌려주는 서로 다른 업무 결과를 합치지 않는다) */
  dedupeKey?: string;
}

export interface ToastItem {
  id: number;
  variant: ToastVariant;
  message: string;
  title?: string;
  leaving: boolean;
}

/** 스크린 리더 안내 : 보일 때 한 번. id 로 구분해 같은 문장이 이어져도 각각 읽고, 이미 읽은 id 는 다시 읽지 않는다 */
export interface ToastAnnouncement {
  id: number;
  text: string;
  assertive: boolean;
}

export interface ToastSnapshot {
  items: ToastItem[];
  /** 최근에 보인 순서대로 (오래된 것부터, 최대 MAX_ANNOUNCEMENTS) — 짧은 사이에 여러 개가 보여도 빠뜨리지 않는다 */
  announcements: ToastAnnouncement[];
}

export const MAX_VISIBLE = 3;
export const MAX_QUEUED = 10;
export const DEFAULT_DURATION: Record<ToastVariant, number> = { success: 3000, info: 4000, warning: 5000, error: 6000 };
/** 긴 문장은 글자당 이만큼 더 보인다 (최대 MAX_DURATION) */
const PER_CHAR_MS = 60;
const MAX_DURATION = 10000;
const MAX_ANNOUNCEMENTS = 5;
/** 사라지는 모션(motion.css --motion-duration-fast 120ms)보다 넉넉히 — 이 뒤에는 목록에서 뺀다 */
export const EXIT_MS = 160;

type PauseReason = 'hover' | 'focus';

interface Entry {
  id: number;
  variant: ToastVariant;
  message: string;
  title?: string;
  duration: number;
  dedupeKey?: string;
  leaving: boolean;
  /** 남은 시간 (보이는 동안 줄어든다) */
  remaining: number;
  /** 지금 세기 시작한 시각 (멈춰 있으면 null) */
  runningSince: number | null;
  timer: number | null;
  exitTimer: number | null;
  pauses: Set<PauseReason>;
}

export function durationFor(variant: ToastVariant, message: string, title?: string): number {
  const length = message.length + (title?.length ?? 0);
  return Math.max(DEFAULT_DURATION[variant], Math.min(MAX_DURATION, length * PER_CHAR_MS));
}

let nextId = 0;

export class ToastStore {
  private visible: Entry[] = [];
  private queued: Entry[] = [];
  private scope = '';
  private hidden = false;
  private announcements: ToastAnnouncement[] = [];
  private snapshot: ToastSnapshot = { items: [], announcements: [] };
  private listeners = new Set<() => void>();

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getSnapshot = () => this.snapshot;

  /** 사용자·프로젝트가 바뀌면 이전 범위의 토스트를 모두 지운다 */
  setScope(scope: string) {
    if (scope === this.scope) return;
    this.scope = scope;
    this.clear();
  }

  /** 지금 범위에서 만든 호출만 보인다 (이전 범위의 늦은 응답은 버린다). 만든 토스트 id, 버리면 null */
  show(scope: string, variant: ToastVariant, message: string, options: ToastOptions = {}): number | null {
    if (scope !== this.scope || !message) return null;
    const same = options.dedupeKey
      ? [...this.visible.filter((e) => !e.leaving), ...this.queued].find((e) => e.dedupeKey === options.dedupeKey)
      : undefined;
    if (same) {
      // 같은 일 : 새로 만들지 않고 (보이는 중이면) 시간만 처음부터 다시 센다
      same.remaining = same.duration;
      if (same.runningSince !== null) this.run(same);
      return same.id;
    }
    const duration = options.duration ?? durationFor(variant, message, options.title);
    const entry: Entry = {
      id: ++nextId, variant, message, title: options.title, duration, dedupeKey: options.dedupeKey,
      leaving: false, remaining: duration, runningSince: null, timer: null, exitTimer: null, pauses: new Set(),
    };
    if (this.activeCount() < MAX_VISIBLE) this.reveal(entry);
    else this.enqueue(entry);
    this.emit();
    return entry.id;
  }

  /** 한 토스트 닫기 (보이는 것은 짧은 모션 뒤 빠지고, 기다리는 것은 바로 빠진다). 이미 닫는 중이면 아무것도 하지 않는다 */
  dismiss(id: number) {
    const waiting = this.queued.findIndex((e) => e.id === id);
    if (waiting >= 0) {
      this.queued.splice(waiting, 1);
      this.emit();
      return;
    }
    const entry = this.visible.find((e) => e.id === id);
    if (!entry || entry.leaving) return;
    this.stop(entry);
    entry.leaving = true;
    if (prefersReducedMotion()) this.remove(entry);
    else entry.exitTimer = window.setTimeout(() => this.remove(entry), EXIT_MS);
    // 빈자리는 바로 다음 토스트에 준다 (사라지는 것은 자리를 차지하지 않는다)
    this.promote();
    this.emit();
  }

  /** 모두 바로 지운다 (범위 변경·로그아웃·Provider 제거). 모션을 기다리지 않는다 */
  clear() {
    for (const entry of this.visible) this.stopAll(entry);
    this.visible = [];
    this.queued = [];
    this.announcements = [];
    this.emit();
  }

  pause(id: number, reason: PauseReason) {
    const entry = this.visible.find((e) => e.id === id);
    if (!entry || entry.leaving) return;
    entry.pauses.add(reason);
    this.stop(entry);
  }

  resume(id: number, reason: PauseReason) {
    const entry = this.visible.find((e) => e.id === id);
    if (!entry || entry.leaving) return;
    entry.pauses.delete(reason);
    this.run(entry);
  }

  /** 브라우저 탭이 가려지면 모든 토스트의 시간을 멈춘다 */
  setHidden(hidden: boolean) {
    if (hidden === this.hidden) return;
    this.hidden = hidden;
    for (const entry of this.visible) {
      if (entry.leaving) continue;
      if (hidden) this.stop(entry);
      else this.run(entry);
    }
  }

  /** 검증용 : 지금 걸려 있는 타이머·대기열 수 */
  stats() {
    const timers = this.visible.filter((e) => e.timer !== null || e.exitTimer !== null).length;
    return { visible: this.visible.length, queued: this.queued.length, timers };
  }

  private activeCount() {
    return this.visible.filter((e) => !e.leaving).length;
  }

  private enqueue(entry: Entry) {
    if (this.queued.length >= MAX_QUEUED) {
      const minor = this.queued.findIndex((e) => e.variant === 'success' || e.variant === 'info');
      this.queued.splice(minor >= 0 ? minor : 0, 1);
    }
    this.queued.push(entry);
  }

  private reveal(entry: Entry) {
    this.visible.push(entry);
    const text = entry.title ? `${entry.title}. ${entry.message}` : entry.message;
    this.announcements = [...this.announcements, { id: entry.id, text, assertive: entry.variant === 'error' }].slice(-MAX_ANNOUNCEMENTS);
    this.run(entry);
  }

  private promote() {
    while (this.queued.length && this.activeCount() < MAX_VISIBLE) this.reveal(this.queued.shift()!);
  }

  /** 멈출 이유가 없으면 남은 시간만큼 센다 */
  private run(entry: Entry) {
    this.stop(entry);
    if (this.hidden || entry.pauses.size > 0) return;
    entry.runningSince = Date.now();
    entry.timer = window.setTimeout(() => {
      entry.timer = null;
      entry.runningSince = null;
      this.dismiss(entry.id);
    }, Math.max(0, entry.remaining));
  }

  /** 세던 시간을 남은 시간에 반영하고 타이머를 멈춘다 */
  private stop(entry: Entry) {
    if (entry.timer !== null) window.clearTimeout(entry.timer);
    if (entry.runningSince !== null) entry.remaining -= Date.now() - entry.runningSince;
    entry.timer = null;
    entry.runningSince = null;
  }

  private stopAll(entry: Entry) {
    this.stop(entry);
    if (entry.exitTimer !== null) window.clearTimeout(entry.exitTimer);
    entry.exitTimer = null;
  }

  private remove(entry: Entry) {
    this.stopAll(entry);
    const before = this.visible.length;
    this.visible = this.visible.filter((e) => e !== entry);
    if (this.visible.length !== before) {
      this.promote();
      this.emit();
    }
  }

  private emit() {
    this.snapshot = {
      items: this.visible.map(({ id, variant, message, title, leaving }) => ({ id, variant, message, title, leaving })),
      announcements: this.announcements,
    };
    for (const listener of this.listeners) listener();
  }
}
