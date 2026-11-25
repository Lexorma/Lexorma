# DB-1 PostgreSQL 단일화 — 1차 H2 제거 영향 분석·상세 설계 · 2차 운영·설정·배포 구현 · 3차 테스트·CI 단일화 · FINAL 최종 통합 검증 (2026-11-25)

**상태 : DB-1 완료 (2026-11-25) — 1차·2차·3차·FINAL 로컬 구현 및 통합 검증 완료 (2차 = 33~45장, 3차 = 46~58장, FINAL = 59~77장, 판정 76장). 남은 것 : 사용자 통합 커밋(PG-D42) 과 그 뒤 첫 원격 CI 확인 (71장). AI-6 은 그 뒤 (PG-D15).** 작업 ID 는 **DB-1** (PG-D30). 1~32장은 1차 분석·설계 기록이며 그때의 문장(예 : '결정 필요', '작업 ID 미정')을 고치지 않았다 — 최종 정책은 33장, 결정 결과는 34장. 1~58장의 '대기'·'FINAL 에서' 같은 표현은 그 시점의 기록이다.

- **근거 표기** : **[코드]** = 저장소 코드·설정을 읽어 확인 (파일 경로), **[실행]** = 격리 사본·임시 PostgreSQL 로 실제 실행해 확인 (1차 32장, 2차 41장), **[추정]** = 코드·측정에서 미루어 본 값, **[미확인]** = 저장소·실행으로 확인할 수 없음.
- 기준 커밋 : `7e8809e` (AI 공통 실행 안정성 개선, `dev`). 1차 실행은 `git archive HEAD` 사본, 2차·3차 실행은 **작업 트리 사본**(41.1·53.0)이다.
- 작업 ID (1차 당시) : 로드맵에 이 작업의 ID 가 없었다 → 2차에서 사용자가 **DB-1** 로 확정 (PG-D30).

## 1. 배경 · 목적

Lexorma 는 초기 설치 편의(별도 DB 없이 실행)를 위해 관리 DB 로 H2 와 PostgreSQL 을 함께 지원해 왔다. 기능(표준화·논리/물리 모델·ERD·원본 카탈로그·매핑·동시 편집·AI·MCP)과 정합성 요구가 커지면서 H2 를 위한 별도 SQL·잠금 분기, 두 벤더 마이그레이션, 이중 테스트 비용이 늘었다. InfraMesh 에서 확인한 H2 운영 문제와 유지보수 부담도 고려해 관리 DB 를 PostgreSQL 로 단일화한다. **InfraMesh 의 문제가 Lexorma 에서 똑같이 생긴다고 단정하지 않는다** — 이 문서는 Lexorma 코드에서 실제로 확인된 H2 비용(7·12·13장)만 근거로 쓴다.

1차 목적 : 영향 범위 전수 분석, PostgreSQL 전용 운영·개발·테스트 구조 설계, 설정 필수화·기동 실패 처리 설계, 데이터·마이그레이션 정책 검토, 배포·설치 절차 정리, 기존 동시성 테스트 안정성 검토(특히 `s4TwoRenames`), 후속 단계와 완료 기준 확정.

## 2. 사용자 확정 정책 PG-D01~D15

| ID | 확정 내용 | 이 문서에서의 해석 |
|---|---|---|
| PG-D01 | Lexorma 자체 관리 DB 는 PostgreSQL 로 단일화 | 관리 DB = 5.1 의 모든 테이블 |
| PG-D02 | H2 운영 지원 완전 제거 | `h2-docker` 프로필·`Dockerfile-h2`·`docker-compose.h2.yml` |
| PG-D03 | H2 개발 환경 지원 제거 | `h2-local` 프로필·H2 콘솔·`H2FileDatabaseCheck` |
| PG-D04 | H2 기반 테스트 환경 제거 | `application-test.yml` 의 H2 메모리 DB, H2 매개변수 테스트 |
| PG-D05 | H2 JDBC 및 관련 의존성 제거 | `com.h2database:h2`, `spring-boot-h2console` (6장) |
| PG-D06 | H2 자동 설정·fallback 제거 | Boot 의 내장 DB 자동 구성(6.3) 포함 |
| PG-D07 | PostgreSQL 연결 설정은 기동 필수 조건 | 8장 |
| PG-D08 | 별도 DB 없는 Standalone 배포 미지원 | 20장 (H2 내장 Standalone 만 해당 — 일반 단일 JAR 은 유지) |
| PG-D09 | React SPA + Spring Boot 단일 JAR 유지 | 20.3 |
| PG-D10 | Docker Compose 배포 유지 | 21장 |
| PG-D11 | 원본 DB 다중 DBMS 지원 유지 | 5.2 — H2 는 원본 대상이 아니다 |
| PG-D12 | AI·MCP 선택 기능 유지 | 25장 |
| PG-D13 | 동시성·승인·권한·프로젝트 격리 정책 유지 | 13장 |
| PG-D14 | 기존 PostgreSQL 사용자 데이터 정합성·업그레이드 호환성 보호 | 10장 — 적용된 마이그레이션은 그대로 |
| PG-D15 | PostgreSQL 단일화 완료 전 AI-6 미착수 | pending-work 순서 |

## 3. 선행 작업 · 작업 트리 상태 (읽기 전용 Git 확인)

- AI 공통 실행 안정성 개선은 **커밋됨** : `7e8809e feat: AI 공통 실행 안정성 개선 및 부분 답변 지원` (2026-11-25, 35개 파일 — 문서 `completed/ai-execution-stability.md` 포함). 그 앞 `95636a3` (D-35), `c1404f0`·`dc9841f` (M-8 FINAL) 도 커밋됨.
- 작업 트리에 **다른 세션의 `AD` 항목 7개**가 있다 (스테이징에 추가됨 + 작업 트리에서 삭제됨) : `docs/ongoing/logical-subtype.md`, `db/migration/{h2,postgresql}/V5__column_name_unique.sql`·`V6__column_required_values.sql`·`V7__column_name_unicode_normalization.sql`. 작업 트리에는 파일이 없고 HEAD 에도 없다 — **지금 상태로 커밋하면 인덱스의 V5~V7 이 마이그레이션으로 들어간다**(네 번째 합침으로 V0 에 이미 들어간 내용이라 신규 설치가 깨진다 [추정]). 이번 작업은 이 항목을 건드리지 않았다 (보고만 — 정리는 사용자 판단).

## 4. 현재 데이터베이스 지원 구조 [코드]

| 프로필 | 파일 | 관리 DB | 비고 |
|---|---|---|---|
| `local` (기본, `application.yml` `spring.profiles.active: local`) | `application-local.yml` | PostgreSQL `localhost:15439/lexorma`, 계정 `lexorma/lexorma` 고정 | DevTools 재시작 끔, Swagger, 개발 키 |
| `h2-local` | `application-h2-local.yml` | **H2 파일** `${LEXORMA_DATA_DIR:~/.lexorma/data}/lexorma` | H2 콘솔 켜짐, Swagger, 개발 키, `H2FileDatabaseCheck` |
| `dev` | `application-dev.yml` | PostgreSQL `lexorma-postgres:5432`, 계정 고정 | 포트 18080, `Dockerfile.dev` |
| `docker` | `application-docker.yml` | PostgreSQL `DB_HOST/PORT/NAME/USERNAME/PASSWORD` (기본값 `lexorma-postgres`·`5432`·`lexorma`·`lexorma`·`lexorma`) | `Dockerfile`, Compose 는 `SPRING_DATASOURCE_*` 로 덮어씀 |
| `h2-docker` | `application-h2-docker.yml` | **H2 파일** `/data/lexorma` | `Dockerfile-h2`, `docker-compose.h2.yml`, 콘솔 꺼짐 |
| `test` | `src/test/resources/application-test.yml` | **H2 메모리** `jdbc:h2:mem:lexorma-test` (환경변수 `SPRING_DATASOURCE_URL` 로 PostgreSQL 전환) | `db/test-account` 콜백, `SqlCapture`, Hikari 최소 유휴 1 |

- Flyway : `spring.flyway.locations: classpath:db/migration/{vendor}` → `db/migration/postgresql`·`db/migration/h2` 에 V0~V3 각 4개 (10장).
- 세션 : Spring Session JDBC, `SPRING_SESSION` 테이블은 V0 가 만든다 (`initialize-schema: never`).
- 배포 산출물 : `Dockerfile`(docker) · `Dockerfile-h2`(h2-docker) · `Dockerfile.dev`(dev) — 세 파일은 마지막 `CMD` 프로필·포트만 다르고 빌드 단계는 같다 [코드]. 릴리스 워크플로·실행 스크립트(sh/bat/ps1)·압축 배포·내장 Java 는 **없다** [코드 — `git ls-files` 전수].

## 5. 관리 DB 와 원본 DB 의 책임 구분

### 5.1 관리 DB (PostgreSQL 로 단일화)

V0 의 모든 `TB_*`(사용자·권한·프로젝트·표준 사전·논리/물리 모델·ERD 배치·매핑·원본 연결·수집 카탈로그·승인·변경 이력·모델 버전·AI/MCP 설정·감사 로그)와 `SPRING_SESSION*`, `flyway_schema_history`.

### 5.2 원본 DB (기존 지원 그대로 — PG-D11)

| 항목 | 현재 지원 [코드] |
|---|---|
| 대상 DBMS (`core/project/domain/enums/DbType`) | `POSTGRESQL`·`ORACLE`·`MYSQL`·`MARIADB` — **H2 없음** |
| 연결 설정 저장 | 4종 모두 (`SourceJdbcSettings`) |
| 실제 연결·수집 | PostgreSQL 만 (`SourceJdbcSettings:145` 그 밖은 `지원하지 않는 DB 유형`, `tool/postgresql/PostgresSourceTools`). JDBC 드라이버도 `org.postgresql:postgresql` 하나뿐 (다른 3종 드라이버는 JAR 에 없다) |
| 원본 비교·가져오기 규칙 | PostgreSQL 만 (`PostgresCompareRules`) |
| DDL 생성 (`table/ddl/TableDdlGenerator`) | PostgreSQL·Oracle·MySQL·MariaDB — **H2 대상 DDL 없음** |
| 물리 모델 데이터 타입·식별자 규칙 (`DbIdentifierRules`) | 위 4종 — H2 없음 |

→ **H2 는 원본 수집 대상도, DDL·물리 타입 대상도 아니다.** 작업 지시 5.3 의 "원본 DB 로서의 H2 · H2 DDL" 판단 항목은 해당 기능이 없으므로 결정이 필요 없다 (31장 PG-Q02·Q03 은 '해당 없음 확인' 으로 닫기를 추천).

주의 : 원본 PostgreSQL 수집 도구(`PostgresSourceTools`)와 관리 DB 의 PostgreSQL 전용 SQL 은 같은 DBMS 라도 **다른 책임**이다. 단일화 작업에서 원본 쪽 SQL(`pg_catalog` 조회 50곳 등)은 건드리지 않는다.

## 6. 모듈 · 의존성 분석

### 6.1 의존성 [실행 — `./gradlew :<모듈>:dependencies` (격리 사본)]

| 모듈 | H2 관련 의존성 | 사용 목적 | 제거 가능 | 영향 |
|---|---|---|---|---|
| `lexorma-app` | `runtimeOnly 'com.h2database:h2'` (→ 2.4.240) | h2-local·h2-docker·test 관리 DB | 가능 | runtime·testRuntime 모두에서 빠진다 |
| `lexorma-app` | `runtimeOnly 'org.springframework.boot:spring-boot-h2console'` (→ 4.1.2, **전이로 h2 2.4.240 을 다시 끌어온다**) | `/h2-console` | 가능 — **반드시 함께 제거** (하나만 빼면 h2 jar 가 남는다) | `SpaForwardController` 제외 목록의 `h2-console` 정리 |
| `lexorma-common`·`core`·`system`·`operation`·`data`·`source`·`ai` | 없음 (`testRuntimeClasspath` 에 `com.h2database` 0건) | — | — | 단위 테스트는 DB 없이 돈다 (15장) |
| `lexorma-data` 컴파일 | `org.hibernate.dialect.H2Dialect` **클래스 참조** 6곳 (7.2) | H2 분기 | 코드 정리로 제거 | H2Dialect 는 hibernate-core 안의 클래스라 jar 제거와 무관하게 컴파일된다 — 남겨도 빌드는 되지만 죽은 분기 |
| 공통 | `httpcore5-h2` 5.4.4 | **HTTP/2** (httpclient5 전이) — H2 DB 와 무관 | 제거 대상 아님 | 검색 오탐 주의 |

- PostgreSQL 쪽 : `runtimeOnly 'org.postgresql:postgresql'` (42.7.14), `flyway-database-postgresql` (12.4.0), `spring-boot-starter-flyway`. Testcontainers 의존성 **없음**. 커넥션 풀 = HikariCP 7.0.2 (Boot 기본). Hibernate 7.4.13 (Dialect 는 자동 판별 — 설정에 `dialect` 지정 없음).
- **단일 JAR 내부** [실행 — 기존 `lexorma-app/build/libs/lexorma.jar` (2026-11-22 빌드) 목록] : `BOOT-INF/lib/h2-2.4.240.jar` (2.6MB), `spring-boot-h2console-4.1.2.jar`, `application-h2-local.yml`·`application-h2-docker.yml` 포함. 2차 후 JAR 에서 네 항목이 사라졌는지 확인한다 (30장).
- 직접 의존성 두 줄 제거로 충분하다 — 다른 라이브러리가 h2 를 전이로 가져오지 않는다 (트리 전수 확인). 전이 제외(`exclude`) 설정은 필요 없다. 신규 운영 의존성 없음.

### 6.2 Gradle 설정의 H2 흔적

- 루트 `build.gradle:56-58` 테스트 힙 주석 ("H2 메모리 DB 의 데이터도 힙에 있어 … 512MB 부족") — 값(`maxHeapSize = '2g'`)은 PostgreSQL 실행에서도 필요한지 18장에서 따로 본다. 주석만 고친다.

### 6.3 H2 자동 설정 / fallback 경로 [코드 + Spring Boot 동작]

1. **명시 프로필** `h2-local`·`h2-docker`·`test` — 설정 파일이 H2 URL 을 준다.
2. **암묵 fallback** : `spring.datasource.url` 이 없는 프로필(예 : 오타 프로필 `--spring.profiles.active=prod`)로 띄우면 Boot 의 `DataSourceAutoConfiguration` 이 클래스패스의 H2 로 **내장 메모리 DB 를 자동 구성**하고 Flyway 가 H2 마이그레이션을 적용해 정상 기동한다 — 재시작하면 데이터가 사라지는 서버가 조용히 뜬다. 실측은 32장 (J-1). H2 jar 를 빼면 이 경로는 Boot 가 `Failed to configure a DataSource: 'url' attribute is not specified and no embedded datasource could be configured` 로 막는다.
3. 코드의 H2 자동 선택 로직(URL·프로필로 H2 를 고르는 Java 코드)은 **없다** — fallback 은 Boot 기본 동작뿐이다.

## 7. H2 관련 코드 전수 조사

검색 : `h2`·`jdbc:h2`·`org.h2`·`H2Dialect`·`h2console`·`h2-local`·`h2-docker`·`mv.db`·`DB_CLOSE_*`·`MODE=`·`AUTO_SERVER`·`NON_KEYWORDS`·`50200`·`40001`·`INFORMATION_SCHEMA.SESSIONS`·`getDatabaseProductName`·`{vendor}` (테스트·문서 포함). `MODE=PostgreSQL`·`AUTO_SERVER`·`DATABASE_TO_LOWER`·`NON_KEYWORDS`·`LOCK_TIMEOUT`·`MV_STORE` 설정은 **어디에도 없다** (H2 는 기본 모드로 돈다 — 그래서 SQL 분기가 생겼다). 분류 : ① 제거 ② PostgreSQL 전용으로 단순화 ③ 원본 DB 기능 유지 ④ 과거 기록 보존 ⑤ 사용자 결정.

### 7.1 설정 · 기동 · 배포 (운영)

| 위치 | 실제 사용 목적 | 분류 |
|---|---|---|
| `lexorma-app/build.gradle:22-24` h2·h2console | H2 드라이버·콘솔 | ① |
| `application-h2-local.yml` | H2 파일 DB 로컬/Standalone | ① |
| `application-h2-docker.yml` | H2 Docker 이미지 | ① |
| `application.yml:8` `profiles.group.h2-local`, `:31`·`:34-35`·`:41` 주석·`flyway.locations {vendor}` | H2 프로필 그룹·벤더별 위치 | ② (`{vendor}` → 10.3 결정) |
| `application-debug-log.yml` 주석 | h2-local 언급 | ② |
| `application-local.yml:1`·`:34-37` 주석 | `docker/docker-compose.yml` 경로(이미 `lexorma/` 로 옮겨짐)·h2-local 언급 | ② |
| `config/H2FileDatabaseCheck` (BeanFactoryPostProcessor, `@Component`) | H2 파일 경로·쓰기 권한·파일 잠금 확인 | ① (8.3 의 PostgreSQL 확인으로 대체 후보) |
| `home/controller/SpaForwardController:26` `h2-console` 제외 | 콘솔 경로를 SPA 로 넘기지 않음 | ② (목록에서 뺀다 — 그 경로는 SPA 로 간다) |
| `core/security/conf/SecurityConfig:82` 주석 | 콘솔 언급 | ② |
| `Dockerfile-h2` | H2 이미지 | ① |
| `lexorma/docker-compose-exam/docker-compose.h2.yml` | H2 Compose | ① |
| `lexorma/docker-compose-exam/.env.example` `LEXORMA_H2_IMAGE`·`LEXORMA_DATA_DIR` 주석 | H2 이미지·H2 파일 위치 | ② (`LEXORMA_DATA_DIR` 마운트는 21.2 결정) |
| `.gitignore` `*.mv.db`·`*.trace.db` | 실수로 H2 파일 커밋 방지 | ⑤ — 남겨도 해가 없음 (기존 H2 파일이 저장소에 생기는 사고 방지, 남기기 추천) |
| `.dockerignore` `data` | H2 기본 데이터 폴더 | ② (남겨도 무해) |

### 7.2 관리 DB SQL · 동시성 분기 (운영 코드)

| 위치 | 현재 동작 | 분류 |
|---|---|---|
| `data/column/repository/ColumnNameKeySql.key` (`instanceof H2Dialect`) | 컬럼명 비교 값 : H2 `FN_UPPER_ROOT(trim(x))` / PG `upper(trim(x) collate "und-x-icu") collate "C"` | ② |
| `data/mapping/repository/StandardNameSql` 3곳 | ASCII 판정·대문자·trim : H2 `regexp_like … \z`·`translate`·`regexp_replace(…\z)` / PG `~`·`upper(collate "C")`·`regexp_replace(…,'g')` | ② |
| `data/mapping/repository/PhysicalNameRecommendationQueryRepository.scansBoundInListPerRow` + `PhysicalNameRecommendationService.JAVA_JUDGE_MIN_ROWS_H2 = 500` | H2 의 바인딩 IN 목록 비용 때문에 자바 판정 전환 기준을 DB 별로 (H2 500 / 그 밖 10,000) | ② (항상 10,000 — 상수 하나·메서드 제거) |
| `data/physical/service/impl/PhysicalModelServiceImpl:844` `keyShare = !(H2Dialect)` | 참조 동기화의 대상 행 `FOR KEY SHARE` 선잠금을 H2 에서는 생략 | ② (항상 KEY SHARE — PostgreSQL 은 지금도 이 경로) |
| `data/physical/sync/ReferenceSyncBoundary.recheckOnH2` + `synced` 집합·`rechecked` | **H2 전용 커밋 직전 재확인** (M8-C4b-R1) : 프로젝트 행을 잡고 동기화한 테이블을 다시 맞춤 | ① (PostgreSQL 은 즉시 return — 13장) |
| `ModelRowLocks.lockNotAvailable`·`PhysicalGenerationService`·`PhysicalNameRenameService`·`SourceImportService` 의 `getErrorCode() == 50200` | NOWAIT 실패 판정 : PG `55P03` ∨ H2 50200 | ② (`55P03` 만) |
| `SourceImportService:186` 주석 `H2 40001` | 교착 코드 설명 | ② (주석) |
| `common/exception/CustomException:14` 주석 | LOCKED 원인 설명의 H2 50200 | ② (주석) |
| `common/jpa/ModelBase:17` 주석 (1µs 비교 — PG 반올림·H2 버림) | 비교 허용 오차의 근거 | ② (주석만, **1µs 허용은 유지** — JSON 왕복·메모리 값 자릿수 차이도 근거) |
| `ai/dictionary/service/AiDescriptionEvaluationService:133` 주석 (H2 때문에 프로젝트 행을 먼저 잡지 않음) | 잠금 순서 설계 근거 | ② 주석만. **잠금 순서는 바꾸지 않는다** (PG-D13 — PostgreSQL 에서도 맞는 설계이며 바꿀 이유가 생기지 않는다) |
| `ErdGraphRepository:32`·`ErdGraphRepositoryImpl:102`·`LogicalModelService:979`·`LogicalModelQueryRepository:55`·`SubtypePlanContexts:62`·`PhysicalNameRecommendationService:167-197`·`PhysicalModelServiceImpl:109`·`:1058`·`ModelRowLocks:32`·`ColumnNameKey:18-19,46` | H2 측정·동작을 근거로 쓴 주석 (쿼리 모양 선택 등) | ② 주석 — **쿼리는 바꾸지 않는다** (PostgreSQL 에서도 맞고 측정 근거가 없으면 JPQL→네이티브 등 재작성 금지) |

### 7.3 테스트 (15·16장에서 상세)

| 위치 | 분류 |
|---|---|
| `application-test.yml` H2 URL·`driver-class-name: org.h2.Driver` | ① → PostgreSQL 필수 (17장) |
| `H2FilePersistenceTest` (4건) | ① (기능 자체가 없어짐) |
| `SubtypeStrategyConversionMigrationTest.h2FileDatabaseKeepsCheckAcrossConnectionsAndRestart` | ① (H2 2.4.240 CHECK 결함 V3 회귀 — PostgreSQL 쪽 같은 제약 검사는 `freshInstall[postgresql]` 가 이미 함) |
| 벤더 매개변수 마이그레이션 테스트 15개 클래스의 `h2` 매개변수 | ① (postgresql 매개변수는 유지) |
| 별도 컨텍스트 6개 (`InitialSetupHttpTest`·`ProjectAccessRevokeTest`·`ProjectRecoveryLoginTest`·`RequestLogStatusHttpTest`·`SessionReloginHttpTest`·`ViewAuthorizationHttpTest`)의 H2 메모리 대체 분기 | ② (PostgreSQL 임의 스키마 분기만 남김) |
| `postgres()`·`h2()` 판별 분기 (동시성 테스트 11개 클래스의 `INFORMATION_SCHEMA.SESSIONS` 감시, `ColumnNameUnicodeMigrationTest`·`ColumnNameUniquenessIntegrationTest`·`PhysicalNameRecommendationConflictIntegrationTest`·`InheritanceReferenceKeyIntegrationTest`·`ConcurrencyDiagnostics`) | ② |
| `assumeTrue(postgres())` (RelationSyncDeadlock 13·InheritanceStrategyConcurrency 21·InheritanceConcurrency 12·AlternateKeyConcurrency 5·PostgresLockWaitWatch 2) | ② (가정 제거 — 항상 실행) |
| `SecretKeyConfigurationTest` 의 `h2-docker`·`h2-local` 프로필 반복 | ② (프로필 목록에서 뺌) |
| `lexorma-data` `SourceImportLockErrorTest` (H2 50200·40001 모양 단언) | ② (H2 경우 삭제, PG 55P03·40P01 유지) |
| `PhysicalNameJudgedPathIntegrationTest` (기준값 DB 별) | ② — 16.3 의 검증 범위 변화 주의 |
| 성능 시험(`LEXORMA_PERF`) 의 H2 분기 (`PhysicalNameF02PocTest`·`PhysicalNameNonAsciiScaleTest`·`LogicalM6FinalPerformanceTest`) | ② |

### 7.4 문서 (26장)

`README.md` 21줄, `CLAUDE.md` 약 12곳, `docs/reference/h2-file-storage.md`(전체), `reference/*-roadmap.md`(기술 서술), `completed/*` 141곳 이상(검증 기록). 완료 기록은 ④ 보존.

## 8. 데이터베이스 연결 · 기동 흐름

### 8.1 현재 기동 흐름 [코드]

1. 설정 로딩 (`application.yml` + 프로필, 기본 `local`). `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/` (Compose) 로 비밀값 파일.
2. `H2FileDatabaseCheck` (BeanFactoryPostProcessor) — `jdbc:h2:file:` 일 때만 경로·잠금 확인.
3. DataSource(Hikari) 생성 → Flyway 자동 구성이 기동 중 `migrate` (첫 연결 = 여기서 실패하면 기동 실패).
4. JPA `ddl-auto: validate` → 스키마 불일치면 기동 실패.
5. Spring Session JDBC (테이블 존재 가정), 스케줄러(세션 정리·요청 로그 보관).
6. 화면 : `TB_ACNT` 가 비면 `/setup` (최초 관리자), 아니면 로그인.

DB 연결 오류 메시지 [실행 — 32.3 J-2~J-5] : 종료 코드 1 로 기동이 멈추고, 원인은 `Error starting Tomcat context …` 아래 긴 `Caused by` 사슬 중간에 나온다 (`PSQLException: … 연결이 거부되었습니다` / `FATAL: password authentication failed for user "lexorma"` / `FATAL: database "no_such_db" does not exist` / `ClassNotFoundException: com.mysql.cj.jdbc.Driver`). Boot 의 요약(`APPLICATION FAILED TO START`)은 나오지 않는다 — 원인을 찾으려면 스택을 읽어야 한다. 네 경우 모두 **비밀번호는 출력되지 않았다**. 단, **비밀번호를 JDBC URL 쿼리(`?password=…`)에 넣고 정상 접속하면 Hibernate 기동 정보 로그 `Database JDBC URL [jdbc:postgresql://…?user=…&password=<원문>]` 에 비밀번호 원문이 남는다** [실행 — J-7b, 현재 동작이며 H2 와 무관]. 8.2 의 관리 DB 확인은 URL 에 `password=` 가 있으면 경고하거나 거절한다 (PG-Q09).

### 8.2 PostgreSQL 필수화 후 기동 흐름 (설계)

1. 설정 로딩 (기존 체계 그대로 — 새 설정 체계 없음).
2. **관리 DB 설정 확인** (기존 `H2FileDatabaseCheck` 자리, 같은 BeanFactoryPostProcessor 방식 — DataSource·Flyway 보다 먼저) : `spring.datasource.url` 이 없거나 비면 → 시작 중단 + 설정 방법 안내 (환경변수 이름·Compose `.env`). `jdbc:postgresql:` 가 아니면 → `지원하지 않는 관리 DB` 로 중단 (예 : `jdbc:h2:`·`jdbc:mysql:`). 메시지에 비밀번호·URL 의 비밀 매개변수 값을 넣지 않는다.
3. **연결 검증** : Hikari 기본(`initializationFailTimeout=1`)이 첫 연결 실패를 기동 실패로 만든다 — 그대로 쓴다. 원인별 안내(8.4)는 선택 : Boot `FailureAnalyzer` 하나로 SQLSTATE(`28P01` 인증, `3D000` DB 없음, `08001`/`08006` 연결 불가)를 짧은 안내 문장으로 바꾸는 정도 (결정 PG-Q09). 새 웹 DB 설정 마법사는 만들지 않는다.
4. Flyway 마이그레이션 (기존 자동 구성) → 실패하면 기동 실패 (repair·clean·baseline 자동 수행 없음).
5. JPA validate → 애플리케이션 초기화.
6. 최초 관리자 설정 또는 로그인 (기존 그대로).

### 8.3 상황별 처리

| 상황 | 현재 (H2 jar 있음) | 2차 후 권장 처리 |
|---|---|---|
| DB 연결 설정 없음 (URL 없는 프로필) | **H2 메모리로 정상 기동** — `jdbc:h2:mem:<UUID>` 사용자 `SA`, Flyway V0~V3 적용, 8.7초 만에 `Started` (6.3, J-1 실측) | 8.2-② 에서 중단 : "관리 DB(PostgreSQL) 연결 설정이 없습니다 — SPRING_DATASOURCE_URL 또는 DB_* …" (Boot 기본 메시지도 실패는 한다) |
| 잘못된 JDBC URL (형식 오류) | 드라이버 오류로 실패 [추정 — 형식 오류 단독은 미실측] | 8.2-② 또는 드라이버 오류로 실패 (원문 유지, 비밀값 마스킹) |
| PostgreSQL 이외 관리 DB URL | `jdbc:h2:` 는 정상 기동, 그 밖은 드라이버 없음 오류 (`jdbc:mysql:` → `ClassNotFoundException`, J-2) | 8.2-② 에서 `지원하지 않는 관리 DB` 로 중단 |
| DB 서버 연결 불가 | Hikari → 기동 실패 (J-3) | 같음 + (선택) 안내 문장 |
| 인증 실패 | 기동 실패, 메시지에 사용자명만 (J-4) — 비밀번호 미출력 | 같음 |
| 대상 DB 없음 | 기동 실패 `database "…" does not exist` (J-5) | 같음 + (선택) "DB 를 먼저 만드세요" |
| ICU 없는 PostgreSQL 빌드 | 신규 설치 V0 의 ICU 확인 문장이 실패 → Flyway 실패 | 같음 (기존 동작) |
| Flyway 실패 (checksum 불일치 등) | 기동 실패 | 같음 — 자동 repair 없음 |
| 정상 연결 | 기존 흐름 | 같음 |

- H2 자동 전환은 어떤 경우에도 하지 않는다. DB 오류를 숨기지 않는다 (재시도로 기동을 붙잡아 두지 않음 — Compose 는 healthcheck 로 순서를 맞춘다).
- 최초 관리자 설정 화면(`/setup`)은 관리 DB 가 연결된 뒤의 기능이라 바뀌지 않는다.

## 9. 필수 설정 최소화 · 설정 호환성

| 구분 | 설정 | 비고 |
|---|---|---|
| 필수 | `spring.datasource.url` (= `SPRING_DATASOURCE_URL`) 또는 `docker` 프로필의 `DB_HOST`·`DB_PORT`·`DB_NAME` (URL 을 만든다) | 둘 다 기존 키 |
| 필수 | 사용자명 `spring.datasource.username` / `DB_USERNAME` | 기존 키 |
| 필수 | 비밀번호 `spring.datasource.password` / `DB_PASSWORD` | 기존 키 — **기본값 처리는 PG-Q09** |
| 선택 | Hikari(`spring.datasource.hikari.*`), SSL(`sslmode` 등 URL 매개변수), 타임아웃, AI Provider, MCP(`LEXORMA_MCP_ENABLED`), 원본 연결, 암호화 키(`LEXORMA_SECRET_KEYS`), Docker Secrets(`SPRING_CONFIG_IMPORT`) | 모두 기존 그대로 |

- **기존 PostgreSQL 사용자의 설정 변경 필요 여부** : `local`·`dev`·`docker` 프로필, Compose(`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`)는 키·값 모두 그대로 쓸 수 있다 → **변경 불필요** [코드]. 변경이 생기는 사용자는 `h2-local`·`h2-docker`·`Dockerfile-h2` 사용자뿐이다 (11장).
- Docker Compose 기본 실행은 `.env` 의 `POSTGRES_DB/USER/PASSWORD` 를 DB 컨테이너와 앱 컨테이너에 함께 넘기므로 사용자가 연결 정보를 두 번 쓰지 않는다 (지금 구조 그대로).
- 불안전한 기본값 : `application-docker.yml` 의 `DB_PASSWORD:lexorma`, `local`·`dev` 의 고정 `lexorma/lexorma`. `local`·`dev` 는 개발 전용이라 유지 추천. `docker` 프로필의 기본 비밀번호는 기존 `docker run` 사용자 호환 때문에 남아 있다 — 제거하면 기본값에 기대던 설치가 기동 실패한다 (데이터 손실은 없음). **결정 PG-Q09** (추천 : 2차에서는 유지하고 README 에 '새 설치는 반드시 지정' 경고를 남긴다 — 기본 정책 변경은 단일화 범위 밖).
- 기본 프로필 `local` (계정 고정, localhost:15439) 은 개발 편의 — 운영 JAR 을 프로필 없이 띄우면 로컬 개발 DB 에 붙으려다 연결 실패한다 (H2 로 넘어가지 않음). 유지 추천 (PG-Q09 에 포함).

## 10. Flyway · 스키마

### 10.1 현재 구조 [코드]

| 버전 | postgresql | h2 | 차이 |
|---|---|---|---|
| V0__init.sql | 3,097줄 | 3,094줄 | 21줄 : Spring Session 의 `BYTEA`↔`LONGVARBINARY`, `COL_NM_CMPR_VL` 생성 컬럼(PG ICU `collate "und-x-icu"` + 컬럼 `collate "C"` + ICU 존재 확인 / H2 `create alias FN_UPPER_ROOT`), 주석 |
| V1__seed_data.sql | 6,732줄 | 6,732줄 | 시퀀스 문법 `nextval('SQ_…')` ↔ `next value for SQ_…` (거의 모든 줄) |
| V2__subtype_strategy_conversion.sql | 228줄 | 228줄 | 시퀀스 문법 |
| V3__fix_subtype_conversion_check.sql | 19줄 | 19줄 | 같음 (H2 CHECK 결함 회피로 만든 CASE 식 — PostgreSQL 에도 적용됨) |

