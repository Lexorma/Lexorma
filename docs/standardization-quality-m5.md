# M-5 2차. 표준화 품질 진단 — 설계 결정과 구현 기록

> [데이터 모델링 로드맵](data-modeling-roadmap.md) M-5 중 **품질 진단(2차)** 의 기록이다. 1차(사용 현황 조회)는 [standardization-usage-m5.md](standardization-usage-m5.md).
> 작성일 2026-11-11. DB 스키마 변경 없음 (Flyway `V0__init.sql` 그대로). 조회 전용 — 컬럼·표준 항목·승인 상태를 바꾸지 않는다.

## 1. 기존 정책 (분석)

| 확인 항목 | 결과 → 진단 기준 |
|---|---|
| 컬럼명 규칙 | 컬럼명 = 연결한 표준 용어의 **영문약어 그대로** (`ColumnManageServiceImpl` 단건 등록·일괄 저장, 화면은 읽기 전용 입력). 엑셀 업로드는 컬럼명으로 약어를 찾아(대문자 → 원문 순) 용어 ID 를 저장한다. 다른 생성 규칙은 없다 |
| 대소문자 | 약어 조회는 대소문자를 구분하지 않는다 (CLAUDE.md "Abbreviation lookups are case-insensitive", `resolveTerms`·업로드) → 비교도 대소문자 무시, 구분자 `_` 는 그대로 비교 |
| 용어 변경 시 컬럼 반영 | **없다.** 용어 약어를 고쳐도 이미 만든 컬럼명은 그대로다 → 컬럼명 불일치가 생기는 주된 경로 |
| 도메인 정의 | `StandardDomain` : 데이터 타입(`dataType`), 길이(`dataLength`), 소수점 길이(`dataDecimalLength`). **정밀도 항목은 따로 없다** — 숫자 타입에서는 길이가 정밀도다. 길이 0 은 미정의로 다룬다 (`StandardTermDto` 가 0 이면 길이를 붙이지 않는다) |
| 컬럼 정의 | `ColumnInfo` : `dataType`, `maxLength`, `precision`, `scale`. 용어를 고르면 화면이 도메인 타입·길이를 채워 주지만 사용자가 바꿀 수 있고, 저장 때 도메인과 비교하지 않는다 |
| 타입 매핑 규칙 | `TableDdlGenerator.mapType` 하나뿐이다 (공통 타입명 → 프로젝트 `DbType` 타입, `VARCHAR(100)` 처럼 타입에 적은 길이 해석, 숫자는 정밀도가 없으면 최대 길이 사용). FK 타입 경고(`checkReferenceType`)도 이 결과를 비교한다 → 도메인 비교도 **같은 함수**를 쓴다 |
| 표준화 예외 컬럼 정책 | 없다 → 예외를 만들지 않았다 (공통 컬럼 세트의 컬럼도 일반 컬럼과 같이 진단한다) |
| 승인 상태 | 참조·정의에 영향이 없는 플래그 → 판정에 쓰지 않고 조회 조건으로만 제공 |
| 관리 범위·권한 | 컬럼·표준 항목 모두 프로젝트 단위. 컬럼 정보 조회는 물리 ERD·사용 현황이 VIEWER |

## 2. 판정 기준

컬럼 1개에 진단 유형별로 최대 1건이 나온다 (한 컬럼에 컬럼명 불일치와 도메인 불일치가 함께 나올 수 있다).

| 유형 | 기준 | 위반으로 보지 않는 경우 |
|---|---|---|
| `TERM_NOT_LINKED` 표준 용어 미연결 | `TB_COL.STD_TRM_SN` 이 null | — (이름이 같은 용어가 이 프로젝트·다른 프로젝트에 있어도 연결로 보지 않는다) |
| `COLUMN_NAME_MISMATCH` 컬럼명 불일치 | 컬럼명 ≠ 연결된 용어의 영문약어 (앞뒤 공백 제거, 대소문자 무시) | 용어에 영문약어가 없다 (기대 컬럼명을 알 수 없다) |
| `DOMAIN_DEFINITION_MISMATCH` 도메인 정의 불일치 | 아래 | 용어에 도메인이 없다 / 도메인에 정의된 항목이 없다 |

**도메인 비교 :** 컬럼과 도메인을 각각 `mapType` 으로 프로젝트 DB 타입 문자열로 바꾼다.

