# 권한별 UI 액션 점검 (M-9 착수 전 안정화)

2026-11-12. 조회 권한(VIEWER) 사용자에게 등록·삭제·수정 버튼이 보이는 문제에서 시작해, 전체 화면의 버튼 노출과 서버 인가를 맞춰 본 기록이다.
역할 체계·DB 는 바꾸지 않았다(마이그레이션 없음). M-9 는 시작하지 않았다.
같은 날 2차 안정화(데이터 사전 승인 기준, VIEWER 의 컬럼 조회, V3 마이그레이션 확인)를 이어서 했다 — 10장. 본문의 표는 2차까지 반영한 최종 상태다.

## 1. 권한 구조 (서버 기준)

**역할 계층** (`RoleHierarchyConfig`) : `ADMIN > MANAGER > (DBA, USER)`, `DBA > USER > VIEWER > NOT_APPROVE > ANONYMOUS`.

- 역할은 **계정 단위**다(`TB_ROLE` / 계정-역할). 프로젝트별 역할은 없다. 가입하면 `NOT_APPROVE`, 사용자 관리(ADMIN)에서 바꾼다.
- 프로젝트 쪽 권한은 두 가지다.
  - **멤버십** : 활성 프로젝트의 승인된 멤버만 그 프로젝트로 로그인한다. `ProjectAccessInterceptor` 가 API 요청마다 다시 확인한다(401 `PROJECT_ACCESS_REVOKED`).
  - **프로젝트 관리자** : 프로젝트마다 한 명(`TB_PRJ` 의 관리자 계정).
- **승인 권한** (`AuthUtil.isApprovalAvailable()`) : ADMIN·MANAGER 역할이거나 **로그인한 프로젝트의 관리자**. 승인·승인취소, 스키마·공통 컬럼 변경에 쓰고, 승인 권한자가 등록한 항목은 바로 승인 상태가 된다.
- API 는 `@PreAuthorize("hasRole('…')")` 로 최소 역할을 선언하고, 데이터는 로그인한 프로젝트(`AccountDto.projectId`)로 걸러진다.
- 화면은 `/api/auth/me` 의 `menus`(서버 `MenuAuthorization` 이 역할 계층으로 계산)와 `approvalAvailable` 만 본다. 화면에서 역할 이름을 직접 비교하는 곳은 없다.

**ADMIN 이라고 무엇이든 하는 것은 아니다** : 승인된 테이블·엔터티는 ADMIN 도 수정할 수 없고(승인취소 후), 프로젝트 관리·공지 변경은 그 프로젝트의 관리자여야 한다.

## 2. 화면 진입과 액션의 분리

`MenuAuthorization.MENU_ROLES` 에는 화면 항목과 **액션 항목**이 함께 있다. 액션 항목은 메뉴가 아니라 버튼 노출용이고, 값은 해당 변경 API 의 `@PreAuthorize` 역할과 같다.

| 액션 ID | 최소 역할 | 쓰는 곳 |
|---|---|---|
| `dictionary-edit` (신규) | USER | 표준 도메인·단어·용어의 추가·수정·삭제, 템플릿·다운로드·업로드 |
| `dictionary-approve` (신규) | DBA | 표준 도메인·단어·용어의 승인·승인취소. 서버가 역할(DBA)과 승인 권한을 둘 다 요구하므로 화면도 `approvalAvailable` 과 함께 본다 (이 ID = 역할 조건, `approvalAvailable` = 승인 권한 조건) |
| `physical-model-edit` | USER | 테이블 관리의 추가·삭제·수정·컬럼 편집·엑셀, 물리 ERD 편집 모드 |
| `logical-model-edit` | USER | 논리 모델·논리 ERD 의 변경 |
| `model-mapping-edit` | USER | 매핑 등록·변경·해제 |
| `notice-edit` (신규) | MANAGER | 공지 추가·수정·삭제 (현재 프로젝트의 관리자 조건과 함께) |

화면 코드는 `useAuth().canView('<액션 ID>')` 와 `approvalAvailable` 로만 판단한다. 새 변경 버튼을 만들 때는 API 의 역할과 같은 액션 ID 를 쓰고, 없으면 `MENU_ROLES`·`MenuId` 에 추가한다.

`approvalAvailable` 은 이제 **승인 권한이면서 USER 이상**일 때만 true 다. 승인·관리 API 가 모두 USER 이상이라, 역할이 VIEWER 이하인 프로젝트 관리자에게 승인 버튼이 보이고 누르면 403 이 나던 것을 없앴다.

## 3. 버튼 표시 정책

1. 권한이 없으면 **렌더링하지 않는다**.
2. 권한은 있는데 데이터 상태 때문에 못 하면 **비활성 + 사유**(기존 ERD 방식 : 승인된 테이블의 `테이블 수정`·`테이블 삭제`, 승인된 엔터티의 `엔터티 삭제` 등) 또는 누를 때 안내(그리드의 일괄 삭제 : "승인된 정보가 포함되어 있습니다").
3. 그리드에 쓸 수 있는 버튼이 하나도 없으면 체크박스 열도 숨긴다(고를 일이 없다).

