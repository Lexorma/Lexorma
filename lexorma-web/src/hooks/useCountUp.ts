import { useEffect, useRef, useState } from 'react';
import { prefersReducedMotion } from '../lib/motion';

/**
 * 숫자 세기 (UI-1, docs/reference/ui-motion-guide.md) : 처음 보일 때만 0 → 값까지 requestAnimationFrame 으로 (durationMs 안, 끝은 정확히 값).
 * - 다시 그리기·테마·언어·창 크기·같은 값 재조회에는 다시 세지 않는다. 값이 바뀌면 세지 않고 바로 새 값 + changed 증가(강조용).
 * - 줄인 모션이면 처음부터 값. 세는 도중 컴포넌트가 사라지거나 값이 바뀌면 남은 프레임을 정리한다.
 * - 화면에는 shown, 보조 기술·업무 값에는 늘 value 를 쓴다 (중간 값은 장식).
 */
export function useCountUp(value: number, durationMs = 500) {
  const [shown, setShown] = useState(() => (prefersReducedMotion() ? value : 0));
  const [changed, setChanged] = useState(0);
  const counted = useRef(false); // 처음 세기를 끝냈거나 건너뛰었는지
  const last = useRef(value);

  useEffect(() => {
    if (counted.current) {
      if (value !== last.current) {
        last.current = value;
        setShown(value);
        setChanged((n) => n + 1);
      }
      return;
    }
    last.current = value;
    if (prefersReducedMotion() || value === 0) {
      counted.current = true;
      setShown(value);
      return;
    }
    let frame = 0;
    const start = performance.now();
    const tick = (now: number) => {
      const progress = Math.min(1, (now - start) / durationMs);
      const eased = 1 - (1 - progress) ** 3;
      if (progress < 1) {
        setShown(Math.round(value * eased));
        frame = requestAnimationFrame(tick);
      } else {
        counted.current = true;
        setShown(value);
      }
    };
    frame = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(frame);
      // 세는 도중 정리된 경우(값 변경·사라짐·개발 모드의 effect 두 번 실행) : 다음 실행에서 값으로 맞춘다
      if (!counted.current) {
        counted.current = true;
        setShown(value);
      }
    };
  }, [value, durationMs]);

  return { shown, changed };
}
