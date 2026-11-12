# M-5 1차. 데이터 표준화 사용 현황 조회 — 설계 결정과 구현 기록

> [데이터 모델링 로드맵](data-modeling-roadmap.md) M-5 중 **사용 현황 조회(1차)** 의 기록이다. 명명 규칙 검증·표준화 품질 진단(2차)은 하지 않았다.
> 작성일 2026-11-11. DB 스키마 변경 없음 (Flyway `V0__init.sql` 그대로).

## 1. 기존 구조 (분석)

```
표준 단어 ─(TB_STD_TRM_WORD : 용어·단어·순서)─ 표준 용어 ─(TB_COL.STD_TRM_SN)─ 컬럼 ─ 테이블 ─ 스키마
                                              표준 용어 ─(TB_STD_TRM.STD_DMN_SN)─ 표준 도메인
```

| 확인 항목 | 결과 |
|---|---|
| 컬럼 → 표준 용어 | ID 참조 (`ColumnInfo.standardTerms`, FK `FK_COL_02`). 컬럼 등록·일괄 저장·엑셀 업로드 모두 용어를 연결한다. 용어 없는 컬럼도 있을 수 있다(nullable) |
| 컬럼 → 표준 도메인 | **직접 참조 없음.** 용어를 거친 간접 연결만 있다. 컬럼은 데이터 타입·길이를 자기 값으로 따로 가진다 |
| 표준 단어 ↔ 표준 용어 | 매핑 테이블 `TB_STD_TRM_WORD`(`StandardTermWordMapping`). 용어 화면 등록·엑셀 업로드가 만들고, V0 예제 데이터에도 있다. 한 용어에 같은 단어가 두 번 들어갈 수 있다 |
| 표준 단어 → 컬럼 | 직접 연결 없음 (단어 → 용어 → 컬럼) |
| 이름 일치로 연결을 추정하는 로직 | 없음. 엑셀 업로드는 컬럼명(용어 영문약어)으로 용어를 찾아 **ID 로 저장**한다 |
| 승인 상태에 따른 연결 차이 | 없음 (승인은 플래그일 뿐 참조는 같다) |
| 관리 범위 | 표준 단어·용어·도메인 모두 프로젝트 단위(`PRJ_SN`) |
| 권한 | 목록 조회 VIEWER, 등록·수정·삭제 USER, 승인 DBA |
| 기존 사용 정보 | 삭제 차단용 검사만 있었다 (용어가 있는 단어·도메인 삭제 거절). 조회 화면·API 는 없었다 |

로드맵은 "도메인 → 용어·컬럼", "용어 → 컬럼·(향후) 속성" 을 범위로 적었다. 이번에는 **도메인 → 컬럼**, **용어 → 컬럼**, **단어 → 용어(+용어별 컬럼 수)** 를 구현했고, 도메인 → 용어 목록은 기존 표준 용어 탭의 도메인명 검색으로 볼 수 있어 따로 만들지 않았다. 속성(논리 모델)은 M-6 이후다.

## 2. 집계 기준

| 표준 항목 | 사용 건수 | 기준 |
|---|---|---|
| 표준 용어 | 사용 컬럼 수 | 그 용어를 참조하는 컬럼 |
| 표준 도메인 | 사용 컬럼 수 | 그 도메인에 속한 용어를 참조하는 컬럼 (간접 참조) |
| 표준 단어 | 사용 용어 수 | 그 단어가 매핑된 용어. 같은 용어에 두 번 들어가도 1 |
| (단어의 용어별) | 사용 컬럼 수 | 별도 지표 : 단어 사용 현황의 용어 행마다 표시 |

이름이 같아도(컬럼명이 용어 약어와 같음, 용어명에 단어 문자열이 들어감) 참조가 없으면 세지 않는다. 컬럼 조회는 N:1 조인만 써서 컬럼 1행이 결과 1행이다.

## 3. 백엔드 (`lexorma-data` `dictionary/`)

