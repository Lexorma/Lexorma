# M-3. ERD ↔ 메타데이터 관리 연동 및 기본 스키마 관리 UI — 설계 결정과 구현 기록

> [데이터 모델링 로드맵](data-modeling-roadmap.md) M-3 의 설계·구현 기록이다. 선행 기록은 [M-1](physical-model-m1.md), [M-2](physical-erd-m2.md).
> 작성일 2026-11-11. DB 스키마 변경 없음 (Flyway `V0__init.sql` 그대로).

## 1. 범위

- 구현 : 스키마 관리 화면(목록·등록·수정·삭제), 테이블 관리의 스키마 열·필터·등록 시 선택·상세 표시, 컬럼 관리의 소속 스키마 표시, 물리 ERD ↔ 테이블 관리·컬럼 관리·데이터 사전 상호 이동, 기본 스키마 이름 고정.
- 구현하지 않음 : 기존 테이블의 스키마 이동 UI, 기본 스키마 변경, 스키마별 동일 테이블명(D-03 후속), ERD 편집(M-8), AI 설명·추정 관계 표시(M-13), 엑셀 템플릿의 스키마 열.

## 2. 로드맵·지시와 실제 코드의 차이

| 항목 | 실제 | 처리 |
|---|---|---|
| "Flyway V2 물리 모델 마이그레이션" | 마이그레이션은 `V0__init.sql` 하나로 합쳐져 있다 | 변경 없음 (M-3 은 DB 변경이 없다) |
| ERD 컬럼 상세에 "기존 컬럼 상세 조회 API 재사용" | `GET /api/selectColumn/detail/{id}` 는 USER 권한이고, 같은 테이블의 승인 전 컬럼 목록(순서 변경용)을 돌려준다. ERD 는 VIEWER 화면이다 | 재사용하지 않았다. ERD 조회 응답(`ErdDto.Column`)에 표준 도메인명 `domainName` 을 더해 이미 받은 데이터로 상세를 그린다 (추가 요청·권한 상승 없음) |
| 테이블 ID 로 테이블 1건 조회 | 없었다 (목록 API 만) | `GET /api/selectTable/detail/{tableId}` (VIEWER) 추가 — ERD 에서 테이블 상세 대화상자를 열 때 쓴다 |
| 권한 부족 오류 코드 | 스키마 등록·수정에도 `DATA_CANNOT_BE_DELETED` ("삭제할 수 없음") | `SCHEMA_PERMISSION_DENIED` 신설 |
| 기본 스키마 이름 변경 | API 로 가능했다 | 서버에서 거절 (`INVALID_SCHEMA_NAME` + 사유). 설명은 수정 가능. 이미 이름이 바뀐 기본 스키마가 있어도 그 이름으로 고정될 뿐 데이터는 바뀌지 않는다 |
| 테이블 목록 검색 | 모르는 `searchColumn` 에 검색어가 오면 NPE(500) | 검색 조건을 무시하고 조회 |

## 3. 백엔드

| 구성 | 내용 |
|---|---|
| `SchemaDto` | 목록 전용 필드 `tableCount`·`created`·`updated`·`createdBy`·`updatedBy` 추가. 4개 인자 생성자를 남겨 저장 요청·기존 코드는 그대로 |
| `SchemaInfoRepository.findSchemaList` | 스키마 + 소속 테이블 수를 한 쿼리로 조회 (기본 스키마 먼저, 이름순) |
| `SchemaServiceImpl` | 목록은 위 쿼리 사용(기본 스키마 자동 생성은 유지), 기본 스키마 이름 변경 거절, 권한 오류 코드 변경 |
| `GET /api/selectTable/data` | 선택 파라미터 `schemaId` (없으면 전체). 조건은 `Specification` 하나로 합쳤고(`TableInfoRepository` 가 `JpaSpecificationExecutor` 확장), 정렬 필드 `schemaName` 은 `schema.schemaName` 으로 바꾼다 |
| `GET /api/selectTable/ddl/{tableId}` | 선택 파라미터 `defaultSchemaName` : 기본 스키마 테이블(및 FK 가 참조하는 기본 스키마 테이블)에 붙일 스키마명. 스키마명 규칙으로 검증(`INVALID_SCHEMA_NAME`). 없으면 기존 DDL 과 같다. 화면은 DDL 보기의 "스키마명 포함" 옵션 (저장하지 않는 보기 옵션) |
| `GET /api/selectTable/detail/{tableId}` | VIEWER. ERD 에서 테이블 상세를 열 때와 상세를 다시 열 때 쓴다. 다른 프로젝트·없는 ID 는 `TABLE_IS_NOT_DEFINED` |
| `ColumnInfoDto` | 조회 전용 `tableInfoId`·`schemaId`·`schemaName` (테이블의 스키마를 읽는다. 컬럼에는 저장하지 않는다). 지연 로딩을 읽으므로 그리드·중복 조회 메서드에 읽기 전용 트랜잭션을 붙였다 |
| `ErdDto.Column.domainName` | 표준 용어의 표준 도메인명 (left join, 쿼리 수 그대로). 용어가 없거나 삭제됐으면 null |
| `MenuAuthorization` | `schema-manage` = VIEWER (목록 API 와 같다), 데이터 관리 그룹에 추가 |

