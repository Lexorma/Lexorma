# SEC-1 — 외부 연결 보안 정책 및 관리자 허용 목록

> 상태 : **구현 (2026-11-14)**. 등록 : [AI 로드맵 SEC-1](ai-metadata-roadmap.md#sec-1-외부-연결-접근-정책-및-관리자-허용-목록-관리). 마이그레이션 V4 (`V4__external_connection_security.sql`).
> AI Provider 연동·AI 채팅·Tool Calling 은 구현하지 않았다 (AI-3).

## 1. 목적과 적용 범위

Lexorma 가 외부 시스템에 연결할 때 **시스템 관리자가 허용한 목적지로만** 연결하고, 실제 연결 목적지를 검증하며, 연결 시도와 정책 변경을 남긴다.

| 연결 유형 | 용도 | 상태 |
|---|---|---|
| `JDBC` | 원본 DB 메타데이터 조회 (연결 테스트·수집·재최신화) | 적용 |
| `AI_HTTP` | Ollama·OpenAI 호환 서버 (AI-3) | 공통 계약·어댑터(`ExternalHttpClients`) 준비, 테스트 HTTP 서버로 검증. 실제 Provider 는 AI-3 |

규칙은 유형마다 따로다 : JDBC 로 허용한 서버가 AI HTTP 에서 저절로 허용되지 않는다.

## 2. 기존 원본 DB 보안 구조 (분석)

| 항목 | SEC-1 전 (AI-2b) | SEC-1 |
|---|---|---|
| JDBC URL | 입력받지 않음. 호스트·포트·DB명을 검증해 서버가 만든다. 드라이버 고정, 옵션 허용 목록(`sslmode`) | 그대로 (다중 호스트·Unix 소켓·임의 소켓 팩토리·프록시 옵션이 들어갈 길이 없다) |
| 호스트 허용 | `LEXORMA_SOURCE_ALLOWED_HOSTS` (비면 제한 없음) | 운영자 상한으로 유지 (AND). 허용은 관리자 규칙 |
| IP/CIDR | 사설·루프백은 `LEXORMA_SOURCE_ALLOWED_NETWORKS` 에 있을 때만, **공인 주소는 기본 허용** | **기본 차단**, 관리자 규칙(유형·포트·호스트·CIDR). 환경 변수는 설정했을 때만 사설·루프백 상한 |
| 절대 차단 | 미지정·링크 로컬·멀티캐스트·브로드캐스트·메타데이터 일부 | 유지 + 240/4·Azure 168.63.129.16·Teredo·IPv4 호환 IPv6·IPv4 를 품은 IPv6(mapped·NAT64·6to4)·메타데이터 이름 |
| DNS 재바인딩 | 실제 소켓이 연결하는 주소를 정책으로 다시 확인 | 판정 때 푼 **주소 집합에 고정** + 규칙 대역·포트·상한을 소켓이 다시 확인 (`PinnedSocket`) |
| 소켓 연결 시점 | `SourceGuardedSocketFactory` (PostgreSQL `socketFactory`) | 같은 자리, 판정 결과(`ConnectionPin`)로 확인. 프록시 없음 |
| 관리자 설정 | 없음 (환경 변수·재시작) | 화면·API, DB 저장, 즉시 반영 |
| 감사 | 없음 (요청 로그만) | 연결 시도·정책 변경 |
| AI HTTP | 없음 | 공통 어댑터 |

- 실제 원본 DB 접속은 `SourceToolExecutor.connect()` 한 곳에서만 일어난다 (연결 테스트·수집·재최신화 모두, 연결 풀 없음 — 요청마다 새 연결). SEC-1 은 이 자리에 붙였다.
- **접속은 PostgreSQL 만 가능**하다 : Oracle·MySQL·MariaDB 는 연결 설정만 저장되고 접속 단계에서 `SOURCE_DRIVER_NOT_AVAILABLE` 로 막힌다. 그래서 드라이버별 소켓 검증 한계(지시서 27.1)는 지금 해당하지 않는다. 다른 DBMS 를 열 때 그 드라이버의 소켓 생성 제어를 먼저 확인한다 (MySQL Connector/J `socketFactory`, MariaDB `socketFactory`, Oracle thin 은 별도 조사 — 보장할 수 없으면 열지 않는다).
- 재사용 : JDBC URL 생성·옵션 허용 목록, 소켓 팩토리 자리, 절대 차단 목록(그대로 포함), 접속 오류 분류. 새로 : 규칙 저장·판정, 주소 고정, 감사, 관리 화면, HTTP 어댑터.

## 3. 관리자 허용 목록 정책

- **기본 차단** : 그 유형의 활성 규칙이 없으면 연결하지 않는다.
- 규칙 항목 : 연결 유형, 호스트명(선택), 허용 대역 CIDR(필수), 포트(필수, 하나), 활성, 설명. 등록자·수정자·일시는 감사 컬럼.
- 평가 : **한 규칙**이 유형·포트·호스트(정한 경우)·대역을 모두 만족해야 허용. 서로 다른 규칙의 조건을 섞지 않는다. 와일드카드 호스트·포트 범위는 없다.
- 등록 거부 : 너무 넓은 대역(IPv4 `/8`, IPv6 `/16` 보다 넓음 — `0.0.0.0/0`·`::/0` 포함), 절대 차단 대역 안의 대역, 메타데이터 이름, 대역 밖의 숫자 호스트, 같은 규칙 중복, 포트 범위 밖, 잘못된 형식.
- 겹치는 넓은 대역(예 `169.0.0.0/8`)은 등록되지만, 그 안의 절대 차단 주소는 여전히 막힌다.
- `localhost` 처럼 IPv4·IPv6 둘 다로 풀리는 이름은 한 규칙의 대역이 모든 주소를 담을 수 없어 차단된다 (`MIXED_ADDRESSES`) — 숫자 주소(`127.0.0.1` 또는 `::1`)로 등록·연결한다. 일부 주소만 허용하거나 한쪽 주소로만 연결하지 않는다. 사유 코드는 `MIXED_ADDRESSES` 그대로이고, 연결 테스트·HTTP 오류와 감사 화면은 주소가 IPv4·IPv6 로 섞였을 때 "호스트가 IPv4와 IPv6 주소로 동시에 해석되어 … 명시적인 IP 주소를 사용하세요" 로 원인을 알려 준다 (그 밖의 일부 허용은 "여러 주소로 해석되었지만 그 주소를 모두 담는 허용 규칙이 없습니다").
- 호스트 비교 : 소문자, 끝의 `.`·IPv6 대괄호 제거, **숫자 IPv6 주소는 RFC 5952 표기로 맞춘다** (`NetworkAddresses.normalizeHost` — `::1`·`[::1]`·`0:0:0:0:0:0:0:1`·`0000:…:0001` 이 같다). 규칙 등록·수정(저장·중복 검사), 판정, 소켓(`PinnedSocket`)·HTTP 이름풀이가 같은 정규화를 쓰고, 판정은 저장된 규칙 호스트도 다시 정규화하므로 예전에 비정규 표기로 저장된 규칙도 맞는다. 숫자 주소는 이름을 풀지 않는다. IPv4-mapped(`::ffff:…`)는 표기를 바꾸지 않고 품은 IPv4 로 판정하며, zone(`%eth0`) 표기는 받지 않는다.

## 4. 절대 차단 대상

`NetworkAddresses.isBlocked` (관리자 규칙으로도 열리지 않는다) :

- 미지정 `0.0.0.0/8`·`::`, 링크 로컬 `169.254.0.0/16`(169.254.169.254 포함)·`fe80::/10`, 멀티캐스트(`224.0.0.0/4`·`ff00::/8`), 예약·브로드캐스트 `240.0.0.0/4`
- 클라우드 메타데이터 : `100.100.100.200`(Alibaba), `168.63.129.16`(Azure), `fd00:ec2::254`(AWS IPv6), 이름 `metadata`·`metadata.google.internal`·`metadata.goog`·`instance-data`·`instance-data.ec2.internal`
- IPv6 우회 표기 : Teredo `2001::/32`, IPv4 호환 `::a.b.c.d`, IPv4 를 품은 IPv6(IPv4-mapped `::ffff:`, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`)는 **품은 IPv4 로 다시 본다**
- 비표준 숫자 표기(`127.1`·`2130706433`·`0x7f.1`·`010.0.0.1`)는 주소로도 이름으로도 받지 않는다

루프백(`127.0.0.0/8`·`::1`)과 사설망은 절대 차단이 아니다 : 관리자가 그 대역을 명시한 규칙이 있을 때만 열린다.

## 5. 호스트·IP/CIDR·포트 검증 (판정 순서)

`ExternalConnectionGuard` :

1. 호스트 형식·포트 범위 (`INVALID_HOST`) → 메타데이터 이름 (`BLOCKED_HOST`) → 숫자 호스트의 절대 차단 (`BLOCKED_ADDRESS`)
2. 그 유형의 활성 규칙 중 포트가 같은 것 (없으면 `NO_RULE`, 같은 호스트의 다른 포트만 있으면 `PORT_NOT_ALLOWED`) → 호스트를 정한 규칙이면 호스트도 같은 것 (`HOST_NOT_ALLOWED`)
3. 이름 풀기 (A·AAAA, 실패는 `DNS_FAILED` — 허용하지 않는다) → 모든 주소의 절대 차단 (`BLOCKED_ADDRESS`) → 운영자 상한 (`OPERATOR_LIMIT`, JDBC)
4. 모든 주소를 담는 한 규칙이 있으면 허용, 없으면 `MIXED_ADDRESSES`(일부만) 또는 `ADDRESS_NOT_ALLOWED`. 다른 주소로 다시 시도하지 않는다
5. 판정 중 오류는 `POLICY_ERROR` 로 차단

판정 결과(`ConnectionDecision`) : 유형, 요청 호스트·포트, 검증한 주소, 허용 여부, 적용 규칙 ID, 차단 사유, **정책 변경 식별자**(마지막 규칙 변경 이력 번호), 요청 식별자, 시도 기록 ID, 소켓 고정값(`ConnectionPin`). 비밀번호·API Key·인증 헤더는 담지 않는다.

**정책 변경과 캐시** : 규칙은 판정마다 새 트랜잭션으로 읽는다 (캐시 없음) — 바꾼 규칙은 다음 연결부터 바로 적용된다. 원본 DB 는 연결 풀이 없고, HTTP 는 요청마다 새 연결이라 정책을 바꾼 뒤 예전 연결이 재사용되지 않는다. DNS 는 판정마다 푼다 (JVM 의 이름 캐시는 그대로 — 정책 캐시와 별개).

## 6. DNS 재바인딩 방어

판정 때 한 번 확인하는 것으로 끝내지 않는다 : **실제 소켓이 연결 직전에 판정 결과로 다시 본다** (`PinnedSocket`).

- 연결 주소가 판정 때 푼 주소 집합에 있어야 하고, 절대 차단이 아니며, 적용 규칙의 대역·포트와 운영자 상한에 맞아야 한다. 아니면 연결하지 않는다 (`BlockedConnectionException` → 시도 결과 `BLOCKED`·`TARGET_CHANGED`).
- 풀리지 않은 이름으로 연결하려 하면 다시 풀지 않고, 판정한 이름일 때만 판정한 주소로 연결한다.
- 소켓은 `Proxy.NO_PROXY` 로 만든다 : JVM 의 `socksProxyHost` 등으로 다른 곳을 거쳐 나가지 않는다.
- TLS 는 이 소켓 위에서 원래 호스트명으로 검증한다 (SNI·인증서 검증 유지, 검증을 끄지 않는다).

## 7. JDBC 적용 방식

- PostgreSQL : 드라이버의 `socketFactory`(=`SourceGuardedSocketFactory`)가 서버가 넣은 속성(`ConnectionPin` : 호스트·포트·검증 주소·규칙 대역·운영자 상한)으로 `PinnedSocket` 을 만든다. 속성이 없으면 어디에도 연결하지 않는다. 사용자 옵션은 `sslmode` 뿐이라 이 속성을 바꿀 수 없다. `sslmode=verify-full` 의 호스트명 검증은 그대로다.
- 다중 호스트·포트 우회·호스트 인코딩·소켓 설정 변경·Unix Domain Socket·프록시 옵션 : URL 을 입력받지 않고(검증한 단일 호스트로 서버가 만든다) 옵션은 허용 목록이라 들어갈 길이 없다. 호스트 표기 검증도 비표준 숫자·쉼표·콜론을 막는다.
- 연결 흐름 (`SourceToolExecutor.connect`) : 드라이버 확인 → 판정 + 시도 기록 → 차단이면 `SOURCE_HOST_NOT_ALLOWED`(사유 포함, 연결 테스트 응답에도 사유) → 고정한 속성으로 연결 → 결과 기록(연결됨 / 실패 / 연결 시점 차단). 결과를 기록하지 못하면 연 연결을 닫는다.
- Oracle·MySQL·MariaDB : 접속하지 않는다 (2장).

## 8. AI HTTP 공통 계약 (AI-3 연계)

`core/network/http/ExternalHttpClients` — **Lexorma 의 외부 HTTP 연결은 모두 여기서 연다. AI-3 은 별도 HTTP 클라이언트를 만들지 않는다.**

```java
try (GuardedHttpTarget target = externalHttpClients.open(ConnectionType.AI_HTTP, URI.create("http://ollama.internal:11434"), context)) {
    RestClient client = RestClient.builder().requestFactory(target.requestFactory()).build();
    ...
}
```

- 전송 : Apache HttpClient 5 (Spring Boot BOM 버전, `lexorma-core` 의 implementation 의존성 — 다른 모듈은 Spring `ClientHttpRequestFactory` 로만 쓴다). Spring `HttpComponentsClientHttpRequestFactory` 위에 연결 결과 기록 인터셉터.
- 이름 풀기(`DnsResolver`)는 판정 때 검증한 이름에 검증한 주소만 돌려준다 (다시 풀지 않는다). 다른 이름은 풀리지 않는다. 소켓은 `PinnedSocket` (다른 포트·주소 차단, 프록시 없음).
- TLS : `DefaultClientTlsStrategy.createDefault()` (JVM 신뢰 저장소, 호스트명 검증, 원래 호스트명으로 SNI).
- 리다이렉트를 따라가지 않는다 (3xx 를 그대로 돌려준다 — 따라갈지는 AI-3 이 새로 판정해 정한다). 경로 계획도 프록시 없음(`DefaultRoutePlanner`), 자동 재시도·쿠키·인증 캐시 없음, **요청마다 새 연결** (연결 재사용 안 함).
- 스킴 http·https 만, 주소의 계정 정보 금지. 차단이면 `EXTERNAL_CONNECTION_BLOCKED`(사유).
- 대상은 요청 한 번(또는 한 작업)마다 연다. 첫 응답·첫 실패를 시도 결과로 남긴다.
- 운영자 환경 변수 상한(`LEXORMA_SOURCE_ALLOWED_HOSTS`·`LEXORMA_SOURCE_ALLOWED_NETWORKS`)은 원본 JDBC 전용이라 AI HTTP 에는 적용되지 않는다 — AI HTTP 는 관리자 규칙(+ 절대 차단)만 본다.
- **로컬 Ollama (AI-3 권장)** : 기본 주소는 `http://127.0.0.1:11434` (`localhost` 는 IPv4·IPv6 로 함께 풀려 막힐 수 있다 — 3장). 관리자가 직접 등록할 규칙 예 : 연결 유형 AI HTTP · 호스트 `127.0.0.1` · CIDR `127.0.0.0/8` · 포트 `11434` · 활성. **이 규칙은 자동으로 만들지 않는다.**
- AI-3 이 할 것 : Provider 등록·설정 UI, 모델 목록·채팅, 응답 스트리밍 처리, 리다이렉트를 따를지 정책, 인증 헤더(로그·감사에 남기지 않는다).

## 9. 감사 정책

| 감사 | 저장 | 트랜잭션 |
|---|---|---|
| 정책 변경 (`TB_EXTRL_CNTN_RULE_HSTRY`) | 규칙 ID, 작업(CREATE·UPDATE·DELETE·ENABLE·DISABLE — 활성만 바뀌면 ENABLE·DISABLE), 변경 전후 JSON, 요청 ID, 수행자·시각 | 규칙 변경과 **같은 트랜잭션** (변경이 커밋되면 이력도 있다). 실패한 요청(검증 400)은 커밋되지 않아 이력이 없고 요청 로그에만 남는다 |
| 연결 시도 (`TB_EXTRL_CNTN_HSTRY`) | 유형, 목적지 호스트·포트, 검증한 주소, 적용 규칙, 결과(ALLOWED·DENIED → CONNECTED·FAILED·BLOCKED), 사유, 실행 유형(USER·ASYNC·SCHEDULED), 요청 ID, 수행자·시각 | 부른 쪽과 **따로 커밋** (REQUIRES_NEW : 부른 쪽이 롤백해도 남는다). 판정을 연결 전에 남기고 허용된 연결은 결과로 고친다 (한 시도 한 행 — 중복 없음) |

- 비밀번호·JDBC 인증 정보·API Key·인증 헤더·URL 은 남기지 않는다 (칼럼이 없다).
- **판정 기록이 실패하면 연결하지 않는다** (`EXTERNAL_CONNECTION_AUDIT_FAILED`, 정책 차단과 다른 코드). 연결 결과 기록이 실패하면 연 연결을 닫는다 (실패 결과의 기록 실패는 로그만 — 이미 연결이 없다).
- 프로젝트 ID 를 두지 않는다 : 시스템 보안 감사라 프로젝트를 지워도 남는다.

## 10. 관리자 API

`/api/security/**` (`ConnectionSecurityRestController`, lexorma-system) — 전부 ADMIN.

| API | 설명 |
|---|---|
| `GET /api/security/connection-policies?type=&keyword=` | 규칙 목록 |
| `POST /api/security/connection-policies` | 등록 (`RuleRequest` : connectionType·host·cidr·port·enabled·description) |
| `PUT /api/security/connection-policies/{id}` | 수정·활성화·비활성화 |
| `DELETE /api/security/connection-policies/{id}` | 삭제 |
| `GET /api/security/connection-policies/unmatched` | 허용 규칙이 없는 등록 연결 (이름을 풀어 판정, 기록 안 함) |
| `GET /api/security/connection-policies/settings` | 절대 차단 안내 |
| `GET /api/security/connection-audits?type=&result=&keyword=&user=&from=&to=&page=&size=` | 연결 시도 (result = ALLOWED·DENIED·결과 코드) |
| `GET /api/security/policy-audits?page=&size=` | 정책 변경 |

검증 실패는 `400 CONNECTION_RULE_INVALID`(사유), 없는 규칙은 `404 CONNECTION_RULE_NOT_FOUND`. 수행자는 로그인 세션에서 정한다 (요청 값의 사용자·관리자 여부를 쓰지 않는다). 요청 로그 본문은 그대로 남는다 (비밀값이 없다).

## 11. 권한

- 역할은 계정 전역이고 프로젝트별 역할이 없다 : **ADMIN 역할 = 시스템 관리자**. 새 권한 모델 없음 (지시서 27.4 해당 없음).
- 규칙·감사 조회·변경 모두 ADMIN. MANAGER·DBA·USER·프로젝트 관리자는 403 (`MenuAuthorization` `connection-security` = ADMIN, 화면 메뉴도 숨김).
- 원본 DB 연결 테스트·수집 : 기존 권한(USER + 그 프로젝트의 관리자 또는 ADMIN)에 이 정책이 더해진다.

## 12. DB 마이그레이션 (V4)

- 적용된 최대 버전 V3, 남아 있던 V6 파일은 저장소에서 빠졌다 (커밋되지 않은 다른 작업, 인덱스에서도 해제됨) → **V4**, PostgreSQL·H2 같은 내용 (시퀀스 표기만 다름).
- 테이블 3개(`TB_EXTRL_CNTN_RULE`·`_RULE_HSTRY`·`TB_EXTRL_CNTN_HSTRY`) + 시퀀스 3 + 인덱스 4 + COMMENT, 예제(단어 외부·사유, 용어 10, 물리·논리·매핑·예제 인덱스) — 표준화 품질 진단 0건 유지.
- **예제 사전·모델 행의 성격** : 초기 데이터는 프로젝트 0(LEXORMA)에 Lexorma 자신의 스키마를 예제 사전·물리·논리 모델로 담는다 (V1 이후 V2·V3 도 같은 방식). V4 의 예제 행은 SEC-1 의 새 테이블 3개를 그 규칙대로 설명하는 것이며 SEC-1 과 무관한 데이터가 아니다. `V0SeedMigrationTest` 가 실제 스키마의 모든 `TB_` 테이블·컬럼이 예제에 COMMENT 와 같게 있고 엔터티·속성 매핑이 1:1 인지 검사하므로, 빼면 이 검사와 표준화 품질 0건 검사가 실패한다. 모든 INSERT 는 프로젝트 0 이 있고 같은 이름이 없을 때만 넣는다 (UPDATE·DELETE 없음). 업그레이드하는 DB 에서도 프로젝트 0 이 있으면 들어간다.
- 기존 데이터 변경·삭제 없음, 기존 데이터를 옮기지 않았다. **관리자 허용 규칙을 자동으로 만들지 않는다** (14장).
- **Flyway 주의** : V4 는 한 번 적용한 뒤 바꾸지 않는다 (바꾸면 `CHECKSUM_MISMATCH` 로 기동 실패). `outOfOrder=false`(기본값, 설정에서 바꾸지 않는다)를 유지한다 — V4 보다 높은 버전이 먼저 적용된 DB 는 `RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED` 로 기동이 멈추므로 별도 검토가 필요하다. 확인한 환경(개발 DB V0~V3, 테스트 DB)에는 V6 적용 이력이 없다 (V6 은 커밋된 적 없고 내용은 V0 에 통합됨). V0~V3 는 고치지 않는다. 임시 PostgreSQL 에서 V0~V3 DB 에 V4 추가·새 DB V0~V4·재기동 검증을 재현해 확인했다.

## 13. 테스트

| 구분 | 내용 | 결과 |
|---|---|---|
| 주소 단위 (`NetworkAddressesTest`) | 절대 차단 IPv4·IPv6·mapped·NAT64·6to4·Teredo, 표기(비표준 숫자·와일드카드·다중 호스트), CIDR·IPv6 표기, IPv6 숫자 호스트 정규화(대괄호·대소문자·확장 표기, mapped·zone 유지), 고정 소켓(이름 재조회 없음·다른 이름 차단·시스템 SOCKS 프록시 무시·IPv6 표기 차이) | 5 통과 |
| 원본 단위 (`SourceAddressPolicyTest`) | 운영자 상한, 소켓 팩토리(판정 없음·재바인딩·다른 포트·상한 밖 차단), 오류 분류 | 통과 |
| 정책 통합 (`ExternalConnectionSecurityIntegrationTest`, H2·PostgreSQL) | SEC-01~15·19·20 판정, SEC-10~13·30 절대 차단·등록 거부, SEC-21~25·29 권한·변경 감사, SEC-26~28 연결 감사·등록 연결 안내·인증 정보 없음, SEC-16~18 HTTP(판정 주소로만·DNS 1회·리다이렉트 미추적·프록시 우회 없음)·TLS(자체 서명 인증서 거부·실패 기록), IP6-01~08(표기가 다른 같은 IPv6 주소·저장된 비정규 규칙·mapped 우회·절대 차단 다른 표기·`[::1]` 실제 HTTP 연결), `MIXED_ADDRESSES` 안내 문구(코드·감사 유지) | 7 통과 |
| JDBC 실제 PostgreSQL (`SourceJdbcSecurityIntegrationTest`, 기존 `SourceJdbcCollection*`) | 허용 규칙으로 수집·재최신화, 규칙 끄면 연결 테스트·수집 차단(사유), 다시 켜면 열림, 감사 CONNECTED·DENIED, 드라이버 소켓 고정(판정 없음·재바인딩 차단) | 1 + 9 + 2 통과 |
| 전체 | H2 `./gradlew build --rerun-tasks`(React 포함), PostgreSQL 18.6 격리 임시 컨테이너(실제 원본 JDBC 포함) | H2 658 (통과 633·건너뜀 25) / PostgreSQL 658 (통과 650·건너뜀 8), 실패 0 |
| 브라우저 (h2-local 실행 jar, 헤드리스 Chrome) | 한국어·영어 × 라이트(1440)·다크(375) : 가로 넘침·콘솔 오류 없음. 보완 후 : localhost(127.0.0.1+::1) 연결 테스트 응답·감사 화면에 IPv4·IPv6 이중 해석 안내(한·영), 확장 표기 `0:0:0:0:0:0:0:1` 은 감사에 `::1` 로 남음. 관리자 : 너무 넓은 대역 등록 거부 문구, 등록·비활성화·수정·삭제(확인), 규칙 없는 등록 연결 → 안내에서 바로 등록(유형·호스트·포트 채움), 연결 시도 감사(차단·연결 실패), 정책 변경 이력. 비관리자(NOT_APPROVE) : 메뉴 없음, API 403, 화면 "접근이 거부되었습니다" | 확인 |

DBMS 별 : PostgreSQL 만 실제로 검증했다. Oracle·MySQL·MariaDB 는 접속 자체가 없으므로 검증 대상이 아니다 (검증 완료로 표시하지 않는다).

## 14. 기존 연결 호환성

- **기본 차단 전환** : 업그레이드하면 이미 등록된 원본 연결도 관리자 규칙이 생길 때까지 연결 테스트·수집이 `SOURCE_HOST_NOT_ALLOWED`(사유 "관리자가 허용한 규칙이 없습니다")로 막힌다. 저장된 카탈로그·매핑·모델은 그대로 읽힌다.
- 규칙을 자동으로 만들지 않는다 (무제한 허용 금지). 관리 화면의 **"허용 규칙이 없는 등록 연결"** 이 목적지(호스트·포트)와 사유를 보여 주고 [규칙 등록]으로 유형·호스트·포트를 채워 준다 — 대역은 관리자가 정한다.
- 환경 변수 (원본 JDBC 전용 운영자 상한, 관리자 규칙과 AND) : 규칙이 없으면 차단, 규칙이 허용해도 설정된 상한이 거부하면 차단, 설정하지 않았으면 추가 상한 없음. AI HTTP 에는 적용하지 않는다. `LEXORMA_SOURCE_ALLOWED_HOSTS` 는 의미 그대로(상한). `LEXORMA_SOURCE_ALLOWED_NETWORKS` 는 **비면 상한 없음**으로 바뀌었다 (이전 : 비면 사설·루프백 차단 — 그 역할은 관리자 규칙의 명시적 허용이 맡는다). 설정해 둔 설치는 사설·루프백이 그 대역과 규칙 둘 다에 맞아야 한다.
- 새 절대 차단(240/4·168.63.129.16·Teredo·IPv4 호환/품은 IPv6)은 예전에 공인 주소로 열렸을 수 있다 — 원본 DB 가 그런 주소일 가능성은 낮지만 그렇다면 연결되지 않는다.
- `localhost` 로 등록한 연결은 IPv4·IPv6 둘 다로 풀리면 `MIXED_ADDRESSES` 로 막힌다 → 연결 호스트를 `127.0.0.1` 로 바꾸거나 한 주소로 풀리는 이름을 쓴다.

## 15. AI-3 연계와 남은 것

- AI-3 은 `ExternalHttpClients.open(AI_HTTP, uri, context)` 와 `requestFactory()` 로 RestClient 를 만든다. Provider Endpoint 등록은 이 규칙(관리자)과 별개의 설정이고, 등록된 Endpoint 도 규칙이 없으면 열리지 않는다.
- 남은 것 : 다른 DBMS 의 소켓 고정(그 드라이버를 열 때), HTTP 리다이렉트를 따를지 정책(AI-3), 연결 시도 감사의 보존 기간·정리(쌓이기만 한다), 규칙 동시 등록의 중복은 서비스 검사(호스트를 비운 규칙은 DB 유일 제약이 없다).
