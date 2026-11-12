# M-0. ERD 기술 검토·라이브러리 선정 결과 (PoC)

> [데이터 모델링 로드맵](data-modeling-roadmap.md) M-0 의 검증 기록이다. 결론은 로드맵 10장 D-01·D-02 에 반영했다.
> 작성일 2026-11-10. 모든 수치는 아래 "측정 환경"에서 실제로 잰 값이며, 재지 못한 항목은 **미측정** 으로 적었다.

## 1. 요약

| 항목 | 결과 |
|---|---|
| 추천 조합 | **React Flow(`@xyflow/react`) + ELK.js(layered, Web Worker)** |
| 근거 | 1,000 테이블 이상에서 ELK 가 Dagre 보다 빠름(3,000: 3.5초 vs 15.0초), 컬럼 단위 포트·레이어 폭 제어·직교 배선 등 ERD 에 필요한 옵션 보유, 공식 워커로 UI 멈춤 없음 |
| 대안 | React Flow + Dagre (300 테이블 이하·번들 크기 우선일 때). 어댑터만 바꾸면 교체 가능하도록 분리해 둠 |
| 가장 큰 위험 | ① ELK 라이선스 EPL-2.0(배포 시 고지·소스 제공 의무) ② ELK 워커 번들 1.6 MB(gzip 462 KB) ③ 허브 테이블이 있는 실데이터에서 계층형 배치가 매우 길쭉해짐(전체 개요 가독성 낮음) |
| 대규모 전략 (D-02) | 3,000 테이블 전체 한 화면 표시는 렌더링은 가능하나(최적화 시 상세 조작 60fps) 개요 확대·축소가 12fps, 배치도 읽기 어려움 → **스키마·검색·이웃 단위 부분 로딩이 기본**, 전체는 단순 표시 개요로만 |
| 상태 | D-01 **권고**(기술 검증 완료, 라이선스 고지 방식 확인 후 확정), D-02 **권고**(M-2 실데이터로 재확인 필요) |

## 2. 기존 코드 분석

### 2.1 현재 React 구조

- `lexorma-web` : React 19.1 + Vite 6.3 + TypeScript 5.8, 라우팅 `react-router-dom` 7.6(`src/app/App.tsx`), 서버 상태 TanStack Query 5, 표 TanStack Table 8(`components/grid/Grid.tsx`).
- 화면은 `features/<영역>` 단위(auth, column, dashboard, dictionary, help, notice, system, table). 메뉴 노출은 서버 `MenuAuthorization` → `/api/auth/me` 의 `menus` → `RequireMenu` / `components/layout/menu.ts`.
- 스타일: `styles/primer.css` 의 `--gh-*` 토큰(라이트/다크 둘 다 정의, `<html data-theme>` 로 전환, `lib/theme.ts` 가 OS 설정 추적), Pretendard 폰트, `.gh-btn` / `.gh-label` 컴포넌트 클래스. 아이콘 `@primer/octicons-react`.
- 그래프·다이어그램 의존성은 없었다. 로드맵(3장) 설명과 실제 코드가 일치한다.

### 2.2 재사용 가능한 코드 (PoC 에서 실제로 재사용한 것 ✔)

| 대상 | 재사용 방식 |
|---|---|
| `--gh-*` 디자인 토큰, `primer.css`, `pretendard.css` ✔ | 노드·엣지·툴바 색을 토큰으로만 지정 → 다크 모드가 별도 코드 없이 동작. React Flow 의 `--xy-*` 변수를 토큰에 연결 |
| `lib/theme.ts`(`followSystemTheme`) ✔ | 그대로 호출. React Flow `colorMode` 는 `data-theme` 와 동기화 |
| `.gh-btn`, `.gh-label`, Octicons ✔ | 툴바·관계 유형 라벨 |
| `useDialog`(`DialogProvider`) | M-2 의 상세·확인 대화상자 (PoC 는 사이드 패널로 대체) |
| `lib/api.ts`, TanStack Query | M-2 의 ERD 일괄 조회 (PoC 는 Mock 데이터만 사용 → API 호출 없음) |
| `Grid` | ERD 와 직접 관계없음. 선택 테이블의 컬럼 목록은 기존 컬럼 관리 화면으로 이동하는 링크로 연결하는 편이 중복이 적다 |
| `RequireMenu`, `MENU_ROLES`/`MENU_GROUPS` | M-2 의 ERD 메뉴 |

### 2.3 기존 API 활용 가능성 (M-2)