- 공통 마이그레이션 디렉터리·플레이스홀더는 없다. DBMS 별 차이는 위뿐이다.
- PostgreSQL 전용 기능 : `generated by default as identity`(1), 생성 컬럼 `stored`(1), ICU 콜레이션, `pg_collation` 확인 — 최소 버전 영향은 22장.

### 10.2 H2 제거 후 구조 (설계)

- **`db/migration/postgresql/` 를 그대로 두고** `locations` 를 `classpath:db/migration/postgresql` 로 고정 (또는 `{vendor}` 유지 — 단일 벤더면 같은 결과). `db/migration/h2/` 4개 파일과 `db/test-account/afterMigrate__test_admin.sql` 의 벤더 무관성은 그대로.
- **디렉터리를 옮기지 않는 이유** : `flyway_schema_history.script` 에는 위치 기준 파일명만 남고(`V0__init.sql` … — J-7 의 새 DB 에서 확인 [실행], 체크섬 V0 `817513475`·V1 `1442848106`·V2 `-1266441869`·V3 `-1874427824`) 검증은 버전·설명·체크섬으로 하므로 옮겨도 통과할 것이다 [추정 — 이동 자체는 미실측]. 하지만 얻는 것이 없고 위험만 있으므로 **이동하지 않는다** (추천).
- 기존 PostgreSQL 마이그레이션 파일은 **한 글자도 바꾸지 않는다** (주석의 H2 언급 포함 — 체크섬). V3 의 "H2 결함 회피" 식도 그대로.
- **신규 마이그레이션 필요 여부** : 스키마·데이터를 바꿀 이유가 없다 → **V4 불필요** (추천). H2 를 위해 PostgreSQL 쪽에 따로 들어간 스키마 객체는 없다 (V3 의 CASE 식은 PostgreSQL 에서도 정상 동작하는 동등 식).
- Flyway `repair`·`clean`·`baseline` 자동 수행 없음. 필요하다는 근거도 없다.
- 예제 동기화 : `V0SeedMigrationTest` 등은 예제(project 0) 가 실제 스키마와 같은지 PostgreSQL 로도 이미 비교한다 → 유지.

### 10.3 업그레이드 호환성

| 대상 | 판정 |
|---|---|
| 현재 V0~V3 로 만든 PostgreSQL DB | 파일·체크섬 불변 → 그대로 시작 (J-6 에서 HEAD 로 만든 DB 를 2차 JAR 로 다시 띄워 확인 — FINAL) |
| 네 번째 합침(2026-11-22) 이전의 V0~V5 로 만든 DB | 이미 이어서 쓸 수 없음 (기존 정책, 이번 작업과 무관) |
| 신규 빈 PostgreSQL | V0~V3 적용 → 최초 관리자 설정 (J-7) |
| `{vendor}` → `postgresql` 고정 | 위치 이름만 바뀌고 파일은 같음 → 영향 없음 |

## 11. 기존 H2 사용자 데이터 처리

- **공식 H2 배포 방식** [코드] : `h2-local` (단일 JAR·bootRun, `~/.lexorma/data/lexorma.mv.db`), `h2-docker` 이미지 (`Dockerfile-h2`, `/data/lexorma.mv.db`), `docker-compose.h2.yml` (`LEXORMA_DATA_DIR` 마운트). 백업 = 정지 후 `.mv.db` 복사 (`reference/h2-file-storage.md`). **H2 → PostgreSQL 이전 기능·절차는 없다**.
- 릴리스 파일 : 저장소에 릴리스 워크플로·배포 아카이브가 없다. Docker Hub 등 외부 레지스트리에 `lexorma/lexorma:h2-latest` 가 올라간 적이 있는지는 **[미확인]**.
- **실제 외부 사용자의 H2 사용 여부는 저장소만으로 확인할 수 없다 [미확인].** 참고 사실 : 2026-11-15·18·22 의 Flyway 합침이 "서비스 사용자가 아직 없다" 는 사용자 결정에 근거했고 (CLAUDE.md), 그 뒤 H2 DB 도 V0~V3 기준이다. 이 사실로 사용자 데이터가 없다고 가정하지 않는다.

| 대안 | 구현 비용 | 데이터 손실 위험 | 사용자 편의 | 유지보수 |
|---|---|---|---|---|
| A. 신규 버전부터 PostgreSQL 만 (이전 기능 없음, H2 파일은 지우지 않음, 이전 버전으로 열람) | 문서 몇 줄 | 사용자가 새 버전에서 데이터를 못 본다 (파일은 남음 — 손실 아님) | 낮음 (수동 재입력 또는 모델 Export/Import) | 없음 |
| B. 일회성 이전 가이드 | 문서 + 실제 이전 검증 1회 (반나절~1일 [추정]) | 절차 실수 가능 — 검증 절차 포함 시 낮음 | 중간 | 문서만 (H2 버전이 바뀌면 낡음) |
| C. 별도 일회성 이전 도구 | 별도 모듈/스크립트 + 테이블·시퀀스·제약·생성 컬럼·세션 테이블 처리 + 검증 (수일 [추정]) | 도구 결함 위험 | 높음 | 높음 (H2 드라이버를 도구에 다시 넣어야 함, 운영 JAR 밖) |

- 부분 대안 (구현 0) : 기존 **모델 교환(DEC-22) Export → PostgreSQL 설치에서 Import** 로 논리·물리 모델·ERD 배치는 옮길 수 있다 [코드]. 사용자·표준 사전 전체·원본 연결·이력·버전·AI 기록은 대상이 아니다 — 가이드에 "부분 이전" 으로만 적는다.
- **추천 : A + 위 부분 이전 안내 한 단락** (실제 H2 사용자 데이터가 확인되면 B 로 올린다). 결정 **PG-Q01**.

## 12. SQL · DBMS 호환성 코드

| 코드 위치 | 현재 동작 | H2 제거 후 | 변경 | 위험 |
|---|---|---|---|---|
| `ColumnNameKeySql.key` | Dialect 분기 | PG 식 상수 | 단순화 | 없음 (PG 경로 그대로) |
| `StandardNameSql` (plain/upper/trim) | Dialect 분기 | PG 식만 | 단순화 | 없음 |
| `PhysicalNameRecommendationQueryRepository.scansBoundInListPerRow` / `JAVA_JUDGE_MIN_ROWS_H2` | H2 500 | 10,000 고정 | 메서드·상수 제거 | 16.3 (테스트 경로 범위) |
| `PhysicalModelServiceImpl.keyShare` | H2 false | 항상 true | 분기 제거 | 없음 (PG 는 지금도 true) |
| `ReferenceSyncBoundary.recheckOnH2` | H2 만 실행 | 없음 | 메서드·`synced`·`rechecked` 제거 | 13장 |
| NOWAIT 판정 `55P03 ∨ 50200` (4곳) | 두 DB | `55P03` | 단순화 (4곳이 같은 판정 — 2차에서 `ModelRowLocks.lockNotAvailable` 하나로 모을지는 선택, 동작 변경 없음) | 낮음 |
| JPQL·QueryDSL 전반 | 두 DB 공통 | 그대로 | **변경 없음** | — |
| 네이티브 잠금 SQL (`for update nowait`·`for key share [nowait]`·`for no key update`) | PG 의미로 작성, H2 는 배타로 해석 | 그대로 | 없음 | — |
| `ColumnNameDbKeys` (`values` 목록 네이티브) | 공통 SQL + `key()` | 그대로 | 없음 | — |
| 정렬·NULL 정렬·대소문자 비교·페이지·시퀀스(`SEQUENCE` id)·Boolean·JSON(문자열 저장)·트랜잭션 격리(READ COMMITTED, 버전 생성) | JPA 공통 | 그대로 | 없음 | — |
| UPSERT·MERGE | 관리 DB 에서 쓰지 않음 | — | — | — |
| 원본 수집 `PostgresSourceTools`·`SourceJdbcErrors`·`SourceToolSession` | 원본 DB 용 | 그대로 (③) | 없음 | — |
| `TableDdlGenerator`·`DbIdentifierRules` (4 DBMS) | 출력 DDL 용 | 그대로 (③) | 없음 | — |

공통 JPQL 을 PostgreSQL 네이티브로 바꾸는 일은 하지 않는다. 주석 정리는 동작을 바꾸지 않는 범위로 한정한다.

## 13. 동시성 제어 · M-8 영향

- 유지 대상 (PG-D13) : 프로젝트 행 배타·공유(`ProjectWriteGuard` — 커밋 직전 `PESSIMISTIC_READ` = PG `FOR SHARE`), 행 잠금(`findForUpdate` = `FOR NO KEY UPDATE`), NOWAIT(`ModelRowLocks`), 키 강도 `FOR UPDATE NOWAIT`, 참조 동기화 `FOR KEY SHARE`, DEC-08 기준값(`ModelBase`), `MODEL_CONFLICT`·`LOCKED`, 삭제 하위 구성 검증, 승인 기준 상태, 매핑 동시성, FK·PK 변경, REF_COLUMN 동기화, 커밋 직전 재동기화(`ReferenceSyncBoundary` 의 공통 부분).
- **H2 전용으로 추가된 코드** : ① `ReferenceSyncBoundary.recheckOnH2` (M8-C4b-R1, PostgreSQL 은 첫 줄에서 return), ② `PhysicalModelServiceImpl` 의 KEY SHARE 생략 분기, ③ NOWAIT 판정의 H2 코드 50200, ④ 테스트의 H2 세션 감시(`INFORMATION_SCHEMA.SESSIONS`). 이 넷은 PostgreSQL 경로에서 쓰이지 않는다 [코드] → 제거해도 PostgreSQL 동작은 같다.
- PostgreSQL 과 **함께 쓰는** 경로 (유지) : `ReferenceSyncBoundary` 의 커밋 직전 동기화 루프(최대 3회), `ProjectWriteGuard`, `AiDescriptionEvaluationService` 의 잠금 순서(H2 를 이유로 정했지만 PostgreSQL 에서도 올바름 — 바꾸지 않음).
- 위험 해소 : pending-work [M-8] 의 남은 위험 ② "H2 커밋 직전 재확인의 잠금 시간 초과 가능성" 은 그 코드가 없어지면서 함께 사라진다 (해결이 아니라 대상 소멸로 기록).
- 잠금 범위 확대·동시성 재설계 없음. H2 에서 '배타로 해석되던' 공유 잠금 때문에 H2 에서만 생기던 대기·교착 우회(예 : 평가 등록의 프로젝트 행 미선점)는 그대로 둔다.

## 14. `RelationSyncDeadlockIntegrationTest.s4TwoRenames` 간헐 실패 조사

### 14.1 알려진 사실 (기록)

M-8 이후 간헐 실패 — 7회 중 1회 (M8-C4a, 'B 가 10초 안에 잠금 대기로 관찰되지 않음'), AI 공통 실행 안정성 최종 검증 묶음 3회 중 1회 (단언 `B 는 A 가 끝날 때까지 프로젝트 행에서 기다린다` 실패, 감시가 본 문장이 `TB_PRJ` 가 아닌 `select … from tb_tbl … where tbl_sn=$1 and prj_sn=$2`), 그 뒤 PostgreSQL 전체 2회 통과 (`completed/modeling-concurrency-m8.md` 16.4·23장, `completed/ai-execution-stability.md` 17장).

### 14.2 코드 분석 [코드]

- 시나리오 : 스키마 S1·S2 의 테이블 TA·TB, 서로 상대의 새 이름을 미리 참조하는 컬럼. A = TA→TA_NEW 이름 변경을 `AFTER_SYNC_FLUSH` 에서 멈춤, B = TB→TB_NEW 이름 변경. 기대 : B 는 `updateTable` 의 키 변경 경로 첫 잠금 `projectRepository.findByIdForUpdate` (TB_PRJ `FOR UPDATE`) 에서 A 를 기다린다.
- 감시 (`waitForLockWaitOnce`) : `select left(query, 600) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()` — **B 의 PID 로 거르지 않는다.** 같은 DB 의 아무 잠금 대기 세션이나 처음 보이면 그 문장을 B 의 대기 문장으로 기록하고 A 를 놓는다. 테스트 DB 는 전체 실행·묶음 실행에서 모든 테스트 클래스·컨텍스트가 함께 쓴다.
- **보존된 직전 실패의 전체 문장** (scratchpad `runs/20261125-093450-bundle3/summary.txt`, 2026-11-25) : `select ti1_0.tbl_sn,…,ti1_0.mdfr_id from tb_tbl ti1_0 where ti1_0.tbl_sn=$1 and ti1_0.prj_sn=$2` — **잠금 절이 없고 잘리지도 않았다** (약 300자 < 600). 이것은 `TableInfoRepository.findByIdAndProjectId` 의 SQL 이며, **B 의 `updateTable` 이 맨 처음 실행하는 문장**(`TableDesignServiceImpl:241`, 잠그기 전 판정용 조회)과 같다. 그 다음 문장이 기대한 대기 `select … from tb_prj … for no key update` (`projectRepository.findByIdForUpdate`)다 [코드].
- 잠금 절 없는 SELECT 는 행 잠금을 기다리지 않는다. 테이블 수준 잠금(AccessExclusiveLock — DDL·autovacuum truncate)이면 기다릴 수 있지만, 이번 외부 감시 4회(대기 관찰 2,139회)에서 relation 잠금 대기는 0회, 모두 `transactionid` 대기였다 [실행].
- **`pg_stat_activity` 의 열 불일치** [실행 — 32.4 결정적 재현] : PostgreSQL 은 세션의 `state`·`query` 를 조회(또는 트랜잭션) 시작 때 한꺼번에 복사하지만 `wait_event_type`·`wait_event` 는 행을 만들 때 그 세션의 현재 값을 읽는다. 그래서 한 행이 **'직전 문장 + 지금 대기'** 를 함께 보여 줄 수 있다 — 재현에서 같은 스냅숏 안의 행이 `idle in transaction | Lock | transactionid | select count(*) from tb_tbl …` 였다 (실제로 기다리는 문장은 `tb_prj … for no key update`).
- 감시(`waitForLockWaitOnce`)는 `wait_event_type = 'Lock'` 만으로 거르고 `state`·PID 를 보지 않는다. B 가 첫 조회를 마치고(`idle in transaction`, `query` = TB_TBL 조회) 다음 문장으로 프로젝트 행 대기에 들어가는 순간과 감시 문장의 상태 복사가 겹치면, 감시는 **B 의 직전 문장**을 대기 문장으로 기록하고 곧바로 A 를 놓는다. 이후 B 는 실제로 프로젝트 행을 기다렸다가 성공하므로 `r[0]`·`r[1]` 은 OK, `r[2]` 단언만 실패한다 — 보존된 실패와 증상이 정확히 같다.
- 같은 감시 문장의 문장 단언 (`containsIgnoringCase("tb_prj")`) 은 S4(:299) 외에도 :546·:647·:690 에 있고, '기다리지 않는다' 단언(:385·:406·:433·:520·:579·:723)은 PID 로 거르지 않아 DB 의 다른 세션 대기에도 걸릴 수 있다 [코드].

### 14.3 이번 반복 실행 (외부 감시 — 테스트 수정 없음)

- 동시성 묶음 (`*Concurren*`·`*Deadlock*`·`*LockWait*`·`*SourceMapping*`·`*SourceImport*`·`*SourceCollection*`·`*SourceRename*` — 27개 클래스 235건) 을 새 빈 DB 로 **4회** + 전체 PostgreSQL 실행 1회 : **모두 실패 0, `s4TwoRenames` 재발 없음** (15라운드 모두 B 의 대기 문장 = `tb_prj … for no key update`).
- 외부 감시(20ms 간격, `pg_stat_activity` + `pg_blocking_pids` + 대기 중인 `pg_locks`) : 대기 관찰 2,139회 = `transactionid` ShareLock 2,133 + 잠금 행을 못 찾은 순간 6, relation 대기 0. 감시 자체가 초당 50문장을 더했다 (재현 확률에 영향 가능 — 기록).
- 결정적 재현 (32.4) : 위 열 불일치를 세 세션(A 역할 프로젝트 행 보유, B 역할 TB_TBL 조회 → 쉼 → 프로젝트 행 대기, 감시)으로 재현.

### 14.4 판정 · 후속

| 가능성 (작업 지시 14장) | 판정 |
|---|---|
| 1. 감시 대상 세션 식별 오류 | **원인으로 판단** — PID 로 거르지 않고, `state`·`query`(스냅숏) 와 `wait_event`(실시간) 를 섞어 읽는다. 메커니즘은 결정적으로 재현, 보존된 실패 문장과 일치 [실행 + 추정 — 실제 실패 순간은 재현하지 못함] |
| 2. 다른 테스트·세션의 대기 감지 | 가능하나 이번 감시 4회에서 S4 동안 다른 세션 대기는 관찰되지 않음. 보존된 문장이 B 의 첫 문장과 같아 1 이 더 맞다 |
| 3. 운영 코드의 예상과 다른 잠금 순서 | **근거 없음** — 잠금 절 없는 첫 조회는 잠금을 잡지 않고, 15라운드 모두 기대한 프로젝트 행 대기 |
| 4. 트랜잭션 경계 | 해당 없음 (감시는 자동 커밋 — AES-D17 의 트랜잭션 안 스냅숏 고정과는 다른 경로. 다만 같은 '상태 복사' 성질의 한 문장 안 버전) |
| 5. 연결 풀·환경 | 근거 없음 |
| 6. 테스트 동기화 방식 | 1 과 같은 결함 (첫 관찰 즉시 A 를 놓음) |

- **판정 : 운영 결함이 아니라 테스트 감시 결함으로 본다** (메커니즘 재현 + 증상 일치 + 운영 잠금 순서 정상). 실제 실패 순간의 PID 기록은 없으므로 '확정' 이 아니라 '유력 원인 확인' 으로 기록하고, 간헐 통과로 해결 처리하지 않는다.
- M8-C4a 의 다른 증상 ('B 가 10초 안에 잠금 대기로 관찰되지 않음', XML 미보존) 은 이 메커니즘으로 설명되지 않는다 — **미확정 유지**.
- 후속 (3차, 테스트만 — PG-Q13) : 감시가 대기를 처음 본 뒤 **새 문장으로 한 번 더 읽어** 같은 PID 가 `state = 'active'` 로 같은 문장에서 계속 기다리는지 확인하고(A 는 멈춰 있으므로 실제 대기는 유지된다), `pid`·`pg_blocking_pids` 로 A 의 백엔드가 막고 있는 세션만 B 로 인정한다. 단언·시간 제한은 완화하지 않는다. 같은 감시를 쓰는 :546·:647·:690 과 '기다리지 않는다' 단언에도 같은 보완을 적용한다. 운영 코드는 바꾸지 않는다.

## 15. 테스트 구조 전수 분석

### 15.1 기준선 [실행 — 32장]

| 실행 | 전체 | 실제 실행 | 실패 | 건너뜀 | 시간 |
|---|---:|---:|---:|---:|---|
| 기록 (AI 공통 실행 안정성 최종) H2 | 1,612 | 1,487 | 0 | 125 | 4분 53초 |
| 기록 PostgreSQL 1차 / 2차 | 1,612 | 1,566 | 0 | 46 | 7분 00초 / 6분 56초 |
| **이번 H2** (원본 PostgreSQL 지정) | 1,612 | 1,506 | 0 | 106 | 5분 05초 |
| **이번 PostgreSQL** (새 빈 DB, 원본 PostgreSQL 지정) | 1,612 | 1,585 | 0 | 27 | 7분 43초 |

- 테스트 수는 기록과 같다 (1,612). 이번 두 실행의 건너뜀이 기록(H2 125 · PG 46)보다 **정확히 19건씩** 적은 것은 `LEXORMA_TEST_SOURCE_POSTGRES_URL` 을 지정해 원본 수집 통합 테스트 19건(`AiDictionarySourceRefresh` 5 · `SourceJdbcCollection` 8 · `SourceJdbcCollectionLimit` 2 · `SourceJdbcSecurity` 1 · `SourceRenameReimportJdbc` 1 · `SourceViewDefinition` 2)이 실제로 돌았기 때문이다 [실행]. 기록의 실행들은 원본 URL 없이 돌았다고 본다 [추정 — 수가 정확히 맞음].
- PostgreSQL 실행의 1,585건에는 테스트가 직접 만든 H2 메모리 DB 로 도는 `[h2]` 변형·H2 전용 테스트 24건(15.3)이 포함돼 있다 — 'PostgreSQL 전체 실행' 도 지금은 H2 를 일부 쓴다.
- 모듈별 테스트 수 : app 1,130 · data 275 · core 94 · common 35 · system 28 · ai 25 · source 16 · operation 9.

### 15.2 유형 분류 [코드 — 클래스 수]

| 유형 | 위치·예 | DB 필요 |
|---|---|---|
| 순수 단위 (Mockito) | 비-app 7개 모듈 62개 클래스 (482건) — `@SpringBootTest`·DataSource 0 | 아니오 |
| app 의 DB 없는 검사 | `ModuleBoundaryTest`·`MessagesCoverageTest`·`EntityJsonExposureTest`·`ReviewReasonContractTest` | 아니오 |
| Flyway·스키마 (DriverManagerDataSource 직접) | 마이그레이션 테스트 15개 클래스 + `AdvancedIndexPostgresDdlTest`·`ViewPostgresDdlTest`·`SecretKeyConfigurationTest`(프로필 기동) | 예 (일부 PG 전용) |
| Spring 컨텍스트 통합 (`@SpringBootTest`) | 174개 클래스 — 서비스·API·권한·프로젝트 격리·원본·AI 가짜 Provider·MCP·동시성·SmokeTest(실제 포트) | 예 |
| 별도 DB 컨텍스트 | 위 6개 HTTP 테스트 (PG 에서는 임의 스키마 `currentSchema=`) | 예 |
| 동시성 | `ConcurrentEdit*` 8·`RelationSyncDeadlock`·`Inheritance*Concurrency` 3·`AlternateKeyConcurrency`·`ModelVersionConcurrency`·`PostgresLockWaitWatch`·`ColumnInsertConcurrency`·`SubtypeGenerationConcurrency` 등 | 예 (5개 클래스 PG 전용) |
| 원본 DB 수집 | `SourceJdbc*` (`LEXORMA_TEST_SOURCE_POSTGRES_URL`, 관리 DB 와 별개) | 원본 PG |
| AI 실제 Provider | 자동 테스트 없음 (실제 Ollama 는 수동 검증 기록) | — |
| 성능 (`LEXORMA_PERF=true`) | 20개 클래스 | 예 (기본 건너뜀) |
| 배포·단일 JAR | 자동 테스트 없음 (수동) | — |

### 15.3 DB 조건별 분류 [실행 — 두 실행의 건너뜀 사유]

| 구분 | 내용 |
|---|---|
| H2 에서만 실행 (어느 실행에서도 H2 로 돈다) | **24건** = `H2FilePersistenceTest` 4 + `SubtypeStrategyConversionMigrationTest.h2File…` 1 + 벤더 매개변수 테스트의 `"h2"` 변형 19 (`AiDefaultProvider` 1 · `AiDictionaryMigration` 1 · `ColumnNameUnicodeMigration` 2 · `ColumnNameUniqueMigration` 1 · `ColumnRequiredValuesMigration` 1 · `EmbeddedMcpMigration` 1 · `IdentifierQuality` 1 · `LexormaIndexSeed` 1 · `LogicalRelationshipAttributeMigration` 1 · `LogicalSubjectAreaMigration` 1 · `LogicalSubtypeMigration` 1 · `SourceRenameMigration` 1 · `SourceViewDefinitionMigration` 1 · `StandardizationQualityRules` 1 · `SubtypeIdentifierColumnMappingMigration` 1 · `SubtypeStrategyConversionMigration` 2 · `V0SeedMigration` 1) |
| PostgreSQL 에서만 실행 (H2 실행에서 건너뜀) | **79건** = `assumeTrue(postgres())` 53 (RelationSyncDeadlock 13 · InheritanceStrategyConcurrency 21 · InheritanceConcurrency 12 · AlternateKeyConcurrency 5 · PostgresLockWaitWatch 2) + `LEXORMA_TEST_POSTGRES_URL` 가정 26 (벤더 매개변수의 `postgresql` 변형 19 + `AdvancedIndexPostgresDdl` · `DdlExport` · `ViewPostgresDdl` · `SchemaScopedNaming` · `PhysicalGenerationStabilization` · `SubtypeDdlGolden` · `SubtypePreservation` 30MB 본문 각 1) |
| 두 DB 공통 | 나머지 (H2 실행 1,506 − H2 전용 24 = 1,482건이 두 DB 에서 모두 돈다) |
| 분기 (같은 테스트가 DB 별로 다른 단언) | 7.3 의 `h2()`·`postgres()` 분기 클래스 |

### 15.4 PostgreSQL 에서 건너뛰는 테스트

**결론 : 기록의 'PostgreSQL 에서 건너뛰는 46건' 은 H2 와 무관하다** — 성능 시험 27 + 원본 수집 19 (원본 PostgreSQL 미지정). 단일화로 영구히 실행되지 않게 되는 테스트는 없다.

| 사유 | 건수 | 클래스 | 단일화 후 |
|---|---:|---|---|
| `@EnabledIfEnvironmentVariable(LEXORMA_PERF=true)` / `assumeTrue(LEXORMA_PERF)` | 27 | 성능 시험 20개 클래스 (`*PerformanceTest`·`PhysicalNameF02PocTest`·`PhysicalNameNonAsciiScaleTest`·`UploadPerformanceTest` …) + `SourceErdIntegrationTest` 1 · `SourceJdbcCollectionIntegrationTest` 1 | 그대로 (명시 실행) — 성능 시험 안의 H2 분기만 정리 |
| `LEXORMA_TEST_SOURCE_POSTGRES_URL` 미지정 | 19 (기록) / 0 (이번) | 15.1 의 원본 수집 6개 클래스 | CI 에서 원본 URL 을 지정해 항상 실행 (PG-Q08) |

## 16. H2 테스트 제거 정책

### 16.1 원칙

테스트 수 유지가 목표가 아니라 **업무 기능·보안·정합성·동시성 검증 범위 유지**가 목표다. 단언 완화·비활성화로 단일화를 끝내지 않는다. 삭제 후보마다 사유와 대체 검증을 남긴다.

### 16.2 삭제 · 전환 대상

| 대상 | 건수 | 처리 | 사유 / 대체 검증 |
|---|---:|---|---|
| `H2FilePersistenceTest` | 4 | 삭제 | h2-local 파일 DB 기능 자체 제거. 대체 : 8.2 관리 DB 확인의 단위 테스트(새로 — URL 없음·비 PG URL·마스킹) |
| `SubtypeStrategyConversionMigrationTest.h2FileDatabaseKeepsCheckAcrossConnectionsAndRestart` | 1 | 삭제 | H2 2.4.240 CHECK 결함 회귀. 대체 : `freshInstall[postgresql]` 의 새 연결 제약 검사 (이미 있음) |
| 벤더 매개변수 `"h2"` 변형 | 19 | 매개변수에서 `h2` 제거 | 같은 단언이 `"postgresql"` 변형으로 이미 돈다 (PG 실행 시). `SubtypeStrategyConversionMigration` 의 V1→V2→V3 업그레이드도 PG 변형 있음 |
| `ColumnNameUnicodeMigrationTest` 의 H2 전용 단언 (`FN_UPPER_ROOT`·H2 JVM 대문자) | 일부 | H2 분기 삭제 | PG ICU 분기 유지 |
| `ColumnNameUniquenessIntegrationTest` (`h2()` → return) | 0 | PG 단언만 | — |
| `SourceImportLockErrorTest` H2 코드 경우 | 일부 | 삭제 | PG `55P03`·`40P01` 단언 유지 |
| 별도 컨텍스트 6개의 H2 메모리 분기 | 0 | PG 임의 스키마만 | — |
| 동시성 11개 클래스의 H2 감시 분기 | 0 | PG 감시만 | — |
| `assumeTrue(postgres())` 53건 | 0 | 가정 제거 (항상 실행) | 업무 검증 범위 확대 (기본 CI 에서 처음 실행됨 — 19장) |
| `SecretKeyConfigurationTest` 프로필 목록 | 0 | `h2-*` 제거 | — |
| `PhysicalNameJudgedPathIntegrationTest` | 0 | 그대로 (16.3) | — |

### 16.3 검증 범위가 바뀌는 곳 (주의)

- `PhysicalNameJudgedPathIntegrationTest` 는 H2 에서 기준값(500)이 작아 **서비스 경로로 자바 전체 판정 쪽까지** 확인했고, PostgreSQL 에서는 기준값(10,000) 때문에 아래 쪽만 서비스로, 자바 판정은 직접 호출로 비교한다 (테스트 주석). H2 를 빼면 '서비스가 자바 판정 경로로 넘어가는 분기' 를 자동 테스트가 서비스 경로로 지나지 않는다. 대체 안 (3차에서 결정, PG-Q14) : ① 테스트 전용으로 기준값을 낮추는 설정(운영 값 불변) ② `PhysicalNameNonAsciiScaleTest`(성능 시험, 5,000·10,000)를 정기 실행 ③ 현 상태 수용.
- `ReferenceSyncBoundary.recheckOnH2` 를 지나던 H2 회귀(예 : C4b-R1 의 H2 단언)는 대상 코드와 함께 없어진다 — PostgreSQL 의 KEY SHARE 경로는 `RelationSyncDeadlockIntegrationTest`·`ConcurrentEditC4b*` 가 계속 검증.

## 17. PostgreSQL 테스트 실행 환경

| 대안 | 장점 | 단점 |
|---|---|---|
| A. Testcontainers | 명령 하나, 매 실행 새 DB | **새 테스트 의존성**, Docker 필수, 컨테이너 기동 시간, Docker 없는 CI/개발 환경에서 전체 실패 |
| B. 외부 테스트 PostgreSQL (환경변수) | **이미 쓰는 방식** (`SPRING_DATASOURCE_URL`·`LEXORMA_TEST_POSTGRES_URL`·`LEXORMA_TEST_SOURCE_POSTGRES_URL`, README 7장), 새 의존성 없음, CI 는 GitHub Actions `services: postgres` | 개발자가 빈 DB 를 준비해야 함, 오접속 방지 장치 필요 |
| C. 둘 다 | 유연 | 두 경로 유지 비용 |

**추천 : B** (결정 PG-Q06·Q07). 설계 :

- 실행 단위 : Gradle `test` 한 번 = 테스트 DB 하나 (지금처럼 모든 Spring 컨텍스트가 같은 DB 를 쓰고, 별도 컨텍스트 6개·마이그레이션 테스트는 임의 스키마 — 이미 구현됨).
- 설정 단순화 : 세 환경변수 중 `SPRING_DATASOURCE_URL` 하나로 관리 DB 를 정하고 `LEXORMA_TEST_POSTGRES_URL` 은 기본값을 그것으로 (원본 수집용 `LEXORMA_TEST_SOURCE_POSTGRES_URL` 은 DB 생성 권한이 필요해 따로 둠 — 지정 안 하면 지금처럼 원본 테스트만 건너뜀). 계정은 지금 `lexorma/lexorma` 가 테스트 코드에 고정 — 환경변수로 받을지 PG-Q07.
- **DB 미지정** : Spring 컨텍스트 테스트는 '관리 DB 미지정 — 테스트 PostgreSQL 을 지정하세요' 로 **실패** (조용히 건너뛰지 않음 — 건너뛰면 CI 가 녹색인 채 검증이 사라진다). 순수 단위 테스트(비-app 모듈, app 의 4개)는 DB 없이 그대로 통과 — `./gradlew :lexorma-data:test` 등은 PostgreSQL 불필요.
- 개발·운영 DB 오접속 방지 : 테스트는 Flyway 로 그 DB 에 V0~V3 + 테스트 관리자 계정을 넣고 대량의 테스트 프로젝트를 만든다. 개발 DB(`localhost:15439/lexorma`)를 가리키면 개발 데이터가 오염된다. 후보 : 테스트 프로필 시작 때 DB 이름이 `test` 를 포함하지 않으면 중단 (명시적 우회 변수 하나) — PG-Q07.
- Flyway 실행 횟수 : 같은 DB 를 쓰는 컨텍스트는 첫 번째만 마이그레이션(나머지 no-op), 마이그레이션 테스트는 각자 스키마 생성·`clean`. 현재와 같음.
- 커넥션 풀 : `minimum-idle: 1`·`idle-timeout: 10s` (컨텍스트 캐시로 max_connections 100 초과 방지 — 기존 설정 유지). 동시성 테스트는 최대 10 그대로.
- 데이터 격리 : 테스트마다 새 프로젝트·이름(`System.nanoTime`) — 같은 DB 공유 전제가 이미 지켜지고 있다 (PostgreSQL 전체 실행 통과).
- 병렬 실행 : 하지 않는다 (동시성 테스트의 감시가 DB 전체 세션을 보므로 — 14장).
- 종료 후 정리 : 빈 DB 를 매번 새로 만드는 것을 권장 (CI 서비스 컨테이너는 Job 마다 새 DB). 로컬은 `dropdb`/`createdb` 안내.
- 실패 진단 : 기존 `ConcurrencyDiagnostics`·`build/reports/tests` 업로드 유지 + CI 에서 PostgreSQL 서버 로그(`docker logs`)를 실패 시 업로드.