프로젝트는 항상 `accountDto.getProjectId()` 로 정한다. 스키마 ID 는 `findByIdAndProjectId`, 테이블 목록의 `schemaId` 는 프로젝트 조건과 함께 걸려 다른 프로젝트 값이면 결과가 없다.

## 4. 프론트엔드

| 구성 | 내용 |
|---|---|
| `features/schema/SchemaManagePage.tsx` (`/schemaManage`) | 목록(스키마명·기본 표시·설명·테이블 수·등록일·수정일·등록자·수정자). 승인 권한자에게만 추가·수정·삭제 버튼. 기본 스키마는 이름 입력을 잠그고, 기본·사용 중 스키마는 삭제 버튼을 비활성화(사유 툴팁). 삭제 전 확인 |
| `features/schema/schemas.ts` `useSchemas` | 스키마 목록 캐시 키 `['schemas', projectId]` — 스키마 관리·테이블 관리·물리 ERD 가 같이 쓴다. 변경 후 `refresh` 로 무효화 |
| `Grid` | `params`(조회 조건, 바뀌면 첫 페이지), `searchFilters`(검색 줄 앞 조건), `initialSearch`(처음 검색 조건) 추가 |
| 테이블 관리 | 스키마 열(정렬 가능)·스키마 필터, 등록 폼의 스키마 선택(기본 스키마 자동 선택, 수정은 표시만), 상세의 스키마·[ERD 에서 보기]·[승인]/[승인취소](승인 권한자, 상태에 따라 하나만). 상세 대화상자는 `features/table/tableDetail.tsx` 의 `useTableDetail` 로 물리 ERD 와 같이 쓴다 |
| 컬럼 관리 | 스키마 열·상세의 스키마, [ERD 에서 보기] |
| 데이터 사전 | `?tab=standardTerms\|standardDomains\|standardWords&searchColumn=&searchQuery=` 로 열면 그 탭을 검색한 상태 |
| 물리 ERD | 상세 패널에 [DDL 보기](기존 `TableDdlView`)·[테이블 상세]·[컬럼 편집] — 화면을 옮기지 않고 테이블 관리와 같은 대화상자(상세·승인·수정, 컬럼 일괄 편집)를 ERD 위에 열고, 바뀌면 그래프를 다시 받는다(배치 유지). 컬럼을 누르면 컬럼 상세(논리명·타입·PK/FK·표준 용어·표준 도메인·설명)와 [데이터 사전에서 보기]. `?schemaId=&tableId=` 로 열면 그 스키마를 조회하고 테이블을 선택해 가운데로 옮긴다 (그 페이지에 없으면 해당 테이블의 이웃 조회로 전환). 데이터 사전으로 이동할 때 저장하지 않은 배치가 있으면 먼저 확인한다 |

**ERD 동기화 :** 화면을 옮길 때마다 ERD 가 스키마 목록·그래프를 다시 받는다(캐시 유효 시간 0). 배치(`TB_ERD_LYT`)는 테이블 ID 기준이라 스키마 이름 변경·테이블 수정에 영향받지 않는다.

**이동 링크의 노출 :** 대상 메뉴 권한(`canView`)이 있을 때만 보인다 (예: VIEWER 에게는 컬럼 관리 링크가 없다).

## 5. 정책

- 기본 스키마 : 프로젝트당 1개, 삭제 불가, 이름 고정, 기본 스키마 변경 기능 없음. 최초 생성의 행 잠금(M-2)은 그대로.
- 스키마 변경 권한 : USER 역할 + 승인 권한(관리자·매니저·프로젝트 관리자). 화면은 버튼을 숨기고 서버가 다시 검증한다.
- 삭제 : 기본 스키마·테이블이 있는 스키마는 거절. 테이블을 지우거나 옮기지 않는다.
- 스키마 이동 : 화면에서 제공하지 않는다. `PUT /api/saveTable` 의 `schemaId` 동작은 M-1 그대로다 (후속에서 정책 결정).
- 호환 : `schemaId` 를 보내지 않는 등록·엑셀 업로드는 기본 스키마, 수정은 현재 스키마 유지. 기존 응답 필드는 그대로이고 필드만 늘었다.