| ERD 에 필요한 데이터 | 현재 | 판정 |
|---|---|---|
| 테이블 ID·이름 | `GET /api/selectTable/data` (VIEWER, 그리드 페이지 단위) | 페이지 그리드용이라 전체 그래프 조회에 부적합 → **신규 일괄 API** |
| 컬럼 ID·이름·타입·PK·NULL | `GET /api/selectColumn/tableColumn/{tableId}` (**USER**, 테이블 1개) | 테이블 수만큼 호출(N+1) + 역할이 USER → VIEWER 조회 불가. **신규 일괄 API** |
| FK 여부·관계 | `ColumnInfo.refTableName`(참조 테이블명 문자열)만 | 참조 컬럼 없음(DDL 은 참조 테이블 PK 로 추정 — `TableDdlGenerator` `refPkColumns`). 복합 FK·관계 유형 표현 불가 → **M-1 관계 엔티티 필요** |
| 스키마 | 없음 (`projectId` 아래 평면) | **M-1** |
| 레이아웃(좌표·줌) | 없음 | **M-2 신규 저장소** (D-10, D-15) |

- 신규 일괄 API 안: `GET /api/erd/graph?schema=&tableIds=&depth=` → `{tables:[{id, schema, name, columns:[{id,name,dataType,pk,nullable}]}], relations:[{id,name,kind,sourceTableId,targetTableId,columns:[{sourceColumnId,targetColumnId}]}]}` — PoC 의 `poc/erd/model.ts` 형식과 같다. M-1 이전 데이터는 서버가 `refTableName` → 참조 테이블 PK 쌍으로 변환해 `kind: FK` 로 내려주면 화면은 바뀌지 않는다.
- **프로젝트 격리:** 기존 패턴(`@AuthenticationPrincipal AccountDto` → `accountDto.getProjectId()` 로 필터, `@PreAuthorize("hasRole('VIEWER')")`, `ProjectAccessInterceptor` 의 `/api/**` 재검증)을 그대로 쓰면 된다. 새 메커니즘은 필요 없다. 레이아웃 저장 API 도 `projectId` 범위로 묶는다.

### 2.4 기존 시스템 영향 (이번 작업)

- 운영 코드(`src/`, 서버, Flyway, 메뉴) 변경 없음. PoC 는 `lexorma-web/poc/erd/` 별도 진입점(`vite.poc.config.ts`)으로 분리했고 결과물은 `build/erd-poc`(git·Docker 무시 대상)로 나간다.
- 운영 번들은 변경 전후 **해시까지 동일**(`dist/assets/index-xPmy_GB9.js`, 487.93 kB / gzip 146.54 kB) — 신규 라이브러리는 운영 화면에서 import 되지 않는다.
- 신규 의존성은 `devDependencies` 에 정확한 버전으로 고정(아래 6장). M-2 에서 운영 화면이 쓰게 되면 `dependencies` 로 옮긴다.

## 3. 후보 라이브러리 검토

확인 일자 2026-11-10, 출처: npm registry(`npm view`), GitHub API(`api.github.com/repos/...`), 설치된 패키지의 LICENSE 파일.
버전은 공개 후 2주 이상 지난 최신 안정판을 골랐다.

| 항목 | React Flow `@xyflow/react` | ELK.js `elkjs` | Dagre `@dagrejs/dagre` |
|---|---|---|---|
| 역할 | 렌더링·인터랙션 | 자동 레이아웃 | 자동 레이아웃 |
| 사용 버전 | 12.12.0 (2026-09-24) | 0.12.0 (2026-07-17) | 3.1.1 (2026-08-08) |
| 최신 | 12.12.1 (11-02), 13.0.0-next 진행 중 | 0.13.0 / 0.12.1 (10-28) | 3.1.3 (10-24) |
| 라이선스 | MIT (하위: zustand MIT, classcat MIT, d3-* ISC) | **EPL-2.0 OR GPL-3.0-or-later** | MIT (하위: `@dagrejs/graphlib` MIT) |
| 유지보수 | GitHub ★38.9k, 마지막 커밋 2026-11-02, 미해결 이슈 139 | ★2.9k, 마지막 커밋 2026-11-09 (Eclipse ELK/Kiel 대학 KIELER 팀), 미해결 97 | ★5.9k, 마지막 커밋 2026-10-24, 미해결 172 (2022 년 `dagre` 0.8.5 이후 `@dagrejs` 로 재개) |
| 번들 (min / gzip) | 185.6 KB / 61.5 KB + CSS 3 KB(gzip) | `elk-api` 5.3 KB / 2.1 KB + 워커 `elk-worker.min.js` 1,595 KB / **461.6 KB** (레이아웃 실행 시에만 로드) | 48.3 KB / 16.8 KB |

