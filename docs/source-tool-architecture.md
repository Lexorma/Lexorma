# 원본 DB 연동 · 내부 도구 아키텍처 (lexorma-source)

> 2026-11-12. AI-1b 보완 작업에서 정한 원본 DB 연동 구조다. 이후 AI-1c·AI-2·AI-3 과 그 뒤의 AI 기능도 이 원칙을 따른다.
> 저장 모델의 세부(테이블·식별자 정책·삭제)는 [source-catalog-ai1b.md](source-catalog-ai1b.md), 전체 단계는 [AI 메타데이터 로드맵](ai-metadata-roadmap.md).

## 1. 원칙

1. **Lexorma 는 단일 서버로 운영한다.** React SPA, Spring Boot, Lexorma 자체 DB 로 이루어진 하나의 JAR(Docker 이미지)이다. 메타데이터 수집 서버, MCP 서버, 도구 실행용 프로세스를 따로 두지 않는다.
2. **원본 DB 메타데이터는 내부 JDBC 도구로 수집한다.** Lexorma 서버가 직접(JDBC) 원본 DB 에 접속한다. 접속은 도구가 실행되는 동안만 열고 끝나면 닫는다. 원본 DB 를 계속 폴링하거나 세션을 유지하지 않는다.
3. **수집 결과는 Lexorma 자체 DB 에 저장한다** (원본 카탈로그 `TB_SRC_*`). 모델링·ERD·데이터 사전·AI 분석은 이 저장본만 읽는다.
4. **AI 기능은 내부 Tool Calling 중심으로 만든다.** LLM 은 서버가 허용한 도구만 부를 수 있고, 그 도구는 Lexorma 자체 DB 를 읽는다. LLM 은 선택 기능이다 — 없어도 Lexorma 의 기본 기능(수집 포함)은 동작한다.
5. **AI 는 원본 DB 에 임의 SQL 을 실행하지 않는다.** 원본 DB 에 접속하는 도구는 AI 에 공개하지 않는다. 원본 DB 에는 메타데이터를 읽는 정해진 조회만 보낸다 (DML·DDL·입력받은 SQL 실행 없음).
6. **MCP 는 나중에 필요할 때 더하는 선택 연동이다.** 지금 구현하지 않고, 기본 실행 경로도 필수 구성 요소도 아니다. 더하더라도 JDBC 수집을 대체하지 않는 또 하나의 Connector 로 둔다 (4장).
7. **`lexorma-source` 와 `lexorma-data` 는 서로 의존하지 않는다** (2장).

## 2. 모듈

```text
lexorma-common
    ↑
lexorma-core          인증·권한·프로젝트, 암호화, 실행 컨텍스트, 로그 정책, 프로젝트 삭제 이벤트
    ↑
    ├── lexorma-system      시스템 관리 (사용자·프로젝트·시스템 로그)
    ├── lexorma-operation   운영 관리 (공지)
    ├── lexorma-data        데이터 표준화, 물리·논리 모델링, ERD, 이력·버전
    └── lexorma-source      원본 DB 연결, 원본 카탈로그, 내부 수집 도구   ← AI-1b 보완에서 추가
    ↑
lexorma-app           조립·실행 (bootJar, Flyway, 화면 리소스)
```

- 같은 층의 네 모듈(system·operation·data·source)은 서로를 참조하지 않는다. `lexorma-core` 는 위의 어떤 모듈도 참조하지 않는다. Gradle 의존성이 컴파일을 막고, `ModuleBoundaryTest` 가 소스·빌드 파일에서 다시 확인한다.
- **모듈 사이의 연동은 core 의 계약으로 한다.**
  - 프로젝트 영구 삭제 : `system` 의 삭제 서비스가 core 의 `ProjectDeletedEvent` 를 발행하고, `data`(`ProjectDataCleanup`)·`operation`·`source`(`SourceCatalogCleanup`)가 각자 같은 트랜잭션에서 자기 데이터를 지운다. 이미 있던 구조를 그대로 썼다 (새 계약을 만들지 않았다). 원본 카탈로그는 다른 모듈의 테이블과 FK 로 이어져 있지 않아 리스너 사이의 순서가 필요 없다.
  - 권한 : core 의 `ExecutionContextVerifier`·`ProjectAuthority`.
  - 삭제와 겹친 등록 : core 의 `ProjectWriteGuard` (JPA 저장은 자동, JDBC 직접 적재는 `guard(projectId)` 호출).
- **원본 카탈로그 ↔ 물리 모델의 매핑·비교(AI-1d, M-4, M-10 ②)** 는 한 모듈이 다른 모듈의 엔티티·Repository 를 직접 쓰지 않게 한다. 필요한 조회는 core 에 계약(인터페이스와 값 객체)을 두고 각 모듈이 구현하며, 둘을 함께 쓰는 유스케이스는 위의 조립 계층에서 부른다. 지금은 그런 연동이 없어 계약을 미리 만들지 않았다.

## 3. 내부 도구

### 3.1 계약 (`lexorma-source` 의 `source/tool`)