## 18. 테스트 실행 시간 · 리소스

| 항목 | 값 | 근거 |
|---|---|---|
| H2 전체 (이번) | 305초 (Gradle 전체, 컴파일 포함), 클래스 합계 260초 — 실행 초반에 같은 기계에서 의존성 조회(Gradle)가 겹쳐 몇 초 늘었을 수 있다 | [실행] |
| PostgreSQL 전체 (이번) | 463초 (7분 43초), 클래스 합계 418초 — H2 대비 클래스 합계 1.6배. 가장 긴 클래스 `PhysicalNameJudgedPath` 21초·`InheritanceStrategyConcurrency` 14.7초·`SmokeTest` 12.8초 | [실행] |
| 기록 | H2 4분 53초, PG 7분 00초·6분 56초 | 기록 |
| 단일화 후 예상 | 1,612 − H2 전용 24 = **1,588건** (실행 1,561 · 성능 건너뜀 27, 원본 URL 지정 시). 현재 PostgreSQL 실행 집합에서 H2 24건(합계 수 초)만 빠지므로 **약 7~8분** [추정]. CI 는 서비스 컨테이너 기동 수십 초 추가 [추정] | |
| Spring 컨텍스트 | 캐시된 서로 다른 구성 여러 개 (별도 컨텍스트 6 + `@DynamicPropertySource`·`@MockitoBean` 조합) | [코드] |
| PostgreSQL 컨테이너 | 0 (외부 DB) | B 안 |
| Flyway | 공유 DB 1회 + 마이그레이션 테스트 스키마마다 1회 (약 20회) | [코드] |
| 테스트 힙 | `maxHeapSize = '2g'` — 512MB 에서 OOM 이력 (H2 메모리 DB + 원본 PG 테스트 동시) | `build.gradle` 주석 |
| GitHub Actions `ubuntu-latest` | 공개 저장소 4 vCPU·16GB [외부 사양 — 비공개 저장소는 2 vCPU·7GB 일 수 있음, 미확인] — PostgreSQL 서비스(수백 MB) + 테스트 JVM 2GB + Gradle 데몬 여유 있음 [추정] | |

- H2 데이터가 힙에서 빠지므로 테스트 JVM 힙 요구는 줄어들 수 있으나, 2g 를 낮추는 것은 측정 뒤에만 (3차 — 실측 없이 바꾸지 않음).
- 성능을 위해 격리(공유 DB 전제)·동시성 테스트 실행 방식을 바꾸지 않는다.

## 19. GitHub Actions · CI

현재 [코드] : `.github/workflows/dev-test.yml` 하나 — `dev` 푸시 때 `./gradlew build` (npm ci + tsc + vite build + 모든 모듈 테스트), **H2 메모리만** (주석 "외부 DB 불필요"), 실패 시 테스트 리포트 업로드, 타임아웃 30분. PostgreSQL Job·Matrix·Testcontainers·Docker 이미지 빌드·릴리스 Workflow **없음**. 즉 **지금 CI 는 PostgreSQL 에서만 도는 79건(동시성 53 · PG 마이그레이션·DDL 실행 26)과 원본 수집 19건을 한 번도 돌리지 않는다** — 운영 DB 인 PostgreSQL 의 검증은 지금까지 수동 실행 기록에만 있다.

2차/3차 후 설계 :

```yaml
services:
  postgres:
    image: postgres:18.6
    env: { POSTGRES_USER: lexorma, POSTGRES_PASSWORD: lexorma, POSTGRES_DB: lexorma_test }   # CI 전용 일회용 값 (비밀 아님)
    ports: ['5432:5432']
    options: --health-cmd "pg_isready -U lexorma -d lexorma_test" --health-interval 3s --health-retries 40
env:
  SPRING_DATASOURCE_URL: jdbc:postgresql://127.0.0.1:5432/lexorma_test
  LEXORMA_TEST_SOURCE_POSTGRES_URL: jdbc:postgresql://127.0.0.1:5432/postgres   # 원본 수집 테스트 (PG-Q08)
```

- `./gradlew build` 유지 (프런트 타입·빌드 + 전체 테스트 + bootJar 는 `build` 에 포함 — `jar` 꺼짐·`bootJar` 산출). Docker 이미지 빌드 Job 추가는 선택 (PG-Q08 — 추천 : `docker build -f Dockerfile` 만 확인하는 가벼운 Job, 푸시 없음).
- 실패 시 CI 실패 (지금과 같음), 리포트 + PostgreSQL 로그 업로드.
- 매 실행 2회 반복은 하지 않는다 (추천) — 시간 2배, 간헐 실패는 14장으로 따로. **FINAL 검증에서만 새 빈 DB 2회**.
- 인증정보 : CI DB 계정은 일회용 서비스 컨테이너 안에서만 쓰는 고정 값이라 Secret 불필요. 실제 운영 비밀은 CI 에 넣지 않는다.

## 20. Standalone 배포 제거 범위

### 20.1 개념 구분

| 개념 | 현재 | 단일화 후 |
|---|---|---|
| **H2 내장 Standalone** (`java -jar lexorma.jar --spring.profiles.active=h2-local`, 별도 DB 없음) | 지원 (README 5장 "Quick start without PostgreSQL") | **제거** (PG-D08) |
| **일반 단일 JAR** (React 포함 `lexorma.jar` 를 PostgreSQL 에 연결) | 지원 (`local`·`docker` 프로필) | **유지** (PG-D09) |
| Docker 이미지 (PostgreSQL) | `Dockerfile` → `lexorma/lexorma` | 유지 |
| Docker 이미지 (H2) | `Dockerfile-h2` → `lexorma/lexorma:h2-latest` | **제거** |
| Docker Compose (PostgreSQL + 앱) | `docker-compose.yml` | 유지 |
| Docker Compose (H2 앱만) | `docker-compose.h2.yml` | **제거** |

### 20.2 제거 대상 목록 [코드]

`application-h2-local.yml`, `application-h2-docker.yml`, `config/H2FileDatabaseCheck.java`, `Dockerfile-h2`, `lexorma/docker-compose-exam/docker-compose.h2.yml`, `.env.example` 의 H2 행, `application.yml` 의 `h2-local` 그룹, README 5·6·7·8장의 H2/Standalone 절, `docs/reference/h2-file-storage.md` (→ 26장). Standalone 전용 실행 스크립트·빌드 Task·릴리스 Workflow·내장 Java·압축 배포는 **원래 없다** — 제거할 것 없음.

### 20.3 단일 JAR 유지 방식

`lexorma-app/build.gradle` 의 `bootJar`·`processResources` (React dist 포함)·`-PfrontendPrebuilt` 는 바꾸지 않는다. `Dockerfile`·`Dockerfile.dev` 의 3단계 빌드도 그대로 (같은 JAR 을 쓴다). 프런트·백엔드 분리 없음.

## 21. Docker · Docker Compose

### 21.1 현재 [코드]

- `lexorma/docker-compose-exam/docker-compose.yml` : `postgres:18.6` + `lexorma/lexorma`, `.env` 필수값(`POSTGRES_DB/USER/PASSWORD/DATA_DIR`, `LEXORMA_DATA_DIR`), healthcheck(`pg_isready`) → `depends_on: service_healthy`, 앱은 `SPRING_DATASOURCE_*`, 암호화 키 = Docker Secret + `optional:configtree:`, 볼륨 `POSTGRES_DATA_DIR:/var/lib/postgresql` (PG 18 경로), 앱 `/data`.
- `lexorma/docker-compose.yml` : 개발용 PostgreSQL 만 (15439, `./volume/lexorma`, 계정 고정).
- **경로 불일치** : README·CLAUDE.md·`application.yml` 주석은 `docker/`·`docker/lexorma/` 라고 쓰지만 실제 파일은 2026-11-17 (`411bf70`) 부터 `lexorma/`·`lexorma/docker-compose-exam/` 이다 — 2차 문서 정리에 포함.
- 참고 : `.env.example` 의 `POSTGRES_DATA_DIR=../volume/lexorma` 는 `lexorma/volume/lexorma` 를 가리키며 개발용 Compose(`lexorma/docker-compose.yml` 의 `./volume/lexorma`)와 **같은 호스트 폴더**다 [코드] — 같은 기계에서 둘을 함께 띄우면 같은 데이터 디렉터리를 두 컨테이너가 열 수 있다 (기존 구조, 이번 범위 밖 — 위험 목록에만 기록).

### 21.2 단일화 후

| 배포 | 구성 |
|---|---|
| 기본 | 지금 `docker-compose.yml` 그대로 (PostgreSQL + 앱) |
| 외부 PostgreSQL | 앱만 실행 : `docker run … -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db> -e SPRING_DATASOURCE_USERNAME … -e SPRING_DATASOURCE_PASSWORD … lexorma/lexorma` (기존 `docker` 프로필·`DB_*` 도 가능). Compose 로 하려면 앱 서비스만 있는 예시 파일 하나(`docker-compose.external-db.yml`) 또는 README 절 — PG-Q16 (추천 : README 절 + 짧은 예시 파일, 새 필수 서비스 없음) |

- `LEXORMA_DATA_DIR:/data` 마운트 : H2 이미지에서는 DB 파일 자리였고 PostgreSQL 이미지에서는 지금 쓰는 곳이 없다 [코드 — `/data` 를 쓰는 설정 없음, 미확인 : 사용자가 다른 용도로 쓰는지]. 남기면 무해, 빼면 `.env` 필수값이 하나 준다 — PG-Q15 (추천 : 2차에서 필수(`:?`)를 선택으로만 완화하고 마운트는 유지 — 기존 `.env` 호환).
- Redis·별도 MCP·AI 서버 등 새 필수 서비스 없음. AI Provider·MCP 는 지금처럼 선택.
- 컨테이너 종료·재시작·데이터 보존 : 지금 구조 그대로 (PostgreSQL 볼륨). H2 의 `stop_grace_period` 같은 특별 처리는 PostgreSQL 앱에 필요 없다.

## 22. PostgreSQL 지원 버전

| 항목 | 현재 |
|---|---|
| Docker Compose 기본·개발 | `postgres:18.6` (두 Compose) |
| CI | 없음 (H2 만) |
| 로컬·검증 기록 | 18.6 (M-8·AI 실행 안정성 등 모든 PG 실행) |
| 문서 | README "18 recommended (verified: 18.6) … ICU 필수" — 최소 버전 명시 없음 |
| JDBC 42.7.14 | PostgreSQL 8.4 이상 지원 [외부 문서 기준] |
| **Hibernate 7.4.13 `PostgreSQLDialect.MINIMUM_VERSION` = 14** | [실행 — `javap` 로 jar 상수 확인] 그 아래는 경고·미지원 |
| 실제 사용 기능의 최소 | 생성 컬럼 `STORED` 12+, `GENERATED … AS IDENTITY` 10+, ICU 콜레이션 10+(ICU 빌드), `FOR NO KEY UPDATE`/`FOR KEY SHARE` 9.3+, `pg_stat_clear_snapshot` (테스트) — 관리 DB SQL 에 15+ 전용 기능(`MERGE`·`NULLS NOT DISTINCT`·`regexp_like`)은 **없다** (`regexp_like` 는 H2 분기에만) [코드] |
| Flyway 12.4 의 지원 범위 | [미확인 — 2차에서 Flyway 문서로 확인] |

- PostgreSQL 14 는 커뮤니티 지원이 2026-11 에 끝났다 (5년 정책, 14.0 = 2021-09) [외부 일정 — 확인 권장]. 15 는 2027-11 까지.
- **추천 (PG-Q04·Q05)** : 최소 지원 **15** (Hibernate 최소 14 이상이면서 커뮤니티 지원 중인 가장 오래된 판, ICU 빌드 필수), 공식 권장 **18** (18.6 검증), Compose 기본 **18.6** 유지, CI **18.6** 하나. 15·16·17 은 FINAL 에서 **최소 판(15) 한 번만** 신규 설치 + 전체 테스트로 호환 확인 (전체 Matrix 도입 안 함). 최소 판 검증을 하지 못하면 문서에 '검증 판 18.6, 15~17 은 미검증' 으로 적는다.

## 23. 보안 · 데이터 보호

- 관리 DB 인증정보 : 환경변수(`SPRING_DATASOURCE_*`/`DB_*`) 또는 Compose `.env`(Git 제외). Docker Secrets 는 지금 암호화 키에만 쓴다 — DB 비밀번호를 Secret 으로 받는 것은 **선택 지원**으로 둘 수 있다 (`optional:configtree:/run/secrets/` 가 이미 켜져 있어 `spring.datasource.password` 파일을 마운트하면 그대로 읽힌다 [코드 — Boot configtree 동작, 미실측]). 모두에게 강제하지 않는다.
- SSL : JDBC URL `sslmode=…` (사용자 선택, 기존 그대로). 별도 설정 추가 없음.
- 권한 : Flyway 가 같은 계정으로 DDL 을 실행한다 → 관리 DB 계정은 스키마 객체 생성 권한이 필요하다 (지금과 같음). 마이그레이션 계정·실행 계정 분리는 범위 밖 (문서에 '필요 권한' 만 적는다).
- 세션(Spring Session JDBC)·감사 로그·프로젝트 격리·원본 비밀번호 암호화(`SecretCipher`)는 DB 종류와 무관 — 변화 없음.
- 오접속 방지 : 테스트(17장) + 운영 기동 확인(8.2 — URL·계정 원문 비밀값 미출력).
- 백업·복구 : PostgreSQL `pg_dump`/`pg_restore` 안내를 README 에 둔다 (H2 파일 복사 안내를 대체). 삭제·초기화·자동 재생성 기능은 추가하지 않는다.
- 단일화로 **줄어드는 공격면** : `/h2-console` (h2-local 에서 켜짐 — DB 전체 접근), H2 파일 DB 의 파일 권한 문제.

## 24. 프런트엔드 · 사용자 화면

[코드 — `lexorma-web/src` 전수] 관리 DB 종류·H2 를 표시하거나 고르는 화면은 **없다** (최초 설정·로그인·관리자·시스템·대시보드·오류·도움말·AI·MCP). `H2` 문자열은 HTML `<h2>` 제목 태그뿐. 원본 연결 화면의 DBMS 선택(PostgreSQL·Oracle·MySQL·MariaDB)과 "수집은 PostgreSQL 만" 안내, DDL Export 의 DBMS 선택은 원본/출력 기능이라 유지. 한·영 문구(`locales/en.ts`) 변경 없음. → **프런트엔드 변경 없음** (타입 검사·빌드만 회귀).

## 25. AI · MCP 영향

- AI(`lexorma-ai`) 운영 코드의 H2 의존 : `AiDescriptionEvaluationService` 주석 1곳뿐 (잠금 순서 유지). Tool 호출 제한·토큰 예산·부분 답변·completion·근거 검증·권한 재검증·Ollama thinking 제어·서버 맥락·MCP Tool 13개 — DB 종류와 무관 → **변경 없음**.
- AI 통합 테스트는 가짜 Provider 로 관리 DB 를 쓴다 → PostgreSQL 전체 실행에 이미 포함 (이번 PG 실행에서 통과 여부 32장).
- 실제 Provider 검증 재사용 : AI 공통 실행 안정성 3차의 실제 Ollama 31회는 H2(h2-local 단일 JAR)·PostgreSQL 어느 쪽이었는지 기록별로 다르다 — DB 와 무관한 경로라 결과를 재사용할 수 있다 [추정]. FINAL 에서는 PostgreSQL 단일 JAR 로 **질의 1~2회(정상·부분 답변) + MCP 호출 1회** 만 다시 확인하는 것을 추천 (PG-Q18).
- AI-6 은 시작하지 않는다.

## 26. 문서 영향

| 문서 | 처리 |
|---|---|
| `README.md` | 3장 기술 스택(H2 행), 4장 구조도, 5장 "Quick start without PostgreSQL" 삭제 → PostgreSQL 빠른 시작, 요구사항(PostgreSQL 필수·최소/권장 판·ICU), 6장 프로필 표(h2-local·h2-docker 삭제), 7장 테스트(PostgreSQL 필수 — 17장), H2 콘솔 행 삭제, 8장 이미지 표·Standalone with H2 절 삭제, 외부 PostgreSQL 절 추가, `docker/` 경로 → `lexorma/` 정정, 백업 안내 |
| `CLAUDE.md` | Commands(h2-local 실행 줄), "Tests use in-memory H2", Migrations "both vendor directories"·H2 rule(V3)·`ColumnNameKey` H2 설명, Tests 절, Documentation 표 — 2차·3차에서 실제 변경과 함께. **이번 1차는 문서 관리 표의 `docs/ongoing/` 항목만** 갱신 |
| `docs/reference/h2-file-storage.md` | 현재 지원 정책 문서가 아니게 됨 → (a) 삭제 (b) 머리에 "2026-11-?? 지원 종료 — 기록" 표시 후 보존. 추천 (b) + 이전 안내(11장) 링크 (PG-Q10). 옮기면 링크 수정 |
| `reference/data-modeling-roadmap.md`·`ai-metadata-roadmap.md` | 상태 표는 2026-11-15 기준 동결 규칙 → 고치지 않는다. 기술 서술(Flyway 양쪽, Dockerfile-h2)은 기록으로 두고, 현재 정책은 이 문서·README 가 가진다 |
| `docs/completed/*` | H2 검증 기록·설계 결정 **보존** (④) |
| `docs/completed-work.md`·`pending-work.md` | 완료 때 갱신 (31장 순서) |
| 설치·개발·Docker·PostgreSQL·백업·테스트·기여·보안·릴리스 노트 가이드 | 별도 문서 **없음** (README 가 전부) — 새 문서를 만들지 않고 README 에 둔다 |

## 27. 변경 대상 파일 · 예상 규모

| 단계 | 파일 (예상) | 규모 [추정] |
|---|---|---|
| 2차 운영·배포 | `lexorma-app/build.gradle`, `application.yml`·`-debug-log`·`-local`·(`-docker` PG-Q09), `application-h2-local.yml`·`-h2-docker.yml` 삭제, `H2FileDatabaseCheck` → 관리 DB 확인 클래스(신규 1 + 단위 테스트 1), `SpaForwardController`, `SecurityConfig`(주석), `ColumnNameKeySql`, `StandardNameSql`, `PhysicalNameRecommendationQueryRepository`·`Service`, `PhysicalModelServiceImpl`, `ReferenceSyncBoundary`, NOWAIT 판정 4곳, `CustomException`·`ModelBase`·`AiDescriptionEvaluationService` 외 주석 ~12곳, `db/migration/h2/` 4개 삭제, `Dockerfile-h2`·`docker-compose.h2.yml` 삭제, `.env.example`, `docker-compose.yml`(PG-Q15), README, CLAUDE.md | 운영 코드 약 15~20 파일, 삭제 8 파일(+H2 마이그레이션 4), 순감 약 1만 줄(대부분 H2 마이그레이션 — V1 6,732줄) |
| 3차 개발·테스트 | `application-test.yml`, 테스트 클래스 약 45개 (마이그레이션 15 · 별도 컨텍스트 6 · 동시성 11 · 분기 6 · `assumeTrue(postgres())` 5 · 기타), `H2FilePersistenceTest` 삭제, `SourceImportLockErrorTest`, `build.gradle`(주석·힙), `.github/workflows/dev-test.yml`, README 7장 | 테스트 약 45~50 파일, 대부분 몇 줄씩 |
| FINAL | 문서 (completed-work·pending-work·이 문서 → completed/) | — |

## 28. 위험 · 대응

| 위험 | 대응 |
|---|---|
| H2 사용자 데이터가 실제로 있음 | PG-Q01 — 파일 삭제 금지, 이전 버전 열람 안내, 필요 시 B 안 |
| `spring-boot-h2console` 만 남아 h2 가 전이로 남음 | 두 줄 함께 제거 + JAR 목록 확인 (30장) |
| 다른 세션의 `AD` 스테이징(V5~V7) 이 함께 커밋됨 | 3장 — 사용자가 인덱스를 정리한 뒤 커밋 |
| 테스트 DB 미지정 시 조용히 건너뛰어 검증 소실 | 17장 — Spring 테스트는 실패, CI 는 서비스 컨테이너 필수 |
| 테스트가 개발 DB 를 오염 | 17장 DB 이름 확인 (PG-Q07) |
| CI 시간 증가 (H2 5분 → PG 약 7분 + 서비스 기동) | 30분 타임아웃 안 [추정] |
| PostgreSQL 전용 동시성 테스트가 CI 에서 처음 돌며 간헐 실패(`s4TwoRenames`·`entityApprovalVsEdit`)가 CI 를 빨갛게 함 | 14장 — 3차에서 원인 처리 또는 감시 보완 (단언 완화 금지) |
| ICU 없는 PostgreSQL 에 설치 | 기존 V0 확인 문장이 명확히 실패 — README 요구사항 |
| PostgreSQL 14 이하 설치 | 22장 최소 판 문서화 (기동 차단은 하지 않음 — Hibernate 경고, PG-Q04) |
| `/data` 마운트·`LEXORMA_DATA_DIR` 필수값 변경으로 기존 `.env` 깨짐 | PG-Q15 — 필수만 완화 |
| 공용 개발 볼륨 폴더 공유 (21.1) | 기록만 (범위 밖) |

## 29. 후속 구현 단계

- **2차 — 운영·배포 PostgreSQL 단일화** : 6장 의존성 제거, 8.2 관리 DB 확인, H2 fallback 제거(6.3), 12장 SQL 분기 정리, 13장 H2 전용 동시성 코드 제거, Flyway 위치 고정·H2 마이그레이션 삭제(10.2), Standalone/H2 Docker 제거(20장), Compose·`.env` 정리(21장), README·CLAUDE.md. **주의** : 2차만 끝나면 H2 테스트 프로필이 H2 jar 없이 못 뜨므로 **테스트가 깨진다** — 2차와 3차의 테스트 설정(`application-test.yml`·매개변수 `h2` 제거)은 같은 변경 묶음이어야 빌드가 녹색이다.
- **3차 — 개발·테스트 PostgreSQL 단일화** : 16장 테스트 정리, 17장 환경(미지정 실패·오접속 방지·환경변수 단순화), CI 서비스 컨테이너(19장), 동시성 테스트 보완·`s4TwoRenames` 원인 처리(14장), 16.3 대체 검증.
- **통합 여부 (PG-Q17)** : 위 주의 때문에 **2차(운영) + 3차의 '테스트 설정 최소 전환' 은 한 단계로 묶고**, 테스트 정리·CI·s4TwoRenames 는 3차로 나누는 것을 추천 — 2차 단독 커밋은 녹색 빌드가 될 수 없다.
- **FINAL** : PostgreSQL 새 빈 DB 전체 2회, HEAD(V0~V3)로 만든 DB 업그레이드 기동, 신규 초기화, Flyway 검증(`flyway_schema_history` 체크섬 불변), Docker Compose(기본·외부 PostgreSQL), 단일 JAR(H2 jar 없음 확인), 최초 설정·로그인, 주요 화면, AI·MCP 회귀(25장), H2 의존성 0 확인, 최소 지원 판 1회(22장), 문서 완료 처리.

## 30. 완료 기준 (후보)

| 항목 | 완료 조건 | 확인 방법 |
|---|---|---|
| 관리 DB | PostgreSQL 전용 | 설정·코드 검색 (`H2Dialect`·`jdbc:h2`·`org.h2` 0건, 원본/DDL 무관 확인) |
| H2 운영·개발·테스트 지원 | 제거 | 프로필·Dockerfile·Compose·테스트 프로필 |
| H2 JDBC | 의존성 트리·JAR 에 `h2-*.jar`·`spring-boot-h2console` 없음 | `dependencies`·`unzip -l` |
| H2 fallback | URL 없는 프로필 기동 = 명확한 오류로 실패 | J-1 재실행 |
| DB 연결 미설정·잘못된 연결 | 명확한 오류 후 기동 실패, 비밀값 미출력 | J-2~J-5 재실행 |
| Flyway | PostgreSQL V0~V3 정상, 체크섬 불변 | 신규·기존 DB |
| 기존 PostgreSQL 데이터 | 보존·업그레이드 호환 | HEAD 로 만든 DB 에서 기동 |
| 신규 PostgreSQL | 정상 초기화·최초 관리자 | 빈 DB |
| 원본 DB 지원 | 기존 범위 (4종 저장·PG 수집·4종 DDL) | 원본 테스트 |
| 업무 기능 테스트 | 기존 검증 범위 유지 (16장 표 근거) | 테스트 목록 대조 |
| PostgreSQL 전체 회귀 | 새 빈 DB 2회 실패 0 | |
| 동시성 정책 | 기존 유지 | 동시성 테스트 + 코드 대조 |
| s4TwoRenames | 원인 확인과 처리 결과 기록 (우연한 통과로 해결 처리 안 함) | 14장 |
| 순수 단위 테스트 | DB 없이 실행 | 비-app 모듈 `test` (DB 없는 셸) |
| 프런트엔드 | 타입 검사·빌드 | `./gradlew build` |
| 단일 JAR · Docker Compose · 외부 PostgreSQL | 유지·정상 | FINAL |
| AI·MCP | 기존 기능 유지 | 자동 + 25장 수동 |
| 신규 운영 의존성 · 신규 필수 서비스 | 없음 | 트리 비교 |
| 문서 | 현재 지원 정책 반영 | README·CLAUDE.md |

## 31. 사용자 결정 필요 사항 (추천안)

이미 확정된 단일화 방향(PG-D01~D15)은 다시 묻지 않는다.

| ID | 결정 | 추천 | 근거 |
|---|---|---|---|
| PG-Q01 | 기존 H2 사용자 데이터 이전 지원 | **A (이전 기능 없음) + 모델 Export/Import 부분 이전 안내**, H2 파일은 지우지 않음 | 11장 — 실제 사용자 [미확인], 사용자 확인 시 B |
| PG-Q02 | 원본 수집 대상으로서의 H2 | **해당 없음으로 닫음** | 5.2 — 지원한 적 없음 |
| PG-Q03 | H2 대상 물리 모델·DDL | **해당 없음으로 닫음** | 5.2 — 기능 없음 |
| PG-Q04 | 최소 지원 PostgreSQL | **15** (ICU 빌드 필수) | 22장 — Hibernate 최소 14, 14 는 지원 종료 |
| PG-Q05 | 권장 PostgreSQL | **18** (검증 18.6), Compose·CI 기본 18.6 | 22장 |
| PG-Q06 | 테스트 실행 방식 | **B — 외부 테스트 PostgreSQL (환경변수)**, Testcontainers 도입 안 함 | 17장 — 기존 방식, 새 의존성 없음 |
| PG-Q07 | 외부 테스트 DB 세부 | `SPRING_DATASOURCE_URL` 하나로 관리 DB 지정(`LEXORMA_TEST_POSTGRES_URL` 기본값 = 그것), DB 미지정이면 Spring 테스트 실패, DB 이름에 `test` 없으면 중단(우회 변수 1개), 계정은 `SPRING_DATASOURCE_USERNAME/PASSWORD` 로 받기 | 17장 |
| PG-Q08 | CI 구조 | `dev-test.yml` 에 `services: postgres:18.6` + 원본 수집 테스트용 URL, 1회 실행, 실패 시 리포트·PG 로그 업로드; Docker 이미지 빌드 확인 Job 은 선택(추천 : 추가, 푸시 없음) | 19장 |
| PG-Q09 | 필수 설정 키·기본값 | 키는 기존 그대로(`SPRING_DATASOURCE_*`·`DB_*`), 관리 DB 확인 클래스로 URL 없음·비 PostgreSQL 중단, 원인 안내 FailureAnalyzer 는 선택(추천 : 넣음), `docker` 프로필 기본 비밀번호·기본 프로필 `local` 은 **유지**(호환) + README 경고 | 8·9장 |
| PG-Q10 | Standalone 제거 범위 | 20.2 목록 전부 제거, `h2-file-storage.md` 는 "지원 종료 — 기록" 표시로 보존, `.gitignore` H2 패턴 유지 | 20·26장 |
| PG-Q11 | PostgreSQL Flyway 파일 정리 범위 | **파일·디렉터리 그대로**, `locations` 만 `db/migration/postgresql` 고정, H2 디렉터리 삭제 | 10.2 |
| PG-Q12 | 신규 마이그레이션 | **없음** (V4 불필요) | 10.2 |
| PG-Q13 | s4TwoRenames 후속 | **테스트 감시 결함으로 판정, 3차에서 감시만 보완** (재확인 읽기 + PID·차단 관계 확인, 같은 감시를 쓰는 단언 포함). 운영 코드 변경 없음. M8-C4a 의 다른 증상은 미확정 유지 | 14.4 |
| PG-Q14 | H2 전용 테스트 삭제·대체 범위 | 16.2 표대로, 16.3 은 ① 테스트 전용 기준값 설정 | 16장 |
| PG-Q15 | Compose 기본 구조 | 지금 구조 유지, H2 Compose 삭제, `LEXORMA_DATA_DIR` 필수 → 선택 완화(마운트 유지) | 21장 |
| PG-Q16 | 외부 PostgreSQL 배포 방식 | README 절 + 앱만 있는 Compose 예시 파일 1개 | 21.2 |
| PG-Q17 | 단계 분리 | **2차(운영 + 테스트 설정 최소 전환) → 3차(테스트 정리·CI·s4TwoRenames) → FINAL** | 29장 |
| PG-Q18 | 최종 회귀 범위 · 작업 ID | 29장 FINAL 목록 (AI 실제 Provider 1~2회 + MCP 1회, 최소 판 1회). 작업 ID 는 사용자가 정한다 (로드맵에 없음 — 후보 : 새 접두어 `DB-1` 또는 `PG-1`) | 25·29장, pending-work 1장 ID 규칙 |

## 32. 이번 1차 실행 검증 기록

모든 실행은 저장소 밖 격리 사본(`git archive HEAD` — 작업 트리의 다른 세션 변경 제외, `lexorma-web/dist` 는 저장소의 빌드 결과를 사본에 복사)과 **임시 PostgreSQL 18.6 컨테이너**(`127.0.0.1:25439`, tmpfs, 이 작업 전용)에서 했다. 개발 DB(15439)·개발 볼륨·기존 컨테이너는 쓰지 않았다. 운영·테스트 코드는 바꾸지 않았다.

### 32.1 의존성 · JAR

- `./gradlew :lexorma-app:dependencies --configuration runtimeClasspath|testRuntimeClasspath` 와 다른 7개 모듈 `testRuntimeClasspath` — 6.1 표.
- HEAD 사본 `:lexorma-app:bootJar -PfrontendPrebuilt=true` (113MB) : `BOOT-INF/lib/h2-2.4.240.jar`·`spring-boot-h2console-4.1.2.jar`·`application-h2-local.yml`·`application-h2-docker.yml` 포함 확인.
- `hibernate-core-7.4.13.Final` `PostgreSQLDialect.MINIMUM_VERSION` = 14 (`javap -c` 의 static 초기화 상수).

### 32.2 전체 테스트

| 실행 | 명령 | 결과 |
|---|---|---|
| H2 | `./gradlew test -PfrontendPrebuilt=true` (+ `LEXORMA_TEST_SOURCE_POSTGRES_URL`) | 1,612 · 실행 1,506 · 실패 0 · 건너뜀 106 · 305초 |
| PostgreSQL | 같은 명령 + `SPRING_DATASOURCE_URL`·`SPRING_DATASOURCE_DRIVER_CLASS_NAME`·`LEXORMA_TEST_POSTGRES_URL` (새 빈 DB `lexorma_test1`) | 1,612 · 실행 1,585 · 실패 0 · 건너뜀 27 · 463초 |

건너뜀 사유는 JUnit XML 의 `skipped message` 로 전수 분류했다 (15.3·15.4).

### 32.3 기동 시나리오 (HEAD JAR, `--server.port=0`)

| ID | 조건 | 결과 | 비밀번호 출력 |
|---|---|---|---|
| J-1 | `--spring.profiles.active=nosuchprofile` (URL 없음) | **정상 기동** — `jdbc:h2:mem:<UUID>` (사용자 `SA`), Flyway V0~V3 적용, `Started … 8.663 seconds` (60초 뒤 강제 종료) | — |
| J-2 | `docker` + `jdbc:mysql://…` | 종료 코드 1, `ClassNotFoundException: com.mysql.cj.jdbc.Driver` | 없음 |
| J-3 | `docker` + 닫힌 포트 | 종료 코드 1, `PSQLException: 127.0.0.1:25999 에 대한 연결이 거부되었습니다 …` (JVM 로캘 한국어) | 없음 |
| J-4 | `docker` + 틀린 비밀번호 | 종료 코드 1, `FATAL: password authentication failed for user "lexorma"` | 없음 |
| J-5 | `docker` + 없는 DB | 종료 코드 1, `FATAL: database "no_such_db" does not exist` | 없음 |
| J-5b | URL 에 `?password=` + 닫힌 포트 | 종료 코드 1 | 없음 |
| J-7 | `docker` + 새 빈 DB | 정상 기동, `flyway_schema_history` V0~V3 성공, `TB_ACNT` 0행 (→ `/setup`) | — |
| J-7b | URL 에 `?user=…&password=…` + 정상 접속 | 정상 기동, **Hibernate 로그 `Database JDBC URL […password=<원문>]`** | **있음** (기존 동작 — 8.1) |

