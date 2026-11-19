# 목록 조회 페이지·정렬 정책 (공통 페이징 안정성 개선, 2026-11-19)

공용 그리드(`components/grid/Grid.tsx`)와 같은 형식(`GET …/data?page&size&sort&searchColumn&searchQuery` → `{ data, recordsTotal, recordsFiltered }`)으로 목록을 주는 관리 API 의 공통 규칙이다. 상태 기록은 `docs/completed-work.md` [COMMON-LIST], 남은 결정은 `docs/pending-work.md` [COMMON-LIST].

## 1. 규칙

| 요청 | 처리 |
|---|---|
| `sort` 없음·빈 값 | 그 API 의 기존 기본 정렬 (서버가 정한 값 — 허용 목록 검사 대상 아님) |
| `필드,asc` · `필드,desc` (방향 대소문자 무시) · `;` 로 여럿 | 그대로 |
| 방향이 없는 항목(`필드`, `필드,`)·빈 항목(`;`, `,asc`)·셋째 칸 | 예전처럼 그 항목만 건너뜀 (단 필드명이 있으면 허용 목록은 본다) |
| 허용 목록에 없는 필드 (존재하지 않는 필드·엔티티에 있지만 허용하지 않은 필드·중첩 경로 `a.b`·대소문자가 다른 필드명) | **400** — 한 항목이라도 그러면 요청 전체 |
| `asc`·`desc` 가 아닌 방향 | 400 |
| `size` 0 이하 또는 100 초과 | 400 (100 으로 줄이지 않는다) |
| `page` 음수 | 400 |
| `page × size` 가 int 를 넘음 (JPA `setFirstResult` 가 표현할 수 없는 오프셋) | 400 |
| 마지막 페이지 뒤 (표현 가능한 오프셋) | 200, 빈 `data` + 전체 수 (기존 계약) |
| `page`·`size` 가 숫자가 아님 | 400 (Spring 변환 오류 — 기존) |

- 오류 응답 : 기존 `CustomException` 형식 `{ code: "INVALID_PAGE_REQUEST", message, status: 400 }`, 문구는 요청 언어(한국어·영어). 사유 문구 : "페이지 번호는 0 이상이어야 합니다." / "페이지 크기는 1~100 사이여야 합니다." / "요청한 페이지가 조회할 수 있는 범위를 벗어났습니다." / "정렬 방향은 asc 또는 desc 만 쓸 수 있습니다." / "허용하지 않는 정렬 항목입니다." — 클래스명·SQL·엔티티 경로·스택은 나가지 않는다 (요청 필드명도 되돌려 주지 않는다).
- 순서 : 인증·인가(Spring Security·`@PreAuthorize`) → 컨트롤러의 프로젝트·대상 확인(있는 API 만, 예 논리 모델 관리의 모델 확인) → 페이지 요청 검사 → 조회. 검사에서 거절되면 목록·수 조회를 하지 않는다 (논리 모델 관리 : 모델 확인 1문장뿐 — `LogicalModelManagementIntegrationTest`).
- 설정 없음 : 상한 100 은 코드 상수 `PageableUtil.MAX_PAGE_SIZE` (공용 그리드 선택지 10·25·50·100).

## 2. 책임

- 공통 `common/utils/PageableUtil.getGridPageable(page, size, sort[, defaultOrder], sortable)` : 위 형식 검사와 `Pageable` 생성. 업무 필드를 알지 못한다 — 허용 목록은 호출하는 쪽이 넘긴다 (허용 목록 없는 예전 메서드는 없앴다 : 새 목록 API 도 허용 목록을 꼭 정한다).
- 각 조회 : 허용 목록(화면 필드명)을 정하고, 화면 필드명 → 실제 정렬 경로(엔티티 경로·QueryDSL 경로·메모리 비교자)를 매핑한다. 이미 맵이 있는 조회는 그 키(`…_SORTS.keySet()`)를 허용 목록으로 쓴다. Spring Data 저장소에 검사하지 않은 필드를 넘기지 않는다 — 예전에는 `projectMember.account.password` 같은 중첩 경로로 사용자 목록을 정렬할 수 있었다(200).
- 허용 목록 = 화면 정의(`lexorma-web/src/features/**/columns.ts`)에서 정렬 가능으로 정의한 열(isFilter 가 false 가 아니고 체크박스가 아닌 열, **숨긴 열 포함** — `SmokeTest.gridSortAndSearch` 가 모두 200 을 보장해 온 계약) + 그리드가 처음 보내는 첫 열 + 다른 화면이 같은 API 에 보내는 정렬(예 인덱스 등록·물리 ERD 의 `tableName`). 화면에 열을 더하거나 정렬 가능하게 바꾸면 허용 목록도 함께 고친다 (SmokeTest 가 잡는다).

