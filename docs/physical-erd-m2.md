# M-2. 물리 ERD 조회 및 레이아웃 저장 — 설계 결정과 구현 기록

> [데이터 모델링 로드맵](data-modeling-roadmap.md) M-2 의 설계·구현 기록이다. 결정 요약은 로드맵 10장(D-01, D-02, D-05, D-10, D-15)에 반영했다.
> 작성일 2026-11-10. 테이블 `TB_ERD_LYT` (Flyway `V0__init.sql`, 이름 규칙은 [DB 명명 규칙](db-naming-standard.md)).

## 1. 범위

- 구현 : 물리 모델 일괄 조회 API, 물리 ERD 화면(데이터 관리 → 물리 ERD), 사용자별 레이아웃 저장·복원, M-1 선행 P1 2건(기본 스키마 동시 생성, PostgreSQL 입력 검증).
- 구현하지 않음 : ERD 에서 테이블·컬럼·관계 편집(M-8), 관리 화면 연동(M-3), 논리 ERD(M-6), 원본 DB 카탈로그·AI 추정 관계. 조회는 물리 모델을 바꾸지 않는다.

## 2. 백엔드 (`metamora-data` `erd/`)

| 구성 | 내용 |
|---|---|
| `ErdRestController` | `/api/erd`, 클래스 단위 `hasRole('VIEWER')` |
| `ErdService` / `impl/ErdServiceImpl` | 조회(readOnly 트랜잭션), 레이아웃 저장 |
| `ErdGraphRepository` / `impl/ErdGraphRepositoryImpl` | QueryDSL DTO 프로젝션. 엔티티를 읽지 않아 `ColumnInfo.standardTerms`(EAGER) N+1 이 없다 |
| `ErdLayoutRepository`, `ErdLayout` 엔티티 | `TB_ERD_LYT` |
| DTO | `ErdDto` (Table, Column, Graph, Search, Layout). 관계는 M-1 `PhysicalModelDto.Relation` 을 그대로 쓴다 |

### 2.1 API

| 메서드·경로 | 설명 |
|---|---|
| `GET /api/erd?schemaId&keyword&isApproval&isMasterTable&page&size` | 조건에 맞는 테이블(스키마 → 테이블명 순) 1페이지 + 그 컬럼 + 그 테이블이 한쪽인 관계. `size` 1~300(기본 100), `totalCount`·`truncated` 로 범위를 알린다 |
| `GET /api/erd/neighbors/{tableId}?depth=1..3` | 선택 테이블에서 관계로 depth 단계까지 이어진 테이블(최대 300, 넘으면 `truncated`) |
| `GET /api/erd/layout` | 내 레이아웃. 저장한 적 없으면 `version`·`viewport` null. 삭제된 테이블 좌표는 빼고 응답(DB 는 바꾸지 않음) |
| `PUT /api/erd/layout` | `{version, nodes: {테이블ID: {x, y}}, viewport: {x, y, zoom}}`. nodes 는 바뀐 것만 보내도 기존 좌표와 합친다 |

- 쿼리 수는 테이블 수와 무관하게 4번(count·테이블·컬럼·관계, `ErdIntegrationTest` 에서 Hibernate 통계로 확인). 이웃 탐색은 단계마다 관계 쿼리 1번 + 테이블·컬럼·관계.
- 검색어는 테이블 물리명·한글명(논리명)·스키마명 부분 일치(대소문자 무시, `%`·`_` 이스케이프), 100자 제한.
- 관계선은 `table_relation` 만 쓴다. 관계로 확정되지 않은 `refTableName` 은 선으로 만들지 않는다. 카디널리티는 표시하지 않는다.
- 요청 로그(`LogsInterceptor`)에서 `/api/erd/**` 는 제외했다 (응답·좌표가 크고 모델 변경이 아님).

### 2.2 프로젝트 격리·권한 (D-05)

