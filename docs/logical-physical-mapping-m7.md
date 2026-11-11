# M-7. 논리·물리 모델 매핑 — 설계 결정과 구현 기록 (1차 : 도메인·API)

> [데이터 모델링 로드맵](data-modeling-roadmap.md) M-7 중 **1차(매핑 저장 구조·관리 API·현황·후보 조회)** 의 기록이다. 작성일 2026-11-11.
> 매핑 관리 화면과 정합성 비교는 **2차**에서 구현했다 — [model-mapping-quality-m7.md](model-mapping-quality-m7.md). 물리명 추천은 구현하지 않았다. 아래 본문은 1차 시점의 기록이다.

## 1. 기존 구조 분석과 결정

| 확인 항목 | 결과 → 결정 |
|---|---|
| 논리 모델 (M-6) | 프로젝트 → 논리 모델 N → 엔터티 → 속성·식별자, 관계. 엔터티·관계에 승인 플래그. 변경은 **논리 모델 행 잠금**으로 직렬화. 속성은 표준 용어만 참조하고 물리 객체를 참조하지 않는다 → **매핑은 별도 패키지(`data/mapping`)·별도 테이블.** 논리·물리 엔티티에는 컬럼을 추가하지 않았다 |
| 물리 모델 | `SchemaInfo` → `TableInfo` → `ColumnInfo`, 관계·인덱스·제약. 테이블은 컬럼이 없고 미승인일 때만, 컬럼은 미승인이고 관계·인덱스·제약에 쓰이지 않을 때만 삭제(`releaseColumns`). 테이블·컬럼 삭제에는 잠금이 없었다 |
| 표준화 | 속성 → 표준 용어, 컬럼 → 표준 용어는 각각 따로 연결된다 → **매핑은 표준 용어 연결을 대체하지 않는다.** 같은 용어·같은 이름이어도 자동으로 매핑하지 않고, 달라도 매핑을 막거나 고치지 않는다 (조회에 양쪽 용어를 함께 준다) |
| 프로젝트 격리 | 모든 조회·변경이 `AccountDto.getProjectId()` 기준 → 동일. 요청의 프로젝트 ID 는 받지 않는다 |
| 권한·승인 | 논리·물리 API 가 조회 VIEWER / 변경 USER → 동일. 로드맵에 매핑 승인 요구가 없다 → **매핑에는 승인 절차가 없다.** 매핑은 엔터티·테이블 자체를 바꾸지 않으므로 승인된(또는 미승인) 엔터티·테이블도 USER 가 매핑할 수 있고, 승인 상태는 조회 응답에 표시만 한다 |
| 로드맵 M-7 | "1:1 우선, 1:N 은 검토" → **1:1.** 로드맵의 M-7 범위에는 물리명 추천·도메인 → 타입 매핑·불일치 확인도 있으나 지시서가 1차에서 제외했다 → **2차로 분리** (로드맵 갱신) |
| 마이그레이션 | CLAUDE.md 정책은 "V0 + 데이터 전용 V1, 추가 마이그레이션은 요청 시에만". 지시서가 기존 DB 보존과 신규 버전 마이그레이션을 요구 → **`V2__logical_physical_mapping.sql` 추가** (V0·V1 은 바꾸지 않았다). 5장 |

## 2. 저장 구조

```text
TB_LGCL_ENTT (LogicalEntity) ──1:1── TB_ENTT_TBL_MPNG (EntityTableMapping) ──1:1── TB_TBL (TableInfo)
                                          │ 1:N
TB_LGCL_ATRB (LogicalAttribute) ─1:1─ TB_ATRB_COL_MPNG (AttributeColumnMapping) ─1:1─ TB_COL (ColumnInfo)
```