- 공통 : 실패 원인은 `Caused by` 사슬 중간에만 있고 Boot 의 실패 요약은 없다. 검증에 쓴 비밀번호는 이 작업용 임시 값이며 J-7b 로그는 확인 뒤 지웠다.

### 32.4 `s4TwoRenames` 반복 · 감시 메커니즘 재현 (14.3)

- 묶음 4회 : `./gradlew :lexorma-app:test --rerun --continue --tests '*Concurren*' --tests '*Deadlock*' --tests '*LockWait*' --tests '*SourceMapping*' --tests '*SourceImport*' --tests '*SourceCollection*' --tests '*SourceRename*'` (회마다 새 빈 DB `lexorma_s4_<n>`, 원본 PostgreSQL 지정) — 각 27개 클래스 235건 · 실패 0 · 건너뜀 1 (성능), 86~89초. 외부 감시 `psql \watch 0.02` (대기 세션의 PID·문장·`pg_blocking_pids`·대기 중인 `pg_locks`).
- 결정적 재현 (임시 DB `j_new`) : A 역할 세션이 `TB_PRJ` 행을 `FOR UPDATE` 로 8초 보유 → B 역할 세션이 `select count(*) from tb_tbl …` 실행 후 2초 쉼(`idle in transaction`) → `TB_PRJ … for no key update` 로 대기. 감시 세션이 B 가 쉬는 동안 한 번 조회한 뒤 같은 트랜잭션에서 다시 조회 :

| 조회 | state | wait_event_type / wait_event | query |
|---|---|---|---|
| 첫 조회 (B 쉬는 중) | idle in transaction | Client | `select count(*) from tb_tbl …` |
| 같은 스냅숏 (B 대기 시작 뒤) | **idle in transaction** | **Lock / transactionid** | **`select count(*) from tb_tbl …`** |
| `pg_stat_clear_snapshot()` 뒤 | active | Lock / transactionid | `select prj_sn from tb_prj … for no key update` |

  → `state`·`query` 는 복사본, `wait_event` 는 실시간이다. 자동 커밋 한 문장에서는 복사와 읽기 사이가 짧을 뿐 같은 일이 생긴다 [추정 — 그 짧은 창 자체는 재현하지 못함].
- 임시 자원 : 컨테이너 `lexorma-pgonly-analysis`(tmpfs)·격리 사본·JAR 은 작업 끝에 지웠다 (32 머리말).

---

# DB-1 2차 — PostgreSQL 전용 운영·설정·배포 구조 구현 (2026-11-25)

## 33. 최종 정책 (PG-D01~D36)

- **전제 (PG-D31, 사용자 확정 2026-11-25)** : 실제 사용자는 0명이다. 그래서 하위 호환·레거시 지원·Deprecated alias·설정 자동 변환·H2 데이터 이전 도구/안내·이전 버전 업그레이드 검증을 만들지 않는다 (PG-D32~D35). 개발 데이터·다른 세션 변경 보호는 별개다 (PG-D36).
- 기본 정책 PG-D01~D15 는 2장 그대로이고, **해석이 바뀐 것** (기록은 지우지 않는다) :
  - **PG-D14** (1차 : 기존 PostgreSQL 사용자 데이터의 업그레이드 호환 보호) → 개발 데이터는 보호하되 **외부 사용자 업그레이드 호환은 요구하지 않는다** — 사용자 0명.
  - **PG-D16** (1차 PG-Q01 추천 : 이전 기능 없음 + 모델 Export/Import 부분 이전 안내) → **이전 도구·안내 모두 제공하지 않는다** — 사용자 0명이라 안내할 대상이 없다.
  - **PG-D23** (Flyway 불필요 변경 없음) : 근거가 '기존 사용자 호환' 이 아니라 **변경 필요가 없고 다른 세션의 마이그레이션 작업과 충돌할 수 있기 때문** 으로 바뀌었다. 추후 Flyway 통합은 별도 승인 대상.

| ID | 내용 | ID | 내용 |
|---|---|---|---|
| PG-D16 | H2 이전 도구·이전 가이드 미제공 | PG-D27 | 외부 PostgreSQL 연결 지원 |
| PG-D17 | 최소 15 · 권장 18 | PG-D28 | 2차 · 3차 · FINAL 단계 |
| PG-D18 | 기존 외부 PostgreSQL 테스트 방식 | PG-D29 | FINAL 에서 PostgreSQL 15 실제 호환 검증 |
| PG-D19 | 테스트 전용 DB 명시 설정 · 안전 검증 | PG-D30 | 작업 ID DB-1 |
| PG-D20 | CI PostgreSQL 전환 · 원본 수집 테스트 활성화 (3차) | PG-D31 | 실제 사용자 0명 전제 |
| PG-D21 | 운영 DB 기본 비밀번호 제거 | PG-D32 | 하위 호환·레거시 지원 미구현 |
| PG-D22 | 관리 DB JDBC URL 안 비밀번호 거절 | PG-D33 | Deprecated 코드·설정 alias·호환 계층 미유지 |
| PG-D23 | 불필요한 PostgreSQL Flyway 변경 없음 | PG-D34 | 이전 버전 데이터 이전·업그레이드 검증은 완료 기준 제외 |
| PG-D24 | 신규 마이그레이션 없음 | PG-D35 | 사용자 문서는 현재 지원 기능만 |
| PG-D25 | `s4TwoRenames` 감시 보완은 3차 | PG-D36 | 개발 데이터·다른 세션 변경 별도 보호 |
| PG-D26 | H2 전용 업무 검증은 PostgreSQL 로 대체 | | |

## 34. PG-Q01~Q18 결정 반영

| ID | 결정 (사용자) | 2차 반영 |
|---|---|---|
| Q01 | 이전 지원 없음 (PG-D16) | 이전 도구·안내 없음. README 에 H2 안내 없음 |
| Q02·Q03 | 원본·DDL 의 H2 — 해당 없음 | 원본 4종·DDL 4종 그대로 (코드 변경 없음) |
| Q04·Q05 | 최소 15 · 권장 18 (PG-D17) | README·Compose 주석. 15 는 '미검증' 표기 (FINAL) |
| Q06·Q07 | 외부 테스트 PostgreSQL + 전용 키·안전 검증 (PG-D18·D19) | 37장 |
| Q08 | CI PostgreSQL (PG-D20) | **3차** — 2차에서는 바꾸지 않음 (44장 위험) |
| Q09 | 기본 비밀번호 제거 (PG-D21) · URL 비밀번호 거절 (PG-D22) | 35장 |
| Q10 | H2 Standalone 전부 제거 | 36장. `h2-file-storage.md` 는 `completed/` 로 옮기고 '지원 종료 기록' 표시 |
| Q11·Q12 | Flyway 파일 그대로 · 신규 없음 (PG-D23·D24) | 위치만 `db/migration/postgresql` 고정, `h2/` 삭제 |
| Q13 | `s4TwoRenames` 감시 보완은 3차 (PG-D25) | 2차 실행에서 재발 여부만 기록 (41.6) |
| Q14 | H2 전용 업무 검증 → PostgreSQL 대체 (PG-D26) | 2차는 실행에 필요한 최소 변경 (38장), 최종 정리 3차 |
| Q15·Q16 | Compose 정리 · 외부 PostgreSQL 앱 전용 Compose (PG-D27) | 36장 |
| Q17 | 2차 · 3차 · FINAL (PG-D28) | 1차 추천(2차에 '테스트 설정 최소 전환' 포함)대로 |
| Q18 | 작업 ID DB-1 (PG-D30), FINAL 범위 | pending-work 항목 DB-1 |

## 35. 운영 설정 · 관리 DB 확인 · 인증정보

### 35.1 의존성 · 프로필 [코드 · 실행]

- `lexorma-app/build.gradle` : `runtimeOnly 'com.h2database:h2'` · `runtimeOnly 'org.springframework.boot:spring-boot-h2console'` 삭제 (콘솔이 h2 를 전이로 가져오므로 둘 다). 신규 운영 의존성 없음. bootJar 안에 `h2-*.jar`·`spring-boot-h2console-*.jar`·`application-h2-*.yml` 없음 [실행 — 41.2].
- 삭제 : `application-h2-local.yml`, `application-h2-docker.yml`, `config/H2FileDatabaseCheck.java`, `db/migration/h2/V0~V3`. alias·변환 없음 (PG-D33).
- `application.yml` : **기본 프로필(`spring.profiles.active: local`) 삭제** — JAR·이미지는 DB 설정을 명시해야 뜬다 (S11). 프로필 그룹에서 `h2-local` 삭제, Flyway 위치 `classpath:db/migration/postgresql` 고정, `spring.datasource.hikari.data-source-properties.reWriteBatchedInserts: true` (예전에는 각 URL 에 `?charSet=UTF-8&reWriteBatchedInserts=true` — 사용자가 URL 에 성능 옵션을 넣지 않아도 되게 옮겼다. `charSet` 은 SQL_ASCII DB 용 옛 옵션이라 뺐다).
- `application-docker.yml` : `DB_HOST/PORT/NAME/USERNAME/PASSWORD` 와 그 기본값(`lexorma-postgres`·`5432`·`lexorma`·`lexorma`·`lexorma`) 삭제 → Spring Boot 표준 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 만 (중복 키 제거, PG-D21·D33).
- `application-local.yml`·`application-dev.yml` : 개발 전용 고정 계정 유지 (local = 사용자 지시, dev = 개발 서버 — 45장 결정 사항), URL 의 옵션만 정리.
- `./gradlew bootRun` : `SPRING_PROFILES_ACTIVE` 가 없으면 `local` (Gradle 작업 설정 — 애플리케이션 기본값이 아니다).

### 35.2 관리 DB 확인 `ManagedDatabaseCheck` [코드]

`lexorma-app` `com.lexorma.config.ManagedDatabaseCheck` — BeanFactoryPostProcessor(`@Component`, 예전 `H2FileDatabaseCheck` 자리)라 DataSource·Hikari·Flyway·Hibernate 보다 먼저 돈다. **연결하지 않는다** (중복 연결 검증 없음).

| 확인 | 결과 |
|---|---|
| `spring.datasource.url` 없음·빈 값 | 중단 — 설정 키·환경변수 안내 |
| `jdbc:postgresql:` 아님 (H2·MySQL·Oracle 등) | 중단 — `지원하지 않는 관리 DB 입니다 (jdbc:h2)` (URL 나머지는 문구에 넣지 않음) |
| URL 에 인증정보 | 중단 — 35.3 |
| `spring.datasource.username` 없음·빈 값 | 중단 |
| 비밀번호 | **요구하지 않는다** — 인증 방식(scram·md5·trust·cert·`.pgpass`)은 서버가 정한다. 필요한데 없으면 첫 연결이 08004 로 멈춘다 (S07b·S07c) |

연결 실패는 Hikari·Flyway 의 기존 구조가 기동을 멈추고, `ManagedDatabaseFailureAnalyzer` (`META-INF/spring.factories`)가 받은 예외만 읽어 Spring Boot 실패 요약(`APPLICATION FAILED TO START`)으로 원인을 알려 준다 : 설정 확인 결과 / 드라이버의 URL 형식 거절 / SQLSTATE `08004` 연결 거부 · `08xxx` 연결 불가 · `28P01`/`28000` 인증 실패 · `3D000` DB 없음 · `53300` 연결 수 초과 · `42501` 권한 없음. 드라이버 문구는 비밀번호를 담지 않는다 (사용자명·호스트는 담는다). 1차에는 원인이 긴 `Caused by` 사슬 중간에만 있었다.

PostgreSQL 버전 강제 확인은 넣지 않았다 : 확인하려면 연결이 하나 더 필요하고(기동 전 추가 연결 금지 원칙), Hibernate 가 최소 14 미만에서 경고하며, 지원 범위(15+)는 문서로 둔다 (PG-D17, 13장 지시 '필요성 확인 후 판단').

### 35.3 JDBC URL 비밀번호 거절 [코드 · 실행]

pgjdbc 42.7.14 의 실제 해석 (`org.postgresql.Driver.parseURL` 직접 호출 — 1차 사본 jar) :

| URL 매개변수 | 드라이버가 비밀번호로 읽나 | Lexorma |
|---|---|---|
| `password=X`, 여러 매개변수 중 `password`, `password=`(빈 값), `password=A&password=B`(마지막 값) | 예 | 거절 |
| `sslpassword=X` (키 파일 비밀번호) | 예 (`sslpassword`) | 거절 |
| `PASSWORD=X`, `Password=X` | 아니오 (이름은 대소문자 구분) — 하지만 URL 원문이 로그에 남는다 | 거절 |
| `pass%77ord=X`, `%70assword=X` | 아니오 (이름은 디코딩하지 않는다) — 원문이 로그에 남는다 | 거절 |
| `password%3DX`, `options=-c%20password%3DX` | 아니오 — 원문이 남는다 | 거절 |
| `user:X@host` | 드라이버가 URL 자체를 거부 → Hikari 가 **URL 원문을 오류에 찍는다** | 거절 (먼저) |
| `jdbc:postgresql:db?password=X` (호스트 생략) | 예 | 거절 |
| `sslmode`·`sslrootcert`·`connectTimeout`·`ApplicationName`·`targetServerType`·`currentSchema`·다중 호스트 | — | 통과 |

규칙 : URL 을 디코딩한 뒤 대소문자 없이 `password` 가 보이거나(`sslpassword` 포함) `//…@` 사용자 정보가 있으면 거절. 드라이버가 읽는 형태보다 넓게 잡는다 — 로그에 원문이 남는 모든 변형을 막기 위해서다 (DB 이름에 `password` 가 들어가면 거절되는 오탐을 감수 — 45장). 적용 범위는 관리 DB 만, 원본 DB 연결(호스트·포트·DB 이름을 따로 받아 URL 을 서버가 만든다)은 바꾸지 않았다.

### 35.4 인증정보 로그 [실행]

모든 기동 시나리오(41.3)·Compose·`docker run` 로그 전체에서 검증용 가짜 비밀번호 문자열 0건. Hibernate 기동 로그의 `Database JDBC URL [...]` 은 이제 인증정보 없는 URL 만 찍는다 (1차 J-7b 의 비밀번호 노출 해소). 남는 출력 : 드라이버의 URL 형식 오류 때 Hikari 가 URL 원문을 오류 사슬에 찍는다 — 인증정보가 든 URL 은 그 전에 거절되므로 비밀값은 없다.

## 36. SQL · 동시성 단순화 · 배포 파일

### 36.1 관리 DB SQL 분기 [코드]

| 위치 | 변경 |
|---|---|
| `ColumnNameKeySql.key(String)` | H2 분기·`Dialect` 인자 삭제 → PostgreSQL 식 상수 (`ColumnNameDbKeys` 가 직접 부름, 내부 `key()`·SessionFactory 조회 삭제) |
| `StandardNameSql` | `H2Dialect` 분기 3곳 삭제 — PG 식 `~ '^[ -~]*$'`·`upper(x collate "C")`·`regexp_replace(…,'g')` 그대로. `LOWER/UPPER_ASCII` 상수 삭제 |
| `PhysicalNameRecommendationService` | `JAVA_JUDGE_MIN_ROWS_H2` 삭제, `javaJudgeMinRows()` = 10,000 (테스트가 리플렉션으로 부르므로 메서드는 유지) |
| `PhysicalNameRecommendationQueryRepository` | `scansBoundInListPerRow()`·쓰지 않게 된 `EntityManager` 필드 삭제 |
| 주석 | H2 측정·동작을 근거로 쓴 주석 12곳을 PostgreSQL 기준으로 고침 — 쿼리 모양은 그대로 (JPQL·QueryDSL 을 네이티브로 바꾸지 않음) |

업무 판정(명칭 정규화·대소문자·표준명 비교·정렬·검색·추천·API 응답)은 PostgreSQL 경로를 그대로 쓰므로 바뀌지 않는다 — 전체 회귀로 확인 (41.5).

### 36.2 M-8 동시성 [코드]

| 위치 | 변경 |
|---|---|
| `PhysicalModelServiceImpl` | `keyShare = !(H2Dialect)` 와 `dialect()` 삭제 → 참조 대상 `FOR KEY SHARE` 선잠금은 항상 (PostgreSQL 은 예전에도 이 경로) |
| `ReferenceSyncBoundary` | H2 전용 커밋 직전 재확인 `recheckOnH2`·`synced`·`rechecked`·`ProjectWriteGuard` 주입 삭제. 커밋 직전 동기화 루프(3회)는 그대로 |
| NOWAIT 판정 4곳 (`ModelRowLocks`·`PhysicalGenerationService`·`PhysicalNameRenameService`·`SourceImportService`) | `getErrorCode() == 50200` 삭제 → SQLSTATE `55P03` 만. 40P01 은 계속 409 로 바꾸지 않는다 |

잠금 순서·범위·NOWAIT·KEY SHARE·`MODEL_CONFLICT`·`LOCKED`·격리 수준 변경 없음. 실제 PostgreSQL 경합은 동시성 묶음 반복으로 확인 (41.6).

### 36.3 배포 파일

| 파일 | 변경 |
|---|---|
| `Dockerfile-h2`, `lexorma/docker-compose-exam/docker-compose.h2.yml` | 삭제 |
| `lexorma/docker-compose-exam/docker-compose.yml` | DB 값은 `.env` 에 한 번 — 앱은 `SPRING_DATASOURCE_*` 로 받음, URL 옵션 정리, H2 용 `LEXORMA_DATA_DIR:/data` 마운트 삭제(쓰는 곳 없음), 앱 `restart: unless-stopped`, PostgreSQL 15+/메이저 버전 데이터 디렉터리 재사용 금지 주석 |
| `lexorma/docker-compose-exam/docker-compose.external-db.yml` (신규) | 외부 PostgreSQL 용 앱 전용 — `LEXORMA_DB_URL/USERNAME/PASSWORD` (`.env`, 모두 필수) → `SPRING_DATASOURCE_*`, 암호화 키 Secret 그대로. 별도 프록시·서비스 없음 |
| `.env.example` | `POSTGRES_PASSWORD=` 빈 값(필수, 기본 비밀번호 없음), H2 이미지·데이터 디렉터리 행 삭제, 외부 PostgreSQL 절 추가 |
| `Dockerfile`·`Dockerfile.dev` | 변경 없음 (같은 3단계 빌드, 단일 JAR 유지) |
| 개발용 `lexorma/docker-compose.yml` | 변경 없음 (개발 볼륨 보호) |

H2 전용 빌드 Task·릴리스 구성·실행 스크립트는 원래 없다. 개발 H2 데이터 파일(`~/.lexorma/data` 등)은 건드리지 않았다. `.gitignore` 의 `*.mv.db` 패턴은 실수 커밋 방지로 남겼다 (40장).

## 37. 테스트 설정 최소 전환 · 테스트 DB 안전 검증

- `application-test.yml` : H2 URL·드라이버 삭제 → `spring.datasource.url/username/password = ${LEXORMA_TEST_POSTGRES_URL/_USER/_PASSWORD:}` (테스트 전용 키, 기존 `LEXORMA_TEST_POSTGRES_URL` 재사용 + 계정 키 2개 추가). Flyway 위치 `db/migration/postgresql` + `db/test-account`.
- `TestDatabaseGuard` (테스트 클래스패스 `@Component` BeanFactoryPostProcessor, 운영 확인보다 먼저) : 테스트 키 없음 → 실패, 실제 datasource URL 이 테스트 URL 과 다른 DB(쿼리 매개변수만 다를 수 있음 — 별도 스키마 테스트) → 실패(일반 `SPRING_DATASOURCE_*` 덮어쓰기 차단), 사용자명 불일치 → 실패, DB 이름에 `test` 없음 → 실패(보조 장치), PostgreSQL 아님 → 실패. Spring 없는 단위 테스트는 거치지 않는다.
- 별도 DB 컨텍스트 6개 (`InitialSetupHttpTest`·`ProjectAccessRevokeTest`·`ProjectRecoveryLoginTest`·`RequestLogStatusHttpTest`·`SessionReloginHttpTest`·`ViewAuthorizationHttpTest`) : H2 메모리 대체 분기 삭제, 테스트 URL 의 임의 스키마만.
- 실행에 필요해 2차에서 바꾼 테스트 (38장) 외의 H2 분기는 3차 (42장).
- 검증 : 41.4.

## 38. 테스트 변경 (2차 최소 범위)

| 구분 | 건수 | 내용 |
|---|---:|---|
| 삭제 | 5 | `H2FilePersistenceTest` 4 (h2-local 기능 삭제), `SubtypeStrategyConversionMigrationTest.h2FileDatabaseKeepsCheckAcrossConnectionsAndRestart` 1 (H2 CHECK 결함 회귀) |
| 매개변수 축소 | 19 | 벤더 매개변수 테스트 17개 클래스의 `"h2"` 변형 삭제 (`@ValueSource({"postgresql"})`) — 같은 단언이 PostgreSQL 변형으로 돈다 |
| 수정 | 10 | 별도 컨텍스트 6, `SecretKeyConfigurationTest`(프로필 목록 : 기본 프로필 없음 = 개발 키 없음), `IndexManagementIntegrationTest`(예제 인덱스 선언을 `postgresql` 마이그레이션에서 읽음), `SourceImportLockErrorTest`(H2 50200 경우 삭제 · 40001 은 PostgreSQL 직렬화 실패로), 벤더 테스트의 죽은 분기는 그대로(3차) |
| 신규 | 30 | `config/ManagedDatabaseCheckTest` 26 (URL 없음·비 PostgreSQL 5·인증정보 변형 14·정상 옵션 5·사용자명), `TestDatabaseGuardTest` 4 |

전체 수 : 1,612 − 24 (삭제 5 + `h2` 변형 19) + 30 = **1,618** [실행 일치]. 업무 검증 범위가 줄어든 곳 : `PhysicalNameJudgedPathIntegrationTest` 의 '기준값에서 서비스가 자바 판정 경로로 넘어가는 분기' (1차 16.3) — 3차에서 PostgreSQL 대체 검증 (PG-D26).

## 39. 문서 갱신

README (기술 스택·구조도·요구사항 PostgreSQL 필수 15+/18·빠른 시작 Compose·로컬 개발·관리 DB 설정 표·프로필 표(기본 프로필 없음)·암호화 키·마이그레이션(V0~V3, `postgresql` 만, 자동 repair 없음)·테스트(전용 키·안전 검증)·Docker A/B/C·외부 PostgreSQL·단일 JAR·비밀번호 파일·백업, `docker/` → `lexorma/` 경로 정정, H2·Standalone·`DB_*` 절 삭제), CLAUDE.md (개발 전제, 관리 DB·테스트·Flyway·Secrets 서술), `docs/reference/h2-file-storage.md` → `docs/completed/h2-file-storage.md` ('지원 종료 기록' 표시, `completed-work.md`·`column-name-uniqueness-m7.md` 링크 수정), pending-work [DB-1]. 영어 문서는 README 하나이며 한국어 사용자 문서는 따로 없다. 과거 완료 문서의 H2 검증 기록은 그대로 둔다.

## 40. H2 잔존 항목 분류 (구현 후 전수)

| 분류 | 상태 | 근거 |
|---|---|---|
| 운영 H2 JDBC · 콘솔 · 프로필 · JDBC URL · Flyway · SQL 분기 · 잠금 분기 · 예외 처리 · 배포 파일 | **없음** | 운영 소스·리소스·빌드·배포 파일 검색 0건 (`.gitignore` 주석 1줄 제외), bootJar 목록 |
| 운영 JAR 안 H2 라이브러리 | **없음** | 41.2 (`httpcore5-h2` 는 HTTP/2 — 무관) |
| H2 이전 도구 · 이전 가이드 · 레거시 alias · Deprecated 운영 코드 | **없음** | — |
| `.gitignore` `*.mv.db`·`*.trace.db`·`*.lock.db` + 주석 | 유지 | 개발 H2 파일이 저장소에 실수로 커밋되는 것 방지 (운영 경로 아님) |
| `ManagedDatabaseCheck` 의 문자열 `jdbc:h2` 테스트·문구 | 유지 | 거절 대상 표기 |
| 테스트 H2 분기 | **3차** | 42장 |
| CI H2 설정 (`dev-test.yml` 주석·DB 없음) | **3차** | 42장 |
| 과거 완료 문서 H2 기록 | 유지 | — |

## 41. 2차 실행 검증

### 41.1 검증 환경

- **작업 트리 사본** : `rsync` (제외 `/.git/`·`build/`·`node_modules/`·`.env`·`/.gradle/`·`/.idea/`·`/data/`·`/lexorma-web/dist/`·`/.claude/`·`/lexorma/volume/`·`/docker/volume/`, 1,811개 파일 28MB, `.env` 0개) → scratchpad `db1/copy`. 작업 트리에 없는 다른 세션의 `AD` 7개는 자연히 들어가지 않았다 (인덱스에만 있음). 사본의 웹 번들은 사본 안에서 새로 빌드 (`./gradlew build` 의 npm ci·vite). 사본과 작업 트리의 코드가 같음을 `diff -rq` 로 확인.
  - 첫 rsync 는 zsh 에서 제외 옵션이 한 인자로 넘어가 제외가 적용되지 않았다 → 바로 지우고 배열로 다시 만들었다 (그 사본에도 `.env` 없음 확인).
- 임시 PostgreSQL 18.6 컨테이너 2개 (tmpfs, `127.0.0.1` 만) : `lexorma-db1-pg18`(테스트 DB, 계정 `lexorma`), `lexorma-db1-pgssl`(SSL 켬 — 기동·Compose·외부 DB 시나리오, 계정 `lexorma_app`). 개발 DB(15439)·개발 볼륨·기존 컨테이너·`lexorma/lexorma:latest` 이미지는 쓰지 않았다 (검증 이미지는 `lexorma/lexorma:db1-verify`).

### 41.2 빌드 · JAR

- `./gradlew build --continue` (npm ci + `tsc -b` + vite build + 모든 모듈 테스트 + bootJar) : **BUILD SUCCESSFUL, 7분 36초**.
- bootJar 목록 : `h2-*.jar`·`spring-boot-h2console-*.jar`·`application-h2-*.yml` **없음**, `ManagedDatabaseCheck`·`ManagedDatabaseFailureAnalyzer`·`META-INF/spring.factories`·React `static/` 있음.
- Docker 이미지 `docker build -f Dockerfile` (3단계) 성공.

### 41.3 기동 시나리오 (2차 JAR, `--spring.profiles.active` 없이 `SPRING_PROFILES_ACTIVE=docker`)

| ID | 조건 | 종료 | 결과 (실패 요약 Description) | 가짜 비밀번호 출력 |
|---|---|---|---|---|
| S01·S10·S13 | 새 빈 DB, `sslmode=require&ApplicationName=…&connectTimeout=5` | 기동 9.2초 | V0~V3 적용, 연결 2개 모두 `ssl=t TLSv1.3`, 테이블 94·시퀀스 84·인덱스 258·제약 709·`SPRING_SESSION*` 2 | 0 |
| S02 | URL 없음 | 1 | `관리 DB(PostgreSQL) 연결 설정이 없습니다 : spring.datasource.url 이 비어 있습니다.` | 0 |
| S03 | 빈 URL | 1 | 같음 | 0 |
| S04 | `jdbc:h2:mem:…` | 1 | `지원하지 않는 관리 DB 입니다 (jdbc:h2). …` — **H2 로 뜨지 않음** | 0 |
| S05 | `jdbc:mysql:…` | 1 | `지원하지 않는 관리 DB 입니다 (jdbc:mysql). …` | 0 |
| S06 | 닫힌 포트 | 1 | `… 서버에 연결할 수 없음 (…) (SQLSTATE 08001) — … 연결이 거부되었습니다 …` | 0 |
| S06b | 형식 오류 URL (포트 `x`) | 1 | `관리 DB JDBC URL 형식이 잘못됐습니다 (PostgreSQL 드라이버가 해석하지 못함).` | 0 |
| S07 | 틀린 비밀번호 | 1 | `… 인증 실패 (…) (SQLSTATE 28P01) — FATAL: password authentication failed for user "lexorma_app"` | 0 |
| S07b · S07c | 비밀번호 없음 · 빈 비밀번호 (SCRAM 서버) | 1 | `… 서버가 연결을 거부함 (비밀번호 미지정 · 인증 방식 · pg_hba.conf) (SQLSTATE 08004) — 서버가 SCRAM 기반 인증을 요청했지만, 비밀번호가 제공되지 않았습니다.` | 0 |
| S08 | 없는 DB | 1 | `… 데이터베이스가 없음 (SQLSTATE 3D000) — FATAL: database "no_such_db" does not exist` | 0 |
| S09 · S09b | URL 에 `password=` · `user:pw@` | 1 | `관리 DB JDBC URL 에 인증정보(…)를 넣을 수 없습니다.` — 연결·Hibernate 이전 | 0 |
| S11 | 프로필 없음 · 설정 없음 | 1 | S02 와 같음 (예전에는 기본 `local` 로 개발 DB 에 붙으려 했고, 1차 J-1 은 H2 메모리로 떴다) | 0 |
| S12 | 비밀번호를 configtree 파일 `spring.datasource.password` 로 | 기동 | 정상 | 0 |
| S14 | 정상 종료(SIGTERM) 후 같은 DB 로 재시작 | 기동 6.8초 | `Successfully validated 4 migrations`, `Schema "public" is up to date`, `flyway_schema_history` 4행 그대로 | 0 |
| S15 | 사용자명 없음 | 1 | `관리 DB 사용자명이 없습니다 : spring.datasource.username 이 비어 있습니다.` | 0 |

S06b·S07b 는 첫 실행에서 '연결할 수 없음'·요약 없음으로 나와 분석기에 08004·URL 형식 경우를 더한 뒤 다시 확인했다 (다른 시나리오는 같은 코드 경로).

### 41.4 신규 DB 데이터 정합성 · 재시작 (S10·S14, HTTP API 스크립트)

최초 관리자 설정(`/api/auth/setup`) → 로그인 → 프로젝트 생성 → 그 프로젝트로 로그인 → 표준 도메인·단어 2·용어 등록 → 논리 모델·엔터티·속성(용어 연결) → 논리 ERD 배치 저장 → 테이블 등록(표준명 `CUST`, 관리자 등록이라 승인 `Y`) → 물리 ERD 배치 → 엔터티↔테이블 매핑 → 원본 연결 설정 저장 → AI Provider 등록(비활성) → 프로젝트 MCP 켬 : **모두 200**. 재시작 뒤 : `setup required=false`(중복 초기화 없음), 로그인·프로젝트 접근, 용어·엔터티·논리/물리 배치 좌표(40.5, 12 / 7, 8)·매핑 상태(매핑 엔터티 1)·원본 연결·Provider·MCP 설정·테이블 승인 `Y` 유지, 새 테이블 추가 등록 200 (첫 시도는 사전에 없는 단어 '이력' 으로 업무 검증 400 — 정상 동작). `TB_ACNT` 1행.

### 41.5 PostgreSQL 전체 회귀 (`./gradlew build` 안, 새 빈 DB `lexorma_test_full1`, 원본 DB 지정)

| 항목 | 1차 기준 | 2차 |
|---|---:|---:|
| 전체 | 1,612 | **1,618** |
| 실제 실행 | 1,585 | **1,591** |
| 실패 | 0 | **0** |
| 건너뜀 | 27 | **27** (모두 `LEXORMA_PERF` 성능 시험 — 기존 opt-in 그대로) |
| 테스트 클래스 시간 합 | 418초 | 399초 |
| 원본 수집 테스트 19건 | 실행·통과 | **실행·통과** |

신규 30 · 수정 10 · 삭제 5 · 매개변수 축소 19 (38장). H2 전체 테스트는 수행하지 않았다 (PG-D04).

### 41.6 동시성 반복 · 간헐 실패

동시성·승인·매핑·원본 묶음 (`*Concurren*`·`*Deadlock*`·`*LockWait*`·`*SourceMapping*`·`*SourceImport*`·`*SourceCollection*`·`*SourceRename*`·`*Approval*`·`*ModelMapping*` — 37개 클래스 271건 : 엔터티·테이블 승인↔수정, 프로젝트 잠금, NOWAIT·KEY SHARE, REF_COLUMN 동기화, PK 변경, FK 등록, 표준 항목 삭제, 논리·물리 매핑, `MODEL_CONFLICT`·`LOCKED`, 잠금 감시 재현) 을 **회마다 새 빈 DB 로 3회** : 각 271건 · 실패 0 · 건너뜀 1 (성능), 94~100초. 3회 중 일부는 Docker 이미지 빌드와 같은 기계에서 겹쳐 돌았다 (부하가 더 높은 조건).

- `s4TwoRenames` : 전체 회귀 1회 + 묶음 3회 = 12라운드 모두 B 의 대기 문장 `tb_prj …` — **재발 없음** (감시 결함은 그대로 — 3차, 해결로 보지 않는다).
- `ConcurrentEditC2IntegrationTest.entityApprovalVsEdit` : 4회 모두 통과 — 원인 미확정 그대로.
- H2 전용 커밋 직전 재확인·KEY SHARE 생략·50200 판정 삭제 뒤에도 PostgreSQL 409/`LOCKED` 계약 테스트가 모두 통과 — 운영 결함 발견 없음.