번들 크기는 esbuild(minify, react 외부화)로 각 라이브러리만 묶어 잰 값이다(PoC 실제 빌드의 청크 크기와 일치: `dagre.esm` 48.4 KB, `elk-worker.min` 1,595 KB).

### 3.1 React Flow — 검증 결과 (모든 조합 공통)

| 검토 항목 | 결과 |
|---|---|
| 커스텀 테이블 노드 | ✔ `nodeTypes.table` → `TableNode` (헤더 + 컬럼 행: PK/FK/PF, 이름, 타입, NULL/NN) |
| 컬럼별 Handle | ✔ 관계에 쓰이는 컬럼에만 좌·우 Handle 2개(`{columnId}:L/R`). `ConnectionMode.Loose` 로 source Handle 을 target 으로도 사용 |
| 컬럼 단위 관계선 | ✔ 컬럼 쌍마다 엣지 1개. 자동 점검(`edgeCheck`: Handle 존재 + 위치 = 해당 컬럼 행 중앙 ±1.5px) 300 / 1,000 / 3,000 에서 398/398, 1,429/1,429, 4,067/4,067 통과 |
| 자기 참조 | ✔ 커스텀 엣지가 오른쪽 → 오른쪽 고리로 그림 |
| 순환 | ✔ A→B→C→A 표시 (렌더링은 방향 제약 없음, 배치는 엔진이 순환 제거 후 계층화) |
| 복합 관계 | ✔ 컬럼 쌍별 엣지 + 굵은 선, 상세 패널에 "복합 n컬럼" |
| 관계 유형 구분 | ✔ CSS 만으로: 실제 FK(파랑), 모델(회색), AI 추정(노랑 점선), 승인(초록). 저장 모델은 미구현(범위 외) |
| 이동·확대·축소·화면 이동·전체 맞춤 | ✔ 기본 제공(`Controls`, `fitView`, `setCenter`) |
| 선택 이벤트 | ✔ `onSelectionChange` → 상세 패널, 연결 관계선 강조 / "선택 관계만" 표시 |
| 다크 모드 | ✔ `colorMode` + `--gh-*` 토큰. 스크린샷으로 확인 |
| 대규모 | `onlyRenderVisibleElements`(화면 밖 생략) + 배율 기준 단순 표시로 DOM 220,103 → 11,585 (3,000, 개요). 4장 참조 |
| 주의 | 무료(MIT) 사용 시 우측 하단 "React Flow" 표기 유지가 권장(제거는 Pro 구독 권장, 법적 의무는 아님). 13.0 이 next 단계 → M-2 착수 시 메이저 변경 확인 |

### 3.2 ELK.js

| 검토 항목 | 결과 |
|---|---|
| 계층형 레이아웃 / 직교 배선 | ✔ `layered` + `ORTHOGONAL` (PoC 는 좌표만 쓰고 선은 React Flow smoothstep 로 직교 표시. ELK 의 굴곡점(bend point) 경로를 엣지에 쓰는 것은 미구현 — M-2 검토) |
| 컬럼 단위 포트 | ✔ `portConstraints: FIXED_POS` 로 컬럼 행 위치에 포트 지정(`elk-ports`). Dagre 에는 없는 기능 |
| 순환·복잡 관계 | ✔ 순환 제거(cycle breaking) 후 배치, 3,000 테이블·4,067 관계 처리 |
| 레이어 폭 제어 | ✔ `MIN_WIDTH`/`COFFMAN_GRAHAM` 레이어링으로 길쭉한 배치 완화 가능(1,000: 종횡비 0.14 → 0.88) 대신 7~11 초로 느려짐 (Node 측정) |
| 성능 | 300: 0.3초, 1,000: 0.75초, 3,000: 3.5초 (4장) |
| Web Worker | ✔ 공식 워커(`elk-api` + `elk-worker.min.js?url`). 레이아웃 중 프레임 16.7ms 유지. ⚠ `elk.bundled.js` 를 **자체 워커 안에서** import 하면 워커 본체로 자신을 등록해 생성자가 사라진다(`TypeError: … is not a constructor`) — 실제로 겪고 공식 방식으로 바꿈 |
| 단점 | 번들 큼(gzip 462 KB, 지연 로드), GWT 로 변환된 코드라 디버깅 어려움, EPL-2.0 |