1. 두 결과가 같으면 일치 (표기만 다른 `VARCHAR2` ↔ `CHARACTER VARYING`, Oracle 의 `INT` ↔ `NUMBER(10)` 등).
2. 도메인에 타입이 있고 기본 타입(괄호 앞)이 다르면 **데이터 타입** 불일치. 이때 길이는 비교하지 않는다 (타입이 다르면 길이의 뜻이 다르다).
3. 타입이 같으면(또는 도메인에 타입이 없으면) 도메인에 **정의된 항목만** 비교한다 : 길이가 있으면 첫 번째 인자 — 숫자 타입(`NUMBER`·`NUMERIC`·`DECIMAL`)은 **정밀도**, 그 밖은 **길이** — , 소수점 길이가 있으면 **소수 자릿수**(없음 = 0).

실제 값·기대 값은 비교에 쓴 DB 타입 문자열이다 (예: `VARCHAR(20)` / `VARCHAR(100)`, 타입 불일치는 `BIGINT` / `NUMERIC`). 응답의 `attributes` 에 다른 속성(`DATA_TYPE`·`LENGTH`·`PRECISION`·`SCALE`)이 들어 있다.

**구분되는 세 경우 :** 실제 불일치(진단 결과) / 비교할 정의가 없음(도메인 없음·항목 미정의 → 결과 없음) / 비교를 지원하지 않음(`mapType` 이 해석하지 못하는 타입 — 예: `ENUM('A','B')` — 은 괄호 앞 문자열만 비교하고 길이는 보지 않는다).

## 3. 백엔드 (`metamora-data` `dictionary/`)

| 구성 | 내용 |
|---|---|
| `StandardizationQualityRestController` (`/api/standardizationQuality`, VIEWER) | `GET /summary` : `totalColumns`·`issueColumns`(문제가 있는 고유 컬럼 수)·`termNotLinked`·`columnNameMismatch`·`domainDefinitionMismatch`. `GET /data` : 그리드 조회 규격(`page`·`size`·`sort`·`searchColumn`·`searchQuery`) + 조건 `type`·`schemaId`·`isApproval`, 검색 `tableName`·`columnName`. 잘못된 `type` 은 400 |
| `StandardizationQualityService` | 읽기 전용 트랜잭션. 프로젝트는 항상 로그인 프로젝트 (`AccountDto`), 요청으로 받지 않는다. 요약과 목록이 **같은 `diagnose`** 를 쓴다 |
| `DictionaryUsageRepository.findQualityColumns` | 사용 현황과 같은 조인에 용어·도메인만 left join 으로 바꾼 DTO 프로젝션 1번 (엔티티·N+1 없음) |
| `TableDdlGenerator.mapType`·`baseType` | `public` 으로만 바꿨다 (로직 변경 없음) |
| `MenuAuthorization` | `standardization-quality` = VIEWER, `data-management` 그룹 |

**조회 방식 :** 저장 테이블·배치 없이 조회할 때 계산한다. 타입 매핑이 자바 코드라 SQL 로 걸러낼 수 없어, 프로젝트의 컬럼 전체(필요한 22개 값)를 한 번에 읽고 메모리에서 진단·조건·정렬·페이징한다. 요약 1번 + 목록 1번 = 화면당 조회 2번. 정렬은 허용한 필드(`type`·`schemaName`·`tableName`·`columnName`·`columnDesc`·`isApproval`)만 받고, 같은 값은 테이블명 → 컬럼 순서 → 진단 유형 순이다.

## 4. 프론트엔드

**화면 구조 :** 데이터 사전의 탭이 아니라 **독립 화면** `/standardizationQuality` (데이터 관리 > 표준화 품질 진단). 데이터 사전 탭은 표준 항목 하나를 기준으로 보는 곳이고, 진단은 프로젝트의 모든 테이블·컬럼을 가로지르는 목록이라 기존 메뉴와 겹치지 않는다.