| 테이블 | 컬럼 | 제약 |
|---|---|---|
| `TB_ENTT_TBL_MPNG` (엔터티 테이블 매핑, `SQ_ENTT_TBL_MPNG`) | `ENTT_TBL_MPNG_SN`, `PRJ_SN`, `LGCL_ENTT_SN`, `TBL_SN`, 감사 컬럼 4개 | `UX_…_01 (LGCL_ENTT_SN)`, `UX_…_02 (TBL_SN)`, `IX_…_01 (PRJ_SN)`, FK → `TB_LGCL_ENTT`, `TB_TBL` |
| `TB_ATRB_COL_MPNG` (속성 컬럼 매핑, `SQ_ATRB_COL_MPNG`) | `ATRB_COL_MPNG_SN`, `ENTT_TBL_MPNG_SN`, `LGCL_ATRB_SN`, `COL_SN`, 감사 컬럼 4개 | `UX_…_01 (LGCL_ATRB_SN)`, `UX_…_02 (COL_SN)`, `IX_…_01 (ENTT_TBL_MPNG_SN)`, FK → `TB_ENTT_TBL_MPNG`, `TB_LGCL_ATRB`, `TB_COL` |

- 이름은 [DB 명명 규칙](db-naming-standard.md). 새 기관 표준단어 : 매핑 `MPNG`.
- FK 에는 CASCADE 가 없다 : 매핑이 남아 있으면 엔터티·테이블·속성·컬럼·부모 매핑 행은 DB 에서도 지워지지 않는다.
- 상태 컬럼·설명·이력 테이블은 만들지 않았다.
- **DB 가 보장하는 것** : 1:1 (UNIQUE 4개), 참조 무결성 (FK 5개). **서비스가 보장하는 것** : 프로젝트 격리, 속성 매핑의 속성·컬럼이 부모 매핑의 엔터티·테이블에 속하는지. (복합 FK 로 DB 에서 보장하려면 `TB_LGCL_ATRB`·`TB_COL` 에 UNIQUE 를 추가해야 해서 하지 않았다. 속성은 엔터티를, 컬럼은 테이블을 옮길 수 없으므로 등록·변경 시점 검사로 충분하다.)

## 3. 정책

| 항목 | 정책 |
|---|---|
| 매핑 단위 | 엔터티 1 : 테이블 1, 속성 1 : 컬럼 1. 매핑이 없는 엔터티·테이블·속성·컬럼도 그대로 존재한다 |
| 자동 매핑 | 없다. 명시적으로 등록한 것만 매핑이다. (1차 시점에는 예제도 매핑하지 않았고, 이후 `V4__mapping_example_data.sql` 이 예제의 논리·물리 모델을 매핑 데이터로 이었다 — 10장) |
| 원본 불변 | 매핑 등록·변경·삭제는 엔터티·테이블·속성·컬럼과 그 승인 상태·표준 용어를 바꾸지 않는다 |
| 부모·자식 | 속성 매핑은 부모(엔터티 ↔ 테이블 매핑)의 엔터티에 속한 속성과 테이블에 속한 컬럼만 연결한다. 그 밖의 조합은 `INVALID_MODEL_MAPPING` |
| 엔터티 매핑 변경 | 테이블만 바꾼다 (요청의 `entityId` 는 무시). **속성 매핑이 있으면 거절** (`INVALID_MODEL_MAPPING`) — 속성 매핑을 먼저 해제한다. 같은 테이블로 다시 저장하는 것은 허용 |
| 속성 매핑 변경 | 컬럼만 바꾼다 (요청의 `mappingId`·`attributeId` 는 무시) |
| 매핑 삭제 | 속성 매핑이 있는 엔터티 매핑은 삭제할 수 없다 (`RELATION_EXISTS`) |
| 엔터티·속성 삭제 | 매핑된 엔터티·속성은 삭제할 수 없다 (`RELATION_EXISTS`, 메시지에 매핑된 테이블·컬럼명). 엔터티의 속성 매핑은 엔터티 매핑이 있어야만 존재하므로 엔터티는 엔터티 매핑만 검사한다 |
| 테이블·컬럼 삭제 | 매핑된 테이블·컬럼은 삭제할 수 없다 (`RELATION_EXISTS`, 메시지에 매핑된 엔터티·속성명). 컬럼은 단건 삭제·일괄 저장의 삭제 모두 `releaseColumns` 에서 검사한다 |
| 논리 모델·스키마 삭제 | 기존 정책(엔터티가 있는 모델, 테이블이 있는 스키마는 삭제 불가)으로 충분하다 — 매핑이 남은 채 지워지는 경로가 없다 |
| 프로젝트 삭제 | 기존 정책대로 프로젝트의 모델 데이터를 지우지 않는다 (`ProjectDeletedEvent` 리스너 없음). 매핑도 양쪽 원본과 함께 남으므로 끊어진 참조는 생기지 않는다. 프로젝트 데이터 정리를 만들 때 매핑 → 원본 순서로 지워야 한다 |
| 권한 | 조회 VIEWER, 등록·변경·삭제 USER. 승인 절차 없음. `NOT_APPROVE` 는 조회도 불가 |