그리드의 `crudActions` 는 그대로다 : 대문자는 항상, 소문자는 `approvalAvailable` 일 때. 화면이 권한에 따라 문자열을 정한다(예 : `canEdit ? 'anCD' : ''`).

## 4. 권한 매트릭스

"수정 전" 은 이번 작업 전에 화면이 보여 주던 대상, "서버" 는 API 의 실제 조건이다. 승인 = 승인 권한(2장).

### 운영·시스템

| 화면 | 액션 | 서버 | 수정 전 화면 | 수정 후 |
|---|---|---|---|---|
| 대시보드 | 조회 | NOT_APPROVE | 일치 | — |
| 공지사항 | 목록·상세 | NOT_APPROVE, **로그인 프로젝트의 공지만** | 목록은 일치. 상세 API 는 ID 만 알면 다른 프로젝트 공지도 읽혔다 | 서버 보강 (6장) |
| 공지사항 | 추가·수정·삭제 | MANAGER + **현재 프로젝트 관리자**, 대상도 관리 프로젝트만 | 프로젝트 관리자면 역할과 무관하게 버튼 표시(USER 관리자는 눌러도 403). 서버는 MANAGER 면 아무 프로젝트에나 허용 | 화면 : 프로젝트 관리자 + `notice-edit`. 서버 보강 |
| 사용자 관리 | 조회·권한 변경·삭제·비밀번호 초기화 | ADMIN | 일치 | 권한 변경·삭제 시 대상 세션 종료 (7장) |
| 프로젝트 관리 | 조회·수정·멤버 추가·승인·삭제 | DBA + **그 프로젝트의 관리자** | 화면은 관리 프로젝트만 보여 줌. 서버는 DBA 면 ID 로 아무 프로젝트나 허용. 멤버 그리드 버튼은 "로그인한 프로젝트" 의 승인 권한을 봤다 | 서버 보강. 그리드 버튼은 선택한 프로젝트 기준(항상 표시) |
| 프로젝트 관리 | 등록 | DBA | 관리 프로젝트가 하나도 없으면 화면이 "접근 불가" 라 버튼에 닿지 못함 | 그대로 (9장) |
| 시스템 로그 | 조회 | DBA | 일치 | — |
| 내 정보 | 이름·비밀번호 변경 | NOT_APPROVE (본인) | 일치 | — |

### 데이터 사전 (표준 도메인·단어·용어)

| 액션 | 서버 | 수정 전 화면 | 수정 후 |
|---|---|---|---|
| 조회·상세·사용 현황 | VIEWER | 일치 | — |
| 추가·수정·삭제 | USER | **모든 사용자에게 표시** (VIEWER 는 눌러도 403) | `dictionary-edit` |
| 템플릿·등록 항목 다운로드·업로드 | USER (엑셀 API 는 다운로드도 USER) | **모든 사용자에게 표시** | `dictionary-edit` |
| 승인·승인취소 | DBA **+ 승인** (2차에서 승인 권한 확인 추가. 전에는 DBA 역할만 봤다) | 승인 권한 | `dictionary-approve` **그리고** 승인 권한 — 서버와 같다 |
| 승인된 항목 수정·삭제 | 거절 | 상세에 수정 버튼 없음, 삭제는 안내 | — |

### 물리 모델

| 화면 | 액션 | 서버 | 수정 전 화면 | 수정 후 |
|---|---|---|---|---|
| 스키마 관리 | 조회 | VIEWER | 일치 | — |
| 스키마 관리 | 추가·수정·삭제 | USER + 승인 | 승인 권한 | 승인 권한(USER 이상) |
| 테이블 관리 | 조회·상세·DDL | VIEWER | 일치 | — |
| 테이블 관리 | 추가·삭제, 템플릿·업로드 | USER | **모든 사용자에게 표시** | `physical-model-edit` |
| 테이블 상세 | 수정·컬럼 편집 | USER (승인된 테이블은 수정 거절) | **모든 사용자에게 표시** | `physical-model-edit` (어느 화면에서 열든) |
| 테이블 관리·상세 | 승인·승인취소 | USER + 승인 | 승인 권한 | 승인 권한(USER 이상) |
| 테이블 상세 | 컬럼 목록 | **VIEWER** (2차에서 조회 API 2개를 VIEWER 로. 전에는 컬럼 API 전체가 USER) | VIEWER 에게 "조회할 권한이 없습니다" | 컬럼 목록 표시 (물리 ERD 와 같은 내용) |
| 컬럼 관리 | 화면·추가·삭제·엑셀·순서·일괄 편집, 등록 폼용 조회 | USER | 일치 (메뉴가 USER) | — (화면은 등록·수정용이라 USER 그대로) |
| 컬럼 관리 | 승인·승인취소 | USER + 승인 | 일치 | — |
| 공통 컬럼 세트 | 변경 | USER + 승인 | 서버가 내려준 `editable` | — |
| 물리 ERD | 조회, 배치 저장 | VIEWER (배치는 사용자별, 모델을 바꾸지 않음) | 일치 | — |
| 물리 ERD | 편집 모드(테이블·컬럼·FK·인덱스·제약) | USER | `physical-model-edit` | — |
| 물리 ERD | 관계·인덱스·제약 승인 | USER + 승인 | 승인 권한 | 승인 권한(USER 이상) |
| DDL 내보내기 (M-11, 2026-11-13 추가) | 통합 DDL 생성·복사·.sql 저장 | VIEWER (조회 전용, 실행 경로 없음) | 메뉴 `ddl-export` VIEWER | — |