### 3.3 Dagre

| 검토 항목 | 결과 |
|---|---|
| 계층형 레이아웃 / React Flow 연동 | ✔ 구현이 가장 단순(그래프 생성 → `layout` → 중심 좌표를 좌상단으로 변환) |
| 성능 | 300: 0.13초(ELK 보다 빠름), 1,000: 0.86초, **3,000: 15.0초** (규모가 커지면 급격히 느려짐) |
| 컬럼 포트 / 레이어 폭 제어 | ✘ 노드 단위만, rankdir·간격 정도 |
| Web Worker | ✔ 자체 모듈 워커로 문제 없이 동작 |
| 대규모 한계 | 3,000 에서 15초 → 워커라 UI 는 멈추지 않지만 사용자가 기다리기 어렵다. 배치도 가장 길쭉함(12,480 × 355,670) |

### 3.4 기타 조합 (검토만)

- **ELK 의 다른 알고리즘(stress, force, rectpacking)**: 허브형 그래프의 길쭉한 배치를 줄일 수 있으나 ERD 의 방향성(참조 → 피참조) 표현이 약해짐. M-2 에서 "개요" 전용 옵션으로 검토.
- **Cytoscape.js / JointJS / GoJS** 등: 캔버스·SVG 렌더러라 테이블·컬럼 행을 React 컴포넌트·기존 CSS 로 그리기 어렵고, GoJS·JointJS+ 는 상용 라이선스. 기존 React·Primer 재사용 원칙에 맞지 않아 PoC 대상에서 제외.

## 4. PoC 성능 측정

### 4.1 측정 환경

| 항목 | 값 |
|---|---|
| 브라우저 | Chrome 157.0.8103.50, **headless**(new headless, puppeteer-core 25.14.0 로 자동 조작), GPU: ANGLE Metal (Apple M1) |
| OS / CPU / 메모리 | macOS 27.0 / Apple M1 (8코어) / 16 GB |
| 뷰포트 | 1600 × 1000 |
| 빌드 | `npm run poc:erd:build` 프로덕션 빌드를 `vite preview` 로 서빙 |
| 데이터 | `poc/erd/mockData.ts` (시드 고정, 규모별 같은 데이터) |

| 규모 | 컬럼 수 (평균) | 관계 | 컬럼 단위 관계선 | 복합 FK | 자기 참조 | 관계 없는 테이블 | 최대 피참조 수 |
|---|---|---|---|---|---|---|---|
| 300 | 3,565 (11.9) | 378 | 398 | 20 | 10 | 68 | 47 |
| 1,000 | 12,020 (12.0) | 1,314 | 1,429 | 115 | 33 | 172 | 54 |
| 3,000 | 35,381 (11.8) | 3,764 | 4,067 | 303 | 100 | 578 | 55 |

순환(3개 테이블 고리)은 100개마다 1개, 허브(전체 1%)가 FK 의 약 30% 를 받는다.

**측정 방법**

- 레이아웃: `runLayout` 의 엔진 호출 시간(워커: 워커 시작·데이터 복사 포함). 같은 계산을 워커 없이 메인 스레드에서도 실행해 Long Task(50ms 이상 메인 스레드 점유) 길이로 UI 멈춤을 쟀다. 각 조합 1회(아래 Node 순수 계산은 3회 중앙값).
- 초기 렌더링: 이전 그래프를 비운 뒤 `setNodes/setEdges` → 노드·엣지 DOM 수가 3프레임 연속 변하지 않을 때까지(엣지는 노드 크기 측정 다음 프레임에 그려진다). zoom 1, 좌상단 뷰포트. 위치는 ELK layered 결과로 고정(모드 간 동일 조건).
- 인터랙션: requestAnimationFrame 간격으로 FPS·p95 프레임 시간 측정, 2초씩. 개요 줌 = 전체 맞춤 상태에서 3배 확대 후 복귀, 상세 줌 = zoom 1 → 0.25 → 1(단순 표시 기준 0.45 통과), 이동 = zoom 1 에서 대각선 2,700px, 드래그 = 관계가 가장 많은 허브 테이블을 실제 마우스 이벤트로 60단계 이동. 줌·이동은 `setViewport` 로 프로그램 실행(사용자 휠 입력과 같은 변환 경로).
- 메모리: GC 강제 후 `performance.memory.usedJSHeapSize`(`--enable-precise-memory-info`), DOM 요소 수 = `document.getElementsByTagName('*').length`. 전체 맞춤 상태에서 측정.
- 모드: **기본**(모든 노드·컬럼 렌더링) / **최적화**(화면 밖 생략 + zoom 0.45 미만 단순 표시) / **최적화+키 컬럼만**.