오류 코드 : `MODEL_MAPPING_IS_NOT_DEFINED`(매핑 없음·다른 프로젝트), `INVALID_MODEL_MAPPING`(부모와 맞지 않는 속성·컬럼, 속성 매핑이 있는 매핑의 테이블 변경), `SAVED_DATA_EXISTS`(이미 매핑된 엔터티·테이블·속성·컬럼), `RELATION_EXISTS`(삭제 차단), `LOGICAL_MODEL_IS_NOT_DEFINED`(엔터티·모델 없음), `TABLE_IS_NOT_DEFINED`(테이블 없음). 모두 400.

## 4. API (`/api/modelMapping`, `ModelMappingRestController`)

| 메서드·경로 | 권한 | 내용 |
|---|---|---|
| `GET /entity/data?page&size&sort&searchColumn&searchQuery&modelId&schemaId&mapped` | VIEWER | **엔터티 기준 목록** (그리드 형식 `{data, recordsTotal, recordsFiltered}`). 엔터티 1행 : 모델·엔터티(승인 상태)·매핑된 스키마·테이블(승인 상태)·속성 수·매핑된 속성 수. 매핑하지 않은 엔터티는 `mappingId`·테이블이 null. `mapped=true` 매핑 목록 / `mapped=false` **매핑 후보 엔터티**. 검색 `entityName`(기본)·`tableName` 부분 일치, 정렬 `modelName`·`entityName`·`schemaName`·`tableName` (없으면 모델명 → 엔터티명) |
| `GET /entity/{mappingId}` | VIEWER | 매핑 상세 : `mapping`(위 1행), `attributes`(엔터티의 **속성 전체** — 매핑된 컬럼과 양쪽 표준 용어, 매핑하지 않은 속성은 `mappingId`·컬럼이 null = 후보 속성), `unmappedColumns`(매핑된 테이블의 컬럼 중 아직 연결하지 않은 것 = **후보 컬럼**) |
| `POST /entity` `{id?, entityId, tableId}` | USER | 엔터티 ↔ 테이블 매핑 등록(`id` 없음)·변경 |
| `DELETE /entity/{mappingId}` | USER | 엔터티 ↔ 테이블 매핑 삭제 |
| `POST /attribute` `{id?, mappingId, attributeId, columnId}` | USER | 속성 ↔ 컬럼 매핑 등록·변경 |
| `DELETE /attribute/{attributeMappingId}` | USER | 속성 ↔ 컬럼 매핑 삭제 |
| `GET /table/data?page&size&sort&searchQuery&schemaId` | VIEWER | **매핑 후보 테이블** : 엔터티에 매핑하지 않은 프로젝트의 테이블 (그리드 형식). 검색은 테이블명·한글명 |
| `GET /status?modelId` | VIEWER | **현황** : 논리 모델별 `totalEntities`·`mappedEntities`·`unmappedEntities`·`totalAttributes`·`mappedAttributes`·`unmappedAttributes`. `modelId` 가 없으면 프로젝트의 모든 모델(모델명 순) |

- 집계 기준 : 엔터티·속성 수 = 그 논리 모델의 것 전부, 매핑 수 = 매핑 행이 있는 것, 미매핑 = 전체 − 매핑. 매핑하지 않은 엔터티의 속성도 전체 속성 수에 들어간다.
- 물리 기준 역방향 현황 API 는 만들지 않았다 : 매핑 안 된 테이블 수는 `GET /table/data` 의 `recordsTotal`, 매핑 안 된 컬럼은 상세의 `unmappedColumns` 로 알 수 있다. 화면에서 필요해지면 2차에서 추가한다.
- 후보 조회는 "아직 매핑하지 않은 것"의 목록일 뿐이다. 이름 유사도·추천은 없다.
- 목록·현황·후보는 QueryDSL 로 DB 에서 걸러 페이징·집계한다 (`ModelMappingQueryRepository`). 목록은 조회 2회(행 + 건수), 상세는 3회, 현황은 1회 (모델 1행에 상관 서브쿼리 4개).