### 원본 DB 관리 (M-4a, 2026-11-13 추가)

| 화면 | 액션 | 서버 | 화면 |
|---|---|---|---|
| 연결 관리·메타데이터 수집·원본 카탈로그 | 조회 (연결 목록·상세, 수집 이력·상세·변경 내역, 시점별 카탈로그·테이블 버전·변경 이력) | VIEWER | 메뉴 `source-connection`·`source-collection`·`source-catalog` VIEWER |
| 연결 관리 | 등록·수정·삭제·연결 테스트 | USER + **그 프로젝트의 관리자 또는 ADMIN** (`verifyProjectManager`) | `source-manage`(USER) **그리고** `/api/auth/me` 의 `projectManageable` |
| 메타데이터 수집 | 수집 실행·취소 | USER + 그 프로젝트의 관리자 또는 ADMIN | 위와 같다 |
| 원본 매핑 관리 (M-4c) | 조회 (`GET /api/sourceMapping`, `/tables`, `/modelTables`) | VIEWER | 메뉴 `source-mapping` VIEWER |
| 원본 매핑 관리 (M-4c·M-4g) | 기준 연결 설정·해제, 스키마·TABLE 매핑 확정·해제, TABLE 매핑 예외 등록·취소 (`PUT /api/sourceMapping/exclusions`, `DELETE /exclusions/{id}`) | USER + 그 프로젝트의 관리자 또는 ADMIN | `source-manage`(USER) **그리고** `projectManageable` |
| 원본·모델 비교 (M-4d) | 조회 (`GET /api/sourceCompare`, `/tables`, `/table`, `/renameCandidates`) | VIEWER | 메뉴 `source-compare` VIEWER |
| 원본 가져오기 (M-4e) | 대상·계획·이력 조회 (`GET /api/sourceImport/candidates`, `POST /plan`(읽기 전용), `GET /executions`) | VIEWER | 메뉴 `source-compare` VIEWER ([미리보기]) |
| 원본 가져오기 (M-4e) | 확정 (`POST /api/sourceImport/apply`) | USER + 그 프로젝트의 관리자 또는 ADMIN | `source-manage`(USER) **그리고** `projectManageable` ([가져오기 확정]) |
| 원본 DB ERD (M-4b) | 조회 (`GET /api/source/connections/{id}/snapshot/erd`, 상세는 카탈로그 API) | VIEWER | 메뉴 `source-erd` VIEWER. 읽기 전용이라 관리 버튼이 없다 |

전역 MANAGER 역할만으로는 다른 사람이 관리하는 프로젝트의 연결·수집을 바꿀 수 없다 (승인 권한 `approvalAvailable` 과 다른 기준). `SourceManagementUiIntegrationTest` 가 화면 값과 서버 거절을 함께 본다.

### 외부 연결 보안 (SEC-1, 2026-11-14 추가)

| 화면 | 액션 | 서버 | 화면 |
|---|---|---|---|
| 외부 연결 보안 | 허용 규칙 조회·등록·수정·활성화·비활성화·삭제, 허용 규칙이 없는 등록 연결, 연결 시도 감사, 정책 변경 이력 (`/api/security/**`) | **ADMIN** (시스템 관리자. MANAGER·DBA·프로젝트 관리자는 403) | 메뉴 `connection-security` ADMIN |
| AI Provider 관리 (AI-3) | Provider 목록·상세·유형, 등록·수정·활성화·비활성화·삭제, 연결 테스트, 모델 목록, 테스트 프롬프트 (`/api/ai/providers/**`), 시스템 기본 Provider 조회·지정·해제 (`/api/ai/default-provider`) | **ADMIN** (시스템 관리자. MANAGER·DBA·프로젝트 관리자는 403) | 메뉴 `ai-provider` ADMIN |

외부 연결 허용 규칙은 프로젝트가 아닌 시스템 보안 정책이다. 원본 DB 연결 테스트·수집은 기존 권한(USER + 프로젝트 관리자 또는 ADMIN)에 이 정책이 더해진다 (규칙이 없으면 연결하지 않는다). `ExternalConnectionSecurityIntegrationTest` 가 USER·MANAGER·DBA 의 거절을 본다.

### 논리 모델·매핑·품질