### 4.2 자동 레이아웃 (ms)

| 테이블 수 | 조합 | 워커 실행 | 레이아웃 중 최장 프레임 (워커) | 메인 스레드 실행 시 Long Task | 순수 계산 (Node, 중앙값) | 결과 크기 (W × H px) |
|---|---|---|---|---|---|---|
| 300 | React Flow + ELK layered | 299 | 16.8 | 272 | 207 | 6,452 × 38,363 |
| 300 | React Flow + ELK + 컬럼 포트 | 299 | 16.7 | 268 | 226 | 5,420 × 35,372 |
| 300 | React Flow + Dagre | 126 | 16.7 | 141 | 126 | 4,320 × 44,589 |
| 1,000 | React Flow + ELK layered | 747 | 16.7 | 718 | 654 | 16,292 × 122,842 |
| 1,000 | React Flow + ELK + 컬럼 포트 | 976 | 16.8 | 1,047 | 953 | 12,263 × 120,105 |
| 1,000 | React Flow + Dagre | 858 | 16.7 | 1,021 | 1,735 | 7,040 × 134,566 |
| 3,000 | React Flow + ELK layered | 3,461 | 16.8 | 3,392 | 4,341 | 44,982 × 298,179 |
| 3,000 | React Flow + ELK + 컬럼 포트 | 4,024 | 16.8 | 3,992 | 5,225 | 32,533 × 336,200 |
| 3,000 | React Flow + Dagre | 15,024 | 16.8 | 15,084 | 18,114 | 12,480 × 355,670 |

- 워커 실행 시 모든 조합에서 레이아웃 중 프레임이 16.7ms(60fps)로 유지 → **UI 멈춤 없음**. 메인 스레드에서 돌리면 계산 시간만큼 화면이 완전히 멈춘다(3,000: ELK 3.4초, Dagre 15.1초).
- 순수 계산(Node 24, 관계 있는 테이블만: 230 / 828 / 2,409개)은 브라우저 값과 절대치가 다르지만(1,000 Dagre 1.7초 vs 브라우저 0.86초) 순위는 같다: 1,000 이상에서 ELK < Dagre, 3,000 에서 Dagre 가 ELK 의 4배 이상.
- Dagre 메인 스레드 실행은 프레임 측정기에 긴 프레임이 잡히지 않아(계산이 측정기 첫 프레임 전에 끝남) Long Task 값으로만 판단했다.
- 모든 배치가 세로로 매우 길다(종횡비 0.04~0.17). 허브 테이블 때문에 계층 수는 적고 계층 당 노드가 많아서다. `fitView` 최소 배율 0.02 에서도 1,000 이상은 한 화면에 다 들어오지 않는다 → 전체 개요는 실용성이 낮다(D-02 근거).

### 4.3 렌더링·인터랙션·메모리 (위치: ELK layered)

| 테이블 수 | 모드 | 초기 렌더링 | 전체 맞춤 후 렌더링 | JS 힙 (전체 맞춤) | DOM 요소 | 개요 줌 FPS (p95 ms) | 상세 줌 FPS (p95) | 이동 FPS (p95) | 드래그 FPS (p95) |
|---|---|---|---|---|---|---|---|---|---|
| 300 | 기본 | 140 ms | 35 ms | 18.2 MB | 22,171 | 56 (33.3) | 61 (16.7) | 61 (16.7) | 61 (16.8) |
| 300 | 최적화 | 96 ms | 62 ms | 16.0 MB | 4,346 | 60 (16.8) | 61 (16.7) | 61 (16.7) | 61 (16.8) |
| 300 | 최적화+키 컬럼 | 41 ms | 63 ms | 16.0 MB | 4,346 | 60 (16.8) | 61 (16.8) | 61 (16.8) | 61 (16.7) |
| 1,000 | 기본 | 532 ms | 71 ms | 53.1 MB | 75,062 | 25 (66.6) | 51 (33.4) | 61 (16.7) | 60 (16.8) |
| 1,000 | 최적화 | 382 ms | 110 ms | 28.1 MB | 7,909 | 39 (33.4) | 61 (16.7) | 61 (16.7) | 61 (16.7) |
| 1,000 | 최적화+키 컬럼 | 145 ms | 200 ms | 45.4 MB | 14,962 | 49 (33.4) | 61 (16.8) | 61 (16.7) | 61 (16.7) |
| 3,000 | 기본 | 1,996 ms | 190 ms | 145.3 MB | 220,103 | **8** (400) | 17 (83.3) | 20 (66.7) | 29 (83.3) |
| 3,000 | 최적화 | 1,146 ms | 146 ms | 43.1 MB | 11,585 | 13 (116.7) | 55 (33.3) | 61 (16.7) | 61 (16.7) |
| 3,000 | 최적화+키 컬럼 | 475 ms | 305 ms | 75.9 MB | 23,880 | 23 (83.3) | 61 (16.7) | 61 (16.7) | 61 (16.7) |