## 5. DB 마이그레이션

- `V2__logical_physical_mapping.sql` (postgresql·h2 같은 내용) : 시퀀스 2개, 테이블 2개, 인덱스 6개, FK 5개. **스키마만 추가하고 기존 테이블·데이터는 바꾸지 않는다.**
- 신규 설치 : V0 → V1 → V2. 기존 DB(V0·V1 적용) : V0·V1 체크섬 변화 없이 V2 만 적용된다. `repair`·초기화·수동 DDL 이 필요 없다.
- **개발 DB(`localhost:15439/metamora`)** : 작업 전 이력은 V0(-1532103848)·V1(-514501166). 작업 중 IDE 에서 실행 중이던 애플리케이션이 재시작하면서 **Flyway 가 V2 를 개발 DB 에 적용했다** (2026-11-11 15:56, 체크섬 324210209, 빈 테이블 2개 추가, 기존 데이터 변화 없음). 의도해서 실행한 것은 아니지만 위 업그레이드 경로 그대로다. **V2 파일은 이미 적용됐으므로 내용을 바꾸면 안 된다** (바꿀 것은 다음 버전으로).
- V0 의 META-MORA 물리 모델 예제(MetaMora 자체 테이블)에는 새 테이블 2개와 단어 `매핑` 이 없다 — V0 를 바꾸면 기존 DB 의 체크섬이 달라지므로 넣지 않았다. 넣으려면 V1 같은 데이터 전용 마이그레이션이 필요하다 (결정 필요, 8장).

## 6. 동시성

| 상황 | 처리 |
|---|---|
| 잠금 순서 | **논리 모델 행 → 물리 테이블 행** (고정). 매핑 변경은 엔터티의 논리 모델 행(M-6 과 같은 잠금)을 잠그고, 이어서 매핑할 테이블 행을 잠근다. 논리 변경은 모델 행만, 테이블·컬럼 삭제는 테이블 행만 잠그므로 순환이 없다. 프로젝트 전체는 잠그지 않는다 |
| 같은 엔터티·속성 동시 매핑 | 모델 행 잠금으로 직렬화 → 뒤 요청은 `SAVED_DATA_EXISTS` |
| 같은 테이블·컬럼 동시 매핑 (다른 모델의 엔터티) | 테이블 행 잠금으로 직렬화 → `SAVED_DATA_EXISTS`. 최종 방어는 UNIQUE (`DataIntegrityViolationException` → `SAVED_DATA_EXISTS`) |
| 부모 매핑 삭제 ↔ 속성 매핑 등록 | 같은 모델 행 잠금. 삭제가 먼저면 등록은 `MODEL_MAPPING_IS_NOT_DEFINED`, 등록이 먼저면 삭제는 `RELATION_EXISTS` |
| 엔터티·속성 삭제 ↔ 매핑 등록 | 같은 모델 행 잠금. 잠금 뒤에 엔터티·매핑을 다시 읽어 검사한다 |
| 테이블·컬럼 삭제 ↔ 매핑 등록 | 테이블 삭제(`deleteTable`)와 컬럼 삭제(`releaseColumns`)가 테이블 행을 잠근 뒤 매핑을 검사한다 (`TableInfoRepository.findForUpdate`, 여러 테이블이면 ID 순) |
| 예외 | 서비스 메서드 단위 트랜잭션이라 중간 예외는 전부 롤백된다 |

## 7. 검증

```bash
./gradlew clean build      # 결과는 아래 표
# PostgreSQL 18.6 : 빈 격리 DB(metamora_m7_test)를 만들어 실행 후 삭제
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/metamora_m7_test SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
METAMORA_TEST_POSTGRES_URL=jdbc:postgresql://localhost:15439/metamora_m7_test \
./gradlew :metamora-app:test -PfrontendPrebuilt=true \
  --tests 'com.koboolean.metamora.ModelMappingIntegrationTest' --tests 'com.koboolean.metamora.LogicalModelIntegrationTest' \
  --tests 'com.koboolean.metamora.StandardizationQualityIntegrationTest' --tests 'com.koboolean.metamora.DictionaryUsageIntegrationTest' \
  --tests 'com.koboolean.metamora.SmokeTest' --tests 'com.koboolean.metamora.ReferenceSyncIntegrationTest' \
  --tests 'com.koboolean.metamora.SchemaManagementIntegrationTest' --tests 'com.koboolean.metamora.PhysicalModelIntegrationTest' \
  --tests 'com.koboolean.metamora.ErdIntegrationTest' --tests 'com.koboolean.metamora.DataPortalTemplateTest' \
  --tests 'com.koboolean.metamora.TermUploadNameGenerationTest' --tests 'com.koboolean.metamora.V0SeedMigrationTest'
```