## 3. 적용 API (PageableUtil, 25곳)

| API | 화면 | 정렬 처리 | 허용 목록 (정의 위치) |
|---|---|---|---|
| `/api/getStandardDomains/data` | 표준 도메인 | Spring Data | `DataDictionaryService.DOMAIN_SORT_FIELDS` |
| `/api/getStandardWords/data` | 표준 단어 | Spring Data | `DataDictionaryService.WORD_SORT_FIELDS` |
| `/api/getStandardTerms/data` | 표준 용어 | Spring Data (도메인 필드 → `standardDomain.*`) | `DataDictionaryService.TERM_SORT_FIELDS` |
| `/api/selectTable/data` | 테이블 관리·인덱스 등록·물리 ERD | Spring Data (`schemaName` → `schema.schemaName`) | `TableDesignService.TABLE_SORT_FIELDS` |
| `/api/selectColumn/data` | 컬럼 관리 | Spring Data | `ColumnManageService.COLUMN_SORT_FIELDS` |
| `/api/physical/index/data` | 인덱스 관리 | Spring Data (테이블·스키마 경로) | `PhysicalModelService.INDEX_SORT_FIELDS` |
| `/api/physical/view/data` | VIEW 관리 | Spring Data | `PhysicalViewService.VIEW_SORT_FIELDS` |
| `/api/selectNotice/data` | 공지 | Spring Data | `NoticeService.NOTICE_SORT_FIELDS` |
| `/api/selectUser/data` | 사용자 관리 (ADMIN) | Spring Data | `UserManageService.USER_SORT_FIELDS` |
| `/api/getSystemLog/data` | 시스템 로그 | Spring Data | `SystemLogService.LOG_SORT_FIELDS` |
| `/api/getProject/{id}/data` | 프로젝트 구성원 | Spring Data | `ProjectManageService.MEMBER_SORT_FIELDS` (`id` 만 — 정렬 가능한 열 없음) |
| `/api/dictionaryUsage/{term,domain}/{id}/data` | 사전 사용 현황 | QueryDSL 맵 | `DictionaryUsageRepository.COLUMN_SORT_FIELDS` |
| `/api/dictionaryUsage/word/{id}/data` | 단어 사용 용어 | QueryDSL 맵 | `DictionaryUsageRepository.TERM_SORT_FIELDS` |
| `/api/standardizationQuality/data` | (화면 없음 — 사전 진단 API) | 메모리 비교자 | `StandardizationQualityService.SORT_FIELDS` |
| `/api/standardizationQuality/model/data` | 모델 품질 진단 | 메모리 비교자 | `StandardizationQualityService.MODEL_SORT_FIELDS` |
| `/api/standardizationQuality/terms/data` | 표준 용어 진단 | 메모리 비교자 | `TermQualityService.SORT_FIELDS` |
| `/api/standardizationQuality/identifiers/data` | DBMS 식별자 진단 | 메모리 비교자 | `IdentifierQualityService.SORT_FIELDS` |
| `/api/modelMapping/entity/data` | 매핑 관리 | QueryDSL 맵 | `ModelMappingQueryRepository.ENTITY_SORT_FIELDS` |
| `/api/modelMapping/table/data` | 매핑 대화상자 후보 테이블 | QueryDSL 맵 | `ModelMappingQueryRepository.TABLE_SORT_FIELDS` |
| `/api/modelMapping/quality/data` | 정합성 비교 | 메모리 비교자 | `ModelMappingQualityService.SORT_FIELDS` |
| `/api/modelMapping/recommendation/column/data` | 컬럼명 추천 | QueryDSL 맵 | `PhysicalNameRecommendationQueryRepository.COLUMN_SORT_FIELDS` |
| `/api/modelMapping/recommendation/table/data` | 테이블명 추천 | 화면 필드 → 매핑 목록 필드 | `PhysicalNameRecommendationService.TABLE_SORT_FIELDS` |
| `/api/logical/model/{id}/entity/data` | 논리 모델 관리 엔터티 | QueryDSL 맵 | `LogicalModelQueryRepository.ENTITY_SORT_FIELDS` |
| `/api/logical/model/{id}/relationship/data` | 논리 모델 관리 관계 | QueryDSL 맵 | `LogicalModelQueryRepository.RELATIONSHIP_SORT_FIELDS` |

