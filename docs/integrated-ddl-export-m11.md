# M-11 통합 DDL Export

2026-11-13. 물리 모델(스키마·테이블·제약·인덱스·FK·VIEW)을 **한 SQL 스크립트**로 내보내는 기능을 기존 DDL 생성기 위에 만든 기록이다. DDL 은 생성만 하고 어떤 DB 에서도 실행하지 않는다.

**상태 : 통합 DDL Export 구현 완료.** M-11 의 나머지 범위(테이블·컬럼 엑셀 Export 의 모델 단위 확장, ERD 이미지, 재가져오기 형식·프로젝트 간 이동 — D-12·D-18)는 이번 작업에 들어 있지 않다 (10장).

## 1. 기존 구조 분석

| 기능 | 구현 위치 | 지원 객체 | DBMS | 입력 범위 | 의존성 처리 | 승인 상태 | 기존 테스트 | 분류 |
|---|---|---|---|---|---|---|---|---|
| 테이블 DDL | `TableDdlGenerator.generate`, `TableDesignServiceImpl.generateTableDdl` (`GET /api/selectTable/ddl/{id}`) | CREATE TABLE(PK·UNIQUE·CHECK), 인덱스, FK, COMMENT | 4종 | 테이블 1개 | FK 는 ALTER TABLE 로 분리 (생성 순서와 무관) | 설계용 : 승인 대기 포함 + `pendingApprovals` 안내 | 골든 파일 4종 + 단위·통합 | 재사용 (문장 묶음 분리만 추가) |
| FK·제약 | 같은 생성기 (`foreignKeys`, `relationForeignKeys`, 키 구성 중복 제거) | 참조 테이블명 FK, 사용자 정의(복합) FK, UNIQUE·CHECK | 4종 | 테이블 1개 | 같은 키 구성의 UNIQUE·고유 인덱스는 한 번만 | 위와 같음 | 위와 같음 | 재사용 |
| 인덱스 DDL | `TableDdlGenerator.indexStatement` / `indexDdl` (M-14) | 일반·UNIQUE·복합·유형·표현식·부분·정렬·NULLS·INCLUDE | 4종 (고급 옵션은 PostgreSQL 만) | 인덱스 1개 / 테이블 | 테이블 DDL 안에서 CREATE TABLE 뒤 | 위와 같음, 인덱스 변경 요청은 미반영 | M-14 통합·PostgreSQL 실행 | 재사용 |
| VIEW DDL | `ViewDdlGenerator.generate` / `generateAll` (M-15) | CREATE VIEW + COMMENT | 4종 (실행 검증은 PostgreSQL) | VIEW 1개 / 프로젝트·스키마의 VIEW 전체 | 등록된 VIEW 참조 기준 위상 정렬, 범위 밖·미확인 참조 경고 | 확정 정의 기준 (변경 요청 미반영), 승인 대기 VIEW 안내 | M-15 통합·PostgreSQL 실행 | 재사용 (테이블 범위 경고·순환 이름 조회 추가) |
| 프로젝트·스키마 단위 Export | VIEW 만 있음 (VIEW 관리의 "DDL 내보내기") | VIEW | — | 프로젝트·스키마 | — | — | — | **신규 개발** (통합) |
| DBMS 선택 | 없음 (프로젝트 설정 고정) | — | — | — | — | — | — | **신규** (요청값, 비우면 프로젝트 설정) |
| 다운로드 | VIEW DDL 대화상자의 브라우저 저장 | — | — | — | — | — | — | 재사용 (`downloadText`·`copyText` 로 꺼냄) |
| 승인 기준 DDL | 없음 — [결정 필요로 남아 있었다](model-integrity-hardening.md#4-ddl-과-승인-상태) | — | — | — | — | — | — | **결정 후 신규** (2장) |
| 권한 | DDL 조회 API 는 모두 VIEWER, 프로젝트 범위 조회 | — | — | — | — | — | — | 재사용 |

화면 : 테이블 상세·인덱스 관리·VIEW 관리에 DDL 대화상자(`TableDdlView`)가 있고, 프로젝트 단위 Export 화면은 없었다.

## 2. 결정 사항 (2026-11-13, 사용자 결정)

| 쟁점 | 결정 |
|---|---|
| 승인 대기 항목 (hardening 4장의 선택지) | **기본은 A (설계용 DDL + 안내), 선택 모드로 B (승인 기준 : 범위 안에 승인 대기 항목이 있으면 만들지 않는다)**. C(빼고 만들기)는 하지 않는다. 승인 대기 변경 요청(VIEW·인덱스)은 어느 모드든 반영하지 않는다 — 확정된 정의 기준 |
| 화면 위치 | 물리 모델 관리 그룹의 새 메뉴 **DDL 내보내기** (`/ddlExport`, 메뉴 권한 `ddl-export` = VIEWER) |

분석으로 정한 것 (기존 계약을 따른다) :

| 쟁점 | 판단 |
|---|---|
| FK 분리 생성 (12.1) | 기존 생성기가 이미 FK 를 `ALTER TABLE` 로 따로 낸다. 개별 테이블 DDL 계약은 바꾸지 않았다 |
| 스키마 밖 참조 (12.2) | 경고만 한다. 기존 VIEW 내보내기(M-15)가 범위 밖 VIEW 참조를 경고하고, 개별 테이블 DDL 도 다른 테이블을 참조하는 FK 를 그대로 내는 것과 같다. 의존 객체를 끌어오거나 막지 않는다 |
| 미지원 옵션 (12.3) | 기존 생성기 정책 그대로 : 인덱스는 지우지 않고 `-- CREATE …` 주석 + 경고(건수는 따로 센다), VIEW 정의 SQL 이 대상 DBMS 검사를 통과하지 않으면 원문 그대로 + 경고. 몰래 빼는 문장은 없다 |
| 실행 가능성 (12.4) | PostgreSQL 만 실제 실행으로 확인했다. 다른 DBMS 는 응답 경고에 "실제 실행을 검증하지 않았습니다" 를 붙인다 |

## 3. 구조

```
GET /api/physical/ddl/export?schemaId=&dbType=&defaultSchemaName=&approvedOnly=   (VIEWER, 조회 전용)
  DdlExportRestController → DdlExportService.export (readOnly 트랜잭션)
    범위 : 프로젝트 전체 또는 스키마 하나 (다른 프로젝트 스키마 → SCHEMA_IS_NOT_DEFINED)
    테이블마다 PhysicalModelService.ddlExtras → TableDdlGenerator.parts  (개별 DDL 과 같은 입력)
    VIEW 는 ViewDdlGenerator.generateAll(…, 범위 안 테이블 ID)
  → DdlExportDto { dbType, scope, schemaName, approvedOnly, ddl, counts, warnings, errors, pendingApprovals }
```

- **재사용 방식** : `TableDdlGenerator` 의 본문을 `parts()` 로 옮겨 문장 묶음(header · CREATE TABLE · 인덱스 · FK · COMMENT)을 돌려주게 했고, `generate()` 는 그 묶음을 예전 순서대로 잇는다. 골든 파일 4종이 글자 하나 바뀌지 않았고, 묶음을 이으면 `generate()` 와 같다는 단위 테스트를 더했다. 참조 테이블 PK 조회(`referencedPkColumns`)는 테이블 서비스의 것을 공개해 같이 쓴다. 새 DDL 문법 코드는 없다.
- **미리보기 = 다운로드** : 화면은 응답의 `ddl` 을 그대로 보여 주고, 복사·`.sql` 저장도 같은 문자열을 쓴다 (서버를 다시 부르지 않는다). 파일명 `lexorma-ddl-{프로젝트}[-{스키마}]-{dbms}.sql` (영문·숫자·`_`·`-` 만 남기고, 남는 것이 없으면 `project`/`schema`).
- **결정적 출력** : 시각 등 비결정 값을 넣지 않는다. 테이블은 스키마(기본 먼저, 이름순) → 테이블명, VIEW 는 위상 순서(같은 단계는 이름순). 같은 모델이면 같은 DDL (테스트).

## 4. 생성 순서

```sql
-- ========================================
-- Lexorma Integrated DDL Export
-- DBMS: PostgreSQL
-- Scope: PROJECT            (또는 SCHEMA 이름)
-- Model: DESIGN (승인 대기 항목 포함)   (또는 APPROVED)
-- ========================================

-- 1. SCHEMAS        CREATE SCHEMA (기본 스키마가 아닌 것)
-- 2. TABLES         테이블마다 CREATE TABLE (PK·UNIQUE·CHECK 포함) + 그 테이블의 COMMENT
-- 3. INDEXES        CREATE [UNIQUE] INDEX
-- 4. FOREIGN KEYS   ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY
-- 5. VIEWS          CREATE VIEW (+ COMMENT), 참조되는 VIEW 먼저
```

빈 구역은 내지 않는다. **인덱스를 FK 보다 먼저** 둔다 : FK 가 고유 인덱스(UNIQUE 제약이 아닌)를 참조할 수 있고(M-1 `referencesKey`), PostgreSQL 은 참조 대상에 고유 인덱스가 먼저 있어야 FK 를 만든다. 개별 테이블 DDL 의 순서(테이블 → 인덱스 → FK)와도 같다. 테스트 모델에 고유 인덱스를 참조하는 복합 FK 를 넣어 PostgreSQL 에서 확인했다.

- **스키마** : 기본 스키마는 접속 계정의 스키마라 만들지 않는다 ("스키마명 포함" 으로 붙이는 이름도 이미 있다고 본다). MySQL·MariaDB 의 `CREATE SCHEMA` 는 데이터베이스를 만든다. Oracle 은 스키마가 사용자(계정)라 문장 대신 주석과 경고를 낸다.
- **COMMENT** : PostgreSQL·Oracle 은 테이블 바로 뒤 `COMMENT ON`, MySQL·MariaDB 는 생성기 그대로 컬럼·테이블 옵션.

## 5. FK

- 모든 FK 는 모든 테이블·인덱스 뒤의 `ALTER TABLE` 이라 **부모·자식, 자기 참조, 상호 참조(순환), 복합 FK, 스키마 간 FK** 모두 순서와 관계없이 실행된다. 순환 FK 때문에 실패하지 않는다 (PostgreSQL 실행 확인).
- 참조 대상 누락·PK 없음·복합 PK 참조(참조 테이블명 FK)·키가 아닌 대상(사용자 정의 FK)은 기존 생성기가 FK 를 만들지 않고 경고한다 (그대로).
- 스키마 단위 Export 에서 대상이 범위 밖인 FK 는 넣고 경고한다 : "이 스크립트 밖의 테이블을 참조하는 FK 가 있습니다 … : 원본 → 대상".

## 6. 인덱스·키 중복 방지

- 테이블 안 : 기존 생성기 그대로 — PK·고유값 컬럼·UNIQUE 제약·고유 인덱스는 같은 컬럼 구성이면 한 번만, 일반 인덱스는 같은 컬럼 순서면 한 번만, 고급 인덱스는 같은 정의면 한 번만 (M-14 D-19 이후 인덱스는 모두 `TB_TBL_IDX` 에서 나온다). PK·UNIQUE 제약이 만드는 인덱스는 따로 `CREATE INDEX` 하지 않는다.
- 테이블 사이 : 문장은 소속 테이블에서만 나와 겹치지 않는다. 대신 **이름 충돌**을 본다 (DBMS 길이 제한으로 자른 뒤) — PostgreSQL·Oracle 의 같은 스키마 안 테이블·VIEW·인덱스 이름(PostgreSQL 은 PK·UNIQUE 제약 이름 포함), Oracle 의 제약 이름. 겹치면 경고한다 (예 : Oracle 30자에서 같아지는 두 인덱스). MySQL·MariaDB 는 인덱스·제약 이름이 테이블 단위라 보지 않는다.
- PostgreSQL 실행 테스트 : 제약이 아닌 인덱스 수 = 응답의 INDEX 건수, PK 수 = 테이블 수.

## 7. VIEW

- `ViewDdlGenerator.generateAll` 그대로 : 정의 SQL 원문 보존(생성기는 `CREATE VIEW 이름 AS` 와 끝의 `;` 만), 등록된 VIEW 참조 기준 위상 정렬(Kahn, 같은 단계 이름순), 범위 밖 VIEW·미확인(외부) 참조 경고.
- 통합 Export 에서 더한 것 : 범위 안 테이블 목록을 넘겨 **범위 밖 테이블을 참조하는 VIEW** 를 경고한다. VIEW 만 내보내는 기존 경로는 null 을 넘겨 예전과 같다.
- **순환** : 등록 경로는 순환을 막는다. 그래도 순환이 있으면(직접 수정한 데이터 등) 기존 VIEW 내보내기는 끝에 붙이고 경고하지만, 통합 Export 는 순서를 정할 수 없으므로 **오류**로 보고 DDL 을 주지 않는다.
- **한계** : 순서는 사용자가 등록한 참조 정보 기준이다. 정의 SQL 을 해석하지 않으므로(D-27) 실제 SQL 과 참조 정보가 다르면 실행이 실패할 수 있다. VIEW 가 있으면 이 안내를 경고로 늘 붙인다.

## 8. 경고와 오류

| 구분 | 내용 | 결과 |
|---|---|---|
| 오류 | 컬럼 없는 테이블 (CREATE TABLE 을 만들 수 없음) | `ddl` 빈 문자열 |
| 오류 | VIEW 순환 (생성 순서를 정할 수 없음) | 〃 |
| 오류 | 승인 기준 모드인데 범위 안에 승인 대기 항목이 있음 (목록은 `pendingApprovals`) | 〃 |
| 요청 거절 (400) | 잘못된 DBMS (`INVALID_PHYSICAL_MODEL`), 스키마명 형식 (`INVALID_SCHEMA_NAME`), 다른 프로젝트·없는 스키마 (`SCHEMA_IS_NOT_DEFINED`) | 예외 |
| 경고 | 생성기 경고(테이블명 · 내용 : 길이 미지정·PK 없음·FK 를 만들지 않은 이유·타입 차이·주석 처리한 인덱스·VIEW COMMENT 불가·정의 SQL 원문 사용 등) | DDL 생성 |
| 경고 | 범위 밖 테이블을 참조하는 FK·VIEW, 범위 밖 VIEW, 미확인(외부) 참조 | 〃 |
| 경고 | VIEW 순서는 참조 정보 기준, 승인 대기 VIEW·인덱스 변경 요청 n건 미반영, 이름 충돌, Oracle 스키마, 내보낼 객체 없음, PostgreSQL 외 DBMS 실행 미검증 | 〃 |

기본(설계용) 모드에서 승인 대기 항목은 오류가 아니고 `pendingApprovals`(테이블 / 컬럼 `테이블.컬럼` / 관계(FK) / 인덱스 / 제약조건 / VIEW)로 알린다 — 개별 DDL 과 같은 안내다.

## 9. DBMS 별 지원 범위

| DBMS | 생성 | 실제 실행 검증 | 비고 |
|---|---|---|---|
| PostgreSQL | 지원 | **완료** (18.6, 임시 DB) | 테스트 모델 + LEXORMA 예제 모델 전체 |
| Oracle | 지원 | 미검증 | 스키마 생성문 없음(주석·경고), 이름 30자 자름 → 충돌 경고 |
| MySQL | 지원 | 미검증 | `CREATE SCHEMA` = 데이터베이스, 고급 인덱스는 주석, VIEW COMMENT 없음 |
| MariaDB | 지원 | 미검증 | MySQL 과 같음 |

## 10. API·화면

- API : `GET /api/physical/ddl/export` — VIEWER, 프로젝트는 로그인 세션의 프로젝트, 조회 전용(CSRF 불필요한 GET, 세션 인증 그대로). 응답은 1장의 `DdlExportDto`.
- 화면 `DDL 내보내기` : 범위(프로젝트 전체 / 스키마), 대상 DBMS(프로젝트 설정 / 4종), 스키마명 포함, 승인 기준 → [DDL 생성]. 결과 영역에 DBMS·범위·모드 라벨과 SCHEMA·TABLE·INDEX·FK·VIEW 건수(주석 처리 INDEX 는 있을 때만), 오류(빨강)·경고·승인 대기 목록, SQL 미리보기, [내보내기 (.sql)]·[복사], 파일명. 오류가 있으면 복사·저장이 꺼진다. DDL 표시·경고 모양은 기존 DDL 대화상자 스타일을 쓴다 (`ddl_export.css` 는 조건 영역·건수·오류만).
- 한국어/영어, Light/Dark, 1440~375px 대응. 메뉴 권한 `ddl-export` 를 `MenuAuthorization` 에 더했다 ([권한 점검 기록](ui-action-authorization-audit.md)).

## 11. 테스트 결과

| 구분 | 결과 |
|---|---|
| 단위 | `TableDdlGeneratorTest` 묶음 = generate (4 DBMS), 골든 파일 4종 그대로 · `ViewDdlGeneratorTest` 순환 이름·범위 밖 테이블 경고(기존 VIEW 내보내기는 그대로) |
| H2 통합 `DdlExportIntegrationTest` (6) | 순서(구역·문장)·FK 7종(부모·자기·순환·복합·고유 인덱스 참조·스키마 간)·PK 6개·제약 이름 중복 없음·고급 인덱스 원문·VIEW 위상 순서·정의 SQL 원문·결정적 출력·개별 DDL 형식 유지·스키마명 포함 / 스키마 단위(범위 밖 FK·VIEW 경고, 기본 스키마만) / 승인 기준·설계용·변경 요청 미반영 / DBMS 선택(MySQL 주석 인덱스·Oracle 스키마·잘린 이름 충돌·잘못된 DBMS·스키마명) / 오류(컬럼 없음·VIEW 순환)·빈 프로젝트 / 격리(다른 프로젝트 객체·스키마)·VIEWER 허용·NOT_APPROVE 거절 |
| PostgreSQL 실제 실행 (같은 클래스, `LEXORMA_TEST_POSTGRES_URL`) | 빈 임시 스키마에서 스크립트 한 번에 실행 → 테이블 수·PK 6·FK 7(이름)·UNIQUE·CHECK·고급 인덱스 정의·제약 아닌 인덱스 수 = 응답 건수·VIEW 5·VIEW 의존(pg_depend)·데이터 넣고 VIEW 조회·COMMENT·FK 위반 거절. 끝나면 임시 스키마만 지운다 |
| PostgreSQL 예제 모델 | 화면에서 내려받은 LEXORMA 예제 프로젝트 통합 DDL(+ SALES 스키마, VIEW 2)을 임시 DB 에서 `--single-transaction`·`ON_ERROR_STOP` 으로 실행 → 오류 없음, 테이블 59·VIEW 2·FK 51·제약 아닌 인덱스 91 (= 화면 건수) |
| 전체 빌드 | H2 `./gradlew build` 통과, `test --rerun` 602건 실패 0 (건너뜀 22 : PostgreSQL 전용 9·성능 2·원본 DB 11) · PostgreSQL 18.6 임시 DB 전체 602건 실패 0 (건너뜀 13 : 성능 2·원본 DB 11) |
| React | `npm run typecheck`·`npm run build` 통과 |
| 브라우저 (헤드리스 Chrome, h2-local) | ADMIN : 메뉴·범위 선택지·프로젝트/SALES 스키마(범위 밖 VIEW·테이블 경고)·MySQL+스키마명 포함·승인 기준, 복사(클립보드 내용 = 미리보기), `.sql` 다운로드(파일 내용 = 미리보기, 파일명 `lexorma-ddl-LEXORMA-postgresql.sql`), 1440·1024·768·500·375px 가로 넘침 없음, 다크 모드. 영어(en-US) 메뉴·버튼·서버 경고 영어. USER·VIEWER 메뉴·생성·복사·저장 가능. 콘솔 오류·실패 응답 0 (로그인 전 비밀번호 변경 단계의 의도된 401 제외) |

Chrome 확장은 이 PC 의 localhost 에 연결되지 않아(M-15 와 같음) 로컬 헤드리스 Chrome(DevTools 프로토콜)로 확인했다.

## 12. 미검증·후속

> **2026-11-13 스키마 단위 이름 이후** ([기록](schema-scoped-naming.md#5-ddlerd)) : 참조 테이블명은 테이블마다 자기 스키마 기준으로 정한다(`TableDesignService.referencedKeys`, 모호하면 FK 를 만들지 않고 경고). "스키마명 포함" 이 기본이 아닌 스키마의 이름과 같아 같은 이름 객체가 겹치면 오류로 만들지 않는다 (겹치지 않으면 경고). 아래 정책 후속의 첫 항목은 이것으로 처리했다.

- **미검증** : Oracle·MySQL·MariaDB 실제 실행 (생성 결과만 테스트).
- **성능** : 테이블마다 관계·인덱스·제약을 조회한다(`ddlExtras` 재사용, 테이블 수 × 몇 개의 조회). 예제 모델(59 테이블)은 즉시 응답하지만 큰 모델의 측정·일괄 조회는 후속.
- **M-11 나머지** : 모델 단위 테이블·컬럼 엑셀 Export, ERD 이미지(PNG/SVG), 재가져오기 형식·프로젝트 간 이동(D-12·D-18), 선택 테이블 Export.
- **정책 후속** : 스키마 단위 TABLE/VIEW 식별자(D-25, M-4)가 바뀌면 이름 충돌 검사·참조 조회(`referencedPkColumns` 는 프로젝트 단위 이름)를 함께 바꾼다. 모델 버전 선택 Export 는 하지 않았다.
- **한계** : VIEW 순서는 등록된 참조 정보 기준이며 실제 실행 성공을 보장하지 않는다. "스키마명 포함" 과 기본이 아닌 스키마 이름이 같으면 확인하지 않는다.
