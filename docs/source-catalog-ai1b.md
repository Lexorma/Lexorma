# AI-1b 외부 DB 연결과 원본 메타데이터 카탈로그

> 2026-11-12. AI 메타데이터 로드맵 1단계의 두 번째 작업([작업 분할](ai-metadata-roadmap.md#1단계-메타데이터-모델-보강--변경-이력--상세-설명-분리)).
> 외부 DB 에서 수집할 원본 메타데이터를 **물리·논리 모델과 따로** 저장하는 구조를 만들었다.
> **같은 날 아키텍처를 보완했다 (10장)** : 첫 구현은 외부 MCP 서버를 거쳐 수집한다고 보고 MCP 서버 설정(`TB_MCP_SRVR`)을 두었지만,
> 별도 서버를 요구하지 않도록 **Lexorma 가 직접 JDBC 로 접속하는 구조**로 바꾸고 코드를 새 모듈 **`lexorma-source`** 로 옮겼다. 원본 카탈로그 모델은 그대로다.
> 원칙과 내부 도구 구조는 [source-tool-architecture.md](source-tool-architecture.md).
> **아직 원본 DB 에 접속하지 않는다** — 연결 설정·저장 모델·도구 계약·조회 API 까지다. 수집 도구(AI-2)와 화면은 없다.
> **2026-11-13 AI-1c** 에서 이 카탈로그를 "현재 상태" 로 두고 수집 실행·테이블/스키마 버전·변경 이력·과거 상태 조회를 더했다 → [source-catalog-versioning.md](source-catalog-versioning.md). 이 문서의 9개 테이블 구조는 그대로이고, AI-1c 에서 바뀐 부분은 각 절에 표시했다.

## 1. 구조

```text
프로젝트
 ├─ 외부 DB 연결 (출처 접속)            TB_SRC_CNTN      DBMS, 호스트·포트·데이터베이스명, 계정, 암호화한 비밀번호, 허용된 옵션, 식별자 비교 규칙
 │   └─ 원본 카탈로그
 │       ├─ 이름 공간 (카탈로그·스키마)  TB_SRC_SCHM
 │       └─ 테이블·뷰                    TB_SRC_TBL       원본 COMMENT
 │           ├─ 컬럼                     TB_SRC_COL       원본 COMMENT, 타입 표현 그대로
 │           ├─ PK·UNIQUE·CHECK          TB_SRC_CNSTRT(+_COL)
 │           ├─ 인덱스                   TB_SRC_IDX(+_COL)
 │           └─ FK                       TB_SRC_REL(+_COL)   다른 스키마의 테이블 참조 가능
 └─ 물리·논리 모델 (lexorma-data, 이어져 있지 않다)
```

- **이름** : 테이블 이름의 `SRC`(출처)는 원본 DB 쪽을 뜻한다. `ORGNL`(원본)은 관계의 "원본 테이블"(`ORGNL_TBL_SN`)로 이미 쓰고 있어 겹치지 않게 했다. `TB_SRC_CNTN` 의 한글명 "출처 접속" 이 문서·화면에서 말하는 **외부 DB 연결**이다.
- **원본 카탈로그 ≠ 물리 모델.** 원본 카탈로그는 외부 DB 에서 수집한 사실이고 사용자가 고치지 않는다. 물리 모델(`TB_SCHM`·`TB_TBL`·`TB_COL` …)은 사용자가 설계하는 데이터다. 두 쪽은 서로를 참조하지 않으며(FK 없음), 한쪽의 변경·삭제가 다른 쪽을 바꾸지 않는다. 명시적 매핑은 AI-1d 에서 만든다.
- 코드는 **`lexorma-source`** 모듈에 있다 : `connection/`(연결 설정, JDBC 규칙), `catalog/`(카탈로그 엔티티·적재·조회), `tool/`(내부 도구 계약), `cleanup/`(삭제). `lexorma-data` 와 서로 의존하지 않는다.

## 2. 외부 DB 연결 (`TB_SRC_CNTN`)

- 프로젝트 소유. 한 프로젝트에 여러 개(개발·운영·연계 DB).
- **저장하는 것** : 연결명, DBMS(`DbType` : PostgreSQL·Oracle·MySQL·MariaDB), 호스트, 포트, 데이터베이스명과 그 종류(`DATABASE` / Oracle 의 `SERVICE_NAME`·`SID`), 계정, 암호화한 비밀번호, 허용된 연결 옵션(JSON), 식별자 비교 규칙, 사용 여부, 설명, 감사 정보.
- **저장하지 않는 것** : JDBC URL (실행할 때 검증한 항목으로 서버가 만든다), 드라이버 클래스(DBMS 마다 고정).
- **입력 검증 (DBMS 별)** : 호스트는 호스트명·IPv4·IPv6 만 (URL·포트·계정을 붙인 값 거절, IPv6 는 대괄호를 벗겨 저장), 포트 1~65535(없으면 DBMS 기본 포트), 데이터베이스명은 URL 구분 문자 없이, Oracle 은 서비스명(기본)·SID 그 밖에는 데이터베이스명, 계정 필수, 옵션은 허용 목록만(PostgreSQL `sslmode`, MySQL·MariaDB `sslMode` — 값도 목록에서, 드라이버 표기로 맞춰 저장), 운영자의 호스트 허용 범위(`LEXORMA_SOURCE_ALLOWED_HOSTS`).
- **비밀번호** : `SecretCipher`(AES-256-GCM)로 암호화해 `CNTN_PSWD_CN` 에 저장한다. 자리(AAD) = 용도 `source-connection` / 프로젝트 / 이 연결의 ID / `password`.
  연결을 먼저 저장해 ID 를 받은 뒤 같은 트랜잭션에서 암호화한다 — 암호화 키가 없으면 연결도 저장되지 않는다 (평문으로도, 비밀번호만 빠진 채로도 남지 않는다). 비밀번호 없는 계정이면 비워 둘 수 있다.
  - 응답에는 비밀번호가 없다 : 설정 여부(`passwordConfigured`)와 상태(`passwordStatus` = `NONE`·`CONFIGURED`·`REREGISTER_REQUIRED`·`KEY_NOT_CONFIGURED`)만 준다. 요청 DTO 의 `toString()` 도 가린다.
  - 수정 요청에서 비밀번호를 비우면 저장된 암호문을 그대로 둔다. 비밀번호는 다듬지 않는다 (앞뒤 공백도 값이다).
  - 다른 연결·다른 프로젝트의 암호문을 옮겨 넣거나 키를 잃어버리면 읽히지 않고 `REREGISTER_REQUIRED` 로 보인다 → 비밀번호를 다시 등록한다.
- 중복 : 프로젝트 안에서 이름이 하나뿐(대소문자 무시). 같은 주소·데이터베이스를 가리키는 연결은 여러 개 둘 수 있다 (계정이나 용도가 다를 수 있다).
- 카탈로그가 있는 연결은 DB 유형과 식별자 비교 규칙을 바꿀 수 없다 (비교용 이름이 그 규칙으로 만들어져 있다). 주소·계정·비밀번호·옵션·이름·설명은 바꿀 수 있다.
- **사용 여부** : 끄면 카탈로그는 남고 그 연결로 도구를 실행(접속)하는 것만 막는다.
- **권한** : 조회는 프로젝트 멤버(VIEWER 이상). 등록·수정·삭제는 **그 프로젝트의 관리자이거나 (그 프로젝트에 접근할 수 있는) ADMIN** — `ExecutionContextVerifier.verifyProjectManager` 가 지금의 DB 로 확인한다 (비활성 프로젝트, 멤버가 아닌 ADMIN, 내려간 역할은 거절). 다른 프로젝트의 연결은 ID 를 알아도 `SOURCE_CONNECTION_NOT_FOUND` 다.
  컨트롤러의 역할 조건은 USER 이상(`hasRole('USER')`)으로 VIEWER(조회 전용)와 미승인 계정만 걸러 낸다 — 프로젝트 관리자는 DBA 역할이 없어도 자기 프로젝트의 연결을 관리한다 (9.1).

## 3. 내부 도구 계약

원본 DB 에 접속하는 일은 모두 내부 도구(`SourceMetadataTool`)로 하고, 도구는 `SourceToolExecutor` 로만 실행한다. 실행기는 실행 직전에 실행 컨텍스트(프로젝트·요청자 권한)·연결(프로젝트 소속, 사용 여부)·호스트 정책·도구(DBMS 지원, 입력 타입)를 확인한 뒤 서버가 조립한 접속 정보를 담은 `SourceToolContext` 를 넘긴다. LLM 없이 실행되고, 원본 DB 도구는 AI 에 공개하지 않는다.
**지금 등록된 도구는 없다** — 계약·실행기·레지스트리만 있고 DBMS 별 수집 도구는 AI-2 에서 만든다. 자세한 것은 [source-tool-architecture.md](source-tool-architecture.md) 3장.

## 4. 원본 카탈로그

| 테이블 | 내용 |
|---|---|
| `TB_SRC_SCHM` | 이름 공간. 카탈로그(데이터베이스)와 스키마를 한 행에 둔다 — DBMS 마다 한쪽만 있거나 둘 다 있다 (PostgreSQL : 둘 다, Oracle : 스키마, MySQL·MariaDB : 카탈로그). `PATH_CN` 은 연결 안의 비교용 경로 |
| `TB_SRC_TBL` | 테이블·뷰(`TBL_TYPE_CD`). 원본 이름, 비교용 이름, 따옴표 여부(알 때만), 원본 COMMENT(`ANTN_CN`), 내용 해시 자리(`HASH_VL`, AI-1c 가 채운다), 원본 존재 여부(`EXST_YN`) |
| `TB_SRC_COL` | 컬럼. 원본 순서, **DBMS 가 준 타입 표현 그대로**(`character varying(320)`, `text[]`), 길이·정밀도·소수 자릿수, NULL 허용, 기본값 표현식 원문(`TEXT`), 원본 COMMENT |
| `TB_SRC_CNSTRT`, `_COL` | PK·UNIQUE·CHECK. 원본 이름, 구성 컬럼(순서대로), CHECK 조건식 원문(`TEXT`) |
| `TB_SRC_IDX`, `_COL` | 인덱스. 이름, 방식(원본 표현), 고유 여부, 구성 컬럼(순서대로) |
| `TB_SRC_REL`, `_COL` | FK. 참조하는 테이블, 참조되는 테이블(다른 스키마 가능), 컬럼 쌍(순서대로), 삭제·수정 규칙(원본 표현) |

- 표준 용어와 이어 두지 않는다. 뷰의 정의 SQL 은 저장하지 않는다.
- FK 가 가리키는 테이블이 카탈로그에 없으면(수집 범위 밖) 이어 두지 않고 스키마명·테이블명·컬럼명만 남긴다. 나중에 그 테이블이 적재된 뒤 관계를 다시 저장하면 이어진다.
- **같은 연결의 객체끼리만 이어진다** : 스키마·테이블·관계는 연결 일련번호를 함께 묶은 FK 로 참조한다(`FK_SRC_TBL_01`, `FK_SRC_REL_01`·`02`). 다른 연결의 스키마에 테이블을 넣거나 다른 연결의 테이블을 참조하는 관계는 DB 가 거절한다.
- 쓰는 경로 (사용자용 변경 API 없음) : 수집 결과는 **AI-1c 의 수집 반영**(`SourceCollectionService` → `SourceCatalogLoader`)이 한 트랜잭션에서 일괄 적재하고 버전·변경 이력을 남긴다. `SourceCatalogWriter` 는 AI-1b 의 한 건 단위 경로로 남겼다 — **테스트 전용**이다 (운영 코드에 호출이 없고 `ModuleBoundaryTest.catalogWritePaths` 가 막는다). 버전·이력을 남기지 않고 고친 테이블의 해시를 지우며, 원본 변경 판정은 마지막 확정 버전으로 하므로 Writer 의 값은 버전·이력에 들어가지 않는다 (다음 수집이 현재 카탈로그만 복구한다). 둘 다 `SourceCatalogSpec` 모양을 받고 같은 저장 규칙(`SourceCatalogRules`)을 쓴다. 같은 객체는 고치고 없으면 만든다. 제약조건·인덱스·관계는 테이블 단위로 통째로 바꾼다 (수집 반영은 바뀐 종류만).
- 관계는 참조되는 테이블의 카탈로그 이름(`RFRNC_CTLG_NM`, AI-1c 에서 더함)도 저장한다 : MySQL·MariaDB 의 다른 데이터베이스 참조를 나중에 다시 이을 수 있게.

### 4.1 모델이 담지 않는 것 (제한 사항)

임의로 바꿔 넣지 않고 받지 않거나 버리지 않는 쪽을 택했다.

- 표현식·함수 기반 인덱스의 표현식, 인덱스 컬럼의 정렬 방향(ASC·DESC)·부분 인덱스 조건·INCLUDE 컬럼 : 자리가 없다. 컬럼으로 이루어진 구성만 저장한다.
- 제약조건의 지연 여부(DEFERRABLE)·검증 여부, FK 의 MATCH 방식.
- 뷰 정의 SQL, Materialized View·Sequence·Synonym·Partition·Trigger·Procedure·Function (로드맵 U-21).
- 컬럼의 문자 집합·콜레이션, 자동 증가·생성 컬럼 표시 (타입 표현이나 기본값 원문에 들어 있으면 그 글자로만 남는다).
- 255자를 넘는 식별자, 20자를 넘는 삭제·수정 규칙 : 잘라서 저장하지 않고 거절한다.
- 제약조건·인덱스·관계에는 "원본 존재 여부" 가 없다 (테이블 단위로 통째로 바꾸므로 지금 있는 것만 남는다). 사라진 것은 AI-1c 의 변경 이력(`REMOVED`)과 이전 테이블 버전에 남는다.

## 5. 원본 식별자 정책 (U-24)

- 모든 객체는 Lexorma 의 내부 ID 를 갖는다. 원본 이름은 **준 그대로** 저장하고(`TBL_NM`, `COL_NM` …), 같은 객체인지는 **비교용 이름**(`CMPR_NM`, 이름 공간은 `PATH_CN`)으로 판단한다 (`SourceIdentifiers`).
- 연결이 다르면 같은 `public.users` 도 다른 객체이고, 스키마가 다르면 같은 이름도 다른 객체다.

| DBMS | 비교용 이름 |
|---|---|
| PostgreSQL | 따옴표 없는 식별자는 소문자로 접는다. 따옴표 식별자는 그대로 |
| Oracle | 따옴표 없는 식별자는 대문자로 접는다. 따옴표 식별자는 그대로 |
| MySQL·MariaDB | 컬럼명은 항상 소문자로 접는다(대소문자를 구분하지 않는다). 데이터베이스·테이블명은 연결의 `IDNTFR_CMPR_CD` 를 따른다 : `CASE_INSENSITIVE` 만 소문자로 접고, `CASE_SENSITIVE` 와 `DBMS_DEFAULT`(서버 설정을 모름)는 접지 않는다 |

- **따옴표 여부를 모를 때는 접지 않는다.** DBMS 의 카탈로그가 돌려주는 이름은 이미 접힌 뒤의 이름이라(PostgreSQL 의 `users`, Oracle 의 `USERS`) 그대로 비교하면 된다. 이름의 모양만 보고 따옴표 여부를 단정하지 않는다. 수집 결과가 "따옴표 없이 쓴 이름" 이라고 알려 준 경우에만 접는다.
  → PostgreSQL 에서 따옴표 없는 `USERS` 와 `users` 는 같은 객체, 따옴표 식별자 `"Users"` 는 다른 객체다.
- MySQL·MariaDB 의 `lower_case_table_names` 를 모르면 `Orders` 와 `orders` 를 합치지 않는다. 연결을 등록하는 사람이 규칙을 정할 수 있고(PostgreSQL·Oracle 은 고를 수 없다), AI-2 의 수집 도구가 서버 설정을 읽으면 그 값으로 정할 수 있다.
- **중복 방지** : `(연결, PATH_CN)`, `(스키마, CMPR_NM)`, `(테이블, CMPR_NM)` 에 UNIQUE. 카탈로그·스키마 중 한쪽이 없어도(NULL) 막히도록 경로는 NULL 이 아닌 한 값으로 만든다 — `길이:카탈로그/길이:스키마` (예 `3:app/6:public`, `0:/2:HR`). 테이블과 뷰는 이름 공간이 같아 유형을 키에 넣지 않았다.

## 6. 원본 COMMENT 와 업무 설명

- 원본 COMMENT 는 원본 카탈로그의 `ANTN_CN`(테이블·컬럼)에만 있다. 최신화가 갱신할 수 있는 값이다.
- 업무 설명은 물리 모델의 `TBL_EXPLN`·`COL_EXPLN` 그대로다. 원본 카탈로그를 쓰는 코드는 물리 모델을 읽지도 쓰지도 않으므로 원본 COMMENT 가 바뀌어도 업무 설명이 바뀔 경로가 없다 (테스트로 확인).
- 업무 설명의 255자 제한을 `TEXT` 로 넓히는 것과 AI 제안·승인은 AI-1d 이후다.

## 7. 삭제

| 지우는 것 | 함께 지워지는 것 | 남는 것 |
|---|---|---|
| 외부 DB 연결 | 그 연결의 원본 카탈로그 전부 (스키마 → … → 관계)와 AI-1c 의 수집 실행·대상·버전·변경 이력. AI-1d 의 원본 ↔ 물리 매핑도 이 목록에 더한다 | 다른 연결의 카탈로그, 물리·논리 모델, 데이터 사전 |
| 프로젝트 (영구 삭제) | 그 프로젝트의 연결과 원본 카탈로그·수집 이력 전부 | 다른 프로젝트 |
| 프로젝트 비활성화 | 없음 | 전부 (변경 API 와 도구 실행만 막힌다) |

- 한 트랜잭션에서 SQL 로 FK 순서대로 지운다 (`SourceCatalogCleanup`). 도중에 실패하면 앞에서 지운 것까지 전부 되돌아간다. `ON DELETE CASCADE` 는 쓰지 않았다.
- **프로젝트 영구 삭제** : `SourceCatalogCleanup` 이 core 의 `ProjectDeletedEvent` 를 직접 받는다 (`@EventListener`, 삭제 서비스와 같은 트랜잭션). 첫 구현은 `lexorma-data` 의 `ProjectDataCleanup` 목록에 끼워 넣었지만 모듈을 나누면서 각 모듈이 자기 테이블을 지우는 기존 방식으로 돌렸다. core 가 `lexorma-source` 를 몰라도 된다.
- 매핑이 생기면(AI-1d) 연결 삭제는 "해제"(매핑 행만 삭제)이고 물리 모델은 남긴다.
- **동시성** : 연결과 카탈로그 엔티티는 `projectId` 를 갖고 JPA 로 저장하므로 `ProjectWriteGuard` 가 커밋 직전에 프로젝트 행을 확인한다 — 프로젝트 삭제와 겹친 등록은 `PROJECT_NOT_FOUND` 로 롤백된다. 카탈로그 적재는 연결 행을 잠그고 쓰므로 연결 삭제와도 겹치지 않는다.
  **AI-1c 보완** : 적재·연결 수정·연결 삭제는 연결보다 **프로젝트 행을 먼저**(공유) 잠근다 (`ProjectWriteGuard.lock`). 처음 구현은 연결을 먼저 잠그고 커밋 직전에 프로젝트를 잠가, 프로젝트 삭제(프로젝트 → 연결 순서)와 서로를 기다릴 수 있었다.
  JDBC 배치로 직접 적재하는 경로를 만들면 그 보호를 받지 못하므로 `ProjectWriteGuard.guard(projectId)` 를 불러야 한다 (테스트 있음).
  `ProjectWriteGuard` 가 "이미 읽어 둔 프로젝트가 지워진 경우" 를 `OptimisticLockException` 이 아니라 `PROJECT_NOT_FOUND` 로 알리도록 고쳤다 (결과는 같았다 — 커밋 실패 — 오류 코드만 달랐다).

## 8. API

경로가 모두 `/api/source/**` 라 요청·응답 본문(비밀번호 포함)은 요청 로그에 남지 않는다 (`LogBodyPolicy`). 오류 메시지도 오류 코드만 남는다.

| API | 권한 |
|---|---|
| `GET /api/source/connections`, `GET /api/source/connections/{id}` | VIEWER 이상 (로그인한 프로젝트) |
| `POST /api/source/connections`, `PUT·DELETE /api/source/connections/{id}` | USER 이상 + 그 프로젝트에 접근할 수 있고 (프로젝트 관리자 또는 ADMIN) |
| `GET /api/source/connections/{id}/schemas`, `GET /api/source/schemas/{id}/tables`, `GET /api/source/tables/{id}` | VIEWER 이상. 목록은 쪽 단위(최대 200건) |

- 원본 카탈로그를 고치는 API, 도구를 실행하는 API(연결 확인·최신화)는 아직 없다 (AI-2 에서 실행기 위에 만든다). 엔티티를 응답으로 내보내지 않는다 (DTO).
- 오류 코드 : `SOURCE_CONNECTION_NOT_FOUND`, `INVALID_SOURCE_CONNECTION`, `SOURCE_OBJECT_NOT_FOUND`, `INVALID_SOURCE_CATALOG`, `SOURCE_TOOL_NOT_AVAILABLE`.
- 첫 구현의 `/api/mcp/**` 는 없앴다. 출시 전이고 화면이 부르지 않아 옛 경로를 남기지 않았다.

## 9. DB

- Flyway `V0__init.sql` 끝에 테이블 10개·시퀀스 10개·인덱스·FK·COMMENT (postgresql·h2 같은 내용). `V1__seed_data.sql` 에는 예제(데이터 사전 → 물리 → 논리, 4-4 절) : 단어 11, 용어 36, 테이블 10, 컬럼 95. 도메인은 늘리지 않고 기존 29개에 맞춰 컬럼 타입을 정했다. 예제에 비밀번호·실제 접속 정보는 없다 (원본 카탈로그 테이블 자체의 행은 넣지 않는다).
- 정식 출시 전 관행대로 V0·V1 을 고쳤다 → **이전 빌드로 만든 DB 는 체크섬이 달라 기동하지 않는다.** 개발 DB 는 새로 만들어야 한다 (이 작업에서는 개발 DB 를 건드리지 않고 빈 검증 DB 에서 확인했다).
- H2·PostgreSQL 차이 : 없다. 다만 H2 는 복합 FK 의 대상이 UNIQUE **제약**이어야 해서(UNIQUE 인덱스로는 안 된다) `UX_SRC_SCHM_02`·`UX_SRC_TBL_02` 는 제약으로 만들었다.
- 예제의 새 테이블에는 관계(`TB_TBL_REL`) 예제를 넣지 않았다 (모델 변경 이력·모델 버전 테이블과 같은 방식).

### 9.1 연결 변경 권한 (최종 보완에서 확정)

연결 변경 = **그 프로젝트에 접근할 수 있고**(활성 프로젝트의 승인된 멤버, 활성 계정) **그 프로젝트의 관리자이거나 ADMIN** — `ProjectAuthority.Access.canManage` 와 같은 기준이고 `ExecutionContextVerifier.verifyProjectManager` 가 지금의 DB 로 확인한다.
처음에는 `hasRole('DBA')` 도 요구해 USER 역할의 프로젝트 관리자가 자기 프로젝트의 연결을 바꿀 수 없었다 → 컨트롤러의 역할 조건을 USER 이상으로 낮췄다 (승인 가능 여부 `approvalAvailable` = USER 이상 + 관리자/ADMIN·MANAGER 와 같은 방식).
USER 이상을 남긴 이유 : VIEWER 는 조회 전용 역할이고 미승인(NOT_APPROVE)은 아무것도 할 수 없어야 한다 — VIEWER 역할의 프로젝트 관리자도 연결을 바꾸지 못한다.

| 계정 | 연결 변경 |
|---|---|
| USER·DBA + 그 프로젝트의 관리자 | 허용 |
| ADMIN + 그 프로젝트의 멤버 | 허용 |
| ADMIN + 멤버 아님 | 거절 (`PROJECT_ACCESS_DENIED`) |
| USER·DBA + 관리자 아님 | 거절 (`EXECUTION_PERMISSION_DENIED`) |
| VIEWER (관리자여도), 미승인 | 거절 (`AccessDeniedException`, 403) |
| 다른 프로젝트의 관리자 | 거절 (연결이 없는 것과 같다 `SOURCE_CONNECTION_NOT_FOUND`, 세션의 프로젝트를 바꿔 넣으면 `PROJECT_ACCESS_DENIED`) |
| 비활성 프로젝트 | 거절 (`PROJECT_ACCESS_DENIED`) |

프로젝트 관리 화면의 API(lexorma-system)는 그대로 `hasRole('DBA')` 다 — 이번 범위에서 바꾸지 않았다.

## 10. 아키텍처 보완 (같은 날)

| | 첫 구현 | 보완 후 |
|---|---|---|
| 원본 DB 접속 | 외부 MCP 서버가 접속, Lexorma 는 서버 주소·인증 정보와 수집 대상 식별자만 | **Lexorma 가 직접 JDBC 로 접속** (내부 도구) |
| 저장 | `TB_MCP_SRVR`(전역) + `TB_SRC_CNTN`(서버 참조, 수집 대상 식별자) | `TB_MCP_SRVR` 제거, `TB_SRC_CNTN` 에 호스트·포트·DB명·계정·암호화 비밀번호·옵션 |
| 코드 위치 | `lexorma-data` 의 `mcp/`·`source/` | 새 모듈 `lexorma-source` (`data` 와 서로 의존하지 않음) |
| 프로젝트 삭제 | `ProjectDataCleanup`(data) 목록에 포함 | `SourceCatalogCleanup` 이 `ProjectDeletedEvent` 를 직접 받음 |
| API | `/api/mcp/servers`, `/api/mcp/connections`, … | `/api/source/connections`, `/api/source/…` |
| 도구 | 없음 | `SourceMetadataTool` · `SourceToolExecutor` · `SourceToolRegistry` (계약만) |

바꾼 이유 : SI·공공·폐쇄망에서 수집을 위해 별도 MCP 서버를 설치·운영하게 하면 부담이 크다. 단일 JAR 로 배포하는 Lexorma 의 운영 방식과도 맞지 않는다. MCP 는 후속 선택 기능으로 남겼다.
유지한 것 : 원본 카탈로그 9개 테이블과 엔티티·적재(`SourceCatalogWriter`)·식별자 정책(`SourceIdentifiers`)·삭제(`SourceCatalogCleanup`)·조회 API, 프로젝트 격리, 비밀값 암호화 방식, 삭제 정합성 테스트.

## 11. 테스트

- `SourceCatalogIntegrationTest` (H2·PostgreSQL, 6건) : 연결 설정(DBMS 별 필수 항목·형식, JDBC URL·임의 옵션 거절, 비밀번호 암호화·응답 비노출·비워 둔 수정·다른 자리의 암호문·잃어버린 키·재등록), 연결 권한·격리(프로젝트 관리자·ADMIN·DBA·USER·VIEWER·미승인, 다른 프로젝트, 위조한 프로젝트 ID, 비활성 프로젝트), 내부 도구 실행(사용자·비동기·배치, 서버가 만든 JDBC URL, 미지원 DBMS·없는 도구·맞지 않는 입력·사용 중지 연결·다른 프로젝트 연결·권한·비활성 프로젝트, 읽을 수 없는 비밀번호), 원본 카탈로그(같은 이름의 다른 연결·스키마, DBMS 별 식별자, 테이블·뷰, 컬럼 순서, 복합 PK·FK·인덱스, 다른 스키마 FK, UNIQUE·CHECK, COMMENT, 업무 설명 미변경, DB 제약), 연결 삭제(범위, 도중 실패 시 롤백), 프로젝트 삭제·비활성화와 삭제에 겹친 등록(JPA·JDBC).
  최종 보완 : 연결 권한 표(9.1)의 계정 조합 전부, 원본 카탈로그 정리(리스너)가 실패하면 프로젝트 삭제 전체(다른 모듈이 지운 데이터 포함)가 롤백되는 것, 같은 삭제 이벤트의 중복 처리.
- `ExecutionContextIntegrationTest` : 암호화 키가 없을 때 연결 등록·수정.
- `ProjectDeletionIntegrationTest` : 원본 카탈로그 테이블(`PRJ_SN` 있음)을 프로젝트 소유 목록과 준비 데이터에 넣었다.
- `ModuleBoundaryTest` : 모듈 사이의 import·빌드 의존성, 실행 경로(컨트롤러는 정기 배치·비동기 실행 컨텍스트를 만들지 않는다, `ai` 패키지는 `com.lexorma.source` 를 참조하지 않는다).
- `SourceIdentifiersTest`, `SourceJdbcSettingsTest` (`lexorma-source` 단위 테스트).
- `V0SeedMigrationTest`·`SmokeTest` : 새 테이블의 COMMENT·예제·표준화 진단(0건).

## 12. 다음 작업

**AI-1c (적재·스냅샷·변경분)** — **구현 (2026-11-13)** : [source-catalog-versioning.md](source-catalog-versioning.md). 아래는 처음 적어 둔 범위다.
- 수집 실행(작업) 단위의 스냅샷 경계 : `COMPLETED` 만 확정, 수집 중인 결과를 조회·분석에 쓰지 않는다.
- 수집 결과(`SourceCatalogSpec`) 검증과 적재 순서 (스키마 → 테이블 → 컬럼 → 제약조건·인덱스 → 관계. 관계는 참조되는 테이블이 적재된 뒤에).
- 변경분(추가·수정·삭제, 달라진 객체만 전후 JSON), 내용 해시(`HASH_VL`), 원본에서 사라진 객체의 논리 삭제(`EXST_YN = false`, 부분 수집에서는 범위 밖 객체를 삭제로 보지 않는다), 이름 변경 후보, 멱등성.
- 스냅샷 보관(U-06) : 현재 상태 + 변경분 이력 + 과거 상태를 재구성할 수 있는 기준 상태.
- **필수 동시성 테스트** : 연결 삭제와 카탈로그 적재의 동시 실행, 같은 연결의 중복 적재, 프로젝트 삭제와 배치 적재의 동시 실행.
- 대량 적재 : 지금의 적재는 객체마다 조회·저장하는 JPA 경로다. 수만 컬럼 규모는 JDBC 배치가 필요할 수 있고, 그때 `ProjectWriteGuard.guard` 와 연결 잠금을 직접 적용한다.

**AI-2 (DBMS 별 내부 수집 도구)**
- `SourceMetadataTool` 구현 (PostgreSQL·Oracle·MySQL·MariaDB), JDBC 연결 확인, 타임아웃·읽기 전용 연결, 수집 범위 선택, 최신화 요청 API, 결과를 AI-1c 적재로 넘기기.
- 접속 직전의 DNS 해석 결과 확인, 드라이버 오류 메시지의 접속 정보 차단, 드라이버 배포 방식 결정.