권한·프로젝트 격리는 각 API 의 기존 그대로 (조회는 로그인 프로젝트로 거른다 — 검사는 그 앞의 형식만 본다). 서비스를 직접 부르는 내부 경로(AI·MCP 도구, 생성·진단·내보내기, 테스트)는 `PageableUtil` 을 거치지 않으므로 이 상한·허용 목록과 무관하다 (예 `getStandardTermsData(PageRequest.of(0, 100_000))` 내부 호출).

## 4. 적용하지 않는 조회 (각자의 기존 한도 유지)

| 조회 | 한도 · 정렬 |
|---|---|
| 논리 ERD 범위 `/erd/part` · 이웃 `/erd/neighbors` · 엔터티 찾기 `/erd/entities` | 300 · 300 · 50 (넘으면 400 `INVALID_LOGICAL_MODEL`), 서버 고정 정렬 |
| 물리 ERD `/api/erd` | 기존 `ErdService.MAX_TABLES` = 300 (넘으면 400 `INVALID_ERD_REQUEST`) |
| 모델 버전 `/api/modelVersion/data` · 모델 변경 이력 `/api/modelHistory/data` | 500 으로 줄임(silent clamp), 서버 고정 정렬 — 버전 비교 대화상자가 500 을 쓴다 |
| AI 설명 제안(100)·외부 연결 규칙 이력(100)·원본 가져오기 실행(100)·원본 이름 변경 이력(200)·원본 수집 실행·변경(200)·원본 카탈로그(200)·원본 매핑·비교 목록(500) | 각자 상한으로 줄임(silent clamp), 서버 고정 정렬 |
| 엑셀 내보내기(`/api/exportExcel/*`, 스트리밍)·모델 내보내기·DDL·생성·진단·일괄 처리 | 페이지 요청 없음 (전체) |
| 논리 전체 조회 `/api/logical/model/{id}/erd`·`/entity`·`/relationship` | 제한 없음 — 아래 5장 |

이 조회들은 공용 그리드 형식이 아니고 정렬을 받지 않는다. 상한을 넘는 값을 400 이 아닌 줄임으로 처리하는 것은 이번 정책(일반 관리 목록)과 다르다 — 통일할지는 결정 사항 (pending-work [COMMON-LIST]).

## 5. 논리 전체 조회 API 3개 (유지 — 결정 필요)

| API | 내부 사용처 | 외부 공개 계약 | 조치 | 사유 |
|---|---|---|---|---|
| `GET /api/logical/model/{id}/erd` (`selectGraph`) | 화면 없음 (관리 화면 M-6 후속 3차, 논리 ERD 1차에서 끊음). 서버 업무 호출 없음. 테스트가 서비스 메서드를 비교 기준으로 직접 부른다 (`LogicalErdScopeIntegrationTest`·`LogicalModelManagementIntegrationTest`·성능 측정 2개) | 저장소 안에는 없음 (외부 연동 가이드·API 버전 계약 없음, Swagger UI 는 `local`·`h2-local` 에만, 인증은 세션 + CSRF). 저장소 밖 사용 여부는 **확인 불가** | 유지 | 외부 사용 여부를 확인할 수 없다 (작업 지시 10.3 '불명확 → 유지 후 결정') |
| `GET /api/logical/model/{id}/entity` (`selectEntities`) | 화면 없음 (논리 ERD 관계 대화상자가 쓰던 것을 3차에서 검색으로 바꿈) | 같음 | 유지 | 같음 |
| `GET /api/logical/model/{id}/relationship` (`selectRelationships`) | 화면 없음 (엔터티 상세가 쓰던 것을 3차에서 엔터티별 API 로 바꿈). 테스트가 기대값 계산에 서비스 메서드를 쓴다 | 같음 | 유지 | 같음 |

위험 : 세 API 모두 VIEWER 이상, 로그인 프로젝트의 모델만, 제한 없음 — 엔터티 3,000개 모델에서 `/erd` 6.5MB·PostgreSQL 225ms·할당 80MB, `/relationship` 은 관계 속성 짝 판정에서 엔터티마다 식별자를 읽어 68문장. 화면이 부르지 않으므로 사람이 직접(또는 스크립트로) 부를 때만 생긴다. 제거한다면 : 컨트롤러 매핑 3개만 지우고 서비스 메서드는 테스트 기준으로 남긴다(또는 테스트 쪽으로 옮긴다), `LogicalGraph` 타입은 `LogicalGraphPart` 가 쓰므로 남긴다. 결과를 조용히 자르는 제한은 하지 않는다.