| 구성 | 내용 |
|---|---|
| `features/quality/StandardizationQualityPage.tsx` | 요약 카드 5개(검사 대상 / 문제가 있는 컬럼 / 유형 3개). 유형 카드를 누르면 그 유형만, [문제가 있는 컬럼] 을 누르면 전체. 스키마·승인 상태 조건, 테이블명·컬럼명 검색, 정렬·페이징은 공통 `Grid` |
| `features/quality/columns.ts` | 테이블명·진단 유형·스키마·컬럼명·논리명·실제 값·기대 값·문제 설명·승인여부·이동 |
| 행의 이동 | 사용 현황의 버튼을 `TableLinks`(`DictionaryUsage.tsx`)로 꺼내 함께 쓴다. **[테이블 상세]** : 테이블 관리와 같은 상세 대화상자, 해당 컬럼 행 강조(`useTableDetail` 의 `columnId` 옵션 — 사용 현황에서 열 때도 강조된다). 상세에서 컬럼을 고치면 요약·목록을 다시 조회한다. **[ERD]** : `/physicalErd?schemaId=&tableId=` (M-3 방식) |
| 빈 상태·오류 | 조건 없이 비면 "진단 결과가 없습니다. 모든 컬럼이 표준을 따르고 있습니다.", 조건이 있으면 "조건에 맞는 진단 결과가 없습니다.", 요약 조회 실패 시 안내 문구 (+ 공통 오류 알림) |
| 스타일 | 대시보드 카드(`dash-stat`)와 기존 토큰을 그대로 쓴다. 추가 CSS 는 카드 5열·선택 표시·강조 행뿐 |

## 5. 검증

```bash
./gradlew clean build      # 332 tests, 실패 0, 건너뜀 2 (환경 변수가 필요한 테스트). npm ci + typecheck + vite build 포함
# PostgreSQL 18.6 : 빈 격리 DB(metamora_m5q_test)를 만들어 실행 후 삭제 (개발 DB 는 건드리지 않았다)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/metamora_m5q_test \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
./gradlew :metamora-app:test -PfrontendPrebuilt=true \
  --tests 'com.koboolean.metamora.StandardizationQualityIntegrationTest' --tests 'com.koboolean.metamora.DictionaryUsageIntegrationTest' \
  --tests 'com.koboolean.metamora.SmokeTest' --tests 'com.koboolean.metamora.ReferenceSyncIntegrationTest' \
  --tests 'com.koboolean.metamora.SchemaManagementIntegrationTest' --tests 'com.koboolean.metamora.PhysicalModelIntegrationTest' \
  --tests 'com.koboolean.metamora.ErdIntegrationTest' --tests 'com.koboolean.metamora.DataPortalTemplateTest' \
  --tests 'com.koboolean.metamora.TermUploadNameGenerationTest'      # 90 tests, 실패 0
```

### 5.1 자동 테스트

| 항목 | H2 | PostgreSQL 18.6 |
|---|---|---|
| `StandardizationQualityServiceTest` 5건 (판정 규칙) : 미연결, 컬럼명(일치·대소문자·구분자·약어 없음), 타입(표기만 다름·DB 별 동등·불일치), 길이·정밀도·소수 자릿수, 일부만 정의된 도메인, 도메인 없음, 한 컬럼에 여러 문제 | 통과 (DB 불필요) | — |
| `StandardizationQualityIntegrationTest` 2건 : 요약(문제 3건·컬럼 2개), 목록 = 요약, 유형·스키마·승인·검색 조건, 페이징·정렬(허용하지 않은 필드 무시), 조회 후 데이터 불변, 다른 프로젝트 격리(같은 약어의 용어가 있어도 미연결), VIEWER 허용·NOT_APPROVE 거절(컨트롤러 메서드 보안) | 통과 | 통과 |
| `SmokeTest` 60건 (HTTP) : `standardizationQuality`(V0 예제에서 요약 = 유형별 목록 건수, 없는 스키마 0건, 잘못된 `type`·`page` 누락 400, 메뉴 권한), `gridSortAndSearch`(품질 진단 그리드 포함) | 통과 | 통과 |
| 회귀 : `DictionaryUsageIntegrationTest` 1, `ReferenceSyncIntegrationTest` 6, `SchemaManagementIntegrationTest` 4, `PhysicalModelIntegrationTest` 4, `ErdIntegrationTest` 7, `DataPortalTemplateTest` 3, `TermUploadNameGenerationTest` 3 | 통과 | 통과 |
| 나머지 모듈 단위 테스트 (데이터 사전·테이블·컬럼·DDL·엑셀 등) | 통과 | — |
| `npm run typecheck`, `npm run build` | 통과 | — |

### 5.2 브라우저 검증 (HTTP·빌드 결과와 별개)

헤드리스 Chrome 을 DevTools 프로토콜로 직접 조작했다 (의존성 추가 없음). 대상은 빌드한 `metamora.jar` 를 `h2-local`(인메모리)·포트 19940 으로 따로 띄운 인스턴스다. **22개 확인 모두 통과, 콘솔 오류 0** (HTTP 4xx 는 의도한 2건 : 초기 비밀번호 차단 403, 승인 전 사용자의 API 403).