| 화면 | 액션 | 서버 | 수정 전 화면 | 수정 후 |
|---|---|---|---|---|
| 논리 모델·논리 ERD | 조회, 배치 저장 | VIEWER | 일치 | — |
| 논리 모델·논리 ERD | 모델·엔터티·속성·식별자·관계 변경, 편집 모드 | USER | `logical-model-edit` | — |
| 논리 모델·논리 ERD | 엔터티·관계 승인 | USER + 승인 | 승인 권한 | 승인 권한(USER 이상) |
| 논리·물리 매핑 | 조회·정합성·추천 | VIEWER | 일치 | — |
| 논리·물리 매핑 | 매핑·테이블 변경·해제 | USER | `model-mapping-edit` | — |
| 표준화 품질 진단 | 조회 | VIEWER | 일치 | — |

**ERD 의 버튼 밖 동작** : 두 ERD 모두 `deleteKeyCode={null}`(Delete·Backspace 로 지워지지 않음), `elementsSelectable={false}`, 연결점은 편집 모드에서만(`nodesConnectable={editing}`), 우클릭 메뉴 없음. 편집 모드 버튼은 액션 권한이 없으면 나오지 않는다. 더블클릭(논리 ERD)은 상세를 열 뿐이고 상세는 조회 모드에서 읽기 전용이다.

## 5. 프론트엔드 변경

- `useAuth` : 액션 ID 3개 추가, `canView`·`approvalAvailable` 의 의미를 주석으로 명시.
- `Grid` : 버튼이 하나도 없으면 체크박스 열을 숨긴다.
- 테이블 관리 : 추가·삭제·승인·엑셀 버튼을 `physical-model-edit` 로.
- 테이블 상세(`useTableDetail`, 테이블 관리·물리 ERD·사용 현황·품질 진단 공용) : 수정·컬럼 편집을 `physical-model-edit` 로.
- 데이터 사전 : 추가·삭제·엑셀·상세의 수정을 `dictionary-edit`, 승인을 `dictionary-approve` 로.
- 공지사항 : 추가·삭제·행 클릭 수정을 프로젝트 관리자 + `notice-edit` 로.
- 프로젝트 관리 : 멤버 그리드 버튼을 선택한 프로젝트 기준으로.

## 6. 서버 보강 (발견한 보안 문제)

화면만 틀린 것이 아니라 서버가 허용하던 것들이다. 역할 체계는 그대로 두고 "누구의 것인지" 확인만 넣었다. 거절은 403 `ACCESS_DENIED`.

| 문제 | 전 | 후 |
|---|---|---|
| 프로젝트 관리 API (`/api/getProject/{id}/…`, `PUT /api/saveProject/project`, 멤버 추가·승인·삭제) | DBA 이상이면 프로젝트·멤버 ID 로 **다른 사람의 프로젝트**를 조회·수정(관리자 교체·비활성화 포함)하고 멤버를 바꿀 수 있었다 | 그 프로젝트의 관리자만 (화면의 `/api/getProject/managed` 와 같은 기준) |
| 공지 등록·수정·삭제 | MANAGER 이상이면 프로젝트 관리자가 아니어도, 대상 프로젝트 ID 를 임의로 넣어도 됐다. 수정은 다른 프로젝트 공지도 ID 로 바꿀 수 있었다 | 현재 프로젝트의 관리자만, 대상도 자신이 관리하는 프로젝트만, 수정은 로그인 프로젝트의 공지만 |
| 공지 상세 (`/api/selectNotice/view/{id}`, `/detail/{id}`) | 로그인만 하면 ID 로 **다른 프로젝트의 공지**를 읽고 읽음 표시까지 남겼다 | 로그인 프로젝트의 공지만. 없는 ID 는 500(NullPointerException) 대신 403 |
| 역할 변경 후의 세션 | 역할이 세션에 담겨, 강등된 사용자가 로그아웃할 때까지 **예전 역할로** API 를 쓸 수 있었다 | 7장 |

## 7. 권한이 바뀔 때

| 변경 | 동작 |
|---|---|
| 프로젝트 멤버 승인취소·삭제, 프로젝트 비활성화 | 기존 그대로 : 다음 API 요청에서 세션 종료, 401 `PROJECT_ACCESS_REVOKED` → 안내 후 로그인 화면 |
| 역할 변경, 사용자 삭제 (사용자 관리) | **신규** : 저장할 때 그 사용자의 세션을 지운다. 열려 있던 화면의 다음 요청은 401 → "세션이 만료되어 로그인 화면으로 이동합니다" → 다시 로그인하면 새 역할의 메뉴·버튼 |
| 프로젝트 관리자 교체 | 세션에 담기지 않는다. 서버는 요청마다 DB 로 판단하므로 바로 적용. 화면의 `approvalAvailable` 은 `/api/auth/me` 를 다시 받을 때(60초가 지난 뒤 창에 돌아오거나 새로고침) 바뀐다. 그 사이에 누르면 서버가 거절한다 |