### 41.7 단위 테스트 DB 없이 · 테스트 DB 안전 검증

- **DB 없이** (테스트 키·`SPRING_DATASOURCE_*` 모두 없음) : 비-app 7개 모듈 `test` (482건) BUILD SUCCESSFUL, app 의 Spring 없는 테스트 (`ManagedDatabaseCheckTest`·`TestDatabaseGuardTest`·`ModuleBoundaryTest`·`MessagesCoverageTest`·`EntityJsonExposureTest`·`ReviewReasonContractTest`·`SecretKeyConfigurationTest`) BUILD SUCCESSFUL.
- **테스트 DB 안전 검증** (`StandardTermApprovalIntegrationTest` 13건으로, 임시 PostgreSQL) :

| ID | 조건 | 결과 (컨텍스트 실패 원인) |
|---|---|---|
| G1 | 테스트 DB 설정 없음 | 13건 실패 — `테스트 DB 가 지정되지 않았습니다 : LEXORMA_TEST_POSTGRES_URL · LEXORMA_TEST_POSTGRES_USER …` |
| G2 | 일반 `SPRING_DATASOURCE_*` 만 (개발 DB 모양 `lexorma_guard`) | 13건 실패 — G1 과 같음. `lexorma_guard` 테이블 **0개** (Flyway 가 돌지 않음) |
| G3 | 테스트 키 + 일반 URL 이 덮어씀 | 실패 — `… spring.datasource.url 이 LEXORMA_TEST_POSTGRES_URL 와 다른 DB 를 가리킵니다 …` |
| G4 | 테스트 DB 이름에 `test` 없음 | 실패 — `… DB 이름에 test 가 없습니다 …` |
| G5 | 테스트 URL 이 닫힌 포트 | 실패 — `Connection refused` (연결 단계) |
| G6 | 정상 테스트 DB | 13건 통과 |

- 단일 JAR SPA 제공 : `/`·`/login`·`/setup`·`/dashboard`·`/logicalErd` 200 (`<title>Lexorma</title>`), `/assets/index-*.js` 200. `/h2-console` 은 이제 SPA 경로(200 index) — 콘솔 없음.
- AI·MCP : 전체 회귀 안의 AI 실행 안정성·근거 검증·권한 재검증·가짜 Provider·MCP(비활성/활성·Tool·격리) 통합 테스트 모두 통과. 실제 Ollama·외부 Provider 호출 없음 (FINAL).

### 41.8 Docker 실제 배포

| 검증 | 결과 |
|---|---|
| 기본 Compose (`docker-compose.yml`, 파일 수정 없이 임시 `.env`, 프로젝트 `lexorma-db1a`, 포트 25443·19941) `config` | 통과. `POSTGRES_PASSWORD`·`POSTGRES_USER`·`POSTGRES_DB`·`POSTGRES_DATA_DIR` 를 각각 비우면 `required variable … is missing a value: Set … in .env` 로 거절 (기본 비밀번호로 뜨지 않음) |
| 기동 | PostgreSQL healthy → 앱 기동 (8.9초, V0~V3 적용) |
| 최초 관리자·로그인·프로젝트·41.4 와 같은 등록 | 모두 200 |
| `docker compose restart` 뒤 | `Schema "public" is up to date`, 등록 데이터·배치·매핑·원본 연결 유지, 추가 등록 200 |
| 암호화 키 Docker Secret (`.env` `LEXORMA_SECRET_KEYS`) | `/run/secrets/lexorma.secret.keys` 마운트, 원본 비밀번호 저장 → `passwordStatus=CONFIGURED`, DB 값 `v1:v1test:…` 암호문, 로그에 원문 없음 |
| 외부 PostgreSQL (`docker-compose.external-db.yml`, `host.docker.internal:25441`, `sslmode=require`) | `LEXORMA_DB_URL/USERNAME/PASSWORD` 를 각각 비우면 거절. 기동(10.2초, V0~V3 적용), PostgreSQL 컨테이너 없음, 연결 10개 모두 SSL, 41.4 등록 모두 200 |
| `docker run` 필수 설정 누락·잘못된 연결 | 설정 없음 → S02 문구, 사용자명 없음 → S15, 비밀번호 없음 → 08004, 없는 호스트 → 08001, URL 비밀번호 → 거절 : 모두 종료 코드 1, 가짜 비밀번호 출력 0 |
| `docker run` 비밀번호 파일 (`-v …:/run/secrets/spring.datasource.password:ro` + `SPRING_CONFIG_IMPORT`) | 기동 성공 |
| Secret 없는 일반 환경 | 외부 DB Compose (`LEXORMA_SECRET_KEYS` 빈 값) 정상 |

## 42. 3차 인계 (정확한 대상)

| 항목 | 대상 |
|---|---|
| 벤더 매개변수 테스트의 죽은 `"h2"` 분기 (`vendor.equals("h2")` · `jdbc:h2:mem` · `db/migration/h2` 문자열) | 17개 클래스 : `AiDefaultProviderIntegrationTest`·`AiDictionaryMigrationTest`·`ColumnNameUnicodeMigrationTest`·`ColumnNameUniqueMigrationTest`·`ColumnRequiredValuesMigrationTest`·`EmbeddedMcpMigrationTest`·`IdentifierQualityIntegrationTest`·`LexormaIndexSeedTest`·`LogicalRelationshipAttributeMigrationTest`·`LogicalSubjectAreaMigrationTest`·`LogicalSubtypeMigrationTest`·`SourceRenameMigrationTest`·`SourceViewDefinitionMigrationTest`·`StandardizationQualityRulesIntegrationTest`·`SubtypeIdentifierColumnMappingMigrationTest`·`SubtypeStrategyConversionMigrationTest`·`V0SeedMigrationTest` — 매개변수 자체 제거 여부 포함 |
| 직접 JDBC 테스트의 DB 키·계정 단일화 | `getenv("LEXORMA_TEST_POSTGRES_URL")` 24개 클래스 + 고정 계정 `"lexorma", "lexorma"` → 테스트 키 `_USER`·`_PASSWORD`, URL 미지정 시 건너뜀(`assumeTrue`) → 실패로 (PG-D19) |
| H2 세션 감시 분기 (`INFORMATION_SCHEMA.SESSIONS`) | `ConcurrencyDiagnostics`·`ConcurrentEditC2`·`C3`·`C4`·`C4b`·`C4bPkChange`·`C4bRefColumn`·`ConcurrentEditC4cSupport`·`ConcurrentEditP1`·`ModelVersionConcurrency`·`PhysicalNameRenameIntegrationTest`·`RelationSyncDeadlock`·`SourceCollectionIntegrationTest`·`SourceImportIntegrationTest`·`SourceMappingIntegrationTest`·`LogicalM6FinalPerformanceTest` |
| `h2()`·`postgres()` DB 판별 분기 | `ColumnNameUniquenessIntegrationTest`·`PhysicalNameRecommendationConflictIntegrationTest`·`PhysicalNameNonAsciiScaleTest`·`PhysicalNameF02PocTest`·`ColumnNameUnicodeMigrationTest`·`InheritanceReferenceKeyIntegrationTest`(`if (!postgres()) return`) |
| PostgreSQL 전용 79건의 조건 정리 | `assumeTrue(postgres())` 53 (5개 클래스) + `LEXORMA_TEST_POSTGRES_URL` 가정 26 → 조건 삭제 |
| H2 를 언급하는 테스트 주석 | 약 70개 파일 (`H2·PostgreSQL` 서술 등) |
| `PhysicalNameJudgedPathIntegrationTest` | 서비스 경로의 자바 판정 분기 PostgreSQL 대체 검증 (1차 16.3 ①~③ 중 선택) |
| 테스트 프로필 최종 정리 · `build.gradle` 테스트 힙(2g) 측정 | `application-test.yml`, 루트 `build.gradle` |
| CI | `.github/workflows/dev-test.yml` : `services: postgres:18.6`, 테스트 키 3개 + `LEXORMA_TEST_SOURCE_POSTGRES_URL` (원본 수집 19건 활성화), 실패 시 리포트·PostgreSQL 로그 업로드, Docker 이미지 빌드 확인 Job(푸시 없음), 주석 정리 |
| `s4TwoRenames` 감시 | `RelationSyncDeadlockIntegrationTest.waitForLockWaitOnce`·`pausedThen` : B 백엔드 PID 식별, `pg_blocking_pids` 로 A 가 막는 세션만, 재확인 읽기(`state='active'` + 같은 문장), 같은 감시를 쓰는 :546·:647·:690 과 '기다리지 않는다' 단언 (14.4) |
| 동시성 감시 안정성 | 위 패턴을 쓰는 다른 감시 (`ConcurrentEdit*` 의 `lockWaitCount` 는 수만 세므로 영향 작음 — 확인) |

## 43. 미검증 · 남은 위험 (2차)

- **미검증** : PostgreSQL 15·16·17 (FINAL — PG-D29), 실제 Ollama·외부 MCP 클라이언트 (FINAL), 브라우저 화면 조작 (2차는 HTTP API·SPA 정적 파일 제공까지 — 프런트 코드 변경 없음), `reWriteBatchedInserts` 를 Hikari 드라이버 속성으로 옮긴 뒤 실제 다중 행 INSERT 재작성 여부 (전체 회귀·엑셀 테스트는 통과, SQL 모양은 보지 않음), 비밀번호 없는 인증 방식(trust·cert·`.pgpass`) 실제 연결 (코드가 막지 않는 것만 확인), Windows·Linux 호스트의 Compose (macOS Docker Desktop 만).
- **남은 위험** : ① **CI** — `dev-test.yml` 은 아직 DB 없이 `./gradlew build` 를 돌린다. 2차를 `dev` 에 푸시하면 Spring 테스트가 `TestDatabaseGuard` 로 실패해 CI 가 빨갛게 된다 (3차에서 전환 — 3차와 함께 푸시하거나 실패를 감수) ② 직접 JDBC 테스트의 고정 계정 `lexorma/lexorma` — 테스트 계정이 다르면 그 테스트가 실패 (3차) ③ URL 비밀번호 규칙의 오탐 (DB·호스트 이름에 `password` 가 들어가면 거절) ④ `dev` 프로필의 고정 계정 (45장) ⑤ 1차의 공용 개발 볼륨 폴더 공유(21.1)는 그대로 ⑥ `s4TwoRenames` 감시 결함은 3차 전까지 남는다.

## 44. 2차 완료 기준 판정

| 항목 | 판정 | 근거 |
|---|---|---|
| H2 운영 의존성 · 콘솔 · 프로필 · fallback · 배포 · Flyway 리소스 | 충족 | 35.1·36.3·40, S04·S11 |
| PostgreSQL 연결 필수 · 기본 비밀번호 제거 · URL 비밀번호 초기화 전 거절 · 인증정보 로그 없음 | 충족 | 35장, 41.3·41.8 |
| PostgreSQL Flyway 정상 · 신규 마이그레이션 없음 · 기존 PostgreSQL 파일 무변경 | 충족 | `db/migration/postgresql` 변경 없음 (`git status`), S10·S14 |
| SQL 분기 PostgreSQL 전용 · H2 동시성 코드 제거 · 동시성 정책 유지 | 충족 | 36장, 41.5·41.6 |
| 원본 DB 기능 · 단일 JAR · Docker Compose · 외부 PostgreSQL · 신규 DB 초기화 · 재시작 유지 | 충족 | 41.3·41.4·41.8 |
| 테스트 설정 · 테스트 DB 보호 · 순수 단위 테스트 DB 없이 | 충족 | 37장, 41.7 |
| PostgreSQL 전체 회귀 실패 0 · 원본 수집 테스트 · 동시성 반복 기록 | 충족 | 41.5·41.6 |
| 프런트엔드 무변경·빌드 · AI·MCP 계약 · 신규 운영 의존성·필수 서비스 없음 | 충족 | 41.2·41.7 |
| H2 이전 기능·가이드·레거시 계층 없음 | 충족 | 40장 |
| 3차 인계 목록 | 충족 | 42장 |

**2차 완료 기준은 모두 충족 (사용자 검토 대기).** DB-1 전체 완료는 아니다 — 3차(테스트 정리·CI·`s4TwoRenames` 감시)와 FINAL(PostgreSQL 15·실제 AI·MCP·최종 2회 회귀)이 남았다. 사용자 결정이 필요한 세부는 45장.

## 45. 사용자 결정 · 확인 필요 (2차에서 정한 세부 구현)

1. **`dev` 프로필의 고정 계정** (`lexorma-postgres:5432/lexorma`, `lexorma/lexorma`) : 개발 서버(`Dockerfile.dev`) 설정이라 local 처럼 남겼다. 운영 기본 비밀번호 제거(PG-D21)를 dev 에도 적용할지 — 적용하면 그 서버 배포에 환경변수가 필요해진다.
2. **외부 PostgreSQL Compose 의 `.env` 키 이름** `LEXORMA_DB_URL/USERNAME/PASSWORD` (컨테이너 안에서는 Spring 표준 `SPRING_DATASOURCE_*`). 기본 Compose 의 `POSTGRES_*` 와 뜻이 달라 따로 두었다. 이 Compose 는 비밀번호를 필수로 받는다 — 비밀번호 없는 인증(cert 등)은 `docker run` 으로.
3. **URL 비밀번호 규칙의 넓이** : 디코딩 후 대소문자 무시 `password` 포함이면 거절 (DB·호스트 이름에 그 글자가 있으면 오탐). 더 좁히려면 드라이버가 실제로 읽는 `password`·`sslpassword` 만으로 — 그 경우 `PASSWORD=` 같은 변형은 로그에 남는다.
4. **`./gradlew bootRun` 의 기본 프로필 `local`** : 애플리케이션 기본 프로필은 없앴고, 개발 편의로 Gradle 작업에만 두었다.
5. **CI** : 3차 전까지 `dev` 푸시 CI 가 실패한다 (43장 ①). 2차·3차를 함께 커밋/푸시할지.
6. **`reWriteBatchedInserts`** 를 URL 대신 Hikari 드라이버 속성(`application.yml`)으로 옮겼다. 사용자 URL 에 같은 옵션을 넣어도 동작은 같다.
7. **`.gitignore` 의 H2 파일 패턴** 은 남겼다 (지워도 무해 — 개발 H2 파일 실수 커밋 방지용).

---

# DB-1 3차 — PostgreSQL 테스트·CI 단일화 및 운영 설정 최종 보완 (2026-11-25)

> 표기 : **[실행]** = 이번에 실제로 돌려 본 결과, **[코드]** = 코드 분석, **[기존]** = 앞선 차수의 기록. 1·2차 기록(1~45장)은 그대로 두었다.

## 46. 확정 정책 PG-D37~D44 · 2차 결정 사항 반영

| ID | 확정 내용 (사용자, 2026-11-25) | 반영 |
|---|---|---|
| PG-D37 | local·dev 프로필의 고정 DB 주소·계정·비밀번호 제거 | `application-local.yml`·`application-dev.yml` 의 `spring.datasource` 블록 삭제 (47.1) |
| PG-D38 | 관리 DB 설정은 Spring Boot 표준 키로 통일 | 외부 Compose 의 `LEXORMA_DB_*` → `SPRING_DATASOURCE_*` (47.2). 남은 키 : `SPRING_DATASOURCE_*`(앱) · `POSTGRES_*`(공식 이미지) · `LEXORMA_TEST_*`(테스트) — 역할이 달라 유지 |
| PG-D39 | 외부 PostgreSQL 인증 방식은 DB 서버 정책을 따른다 | 외부 Compose 의 비밀번호 필수(`:?`) 제거 → 그대로 전달 (설정 없음 = 비밀번호 없음, 빈 값 = 빈 비밀번호) (47.2) |
| PG-D40 | JDBC URL 인증정보 검사를 구성 요소 기반으로 | `ManagedDatabaseCheck.inspect` (48장) |
| PG-D41 | Gradle bootRun 의 local 기본 프로필 유지 | `lexorma-app/build.gradle` 그대로, DB 설정은 주지 않는다 (47.3) |
| PG-D42 | DB-1 3차·FINAL 뒤 사용자가 직접 통합 커밋 | 커밋·스테이징 없음. 원격 CI 는 실행하지 않았다 (52.4) |
| PG-D43 | `reWriteBatchedInserts` 는 드라이버 속성 방식 유지 + 실제 적용 검증 | 설정 그대로, 회귀 테스트 `JdbcBatchRewriteIntegrationTest` 추가 (51장) |
| PG-D44 | `.gitignore` 의 H2 파일 패턴 유지 | 패턴 유지, 주석만 '실수 커밋 방지' 로 (54장) |

2차 결정 요청(45장) 처리 : ① dev 고정 계정 → PG-D37 로 제거 · ② 외부 Compose 키 → PG-D38·D39 · ③ URL 규칙 넓이 → PG-D40 (오탐 제거, 변형 차단은 유지) · ④ bootRun local → PG-D41 유지 · ⑤ CI 와 커밋 → PG-D42 (3차에서 CI 전환, 커밋은 FINAL 뒤) · ⑥ `reWriteBatchedInserts` → PG-D43 · ⑦ `.gitignore` → PG-D44.

선행 상태 [실행 — 읽기 전용 Git] : 브랜치 `dev`, HEAD `7e8809e`. 다른 세션의 `AD` 7개(V5~V7 마이그레이션 6 · `logical-subtype.md`)는 인덱스에만 있고 작업 트리에 없다 — 그대로 두었다. 기존 스테이징(`A`·`AM`)은 바꾸지 않았다 (`TestDatabaseGuard.java`·`ManagedDatabaseCheckTest.java` 는 3차 수정으로 `A` → `AM`, 스테이징 명령은 쓰지 않았다).

## 47. 운영 설정 정리

### 47.1 local · dev 프로필 [코드]

- `application-local.yml` : `spring.datasource.url/username/password` (`localhost:15439/lexorma`, `lexorma/lexorma`) 삭제. DevTools 재시작 끔·Swagger·JPA 설정·개발 전용 공개 암호화 키는 그대로. 머리 주석에 '프로필은 DB 정보를 주지 않는다, SPRING_DATASOURCE_* 로' 를 적었다.
- `application-dev.yml` : `lexorma-postgres:5432/lexorma`, `lexorma/lexorma` 삭제. 포트 18080·JPA 설정 그대로.
- 개발 서버 배포 파일 : `Dockerfile.dev` 는 `CMD --spring.profiles.active=dev` 뿐이고 계정을 참조하지 않는다 [코드]. 실제 개발 서버 설정(서버의 환경변수)은 이 저장소에 없고 바꾸지 않았다 — **dev 이미지로 배포하는 곳은 이제 `SPRING_DATASOURCE_*` 를 줘야 뜬다** (64장 확인 요청).
- `lexorma/docker-compose.yml` (개발용 PostgreSQL 컨테이너, `POSTGRES_*` = lexorma) 은 DB 서버 쪽 설정이라 그대로 — 개발 DB·볼륨 보호 (PG-D36).
- `ManagedDatabaseCheck` 의 'URL 없음' 안내에서 "로컬 개발은 --spring.profiles.active=local" 을 빼고 "프로필(local · dev 포함)은 DB 연결 정보를 주지 않습니다" 로 바꿨다.

### 47.2 Spring Boot 표준 설정 · 외부 PostgreSQL Compose [코드]

- 애플리케이션 관리 DB 키 : `SPRING_DATASOURCE_URL`(필수) · `SPRING_DATASOURCE_USERNAME`(필수) · `SPRING_DATASOURCE_PASSWORD`(서버 인증 방식에 따름). 같은 뜻의 다른 키는 없다 (`DB_*` 는 2차에서, `LEXORMA_DB_*` 는 이번에 삭제 — alias·변환 없음). 저장소 전체에서 `LEXORMA_DB_`·`DB_URL` 참조 0 (README·Compose·코드) [실행 — grep].
- `docker-compose.external-db.yml` : `SPRING_DATASOURCE_URL`·`_USERNAME` 은 `${…:?}` 로 필수, `SPRING_DATASOURCE_PASSWORD:` 는 값 없는 전달 — Compose 는 `.env`(또는 셸)에 **없으면 컨테이너에 넣지 않고, 빈 값이면 빈 문자열** 로 넘긴다 [실행 — `docker compose config` : 값 있음 → 값, `X=` → `""`, 줄 없음 → `null`]. Docker 암호화 키 Secret 은 그대로.
- `.env.example` 의 외부 PostgreSQL 절을 표준 키 3개로 바꾸고 '비밀번호는 인증 방식에 따라, 필요 없으면 줄을 지운다' 를 적었다. 기본 Compose 의 `POSTGRES_PASSWORD` 필수(`:?`)·기본 비밀번호 없음·두 컨테이너 공유는 그대로 (PG-D39 의 기본 Compose 정책).

### 47.3 bootRun 기본 프로필 (PG-D41) [코드]

| 실행 | 프로필 | DB 설정 |
|---|---|---|
| `./gradlew bootRun` | `SPRING_PROFILES_ACTIVE` 가 없으면 `local` (Gradle 작업 설정) | `SPRING_DATASOURCE_*` 필수 |
| `java -jar lexorma.jar` | 지정한 것만 (없으면 기본 프로필 없음) | 필수 |
| Docker | Dockerfile 의 `CMD` (`docker`·`dev`) 또는 배포 설정 | 필수 |
| 테스트 | `test` | 테스트 전용 키 (`LEXORMA_TEST_POSTGRES_*`) |

## 48. JDBC URL 인증정보 검사 (PG-D40)

### 48.1 pgJDBC 의 URL 해석 [코드 — pgjdbc 42.7.14 `Driver.parseURL`, 1차 32장 실측]

- `jdbc:postgresql:` 뒤를 첫 `?` 에서 나눈다. 앞이 `//` 로 시작하면 다음 `/` 까지가 호스트 목록(`호스트[:포트]` 를 쉼표로, IPv6 는 `[…]`), 나머지가 DB 이름(URL 디코딩). 아니면 전체가 DB 이름.
- 뒤는 `&` 로 나눈 `이름=값` (값은 한 번 디코딩). 비밀번호로 읽는 이름은 대소문자 그대로의 `password`·`sslpassword` 뿐 — 인코딩된 이름(`pass%77ord`)이나 `PASSWORD` 는 드라이버가 비밀번호로 쓰지 않지만 **URL 원문이 Hibernate 기동 로그와 Hikari 오류에 남는다** (2차 35장).
- `user:pw@호스트` 는 드라이버가 받지 않고, 그 오류 문구에 URL 원문이 들어간다 (2차 S09b — Analyzer 가 원문 없이 다시 쓴다).

### 48.2 판정 (`ManagedDatabaseCheck.inspect` — 연결·드라이버 호출 없음, 정규식·`URLDecoder` 한 번)

| 구성 요소 | 인증정보 (거절) | 형식 오류 (거절) | 통과 |
|---|---|---|---|
| 호스트 부분 | `@` 가 있음 (`user:pw@`·`user@`, 다중 호스트 중 하나라도) | `호스트[:포트]` · `[IPv6]` 가 아닌 것 (공백·문자 포트 등) | `password-management-db`, `h1:5432,h2:5433,[::1]:5434`, 빈 호스트(`///db`) |
| 매개변수 이름 (한 번 디코딩) | 대소문자 무시 `password` 포함 — `password`·`sslpassword`·`PASSWORD`·`pass%77ord`·`%70assword`·`password%3D…`(= 없이 이름만) · 빈 값 · 중복 | 디코딩 뒤 `[A-Za-z][A-Za-z0-9_.-]*` 가 아님 (이중 인코딩 `pass%2577ord`, 빈 이름 `=x`), 잘못된 `%` | 그 밖의 이름 |
| 매개변수 값 (한 번 디코딩) | `(ssl)password` + `=`·`:`·`%3D`·`%3A` 표현 (`options=-c password=…`, `options=-c sslpassword:…`, `ApplicationName=x password=…`) | 잘못된 `%` (그 조각에 `password` 글자가 있으면 인증정보로) | `ApplicationName=password-service`, `options=-c search_path=password_app` |
| DB 이름 | 위와 같은 표현 (`/db;password=…`) | 잘못된 `%` | `password_service` |

- 판정 순서 : 인증정보가 형식 오류보다 먼저다 (둘 다 있으면 인증정보로 알린다). 디코딩은 한 번만 (드라이버와 같다) — 이중 인코딩 이름은 반복 디코딩 대신 '평범한 이름이 아님' 으로 형식 오류 처리.
- 오류 문구 : 인증정보 = "…인증정보(password · sslpassword 매개변수, 값 안의 password= 표현 또는 user:password@)를 넣을 수 없습니다", 형식 = "…형식이 올바르지 않습니다 (호스트 목록 · 매개변수 이름 · % 표기)". 둘 다 URL·값을 넣지 않는다.
- **한계 (문서화)** : 위 표현만 막는다. 임의의 값이 비밀값인지는 판별할 수 없다 (예 : `ApplicationName=<비밀값>` 은 통과하고 로그에 남는다) — README 6장 "never put one in the URL". 매개변수 이름·호스트 문법을 pgJDBC 보다 엄격하게 본다 (드라이버가 받는 특이한 호스트 표기 — 예 : 퍼센트 인코딩된 호스트 — 는 형식 오류).

### 48.3 단위 테스트 (`ManagedDatabaseCheckTest`, DB 없음) [실행]

- 인증정보 거절 **22** (2차 14 그대로 + 이번 8 : `sslpassword` 빈 값+중복, 이름만 `password`, `u@h`, 다중 호스트 중 `u:pw@h2`, `options` 안 `sslpassword:`, `ApplicationName` 값 안 `password=`, DB 이름 `;password=`, 잘못된 `%` 와 함께 쓴 `password`) — 문구·안내에 가짜 비밀번호 없음.
- 통과 **13** (2차 5 + 이번 8 : 호스트 `password-management-db`, DB `password_service`, 값 `password-service`·`search_path=password_app`, 다중 호스트 3개 + `[::1]`, `[fe80::1%25en0]`, `///db`, 인증서 옵션 + 빈 `&&`).
- 형식 오류 **8** (`sslmode=require%`, `ssl%mode`, `pass%2577ord`, 공백 호스트, 문자 포트, 빈 이름, DB `%zz`, 이름 `pass%zzword`).
- 지원 안 함 5 · URL 없음 · 사용자명 — 2차 그대로. 합계 50건 실패 0.

## 49. 테스트 정리 — H2 제거 · PostgreSQL 단일화

### 49.1 전수 조사 (3차 시작 시점 → 끝) [실행 — grep, `lexorma-*/src/test`]

| 형태 | 시작 | 끝 | 처리 |
|---|---:|---:|---|
| `vendor.equals("h2")` 분기 | 43줄 / 17파일 | 0 | 분기·H2 기대값 삭제 |
| `jdbc:h2` | 20줄 / 19파일 | 2 | 남은 2 = H2 URL 을 **거절하는지** 보는 현재 정책 테스트 (`ManagedDatabaseCheckTest.unsupported`, `TestDatabaseGuardTest`) |
| `@ValueSource(strings = {"postgresql"})` 벤더 매개변수 | 19메서드 / 17파일 | 0 | 일반 `@Test` + `@DisplayName("… PostgreSQL …")` (매개변수 변형 19 → 0, 실행 수는 같다) |
| `db/migration/" + vendor` | 17 | 0 | `classpath:db/migration/postgresql` |
| `INFORMATION_SCHEMA.SESSIONS` (H2 세션 감시) | 16줄 / 14파일 | 0 | PostgreSQL 감시만 (50장) |
| `postgres()`·`h2()`·`isPostgres()`·제품명 판별 | 115줄 / 23파일 | 0 (성능 시험 보고용 제품명 출력 1) | 판별 메서드·조건 삭제 |
| `assumeTrue(postgres())` (PostgreSQL 이 아니면 건너뜀) | 53 / 5파일 | 0 | 삭제 — 관리 DB 종류로 건너뛰는 테스트 없음 |
| `getenv("LEXORMA_TEST_POSTGRES_URL")` + `assumeTrue(url != null)` (URL 없으면 건너뜀) | 24파일 | 0 | `TestDatabaseGuard.directDataSource()`·`singleConnectionDataSource()` — 키가 없으면 실패 |
| 고정 계정 `"lexorma", "lexorma"` | 25줄 / 23파일 | 0 | 테스트 키 `_USER`·`_PASSWORD` |
| 원본 수집 테스트 기본 계정 (`getOrDefault(…, "lexorma")`) | 2 | 0 | `_USER` 필수 (49.4) |
| H2 를 현재 동작처럼 적은 주석 | 약 129줄 | 7 | 남은 7 = 날짜가 있는 과거 기록 (예 : "C2 때 예전 H2 의 500 은 …", "(H2 · PostgreSQL 모두 7건 실패 — 문서 기록)", V3 가 생긴 이유) |

남은 `assumeTrue` 는 관리 DB 와 무관한 명시적 실행 조건뿐이다 : 원본 수집 URL(`SourceJdbcTestSupport`, 49.4), ICU 가 Unicode 16.0 을 아는지(`ColumnNameUniquenessIntegrationTest` — PostgreSQL 빌드의 ICU 판), `LEXORMA_PERF` (성능 시험 22개 클래스·메서드 — opt-in 유지).

### 49.2 삭제 · 전환 대응표

| 기존 테스트 (차수) | 기존 목적 | 처리 · 사유 | PostgreSQL 대체 | 범위 유지 |
|---|---|---|---|---|
| `H2FilePersistenceTest` (2차 삭제) | H2 파일 DB 재시작 유지 | 삭제 — H2 자체 동작 | 2차 41.4 신규 DB 등록·재시작 유지 (실행), 각 마이그레이션 테스트의 '다시 migrate 해도 0건' | 유지 (H2 고유 부분만 없어짐) |
| `SubtypeStrategyConversionMigrationTest.h2FileDatabaseKeepsCheckAcrossConnectionsAndRestart` (2차 삭제) | H2 의 CHECK 가 연결·재시작 뒤에도 평가되는지 (V3 원인) | 삭제 — H2 결함 회귀 | 같은 클래스 `freshInstall`·`upgrade` 의 '새 연결에서 제약 검사' | 유지 |
| `SourceImportLockErrorTest` H2 50200 (2차) | H2 잠금 시간 초과 → 409 | 삭제 — H2 오류 코드 | 같은 클래스의 55P03·40001 판정 | 유지 |
| `SubtypeStrategyConversionMigrationTest.upgrade` 의 '같은 연결로 V2 기록 넣기' (3차) | H2 에서 V2 CHECK 를 쓰려면 같은 연결이어야 했다 | 일반 연결로 단순화 (H2 사유 소멸) | 같은 단언 (V2 기록 보존·V3 재정의·새 연결 검사) | 유지 |
| `rejected(…)` 의 H2 오류 단언 (90098·'Check constraint invalid') (3차) | H2 평가 오류를 위반으로 착각하지 않기 | H2 전용 단언만 삭제 | SQLSTATE(23514·23502·23505) + 제약 이름 단언 그대로 | 유지 |
| 벤더 매개변수 17클래스 19메서드 (3차) | H2·PostgreSQL 같은 기대값 | `@Test` 로 (H2 기대값 — `UX_COL_01` 대문자, `FN_UPPER_ROOT`, `INFORMATION_SCHEMA.INDEXES`·`REMARKS`, `next value for` — 삭제) | PostgreSQL 기대값 그대로 (`ux_col_01`, `und-x-icu`, `pg_index`, `obj_description`, `nextval`) | 유지 |
| `ColumnNameUnicodeMigrationTest` 의 H2 분기 (3차) | H2 는 BMP 만·보조 평면 제외 | 삭제 — 보조 평면 255 입력은 이제 항상 검사 | 같은 테스트 | **넓어짐** |
| `ColumnNameUniquenessIntegrationTest` H2 `return` (3차) | H2 는 Java 대문자라 ƛ/Ƛ 다름 | 삭제 | ICU 분기만 (assume 은 ICU 판 조건) | 유지 |
| `InheritanceReferenceKeyIntegrationTest` `if (!postgres()) return` (3차) | H2 에서 DDL 실제 실행 생략 | 삭제 | 항상 실행 | 유지 |
| `PhysicalNameNonAsciiScaleTest`·`PhysicalNameF02PocTest` H2 분기 (3차, 성능·PoC opt-in) | H2 5,000건 이상 예전 방식 생략, H2 `TABLE(X …)`·`regexp_like`·EXPLAIN | 삭제 | PostgreSQL 식·전 규모 비교 | 유지 (opt-in) |
| `SubtypePreservationIntegrationTest.fileSizeBoundary` 실행 조건 (3차) | H2 전체 실행의 1GB 힙 부족 → PostgreSQL URL 또는 `LEXORMA_EXCHANGE_SIZE_TEST` 일 때만 | 조건 삭제 — 항상 실행 | 같은 테스트 | 유지 |
| `PhysicalNameJudgedPathIntegrationTest` (3차) | 서비스가 기준값에서 자바 판정 경로로 넘어가는지 (H2 기준값 500 으로) | 둘로 나눔 (49.3) | 운영 기준값 10,000 에서 실제 서비스 경로 | **복원** (H2 제거로 줄던 범위) |
| 6개 별도 스키마 HTTP 테스트의 `CONFIGURED_URL == null` 분기 (2차 유지) | H2 인메모리 대체 | 그대로 둠 — 2차에서 `TestDatabaseGuard.url()` 로 바뀌었고 null 이면 가드가 먼저 컨텍스트를 막는다 (죽은 분기는 아님 : 키가 없을 때 메시지 경로) | — | — |