- 모든 쿼리는 `accountDto.getProjectId()` 로 거른다. 다른 프로젝트의 `schemaId` → `SCHEMA_IS_NOT_DEFINED`, `tableId` → `TABLE_IS_NOT_DEFINED`, 레이아웃에 다른 프로젝트 테이블 ID → `TABLE_IS_NOT_DEFINED`. 레이아웃은 (프로젝트, 사용자) 로만 찾으므로 레이아웃 ID 를 받는 API 가 없다.
- 권한 회수는 기존 `ProjectAccessInterceptor` 가 처리한다.
- 조회와 레이아웃 저장 모두 VIEWER 이상. 레이아웃은 개인 화면 설정이고 물리 모델을 바꾸지 않으므로 조회 권한만 있는 사용자도 **자기 배치는 저장할 수 있다**. NOT_APPROVE 는 차단.
- `MenuAuthorization` : `physical-erd` = VIEWER (페이지·API 모두 VIEWER, 스키마 목록 `GET /api/schema` 도 VIEWER).

## 3. 레이아웃 (D-10, D-15)

`TB_ERD_LYT` : `ERD_LYT_SN`(시퀀스 `SQ_ERD_LYT`), `PRJ_SN`, `ACNT_SN`, `NODE_PSTN_CN`(TEXT JSON), `SCRN_X_CRDNT`·`SCRN_Y_CRDNT`·`SCRN_MGNF`(viewport), `VER_NO`(@Version), 감사 컬럼. `(PRJ_SN, ACNT_SN)` UNIQUE (`UX_ERD_LYT_01`).

- **저장 단위 (D-10) : 사용자 × 프로젝트 1건.** 스키마·검색·이웃 화면이 같은 좌표 공간을 쓴다 (한 테이블은 어느 화면에서든 같은 자리). 사용자끼리 충돌이 없고, 공유 배치는 편집(M-8)과 함께 재검토한다.
- **저장 시점 (D-15) : [배치 저장] 버튼.** 자동 저장 없음. 저장하지 않은 변경 수를 툴바에 표시하고 새로고침·창 닫기 때 브라우저 경고. 범위를 바꿔도 저장 안 한 좌표는 메모리에 남아 다음 저장에 포함된다.
- **복원 :** 저장된 좌표가 있는 테이블은 그 자리, 없는 테이블(새 테이블)만 ELK 로 배치해 **이미 배치된 모든 테이블의 오른쪽**에 놓는다. 삭제된 테이블 좌표는 조회 때 무시하고 다음 저장 때 정리. 저장한 viewport 는 첫 화면(기본 범위)에서만 복원하고, 범위를 바꾸면 조회 결과가 다 보이게 맞춘다.
- **동시성 :** 저장 요청의 `version` 이 DB 와 다르면 409 `ERD_LAYOUT_CONFLICT`. 같은 version 으로 동시에 들어오면 `@Version`(갱신) / UNIQUE(최초 저장)에 걸려 하나만 성공한다. 화면은 공통 알림 후 [저장된 배치 불러오기] 로 다시 받는다.
- **검증 :** 좌표 유한·절댓값 1e7 이하, zoom 0.02~4, 노드 5,000개 이하.
- 레이아웃 저장은 `TB_ERD_LYT` 만 쓴다. `TB_TBL.MDFCN_DT` 가 바뀌지 않음을 테스트로 확인했다.

## 4. 프론트엔드 (`metamora-web/src/features/erd`)

| 파일 | 내용 |
|---|---|
| `PhysicalErdPage.tsx` | `React.lazy` 로 지연 로딩. `PageHeader` + 툴바(스키마·검색·승인·마스터, 자동 배치·전체 보기·배치 저장·저장된 배치 불러오기) + 범위 표시줄(전체 N개 중 a–b, 이전/다음 100개) + 캔버스 + 상세 패널 |
| `TableNode.tsx` | 테이블 노드. 컬럼별 연결점(왼쪽 들어옴 / 오른쪽 나감 / 오른쪽 들어옴=자기 참조) + 테이블 연결점 |
| `layout.ts` | 레이아웃 어댑터 (PoC `engine.ts` 의 ELK 경로). 공식 ELK 워커, 새 요청 시 취소, 30초 제한, 실패 시 격자 배치. 관계 없는 테이블은 아래쪽 격자 |
| `model.ts` | API 타입, 노드 치수 상수 |
| `src/styles/erd.css` | `--gh-*` 토큰만 사용 (라이트/다크 모드). 1,100px 미만에서는 상세 패널을 캔버스 아래로 |