이미 열어 둔 편집 대화상자는 닫히지 않는다. 저장을 누르면 서버가 거절(401·403)하고 변경은 일어나지 않는다. 실시간 알림은 만들지 않았다.
자기 역할을 바꾼 ADMIN 도 로그아웃된다.

## 8. 테스트

**자동 테스트** (격리 복사본에서 실행)

| 실행 | 결과 |
|---|---|
| H2 `./gradlew clean build` (프론트엔드 타입 검사·빌드 포함) | 378건, 실패 0, 건너뜀 2 (`V0SeedMigrationTest` 의 PostgreSQL 쪽, `UploadPerformanceTest`) |
| PostgreSQL 18.6, 따로 만든 검증 DB 에서 전체 (`./gradlew test --rerun`) — 끝난 뒤 DB 삭제 | 378건, 실패 0, 건너뜀 1 (`UploadPerformanceTest`) |

- `UiActionAuthorizationIntegrationTest` (신규 4건)
  - 모든 `/api` 핸들러가 역할을 선언하고, 변경 API(POST·PUT·PATCH·DELETE)는 예외 목록(로그인 전 절차, 본인 계정, 사용자별 ERD 배치) 외에는 USER 이상 — 조회 권한이 호출할 수 있는 변경 API 가 새로 생기면 실패한다.
  - 프로젝트 관리 : DBA·MANAGER·ADMIN 이어도 남의 프로젝트는 거절, 값이 바뀌지 않음.
  - 공지 : 관리자가 아닌 MANAGER·ADMIN, 관리하지 않는 대상 프로젝트, 다른 프로젝트에서의 읽기·수정 거절.
  - `/api/auth/me` : VIEWER 인 프로젝트 관리자의 `approvalAvailable`·액션 값.
- `ProjectAccessRevokeTest` (1건 추가, 실제 서버·세션) : 역할 변경 → 세션 삭제 → 예전 쿠키 401 → 다시 로그인하면 새 역할, VIEWER 로 강등 후 변경 API 403.
- `MenuAuthorizationTest`, `NoticeServiceImplTest`, `UserManageServiceImplTest` 보정.

**역할별 API 직접 호출** (격리 실행 서버, 역할마다 계정을 만들어 로그인)

조회·변경 API 33개 × 6역할. NOT_APPROVE 는 대시보드·공지 조회만 200 이고 나머지는 403. VIEWER 는 조회만 200, 변경 API 는 모두 403(자기 ERD 배치 저장만 허용). USER·DBA 의 승인 API 는 400 `APPROVAL_PERMISSION_DENIED`, 데이터 사전 승인은 DBA 부터. 공지 변경은 프로젝트 관리자가 아닌 MANAGER·ADMIN 도 403, 프로젝트 관리자(admin)는 200.

**브라우저** (로컬 헤드리스 Chrome, 격리 실행 서버 + H2)

역할마다 로그인해 16개 화면의 버튼, 상세 대화상자의 버튼, ERD 의 편집 모드·연결점을 수집했다.

| 역할 | 결과 |
|---|---|
| NOT_APPROVE | 메뉴는 대시보드·공지. 나머지 화면은 주소를 직접 넣어도 "접근 불가". 버튼 없음 |
| VIEWER | 변경 버튼 0개(추가·삭제·승인·수정·업로드·편집 모드 없음), 체크박스 열 없음, 테이블 상세는 DDL·ERD·매핑 보기만, ERD 연결점 0 |
| USER | 추가·삭제·엑셀·편집 모드 있음, 승인·스키마 변경 없음. 추가 대화상자가 열린다(테이블·단어·용어·컬럼·엔터티) |
| DBA | USER 와 같고 시스템 로그 추가. 데이터 사전 승인 버튼은 없음(승인 권한이 없다) |
| MANAGER | 승인·승인취소, 스키마 추가·수정·삭제 추가. 공지 변경 버튼 없음(프로젝트 관리자가 아님) |
| ADMIN (프로젝트 관리자 아님) | MANAGER 와 같고 사용자 관리 추가 |
| ADMIN (프로젝트 관리자) | 공지 추가·삭제, 프로젝트 관리 버튼까지 |

- 로그인 중 강등(USER → VIEWER) : 열려 있던 테이블 관리에서 조회를 누르면 세션 만료 안내, 변경 API 401, 다시 로그인하면 버튼이 사라진다.
- 다크 모드, 영어, 600px 폭 : VIEWER 화면에 빈 버튼 영역·가로 넘침 없음.

**확인하지 못한 것**

- 허용된 버튼은 "보이는지" 와 "등록 대화상자가 열리는지" 까지만 브라우저로 봤다. 저장까지의 동작은 기존 통합 테스트(API)와 이번 API 호출로만 확인했다.
- 승인된 데이터의 비활성 표시는 예시 데이터(모두 승인 상태)에서 봤고, 승인 대기 상태의 화면은 역할별로 따로 보지 않았다.
- PostgreSQL 을 붙인 상태의 화면, Chrome 이외 브라우저, 터치.
- 프로젝트 관리자가 USER·VIEWER 역할인 경우의 화면(서버 응답은 통합 테스트로 확인).
- 엑셀 업로드의 실제 파일 처리(권한 응답만 확인).

