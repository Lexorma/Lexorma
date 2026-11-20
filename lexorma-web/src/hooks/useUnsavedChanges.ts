import { useCallback, useEffect, useRef } from 'react';
import { useBlocker, type BlockerFunction } from 'react-router-dom';
import { useDialog } from '../components/dialog/DialogProvider';

/**
 * 저장하지 않은 변경 보호 (M-2 개선 2차 D-19). 지금은 물리 ERD·논리 ERD 의 배치가 쓰고, 다른 편집 화면도 같은 방식으로 붙일 수 있다.
 * - 사이드바·화면 안 링크·브라우저 뒤로/앞으로 : 라우터가 이동을 멈추고(useBlocker) 공통 확인 대화상자를 한 번 띄운다. 취소하면 그 화면과 변경이 그대로 남는다
 * - 새로 고침·창 닫기 : 브라우저 기본 경고 (beforeunload)
 * - 화면 안에서 먼저 묻는 이동(다른 모델 고르기·다른 화면 버튼) : confirmLeave 로 한 번만 묻고, 이미 확인했으므로 라우터는 다시 묻지 않는다
 * - 로그아웃 : 라우터 밖에서 시작하므로 hasUnsavedChanges 로 먼저 묻는다 (Header)
 * - 세션 만료·권한 회수로 로그인 화면에 보내는 이동은 막지 않는다 (navigateWithoutGuard)
 * 자동 저장·재시도는 하지 않는다. 저장이 실패(409 포함)하면 화면이 dirty 를 그대로 두므로 보호도 그대로다.
 */

/** 저장하지 않은 변경이 있는 화면 수 (같은 때 보통 하나) */
let dirtyScreens = 0;
/** 확인을 이미 받았거나 막으면 안 되는 이동 동안만 true (navigate 는 막을지를 동기로 판단한다) */
let bypass = false;

/** 확인 없이 이동한다 : 이미 확인을 받은 이동, 세션 만료·권한 회수에 따른 강제 이동 */
export function navigateWithoutGuard(go: () => void) {
  bypass = true;
  try {
    go();
  } finally {
    bypass = false;
  }
}

/** 지금 화면에 저장하지 않은 변경이 있는지 (로그아웃 확인용) */
export const hasUnsavedChanges = () => dirtyScreens > 0;

/**
 * dirty 인 동안 이 화면을 떠나는 이동을 확인한다. 주소의 경로나 쿼리가 바뀌는 이동만 묻는다 (같은 주소로의 이동·state 만 바꾸는 이동은 묻지 않는다).
 * 돌려주는 confirmLeave(go) : 화면 안에서 시작하는 이동 — dirty 면 한 번 묻고 확인하면 go 를 실행한다
 */
export function useUnsavedChangesGuard(dirty: boolean, message: string) {
  const dialog = useDialog();
  const shouldBlock = useCallback<BlockerFunction>(({ currentLocation, nextLocation }) =>
    dirty && !bypass && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search), [dirty]);
  const blocker = useBlocker(shouldBlock);

  useEffect(() => {
    if (!dirty) return;
    dirtyScreens++;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => {
      dirtyScreens--;
      window.removeEventListener('beforeunload', warn);
    };
  }, [dirty]);

  // 멈춘 이동마다 한 번만 묻는다 (확인 → 이어서 이동, 취소·닫기 → 그대로 머문다)
  const asking = useRef(false);
  useEffect(() => {
    if (blocker.state !== 'blocked' || asking.current) return;
    asking.current = true;
    dialog.confirm(message, () => {
      asking.current = false;
      blocker.proceed();
    }, () => {
      asking.current = false;
      blocker.reset();
    });
  }, [blocker, dialog, message]);

  return useCallback((go: () => void) => {
    if (!dirty) return go();
    dialog.confirm(message, () => navigateWithoutGuard(go));
  }, [dirty, dialog, message]);
}
