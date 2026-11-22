/**
 * 같은 테이블 안 컬럼명 비교 값 (M7-U01·U06 B3, 서버 ColumnNameKey · DB UX_COL_01 과 같은 기준) : 앞뒤 공백(U+0020)을 지우고 로캘 중립 확장 대문자로.
 * 화면의 중복 표시·건너뛰기에만 쓰는 보조 검사다 — 최종 판정은 서버·DB 가 한다. 저장하는 컬럼명은 바꾸지 않는다.
 *
 * 서버는 String.toUpperCase(Locale.ROOT) 라 글자가 늘 수 있다 (ß → SS, ﬁ → FI). JS toUpperCase 도 로캘과 무관한 같은 확장 대문자지만
 * 브라우저의 Unicode 판이 서버(Java 21 = Unicode 15.0)보다 새로우면 서버가 모르는 대소문자 짝(ƛ → Ƛ 등, Unicode 16.0 이후)을 화면만 같은 이름으로 보아
 * 서버가 허용하는 이름을 막을 수 있다 (Chromium 151 실측 55글자 — docs/completed/column-name-uniqueness-m7.md 14장).
 * 그래서 오래전에 채워진 블록(STABLE)의 글자이고 그 대문자도 모두 STABLE 일 때만 바꾸고, 아니면 그 글자를 그대로 둔다 —
 * 새 대소문자 짝은 새 코드 포인트(이 블록 밖)에 생기므로 서버보다 덜 잡을 수는 있어도 더 잡지 않는다.
 */
const STABLE: ReadonlyArray<readonly [number, number]> = [
  [0x0000, 0x024f], // Basic Latin ~ Latin Extended-B
  [0x02b0, 0x036f], // Spacing Modifier Letters, Combining Diacritical Marks (ŉ → ʼN, ǰ → J̌ 의 결과)
  [0x0370, 0x03ff], // Greek and Coptic
  [0x0400, 0x04ff], // Cyrillic
  [0x0530, 0x058f], // Armenian
  [0x1e00, 0x1fff], // Latin Extended Additional, Greek Extended
  [0xfb00, 0xfb17], // Latin · Armenian 합자 (ﬁ → FI)
  [0xff00, 0xffef], // 전각 (ａ → Ａ)
];

const stable = (cp: number) => STABLE.some(([from, to]) => cp >= from && cp <= to);

export function columnNameKey(name: string | null | undefined): string {
  return Array.from((name ?? '').replace(/^ +| +$/g, ''))
    .map((c) => {
      const upper = c.toUpperCase();
      if (upper === c) return c;
      return stable(c.codePointAt(0)!) && Array.from(upper).every((u) => stable(u.codePointAt(0)!)) ? upper : c;
    })
    .join('');
}