## 9. 남은 불일치·위험 (결정 필요)

정책을 넓히거나 바꾸는 일이라 손대지 않고 보수적으로 맞췄다.

1. ~~데이터 사전 승인의 기준이 다른 승인과 다르다~~ → **2차에서 해결** (10.2). 남은 차이 : 최소 역할이 데이터 사전은 DBA, 테이블·컬럼·논리 모델은 USER 다. 그래서 USER 인 프로젝트 관리자는 테이블은 승인하지만 데이터 사전은 승인하지 못한다(화면·서버 일치). 최소 역할까지 맞출지는 결정 사항이다.
2. ~~VIEWER 는 테이블 상세의 컬럼 목록을 못 본다~~ → **2차에서 해결** (10.3).
3. **엑셀 다운로드(템플릿·등록 항목)가 USER 다.** VIEWER 는 데이터 사전을 내려받지 못한다. (P2, 10.5)
4. **프로젝트 관리 메뉴는 DBA 에게 보이지만, 관리하는 프로젝트가 없으면 화면이 "접근 불가" 다.** 그 상태에서는 프로젝트 등록 버튼에도 닿지 못한다(API 는 DBA 면 등록 가능).
5. **ADMIN 도 남의 프로젝트는 관리하지 못한다.** 화면의 기존 동작(관리 프로젝트만 표시)에 서버를 맞춘 결과다. 관리자가 없어진 프로젝트를 넘겨받는 경로가 필요하면 따로 만들어야 한다.
6. 역할이 바뀐 사용자는 "세션이 만료되어…" 안내를 본다(역할 변경 때문이라는 문구는 없다).
7. 테이블 관리 화면의 설명 문구("등록하고 승인합니다… 수정할 수 있습니다")는 VIEWER 에게도 그대로 보인다.
8. `POST /api/insertDataDictionary/standardWords` 에 빈 본문을 보내면 500 이다(권한과 무관한 입력 검증 누락).
9. 비밀번호 초기화는 대상의 세션을 끊지 않는다.

## 10. 2차 안정화 (2026-11-12)

### 10.1 V3 마이그레이션

- 대상 : `lexorma-app/src/main/resources/db/migration/postgresql/V3__mapping_example_and_comments.sql`.
- 1차 작업 중(10:38) 작업 트리에서 39번째 줄(`TB_ATRB_COL_MPNG.ATRB_COL_MPNG_SN` 의 `insert into TB_COL`) 가운데에 줄바꿈과 공백이 들어가 있는 것을 발견했다. 공백·줄바꿈을 빼면 커밋된 내용과 같았고(SQL 의미 변화 없음, 인코딩·줄 끝 문자 변화 없음), 이번 작업의 도구가 만든 변경이 아니다(편집기에서 들어간 것으로 보인다).
- **Flyway 체크섬은 줄 단위로 계산하므로 달라진다** : 커밋된 파일 `1360113569`, 줄바꿈이 들어간 상태 `592152589`. V3 가 이미 적용된 DB 는 기동 때 검증(validate)에서 실패한다.
- 2차 작업을 시작했을 때는 파일이 이미 커밋된 내용으로 돌아가 있었다(11:13, 바이트 단위로 `HEAD` 와 같음, `git status` 에 나오지 않음). 그래서 **복원 작업은 하지 않았다.** V0~V4 다섯 파일 모두 `HEAD` 와 같다.
- 검증 (일회용 PostgreSQL 18.6 컨테이너, 볼륨 없음, 끝난 뒤 삭제) : 빈 DB 에 V0~V4 적용 성공 → 같은 DB 로 다시 기동해 "5 migrations validated, up to date". 이력 테이블의 체크섬은 V0 `-1532103848`, V1 `-514501166`, V2 `324210209`, V3 `1360113569`, V4 `650038310`.
- **확인하지 못한 것** : 개발 DB(`lexorma`, 15439)의 이력 테이블. 작업 시점에 컨테이너가 내려가 있었고 올리지 않았다. 개발 DB 가 커밋된 파일로 적용됐다면 위 값과 같아야 한다 — `select version, checksum from flyway_schema_history order by installed_rank` 로 비교할 수 있다. Flyway repair 는 실행하지 않았다.

### 10.2 데이터 사전 승인 정책

| | 전 | 후 |
|---|---|---|
| 서버 (`PATCH /api/approvalStandard{Domains,Words,Terms}/{true\|false}`) | DBA 역할이면 승인·승인취소 (승인 권한 확인 없음) | DBA 역할 **그리고** 승인 권한(ADMIN·MANAGER 역할 또는 로그인한 프로젝트의 관리자). 승인 권한이 없으면 400 `APPROVAL_PERMISSION_DENIED` (테이블·컬럼 승인과 같은 응답), 역할이 모자라면 403 |
| 화면 | `dictionary-approve`(DBA) 그리고 `approvalAvailable` | 그대로 — 이제 서버와 같다 |