- 관계선 : React Flow 기본 `smoothstep`. 컬럼이 보이면 **컬럼 쌍마다** 해당 컬럼 연결점에(복합 FK 는 쌍 수만큼), 축소 상태·노드에서 생략된 컬럼(21번째 이후)은 **관계당 1개를 테이블 연결점**에 그려 선이 엉뚱한 컬럼에 붙지 않는다. 실선 FK / 점선 LOGICAL, 색으로 출처(USER·REF_COLUMN·IMPORTED·AI_APPROVED) 구분, 범례에 "원본 DB 의 실제 FK 와 다를 수 있음" 안내.
- 축소 표시 : 화면상 노드 너비(zoom × 280px)가 140px 미만이면 컬럼 행을 그리지 않는다(M-0 PoC 기준값). 노드 크기는 그대로.
- 화면 밖 노드 생략 : `onlyRenderVisibleElements`.
- 탐색 : 노드 클릭 → 상세 패널(테이블명·논리명·스키마·설명·승인·마스터, 컬럼 목록, 관계 목록 — 조회 범위 밖 상대 테이블도 표시), 연결 테이블 강조, "연결된 테이블만 보기", N단계(1~3) 이웃 탐색, "테이블로 이동" 선택 → 화면 이동.
- 캐시 : 레이아웃은 화면 진입마다 새로 받는다(`gcTime: 0`). 그래프는 범위별로 TanStack Query 캐시, [새로고침] 으로 물리 모델 변경을 다시 받는다. 폴링 없음.
- 프로젝트 전환 시 `ReactFlowProvider` 를 프로젝트 ID 로 다시 만든다.

### 4.1 번들 (vite build, 2026-11-10)

| 청크 | 크기 (gzip) | 로드 시점 |
|---|---|---|
| `index-*.js` (기존 화면 전체) | 496.37 kB (149.15 kB) | 최초 |
| `PhysicalErdPage-*.js` (React Flow + ERD) | 213.09 kB (70.45 kB) | 물리 ERD 진입 시 |
| `PhysicalErdPage-*.css` | 19.80 kB (3.56 kB) | 물리 ERD 진입 시 |
| `elk-worker.min-*.js` | 1,595.33 kB | 자동 배치가 필요할 때(워커) |

ERD 코드는 로그인·대시보드 초기 번들에 포함되지 않는다 (변경 전 초기 번들 크기는 따로 측정하지 않음).

## 5. 라이선스 (D-01)

- `elkjs` 0.12.0 : `EPL-2.0 OR GPL-3.0-or-later` 중 EPL-2.0 선택, 수정 없이 npm 배포본(`elk-api.js`, `elk-worker.min.js`)을 번들. 배포 워커 파일에는 저작권 헤더가 없어 고지를 따로 둔다.
- `@xyflow/react` 12.12.0 / `@xyflow/system` 0.0.83 : MIT.
- 조치 : `metamora-web/public/jsons/licenses/THIRD_PARTY_NOTICES.txt`(고지·저작권·소스 입수 주소·MIT 전문), `EPL-2.0.md`(전문). jar 와 Docker 이미지에 같이 들어가 `/jsons/licenses/` 로 제공되고, ERD 범례의 "오픈소스 고지" 로 연결된다.
- 판단 보류 : 무수정 Object Code 배포에서 소스 입수 방법을 URL 안내로 충분히 갈음하는지, 다른 서드파티 전체 고지가 필요한지는 법률 검토 사항으로 남긴다 (검증 완료로 보지 않음).

## 6. M-1 선행 P1