| 자동 테스트 | H2 | PostgreSQL 18.6 |
|---|---|---|
| `ModelMappingIntegrationTest` 6건 : 엔터티 매핑(등록·조회·변경·삭제, 중복, 없는 ID, 다른 프로젝트, 목록 조건·페이징, 후보 테이블) / 속성 매핑(중복, 부모 불일치, 다른 프로젝트, 표준 용어 불변, 후보 컬럼, 부모 변경·삭제 차단) / 삭제 정책(엔터티·테이블·속성·컬럼·일괄 저장 삭제 차단, 해제 후 삭제, 모델·스키마·프로젝트 삭제, DB UNIQUE·FK, 롤백) / 현황(빈 모델·없음·일부·전체, 모델별·프로젝트별) / 권한(VIEWER·USER·NOT_APPROVE) / 동시 요청(같은 엔터티·테이블·속성·컬럼, 부모 매핑·엔터티·테이블·컬럼 삭제와 등록) | 통과 | 통과 |
| `SmokeTest.modelMapping` (HTTP : 현황 → 후보 → 매핑 → 상세 → 오류 코드 → 삭제, 예제의 계정 ↔ `TB_ACNT`) | 통과 | 통과 |
| `V0SeedMigrationTest` (V0 → V1 → V2 적용·재시작 검증, 매핑 테이블은 비어 있음) | 통과 | 통과 |
| `TableDesignServiceImplTest` (테이블 삭제가 행 잠금 조회를 쓰도록 수정) | 통과 | — |
| 회귀 : 위 명령의 나머지 (M-1 물리 모델·관계 동기화, M-2 ERD, M-3 스키마, M-5 사용 현황·품질 진단, M-6 논리 모델·ERD·레이아웃, Excel 업로드, DDL) | 통과 | 통과 |

- `./gradlew clean build` : **348 tests, 실패 0, 건너뜀 2** (`V0SeedMigrationTest` 의 PostgreSQL 1건, `UploadPerformanceTest` 1건). npm ci + vite build 포함.
- PostgreSQL 격리 DB : **108 tests, 실패 0, 건너뜀 0.** Flyway 이력 V0·V1·V2 성공, V0·V1 체크섬이 개발 DB 와 같다.
- 기존 데이터가 있는 DB : 개발 DB 를 `pg_dump` 로 복사한 격리 DB(`metamora_m7_upgrade`, 실행 후 삭제)에서 `ModelMappingIntegrationTest`·`LogicalModelIntegrationTest`·`PhysicalModelIntegrationTest` 18건 통과.

**미검증 (완료로 보지 않음)**

- 성능 : 큰 모델(엔터티·테이블 수천 건)에서의 목록·현황 조회 시간, 실행 계획. 측정하지 않았다.
- 동시성 : 테스트는 스레드 2~4개로 결과의 일관성만 확인한다. 부하 상황의 잠금 대기·교착 빈도는 측정하지 않았다. MySQL·MariaDB·Oracle 은 대상이 아니다 (자체 DB 는 PostgreSQL·H2).
- 화면 : 매핑 UI 가 없으므로 브라우저 검증 없음. 영어 메시지는 등록 여부(`MessagesCoverageTest`)만 확인.
- V2 가 적용된 뒤의 개발 DB 에서 실행 중인 애플리케이션의 화면 동작은 확인하지 않았다.

## 8. 2차 연계 · 후속