| 타입 | 역할 |
|---|---|
| `SourceMetadataTool<I, O>` | 도구 하나 : `name()`, `supports(DbType)`, `inputType()`, `execute(SourceToolContext, I)`. DBMS 마다 구현을 따로 둘 수 있다 |
| `SourceTools` | 도구 이름 : 연결 확인, 스키마·테이블·컬럼·제약조건·인덱스·관계 조회 |
| `SourceToolRegistry` | 등록된 도구(Spring 빈)를 이름·DBMS 로 찾는다. 같은 이름·같은 DBMS 의 구현이 둘이면 기동을 막는다 |
| `SourceToolExecutor` | 도구를 실행하는 **유일한 길**. 확인을 모두 거친 뒤 컨텍스트를 만들어 넘긴다 |
| `SourceToolContext` | 실행 한 번의 컨텍스트 : 확인된 실행 컨텍스트, 연결, DBMS, 고정 드라이버, 서버가 만든 JDBC URL, 계정, 허용된 옵션, 비밀번호(부를 때 복호화). 실행기만 만들 수 있다 |

### 3.2 실행 흐름

```text
사용자 요청 (최신화 버튼)        비동기 작업            정기 배치
   └── ExecutionContext.user    └── .async(...)       └── .scheduled(...)
                     \               |               /
                      SourceToolExecutor.execute(도구 이름, 컨텍스트, 연결 ID, 입력)
                        1. 실행 컨텍스트 재검증 (DB) : 프로젝트 존재·활성, 요청자가 지금도 관리자 또는 ADMIN
                           (정기 배치는 요청자가 없어 프로젝트 상태만)
                        2. 연결 : 그 프로젝트의 것만, 사용 중지면 거절
                        3. 접속 범위 : 운영자의 호스트 허용 범위를 실행 시점에 다시 확인
                        4. 도구 : 이름·DBMS 로 찾고 입력 타입 확인 (미지원 DBMS 는 거절)
                        5. SourceToolContext 생성 (JDBC URL = 검증한 항목으로 서버가 조립)
                      → SourceMetadataTool.execute(context, input)
                      → (AI-2) 원본 DB 메타데이터 → SourceCollectionSpec(스키마별 완결 여부 + SourceCatalogSpec)
                      → (AI-1c) SourceCollectionService.apply : 한 트랜잭션에서 현재 카탈로그·버전·변경 이력 반영 (source-catalog-versioning.md)
```

- LLM 이 끼지 않는다. 최신화는 AI 사용 여부와 상관없이 된다.
- 원본 카탈로그를 바꾸는 운영 경로는 수집 반영(`SourceCollectionService.apply`) 하나다. 원본 변경(버전·이력)은 마지막 확정 버전과 비교하고, 현재 카탈로그는 수집 결과로 맞춘다 ([source-catalog-versioning.md](source-catalog-versioning.md) 1.1·4.3).
- 도구는 프로젝트·연결을 스스로 고르지 않는다. 컨텍스트가 가리키는 연결에만 접속한다.
- 비밀번호는 도구가 `password()` 를 부를 때 복호화된다. 컨텍스트의 `toString()` 에 계정·비밀번호가 나오지 않는다.
- **실행 방식은 요청이 고르지 않는다.** 컨텍스트는 서버 코드가 만든다 : HTTP 요청은 로그인 세션으로 `user(...)` 만 만들고, `async(...)` 는 그 요청에서 저장한 요청자·프로젝트로(실행할 때 요청자의 지금 권한을 다시 본다), `scheduled(...)` 는 배치 스케줄러만 만든다.
  정기 배치는 요청자가 없어 프로젝트 상태만 보므로, 요청을 받는 쪽(컨트롤러)이 배치·비동기 컨텍스트를 만들면 권한 확인을 건너뛰게 된다 → `ModuleBoundaryTest.executionPaths` 가 막는다.
  배치를 켜는 일(프로젝트·연결·일정 등록)은 사람의 요청이므로 그때 `verifyProjectManager` 로 확인한다 (AI-2 이후). 배치의 실행 주체는 작업 ID(`jobId`, 필수)와 감사자 `SCHEDULER` 로 남는다.
- AI 의 Tool Calling 목록에는 원본 DB 도구가 없다 : `ai` 패키지는 `com.lexorma.source` 를 참조하지 않는다 (`ModuleBoundaryTest.executionPaths`).

### 3.3 JDBC 접속 규칙 (`SourceJdbcSettings`, `SourceHostPolicy`)