새 테스트 : `JdbcBatchRewriteIntegrationTest` 3 (51장), `PostgresLockWaitWatchIntegrationTest.staleStatementAndForeignWait` 1 (50장), `PhysicalNameJudgedPathIntegrationTest.serviceSwitchesAtThreshold` 1, `ManagedDatabaseCheckTest` 매개변수 사례 +24 (48.3).

### 49.3 `PhysicalNameJudgedPathIntegrationTest` 대체 검증 (1차 16.3 PG-Q14)

- 1차가 걱정한 것 : H2 는 기준값이 500 이라 '서비스가 SQL 덮어쓰기 → 전체 자바 판정으로 넘어가는 분기' 를 서비스 경로로 지났지만, PostgreSQL 기준값은 10,000 이라 아래쪽만 서비스로 보고 자바 판정은 직접 호출했다.
- 선택 : 1차 안 ①(테스트용 기준값 설정)은 운영 코드 변경이라 3차 범위(37장 지시) 밖, ②(성능 시험 정기 실행)는 opt-in 정책과 어긋남 → **운영 기준값 그대로 9,999·10,000 행을 만들어 서비스를 실제로 부르는 일반 테스트**를 추가했다. 운영 코드·기준값·정책은 바꾸지 않았다.
- `belowThreshold` (예전 PostgreSQL 실행과 같은 범위) : 자바 판정 행 999 → `statusPlan.javaJudged = false`, 서비스(SQL 덮어쓰기)와 직접 부른 자바 판정 경로 각각 상태 4 × 정렬 5 × 쪽 크기 10·100 × 첫·중간·마지막·빈 쪽 + 검색 2, 요약.
- `serviceSwitchesAtThreshold` (새) : 엔터티 206 × 속성 50 (10,300행, 생성은 100엔터티씩 — 요청 상한) → 자바 판정 행 9,999 에서 `javaJudged = false` + 요약 = 예전 방식, 한 행 더해 10,000 에서 `javaJudged = true` + **서비스**의 요약 · 상태 4 × 정렬 5 × 쪽 크기 100 × 첫·마지막·빈 쪽 + 검색 2 가 예전 방식(저장소 SQL + 덮어쓰기 목록)과 행 ID·순서·건수·쪽 수까지 같다. 비 ASCII 행 구성(일치 É↔é · 추천 가능 · 충돌 · 추천 불가(공백·탭) · 끝 탭 일치 F01)과 다른 프로젝트 격리는 예전과 같다.
- 비용 [실행] : 처음 전체 범위(쪽 크기 10·100 × 4쪽)로 10,000행을 비교하니 한 테스트 329초 — 쪽 한 번이 약 1초(예전 방식 SQL 에 바인딩 1만 개). 그래서 경계 쪽은 범위를 줄였다 : `belowThreshold` 17.7초 · `serviceSwitchesAtThreshold` 73.9초. 행 준비는 같은 UPDATE 를 배치로 보낸다 (문장·순서는 예전과 같다).
- 대응 : 예전 H2 기준값 경계(서비스 두 경로) ↔ 지금 PostgreSQL 운영 기준값 경계(서비스 두 경로). 예전 PostgreSQL 아래쪽 전체 비교 ↔ `belowThreshold` 그대로.

### 49.4 직접 JDBC 테스트 · 테스트 DB 안전 검증 · 원본 수집 테스트 DB

- 스프링 없이 연결하는 테스트 24개(마이그레이션 신규 설치·DDL 실제 실행)는 `TestDatabaseGuard.directDataSource()` (또는 `set search_path` 가 이어져야 하는 2곳은 `singleConnectionDataSource()`)로만 연결한다. 연결 전에 `check(testUrl, testUser, testUrl, testUser)` — 키 없음 · `jdbc:postgresql:` 아님 · DB 이름에 `test` 없음이면 예외(실패). 일반 `SPRING_DATASOURCE_*` 는 읽지 않는다 (fallback 없음).
- 스프링 테스트 컨텍스트 : 2차 `TestDatabaseGuard` (BeanFactoryPostProcessor) 그대로 — Flyway·DataSource·데이터 초기화(테스트 계정 콜백) 전에 확인, 일반 설정이 덮어쓰면 거절. 별도 스키마를 쓰는 6개 HTTP 테스트도 같은 URL 의 `currentSchema` 만 다르다 (가드가 쿼리 매개변수만 허용).
- 확인한 우회 경로 [코드] : ① `DriverManager`·`DriverManagerDataSource`·`SingleConnectionDataSource` 를 테스트에서 직접 만드는 곳 = 가드 유틸 2개 + 의도적으로 같은 테스트 DB 에 새 연결을 여는 `PostgresLockWaitWatchIntegrationTest.open`(풀의 URL·계정 그대로 — 가드를 통과한 컨텍스트의 값) + 원본 DB 지원 클래스. ② `SPRING_DATASOURCE_URL` 을 읽는 테스트 0 (성능 시험의 보고 문구도 바꿨다). ③ 원본 수집 테스트는 관리 DB 키를 쓰지 않는다.
- 원본 수집 테스트 DB 보호 (`SourceJdbcTestSupport`) : `LEXORMA_TEST_SOURCE_POSTGRES_URL` 이 없으면 이 19건만 건너뛴다 (명시적 실행 조건 — 관리 DB 종류와 무관). 있으면 `_USER` 필수(기본 계정 `lexorma` 삭제), `jdbc:postgresql:` 필수, **관리 테스트 DB 와 같은 DB 면 실패**, 호스트가 주소 하나로 풀리지 않으면 실패 (예전엔 건너뜀). 테스트는 원본 역할 DB `ai2b_…` 를 만들고 끝나면 지운다 (기존).
- DB 이름 `test` 규칙은 보조 장치일 뿐이다 — 테스트 전용 DB·계정을 쓰라고 README 7장에 적었다 [기존 2차 PG-D19].
- 실측 [실행] : 테스트 DB 이름에 `test` 가 없는 DB(`judged1`)로 돌린 첫 시도에서 컨텍스트가 뜨지 않았다 (가드 동작 — 데이터 생성 없음). 2차 G1~G6 결과는 그대로 [기존 41.7].

### 49.5 순수 단위 테스트

- DB 연결 검증은 Gradle 전역 조건이 아니다 : 가드는 스프링 테스트 컨텍스트 / 직접 연결 유틸에서만 돈다. Testcontainers 는 쓰지 않는다.

## 50. 동시성 감시 보완 — H2 세션 감시 제거 · `s4TwoRenames`

### 50.1 H2 감시 제거 · 기존 수정 유지 [코드]

- `ConcurrencyDiagnostics.lockWaitCount(jdbc, sql)` : H2 분기(플래그)를 없애고 **AES-D17 수정은 그대로** — 같은 연결(`ConnectionCallback`)에서 `pg_stat_clear_snapshot()` 뒤 감시 SQL. 진단 `describe` 의 세션 목록은 PostgreSQL 만 (`pg_blocking_pids` 열 추가).
- 감시 SQL 의 `postgres() ? … : INFORMATION_SCHEMA.SESSIONS …` 삼항식 14곳 → PostgreSQL 문장만 (C2·C3·C4·C4b·C4bPkChange·C4bRefColumn·C4cSupport·P1·ModelVersionConcurrency·SourceImport×2·SourceMapping·SourceCollection·RelationSync). 감시 조건·시간 제한·단언은 그대로.
- `PostgresLockWaitWatchIntegrationTest` 의 결정적 재현 2건(새 백엔드 · 기존 백엔드)은 그대로 — 바뀐 것은 `lockWaitCount` 의 인자 하나와 PostgreSQL 판별 삭제뿐.

### 50.2 `s4TwoRenames` 감시 개선 (`RelationSyncDeadlockIntegrationTest`) [코드]

- **A 의 PID** : 대기점(`hold()`)에서 A 스레드의 트랜잭션에 묶인 `ConnectionHolder` 의 연결로 `pg_backend_pid()` — A 가 잠금을 쥔 바로 그 연결. 없으면 다른 연결로 대신하지 않고 실패.
- **B 의 PID** : 이 테스트 컨텍스트의 `DataSource` 를 감싸(`@TestConfiguration` 의 BeanPostProcessor — 다른 테스트 컨텍스트·운영 코드는 그대로) 감시 대상 스레드가 `getConnection()` 으로 받은 연결마다 그 연결로 `pg_backend_pid()` 를 읽어 모은다. 첫 문장보다 먼저 기록되고, 감시 시작 뒤 B 가 새 연결을 받아도 따라간다. 풀 재사용 : 기록은 물리 백엔드 기준이라 B 가 돌려준 연결을 다른 스레드가 빌려도 A 에 막힌 대기가 아니면 걸리지 않는다.
- **판정** (`ConcurrencyDiagnostics.confirmedLockWaits(jdbc, B 의 PID들, {A 의 PID})`) : 같은 연결에서 스냅숏을 지우고 두 번 읽어, B 의 백엔드가 **두 번 모두** `state = 'active'` + `wait_event_type = 'Lock'` + `pg_blocking_pids` 에 A + **같은 문장**일 때만 '기다린다' 로 보고 그 문장을 돌려준다. 첫 읽기와 둘째 읽기의 문장이 다르면(직전 문장 행) 다음 반복에서 다시 본다 — A 는 대기점에 멈춰 있으므로 실제 대기는 유지된다.
- 같은 판정을 `whileTableHeld`(D11 — 행을 쥔 트랜잭션의 PID = 그 트랜잭션 안에서 읽은 `pg_backend_pid()`)에도 썼다. 그래서 `tb_prj` 문장 단언 4곳(S4 포함 — 1차 14.2 의 :546·:647·:690 이 지금 584·683·724행), 기다림 단언 2곳, '기다리지 않는다'(`isNull`) 단언 8곳, D11 의 `WAITED:` 판정이 모두 B 의 실제 대기만 본다.
- 바꾸지 않은 것 : 시나리오·대기점·시간 제한(10초·30초·60초)·반복 횟수(3회)·단언(`r[0]`·`r[1]` OK, `r[2]` 에 `tb_prj `)·sleep 없음·재시도 없음. 운영 코드의 잠금 순서·범위 무변경. 쓰지 않던 `waitForLockWait()` 삭제.
- '기다리지 않는다' 단언의 뜻이 좁아졌다 : 예전엔 DB 의 **아무 세션**이 대기하면 실패했고, 지금은 **B 가 A 에 막혀** 기다릴 때만 실패한다 (지시 25.2 — 다른 세션 대기를 B 의 대기로 보지 않는다). B 가 A 가 아닌 다른 세션을 기다리는 경우는 이 테스트 시나리오에 없다 [코드].

### 50.3 결정적 재현 — 예전 감시 vs 고친 감시 (`PostgresLockWaitWatchIntegrationTest.staleStatementAndForeignWait`) [실행]

세션 5개(A·B·C·D·감시 W, 풀 밖 새 연결)로 sleep 없이 관찰로 순서를 정한다.

| 단계 | 예전 감시 (RelationSync 옛 문장) | 고친 감시 |
|---|---|---|
| C 가 D 의 권고 잠금을 기다림 (관련 없는 세션), B 는 아직 안 기다림 | **1건 — C 의 대기를 대기로 봄 (결함)** | 0 (B 의 PID·A 의 차단만) |
| W 가 트랜잭션 안에서 한 번 읽어 상태 복사본 고정 → B 가 `TB_PRJ … for no key update` 로 A 를 기다림 | B 의 행 = `state idle in transaction` + `wait Lock` + 문장 **`select count(*) from TB_TBL …`** (직전 문장 — s4 실패 증상과 같음) | B 의 PID → `TB_PRJ … for no key update` |
| 차단자를 A 가 아닌 C 로 주면 | — | 0 (B 의 대기로 인정 안 함) |
| PID·차단자 없이 (모든 세션) | — | B·C 둘 다 — 거르는 이유 |
| A 커밋 → B 진행 | — | 0 (잘못 감지 없음) |

출력 : `[DB-1 3차 감시 재현] 예전 감시(B 대기 전)=1건 · 고친 감시(B 대기 전)=0 · 고정 스냅숏의 B 행 state=idle in transaction wait=Lock · 고친 감시(B 대기)=1 · 다른 차단자 기준=0 · PID 없이(모든 세션)=2 · A 종료 뒤=0`. 자동 커밋 한 문장 안의 '복사 → 실시간 읽기' 창(실제 s4 실패의 유력 경로)은 그대로 재현할 수 없어 트랜잭션 고정 스냅숏으로 넓혀 재현했다 — 같은 성질(상태·문장 복사본 + 실시간 대기)이다 [1차 32.4 와 같은 메커니즘].

### 50.4 같은 패턴 전수 점검 (지시 27장) [코드]

| 감시 | 형태 | 결함 | 처리 |
|---|---|---|---|
| RelationSync `pausedThen`·`whileTableHeld` | 대기 세션의 **문장**을 단언, PID 없음 | 있음 (s4) | 50.2 로 고침 |
| `SourceCollectionIntegrationTest.awaitLockWait` | 대기 세션의 **문장**(`TB_SRC_CNTN`·`TB_PRJ`·`TB_SRC_CLCT_EXCN`)을 단언, PID 없음 | 같은 '직전 문장' 결함 가능 | `confirmedLockWaits(jdbc, ∅, ∅)` — 같은 세션 두 번 `active`+같은 문장. 대기 세션의 PID 는 서비스 내부 연결이라 B 의 PID 수집 장치가 없어 DB 전체 범위는 그대로 (다른 세션 대기면 문장 단언이 실패 쪽으로 드러난다) |
| `ConcurrentEdit*`·`ModelVersionConcurrency`·`SourceImport`·`SourceMapping` 의 `lockWaitCount`·`count(*)` | 대기 **수**만 보고 진행 순서를 정함 | 문장 결함 없음 (`wait_event` 는 실시간 값). DB 전체 수라 다른 세션 대기면 일찍 A 를 놓을 수 있다 — 결과는 기다림/409 중 하나로 단언이 이미 허용하는 경우가 대부분, 실제 실패 기록 없음 | 바꾸지 않음 (지시 : 동일 결함 확인된 것만) — 기록 |
| `PostgresLockWaitWatchIntegrationTest` | 재현 시험 자체 | — | 새 재현 1건 추가 |

### 50.5 반복 결과 · 판정 → 53.3

## 51. `reWriteBatchedInserts` 실제 적용 (PG-D43)

- 설정 [코드] : `application.yml` `spring.datasource.hikari.data-source-properties.reWriteBatchedInserts: true` (URL 에 없음). Hibernate : `jdbc.batch_size: 500`·`order_inserts`·`order_updates`, ID 는 모두 `SEQUENCE`(`allocationSize = 50`) — IDENTITY 가 아니라 INSERT 배치가 가능하다. 아무것도 바꾸지 않았다.
- 회귀 테스트 `JdbcBatchRewriteIntegrationTest` (일반 시험, 3건) [실행] :
  1. **전달** : 애플리케이션 풀 연결 → `BaseConnection.getQueryExecutor().isReWriteBatchedInsertsEnabled() = true`, 연결 URL 에 옵션 없음 (드라이버는 runtimeOnly 라 리플렉션).
  2. **서버 관측** : 롤백하는 트랜잭션의 임시 표 + `FOR EACH STATEMENT` 트리거로 서버가 받은 INSERT 문장을 센다. 300행 JDBC 배치 — 켜짐(애플리케이션 풀) **4문장**, 결과 값 `SUCCESS_NO_INFO(-2)` / 끔(같은 서버, 테스트 키 + `reWriteBatchedInserts=false`) **300문장**, 결과 값 1. 행 수·값 합 같음.
  3. **업무 경로** : 논리 → 물리 생성(엔터티 3 · 속성 120)이 넣는 `TB_COL` 120행 → **INSERT 4문장** (Hibernate 배치 + 재작성). 트리거·함수·임시 표·생성 결과는 롤백으로 남지 않음 (트리거 0 확인).
- 서버 로그로 본 SQL 형태 [실행 — 임시 컨테이너에서만 `log_statement=all` 잠깐 켜고 되돌림] : 켜짐 = `insert into batch_rows (id, name) values ($1, $2),($3, $4),…` 256·32·8·4행짜리 4문장 / 끔 = 1행짜리 300문장. `TB_COL` = `insert into TB_COL(…) values (…),(…)…` 64·32·16·8행 4문장. 드라이버는 128 이하 2의 거듭제곱 크기로 묶는다 (pgjdbc 동작).
- 범위 : 재작성은 **JDBC 배치 INSERT 에만** 효과가 있다. 단건 저장·`@DynamicUpdate` UPDATE(배치 안 됨 — CLAUDE.md AI-5)·네이티브 단건 INSERT 에는 효과가 없다. 성능 수치 비교는 하지 않았다 (설정·SQL 형태 검증만).

## 52. GitHub Actions PostgreSQL 전환 (`.github/workflows/dev-test.yml`)

### 52.1 기존 구조 [코드]

Workflow 1개 (`Test (dev)`) : `push: branches: [dev]`, `concurrency: dev-test` (진행 중 취소), `ubuntu-latest`, timeout 30분, `permissions: contents: read`, Java 21 temurin · Node 22 (npm 캐시) · `gradle/actions/setup-gradle@v4`, `./gradlew build` 한 번, 실패 시 테스트 리포트 업로드. 릴리스·배포·이미지 게시 Workflow 없음. 테스트 힙 2g·Gradle 병렬 설정 없음(테스트 포크 1).

### 52.2 바꾼 것

- `test` Job 에 `services.postgres` (`postgres:18.6`, `POSTGRES_DB/USER = lexorma_test`, CI 전용 일회성 비밀번호, `5432:5432`, `pg_isready` healthcheck 5s/5s/10회) — 지시 30장 예시 그대로.
- Job `env` : 테스트 키 3개 (`jdbc:postgresql://127.0.0.1:5432/lexorma_test`) + 원본 수집 키 3개 (`…/postgres` — 같은 서버의 다른 DB, `127.0.0.1` 숫자 주소). 원본 테스트는 테스트마다 원본 역할 DB 를 만들고 지우므로 별도 컨테이너가 필요 없다.
- `./gradlew build` 그대로 한 번 (npm ci · `tsc -b` · vite · 컴파일 · 전체 테스트 · bootJar). 성능 시험은 `LEXORMA_PERF` 가 없어 건너뜀 (opt-in 유지). `continue-on-error`·재시도·제외 없음.
- 실패 시 : `docker logs <service id>` → `postgres.log`, 업로드에 `build/test-results/test/`(XML — 실패 원인 텍스트) 추가.
- timeout 30 → **45분** : 로컬 CI 동등 실행이 8분 15초~8분 36초 (53.1) — GitHub 기본 러너(4 vCPU)에서의 시간은 미측정이라 여유를 뒀다 (사용자 확인 64장).
- 새 Job `docker-image` (독립, push 없음, 레지스트리 인증정보 없음) : `docker build -f Dockerfile -t lexorma:ci .` (Dockerfile 이 React 빌드·bootJar 를 하고 테스트는 다시 돌지 않는다) → 이미지의 `/app/lexorma.jar` 를 꺼내 `BOOT-INF/classes/static/index.html` 있음 · `BOOT-INF/lib/postgresql-` 있음 · `BOOT-INF/lib/h2` 없음 확인. `Dockerfile.dev` 는 빌드 단계가 같아 따로 빌드하지 않는다.
- 주석 : '(H2 인메모리, 외부 DB 불필요)' 삭제, 서비스·키 설명.

### 52.3 Workflow 검증 [실행]

- YAML 구문 : Ruby `YAML.load_file` 로 읽음 — Job 2개(`test`·`docker-image`), 서비스 옵션·환경변수 6개·단계 확인.
- `actionlint` (GitHub Actions 전용 검사) : 로컬에 없고, 검사 도구 이미지를 내려받는 명령은 이 세션의 권한 정책이 막아 **실행하지 못했다** — 미검증.
- 로컬 CI 동등 실행 → 53.1 (같은 이미지·DB 이름·계정·CI 비밀번호·포트 5432·환경변수·`./gradlew build`).

### 52.4 원격 CI

**실행하지 않았다.** PG-D42 에 따라 FINAL 뒤 사용자가 통합 커밋·푸시할 때 처음 돈다. GitHub-hosted runner 에서의 성공·시간·메모리는 그때 확인할 사항이다 (FINAL 인계 57장).

## 53. 3차 실행 검증

### 53.0 환경 [실행]

- **격리 사본** : 작업 트리를 `rsync` (제외 `/.git/`·`**/build/`·`**/node_modules/`·`**/.env`·`/.gradle/`·`/.idea/`·`/lexorma-web/dist/`·`/lexorma/volume/`·`/docker/volume/`, 먼저 `--dry-run --stats` — 1,819개 27MB) → scratchpad `db1/r3/copy`. `.env` 0개. 다른 세션의 `AD` 7개는 작업 트리에 없어 들어가지 않았다 (V5~V7 없음 — 사본의 `db/migration/postgresql` = V0~V3). 웹 번들은 사본 안에서 새로 빌드. 수정 뒤 다시 동기화하고 `diff -rq` 로 작업 트리와 같음을 확인.
- 작업 트리에서는 `-PfrontendPrebuilt=true` 로 바뀐 테스트만 골라 돌렸다 (빌드 산출물만 생김, 저장소 `lexorma-web/dist` 는 건드리지 않음).
- 임시 PostgreSQL 18.6 (모두 tmpfs, `127.0.0.1` 만, 이름 `lexorma-db1r3-*`) : `-ci`(CI 와 같은 DB·계정·비밀번호·포트 5432), `-pg`(골라 돌리기·동시성 반복, 실행마다 새 빈 DB), `-scn`(기동·Compose — scram 계정 `lexorma_app`, trust 계정 `lexorma_trust`, SSL 켬, 네트워크 별칭 `password-management-db`). 개발 DB(15439)·개발 볼륨·기존 볼륨 12개·기존 이미지는 쓰지 않았다.

### 53.1 로컬 CI 동등 전체 실행 (`./gradlew build`, 격리 사본, CI 와 같은 환경변수 6개) [실행]

| 회 | 결과 | 시간 | 비고 |
|---|---|---|---|
| 1 | **BUILD FAILED** — `ExternalConnectionSecurityIntegrationTest.connectionAuditAndUnmatched` 1건 실패 (lexorma-ai 25 · lexorma-app 1,165 까지 돌고 멈춤) | 8분 36초 | 아래 |
| 2 (수정 뒤, 새 컨테이너) | **BUILD SUCCESSFUL** — 1,647건 · 실행 1,620 · **실패 0** · 건너뜀 27 (284 클래스) | 8분 15초 | npm ci · `tsc -b` · vite · 컴파일 · 전체 테스트 · bootJar |

- 1회 실패 원인 [실행 + 코드] : 원본 수집 테스트(`SourceJdbcTestSupport.allowSource`)가 원본 DB 주소의 JDBC 허용 규칙 `127.0.0.1/32:5432` 를 만든다. CI 에서는 원본 DB 가 **5432** 라, 같은 DB 를 쓰는 이 보안 테스트가 가짜 호스트(10.60.0.5)에 고정 포트 5432 를 쓰자 '포트가 같은 규칙이 있음 → 주소 대역 밖'(`ADDRESS_NOT_ALLOWED`, 응답 "허용 대역 밖의 주소입니다")이 되어 기대한 `NO_RULE` 이 아니었다. 운영 결함이 아니라 테스트의 환경 의존 — 같은 클래스의 다른 테스트는 모두 무작위 포트(15000~18999)를 쓰는데 이 테스트만 5432 를 박아 두었다. **포트를 같은 방식(19000~19999 무작위)으로 바꿨다** (단언은 그대로). 2차까지는 원본 DB 가 다른 포트여서 드러나지 않았다. 같은 형태(`"POSTGRESQL", …, 5432` + 규칙 판정 단언)는 다른 클래스에 없었다 (2회 실패 0).
- 건너뜀 27 = 모두 `LEXORMA_PERF` 성능 시험 (22개 클래스 · 메서드) — 관리 DB 종류로 건너뛴 테스트 0.
- 원본 수집 테스트 **19건 실제 실행·통과** : `AiDictionarySourceRefresh` 5 · `SourceJdbcCollection` 8 (+ 성능 1 건너뜀) · `SourceJdbcCollectionLimit` 2 · `SourceJdbcSecurity` 1 · `SourceRenameReimportJdbc` 1 · `SourceViewDefinition` 2.
- 테스트 JVM 힙 [실행 — 2회 실행에 `-Xlog:gc` 를 붙여 측정] : app 테스트 JVM GC 직전 최대 **1,614MB**, GC 뒤 최대 **1,314MB**, 커밋 2,048MB, Full GC 0. 그래서 `maxHeapSize = '2g'` 는 **유지** (줄이면 여유가 없다 — 2차 '측정 뒤 결정' 의 결론). 다른 모듈 JVM 은 300MB 미만.

### 53.2 2차 대비 테스트 수 [실행]

| | 2차 | 3차 |
|---|---:|---:|
| 전체 | 1,618 | **1,647** |
| 실행 | 1,591 | **1,620** |
| 실패 | 0 | 0 |
| 건너뜀 (성능 opt-in) | 27 | 27 |
| 원본 수집 실행 | 19 | 19 |

증가 29 = `ManagedDatabaseCheckTest` +24 (매개변수 사례) · `JdbcBatchRewriteIntegrationTest` +3 · `PostgresLockWaitWatchIntegrationTest` +1 · `PhysicalNameJudgedPathIntegrationTest` +1. 3차에서 지운 테스트 메서드 0 (지운 것은 H2 분기·H2 단언·벤더 매개변수 — 매개변수 변형 19 → 0 은 실행 수가 그대로다. H2 전용 테스트 삭제는 2차에서 끝났다). 업무 검증 범위 : 줄어든 곳 없음, 넓어진 곳 2 (49.2 — 보조 평면 입력, 운영 기준값 경계).

### 53.3 동시성 반복 · `s4TwoRenames` [실행]

- **동시성 묶음 3회** (2차와 같은 패턴 `*Concurren*`·`*Deadlock*`·`*LockWait*`·`*SourceMapping*`·`*SourceImport*`·`*SourceCollection*`·`*SourceRename*`·`*Approval*`·`*ModelMapping*`, 원본 수집 포함, 실행마다 새 빈 DB) : **37 클래스 · 272건 · 실행 271 · 실패 0 · 건너뜀 1(성능)** × 3, 각 93~94초. 2차(37 클래스 271건 실행)와 같은 범위 — 엔터티·테이블 승인↔수정, 프로젝트 잠금, 행 NOWAIT, KEY SHARE, REF_COLUMN 동기화, PK 변경, FK 등록, 표준 항목 삭제, 매핑 변경, `MODEL_CONFLICT`·`LOCKED` 사유, `entityApprovalVsEdit`, `s4TwoRenames` 포함.
- **`s4TwoRenames` 독립 10회** (그 메서드만, 실행마다 새 빈 DB) : 10/10 통과, 각 15초.
- 라운드 기록 : 묶음 3회 + 독립 10회 = 13회 × 3라운드 = **39라운드 모두 `[OK, OK, select p1_0.prj_sn … from tb_prj … for no key update]`** — B 의 백엔드가 A 에 막혀 프로젝트 행에서 기다린 것을 PID·차단 관계·두 번 확인으로 본 결과.
- `ConcurrentEditC2IntegrationTest.entityApprovalVsEdit` : 묶음 3회 모두 통과.
- 판정 : **감시 보완 완료** (A·B PID 식별, 실제 차단 관계, 같은 문장 두 번 확인, 결정적 재현으로 예전 감시의 두 취약점 — 다른 세션 대기 · 직전 문장 — 을 보이고 고친 감시가 걸러냄을 확인). **기존 간헐 실패의 원인은 '확정' 이 아니다** : 실제 실패 순간의 PID 기록이 없어 유력 원인(14.4 — 감시 결함)이 더 굳어졌을 뿐이다. 이번 반복에서 재발은 없었고, 감시가 바뀐 뒤라 같은 증상(직전 문장 기록)은 이제 나올 수 없다. 운영 결함은 발견되지 않았다 (39라운드 모두 기대한 잠금 순서).

### 53.4 운영 설정 기동 회귀 C01~C15 (격리 사본 `lexorma.jar`, `--server.port=0`) [실행]

| ID | 조건 | 결과 | 주요 문구 |
|---|---|---|---|
| C01 | `local` + 정상 DB | 기동 (Flyway 적용) | — |
| C02 | `local` + DB 설정 없음 | 종료 1 | "관리 DB(PostgreSQL) 연결 설정이 없습니다 : spring.datasource.url 이 비어 있습니다." |
| C03 | `dev` + 정상 | 기동 | — |
| C04 | `dev` + 없음 | 종료 1 | C02 와 같음 |
| C05 | 프로필 없음 + 정상 | 기동 ("No active profile set") | — |
| C06 | 프로필 없음 + 없음 | 종료 1 | C02 와 같음 |
| C07 | `./gradlew bootRun` + 정상 (`SPRING_DATASOURCE_*` 환경변수) | 기동, 프로필 `local` | — |
| C08 | `bootRun` + 없음 | **기동 중단** (Started 없음, C02 와 같은 문구) — 단 Gradle 은 `BUILD SUCCESSFUL` (DevTools `restartedMain` 이 실패를 종료 코드로 넘기지 않음 — 기존 동작, 63장) | |
| C09 | 외부 PostgreSQL Compose | 기동 — 53.5 B1 | |
| C10 | 기본 Compose `POSTGRES_PASSWORD=` | `docker compose config` 종료 1 "Set POSTGRES_PASSWORD in .env (see .env.example)" — 컨테이너 안 만듦 | |
| C11 | URL 인증정보 : `?password=…` · `user:pw@host` · `options=-c password=…` / 형식 오류 `ssl%mode` | 4개 모두 종료 1, **Hikari·Flyway·Hibernate 로그 0줄** (초기화 전) | 인증정보 3 = "…인증정보(…)를 넣을 수 없습니다.", 형식 = "…형식이 올바르지 않습니다 (…)." |
| C12 | 호스트명 `password-management-db` (Docker 네트워크 별칭) | 기동 — 53.5 C | |
| C13 | DB 이름 `password_service` / 값 `ApplicationName=password-service`·`options=-c application_name=password_app` / 다중 호스트(죽은 호스트 → 살아 있는 호스트) | 3개 모두 기동 | — |
| C14 | Docker Secrets (DB 비밀번호 파일 + 암호화 키 파일) | 기동 — 53.5 C | |
| C15 | `jdbc:h2:mem:lexorma` | 종료 1 | "지원하지 않는 관리 DB 입니다 (jdbc:h2). Lexorma 관리 DB 는 PostgreSQL 만 지원합니다." |

추가 실패 안내 (Analyzer 퇴행 확인) : 연결 불가 08001 · 인증 실패 28P01 · DB 없음 3D000 · 사용자명 없음 — 2차와 같은 문구 (비밀번호·URL 없음). 모든 시나리오 로그에서 가짜 비밀번호 2종 **0건**, H2 클래스 0건.

### 53.5 비밀번호 없는 인증 · Docker [실행]

**비밀번호 없는 인증 (PG-D39, 임시 컨테이너에서 `lexorma_trust` 만 trust — 운영 권장 설정이 아니다)** :

| | 비밀번호 설정 | 결과 |
|---|---|---|
| P1 | 없음 (JAR) | 기동 |
| P2 | 빈 값 | 기동 |
| P3 | 없음, scram 계정 | 종료 1 — "서버가 연결을 거부함 (비밀번호 미지정 · 인증 방식 · pg_hba.conf) (SQLSTATE 08004)" |
| P4 | 빈 값, scram 계정 | P3 과 같음 |

인증서(클라이언트 인증서) 인증은 **실행하지 않았다** (미검증 62장). Lexorma 는 비밀번호를 요구하지 않을 뿐 인증을 우회하지 않는다 — 서버가 요구하면 첫 연결에서 멈춘다.

**A. 기본 Compose** (`docker-compose.yml` 수정 없이, 이미지 `lexorma/lexorma:db1r3-verify`, `.env` 에 가짜 비밀번호·임시 데이터 디렉터리·다른 포트·암호화 키) : C10 거절 → `up -d` → PostgreSQL healthy → 앱 기동 → 최초 관리자 설정·로그인·프로젝트·표준 사전·논리·물리·ERD 배치·매핑·원본 연결·AI·MCP 설정 등록(실패 0) → `down` → `up -d` (데이터 디렉터리 유지) → Flyway "Successfully validated 4 migrations"(다시 적용 안 함) → 로그인·등록 데이터 조회 전부 일치(실패 0). 원본 연결 비밀번호를 저장하면 `cntn_pswd_cn` 이 `v1:v1test:…` 암호문 (암호화 키 Docker Secret), 평문 없음. 앱 로그에 가짜 비밀번호·암호화 키 원문 0건.

**B. 외부 PostgreSQL Compose** (`docker-compose.external-db.yml`, `.env` 표준 키, `host.docker.internal:25461`, `sslmode=require`) :