- 승인·승인취소는 같은 API 다(경로의 true/false). 일괄 승인도 같은 API 에 여러 건을 보내는 것이라 따로 없다.
- 확인은 서비스(`DataDictionaryServiceImpl`)에 넣었다. 테이블·컬럼·논리 모델이 쓰는 `AuthUtil.isApprovalAvailable()` 그대로다.
- 프로젝트 격리는 전부터 있었다(로그인 프로젝트의 ID 만 바뀌고 나머지는 건너뛴다).
- "DBA 가 데이터 사전을 승인한다" 는 업무 정책은 문서 어디에도 없었다. 최소 역할 DBA 는 넓히지도 좁히지도 않았다.
- 승인된 항목의 수정·삭제 제한은 그대로다.

| 사용자 | 버튼 | API |
|---|---|---|
| NOT_APPROVE·VIEWER·USER | 없음 | 403 |
| DBA (승인 권한 없음) | 없음 | **400 `APPROVAL_PERMISSION_DENIED`** (전에는 200) |
| MANAGER·ADMIN | 승인·승인취소 | 200 |
| 프로젝트 관리자 + DBA | 승인·승인취소 | 200 |
| 프로젝트 관리자 + USER | 없음 (테이블·스키마 쪽 승인 버튼은 있음) | 403 (테이블 승인은 200) |
| 프로젝트 관리자 + VIEWER | 없음 | 403 |

### 10.3 VIEWER 의 컬럼 조회

| API | 전 | 후 |
|---|---|---|
| `GET /api/selectColumn/tableColumn/{tableId}` (테이블의 컬럼 목록 — 테이블 상세) | USER | **VIEWER** |
| `GET /api/selectColumn/data` (컬럼 목록 그리드) | USER | **VIEWER** |
| `GET /api/selectColumn/detail/{id}` | USER | USER 그대로 |
| `GET /api/selectColumn/table/{tableName}`, `/duplicate`, `/term/…`, `POST /term/resolve` | USER | USER 그대로 (등록 폼용) |
| 등록·삭제·일괄 저장·순서·승인·업로드 | USER (+ 승인) | 그대로 |

- `detail/{id}` 는 이름과 달리 "컬럼 상세" 가 아니라 **순서 변경 편집기용** 조회다(승인된 컬럼이 있으면 빈 목록을 준다). 그래서 열지 않았다. 컬럼의 상세 항목은 `tableColumn` 응답에 들어 있다.
- 다른 프로젝트·없는 테이블 ID 로 `tableColumn` 을 부르면 전에는 NullPointerException(500)이었다 → 400 `TABLE_IS_NOT_DEFINED`. `detail` 도 같다.
- 컬럼 관리 화면(메뉴)은 USER 그대로 둔다. 화면이 등록·수정용이고 등록 폼의 조회가 USER 다. VIEWER 용 조회 화면으로 열지는 P2.
- 화면 코드는 바꾸지 않았다. 테이블 상세가 이미 이 API 를 부르고 있어서, VIEWER 에게 "조회할 권한이 없습니다" 대신 컬럼 표가 나온다. 수정·컬럼 편집 버튼은 1차에서 막은 그대로다.

### 10.4 2차 테스트

| 실행 | 결과 |
|---|---|
| H2 `./gradlew clean build` (격리 복사본) | 380건, 실패 0, 건너뜀 2 (`V0SeedMigrationTest` 의 PostgreSQL 쪽, `UploadPerformanceTest`) |
| PostgreSQL 18.6 일회용 컨테이너, 전체 (`./gradlew test --rerun`) | 380건, 실패 0, 건너뜀 1 (`UploadPerformanceTest`) |

`UiActionAuthorizationIntegrationTest` 에 2건 추가(모두 6건) : 데이터 사전 승인·승인취소(도메인·단어·용어 × 승인 권한 없는 DBA·USER, 역할이 모자란 프로젝트 관리자, 다른 프로젝트의 ADMIN, MANAGER·ADMIN·DBA 인 프로젝트 관리자), 컬럼 조회(VIEWER 허용, 변경 API·폼용 조회 거절, 미승인 거절, 다른 프로젝트).

**직접 API 호출 + 헤드리스 Chrome** — 이번에는 격리 실행 서버를 **PostgreSQL**(위 컨테이너의 새 DB)에 붙였다.