- **JDBC URL 을 입력받지 않는다.** 호스트·포트·데이터베이스명(Oracle 은 서비스명·SID)을 따로 받아 형식을 검증하고 서버가 URL 을 만든다. URL 구분 문자(`/ ? ; & @ :` 등)는 각 항목에 들어갈 수 없다. 계정·비밀번호는 URL 에 넣지 않는다.
- **드라이버는 DBMS 마다 정해진 하나**다 (PostgreSQL `org.postgresql.Driver`, Oracle `oracle.jdbc.OracleDriver`, MySQL `com.mysql.cj.jdbc.Driver`, MariaDB `org.mariadb.jdbc.Driver`). 드라이버 클래스를 입력받지 않는다.
- **연결 옵션은 허용 목록**의 이름·값만 받는다. 지금은 전송 암호화 방식만 연다 (PostgreSQL `sslmode`, MySQL·MariaDB `sslMode`, Oracle 없음). 비밀값·파일 읽기·다른 주소로의 접속·임의 클래스 로딩을 여는 옵션은 받지 않는다.
- **접속 가능한 호스트**는 운영자가 `LEXORMA_SOURCE_ALLOWED_HOSTS`(이름 또는 `*.` 접미사, 쉼표 구분)로 좁힐 수 있다. 연결을 등록·수정할 때와 도구를 실행하기 직전에 확인한다. 비우면 제한하지 않는다.
- 타임아웃 기본값 : 접속 10초, 조회 30초 (`SourceToolContext` 의 상수). 실제 적용과 읽기 전용 연결 설정(`setReadOnly`), DNS 해석 결과의 주소 대역 확인은 실제 접속을 만드는 AI-2 에서 한다.
- 원본 DB 계정은 메타데이터 조회 권한만 가진 읽기 전용 계정을 권한다 (등록 화면·운영 문서에서 안내 — AI-2).

### 3.4 DBMS 별 확장

```text
SourceToolExecutor
   └── SourceToolRegistry.find(도구 이름, DbType)
          ├── PostgreSQL 구현   (information_schema / pg_catalog)
          ├── Oracle 구현       (ALL_* 딕셔너리 뷰)
          ├── MySQL 구현        (information_schema)
          └── MariaDB 구현      (information_schema)
                 └── 결과는 DBMS 와 상관없는 공통 모양 : SourceCatalogSpec
```

- 결과는 기존 `SourceCatalogSpec` 을 쓴다 (원본 이름 그대로, 따옴표 여부는 알 때만, 타입 표현 원문, NULL·기본값, COMMENT, PK·FK·UNIQUE·CHECK, 인덱스, 복합 키 순서, 다른 스키마 참조).
- DBMS 차이를 억지로 없애지 않는다. 비교용 이름은 저장할 때 `SourceIdentifiers` 가 DBMS·연결의 규칙으로 만든다.

### 3.5 AI Tool Calling 과의 관계

| 구분 | 예 | AI 에 공개 |
|---|---|---|
| Lexorma 자체 DB 조회 도구 | 테이블·컬럼·관계, 표준 용어·도메인, 데이터 사전, 모델 변경 이력, 원본 카탈로그(저장본) | 허용 목록과 권한 확인을 거쳐 공개할 수 있다 (AI-3·AI-4) |
| 원본 DB 도구 (`SourceMetadataTool`) | 연결 확인, 원본 DB 직접 조회·전체 수집 | **공개하지 않는다** |
| 인증 정보 | 비밀번호 조회·변경 | 공개하지 않는다 |
| SQL 실행 | 원본 DB 에 SQL 실행 | 만들지 않는다 |

AI 에 공개하는 도구 목록은 `SourceToolRegistry` 와 별개로 만든다. AI 기능을 별도 모듈로 둘지는 AI-3 에서 실제 의존성(어느 모듈의 조회를 묶는지)을 보고 정한다.

## 4. MCP (후속 선택 기능)

```text
Source Connector
├── JDBC (내부 도구)   ← 기본이자 현재 유일한 방식
└── MCP                ← 필요해지면 선택적으로 추가
```

- 지금 구현 대상이 아니다. MCP 전용 테이블·서버 설정·클라이언트·의존성을 미리 만들지 않는다 (AI-1b 첫 구현의 `TB_MCP_SRVR` 와 관련 코드는 이 보완에서 제거했다).
- 더한다면 : MCP 를 켜지 않아도 모든 기본 기능이 동작하고, JDBC 수집을 대체하지 않으며, `SourceMetadataTool` 의 또 다른 구현(같은 결과 모양 `SourceCatalogSpec`)으로 붙여 전체 구조를 바꾸지 않는다.

## 5. JDBC 드라이버

| DBMS | 드라이버 | 라이선스 (도입 시 다시 확인) | 현재 배포본 |
|---|---|---|---|
| PostgreSQL | `org.postgresql:postgresql` | BSD-2-Clause | 포함 (Lexorma 자체 DB 용으로 이미 있다) |
| MariaDB | `org.mariadb.jdbc:mariadb-java-client` | LGPL-2.1 | 없음 |
| MySQL | `com.mysql:mysql-connector-j` | GPL-2.0 + Universal FOSS Exception | 없음 |
| Oracle | `com.oracle.database.jdbc:ojdbc11` | Oracle Free Use Terms and Conditions | 없음 |

- 이 보완에서는 드라이버를 더하지 않았다 (실제 접속은 AI-2). 연결 응답의 `driverAvailable` 이 드라이버가 배포본에 있는지 알려 준다.
- MySQL·MariaDB·Oracle 드라이버를 기본 배포본에 넣을지, 운영자가 넣게 할지는 라이선스 조건(특히 GPL 계열의 재배포)과 함께 AI-2 착수 전에 정한다.