- 같은 측정을 두 번 실행해 값이 일관됨을 확인했다(1차·2차 차이 대부분 ±10% 이내, 3,000 기본 개요 줌 p95 는 216 → 400ms 로 변동 큼).
- **최적화 효과(3,000):** DOM 95% 감소, JS 힙 70% 감소, 상세 화면 이동·드래그 20fps → 60fps. 단, 전체가 화면에 있는 개요 줌은 4,067 개 SVG 경로 때문에 13fps 에 머문다.
- "키 컬럼만"은 노드가 작아져 전체 맞춤 배율이 단순 표시 기준(0.45)을 넘는 경우가 생겨(300: 0.07) 오히려 메모리가 늘 수 있다. 단순 표시 기준은 배율이 아니라 "화면 위 노드 크기" 기준이 더 적합하다(M-2 개선점).
- 초기 렌더링 시간의 대부분은 화면 밖 노드도 React Flow 내부 저장소에 등록·측정하는 비용이다(화면 밖 생략을 켜도 3,000 에서 1.1초).

### 4.4 번들 크기 증가량

| 항목 | min | gzip | 비고 |
|---|---|---|---|
| React Flow (+zustand, d3-zoom 등) | 185.6 KB | 61.5 KB | ERD 화면 지연 로드(라우트 단위 `lazy`) 권장 |
| React Flow CSS | — | 3.0 KB | |
| ELK `elk-api` (메인) | 5.3 KB | 2.1 KB | |
| ELK 워커 `elk-worker.min.js` | 1,595 KB | 461.6 KB | 자동 레이아웃을 처음 실행할 때만 워커가 내려받음, `/assets` 1년 캐시 |
| Dagre (+graphlib) | 48.3 KB | 16.8 KB | |
| 현재 운영 번들 (비교용) | 487.9 KB | 146.5 KB | 이번 작업으로 변하지 않음 |

### 4.5 미측정·한계

| 항목 | 사유 |
|---|---|
| 실제 사용자 휠·트랙패드 줌 체감 | 자동화 측정은 `setViewport` 프로그램 실행. 수동 체감 확인은 **미측정** |
| headed(일반 창) Chrome, Safari, Firefox | headless Chrome 만 측정. 다른 브라우저 **미측정** |
| 저사양 PC (내장 그래픽, 8GB 이하) | **미측정** — M-2 에서 사내 표준 PC 로 확인 필요 |
| 실데이터(현재 DB 의 테이블·컬럼) | PoC 는 Mock 데이터만 사용 (범위). 실제 관계 밀도·허브 분포는 M-2 에서 확인 |
| ELK 굴곡점 경로 사용 시 성능 | 구현하지 않음 — **미측정** |
| 레이아웃 반복 측정의 분산 | 브라우저 측정은 조합당 1회(2회 실행한 렌더링 제외) |

## 5. 기능 검증 결과

자동 점검 스크립트(puppeteer) + 스크린샷으로 확인.