- **기본 스키마 동시 생성 :** `SchemaServiceImpl.resolveSchema` 가 기본 스키마가 없을 때만 `ProjectRepository.findByIdForUpdate`(PESSIMISTIC_WRITE) 로 프로젝트 행을 잠그고 다시 조회한 뒤 만든다. 이미 있으면 잠그지 않는다. 8개 동시 요청 → 기본 스키마 1개, 모두 같은 ID (H2·PostgreSQL).
- **PostgreSQL 입력 검증 2건 :** 기존 `PhysicalModelIntegrationTest.compositeModelDdl` 의 `1bad name` → `INVALID_SCHEMA_NAME`, `1 = 1; DROP TABLE TB_ORDER` → 거절 assertion 을 PostgreSQL 18.6 격리 DB 에서 재실행해 통과 (테스트 중복 작성 없음).

## 7. 검증

```bash
./gradlew clean build                                             # 290 tests, 실패 0, 건너뜀 2 (환경 변수 필요 테스트)
# PostgreSQL : 빈 격리 DB 를 만들어 지정 (사용 중인 DB 금지)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/metamora_erd_test \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
./gradlew :metamora-app:test --tests 'com.koboolean.metamora.ErdIntegrationTest' --tests 'com.koboolean.metamora.PhysicalModelIntegrationTest'
```

| 항목 | H2 | PostgreSQL 18.6 |
|---|---|---|
| `ErdIntegrationTest` 6건 : 일괄 조회(컬럼 순서·복합 FK 쌍 순서·자기 참조·순환·관계 없음·쿼리 4회·페이지), 검색·필터·이웃 1~3단계·한도, 프로젝트 격리·VIEWER/NOT_APPROVE, 레이아웃 저장·합치기·삭제 테이블·충돌·값 검증·모델 불변·사용자 분리, 최초 저장 동시 요청, 기본 스키마 동시 생성 | 통과 | 통과 |
| `PhysicalModelIntegrationTest` 4건 (M-1, 입력 검증 2건 포함) | 통과 | 통과 |
| HTTP (h2-local jar) : 로그인 → `menus` 에 physical-erd, 빈 그래프, 레이아웃 최초 저장 200 / 같은 version 재저장 409 / 없는 테이블 400 / 숫자 아닌 ID 400 / zoom 9 → 400, 이웃 없는 ID 400, size 301 → 400, `/physicalErd`·고지 파일 200 | 확인 | — |
| `npm run typecheck`, `npm run build` | 통과 | — |

**미검증 (완료로 보지 않음)**

- 브라우저 화면 동작 전체 : 노드 렌더링·관계선 위치·드래그·자동 배치·저장/복원·다크 모드·작은 창 (Chrome·Safari·Firefox, 마우스 휠·트랙패드 모두 미확인).
- 실제 DB 300 / 1,000 / 3,000 테이블 규모의 API 응답 시간·응답 크기·렌더링·DOM·Heap·FPS. M-0 수치는 Mock 데이터 기준이다.
- 마이그레이션은 공통표준 명명 전환 때 `V0__init.sql` 하나로 다시 만들었다 (운영 데이터 없음). 신규 설치는 테스트 기동마다 확인된다.

## 8. 후속

| 항목 | 내용 |
|---|---|
| 브라우저·성능 실측 | 위 미검증 항목. 격리 DB 에 대량 데이터를 넣어 측정하고 D-02 기준값(140px, 300개, 노드당 20컬럼) 재조정 |
| 앱 내 화면 이동 경고 | 저장 안 한 배치가 있을 때 브라우저 새로고침·닫기만 경고한다. 메뉴로 다른 화면 이동은 경고 없이 버려진다 (React Router blocker 필요) |
| 전체 화면 Zoom | 300개 한도 안에서도 축소 시 관계선 수가 많으면 느릴 수 있다. 축소 시 관계선 생략 등은 실측 후 |
| M-3 | 상세 패널에서 테이블 관리·데이터 사전으로 이동, 관리 화면에서 ERD 로 이동 |
| M-6 / M-8 | `TableNode`·`layout.ts`·레이아웃 저장 형식(노드 ID → 좌표)은 모델 종류와 무관해 논리 ERD 에 재사용 가능. 편집 전에 관계 동기화 공통 경계(로드맵 M-1 후속)와 공유 레이아웃(D-10) 결정 필요 |
