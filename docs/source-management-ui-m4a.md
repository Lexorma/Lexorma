# M-4a 원본 DB 관리 화면

> 2026-11-13. [M-4](data-modeling-roadmap.md#m-4-역공학-결과의-물리-모델-가져오기erd-자동-생성)의 첫 단계.
> 이미 있는 원본 DB 연결·수집·원본 카탈로그·변경 이력 API(AI-1b·AI-1c·AI-2b)를 화면으로 제공한다.
> 원본 DB 는 조회만 하고, 물리 모델은 바꾸지 않는다. 신규 Flyway 마이그레이션은 없다.

## 1. 범위

| 포함 | 제외 (M-4b 이후) |
|---|---|
| 연결 목록·등록·수정·삭제·연결 테스트 | 원본 DB ERD |
| FULL·PARTIAL 수동 수집, 진행 상태, 취소, 결과 요약, 이력 | 물리 모델로 가져오기 |
| 원본 카탈로그 탐색 : 스키마 → 테이블·VIEW → 컬럼·PK·UNIQUE·CHECK·인덱스·FK·COMMENT | 원본 ↔ 모델 비교 |
| 수집 시점(반영 순서) 선택, 그 시점의 카탈로그 | 스키마 매핑, 객체 매핑 테이블 |
| 수집별·테이블별 변경 이력(신규·변경·삭제)과 테이블 버전 | 자동 동기화 |
| | 원본 SQL 실행, VIEW 정의 SQL 수집 |
| | Oracle·MySQL·MariaDB·EDB 수집기 |

## 2. 메뉴와 화면

사이드바 그룹 **원본 DB 관리** (`source-management`, Lucide `ServerCog`)는 물리 모델 관리와 데이터 품질 관리 사이에 둔다.

| 메뉴 ID | 화면 | 경로 | 최소 역할 |
|---|---|---|---|
| `source-connection` | 연결 관리 | `/sourceConnection` | VIEWER |
| `source-collection` | 메타데이터 수집 | `/sourceCollection` | VIEWER |
| `source-catalog` | 원본 카탈로그 | `/sourceCatalog` | VIEWER |
| `source-manage` | (버튼) 연결 등록·수정·삭제·테스트, 수집 실행·취소 | — | USER |

- 화면 사이에서는 `?connectionId=` 로 연결 선택을 이어 간다.
- 수집 화면은 `?executionId=` 를 쓴다.
- 카탈로그 화면은 `?order=` (반영 순서), `?schemaId=`, `?tableId=` 를 쓴다.
- 수집 상세의 [이 수집 시점의 원본 카탈로그 보기] 와 테이블 버전의 [이 시점 보기] 는 `order` 로 이동한다.

### 2.1 연결 관리

- **목록**
  - 연결명·설명, DBMS 와 지원 표시, 접속 대상(`호스트:포트/DB`, Oracle 은 서비스명·SID 표시), 계정, 상태를 보여 준다.
  - 상태 : 사용 여부, 비밀번호 상태(`passwordStatus`), 드라이버 없음.
  - 수집한 스키마 수를 보여 주고 카탈로그로 이동하는 링크를 단다.
- **등록·수정 대화상자**
  - 입력 항목 : DBMS, 호스트, 포트(빈칸이면 DBMS 기본), 데이터베이스명(Oracle 은 서비스명 또는 SID 와 그 종류), 계정, 비밀번호, 설명, 사용.
  - 허용 목록에 있는 연결 옵션(PostgreSQL `sslmode`, MySQL·MariaDB `sslMode`)을 고를 수 있다.
  - MySQL·MariaDB 만 식별자 대소문자 규칙을 고른다.
  - **JDBC URL 입력은 없다.**
- **비밀번호**
  - 보내기만 하고 화면에 다시 채우지 않는다.
  - 수정할 때 비워 두면 저장된 비밀번호를 그대로 쓴다.
  - 응답에는 `passwordConfigured`·`passwordStatus` 만 온다.
- **연결 테스트**
  - 대화상자의 [연결 테스트]는 저장 전 설정으로 접속한다 (`POST /api/source/connections/test?connectionId=`).
  - 목록의 [테스트]는 저장된 설정으로 접속한다.
  - 결과로 접속 여부, 메타데이터 조회 가능 여부, DB 제품·버전, 전송 암호화(sslmode·인증서·호스트명 확인)를 보여 준다.
  - 실패하면 서버가 준 메시지와 `SOURCE_*` 코드를 보여 준다.
- **DBMS 지원 표시**
  - 연결 테스트와 수집은 PostgreSQL 만 된다. 서버에 수집 도구가 `PostgresSourceTools` 뿐이다.
  - Oracle·MySQL·MariaDB 는 "설정만 저장" 으로 표시하고, 테스트·수집 버튼을 끈다.
  - EDB 는 `DbType` 에 없어 고를 수 없다.
- **삭제** : 확인 문구에서 그 연결의 원본 카탈로그·수집 이력·변경 이력도 지워지고 물리·논리 모델은 남는다고 알린다. 끝나면 지운 카탈로그 건수를 알린다.

### 2.2 메타데이터 수집

- **수집 실행** (관리 권한이 있을 때만 보인다)
  - FULL : 시스템 스키마를 뺀 전부.
  - PARTIAL : 이미 수집한 스키마를 체크박스로 고르거나, 이름을 직접 쉼표로 적는다. 원본 이름 그대로, 대소문자를 구분해 비교한다.
  - 확인 대화상자를 거친 뒤 `POST …/collections` 로 시작한다 (202 + 실행 ID).
- **중복 요청 방지**
  - 요청 중이거나 이 연결의 수집이 진행 중이면 [수집 시작]이 꺼지고 이유를 보여 준다.
  - 수집할 수 없는 DBMS, 사용 중지된 연결, 드라이버 없음도 같은 방식으로 막는다.
  - 서버도 진행 중이면 409 `SOURCE_COLLECTION_IN_PROGRESS` 로 거절한다.
- **진행 상태**
  - 진행 중(CREATED·RUNNING)인 실행 상세와 목록을 2초마다 다시 읽는다.
  - **진행률은 서버가 주지 않으므로 만들지 않는다.** 건수도 서버가 채운 값만 보이고, 비어 있으면 `-` 로 둔다.
  - 진행 중에는 [수집 취소]가 있다.
- **결과**
  - 상태는 `완료 · 반영됨` / `실패 · 반영 안 됨` / `취소 · 반영 안 됨` 으로 보인다.
  - 범위, 실행 방식, 시작·종료, 반영 순서·시각, 테이블·컬럼·새 버전·변경 건수를 보여 준다.
  - 대상 스키마마다 결과(완료·실패·원본에 없음·결과 없음)를 보여 준다.
  - **COMPLETED 만 변경 내역과 카탈로그 링크가 있다.** 실패·취소는 반영되지 않았다고 알린다.
- **변경 내역**
  - 수집 실행의 객체별 신규·변경·삭제를 50건씩 쪽으로 나눠 보여 준다 (구분·대상·이름·바뀐 항목).
  - 변경 전후 JSON 은 서버가 준 정규화 내용을 그대로 펼친다.
- 수집이 끝나면 연결 목록(수집한 스키마 수)과 PARTIAL 후보를 다시 읽는다.

### 2.3 원본 카탈로그

- **수집 시점 선택** : "마지막 반영(현재)" 과 COMPLETED 수집의 반영 순서 중에서 고른다. 실패·취소는 반영 순서가 없어 나오지 않는다.
- **목록** : 스키마와 테이블·VIEW 목록은 그 시점의 스냅샷 API 를 읽는다 (`/snapshot`, `/snapshot/schemas/{id}/tables`). 그 반영에서 생긴 버전이면 신규·변경 표시를 단다. 유형(TABLE·VIEW)·이름으로 거를 수 있다.
- **상세의 출처**
  - 마지막 반영이면 현재 카탈로그 상세(`/api/source/tables/{id}`)를 읽어 들어오는 FK 까지 보여 준다.
  - 과거 시점이면 그때의 버전 내용(`/tables/{id}/snapshot?order=`)을 같은 표 모양으로 바꿔 보여 준다 (`fromDetail`·`fromSnapshot`).
  - 버전은 테이블 자신의 내용만 담아서, 과거 시점에는 들어오는 FK 가 없다고 안내한다.
- **상세 탭**
  - 컬럼 : 순서, PK 표시, 타입, NULL, 기본값·IDENTITY, 원본 COMMENT.
  - 제약조건 : PK, UNIQUE, CHECK 식.
  - 인덱스 : 방식, UNIQUE, 표현식·정렬·NULLS·INCLUDE, 부분 인덱스 조건.
  - FK : 나가는 것과 들어오는 것, 삭제·수정 규칙. 카탈로그에 있는 테이블이면 이동할 수 있다.
  - 변경 이력과 버전 : 버전에서 [이 시점 보기]로 이동한다.
- **VIEW** : 컬럼·변경 이력·버전만 보인다. "정의 SQL 은 수집하지 않습니다" 를 안내한다.
- **물리 모델과의 구분** : 화면 머리말과 안내 배너에 "수집한 사실, Lexorma 물리 모델과 별개, 원본 DB 를 그 시점으로 되돌린 것이 아님" 을 적는다.

## 3. 권한

- **서버 기준 (변경 없음)**
  - 조회 API 는 VIEWER 이상이다.
  - 연결 변경·테스트와 수집 실행·취소 API 는 `@PreAuthorize` USER 이상이고, 서비스가 지금의 DB 로 **그 프로젝트의 관리자 또는 ADMIN** 인지 다시 확인한다 (`ExecutionContextVerifier.verifyProjectManager` = `ProjectAuthority.canManage`).
- **지시서의 권장안(MANAGER/ADMIN)과의 차이**

  | 계정 | 서버 기준 | 권장안 |
  |---|---|---|
  | 전역 MANAGER 역할이지만 그 프로젝트의 관리자가 아닌 계정 | 관리 불가 | 관리 가능 |
  | 역할이 USER 인 프로젝트 관리자 | 관리 가능 | 관리 불가 |

  프로젝트 설정은 프로젝트 관리자·ADMIN 의 일이라는 기존 결정(AI-1b)을 바꾸지 않았다. 화면도 서버와 같은 기준을 따른다.
- **화면 조건** : `menus.source-manage`(역할 USER 이상) && `projectManageable`
  - `projectManageable` 은 `/api/auth/me` 에 새로 추가했다. `ProjectAuthority.canManage` 와 같은 값이다.
  - VIEWER 와 관리자가 아닌 USER·MANAGER 에게는 버튼 대신 "프로젝트 관리자 또는 ADMIN 만" 안내가 보인다.
- **보안 경계 (기존 그대로)**
  - 다른 프로젝트의 연결은 목록에 없다. ID 를 알아도 `SOURCE_CONNECTION_NOT_FOUND` 다.
  - 비밀번호·JDBC URL·드라이버 메시지는 응답과 화면에 없다.
  - `/api/source/**` 는 요청 로그 본문을 남기지 않는다 (`LogBodyPolicy`).
  - 변경 요청에는 CSRF 를 쓴다 (`lib/api.ts`).
  - 접속 대상은 서버의 주소 정책(`LEXORMA_SOURCE_ALLOWED_NETWORKS`·허용 호스트)을 따른다.

## 4. 바뀐 것

| 구분 | 파일 |
|---|---|
| 서버 (core) | `MenuAuthorization` (메뉴 3 + 버튼 1 + 그룹), `AuthRestController` (`projectManageable`) |
| 화면 (신규) | `features/source/{model.ts, shared.tsx, SourceConnectionPage.tsx, SourceCollectionPage.tsx, SourceCatalogPage.tsx}`, `styles/source.css` |
| 화면 (수정) | `app/App.tsx` (경로 3), `components/layout/{menu.ts, Sidebar.tsx}`, `hooks/useAuth.tsx`, `locales/en.ts` (182 문구) |
| 테스트 | `SourceManagementUiIntegrationTest` (신규 2), `MenuAuthorizationTest` (VIEWER·USER 단정 추가) |

- 원본 수집 코드(`lexorma-source`)와 API 는 바꾸지 않았다.
- 새 API 클라이언트·상태 관리는 만들지 않았다. `lib/api.ts` 와 TanStack Query 만 쓴다.
- 표·라벨·탭·안내 배너·대화상자는 기존 공통 모양(`Label`, `nav-tabs`, `gh-flash`, `dialog-wide` + `gh-dialog-actions`)을 그대로 쓴다.
- 쪽 단위 원본 API(`page`·`size`)는 공통 `Grid`(`/data` 규약)와 맞지 않는다. 그래서 화면 안의 작은 쪽 이동(`Pager`)을 썼다.

## 5. 검증

- **테스트**

  | 실행 | 결과 |
  |---|---|
  | `./gradlew build` (H2) | 601 통과, 실패 0, 건너뜀 19 |
  | PostgreSQL 18.6 새 임시 DB `m4a_test` | 601 통과, 실패 0, 건너뜀 13 |
  | `npm run typecheck` · `npm run build` | 통과 |

- **브라우저 (로컬 headless Chrome)**
  - 대상 : bootJar(`h2-local`, 포트 9941, 비밀값 키·`LEXORMA_SOURCE_ALLOWED_NETWORKS=127.0.0.1/32` 지정).
  - 원본 : 임시 PostgreSQL DB `m4a_src`. 스키마 2개, 테이블 4개, VIEW 1개, 표현식·부분·DESC NULLS LAST·INCLUDE 인덱스, 스키마를 넘는 FK, CHECK, COMMENT 를 두었다. 읽기 전용 계정 `m4a_reader` 로 접속했다.
  - 연결 : 빈 목록 → 등록 대화상자 → 틀린 비밀번호로 테스트(`SOURCE_AUTH_FAILED` 표시) → 맞는 비밀번호로 테스트(PostgreSQL 18.6, sslmode=prefer) → 등록.
  - 다른 DBMS : Oracle 연결은 "설정만 저장" 표시에 [테스트]가 꺼지고, "드라이버 없음" 으로 보인다.
  - 저장된 연결 테스트를 했다.
  - 수정 : 비밀번호를 비워 둔 채 설명만 바꿨고, 저장된 비밀번호로 테스트가 성공했다.
  - 삭제 : Oracle 연결을 확인 문구와 지운 건수 알림을 거쳐 지웠다.
  - 수집
    - FULL 이 COMPLETED 됐다 (스키마 2, 테이블·VIEW 5, 컬럼 22).
    - 연속 요청 : 첫 요청은 202, 두 번째는 409 였다.
    - PARTIAL(sales) : 신규 인덱스·컬럼 COMMENT 변경 2건이 나왔다.
    - FULL : 신규 컬럼 1건이 나왔다.
    - 원본에서 인덱스를 지운 뒤에는 삭제로 기록됐다.
    - 같은 내용을 다시 수집하면 "달라진 것이 없습니다" 로 보였다.
  - 취소 : 응답하지 않는 로컬 TCP 포트로 연결해 RUNNING 중 [수집 시작]이 꺼지는 것을 봤다. [수집 취소] 로 `취소 · 반영 안 됨` 이 됐다.
  - 실패 : 같은 연결이 접속 제한 시간 뒤 FAILED(`SOURCE_CONNECT_FAILED`) 가 됐다. 이 연결의 카탈로그는 "아직 반영된 수집이 없습니다" 로 보였다.
  - 카탈로그 탐색
    - 스키마 → 테이블 → 컬럼(PK·IDENTITY·COMMENT)·제약조건(PK·UNIQUE·CHECK)·인덱스(표현식·정렬·INCLUDE·조건)·FK(들어오는 FK 에서 `sales.orders` 로 이동)를 확인했다.
    - 변경 이력·버전을 확인했다.
    - VIEW 는 컬럼만 있고 정의 SQL 안내가 보였다.
    - 반영 순서 1 을 고르면 그때의 컬럼만 보이고, 들어오는 FK 미표시 안내가 나왔다.
  - 권한
    - VIEWER·USER(관리자 아님)·전역 MANAGER(관리자 아님) 는 세 화면 조회만 된다. 관리 버튼 대신 안내가 보인다.
    - API 를 직접 부르면 USER·MANAGER 는 403 `EXECUTION_PERMISSION_DENIED`, VIEWER 는 403 이다.
  - 화면 조건 : 한국어·영어, 라이트·다크, 1440px·375px 에서 가로 넘침이 없다 (등록 대화상자 포함). 콘솔 오류도 없다. 확인용으로 일부러 부른 403·409 와 로그인 전 401 만 나왔다.
- **정리** : 검증용 임시 DB(`m4a_src`, `m4a_test`)와 역할(`m4a_reader`)은 확인 후 지웠다. 개발 DB `lexorma` 는 쓰지 않았다.

## 6. 남은 것

- **실패 메시지** : 실패한 수집은 오류 코드만 있고 `errorMessage` 가 비어 있는 경우가 있다 (예 : `SOURCE_CONNECT_FAILED`). 화면은 일반 문구에 코드를 붙인다. 코드별 안내 문구를 서버가 줄지는 후속에서 정한다.
- **큰 스키마** : 테이블 목록은 스냅샷 API(쪽 단위 아님)를 한 번에 읽고 화면에서 거른다. 테이블이 수천 개인 스키마의 응답 크기·검색은 M-4b 와 함께 본다.
- **수집 대상 고르기** : PARTIAL 의 스키마 후보는 이미 수집한 스키마뿐이다. 원본 DB 에서 스키마 목록을 바로 읽는 API 는 없다.
- **진행 중 알림** : 수집 진행 중 화면을 떠나면 끝났다는 알림이 없다 (다시 들어오면 보인다).
- **M-4b** : 원본 DB ERD — 2026-11-13 구현 ([기록](source-erd-m4b.md)). 남은 결정은 M-4 분석 보고의 1~11 이다.