| 검증 | 결과 | 근거 |
|---|---|---|
| 테이블 노드·컬럼·타입·PK/FK/NULL 표시 | ✔ | 스크린샷 |
| 관계선이 올바른 컬럼에 연결 | ✔ | `edgeCheck` 전 규모 100% |
| 자기 참조 / 순환 / 복합 관계 | ✔ | t11 고리, t20→t21→t22→t20, `erp_tbl_0020 → crm_tbl_0031`(2컬럼) 스크린샷 |
| 노드 이동 | ✔ | 실제 마우스 드래그, 이동 후 관계선 방향(좌/우) 재계산 |
| 확대·축소·이동·전체 맞춤 | ✔ | 측정 시나리오 |
| 자동 레이아웃 (3종) | ✔ | 전 규모 |
| 테이블 검색·포커스 | ✔ | 검색 → `fitView(nodes)`, 현재 부분 화면에 없으면 해당 테이블 + 이웃만 불러옴 |
| 레이아웃 추출·복원 (X·Y·Zoom·Viewport) | ✔ | 5개 노드 이동·뷰포트 변경 → 저장 → 자동 레이아웃으로 흐트러뜨림 → 복원: 300개 중 불일치 0, 뷰포트 일치. 새로고침 후 `localStorage` 에서 복원도 일치 |
| 다크 모드 | ✔ | 스크린샷 (토큰만 사용) |
| 콘솔 오류 | 없음 | 정적 서버의 favicon 404 1건만 (PoC 페이지에 아이콘 없음) |

## 6. 최종 추천과 M-2 적용 구조

### 6.1 추천: React Flow + ELK.js (layered, 공식 Web Worker)

- **선정 이유:** ① 대규모(1,000~3,000)에서 Dagre 보다 빠르고(3,000 에서 4.3배), ② 컬럼 포트·레이어 폭 제어·굴곡점 등 ERD 품질을 높일 수단이 엔진 안에 있으며, ③ 공식 워커로 UI 를 막지 않는다. React Flow 는 테이블·컬럼을 React 컴포넌트와 기존 Primer CSS 로 그대로 그릴 수 있는 유일한 후보였다.
- **Dagre 대비 단점:** 번들(+462 KB gzip, 지연 로드), 300 이하에서는 Dagre 가 2배 빠름(0.13 vs 0.3초, 체감 차이는 작음), EPL-2.0.
- **기본값 권고:** `elk`(노드 단위)로 시작하고, 컬럼 포트(`elk-ports`)는 선 교차가 적은 대신 1,000 에서 30% 느려 "정밀 정렬" 옵션으로 둔다.
- **라이선스 검토:** React Flow·Dagre 는 MIT 로 Lexorma(MIT)와 충돌 없음. ELK.js 는 EPL-2.0(또는 GPL-3.0+) 중 EPL-2.0 을 택해 쓰는 것으로 보인다. EPL-2.0 은 파일 단위 약한 카피레프트라 **ELK 를 수정하지 않고 별도 파일로 배포하면 Lexorma 코드에는 영향이 없다**고 판단되나, jar·Docker 이미지 배포 시 ① EPL 사본 동봉 ② ELK 소스 입수 방법 고지 ③ 원 저작권 표시 유지가 필요하다(서드파티 고지 파일 추가, `elk-worker.min.js` 의 라이선스 주석이 빌드 후에도 남는지 확인). **법적 확인은 프로젝트 관리자 결정 사항**으로 남긴다.

### 6.2 대규모 ERD 전략 (D-02 권고)

1. **부분 로딩이 기본:** 스키마 단위, 검색 결과, 선택 테이블 + N 단계 이웃. 서버 API 도 이 단위로 자른다. (PoC 에 스키마·이웃 보기 구현, 300 → 50 테이블 수준으로 줄면 레이아웃 0.1초 이하)
2. **화면 밖 생략**(`onlyRenderVisibleElements`) + **배율 기준 단순 표시**(컬럼 행 제거, 크기·Handle 위치 유지) 항상 켬.
3. **레이아웃은 항상 워커**, 새 요청 시 이전 계산 취소(PoC `runLayout` 구현). 1,000 이상 전체 배치는 진행 표시 후 결과를 저장해 재사용(M-2 레이아웃 저장).
4. **관계선 줄이기:** 선택 테이블 관계만 표시, 관계 유형 필터(AI 추정 기본 숨김 — D-14).
5. **컬럼 접기:** "키 컬럼만" 옵션(단, 단순 표시 기준을 화면 크기 기준으로 바꿔야 효과가 있음).
6. 3,000 테이블 전체를 상세 노드로 한 화면에 그리는 것은 목표로 하지 않는다(개요 줌 13fps, 배치 가독성 낮음).

### 6.3 M-2 컴포넌트 구조 제안

