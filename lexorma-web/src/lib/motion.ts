/**
 * 자바스크립트로 움직이는 모션의 공통 규칙 (UI-1, docs/reference/ui-motion-guide.md).
 * CSS 모션은 styles/motion.css 의 토큰을 쓰고, JS 모션(숫자 세기·ERD 화면 이동·대화상자 닫기 기다림)은 여기서 줄인 모션을 확인한다.
 * 실행할 때마다 다시 읽으므로 사용 중에 운영체제 설정을 바꿔도 다음 동작부터 따른다.
 */
export function prefersReducedMotion() {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}

/** 모션 시간 : 줄인 모션이면 0 (React Flow 의 duration 처럼 0 이면 바로 최종 상태) */
export function motionMs(ms: number) {
  return prefersReducedMotion() ? 0 : ms;
}
