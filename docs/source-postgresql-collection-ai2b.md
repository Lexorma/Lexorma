# AI-2b PostgreSQL JDBC 메타데이터 수집

> **상태 : 구현 (2026-11-13).** PostgreSQL 원본 DB 에 실제로 접속해 메타데이터를 읽고 AI-1c 의 수집 실행(`start → heartbeat → apply / fail`)으로 원본 카탈로그·버전·변경 이력에 반영한다.
> Oracle·MySQL·MariaDB 수집, 외부 드라이버 로딩, 자동 주기 수집, 화면은 아직 없다. 관련 문서 : [아키텍처](source-tool-architecture.md), [AI-1b 저장 모델](source-catalog-ai1b.md), [AI-1c 버전·이력](source-catalog-versioning.md), [로드맵 2단계](ai-metadata-roadmap.md#2단계-dbms-별-내부-메타데이터-수집-도구-jdbc).

## 1. 구성

```
POST /api/source/connections/{id}/collections   (USER + 프로젝트 관리자·ADMIN)
  └ SourceRefreshService.refresh
      ├ SourceCollectionService.start (AI-1c, 실행 RUNNING, 작업 ID = 서버가 만든 UUID, 실행 방식 ASYNC)
      └ 작업 대기열 (스레드 2, 대기 10) → Job
            ├ SourceToolExecutor.session ── 실행 컨텍스트 재검증 · 연결 · 호스트 정책
            │     └ SourceToolSession (JDBC 연결 1개, 읽기 전용 REPEATABLE READ)
            │           schema → table → column → constraint → index → relationship  (PostgresSourceTools, 도구마다 조회 1번)
            ├ 세션 종료 (rollback · close)
            └ SourceCollectionService.apply (한 트랜잭션)  /  실패하면 fail(오류 코드)
      heartbeat (기본 30초, 짧은 트랜잭션) · 전체 제한 시간 (기본 10분) · 취소 → SourceToolSession.abort
```

- 모듈 의존은 그대로다 (`lexorma-source` → `lexorma-core`). 새 모듈·새 Registry 없음. PostgreSQL 드라이버는 Boot BOM 의 `org.postgresql:postgresql`(42.7.14 — 테스트·배포본 같은 버전)이 `lexorma-app` 의 `runtimeOnly` 로 이미 있어 Gradle 을 바꾸지 않았다 — `lexorma-source` 는 드라이버 클래스를 컴파일 시점에 참조하지 않고 실행 시점에 이름으로 만든다.
- 기존 계약 재사용 : `SourceMetadataTool`·`SourceToolRegistry`·`SourceToolExecutor`·`SourceToolContext`, 도구 이름 7개(`SourceTools`), `ExecutionContextVerifier`, `SourceCollectionService`(start·heartbeat·apply·fail·cancel), `SourceCollectionPreparer`·`SourceCatalogLoader`, `SecretCipher`, `SourceJdbcSettings`, `SourceHostPolicy`.
- 새 클래스 : `tool/SourceToolSession`, `tool/SourceMetadata`(도구 입·출력), `tool/postgresql/PostgresSourceTools`, `connection/jdbc/SourceAddressPolicy`·`SourceGuardedSocketFactory`·`SourceJdbcErrors`, `connection/service/SourceConnectionTester`·`SourceConnectionTestLimiter`, `collection/service/SourceRefreshService`.

## 2. JDBC 세션

| 항목 | 내용 |
|---|---|
| 연결 | 세션 하나 = JDBC 연결 하나. 도구가 **처음 조회할 때** 드라이버(`org.postgresql.Driver`)로 직접 연다 — DataSource·연결 풀·JPA·Lexorma 트랜잭션과 무관. DB 를 읽지 않는 도구만 실행하면 접속하지 않는다 |
| 재사용 | 한 수집에서 도구 7개가 같은 연결을 쓴다 (조회 6번 : 스키마·테이블·컬럼·제약조건·인덱스·관계) |
| 트랜잭션 | `setAutoCommit(false)` + `setTransactionIsolation(REPEATABLE_READ)` + `setReadOnly(true)` → 첫 조회에서 `BEGIN READ ONLY` (격리 수준은 세션 기본값으로 이미 REPEATABLE READ). 세션 시작 옵션으로 `default_transaction_read_only=on` 도 준다 — `setReadOnly` 만 믿지 않는다. 테스트가 `transaction_read_only = on`, `transaction_isolation = repeatable read`, 두 도구의 `now()`·백엔드 pid 가 같음(같은 트랜잭션), `CREATE TABLE` 거절을 확인한다 |
| 이름 찾기 | 세션 옵션 `search_path=pg_catalog`, 조회는 `pg_catalog.` 를 붙인다 (사용자 스키마의 같은 이름 함수에 가려지지 않는다) |
| 시간 | `connectTimeout`·`loginTimeout` 10초, 조회마다 `Statement.setQueryTimeout` 30초 + 서버 `statement_timeout` 30초, `socketTimeout` 60초(끊긴 네트워크에서 영원히 기다리지 않는다), 전체 10분 (`SourceRefreshService` 의 타이머) |
| 멈춤 | `SourceToolSession.abort(사유)` (다른 스레드) : 실행 중인 `Statement.cancel()` → `Connection.abort()`. 스레드 interrupt 에 기대지 않는다. 그 뒤의 조회·도구는 그 사유로 실패한다 |
| 정리 | 세션이 끝나면(예외 포함) `rollback` → `close`. **`apply` 는 연결을 닫은 뒤에** 부른다 |

조회는 `SourceToolContext.query(sql, binder, mapper)` 로만 한다 (연결을 도구에 내주지 않는다). SQL 은 코드의 상수이고 값은 `PreparedStatement` 로만 넣는다 (스키마 목록은 `text[]` 배열 하나).

## 3. 원본 DB 보안

### 3.1 접속 대상 (`lexorma.source.allowed-networks`)

| 주소 | 정책 |
|---|---|
| 미지정(0.0.0.0/8, ::), 링크 로컬(169.254.0.0/16 — 메타데이터 169.254.169.254 포함, fe80::/10), 멀티캐스트, 브로드캐스트, fd00:ec2::254, 100.100.100.200 | **항상 차단** (허용 목록에 넣어도) |
| 루프백(127/8, ::1), 사설(10/8, 172.16/12, 192.168/16), 100.64/10, fc00::/7, fec0::/10 | 허용 목록(CIDR)에 있을 때만 |
| 공인 주소 | 허용 (`lexorma.source.allowed-hosts` 로 호스트 이름 범위를 좁힐 수 있다 — 기존 정책) |

- **기본값은 비어 있다 = 사설·루프백으로는 접속하지 않는다.** 내부망 DB 는 운영자가 대역을 연다 (예: `LEXORMA_SOURCE_ALLOWED_NETWORKS=10.20.0.0/16`). Docker 안의 Lexorma 가 같은 호스트의 DB 에 붙는 경우도 그 주소(172.17.0.1 등)를 열어야 한다. 테스트만 `127.0.0.0/8,::1/128` 을 연다.
- **DNS rebinding** : 접속 직전에 이름이 풀리는 모든 주소를 확인하고(오류 코드를 일찍 알리기 위한 것), **드라이버의 실제 소켓**(`socketFactory` = `SourceGuardedSocketFactory`)이 연결하려는 주소를 다시 확인한 뒤 바로 그 주소로 연결한다. 확인과 연결 사이에 이름을 다시 풀지 않으므로, 사전 확인 뒤 DNS 가 다른 주소를 돌려줘도 그 주소가 정책에 맞지 않으면 연결하지 않는다. TLS 는 이 소켓 위에서 드라이버가 **호스트명**으로 검증하므로 `verify-full` 과 충돌하지 않는다. 테스트가 사전 확인 없이 드라이버로 직접 루프백에 접속해 소켓이 막는 것을 확인한다.
- 한계 : 취소 요청(cancel request)도 같은 소켓 팩토리로 같은 호스트에 간다. JVM 에 SOCKS 프록시를 설정하면 정책은 목적지 주소를 보지만 실제 연결은 프록시로 간다 (운영자가 정하는 것).

### 3.2 TLS (`sslmode`, 기존 허용 목록 그대로)

| 값 | 암호화 | 인증서 확인 | 호스트명 확인 |
|---|---|---|---|
| `disable` | 안 함 | — | — |
| `allow`·`prefer`(드라이버 기본) | 서버가 되면 | **안 함** | 안 함 |
| `require` | 반드시 | **안 함** | 안 함 |
| `verify-ca` | 반드시 | 함 | 안 함 |
| `verify-full` | 반드시 | 함 | 함 (**운영 권장**) |

- 기본값은 바꾸지 않았다 (지금 등록된 연결의 동작이 바뀌지 않게). 연결 테스트는 설정값(`mode`)과 **서버가 알려 준 실제 암호화 여부**(`pg_stat_ssl`)를 따로 주고, `certificateVerified` 는 verify-ca·verify-full 이면서 암호화된 경우만, `hostnameVerified` 는 verify-full 만 true 다 — `require`·`prefer` 를 검증된 보안 연결로 표시하지 않는다.
- 한계 : 루트 인증서 위치(`sslrootcert`)는 연결 옵션으로 열지 않았다. verify-ca·verify-full 은 드라이버 기본 위치(실행 계정의 `~/.postgresql/root.crt`)의 인증서를 쓴다. 운영 배포에서 그 파일을 넣는 방법(이미지·볼륨)은 정하지 않았다.

### 3.3 비밀정보

- 비밀번호는 접속하는 순간에만 복호화해 드라이버 속성으로 준다 (URL 에 없다). 계정·비밀번호·URL·드라이버 메시지는 응답·실행 기록(`ERR_MSG_CN`)·로그에 넣지 않는다 — 드라이버 오류는 `SourceJdbcErrors` 가 SQLSTATE·원인으로 **코드만** 고르고, 로그에는 예외 종류와 SQLSTATE 만 남긴다. 테스트가 틀린 비밀번호의 연결 테스트 뒤 로그·응답에 그 값이 없는 것을 확인한다.
- 저장 전 연결 테스트에서 비밀번호를 비우면 저장된 비밀번호를 쓰는데, **DBMS·호스트·포트·데이터베이스·계정이 저장된 것과 같을 때만** 쓴다 (다른 주소로 바꾼 설정에 저장된 비밀번호를 보내지 않는다 : `INVALID_SOURCE_CONNECTION`).

### 3.4 연결 테스트 호출 제한

기존 호출 제한 구조가 없어 최소한으로 더했다 (`SourceConnectionTestLimiter`) : 계정마다 1분에 10번(`lexorma.source.connection-test.per-minute`), 서버 전체 동시 4개(`max-concurrent`), 넘으면 429 `SOURCE_TEST_RATE_LIMITED`. 서버 메모리에만 있어 인스턴스마다 따로 센다. 접속 실패의 종류(시간 초과·거절)는 코드로 구분되므로 허용된 대역 안에서는 포트가 열렸는지 알 수 있다 — 대역 제한과 호출 제한이 그 범위를 좁힌다.

## 4. PostgreSQL 도구 (`PostgresSourceTools`, PostgreSQL 12 이상)

| 도구 | 시스템 카탈로그 | 내용 |
|---|---|---|
| `source.connection.test` | DatabaseMetaData, `pg_namespace`, `pg_stat_ssl` | 제품명·버전, 메타데이터를 읽을 수 있는지(권한 없음은 `metadataReadable=false`), TLS 상태 |
| `source.schema.discover` | `pg_namespace` | `pg_` 로 시작하지 않는 스키마와 `information_schema` 를 뺀 전부 (`pg_catalog`·`pg_toast`·`pg_temp_N`·`pg_toast_temp_N` 은 PostgreSQL 이 예약한 이름) |
| `source.table.discover` | `pg_class`·`pg_namespace`·`pg_description` | 일반 테이블(`r`)·파티션 부모(`p`) = TABLE, 뷰(`v`) = VIEW, 테이블 COMMENT |
| `source.column.discover` | `pg_attribute`·`pg_attrdef`·`pg_description`, `format_type` | 순서(attnum), 타입 원본 표현(`format_type` : `character varying(100)`, `numeric(10,2)`, `integer[]`, 도메인명), 길이(char·varchar·bit), 정밀도·소수 자릿수(numeric), NULL, 기본값, COMMENT, Identity·생성 컬럼 |
| `source.constraint.discover` | `pg_constraint` | PK·UNIQUE·CHECK(`pg_get_expr` 의 식), 구성 컬럼 순서, 시스템이 붙인 이름 그대로 |
| `source.index.discover` | `pg_index`·`pg_am`·`pg_get_indexdef`·`pg_get_expr` | 방식(btree 등), 고유, 구성 요소(컬럼 또는 표현식) 순서, 정렬(DESC·NULLS), INCLUDE 컬럼, 부분 인덱스 조건. 유효하지 않은 인덱스(`indisvalid = false`, 실패·진행 중인 CONCURRENTLY)는 뺀다. PK·UNIQUE 를 받치는 인덱스도 원본에 있는 인덱스라 함께 들어간다 |
| `source.relationship.discover` | `pg_constraint`(`f`) | 복합 FK, 다른 스키마 참조, 참조 컬럼 순서, ON DELETE·ON UPDATE(`NO ACTION`·`RESTRICT`·`CASCADE`·`SET NULL`·`SET DEFAULT`). 파티션 테이블을 참조할 때 생기는 하위 제약조건(`conparentid <> 0`)은 뺀다 |

- **도구 하나가 대상 스키마 전체를 조회 한 번으로 읽는다** (`nspname = any(?)`). 테이블마다 조회하지 않는다 (규모와 상관없이 조회 6번, 아래 7장).
- **식별자** : 카탈로그에 저장된 이름 그대로 넘기고 따옴표 여부는 모른다(`quoted = null`) → AI-1b 규칙대로 접지 않고 그대로 비교한다. `customer`·`"Customer"`·`"고객정보"` 는 서로 다른 객체이고, 다른 스키마의 같은 이름 테이블도 다르다 (테스트).
- **기본값 칸** : PostgreSQL 컬럼은 기본값·Identity·생성 컬럼 중 하나만 갖는다. 새 칸을 만들지 않고 원본 DDL 표기로 구분해 넣는다 — 기본값 = 식(`now()`, `nextval('app.orders_serial_id_seq'::regclass)`), Identity = `GENERATED ALWAYS AS IDENTITY` / `GENERATED BY DEFAULT AS IDENTITY`, 생성 컬럼 = `GENERATED ALWAYS AS (식) STORED` / `VIRTUAL`(PostgreSQL 18).
- **수집하지 않는 것 (명시적으로 제외)** : 파티션 자식(부모로만 본다), 구체화 뷰(`m`), 외부 테이블(`f`), 시퀀스, 임시 테이블(`pg_temp_N`), 복합 타입, 시스템 스키마. `SourceTableType` 이 TABLE·VIEW 뿐이라 다른 유형을 TABLE 로 바꿔 넣지 않는다. 지원 대상만 범위로 보므로 이 객체들은 "사라짐" 으로 판정될 일이 없다 — 단, 일반 테이블이 **파티션으로 붙으면**(`ATTACH PARTITION`) 원본에서 독립 테이블이 아니게 되어 다음 FULL 에서 REMOVED 가 된다 (의도한 규칙).
- **모델이 담지 않는 것** : 뷰 정의 SQL, Identity 시퀀스 옵션, 컬럼 콜레이션, 인덱스 연산자 클래스·콜레이션, 제약조건의 DEFERRABLE·NOT VALID·NO INHERIT·`NULLS NOT DISTINCT`, 배제 제약조건(EXCLUDE — 받치는 인덱스만 들어간다), FK 의 MATCH·`SET NULL (컬럼)`, 트리거·함수·권한·통계, 데이터.
- **누락 구분** : 값이 없음 = NULL / 빈 목록. 조회 권한 부족 = `SOURCE_PERMISSION_DENIED`(그 수집 전체 실패), 조회 실패 = `SOURCE_METADATA_FAILED`, 지원하지 않는 객체 = 위의 제외 목록, 모델이 담지 않는 항목 = 위의 목록. 조회가 하나라도 실패하면 어떤 스키마도 COMPLETED 로 넘기지 않는다. PostgreSQL 의 시스템 카탈로그는 기본으로 모든 계정이 읽을 수 있어 테이블 권한 때문에 객체가 빠지지 않는다 — 카탈로그 읽기 권한을 회수한 DB 는 조회가 실패한다 (테스트).

## 5. 표현식 인덱스 모델 (V2 마이그레이션)

AI-1c 의 Preparer 는 구성 컬럼이 없는 인덱스를 거부했다. 새 테이블 없이 기존 구성 컬럼 행을 넓혔다.

| 테이블 | 변경 | 뜻 |
|---|---|---|
| `TB_SRC_IDX` | `CND_CN TEXT` 추가 | 부분 인덱스 조건 (`pg_get_expr` 원본 표현) |
| `TB_SRC_IDX_COL` | `EXPR_CN TEXT` 추가 | 표현식 요소 (`pg_get_indexdef` 의 그 요소) |
| `TB_SRC_IDX_COL` | `SORT_CD varchar(20)` 추가 | `DESC`, `NULLS FIRST`, `DESC NULLS LAST`, `INCLUDE`. 기본(ASC NULLS LAST, DESC 는 NULLS FIRST)이면 NULL |
| `TB_SRC_IDX_COL` | `SRC_COL_SN` NULL 허용 | 표현식 요소는 컬럼이 없다. 한 요소는 컬럼과 표현식 중 **하나**만 (Preparer·Writer 가 검증) |

- `SourceCatalogSpec.Index(name, indexType, unique, members, predicate)` + `IndexMember(name, expression, sort)`. 예전 생성자 `Index(name, indexType, unique, List<Name> columns)` 는 컬럼만의 오름차순 인덱스로 남겼다.
- **정규화·해시** : 인덱스 JSON 에 `expressions`·`sorts`·`predicate` 를 **값이 있을 때만** 넣는다. 컬럼만의 오름차순 인덱스는 AI-1c(FORMAT 1)와 한 글자도 같은 JSON·해시다 → **`FORMAT`·해시 접두사(`v1:`)를 올리지 않았다**. 이미 저장된 버전과 비교해도 바뀐 것으로 보이지 않고, 기존 버전 행은 고치지 않는다 (테스트 `SourceCatalogCanonicalIndexTest`). 이전에 컬럼만으로 저장된 인덱스가 실제로는 DESC 였다면 첫 수집에서 MODIFIED 가 된다 (새로 알게 된 사실).
- 이름 없는 인덱스의 서명 키는 표현식 요소를 `(식)` 으로 넣는다 (PostgreSQL 인덱스는 항상 이름이 있다).
- **Flyway** : `V2__source_index_expression.sql` (postgresql·h2) — 스키마 변경, `COMMENT ON`, 예제(용어 `조건_내용`·`표현식_내용`·`정렬_코드`와 용어 단어, `TB_COL`, 논리 속성, 속성 ↔ 컬럼 매핑). **V0·V1 은 고치지 않았다** (기존 DB 의 체크섬이 그대로라 개발 DB 를 다시 만들 필요가 없다). 단어는 모두 기존 것(조건·표현식·정렬·내용·코드). `V0SeedMigrationTest` 는 마이그레이션 3개를 기대한다.

## 6. 수집 실행 (`SourceRefreshService`)

- **요청** : 이 DBMS 의 도구가 없으면(PostgreSQL 외) 실행을 만들지 않고 400 `SOURCE_TOOL_NOT_AVAILABLE`. 실행 컨텍스트는 요청자의 `ASYNC`(요청자·프로젝트·서버가 만든 작업 ID)다 — 관리자 권한으로 바꾸지 않는다. 세션을 열 때와 `apply` 직전에 `verifyProjectManager` 로 다시 본다.
- **FULL** : 접속한 데이터베이스의 시스템이 아닌 스키마 전체 (빈 스키마 포함, 스키마별 포함·제외 설정 없음). 스키마 목록 → 테이블 → 컬럼 → 제약조건 → 인덱스 → 관계가 **모두 성공해야** 스키마들을 COMPLETED 로 넘긴다. 스키마가 하나도 없으면 AI-1c 규칙대로 실패.
- **PARTIAL** : 요청한 스키마 중 원본에 있는 것만 읽는다. 없는 스키마는 결과에서 빠져 AI-1c 가 NOT_FOUND 로 남기고 **실행 전체를 반영하지 않는다** (`SOURCE_COLLECTION_INCOMPLETE`). 이름은 원본 그대로 비교한다 (`App` ≠ `app`). 범위 밖 스키마는 그대로이고, 범위 밖을 참조하는 FK 는 참조 이름을 남기고 이미 카탈로그에 있으면 잇는다 (지어내지 않는다).
- **일관성** : 모든 조회가 한 REPEATABLE READ 스냅숏에서 돈다. 하위 객체가 테이블 목록에 없는 테이블을 가리키거나, 카탈로그 함수가 지워진 객체를 만나면(`XX000`·`42P01` 등) `SOURCE_CHANGED_DURING_COLLECTION` 으로 실행 전체를 실패시킨다.
- **heartbeat** : 기본 30초(`lexorma.source.collection.heartbeat-interval`). 실행이 더 이상 진행 중이 아니거나(다른 서버에서 취소·정리) 없어졌으면(연결 삭제) 원본 조회를 멈춘다.
- **취소** : `POST /api/source/collections/{executionId}/cancel` → AI-1c `cancel`(CANCELLED) → 이 서버의 작업이면 바로 `abort`, 다른 서버의 작업이면 다음 heartbeat 에서 멈춘다. 그 뒤의 `fail` 은 AI-1c 규칙대로 이미 끝난 실행을 덮어쓰지 않고, `apply` 는 RUNNING 이 아니어서 반영하지 않는다.
- **전체 제한 시간** : 기본 10분(`lexorma.source.collection.timeout`). 넘으면 조회 취소·연결 종료 후 FAILED `SOURCE_COLLECTION_TIMEOUT`.
- **작업 스레드** : 2개(`workers`), 대기열 10(`queue-capacity`). 가득 차거나 종료 중이면 방금 만든 실행을 FAILED `SOURCE_COLLECTION_REJECTED` 로 닫고 503 — RUNNING 으로 남지 않는다.
- **서버 종료** : 새 작업을 받지 않고, 돌고 있는 작업의 조회를 멈춰 FAILED `SOURCE_COLLECTION_ABANDONED` 로 남긴다 (15초 기다린 뒤 강제 종료). 그래도 남은 실행(프로세스 강제 종료 등)은 AI-1c 의 멈춘 실행 정리(`stale-after`)가 다음 등록 때 정리한다.
- **자동 재시도 없음.** 원본 오류로 기존 카탈로그를 지우지 않는다 (실패한 실행의 현재 카탈로그 변경 0건 — 테스트).
- **객체 급감 진단** : 완료한 수집의 테이블·컬럼 수가 직전 같은 종류의 반영보다 절반 아래로 줄면 경고 로그(이전·현재 수, 감소율)만 남긴다. 임계값으로 막지 않는다 (완결성은 수집 단계에서 확인).
- **AI-1c 보완** : `SourceCollectionService.fail` 이 권한 재확인에 실패하면 예전에는 실행이 RUNNING 으로 남고 이 서버의 진행 목록에서도 빠지지 않아 그 연결의 수집을 다시 시작할 수 없었다. 지금은 실행을 그 권한 오류 코드로 FAILED 로 닫은 뒤 예외를 던진다.

## 7. API

| 메서드 | 경로 | 권한 | 내용 |
|---|---|---|---|
| POST | `/api/source/connections/{id}/collections` | USER + 프로젝트 관리자·ADMIN | 본문 `{"type": "FULL" \| "PARTIAL", "schemas": ["app"]}` → **202** `{executionId, jobId, status}`. 진행 중이면 409 `SOURCE_COLLECTION_IN_PROGRESS`, 대기열 가득 503 |
| GET | `/api/source/collections/{executionId}` | VIEWER | (AI-1c 그대로) 상태·시작·마지막 heartbeat 는 실행 행, 종료·반영 시각, 범위·대상별 결과·오류 코드·건수 |
| POST | `/api/source/collections/{executionId}/cancel` | USER + 그 수집을 요청한 사람 | CANCELLED. 끝난 실행은 409 `SOURCE_COLLECTION_NOT_RUNNING` |
| POST | `/api/source/connections/{id}/test` | USER + 프로젝트 관리자·ADMIN | 저장된 연결 테스트 |
| POST | `/api/source/connections/test?connectionId=` | USER + 프로젝트 관리자·ADMIN | 저장 전 테스트 (등록·수정 화면의 본문 그대로, 저장하지 않는다). 연결명은 없어도 된다 |

연결 테스트 응답 : `{connected, metadataReadable, productName, productVersion, tls: {mode, encrypted, protocol, certificateVerified, hostnameVerified}, errorCode, message}`. 접속·조회 실패는 200 의 `errorCode`·`message`(요청 언어), 권한·검증·없는 연결·호출 제한은 HTTP 오류다. 비밀번호·JDBC URL·드라이버 메시지는 없다.
VIEWER 는 최신화·취소·연결 테스트를 못 하고(403), 프로젝트 관리자가 아닌 USER 는 서비스가 막는다(403 `EXECUTION_PERMISSION_DENIED`). 화면이 없어 `MenuAuthorization` 은 바꾸지 않았다 (화면을 만들 때 액션 id 를 더한다).

## 8. 오류 코드

| 코드 | HTTP | 상황 |
|---|---|---|
| `SOURCE_CONNECT_TIMEOUT` | 504 | 접속 시간 초과 |
| `SOURCE_CONNECT_FAILED` | 502 | 접속 실패(거절·이름 풀이·DB 없음), 수집 도중 서버가 연결을 끊음(`57P0x`·`08xxx`) |
| `SOURCE_AUTH_FAILED` | 502 | 인증 실패 (`28000`·`28P01`) |
| `SOURCE_PERMISSION_DENIED` | 502 | 카탈로그 읽기 권한 없음 (`42501`) |
| `SOURCE_TLS_FAILED` | 502 | TLS 연결·인증서 검증 실패, 서버가 TLS 를 지원하지 않는데 요구함 |
| `SOURCE_DRIVER_NOT_AVAILABLE` | 400 | 드라이버 없음 (PostgreSQL 외 DBMS 의 접속 포함) |
| `SOURCE_HOST_NOT_ALLOWED` | 400 | 접속 대상 정책 위반 (호스트 범위, 주소 대역, 실제 소켓의 주소) |
| `SOURCE_QUERY_TIMEOUT` | 504 | 조회 시간 초과 (`57014`, 응답 대기 시간 초과) |
| `SOURCE_METADATA_FAILED` | 502 | 그 밖의 조회 실패 |
| `SOURCE_CHANGED_DURING_COLLECTION` | 409 | 수집 도중 원본 구조가 바뀌어 일관되지 않음 |
| `SOURCE_CANCELLED` | 409 | 취소 (실행 상태의 사유) |
| `SOURCE_COLLECTION_TIMEOUT` | 504 | 전체 제한 시간 초과 |
| `SOURCE_COLLECTION_REJECTED` | 503 | 작업 대기열이 가득 참·서버 종료 중 |
| `SOURCE_TEST_RATE_LIMITED` | 429 | 연결 테스트 호출 제한 |

실행 기록의 오류 코드는 AI-1c 규칙대로 `[A-Z][A-Z0-9_]*` 만 남는다. 수집 실패는 실행 상태(FAILED + 코드)로 남고, HTTP 상태는 동기 API(연결 테스트 제외)에서 예외로 던질 때만 쓰인다.

## 9. 테스트

| 테스트 | 내용 |
|---|---|
| `SourceAddressPolicyTest` (단위) | 차단·허용 목록·공인 주소, IPv6·IPv4 매핑, 소켓이 실제 연결 주소를 보는지, JDBC 오류 분류, 로그용 설명에 메시지가 없는지 |
| `SourceCatalogCanonicalIndexTest` (단위) | 컬럼만의 인덱스 JSON·해시가 FORMAT 1 과 같음, 표현식·정렬·조건 JSON, 서명 키 |
| `SourceJdbcCollectionIntegrationTest` | 실제 PostgreSQL 원본 : 최초 FULL(대상 객체·제외 객체·식별자·타입·기본값·Identity·생성 컬럼·COMMENT·복합 PK·UNIQUE·CHECK·표현식/부분/DESC/NULLS/INCLUDE 인덱스·복합/다른 스키마 FK), 변경 없는 재수집 0건, 테이블·컬럼 추가·타입·COMMENT·인덱스·FK 변경·삭제·재등장(생성 차수 2)·과거 버전 유지, PARTIAL·범위 밖 보존·없는 스키마·대소문자·빈 스키마·범위 밖 FK, 읽기 전용 REPEATABLE READ 세션·쓰기 거절·연결 재사용·연결 종료·조회 제한 시간·다른 스레드의 중단, 연결 테스트(TLS 상태·인증 실패·TLS 실패·카탈로그 권한 없음·저장된 비밀번호 재사용 조건·로그에 비밀번호 없음), 접속 대상 정책(드라이버 소켓 포함), 권한(VIEWER·관리자 아닌 USER·잘못된 범위), 진행 중 사건(중복 요청·취소·조회 시간 초과·백엔드 종료·권한 회수·연결 삭제). 성능(opt-in) |
| `SourceJdbcCollectionLimitIntegrationTest` | 작업 스레드 1·대기열 1·전체 제한 1초 : 전체 제한 시간, 대기열 가득 참(503, RUNNING 으로 남지 않음), 서버 종료(ABANDONED, 종료 뒤 요청 거절) |

원본 조회를 오래 걸리게 만들 때는 원본 DB 에서 `pg_catalog.pg_description` 을 배타 잠금으로 잡아 두고, 수집 세션이 **잠금을 기다리는 것**(`pg_stat_activity.wait_event_type = 'Lock'`)을 확인한 뒤 사건을 일으킨다 (고정 대기에 기대지 않는다).

```bash
# 원본 DB 역할 : 테스트마다 ai2b_… 데이터베이스를 만들고 지운다 (데이터베이스·역할을 만들 수 있는 계정)
LEXORMA_TEST_SOURCE_POSTGRES_URL=jdbc:postgresql://localhost:15439/postgres ./gradlew test --rerun

# Lexorma 내부 DB 도 PostgreSQL 로 (검증용 DB 를 따로 만든다 : 개발 DB 를 쓰지 않는다)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/lexorma_ai2b_test SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
LEXORMA_TEST_POSTGRES_URL=jdbc:postgresql://localhost:15439/lexorma_ai2b_test \
LEXORMA_TEST_SOURCE_POSTGRES_URL=jdbc:postgresql://localhost:15439/postgres ./gradlew test --rerun

# 성능 (opt-in)
LEXORMA_PERF=true LEXORMA_TEST_SOURCE_POSTGRES_URL=... ./gradlew :lexorma-app:test --tests 'com.lexorma.SourceJdbcCollectionIntegrationTest.performance' --rerun -i
```

`LEXORMA_TEST_SOURCE_POSTGRES_URL` 이 없으면 원본 DB 테스트는 건너뛴다 (단위 테스트는 돈다). 2026-11-13 결과 : H2 내부 DB 494건·PostgreSQL 내부 DB 494건 통과 (AI-1c 478건 + 16건), 건너뜀은 opt-in 성능 테스트(PostgreSQL 내부 DB 3건, H2 는 `V0SeedMigrationTest` 의 PostgreSQL 쪽 포함 4건).

### 9.1 성능 (한 번 측정)

Apple M1(8코어), Docker PostgreSQL 18.6(원본·내부 DB 같은 컨테이너), 테이블마다 컬럼 20·PK·FK 1·인덱스 1, PARTIAL 한 스키마, **한 번씩만 측정**했다 (편차를 가리지 않았다).

| 규모 (테이블/컬럼) | 원본 조회 수 | 원본 읽기·정규화 (ms) | AI-1c 반영 (ms) | 전체 (ms) | 버전 | 변경 없는 재수집 전체 (ms) | 힙 증가 (MB, 대략) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 100 / 2,000 | 6 | 62 | 536 | 678 | 101 | 148 | 19 |
| 1,000 / 20,000 | 6 | 162 | 2,052 | 2,230 | 1,001 | 324 | 94 |
| 3,000 / 60,000 | 6 | 568 | 4,130 | 4,717 | 3,001 | 677 | 164 |

- 원본 조회 수는 규모와 상관없이 6번이다 (N+1 없음). 최초 수집 시간의 대부분은 AI-1c 의 반영(버전 3,001행 등 한 트랜잭션)이다. 원본 읽기 시간에는 결과를 Java 객체로 바꾸는 것까지 들어 있다.
- 힙 증가는 GC 를 한 번 부른 뒤의 사용량 차이라 정확한 값이 아니다. 결과 전체를 메모리에 모은 뒤 반영한다 (Preparer 의 상한 : 테이블 20,000·컬럼 1,000,000).

## 10. 운영 설정

| 설정 (환경 변수) | 기본 | 뜻 |
|---|---|---|
| `lexorma.source.allowed-networks` (`LEXORMA_SOURCE_ALLOWED_NETWORKS`) | 비움 | 접속을 허용할 사설·루프백 대역 (CIDR, 쉼표) |
| `lexorma.source.allowed-hosts` | 비움 | 호스트 이름 범위 (기존) |
| `lexorma.source.jdbc.connect-timeout-seconds` | 10 | 접속·인증 |
| `lexorma.source.jdbc.query-timeout-seconds` | 30 | 조회 하나 (응답 대기는 그 두 배) |
| `lexorma.source.collection.timeout` | PT10M | 수집 하나 전체 |
| `lexorma.source.collection.heartbeat-interval` | PT30S | 살아 있음 확인 (`stale-after` PT10M 보다 충분히 짧게) |
| `lexorma.source.collection.workers` / `queue-capacity` | 2 / 10 | 수집 작업 스레드·대기열 |
| `lexorma.source.connection-test.per-minute` / `max-concurrent` | 10 / 4 | 연결 테스트 호출 제한 |

원본 계정은 메타데이터만 읽는 최소 권한 계정을 쓴다 (PostgreSQL 은 별도 권한 없이 카탈로그를 읽을 수 있다 : 테이블 `SELECT` 권한을 주지 않아도 된다).

## 11. 남은 문제 · 다음 단계

- ~~배포본에서의 소켓 팩토리 적재 미확인~~ → **배포 검증 완료 (2026-11-13)** : `java -jar lexorma.jar`(JDK 21, `docker` 프로필, 허용 대역 `127.0.0.1/32`)와 `Dockerfile` 이미지(`docker_lexorma-network`, 원본 `lexorma-postgres` → 172.18.0.2, 허용 대역 `172.18.0.2/32`) 모두에서 연결 테스트·차단 주소(`SOURCE_HOST_NOT_ALLOWED`)·인증 실패(`SOURCE_AUTH_FAILED`)·FULL 수집 202 → RUNNING → COMPLETED·변경 없는 재수집(버전·이력 0건)을 API 로 확인했다. 드라이버와 `SourceGuardedSocketFactory`(+`$GuardedSocket`)는 실행 jar 안의 `BOOT-INF/lib` 에서 적재되고(클래스 적재 로그), 원본 서버 로그에서 세션마다 `REPEATABLE READ` → `BEGIN READ ONLY` → 조회 6번 → `ROLLBACK`, 쓰기 0건, 끝난 뒤 열린 세션 0개였다. 배포본의 jar 로 사전 확인 없이 접속하면 소켓이 막는다(`BlockedAddressException`). 실행 jar 는 JDK 21 이 필요하다 (17 로는 `UnsupportedClassVersionError`).
- 루트 인증서 배포 방법(3.2), 호출 제한의 인스턴스 간 공유(3.4), 다른 서버에서 돌고 있는 작업의 취소는 다음 heartbeat 까지 늦다.
- 연결을 지운 뒤 끝난 작업은 `SourceCollectionInstance` 의 진행 목록에 실행 ID 하나를 남긴다 (기능 영향 없음, 재시작하면 사라진다).
- MySQL·MariaDB 전에 정할 공통 계약 : ① 이름 공간 — MySQL 의 데이터베이스를 `catalog` 와 `schema` 중 어디에 둘지 (`SourceCollectionSpec.Namespace`), ② 식별자 비교 규칙을 서버 설정(`lower_case_table_names`)으로 채울지, ③ `SourceJdbcSettings.connectionProperties` 의 DBMS 별 속성(시간 초과 단위가 ms 인 드라이버, 읽기 전용·소켓 팩토리 속성 이름), ④ `SourceTableType` 의 확장 여부, ⑤ 인덱스 접두 길이(MySQL `col(10)`)를 `SORT_CD`·`EXPR_CN` 중 어디에 둘지, ⑥ CHECK 식 표현(MySQL 8.0.16+), ⑦ PARTIAL 요청 이름의 비교 규칙(지금은 원본 그대로 비교).