| 구분 | 확인한 것 |
|---|---|
| 인증·공통 (`AuthProvider` 가 `DialogProvider` 를 감싼 뒤) | 비로그인 → 로그인 화면, 초기 비밀번호 변경 대화상자 → 로그인, 새로고침 후 로그인 유지, 로그아웃 후 보호 화면 차단, ADMIN 전체 메뉴 / 승인 전 가입자는 대시보드·공지만 + 품질 진단 API 403, 사이드바 이동(React Router), 알림·확인 대화상자 |
| M-3 스키마 관리 | 목록, 기본 스키마 삭제 비활성, 등록·수정·삭제, 테이블 관리의 스키마 필터, 테이블 등록 폼의 스키마 선택, 테이블 상세 → ERD 이동·선택, ERD 컬럼 상세 대화상자, 테이블을 옮겨 [배치 저장] → 새로고침 후 위치 유지 |
| M-5 1차 사용 현황 | 사용 건수 열, 사용 현황 대화상자, 그 위에 테이블 상세 겹쳐 열기 → 상세만 닫힘 → 모두 닫힘, 단어 → 용어 → 컬럼 팝업(2중) → ERD 이동 시 모두 닫힘·노드 선택 |
| M-5 2차 품질 진단 | 요약 카드 숫자, 카드로 유형 필터(0건 유형의 빈 안내 포함), 테이블명 검색·없는 검색어·다음 페이지, 실제 값·기대 값, 테이블 상세(해당 컬럼 강조) + DDL 팝업 중첩·차례로 닫기, ERD 이동·포커스 |
| 다크 모드 (`prefers-color-scheme: dark`) | 품질 진단 화면·카드·그리드 글자, 테이블 상세 대화상자·강조 행, 사용 현황 대화상자의 배경·글자 밝기 + 화면 캡처 확인 |

**브라우저 미검증 (완료로 보지 않음)**

- VIEWER·USER·DBA·MANAGER 계정 화면 (ADMIN 과 승인 전 가입자만 확인. VIEWER 허용은 컨트롤러 수준 테스트만 있다).
- 세션 만료 알림(401 → 로그인 이동), 프로젝트 접근 해제 알림.
- 영어 화면, 좁은 화면(1012px 이하), Chrome 이외 브라우저, 키보드만으로 조작.
- PostgreSQL 을 붙인 상태의 화면 (브라우저 검증은 H2 인메모리로만 했다).
- 엑셀 업로드·다운로드, 컬럼 일괄 편집, 공지사항 등 이번에 손대지 않은 화면.
- 요약 조회 실패 문구 (오류 상황을 만들지 않았다).

## 6. 확인된 사실과 제약

- **V0 예제 데이터 : 269개 컬럼 중 215개가 도메인 정의 불일치로 나온다** (미연결 0, 컬럼명 불일치 0). MetaMora 자체 테이블의 실제 타입이 공공 표준 도메인과 다르기 때문이다 — `VARCHAR(255)` ↔ 명V256 67건, `BIGINT` ↔ 일련번호N22(`NUMERIC`) 66건, `BOOLEAN` ↔ 여부C1(`CHAR`) 29건, `INTEGER` ↔ 수N10 15건 등. 판정은 맞지만 예제로는 시끄럽다. 예제 데이터를 표준에 맞출지, 동등 타입(여부 ↔ BOOLEAN, 일련번호 ↔ BIGINT)을 정책으로 인정할지는 정하지 않았다 (임의로 새 규칙을 만들지 않았다).
- 도메인에 정밀도 항목이 없어 숫자 타입의 길이를 정밀도로 본다.
- 길이가 없는 도메인은 길이를 비교하지 않는다. "도메인 길이보다 큰 컬럼만 위반" 같은 방향성 규칙은 없다 — 다르면 불일치다.
- **대량 데이터 :** 프로젝트 컬럼 전체를 매번 읽는다. 수천~수만 컬럼은 문제없을 크기지만 실행 시간·메모리를 재지 않았다. 수십만 건이면 진단 결과 저장(AI-6)이나 SQL 로 걸러낼 수 있는 유형(미연결·컬럼명)의 분리가 필요하다.
- 테이블명 규칙(접두어·승인 단어), 용어 중복·유사어, 비표준 컬럼명 개선안 후보는 이번 범위가 아니다 (로드맵 M-5 잔여).
- `MenuAuthorization.MENU_GROUPS` 의 `operation-modeling` 키는 화면의 그룹 ID `data-modeling` 과 다르다 (`MENU_ROLES` 의 `data-modeling` 항목 덕분에 동작한다). 기존 상태 그대로 두었다.