| 구성 | 내용 |
|---|---|
| `DictionaryUsageRestController` (`/api/dictionaryUsage`, VIEWER) | `GET /term/{id}/data`, `GET /domain/{id}/data` : 사용 컬럼 (조건 `schemaId`·`isApproval`, 검색 `tableName`·`columnName`). `GET /word/{id}/data` : 사용 용어 (검색 `termName`·`termAbbreviation`). 그리드 조회 규격(`page`·`size`·`sort`·`searchColumn`·`searchQuery`)과 응답(`data`·`recordsTotal`)을 따른다 |
| `DictionaryUsageService` | 읽기 전용 트랜잭션. 표준 항목 ID 가 로그인 프로젝트 것인지 먼저 확인한다 (`NOT_FOUND_TERM_DATA`·`NOT_FOUND_DOMAIN_DATA`·`NOT_FOUND_WORD_DATA`) |
| `DictionaryUsageRepository` | QueryDSL DTO 프로젝션 (엔티티를 읽지 않는다). 조회 1번 + count 1번. 정렬은 허용한 필드만 받고 뒤에 기본 정렬을 붙여 페이지 순서를 고정한다 |
| 데이터 사전 목록 3종 | 응답에 `usageCount` 추가. 한 페이지의 ID 를 모아 group by 조회 1번으로 채운다 (목록 조회 로직은 그대로) |
| `GET /api/selectColumn/data` 의 `tableId` | 받기만 하고 무시하던 파라미터를 실제로 적용했다 (조건을 `Specification` 하나로 합침). 화면에서는 쓰지 않는다 |

VIEWER 에게 컬럼 정보를 주는 것은 물리 ERD(VIEWER)가 이미 주는 범위와 같다. 조회 API 는 아무것도 쓰지 않는다. 용어가 삭제된 컬럼(참조 null)은 사용 현황에 나오지 않을 뿐 조회가 실패하지 않는다.

**성능 :** 사용 컬럼 조회는 `TB_COL` 을 용어(또는 용어의 도메인)·프로젝트로 거른다. `TB_COL.STD_TRM_SN` 에는 인덱스가 없다 — 지금 규모(예제 수백 컬럼)에서는 실행 계획을 확인하지 않았고 인덱스도 추가하지 않았다(후속).

## 4. 프론트엔드

| 구성 | 내용 |
|---|---|
| 데이터 사전 세 탭 | "사용 컬럼"(용어·도메인) / "사용 용어"(단어) 열. 숫자를 누르면 사용 현황 대화상자. 상세 대화상자에도 [사용 현황] |
| `features/dictionary/DictionaryUsage.tsx` | 사용 컬럼 목록(스키마·승인 상태 조건, 테이블명·컬럼명 검색, 정렬·페이징 — 공통 `Grid`). 단어는 용어 목록을 먼저 보여 주고 용어의 사용 컬럼 수를 누르면 그 용어의 컬럼 목록이 팝업으로 열린다 |
| 행의 이동 | **[테이블 상세]** : 화면을 옮기지 않고 테이블 관리와 같은 상세 대화상자(`useTableDetail` : 컬럼 목록·컬럼 편집·DDL·승인·수정)를 사용 현황 위에 연다. **[ERD]** : `/physicalErd?schemaId=&tableId=` 로 이동해 그 테이블을 선택·포커스 (M-3 방식 그대로). 메뉴 권한이 있을 때만 보인다 |
| 빈 상태 | "이 표준 용어를 사용하는 컬럼이 없습니다." 등 항목별 안내 (`Grid` 의 `emptyText`) |
| `App.tsx` | `AuthProvider` 가 `DialogProvider` 를 감싸도록 순서를 바꿨다 — 대화상자 내용에서도 `useAuth` 를 쓸 수 있어 공통 `Grid`·`useSchemas`·`useTableDetail` 을 대화상자 안에서 쓴다 |

지시서의 "테이블·컬럼 관리 화면으로 이동" 은 요청에 따라 페이지 이동 대신 **대화상자**로 구현했다. 컬럼 관리로의 이동은 따로 두지 않았다 — 테이블 상세의 컬럼 목록·[컬럼 편집] 이 그 역할을 한다.

## 5. 검증