- 10.2 의 표 전체를 실제 로그인 세션으로 확인(프로젝트 관리자 USER·VIEWER 는 프로젝트를 만들어 관리자를 넘겨서 준비).
- VIEWER : 테이블 상세에 컬럼 8건 표시, `tableColumn` 응답과 `/api/erd` 의 컬럼 이름이 같음, 변경 버튼 없음, 등록·삭제·일괄 저장·승인·순서 API 403.
- 승인 대기 데이터(MANAGER 가 API 로 단어·도메인·용어·테이블을 승인취소) : VIEWER 는 상세에 수정 없음 / USER·DBA 는 수정 있음, 승인 없음, 테이블의 컬럼 편집은 비활성 / MANAGER·ADMIN 은 승인 + 수정.
- 승인 완료 데이터 : 모든 역할에 수정 없음, MANAGER·ADMIN 에게 승인취소.
- 그 뒤 ADMIN 이 API 로 다시 승인(200).

**확인하지 못한 것** : 승인·승인취소를 화면 버튼으로 눌러 처리하는 것(버튼 표시와 같은 API 의 직접 호출까지만), 개발 DB 의 Flyway 이력, Chrome 이외 브라우저, 엑셀 업로드·다운로드의 실제 조작.

### 10.5 P2 후속 과제 (이번에 구현하지 않음)

| 항목 | 내용 |
|---|---|
| VIEWER 엑셀 다운로드 | `/api/downloadTemplate/**` 는 클래스 단위로 USER 다. `dictionary` 는 화면에서 조회되는 데이터 사전을 그대로 내려받는 것이라 조회와 같은 성격이고, 나머지는 빈 양식·공공 표준 파일이다. VIEWER 에게 열려면 서버 역할과 화면(`dictionary-edit` 로 묶은 버튼)을 함께 바꿔야 한다. 민감 정보 여부를 먼저 확인 |
| 프로젝트 관리 메뉴 | 관리하는 프로젝트가 없는 DBA·MANAGER·ADMIN 에게 메뉴가 보이지만 화면은 "접근 불가". 메뉴를 숨길지, 안내 화면(프로젝트 등록 포함)을 둘지, 메뉴 권한과 관리 권한을 나눌지. **ADMIN 에게 모든 프로젝트의 관리 권한을 자동으로 주지 않는다** |
| 표준 단어 빈 본문 500 | `POST /api/insertDataDictionary/standardWords` 에 빈 본문 → 500. 입력 검증을 넣어 400 으로 |
| 데이터 사전 승인의 최소 역할 | 데이터 사전 DBA, 테이블·컬럼·논리 모델 USER. 맞출지 결정 |
| 컬럼 관리 화면의 VIEWER 조회 | 조회 API 는 열렸다. 화면을 VIEWER 에게 열려면 변경 버튼을 액션 권한으로 묶어야 한다 |
| 역할 변경 안내 문구, 비밀번호 초기화 시 세션 | 9장 6·9번 |
| 브라우저 추가 검증 | 승인 버튼을 눌러 처리하는 흐름, Chrome 이외 브라우저, 엑셀 업로드·다운로드 실제 조작, 터치 |

### 10.6 M-9 착수

P1 로 남은 문제는 없다. 위 표는 모두 P2 이고 M-9(모델 변경 이력)의 설계와 겹치지 않는다. 개발 DB 를 다시 올릴 때 V3 체크섬만 한 번 확인하면 된다(10.1).

## 11. 복구 세션 (2026-11-12, 프로젝트 복구 로그인)

활성 프로젝트가 하나도 없을 때 시스템 ADMIN 이 프로젝트 없이 로그인하는 **복구 세션**이 생겼다 ([기록](project-recovery-login.md)). 이 문서의 권한 매트릭스와의 관계 :

- 복구 세션의 권한은 `ROLE_RECOVERY` 하나이고 역할 계층 밖이다. 위 매트릭스의 어떤 화면·액션도 쓸 수 없다 (`/api/auth/me` 의 `menus` 전부 `false`, `approvalAvailable` `false`).
- 서버는 `SecurityConfig` 에서 복구 세션의 `/api/**` 를 `/api/recovery/**`(프로젝트 목록·활성화)만 남기고 막는다. 반대로 `/api/recovery/**` 는 일반 세션(ADMIN 포함)이 쓸 수 없다.
- 프로젝트 수정(`PUT /api/saveProject/project`)에 마지막 활성 프로젝트 확인(`confirmLastActive`)이 추가됐고, 사용자 관리의 권한 변경은 마지막 활성 ADMIN 을 강등할 수 없다.

## 11. 추가 화면 (M-9 2차)

| 화면 | 액션 | 서버 | 화면 |
|---|---|---|---|
| 모델 변경 이력 (`/modelHistory`, 메뉴 `model-history`) | 목록·객체별·그룹·상세 조회 | VIEWER, 이력의 프로젝트 = 로그인 프로젝트 | 메뉴·화면 VIEWER 부터. 변경 버튼 없음 (쓰기 API 가 없다) |

NOT_APPROVE 는 메뉴가 없고 API 가 403 이다. 복구 세션은 화면이 복구 화면으로 돌아가고 API 가 403 이다. 테이블 상세·엔터티 상세의 [변경 이력] 버튼은 `canView('model-history')` 일 때만 나온다.