- **M-7 2차 (매핑 관리 UI·정합성 비교)** : 화면은 `GET /entity/data`(그리드) → 상세(`/entity/{id}`)의 `attributes`·`unmappedColumns` 로 좌우 연결 UI 를 만들 수 있다. 메뉴를 추가할 때 `MENU_ROLES`(VIEWER, 변경 버튼은 USER)·`menu.ts` 를 함께 고친다.
- 정합성 비교 : 상세의 양쪽 표준 용어(`attributeTermId` ↔ `columnTermId`)가 출발점이다. 이름·타입·길이·NULL 차이는 M-5 품질 진단처럼 읽을 때 계산하고 저장하지 않는 방식을 권한다 (타입은 `TableDdlGenerator.mapType`).
- 물리명 추천(용어 → 컬럼명, 엔터티 → 테이블명), 논리 → 물리 일괄 생성(D-08)은 미구현.
- 1:N·N:M 확장 : UNIQUE 4개가 1:1 을 강제한다. 확장할 때는 해당 UNIQUE 를 (엔터티, 테이블) 복합으로 바꾸고 "속성 매핑이 있으면 변경 차단" 규칙을 부모 매핑 단위로 유지하면 된다. 로드맵 요구가 생길 때 결정한다.
- **결정 필요** : V0 예제 물리 모델·데이터 사전에 매핑 테이블 2개와 단어 `매핑` 을 넣을지 (넣는다면 데이터 전용 새 마이그레이션 + `V1` 논리 예제 보강).
- 목록 조회 범위가 커지면 `TB_ENTT_TBL_MPNG (PRJ_SN)` 외 인덱스와 현황의 상관 서브쿼리를 조인 집계로 바꾸는 것을 검토한다 (측정 후).
- 프로젝트 삭제 시 모델 데이터 정리는 여전히 없다 (기존 공백). 만들 때 매핑을 먼저 지운다.

## 9. 후속 반영 : 예제 모델·COMMENT (V3)

- `V3__mapping_example_and_comments.sql` (postgresql·h2, 시퀀스 문법만 다름) : META-MORA 예제에 매핑 테이블 2개를 넣었다 — 단어 `매핑`·용어 2개, 물리 모델(테이블 2·컬럼 16·FK 관계 5), 논리 모델(엔터티 2·속성 16·주 식별자 2·관계 5). 논리·물리 예제를 매핑으로 잇지는 않았다.
- MetaMora 자체 테이블 37개·컬럼 357개 전부에 `COMMENT ON` 을 달았다 (테이블 = 한글명, 컬럼 = 한글 용어). 이전에는 COMMENT 가 하나도 없었다. `SPRING_SESSION*`·`flyway_schema_history` 는 제외.
- **관리 규칙** : 테이블·컬럼을 추가하는 마이그레이션은 같은 파일에 COMMENT 와 예제(물리·논리)를 넣는다. `V0SeedMigrationTest` 가 실제 스키마의 `TB_` 테이블·컬럼 집합과 COMMENT 를 예제(`TB_TBL.TBL_KORN_NM`, `TB_COL.COL_EXPLN`)와 비교해 빠지면 실패한다.
- 5장·8장의 "V0 예제에 새 테이블이 없다 / 결정 필요" 는 이 마이그레이션으로 해소됐다.
- 기존 DB : 프로젝트 0 에 같은 이름의 테이블·단어를 직접 등록해 둔 경우 이름으로 찾는 하위 조회가 여러 행이 되어 V3 가 실패할 수 있다 (V1 과 같은 전제).

## 10. 2차에서 달라진 점

- API 추가 : `GET /entity/lookup`(엔터티 또는 테이블의 매핑 1건), `GET /quality/summary`·`/quality/data`(정합성 비교). 4장의 기존 API 와 DTO 는 바뀌지 않았다.
- 메뉴 : `model-mapping`(VIEWER)·`model-mapping-edit`(USER) 를 `MENU_ROLES` 에, `model-mapping` 을 `logical-modeling` 그룹에 넣었다.
- 7장의 "화면 : 매핑 UI 가 없으므로 브라우저 검증 없음"은 2차에서 해소됐다. 상세는 [model-mapping-quality-m7.md](model-mapping-quality-m7.md).
- **예제 매핑 (V4)** : `V4__mapping_example_data.sql` 이 META-MORA 예제의 엔터티 37개·속성 357개를 전부 물리 테이블·컬럼에 매핑한다 (엔터티명 = 테이블 한글명, 같은 표준 용어). 5장·7장·9장의 "매핑 테이블은 비어 있다 / 예제를 매핑으로 잇지 않았다"는 V4 로 바뀌었다. 상세는 [model-mapping-quality-m7.md](model-mapping-quality-m7.md) 8장.