| | `.env` | 컨테이너 환경변수 (compose config) | 결과 |
|---|---|---|---|
| B1 | URL·사용자·비밀번호 (scram) | 비밀번호 전달 | 기동, SSL 연결, 최초 설정·로그인·등록 실패 0 |
| B2 | 비밀번호 줄 없음 (trust 계정) | `null` (넣지 않음) | 기동, SSL |
| B3 | `SPRING_DATASOURCE_PASSWORD=` (trust) | `""` | 기동, SSL |
| B4 | 비밀번호 줄 없음 (scram) | `null` | 종료 1 — 08004 안내 (재시작 정책으로 되풀이) |
| B5 | 틀린 비밀번호 | 전달 | 종료 1 — 28P01 안내 |
| B6 | URL 없음 | — | `config` 거절 "Set SPRING_DATASOURCE_URL in .env" |
| B7 | 사용자명 없음 | — | `config` 거절 "Set SPRING_DATASOURCE_USERNAME in .env" |

**C. `docker run`** : C12 호스트명 `password-management-db` 기동 · C14 `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/` + `spring.datasource.password` 파일 + `lexorma.secret.keys` 파일로 기동(비밀번호 환경변수 없음) · 설정 없음 / 사용자명 없음 / 틀린 비밀번호 / URL 인증정보 모두 종료 1·명확한 문구. 로그에 가짜 비밀번호·키 0건.

**이미지 빌드 (CI `docker-image` Job 과 같은 단계)** : `docker build -f Dockerfile` 성공 → 이미지의 `/app/lexorma.jar` : `static/index.html` 1 · `BOOT-INF/lib/postgresql-` 1 · `BOOT-INF/lib/h2` 0 · `application-h2*` 0. 격리 사본 bootJar(110MB) 도 H2 0·SPA 있음.

### 53.6 단위 테스트 (DB 없이) · 프런트엔드 · AI·MCP

- DB 설정 없이 (테스트 키·`SPRING_DATASOURCE_*` 모두 없음, 격리 사본) : 비-app 7개 모듈 **482건 실패 0** · app 의 스프링 없는 테스트(`ManagedDatabaseCheckTest` 50 · `TestDatabaseGuardTest` 4 · `ModuleBoundaryTest` · `MessagesCoverageTest`) **59건 실패 0**.
- 같은 조건에서 DB 가 필요한 테스트는 **건너뛰지 않고 실패** : 직접 연결 `V0SeedMigrationTest` = "테스트 DB 가 지정되지 않았습니다 : LEXORMA_TEST_POSTGRES_URL · LEXORMA_TEST_POSTGRES_USER …", 스프링 `SmokeTest`·`ExampleTermCodeDefinitionTest` = 컨텍스트 로드 실패(원인 `TestDatabaseGuard`).
- 프런트엔드 : 업무 코드 변경 없음. `./gradlew build` 안의 npm ci · `tsc -b` · vite build 성공 (53.1), bootJar·이미지 JAR 에 `static/index.html` (53.5), 기동한 JAR·컨테이너가 SPA·API 를 제공 (E2E 의 `/api/**` 호출). 브라우저 화면 검증은 FINAL.
- AI·MCP : 업무 코드 변경 없음. 전체 실행의 AI·MCP 관련 26 클래스 152건 실패 0 (`MetadataTool`·`LogicalMetadataService`·`EmbeddedMcp*`·`Ai*` 등, 가짜 Ollama). 실제 Ollama·외부 MCP 클라이언트는 FINAL.

## 54. H2 잔존 항목 최종 조사 [실행 — 저장소 grep (`docs/` 제외) · 운영 JAR · 이미지 JAR]

| 분류 | 상태 | 근거 |
|---|---|---|
| 운영 H2 JDBC · Console · 운영 프로필 · 운영 URL · Flyway 리소스 | 없음 | 2차 삭제, bootJar·이미지 JAR 에 `h2`·`application-h2*` 0 |
| 테스트 H2 JDBC · 테스트 프로필 · 테스트 URL | 없음 | 남은 `jdbc:h2` 2곳은 H2 URL **거절** 정책 테스트 |
| 운영 SQL · 잠금 분기 | 없음 | 2차 36장 |
| 테스트 SQL · 잠금 분기 · H2 세션 감시 · H2 전용 테스트 · DB 판별 | 없음 | 49.1 |
| H2 기반 CI · 배포 · 호환 설정 · 이전 기능 · 이전 가이드 | 없음 | 52장, 2차 40장. CI 는 이미지 JAR 에 H2 가 **없는지** 검사한다 |
| H2 파일 ignore 패턴 | **유지** (PG-D44) | `.gitignore` `*.mv.db`·`*.trace.db`·`*.lock.db`, 주석만 '실수 커밋 방지' |
| 과거 기록 | 유지 | `docs/completed/**`·`completed-work.md`·이 문서 1~45장, 적용된 마이그레이션 V0·V3 의 주석(수정 금지), 테스트의 날짜 있는 과거 기록 주석 7줄 |
| 기타 | 해당 없음 | 프런트엔드의 `h2` = HTML 제목 태그 |

## 55. 변경 범위 [실행 — 읽기 전용 Git·파일 시각]

- 3차에서 바뀐 파일 **138** : 운영·설정·배포 11 (`ManagedDatabaseCheck.java`, `application-local.yml`, `application-dev.yml`, `docker-compose.external-db.yml`, `.env.example`, `.github/workflows/dev-test.yml`, `build.gradle`(주석), `.gitignore`(주석), `README.md`, `CLAUDE.md`(Git 제외 파일), 문서) + 테스트 127 (신규 1 `JdbcBatchRewriteIntegrationTest.java`, 나머지 수정 — `ConcurrencyDiagnostics`·`TestDatabaseGuard`·`RelationSyncDeadlock`·`PostgresLockWaitWatch`·`PhysicalNameJudgedPath`·`ExternalConnectionSecurity`·`SourceJdbcTestSupport`·`SourceCollection`·`ManagedDatabaseCheckTest`·벤더 매개변수 17 · 직접 JDBC 24 · 감시 14 · 주석 정리). 문서 : 이 문서(46~58장)·`pending-work.md`·`completed/modeling-concurrency-m8.md`(24장).
- 3차에서 지운 파일 0, 새 마이그레이션 0, 운영 의존성 변경 0 (`build.gradle`·`libs.versions.toml` 의존성 무변경), 프런트엔드 변경 0.
- DB-1 1~3차 전체 (HEAD 대비) : 183 파일, +2,622 / −11,714 (대부분 H2 마이그레이션 삭제) + 새 파일 1 (`??`). `git status` : `M` 164 · `D` 11 · `A` 4 · `AM` 5 · `AD` 7(다른 세션) · `??` 1.
- Flyway [실행] : `db/migration/postgresql` 은 V0~V3 그대로 (HEAD 대비 변경 없음 — 다른 세션의 V5~V7 은 인덱스에만 `AD`), repair·clean·baseline 실행 없음.

## 56. 미검증 · 남은 위험 · 사용자 확인 필요

**미검증** :
- 원격 GitHub Actions (PG-D42 — 커밋 전). `actionlint` 검사 (도구 없음·이미지 내려받기 거부). GitHub 러너의 시간·메모리.
- PostgreSQL 15·16·17 (FINAL — 15 최신 패치). 클라이언트 인증서 인증 (trust 로 '비밀번호 없이' 만 확인). 실제 Ollama·외부 MCP 클라이언트·브라우저 화면 (FINAL). 리눅스 호스트에서의 Compose (macOS + OrbStack 만). 배치 재작성의 성능 수치 (형태·문장 수만).
- `s4TwoRenames` 예전 간헐 실패의 실제 순간 (PID 기록 없음 — 원인 '유력', 확정 아님).

**남은 위험** :
1. CI 첫 실행 : 로컬 동등 실행은 성공했지만 GitHub 러너(4 vCPU·16GB)에서 동시성 시험의 시간 여유·전체 시간이 다를 수 있다 (timeout 45분).
2. URL 검사는 **표현 기반** : `ApplicationName=<비밀값>` 같은 임의 값은 막지 못한다 (README 경고). 반대로 호스트·매개변수 이름 문법을 pgJDBC 보다 엄격히 봐 드라이버가 받는 특이한 표기가 형식 오류가 될 수 있다.
3. `./gradlew bootRun` 에서 앱이 DB 설정 없이 멈춰도 Gradle 은 `BUILD SUCCESSFUL` (DevTools `restartedMain` — 기존 동작, 문구는 정상 출력). 스크립트가 종료 코드에 기대면 놓친다.
4. dev 이미지(`Dockerfile.dev`)로 배포하는 개발 서버는 이제 `SPRING_DATASOURCE_*` 를 줘야 뜬다 — 그 서버 설정은 이 저장소에 없어 확인하지 못했다.
5. 대기 '수' 만 보는 감시(`ConcurrentEdit*` 등)는 DB 전체를 센다 (50.4 — 실제 실패 기록 없음, 바꾸지 않음).

**사용자 확인 필요 (3차에서 정한 세부)** :
1. CI `test` Job timeout 30 → 45분.
2. `PhysicalNameJudgedPathIntegrationTest` 를 운영 기준값(10,000) 경계까지 일반 테스트로 — 전체 실행 +약 74초. 경계 쪽 비교 범위는 줄였다 (쪽 크기 100 × 첫·마지막·빈 쪽).
3. `ExternalConnectionSecurityIntegrationTest` 의 고정 포트 5432 → 무작위 19000~19999 (CI 원본 DB 포트와 충돌, 단언 무변경).
4. 원본 수집 테스트 : URL 을 주면 `_USER` 필수, 관리 테스트 DB 와 같은 DB 면 실패, 주소가 하나로 안 풀리면 (예전 건너뜀 →) 실패.
5. `docker-image` Job 은 `Dockerfile` 만 빌드한다 (`Dockerfile.dev` 는 같은 단계라 생략).
6. URL 검사에서 매개변수 이름이 디코딩 뒤 평범한 이름(`[A-Za-z][A-Za-z0-9_.-]*`)이 아니면 형식 오류로 거절 (이중 인코딩 대응 — 반복 디코딩 대신).
7. 테스트 힙 2g 유지 (측정 최대 1.6GB).

## 57. FINAL 인계

- **PostgreSQL 버전** : 18.6 새 빈 DB 전체 `./gradlew build` 2회 · 15 최신 패치 새 빈 DB 전체 1회 — 각 Flyway 초기화(V0~V3), SQL 호환(ICU `und-x-icu` 포함), 동시성 묶음, 원본 수집 19건(원본도 같은 버전), Spring Session JDBC, 신규 등록·재시작 유지. 15 에서 실패하면 지원 최소 버전 결정.
- **원격 CI** : 사용자가 통합 커밋·푸시한 뒤 첫 `Test (dev)` 실행의 두 Job 결과·시간·`postgres.log`. 실패하면 로컬 동등 실행(이 문서 53.1)과 비교.
- **배포** : 일반 단일 JAR · 기본 Compose · 외부 PostgreSQL Compose(비밀번호 있음·없음) · Docker Secrets · 필수 설정 누락 · URL 인증정보 거절 · 데이터 영속성 · 운영 JAR/이미지에 H2 없음 — 53.4~53.5 시나리오 재사용 (scratchpad `db1/r3/scn.sh`·`compose.sh`).
- **기능** : 최초 관리자 · 로그인 · 프로젝트 · 표준 사전 · 논리 · 물리 · ERD · 매핑 · 승인 · 원본 수집 · AI 설정 · 실제 로컬 Ollama · MCP 비활성/활성 · Tool 13개 · 주요 브라우저 화면.
- **문서** : README · 개발·테스트 가이드 · 배포 설정 · 지원 버전 · H2 잔존 · DB-1 완료 문서(이 문서를 `completed/` 로) · pending-work · completed-work · CLAUDE.md.
- **통합 커밋 준비 (사용자가 직접)** : DB-1 전체 변경·신규·삭제 파일 목록, 제외할 다른 세션 항목 (`AD` 7개 — V5~V7 ×6, `logical-subtype.md`), 기존 스테이징 (`A`/`AM` 9개 — 1·2차에서 생긴 상태, 이번에 바꾸지 않음), 커밋 메시지 제안, 원격 CI 확인 항목.

## 58. 3차 완료 기준 판정

| 항목 | 판정 | 근거 |
|---|---|---|
| PG-D01~D44 · local/dev 고정 DB 제거 · 표준 키 통일 · 외부 Compose · 외부 인증 서버 정책 · bootRun local 유지 | 충족 | 46·47장, 53.4·53.5 |
| URL 검사 구성 요소 기반 · 정상 URL 오탐 제거 · 인증정보 로그 0 | 충족 | 48장, 53.4 (C11·C13) |
| H2 테스트 의존·프로필·전용 테스트·분기 없음 · 직접 JDBC 고정 계정 없음 · 테스트 DB 안전 검증 전 경로 · 순수 단위 테스트 DB 없이 | 충족 | 49장, 53.6 |
| PostgreSQL 전용 조건 정리 · 물리명 추천 서비스 경로 대체 | 충족 | 49.1·49.3 |
| H2 세션 감시 제거 · 기존 감시 수정 유지 · `s4TwoRenames` PID·차단 관계 · 독립 10회 · 묶음 3회 | 충족 | 50장, 53.3 |
| `reWriteBatchedInserts` 실제 적용 | 충족 | 51장 |
| CI PostgreSQL 서비스 · 원본 수집 · Docker 빌드 Job 구성 · 로컬 CI 동등 검증 성공 | 충족 (원격 실행은 기준 제외 — PG-D42) | 52장, 53.1 (2회차) |
| 원본 수집 19건 실제 통과 · 전체 회귀 실패 0 · 프런트엔드 빌드 · 단일 JAR · 기본 Compose · Secrets | 충족 | 53.1·53.5 |
| 기존 업무 기능 · AI·MCP 계약 · 신규 운영 의존성 0 · 신규 마이그레이션 0 · H2 지원 경로 0 · ignore 패턴 유지 | 충족 | 53.6·54·55장 |
| FINAL 인계 · Git 읽기 전용 · 중간 커밋 없음 · 다른 세션 보호 | 충족 | 57장, 46장 머리 |

**3차 완료 기준은 모두 충족했다 (사용자 검토 대기).** 원격 CI 와 PostgreSQL 15 는 FINAL 에서 확인한다. DB-1 전체는 완료가 아니다.

---

# DB-1 FINAL — 최종 통합 검증 및 완료 처리 (2026-11-25)

FINAL 은 새 기능 없이 1~3차 결과를 최종 코드로 다시 검증하고 완료 처리한다. 한 세션 안에서 사용량 한도로 한 번 중단됐다가 재개했다 — 중단 전 검증(61~68장 대부분)의 증거는 재개 때 모두 남아 있음을 확인했고 (60.3), 재개 뒤에는 남은 항목만 수행했다 (각 절에 **[재개]** 표시). 증거 위치 : 세션 scratchpad `final/` (77장).

## 59. 확정 정책 PG-D45~D51 (3차 56장 사용자 확인 요청 a~g 반영)

| ID | 확정 내용 | 반영 |
|---|---|---|
| PG-D45 | CI `test` Job timeout 45분 유지 | `.github/workflows/dev-test.yml` 그대로 |
| PG-D46 | `PhysicalNameJudgedPathIntegrationTest` 의 운영 기준값(10,000) 경계 테스트 유지 | 3회 전체 실행에서 실행·통과 |
| PG-D47 | `ExternalConnectionSecurityIntegrationTest` 의 무작위 포트 유지 | 그대로 |
| PG-D48 | 원본 수집 테스트의 강화된 연결·DB 분리 검증 유지 | 그대로, 19건 매 실행 |
| PG-D49 | 배포용 `Dockerfile` 이미지 빌드 검증 Job 유지 (`Dockerfile.dev` 는 넣지 않음) | 그대로 |
| PG-D50 | JDBC URL 매개변수 이름의 엄격한 문법 검증 유지 | 그대로 (F10) |
| PG-D51 | 테스트 힙 2GB 유지 | 그대로 (70장) |

PG-D01~D44 는 바꾸지 않았다. FINAL 중 운영·테스트 코드 변경 : **없음** (문서 경로 주석 3곳만 — 74.3).

## 60. 검증 대상 · 격리 사본

### 60.1 대상 [실행 — 읽기 전용 Git]

- HEAD `7e8809e` (`dev`), DB-1 변경은 모두 미커밋 작업 트리. 시작 시 `git status` : `M` 165 · `D` 11 · `A` 4 · `AM` 5 · `AD` 7(다른 세션) · `??` 1 (`JdbcBatchRewriteIntegrationTest.java`). HEAD 대비 184 파일 +3,035 / −11,714.
- 재개 시 달라진 점 1개 : `JdbcBatchRewriteIntegrationTest.java` 가 `??` → `A` (스테이징됨 — 에이전트는 Git 쓰기 명령을 쓰지 않았다. 스테이징 내용 = 작업 트리 내용).

### 60.2 격리 사본 구성

작업 트리를 `rsync` (제외 : `/.git/`·`**/build/`·`**/node_modules/`·`**/.env`·`/.gradle/`·`**/.gradle/`·`/.idea/`·`/lexorma-web/dist/`·`/lexorma/volume/`·`/docker/volume/`·`/.claude/`, 먼저 `--dry-run --stats` — 1,816개 27MB) → `final/copy`. `.env` 0개. 다른 세션의 `AD` 7개는 작업 트리에 없으므로 사본에도 없다 (사본 `db/migration/postgresql` = V0~V3). 웹 번들은 사본 안에서 새로 빌드. 코드·설정·테스트 1,082개 파일 SHA-256 목록 = `final/copy-hashes.txt` (목록 자체 `68e1b350…`). 주요 파일 : `ManagedDatabaseCheck.java` `5538d1f9…`, `application.yml` `26801cf9…`, `dev-test.yml` `7abdd742…`, `V0__init.sql` `9b18101a…`, `V1__seed_data.sql` `c2420e1f…`.

### 60.3 재개 시 일치 확인 [재개 — 실행]

증거 폴더·스크립트·JAR 모두 남아 있었다. `diff -rq 작업 트리 사본` (빌드 산출물 제외) **차이 0**, JAR SHA-256 `ea1b016b…` 그대로 → 중단 전 검증은 같은 코드에 대한 것이다. 3차 사본은 3차 세션이 정리해 남아 있지 않다 — 3차 이후 작업 트리에서 바뀐 파일은 수정 시각으로 확인했다 (`ManagedDatabaseCheck.java` 13:56:30 · `pending-work.md` · `completed/modeling-concurrency-m8.md` — 3차 마지막 편집). FINAL 전체 실행 3회(14:01~)는 이 최종 코드로 돌았고, 3차의 'DB 없이 단위 테스트' 증거(13:54)는 그 편집 전이라 재개 뒤 다시 실행했다 (72장).

## 61. 전체 회귀 — PostgreSQL 18.6 2회 · 15.20 1회 [실행]

방법 : 실행마다 **새 컨테이너(tmpfs = 새 빈 DB)**, CI 와 같은 DB 이름·계정·CI 전용 비밀번호·포트 5432·환경변수 6개 (원본 수집 = 같은 서버의 `postgres` DB), `./gradlew clean build --no-daemon --no-build-cache` (테스트가 up-to-date 로 건너뛰지 않게), `-Xlog:gc` 로 테스트 JVM 측정. 스크립트 `final/full.sh`.

| | 18.6 1회 | 18.6 2회 | 15.20 |
|---|---:|---:|---:|
| 서버 | `PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2)` | 같음 | `PostgreSQL 15.20 (Debian 15.20-1.pgdg13+1)` |
| 결과 | BUILD SUCCESSFUL | BUILD SUCCESSFUL | BUILD SUCCESSFUL |
| 전체 / 실행 / 실패 / 건너뜀 | 1,647 / 1,620 / **0** / 27 | 1,647 / 1,620 / **0** / 27 | 1,647 / 1,620 / **0** / 27 |
| 원본 수집 실행 | 19 | 19 | 19 |
| 시간 | 9분 52초 | 8분 34초 | 9분 39초 |
| 실행 작업 | 52 중 45 | 52 중 50 (`npmCi`·`buildFrontend` UP-TO-DATE) | 52 중 52 (`node_modules`·`dist` 를 지우고 실행) |
| Flyway (`flyway_schema_history`) | V0~V3 성공 4행 | 같음 | 같음 |
| bootJar SHA-256 | `ea1b016b3ec1…` | 같음 | 같음 |

- 누적 실행 **4,860건 · 실패 0**. 건너뜀 27 = 매 실행 모두 `LEXORMA_PERF` 성능 시험 (22 클래스, opt-in 유지) — 관리 DB 종류·환경 때문에 건너뛴 테스트 0.
- **2회차의 프런트엔드 재사용** : `clean` 이 `node_modules`·`dist` 를 지우지 않아 `npmCi`·`buildFrontend` 가 UP-TO-DATE 였다. 테스트 작업 8개와 컴파일·bootJar 는 모두 다시 실행됐다. npm ci · `tsc -b` · vite 는 1회차와 15.20 회차에서 실제 실행됐고, 세 JAR 의 해시가 같다.
- 모듈별 (매 회 같음) : common 35 · core 94 · system 28 · operation 9 · data 275 · source 16 · ai 25 · app 1,165 (건너뜀 27).
- 원본 수집 19건 : `AiDictionarySourceRefresh` 5 · `SourceJdbcCollection` 8 · `SourceJdbcCollectionLimit` 2 · `SourceJdbcSecurity` 1 · `SourceRenameReimportJdbc` 1 · `SourceViewDefinition` 2 — 원본 DB 도 같은 판(18.6 / 15.20).
- 주요 테스트 실행 확인 : `InitialSetupHttpTest`, `SessionReloginHttpTest` 6, `JdbcBatchRewriteIntegrationTest` 3, `PhysicalNameJudgedPathIntegrationTest` 2(9,999 · 10,000 경계 포함), `ConcurrentEditC2IntegrationTest.entityApprovalVsEdit`, `RelationSyncDeadlockIntegrationTest.s4TwoRenames` (66장) — 모두 3회 통과. 동시성 이름 패턴(app, 35 클래스) 262건 실행 · AI·MCP 이름 패턴 161건 실행, 실패 0.
- **18.6 ↔ 15.20 서버 오류 비교** : 서버 로그의 `ERROR` 279 / 287 / 279건, 종류(메시지 형태)는 **완전히 같다** — 모두 테스트가 일부러 만드는 것 (NOWAIT 55P03, UNIQUE·FK·CHECK·NOT NULL 위반, 인덱스 방식이 지원하지 않는 옵션 DDL 검증 9, 원본 권한 거부 3, 문장 취소·시간 초과). 15.20 에서만 나는 오류·실패 0.

## 62. PostgreSQL 지원 버전 — 최종

| 구분 | 버전 |
|---|---|
| 최소 지원 | **15** (ICU 빌드 필수) |
| 권장 | **18** |
| 실제 검증 | **15.20** (공식 이미지 `postgres:15.20`, 2026-11-24 게시 — Docker Hub 의 15.x 최신) · **18.6** |
| 기본 Compose · CI | 18.6 |
| 검증하지 않음 | 16 · 17 (전체 회귀 안 함 — 지원 범위 안이지만 '검증 완료' 로 표시하지 않는다) |

15.20 에서 신규 설치(Flyway V0~V3, ICU `und-x-icu` 생성 컬럼 포함)·전체 회귀·단일 JAR 업무 흐름·재시작 유지·세션 유지·원본 수집(원본도 15.20)을 확인했다. 런타임 버전 확인 기능은 추가하지 않았다.

## 63. Flyway · 관리 DB 설정 · 기동 · 보안 [실행]

### 63.1 Flyway

- 위치는 `classpath:db/migration/postgresql` 하나, H2 리소스 0, V0~V3 은 HEAD 와 같음 (`git diff HEAD` 빈 결과), 신규 마이그레이션 0, `clean`·`repair`·`baseline` 실행 0.
- 새 빈 DB : `Successfully applied 4 migrations … now at version v3` (18.6·15.20, JAR·Compose·외부 Compose·Secrets 모두). 재시작 : `Successfully validated 4 migrations` + `Schema "public" is up to date` (다시 적용 없음), `flyway_schema_history` = 0·1·2·3 성공 4행.
- 다른 세션의 V5~V7 은 사본·JAR 에 없다.

### 63.2 설정 · 기동 시나리오 F01~F15 (최종 JAR, 임시 PostgreSQL 18.6 SSL 켬, 스크립트 `final/scn.sh`)

| ID | 조건 | 결과 |
|---|---|---|
| F01 | 정상 (프로필 없음) | 기동, Flyway 적용 / 재시작 검증 |
| F02 · F03 | URL 없음 · 빈 값 | 종료 1 "관리 DB(PostgreSQL) 연결 설정이 없습니다 : spring.datasource.url 이 비어 있습니다." |
| F04 | 사용자명 없음 | 종료 1 "관리 DB 사용자명이 없습니다 …" |
| F05 | `jdbc:h2:mem:` · `jdbc:h2:file:` | 종료 1 "지원하지 않는 관리 DB 입니다 (jdbc:h2) …" |
| F06 | `jdbc:mysql:` | 종료 1 (jdbc:mysql) |
| F07 · F08 · F09 | 연결 불가 · 틀린 비밀번호 · DB 없음 | 종료 1, 분석기 문구 08001 · 28P01 · 3D000 |
| F10 | `ssl%mode` · 포트 자리에 문자 | 종료 1 "… URL 형식이 올바르지 않습니다 …" |
| F11 | `?password=` · `user:pw@` · `options=-c password=` · `%50ASSWORD` · `sslpassword` · 빈 `password=` | 6개 모두 종료 1 "… 인증정보 … 넣을 수 없습니다", **Hikari·Flyway 시작 0줄** (초기화 전 거절) |
| F12 | `sslmode=require` + `ApplicationName` + `connectTimeout` + `options=-c application_name=` / 다중 호스트(죽은 호스트 먼저) | 기동 |
| F13 · F14 | `local` · `dev` + 정상 | 기동 (프로필 표시 `"local"` · `"dev"`) |
| F15 | `local`·`dev` 설정 없음 · `local` 사용자명 없음 | 종료 1 |
| P1 · P3 | trust 계정 비밀번호 없음 / scram 계정 비밀번호 없음 | 기동 / 종료 1 (08004) |

- 28개 모두 기대대로. 모든 로그에서 가짜 비밀번호 2종 **0건**, H2 클래스 로딩 0 (`jdbc:h2` 문자열은 거절 문구에만).
- 인증서(클라이언트 인증서) 인증 : **실행하지 않음** (3차와 같음). trust 는 격리 컨테이너의 한 계정에만 썼고 운영 권장 설정이 아니다.
- URL 검사는 **표현 기반**이다 — 정상 매개변수 값 안의 임의 문자열이 비밀인지는 판단하지 않는다 (README 경고, 48장).

### 63.3 종료 상태

- 일반 JAR : 실패 시나리오 모두 종료 코드 1 (위 표).
- Docker `docker run` (재시작 정책 없음) : 설정 없음·사용자명 없음·틀린 비밀번호·URL 인증정보·H2 URL → 컨테이너 종료 코드 1, 재시작 0.
- 외부 Compose (`restart: unless-stopped`) : 비밀번호 필요 서버에 비밀번호 없음·틀린 비밀번호·URL 인증정보 → 앱이 뜨지 않고 같은 문구로 재시작을 되풀이 (150초 동안 11회). 성공으로 처리되는 경로 없음.
- `./gradlew bootRun` 설정 없음 : 앱은 기동하지 않지만 Gradle 은 `BUILD SUCCESSFUL` (DevTools `restartedMain`, 3차 C08 증거 — 그 뒤 `build.gradle`·`application-local.yml` 변경 없음). 개발 실행 전용이고 배포 경로(JAR·Docker)는 실패를 전달하므로 FINAL 차단 사항이 아니다. README 개발 절에 한 줄 적었다.

### 63.4 인증정보 로그

기동 시나리오 28개, JAR 업무 실행 4회(18.6·15.20 × 기동·재시작), 기본 Compose · 외부 Compose · Secrets 컨테이너 로그에서 가짜 관리 DB 비밀번호 · 가짜 원본 DB 비밀번호 · 가짜 계정 비밀번호 · 암호화 키 원문 **모두 0건**. 테스트 XML·서버 로그는 CI 전용 일회성 값(`ci_test_password`)만 담는다 — 실제 운영 인증정보가 들어갈 곳이 아니다. AI·MCP : 요청 로그 본문 `[PROTECTED]` (기존 정책), MCP 토큰 원문은 발급 응답에만 (증거에도 남기지 않음).

## 64. 단일 JAR · Docker [실행]

### 64.1 단일 JAR

`lexorma.jar` 110,502,352 바이트, `JarLauncher` · `Start-Class com.lexorma.LexormaApplication` · Spring Boot 4.1.2. 포함 : `static/index.html` + `assets/` 13, `postgresql-42.7.14.jar`, `flyway-core-12.4.0` · `flyway-database-postgresql-12.4.0`, `db/migration/postgresql/V0~V3`, `ManagedDatabaseCheck`·`spring.factories`. 없음 : H2 드라이버 · H2 콘솔 · `db/migration/h2` · `application-h2*` · `H2FileDatabaseCheck` · DevTools · Testcontainers (`httpcore5-h2` 는 HTTP/2 라이브러리). 기동 → 최초 관리자 → 로그인 → 업무 → 정상 종료(`Graceful shutdown complete`, SIGTERM 143) → 재시작 → 데이터 유지 (65장).

### 64.2 Docker 이미지 (CI `docker-image` Job 과 같은 단계)

`docker build --no-cache -f Dockerfile` 성공 (51초 — npm ci 201 패키지 · vite · Gradle bootJar 34초), 이미지 `/app/lexorma.jar` : `static/index.html` 1 · `postgresql-` 1 · `BOOT-INF/lib/h2` 0 · 마이그레이션 4 · DevTools 0, `CMD --spring.profiles.active=docker`, `EXPOSE 9940`. 외부 레지스트리 게시 없음.

### 64.3 기본 Compose (`docker-compose.yml` 수정 없이)

`POSTGRES_PASSWORD` 빈 값·줄 없음 → `config` 종료 1 "Set POSTGRES_PASSWORD in .env" (기본 비밀번호 없음). 정상 : PostgreSQL healthy → 앱 기동 (Flyway 4 적용) → 업무 등록 103 성공 (실패 2 = 65.2 의 스크립트 기대값) → 원본 비밀번호 4건 모두 `v1:<키ID>:` 암호문 · 평문 0 → `down` → `up` (데이터 디렉터리 유지) → Flyway 검증만 → 재시작 뒤 확인 9/9. DB 정보는 `.env` 한 곳 (앱은 같은 값으로 URL 을 만든다).

### 64.4 외부 PostgreSQL Compose (`docker-compose.external-db.yml`) — 재시작 재검증 [재개]

- 중단 전 : B1 기동·등록 성공, B2 trust 비밀번호 줄 없음 기동, B4·B5·B8 거절, B6·B7 `config` 거절. B1 재시작 뒤 확인은 **검증 스크립트가 앱 준비 전에 접속**해(로그의 예전 `Started` 줄을 보고 판단) 연결이 끊겼다 — 운영 결함 아님.
- 재개 : 준비 판정을 실제 HTTP(`/api/auth/csrf` 200)와 컨테이너 상태(종료·재시작 반복이면 '기동 실패')로 바꿨다 (고정 sleep 없음, 제한 180초, `final/ready.sh`·`restart.sh`). 결과 — 서비스는 `lexorma-app` 하나 (PostgreSQL 컨테이너 없음), 기동 준비 10초, 등록 **110/0**, 외부 DB 연결 10개 모두 SSL, 앱 재시작 준비 7초 (재시작 횟수 0, Flyway 검증만), 재시작 뒤 확인 **12/0** (id·승인·배치·FK·인덱스·매핑·원본 연결 4종 + 저장 비밀번호 복호화 연결 테스트 + **원본 재수집 COMPLETED**), 재시작 뒤 외부 DB 연결 유지(SSL), 원본 비밀번호 암호문 4 · 평문 0, 로그 비밀값 0.

### 64.5 Docker Secrets — 재시작 재검증 [재개]

`docker run` + `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/` + 파일 `spring.datasource.password` · `lexorma.secret.keys` (컨테이너 환경변수에 비밀번호·키 0개), 호스트명 `password-management-db`. 기동 준비 9초 → 등록 110/0 (원본 연결·수집 COMPLETED 3 테이블 9 컬럼) → 원본 비밀번호 암호문 4 · 평문 0 → `docker restart` → 준비 7초 → 재시작 뒤 12/0 : 저장된 원본 비밀번호를 Secret 키로 복호화해 연결 테스트 성공 · 재수집 COMPLETED (반영 순서 2) → 로그 비밀값 0. Secret 은 선택 기능 그대로 (환경변수 방식도 64.3·65장에서 동작).

### 64.6 개발 서버 (`Dockerfile.dev`, `dev` 프로필)

저장소에 있는 것 : `Dockerfile.dev` (포트 18080, `--spring.profiles.active=dev`), `application-dev.yml` (DB 정보 없음). 개발 서버 배포 스크립트·환경변수 파일은 **저장소에 없어 확인하지 못했다** (접속·변경하지 않음). 제거된 `DB_*`·`LEXORMA_DB_*`·고정 계정 참조 : 저장소(문서 제외)에 0. 개발 서버 배포 때 필요한 값 : `SPRING_DATASOURCE_URL` (`jdbc:postgresql://…`, 인증정보 없이) · `SPRING_DATASOURCE_USERNAME` · 서버가 비밀번호를 요구하면 `SPRING_DATASOURCE_PASSWORD` (또는 Secret 파일), 원본 비밀번호를 저장하려면 `LEXORMA_SECRET_KEYS` (+ `LEXORMA_SECRET_ACTIVE_KEY_ID`), MCP 를 쓰려면 `LEXORMA_MCP_ENABLED=true`. `dev` 프로필에는 고정 암호화 키가 없다.