## 6. 검증

```bash
./gradlew clean build      # 314 tests, 실패 0, 건너뜀 2 (환경 변수가 필요한 테스트)
# PostgreSQL 18.6 : 빈 격리 DB(metamora_m3_test)를 만들어 실행 후 삭제
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/metamora_m3_test \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
./gradlew :lexorma-app:test -PfrontendPrebuilt=true \
  --tests 'com.lexorma.SchemaManagementIntegrationTest' --tests 'com.lexorma.PhysicalModelIntegrationTest' \
  --tests 'com.lexorma.ErdIntegrationTest' --tests 'com.lexorma.SmokeTest'
```

| 항목 | H2 | PostgreSQL 18.6 |
|---|---|---|
| `SchemaManagementIntegrationTest` 4건 : 목록(기본 먼저·테이블 수·등록 정보), 대소문자 무관 중복 차단, 이름 변경 후 ID·소속 테이블 유지와 DDL 접두어 변경, 기본 스키마 이름 변경·삭제 차단, 사용 중 스키마 삭제 차단, 스키마별 테이블 조회·검색·스키마명 정렬, 컬럼의 소속 스키마, 용어 없는 컬럼의 ERD 조회, 예제 데이터의 용어·도메인, USER·VIEWER 변경 차단, 다른 프로젝트 스키마·테이블 ID 차단, 다른 프로젝트의 같은 스키마명 허용 | 통과 | 통과 |
| `SmokeTest` 58건 (HTTP) : `schemaManagement`(목록 → 등록 → 중복 → 이름 변경 → 스키마별 테이블 조회 → 테이블 1건 조회 → 기본 스키마 보호 → 삭제), `gridSortAndSearch`(모든 그리드의 정렬 × 검색, 스키마 열 포함), `/schemaManage`·`/physicalErd` 화면 경로 | 통과 | 통과 |
| `ErdIntegrationTest` 7건, `PhysicalModelIntegrationTest` 4건 (회귀 : 조회·쿼리 수·레이아웃·DDL·격리) | 통과 | 통과 |
| `MenuAuthorizationTest` (schema-manage) 등 나머지 모듈 테스트 | 통과 | — |
| `npm run typecheck`, `npm run build` | 통과 | — |

**미검증 (완료로 보지 않음)**

- 브라우저 화면 동작 : 스키마 관리 화면, 테이블 관리의 스키마 필터·등록 폼, ERD 의 이동 버튼·컬럼 상세·`?schemaId=&tableId=` 포커스, 데이터 사전 딥링크, 다크 모드·좁은 화면. 자동 테스트는 API 와 화면 경로(200)까지만 본다.
- HTTP 수준의 역할별 차단 : VIEWER·USER 계정으로 로그인해 호출하는 테스트는 없다. 서비스의 승인 권한 검사는 테스트했고, 컨트롤러의 `@PreAuthorize` 는 M-1 그대로다.
- 엑셀 업로드 회귀는 기존 테스트 범위 그대로다 (M-3 에서 업로드 코드는 바꾸지 않았다).
- AI 미설정 상태 : 이 저장소에는 AI 연동이 없어 ERD 는 AI 와 무관하게 동작한다. 별도 시나리오는 없다.

## 7. 후속

| 항목 | 내용 |
|---|---|
| 스키마 이동 | 테이블의 스키마 이동 UI 와 승인된 테이블의 이동 허용 여부 (API 는 가능). 로드맵 M-3 미확정 사항에 남김 |
| 스키마명 유니크 인덱스 | 서비스는 대소문자 무관 중복을 막지만 `UX_SCHM_01` 은 대소문자를 구분한다. 동시 등록 경합 시 대소문자만 다른 이름이 들어갈 수 있다 |
| `GET /api/schema` 의 쓰기 | 기본 스키마가 없으면 조회가 만든다 (VIEWER 조회가 쓰기 트랜잭션). 유지 |
| 메뉴 이동 경고 | ERD 의 이동 버튼은 저장하지 않은 배치를 확인하지만, 사이드바 메뉴 이동은 여전히 경고 없이 버려진다 (M-2 후속 그대로) |
| 메뉴 그룹 ID | `MenuAuthorization.MENU_GROUPS` 의 `operation-modeling` 과 화면의 `data-modeling` 이 다르다. 동작에는 영향이 없다 (`MENU_ROLES` 로 판단) |
| D-03 | 스키마별 동일 테이블명·엑셀 스키마 열 (변경 없음) |