```text
features/erd/
├─ ErdPage.tsx              라우트(RequireMenu "erd"), 쿼리(useErdGraph), 레이아웃 저장/복원
├─ canvas/
│  ├─ ErdCanvas.tsx         ReactFlow 래퍼: nodeTypes/edgeTypes, 화면 밖 생략, 선택·드래그 이벤트
│  ├─ TableNode.tsx         헤더 + ColumnRow 목록 + 컬럼 Handle, 배율 기준 단순 표시
│  ├─ ColumnRow.tsx         PK/FK/타입/NULL (물리) · 속성/도메인 (논리) — 표시 필드만 props 로
│  ├─ RelationEdge.tsx      유형별 스타일, 자기 참조 고리, (선택) ELK 굴곡점
│  └─ erd.css               --gh-* 토큰만 사용
├─ ErdToolbar.tsx           자동 레이아웃·전체 맞춤·저장·보기 범위
├─ ErdSearch.tsx            검색 → 포커스 / 이웃 불러오기
├─ ErdDetailPanel.tsx       선택 테이블 요약 + 기존 테이블·컬럼 관리 화면으로 이동 (편집 화면 중복 구현 안 함)
├─ layout/
│  ├─ engine.ts             LayoutEngine 어댑터 (입력: 노드 크기·포트·간선 / 출력: id → x,y)
│  ├─ algorithms.ts         elkLayout / dagreLayout / 공통 전·후처리
│  └─ dagre.worker.ts       (Dagre 를 남길 경우만)
├─ model.ts                 ErdModel(tables, relations{kind, columns[]}) / ErdLayoutState
└─ graph.ts                 ErdModel → React Flow 노드·엣지 변환, 이웃 계산
```

- **엔진 비종속:** 화면은 `LayoutRequest/LayoutResult` 만 알고, 저장 형식도 `노드 id → x,y + viewport` 라 엔진을 바꿔도 저장된 배치·컴포넌트가 그대로다(PoC 에서 엔진 3종을 같은 화면으로 교체해 확인).
- **물리·논리 ERD 공용:** `ErdModel` 은 "테이블 = 노드, 컬럼 = 행, 관계 = 컬럼 쌍 목록"만 가정한다. 논리 ERD(M-6)는 엔터티·속성을 같은 형태로 변환해 `ErdCanvas` 를 재사용하고, `ColumnRow` 의 표시 필드만 바꾼다.
- **상태:** 서버 데이터는 TanStack Query, 노드 위치는 React Flow 상태, 저장은 별도 API(모델과 분리, M-2 / D-10 / D-15). 전역 상태 라이브러리는 추가하지 않는다(React Flow 내부 zustand 만).
- **메뉴·권한:** `MENU_ROLES`/`MENU_GROUPS` 에 ERD 추가, 조회 API `VIEWER`, 레이아웃 저장 역할은 D-10 결정에 따름.

## 7. 다음 작업 제안

**M-1 진행 전 결정 필요**
- D-03 스키마 위치, D-04 `refTableName` 이전 — ERD 일괄 API 응답 형식(`relations[].columns[]`, `schema`)이 이 결정을 전제로 한다.
- 관계 유형(`kind`)·출처 컬럼을 M-1 관계 엔티티에 처음부터 넣을지 (PoC 는 4종 표시 가능 확인).
- ELK 라이선스(EPL-2.0) 수용 여부와 서드파티 고지 방식.

**M-2 구현 시 고려**
- ERD 일괄 조회 API 신규(기존 컬럼 API 는 USER 역할·테이블 단위라 재사용 불가), 스키마·이웃 단위 페이징.
- ERD 라우트를 `React.lazy` 로 분리해 운영 첫 화면 번들 증가 0 유지.
- 단순 표시 기준을 "화면 위 노드 크기"로 변경, 전체 레이아웃 결과 저장·재사용, 새 테이블만 증분 배치(ELK `interactive` 옵션 검토).
- 허브 테이블 표시 방식(관계선 묶기, 허브 제외 배치 후 별도 배치) — 실데이터로 확인.
- `@xyflow/react` 13 정식 출시 여부 확인 후 버전 결정, 저사양 PC·Safari 측정.

## 부록. PoC 실행 방법

```bash
cd lexorma-web
npm run poc:erd           # 개발 서버 http://localhost:5174 (API·로그인 불필요, Mock 데이터)
npm run poc:erd:build     # build/erd-poc (jar·Docker 에 포함되지 않음)
npm run poc:erd:preview   # 빌드 결과 서빙
```

브라우저 콘솔의 `window.__erdPoc`(run, zoomOverview, zoomCross, pan, dragTarget, edgeCheck, layoutRoundTrip …)로 측정 시나리오를 재현할 수 있다.