```bash
./gradlew clean build      # 324 tests, 실패 0, 건너뜀 2 (환경 변수가 필요한 테스트)
# PostgreSQL 18.6 : 빈 격리 DB(metamora_m5_test)를 만들어 실행 후 삭제
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/metamora_m5_test \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
./gradlew :lexorma-app:test -PfrontendPrebuilt=true \
  --tests 'com.lexorma.DictionaryUsageIntegrationTest' --tests 'com.lexorma.SmokeTest' \
  --tests 'com.lexorma.ReferenceSyncIntegrationTest' --tests 'com.lexorma.SchemaManagementIntegrationTest' \
  --tests 'com.lexorma.PhysicalModelIntegrationTest' --tests 'com.lexorma.ErdIntegrationTest' \
  --tests 'com.lexorma.DataPortalTemplateTest' --tests 'com.lexorma.TermUploadNameGenerationTest'
```

| 항목 | H2 | PostgreSQL 18.6 |
|---|---|---|
| `DictionaryUsageIntegrationTest` : 용어 → 컬럼(없음·여러 테이블·여러 스키마), 도메인 → 컬럼(간접, 컬럼마다 한 번), 단어 → 용어(같은 단어 두 번 매핑 = 1, 용어별 컬럼 수), 이름만 같은 컬럼·용어 제외, 스키마·승인·검색 조건, 페이징·정렬(허용하지 않은 필드 무시), 목록의 사용 건수, 컬럼 목록 `tableId`, 다른 프로젝트 ID 차단·건수 제외 | 통과 | 통과 |
| `SmokeTest` 59건 (HTTP) : `dictionaryUsage`(목록의 `usageCount`, 용어·도메인·단어 조회, 조건, 없는 ID → 400 코드), `gridSortAndSearch`(사용 현황 그리드 2종 포함 모든 그리드의 정렬 × 검색) | 통과 | 통과 |
| 회귀 : `ReferenceSyncIntegrationTest` 6, `SchemaManagementIntegrationTest` 4, `PhysicalModelIntegrationTest` 4, `ErdIntegrationTest` 7, `DataPortalTemplateTest` 3, `TermUploadNameGenerationTest` 3 | 통과 | 통과 |
| 나머지 모듈 단위 테스트 (데이터 사전·테이블·컬럼·DDL 등) | 통과 | — |
| `npm run typecheck`, `npm run build` | 통과 | — |

**미검증 (완료로 보지 않음)** — 첫 항목(브라우저 화면 동작)은 2차 때 헤드리스 Chrome 으로 확인했다 ([결과](standardization-quality-m5.md) 5.2). 세션 만료 알림과 아래 두 항목은 그대로 미검증이다.

- 브라우저 화면 동작 : 사용 건수 열·사용 현황 대화상자·조건·페이징·빈 상태·테이블 상세 겹쳐 열기·ERD 이동·다크 모드. `AuthProvider`/`DialogProvider` 순서를 바꿨으므로 로그인·로그아웃·세션 만료 알림과 기존 대화상자도 화면에서 확인이 필요하다.
- VIEWER·USER 계정으로 로그인해 호출하는 HTTP 수준 권한 테스트 (컨트롤러는 클래스 단위 `@PreAuthorize("hasRole('VIEWER')")`).
- 대량 데이터에서의 조회 시간·실행 계획.

## 6. 2차 준비 사항 (명명 규칙 검증·품질 진단)

> 용어 없는 컬럼·컬럼명 ↔ 약어·도메인 ↔ 타입은 2차에서 구현했다 ([standardization-quality-m5.md](standardization-quality-m5.md)). 사용 건수 정렬·인덱스는 그대로 남아 있다.

| 항목 | 내용 |
|---|---|
| 용어 없는 컬럼 | 사용 현황에 나오지 않는다. "표준 용어 미연결 컬럼" 목록은 2차의 품질 진단 대상 |
| 컬럼명 ↔ 용어 약어 | 연결돼 있어도 컬럼명이 용어 영문약어와 다른지는 보지 않는다 (2차 규칙) |
| 도메인 ↔ 컬럼 타입·길이 | 컬럼은 타입·길이를 따로 가지므로 도메인과 다를 수 있다. 불일치 탐지는 2차 규칙 |
| 사용 건수 정렬 | 목록의 사용 건수 열은 정렬할 수 없다 (페이지 단위로 채운다). 미사용 표준 항목 찾기는 2차에서 조건으로 제공 |
| 인덱스 | `TB_COL.STD_TRM_SN`, `TB_STD_TRM_WORD.STD_WORD_SN` 인덱스 여부를 대량 데이터 실행 계획으로 확인 |