## 65. 업무 기능 · 영속성 · 세션 [실행]

### 65.1 단일 JAR 업무 흐름 (18.6 · 15.20 각각, API — `final/e2e_final.py`)

최초 관리자 설정(중복 설정 409) → 로그인 → 프로젝트 생성·선택 → 표준 도메인 2·단어 4·용어 3 등록 → 승인 취소·승인·오래된 기준값 409 → 품질 요약·사용 현황 → 논리 모델·주제 영역·엔터티·속성·식별자·관계·관계 속성 쌍·상속 조회·엔터티 승인·주제 영역 분류·논리 ERD 범위·배치 저장 → 테이블 2·PK·FK(REF_COLUMN `FK_ORD_CUST_NO`)·인덱스·컬럼 순서·테이블 DDL·통합 DDL 4종(PostgreSQL·Oracle·MySQL·MariaDB, 오류 0, FK 포함)·물리명 추천·물리 ERD·배치 저장·테이블 승인·409 → 엔터티·속성 매핑 4·중복 매핑 거절·정합성(MATCH 25 — 유형 7)·요약 → 회원가입·권한 부여·사용자 로그인·승인 엔터티 수정 거절(USER)·ADMIN API 403·비회원 프로젝트 로그인 401·동시 로그인 1개(이전 세션 401)·로그아웃 → SEC-1 JDBC 규칙·원본 연결(SSL)·Oracle·MySQL·MariaDB 연결 설정 저장·연결 테스트·전체 수집(테이블 3·컬럼 9·VIEW 포함)·스냅숏·원본 테이블 상세·기준 연결·스키마·테이블 매핑·비교(MODIFIED·ADDED_IN_SOURCE·ADDED_IN_MODEL) → AI_HTTP 규칙·AI Provider·기본 Provider READY → MCP 프로젝트 켬.

결과 (중단 전) : 18.6 · 15.20 각각 **103 성공 · 실패 2**. 재시작 뒤 확인 9/9 (18.6·15.20) — 용어·테이블·컬럼 id, 승인 상태, 논리·물리 배치 좌표, FK·인덱스, 정합성 결과, 원본 연결 4종·수집 결과·기준 연결, AI Provider READY, MCP 설정, 사용자 로그인 유지.

### 65.2 스크립트 실패 2건과 재검증 [재개]

| 항목 | 중단 전 기록 | 원인 | 재검증 (18.6·15.20 JAR, B1·C 컨테이너) |
|---|---|---|---|
| 컬럼 순서 변경 400 | 실패로 기록 | 관리자가 만든 컬럼은 승인 권한자 자동 승인 → 승인된 컬럼 순서 변경 거절은 **기존 정책** (`APPROVED_DATA_CANNOT_BE_UPDATE`) | 승인 상태 거절 400 → 승인 해제 → 순서 변경 200 → 저장된 순서 확인 (`CUST_NM` 1 · `CUST_NO` 2) → 오래된 기준 순서 409 → 다시 승인 — 모두 통과 |
| 로그아웃 204 | 200 을 기대 | 기존 계약 `SecurityConfig.logoutSuccessHandler` = **204** | 204 + 로그아웃 뒤 같은 세션의 인증 필요 API 401 — 통과 |

스크립트 기대값·데이터 준비만 바꿨다 (승인 정책·API 응답 무변경). 재검증 결과 18.6 · 15.20 각각 phase 1 **110/0**, 재시작 뒤 **12/0** (재수집 포함).

### 65.3 세션 (Spring Session JDBC)

세션 테이블은 Flyway V0 이 만든다 (`SPRING_SESSION` 행 확인). 로그인·속성·프로젝트 선택·동시 로그인 1개(앞 세션 `DUPLICATE_LOGIN` 401)·로그아웃 무효화는 65.1. **재시작 뒤 세션 유지** (15.20, 로그인 → 쿠키 보관 → 정상 종료 → 기동 → 같은 쿠키로 `me` 200 · 프로젝트 유지 · 조회 200) = 기존 정책 ("재시작해도 만료 전이면 남는다", `application.yml`). 18.6 실행의 '재시작 전 세션 401' 은 그 뒤 같은 계정의 다른 로그인이 세션을 대체했기 때문이라 판단에 쓰지 않았다. 관련 통합 테스트(`SessionReloginHttpTest` 6, `InitialSetupHttpTest`)는 3회 전체 실행 통과. Redis 등 새 저장소 없음.

### 65.4 다중 DBMS 원본

관리 DB 단일화와 별개로 원본·물리 모델 DBMS 4종 유지 : Oracle·MySQL·MariaDB 연결 설정 저장(수집은 PostgreSQL 만 — 기존 범위, 신규 구현 없음), 통합 DDL 4종 생성 (65.1), DDL 골든 테스트는 전체 회귀에 포함.

## 66. 동시성 · `s4TwoRenames` · JDBC 배치

- 같은 코드라 3차의 동시성 묶음 271건 × 3회 · `s4TwoRenames` 독립 10회 결과를 쓴다 (53.3). FINAL 전체 실행 3회 모두 동시성 테스트 실패 0.
- `s4TwoRenames` : 3회 × 3라운드 = **9라운드 모두 `[OK, OK, select p1_0.prj_sn … from tb_prj … for no key update]`** (B 의 백엔드가 A 에 막혀 프로젝트 행에서 기다림 — PID·차단 관계·두 번 확인 감시). 3차 39라운드와 합쳐 48라운드 정상.
- **최종 판정** : 테스트 감시 개선 완료 · 확인된 감시 결함 보완 완료 · 예전 간헐 실패의 정확한 원인은 **유력 원인 확인, 확정 아님** (실패 순간 PID 기록 없음) · 운영 잠금 결함 **발견되지 않음**. 정상 통과했으므로 원인 확정 작업은 하지 않는다. 재발하면 개선된 PID·차단 관계 기록으로 분석한다 (M-8 문서 24장에 같은 판정).
- `JdbcBatchRewriteIntegrationTest` 3건 (배치 300행 비활성 300문장 · 활성 4문장 · 논리→물리 생성 TB_COL 120행 4문장 + 데이터 정합성) : 18.6 · 15.20 전체 실행에서 통과. 문장 수 감소를 처리 시간 개선율로 표현하지 않는다.

## 67. AI — 통합 테스트 · 실제 Ollama

### 67.1 계약

AI·MCP 관련 테스트(이름 패턴 161건, 3차 26 클래스 152건 포함)가 3회 전체 실행에서 실패 0 — 메타데이터 조회 · D-35 논리 조회 · 상속 · Tool 제한 · 토큰 예산 · 부분 답변 6개 사유 · 근거 검증 · 권한 재검증 · think 제어 · 서버 맥락의 결정적 계약 유지. AI 코드·프롬프트·Tool·근거 상한·TokenEstimator 변경 없음. AI-6 미착수.

### 67.2 실제 Ollama (로컬 Ollama 0.40.18, 이미 설치된 `gemma4:e4b`, 단일 JAR + PostgreSQL 18.6, 외부 전송·모델 설치 없음)

| ID | 질의 | HTTP | 시간 | 완료 | Tool | 근거 검증 | 판정 |
|---|---|---|---|---|---|---|---|
| AI-F01 | 표준 용어·도메인 | 200 | 23초 | COMPLETE | searchStandardTerms OK | PARTIAL (10/20 — DOMAIN NOT_FOUND) | 모델이 근거의 id 를 잘못 붙임 (같은 질문 재실행 10/10 VERIFIED) — 비결정적 모델 출력 |
| AI-F02 | TB_COL 컬럼·PK | **502** | 57초 | — | — | — | `AI_STRUCTURED_OUTPUT_INVALID` : 컬럼 32개를 근거로 들어 근거 상한 30 을 두 번(재요청 포함) 넘김 — 기존 스키마 계약대로 거절, 모델 출력 한계 |
| AI-F03 | 논리 모델 개요 | 200 | 9초 | COMPLETE | Overview OK | PARTIAL 5/6 | SUBJECT_AREA 설명 사용 표시는 확인 불가(`DESCRIPTION_NOT_RETRIEVED`) — 설명 사용 확인은 논리 속성만 (D35-D25, 보수적 계약). 재실행 때는 엔터티를 `LOGICAL_ATTRIBUTE` 로 잘못 표시해 NOT_FOUND — 검증기가 맞게 걸러냄 |
| AI-F04 | 주제 영역·엔터티 | 200 | 5초 | COMPLETE | Overview NOT_FOUND | NO_EVIDENCE | 모델이 모델 이름을 'FINAL' 로 잘라 넘김 → "없음" — 의미 오류 |
| AI-F05 | 유효 속성(상속) | 200 | 8초 | COMPLETE | Detail NOT_FOUND | UNVERIFIED | F04 와 같은 의미 오류 |
| AI-F06 | 상속 식별자·관계 | 200 | 15초 | COMPLETE | Detail INVALID_ARGUMENT → Overview OK → Detail OK | VERIFIED 2/2 | 정상 (첫 호출 인자 오류는 Tool 이 거절 후 복구) |
| AI-F07 | 다량 조회 (모든 엔터티·속성·설명) | 200 | 36초 | **PARTIAL · SAME_TOOL_LIMIT_REACHED** | 6 OK | VERIFIED 10/10 | 정상 — 실제 Provider 의 부분 답변 |
| AI-F08 | 권한 범위 (USER, 다른 프로젝트 테이블 + 자기 테이블) | 200 | 12초 | COMPLETE | 첫 조회 NOT_FOUND → 검색 → 조회 OK | VERIFIED 3/3 | 다른 프로젝트 `TB_AI_STNG` 은 "등록되어 있지 않음", 자기 `CUST` 만 답 — 프로젝트 격리 정상 |

- 8건 중 HTTP 200 7건 · 502 1건. **HTTP 200 과 의미적으로 맞는 답은 다르다** — 의미가 맞은 것은 F06·F07·F08 (+ F01 재실행), F04·F05 는 모델의 의미 오류, F01·F03 의 확인 실패·불가는 모델의 근거 표기 오류를 검증기가 걸러낸 결과다.
- **DB-1 결함 0** : PostgreSQL 조회 실패 · 정상 조회 경로 퇴행 · Tool 실행 중 DB 오류 · 근거 검증 계약 위반 · 프로젝트 격리 위반 · 권한 재검증 실패 · PARTIAL 계약 위반 모두 없음. 서버 로그의 AI 관련 경고는 구조화 재요청·출력 한도뿐.
- 브라우저 (69장) 에서 실제 답변 2건 추가 : 단순 질의 COMPLETE(13초, 근거 2개 확인), 엔터티 5개 상세 질의 **실제 PARTIAL 안내 표시** (42초, 근거 13개 확인). 같은 화면의 대량 질의 1건은 서버가 PARTIAL(SAME_TOOL_LIMIT, 결과 5건 전달)로 판정했지만 구조화 응답이 근거 61개 → 재요청이 출력 한도 4,096 에서 잘려 **'부분 답변 실패 — 최초 사유 유지' 502 `AI_TOOL_CALL_LIMIT_EXCEEDED`** 로 끝났다 — AI 공통 실행 안정성 문서의 남은 위험(긴 답의 출력 한도, `completed/ai-execution-stability.md`)이 재현된 것으로 DB-1 결함이 아니다.

## 68. MCP [실행 — 단일 JAR, 실제 HTTP JSON-RPC]

- **비활성 (기본)** : 15.20 JAR (`LEXORMA_MCP_ENABLED` 없음) — `POST /mcp` 404 · `GET /mcp` 404, 앱 정상 (업무 흐름 103·재시작 9/9), 화면 설정은 저장되지만 서버 꺼짐 표시.
- **활성** (18.6 JAR, `LEXORMA_MCP_ENABLED=true`) : 토큰 없음 401 · 잘못된 토큰 401 · 다른 Origin 403 · `GET` 405 · `initialize` 200 (`protocolVersion 2025-06-18`, 서버 `lexorma`) · `tools/list` **13개** (공유 11 + `getProjectInfo` · `getLogicalModel`, 입력 스키마 확인).
- **Tool 13개 모두 실제 호출 성공** (각 HTTP 200 · `isError=false`, 예제 프로젝트 0 + 상속 모델) : `listSchemas` (1) · `searchTables` (10, PARTIAL 일치) · `getTableDetail` (TB_COL 32 컬럼) · `getTableColumns` (32) · `getTableRelations` (나감 2 · 들어옴 9) · `searchStandardTerms` (10) · `searchStandardWords` (1) · `searchStandardDomains` (8) · `getLogicalModelOverview` (엔터티 5 · 주제 영역 1) · `searchLogicalEntities` (3) · `getLogicalEntityDetail` (개인고객 — 상속 속성 4 + 자기 1, PK 순서) · `getProjectInfo` (LEXORMA 91 테이블) · `getLogicalModel` (`attributeScope DIRECT`, `hasInheritance true`).
- 격리 · 읽기 전용 : 프로젝트 0 토큰으로 다른 프로젝트 테이블 검색 0건 · 다른 프로젝트 테이블 id 조회 `NOT_FOUND` · 없는 Tool(`dropTable`) `-32602 Unknown tool` · 다른 프로젝트 토큰의 `getProjectInfo` = 그 프로젝트. 토큰 폐기 뒤 401, 호출 이력 기록 (`auth:REVOKED DENIED` 등).
- 외부 MCP 클라이언트 : **검증하지 않음** (설치·계정 설정을 강제하지 않음 — 차단 사항 아님).

## 69. 브라우저 [재개 — 실행, 기존 Playwright 1.62.1 + Chromium, 최종 JAR + 새 빈 PostgreSQL 18.6, MCP 켬]

스크립트 `final/browser/*.js`, 화면 `final/browser/shots/` (각 단계 스크린샷). 새 테스트 프레임워크 추가 없음. 구분 : **[조작]** = 실제 화면 입력·저장, **[표시]** = 화면 접근·데이터 표시 확인.

| 흐름 | 결과 |
|---|---|
| 최초 관리자 설정 `/setup` [조작] → 로그인 → 대시보드 | 통과 |
| 프로젝트 생성 (프로젝트 관리 › 등록) · 로그아웃 · 새 프로젝트로 로그인 [조작] | 통과 |
| 표준 도메인 · 단어 2 · 용어 등록 (용어명 → 도메인 선택 → 약어 `CUST_NM` 자동) [조작] | 통과 (처음엔 스크립트가 화면 필수값 '저장 형식'·'단위' 를 비워 막힘 — 화면 검사 정상) |
| 용어 승인취소 → '승인 대기' → 승인 [조작] | 통과 |
| 논리 모델 · 엔터티 2 등록 [조작] / 테이블 등록 (코멘트 → 이름 `CUST` 자동) · 컬럼 등록 (테이블·표준 용어 검색 선택) [조작] | 통과 |
| 논리 ERD · 물리 ERD : 편집 모드 → 노드 끌기 → 배치 저장 → 다시 열기 [조작] | 두 화면 모두 저장된 위치로 다시 열림 |
| 논리·물리 매핑 (고객 ↔ CUST) [조작] | '매핑 완료' |
| 외부 연결 규칙 JDBC · AI_HTTP 등록 [조작] | 통과 |
| 원본 연결 등록 (연결 테스트 '연결 성공 · 메타데이터 조회 가능' → 등록) · 수집 실행 → 완료 · 기준 연결 저장 · 스키마 매핑 확정 · 테이블 매핑 (shop.customer → CUST) · 원본·모델 비교 '매핑됨 · 다름 6' [조작] | 통과. 순서 안내 '원본 스키마 shop 의 스키마 매핑을 먼저 확정해주세요' 확인. 재실행 중 같은 이름 재등록 400 `INVALID_SOURCE_CONNECTION` (같은 이름 거절 — 정상) |
| AI Provider 등록 · '기본으로 지정' → READY [조작] · AI 도우미 실제 답변 [조작] · 실제 PARTIAL 안내 [조작] | 통과 (67.2) |
| MCP : 프로젝트 켬 · 토큰 발급 (만료일 필수) [조작] | 통과 (토큰 접두어 `lxm_`, 목록 표시) |
| 회원가입 화면 → 승인 전 로그인 거절 → 관리자 사용자 관리에서 ROLE_USER → 프로젝트 관리에서 회원 승인 (자동 승인 꺼진 프로젝트) → 사용자 로그인 (프로젝트 목록에 자기 프로젝트만) [조작] | 통과 |
| 권한 차이 : 사용자 메뉴에 사용자 관리 · AI Provider 설정 · 외부 연결 보안 없음, 직접 URL → '접근이 거부되었습니다' [표시] | 통과 |
| 동시 수정 : 관리자 A · 사용자 B 가 같은 테이블 수정 폼 → A 저장 → B 저장 `PUT /api/saveTable` **409** [조작] | 폼 안 안내 '다른 사용자가 이 항목을 변경했습니다. 작성한 내용은 유지됩니다. 최신 내용을 확인한 뒤 다시 저장해 주세요.' · B 입력 보존 · [최신 내용 확인] → 편집 시작 시 / 현재 서버 값 / 내 변경 비교 + [내 값으로] · 409 뒤 추가 요청 0 (자동 재시도·자동 저장 없음) · 최신 값 기준 수동 재적용 저장 성공. 업무 설명 동시 수정도 `PUT /api/bizDescription` 409 |
| 언어·테마·화면 크기 [표시] : ko·light·1440 / ko·dark·375 / en·light·375 / en·dark·1440 × 대표 화면 10 (대시보드 · 표준 용어 · 논리 모델 · 논리 ERD · 테이블 · 물리 ERD · 매핑 · 원본 매핑 · AI Provider · MCP) | 40개 모두 가로 넘침 0, `data-theme`·`lang` 맞음, 영어 제목 번역, API 오류·Console 오류 0 |
| PARTIAL 안내 표시 (응답 가로채기 — 서버 계약 모양, 실제 응답과 구분) ko·light·1440 / en·dark·375 | 안내 문구 ko · en, 넘침 0, Console 0 |

- 미검증 조합 : 1024px, ko·dark·1440, en·light·1440 등 나머지 조합 (전체 매트릭스는 하지 않음). 브라우저 승인은 승인취소→승인 1건(용어)만 — 나머지 승인은 API·통합 테스트.
- 발견된 UI 회귀 : **없음** (DB-1 은 프런트엔드 업무 코드를 바꾸지 않았다). UI 개선 후보도 새로 기록할 것 없음.

## 70. 테스트 JVM 메모리 — 기록 정정

app 테스트 JVM (G1, `maxHeapSize = '2g'`, 측정 `-Xlog:gc`, 단위 MB) :

| 실행 | GC 직전 최대 | Young GC 직후 최대 | Remark 직후 최대 | 커밋 최대 (= 최대 힙) | Full GC | Evacuation Failure | OOM |
|---|---:|---:|---:|---:|---:|---:|---:|
| FINAL 18.6 1회 | 2,047 | 1,704 | 1,779 | 2,048 | 0 | 2 | 0 |
| FINAL 18.6 2회 | 2,029 | 1,782 | 1,811 | 2,048 | 0 | 1 | 0 |
| FINAL 15.20 | 2,028 | 1,785 | 1,773 | 2,048 | 0 | 2 | 0 |
| 3차 2회 (같은 로그를 같은 기준으로 다시 계산) | 2,046 | 1,819 | 1,826 | 2,048 | 0 | 3 | 0 |

- **정정** : 3차 53.1 의 'GC 직전 최대 1,614MB · GC 뒤 최대 1,314MB' 는 최대값이 아니라 같은 로그 안의 GC 한 건(1,960→1,314)과 다른 한 시점의 값이었다 — 같은 로그를 다시 계산하면 위 표처럼 2,046 / 1,819 이다. FINAL 중간 보고의 'GC 직후 약 1.9GB' 도 회수가 없는 `Pause Cleanup` 기록(1,909→1,909)을 잘못 읽은 값이라 정정한다. 기준이 다른 값끼리 비교하지 않는다.
- 판정 : 3차와 FINAL 의 사용량은 같은 수준이다 (퇴행 없음). 힙 2GB 는 유지(PG-D51)하되 **여유가 크지 않다** — GC 직전이 상한에 닿고 Evacuation Failure 가 1~3회 있으며, Full GC·OOM 은 없다. GitHub 러너(16GB)에서의 첫 실행 확인 항목으로 남긴다 (75장). 메모리 최적화는 하지 않았다.

## 71. CI

- 구성 [코드 — 정적 검토] : `push: dev` · `concurrency dev-test` · `test` Job (ubuntu-latest, timeout 45, `contents: read`, `postgres:18.6` 서비스 + `pg_isready` healthcheck, CI 전용 계정, 테스트 키 3 + 원본 수집 키 3 — 관리 `lexorma_test` / 원본 `postgres` 같은 서버 다른 DB, Java 21 temurin · Node 22 · setup-gradle, `./gradlew build`, 실패 시 `postgres.log` + 테스트 리포트·XML 업로드) + `docker-image` Job (`Dockerfile` 빌드 → JAR 의 SPA·PostgreSQL 드라이버 있음 · H2 없음 검사, push 없음). 테스트 힙 2g 는 `build.gradle`. `continue-on-error`·재시도·제외·PostgreSQL 테스트 건너뜀 **없음**.
- YAML 문법 [실행] : Ruby `YAML.load_file` — Job 2개, 단계 7·3, 서비스·환경변수 확인.
- GitHub Actions 전용 검사(`actionlint`) : **실행하지 못함** — 로컬에 없고, 검사를 위한 도구 설치·이미지 내려받기는 하지 않았다.
- 로컬 CI 동등 [실행] : 61장 3회가 CI 와 같은 이미지·DB 이름·계정·비밀번호·포트·환경변수·`./gradlew build` (+ clean) — 모두 성공. Docker 이미지 Job 동등 : 64.2.
- **원격 GitHub Actions : 실행하지 않음** — PG-D42 (통합 커밋 전 커밋·푸시 금지). 원격에서 통과했다고 기록하지 않는다. 사용자가 통합 커밋·푸시한 뒤 첫 `Test (dev)` 의 두 Job 결과·시간·`postgres.log` 를 확인한다.

## 72. 순수 단위 테스트 · 테스트 DB 안전 [재개 — 실행]

- DB 설정 없이 (테스트 키·`SPRING_DATASOURCE_*` 모두 해제, `--rerun-tasks`) : 비-app 7개 모듈 **482건 실패 0** (common 35 · core 94 · system 28 · operation 9 · data 275 · source 16 · ai 25) + app 의 스프링 없는 테스트 (`ManagedDatabaseCheckTest` · `TestDatabaseGuardTest` · `ModuleBoundaryTest` · `MessagesCoverageTest`) **59건 실패 0** — 최종 코드 기준.
- DB 가 필요한 테스트는 **건너뛰지 않고 실패** (임시 DB 만 사용) : 변수 없음 · DB 이름에 `test` 없음 (`e2e_main`) · `jdbc:h2:mem:test` · 사용자명 없음 → 직접 연결 `V0SeedMigrationTest` 는 각각 "테스트 DB 가 지정되지 않았습니다 …" · "… DB 이름에 test 가 없습니다 …" · "… jdbc:postgresql: URL 이어야 합니다." 로 실패, 스프링 `ExampleTermCodeDefinitionTest` 는 컨텍스트 로드 실패 (원인 `TestDatabaseGuard`). 안전하지 않은 DB 로는 연결 전에 멈춘다. 원본 수집 테스트의 분리 검증(같은 대상 거절·사용자명 필수·모호한 주소 거절)은 `TestDatabaseGuardTest`·`SourceJdbcTestSupport` 로 3회 전체 실행에 포함.
- H2 · Testcontainers 추가 없음.

## 73. H2 잔존 · 의존성 · 아키텍처 — 최종 [실행]

| 분류 | 상태 |
|---|---|
| 운영·테스트 H2 JDBC · 전이 의존성 | 없음 — 모든 모듈의 Gradle 의존성 그래프에 `com.h2database` 0, `testcontainers` 0. HEAD 대비 의존성 변경은 `runtimeOnly 'com.h2database:h2'`·`spring-boot-h2console` **삭제뿐** |
| H2 Console · 운영/테스트 프로필 · 운영 URL · Flyway 리소스 · 운영·테스트 SQL/잠금 분기 · 세션 감시 · 전용 테스트 · CI · Docker 배포 · 호환 설정 · 이전 기능·가이드 · Standalone | 없음 — JAR·이미지 JAR 에 H2 0 |
| 남은 `h2` 문자열 (문서 제외) | H2 URL **거절** 정책 테스트 2 (`TestDatabaseGuardTest`, `ManagedDatabaseCheckTest`) · CI 의 'H2 가 없는지' 검사 · `.gitignore` H2 파일 패턴(PG-D44) · 과거 기록 주석 (테스트 7 · `build.gradle` 1) · 수정하지 않는 V0·V1·V3 주석 · `package-lock.json` 해시 우연 일치 · 프런트엔드 HTML `h2` |
| 신규 운영 의존성 · 필수 외부 서비스 | 없음 — Redis·MCP·AI Provider·별도 프런트 서버·DB 초기화 서비스·DB 설정 화면·새 인증 체계 필수화 없음, 단일 JAR 유지 |

판정 : **H2 실행·지원 경로 0** (문자열 0 기준이 아니라 실제 경로 기준).

## 74. 문서 정리 · 통합 커밋 준비

### 74.1 문서

- README : 지원 버전 문장을 '검증 15.20 · 18.6, 16·17 미검증' 으로, 개발 절에 `bootRun` 종료 상태 한 줄 (63.3).
- 이 문서 : FINAL(59~77장) 추가 후 `docs/ongoing/` → `docs/completed/postgresql-only-migration.md` (파일 이름 유지).
- `completed-work.md` [DB-1] 추가 · `pending-work.md` [DB-1] 삭제·작업 순서 갱신 (원격 CI 확인을 후속 확인 항목으로) · CLAUDE.md (문서 위치·잘못된 Compose `.env` 경로 `docker/lexorma/.env` → `lexorma/docker-compose-exam/.env`, CLAUDE.md 는 Git 제외 파일) · `completed/modeling-concurrency-m8.md` 24장에 FINAL 판정 한 줄 · `completed/h2-file-storage.md` 링크.
- `completed/ai-execution-stability.md` · `completed/ai-logical-model-query-d35.md` : 과거 H2 측정 기록은 그대로 두고, 맨 위에 '관리 DB 는 DB-1 뒤 PostgreSQL 전용' 참조 한 줄.

### 74.2 이동에 따른 경로 주석 (코드 3곳 — 주석만)

`ManagedDatabaseCheck.java` · `JdbcBatchRewriteIntegrationTest.java` · `build.gradle` 의 `docs/ongoing/postgresql-only-migration.md` → `docs/completed/…`. 바이트코드·동작 변화 없음 — 컴파일만 다시 확인 (74.3). 문서 이름만 적은 테스트 주석(장 번호 인용)은 그대로 맞다.

### 74.3 주석 변경 뒤 확인

주석 변경 뒤 작업 트리를 격리 사본에 다시 동기화해 `compileJava compileTestJava` 성공과 `ManagedDatabaseCheckTest`·`JdbcBatchRewriteIntegrationTest` 재실행을 확인했다 (77장 `final/after-comment.log`). 전체 회귀는 다시 돌리지 않았다 (주석만 — FINAL 지시 23장).

### 74.4 통합 커밋 대상 (사용자가 직접)

최종 보고에 파일 단위 목록. 핵심 주의 :

- **현재 스테이징을 그대로 커밋하면 안 된다.** 인덱스에 다른 세션의 `AD` 7개(V5~V7 ×6 · `docs/ongoing/logical-subtype.md`)가 남아 있고, `AM` 5개는 스테이징된 내용이 최종 작업 트리보다 오래됐다 (FINAL 이 검증한 것은 작업 트리 내용).
- 이 문서는 인덱스에 `docs/ongoing/…` 로 `A` 스테이징돼 있다 — 이동 뒤에는 `docs/ongoing/postgresql-only-migration.md` 가 인덱스에만 남으므로(작업 트리 없음) 커밋에서 빼고 `docs/completed/postgresql-only-migration.md` 를 넣어야 한다.

## 75. 미검증 · 남은 위험

**미검증** : 원격 GitHub Actions (PG-D42) · `actionlint` · PostgreSQL 16 · 17 · 클라이언트 인증서 인증 · 외부 MCP 클라이언트 · 별도 리눅스 호스트 배포 (macOS + OrbStack 만) · 개발 서버의 실제 배포 설정 (저장소에 없음) · 브라우저 나머지 조합 · opt-in 성능 시험 · `s4TwoRenames` 예전 간헐 실패의 실제 순간.

**남은 위험** :
1. CI 첫 실행 : GitHub 러너의 시간(45분 안)·동시성 시험 여유·테스트 힙(70장 — 상한 근처) 미확인.
2. URL 인증정보 검사는 표현 기반 — 정상 매개변수 값의 임의 비밀은 막지 못한다 (README 경고).
3. `bootRun` 은 설정 누락 실패를 Gradle 종료 코드로 넘기지 않는다 (개발 실행만).
4. 실제 모델(gemma4:e4b)의 의미 오류·긴 답의 출력 한도로 인한 502 — 기존 AI 한계, AI 후속 작업 범위.
5. 개발 서버는 `SPRING_DATASOURCE_*` 없이는 뜨지 않는다 (64.6 의 값을 배포 설정에 넣어야 한다).

## 76. FINAL 완료 기준 판정

| 항목 | 판정 | 근거 |
|---|---|---|
| PG-D01~D51 반영 | 충족 | 59장 |
| PostgreSQL 18.6 새 빈 DB 2회 · 15.20 1회, 실패 0, 원본 수집 19건씩, 신규 제외 없음 | 충족 | 61장 |
| 순수 단위 테스트 DB 없이 · 테스트 DB 안전 | 충족 | 72장 |
| Flyway V0~V3 적용·재시작 · 신규 마이그레이션 0 · 신규 운영 의존성 0 | 충족 | 63.1 · 73장 |
| 관리 DB PostgreSQL 필수 · 기본 인증정보 없음 · URL 인증정보 차단 · 정상 URL 허용 · 로그 노출 0 | 충족 | 63장 |
| 단일 JAR · 기본 Compose · 외부 Compose · Docker Secrets · Docker 이미지 | 충족 | 64장 (재시작 재검증 포함) |
| 신규 설치 · 영속성 · 인증·세션 · 표준 · 논리 · 물리 · ERD · 매핑 · 승인 · 원본 수집 · 다중 DBMS | 충족 | 65장 · 69장 |
| 동시성 · `s4TwoRenames` · JDBC 배치 | 충족 | 66장 |
| AI 통합 계약 · 실제 Ollama 대표 질의 · 부분 답변 | 충족 (실제 모델 한계는 별도 기록) | 67장 |
| MCP 비활성·활성 · Tool 13개 등록·실제 호출 | 충족 | 68장 |
| 브라우저 대표 업무 흐름 · 권한 · 충돌 · 언어·테마·모바일 | 충족 | 69장 |
| CI PostgreSQL 구성 · 로컬 CI 동등 · Docker Job 동등 | 충족 (원격 실행·`actionlint` 는 기준 제외 — 미검증 기록) | 71장 |
| H2 실행·지원 경로 · 호환 계층 · 이전 기능 0 | 충족 | 73장 |
| 문서 · 로드맵 · 통합 커밋 대상 | 충족 | 74장, 최종 보고 |
| Git 읽기 전용 · 중간 커밋 없음 · 다른 세션 보호 | 충족 | 60.1 · 74.4 |

**DB-1 판정 : 로컬 구현 및 통합 검증 완료.** 남은 것은 사용자의 통합 커밋과, 그 뒤 첫 원격 CI 확인이다 (구현 미완료가 아니라 후속 확인). AI-6 은 그 뒤 사용자와 시작한다 (PG-D15).

## 77. 검증 증거 위치

세션 scratchpad `final/` (실제 인증정보·토큰·키 원문 없음 — 모두 가짜 값·일회성 값) : `head.txt`·`status-before.txt`·`staged-before.txt`·`status-resume.txt`·`status-final.txt`·`diff-head-names.txt`·`copy-hashes.txt` (대상) · `pg18-run1/`·`pg18-run2/`·`pg15-run1/` (`build.log`·`server.txt`·`flyway.txt`·`postgres.log`·`xml/`·`gc/`·`jar.txt`) · `sum.py` (요약) · `scn-25481/`·`scn-25481.txt` (F01~F15) · `jar18/`·`jar15/` (JAR 업무·MCP·AI `mcp_ai.json`·재검증 `re/`) · `compose/`·`compose.txt`·`compose2/`·`restart.txt` (Docker) · `docker-build-nocache.log`·`img-jar.txt`·`jar-list.txt` · `deps-app.txt`·`deps-all.txt` · `unit-nodb.log`·`guard-G1~G4.log` · `browser/` (`flow*.log`, `shots/`) · `after-comment.log`.
