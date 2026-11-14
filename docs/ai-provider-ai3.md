# AI-3 — Spring AI 기반 AI Provider 관리 · Ollama 연동

> 상태 : **완료 (2026-11-14, 후속 안정화 포함 — 12장)**. 등록 : [AI 로드맵 3단계](ai-metadata-roadmap.md#3단계-ollama-연결-및-provider-추상화). 마이그레이션 V8 (`V8__ai_provider.sql`).
> 후속 : **시스템 기본 AI Provider** (V9, 13장).
> 실제 업무 AI 기능(데이터 사전 생성·품질 점검·관계 추정·Tool Calling·MCP)·스트리밍·OpenAI 호환 Provider 는 구현하지 않았다.

## 1. 결정 요약

| 항목 | 결정 |
|---|---|
| AI 프레임워크 (U-10) | **Spring AI 2.0.2** (`spring-ai-ollama`, `spring-ai-client-chat`). Spring Framework 7.0.10 · Jackson 3.1.7 · Reactor 3.8 기준이라 Boot 4.1.2 BOM 과 같은 버전이다. 버전은 `gradle/libs.versions.toml` 의 `spring-ai`, BOM 은 루트 `build.gradle` 이 가져온다 |
| 자동 설정 | **starter·autoconfigure 모듈을 쓰지 않는다.** 넣은 jar 6개(ollama·client-chat·model·commons·retry·template-st)에는 `AutoConfiguration.imports`·`spring.factories` 가 없다 → 기본 `ChatModel`·`OllamaApi`·`ChatClient.Builder` Bean 이 없고 `spring.ai.ollama.*` 설정도 필요 없다 (테스트로 확인) |
| AI 활성화 설정 | 없다. Provider 등록·활성 여부는 DB(`TB_AI_PRVDR`) |
| 모델 객체 생성 | 요청마다 Provider 설정으로 만든다 (캐시 없음) → 수정·비활성화·삭제·기본 모델 변경이 재시작 없이 다음 요청부터 적용 |
| HTTP | 모든 요청이 SEC-1 `ExternalHttpClients` (연결 유형 `AI_HTTP`) 의 요청 팩토리로 나간다. Spring AI 의 기본 HTTP 클라이언트를 쓰지 않는다 |
| 스트리밍 | **지원하지 않는다 (확정, 2026-11-14)** (5장). SEC-1 판정·연결 준비 전에 `AI_PROVIDER_UNSUPPORTED` 로 막는다. 실제 필요가 생기면 별도 작업으로 검토한다 |
| 모듈 (U-01 의 AI 부분) | 설정·호출(`core/ai`)은 `lexorma-core`, 관리자 API 는 `lexorma-system` (`system/ai/controller`) — SEC-1 과 같은 배치. 이후 AI 기능(data 등)은 core 의 `AiProviderClients.call` 을 쓴다 |
| 설정 범위 (U-04) | Provider 는 시스템 전역, 관리는 ADMIN 만 (메뉴·API) |
| 인증 정보 | 받지 않고 저장하지 않는다 (Ollama 는 필요 없다). 비밀값 키 없이 동작한다 |

## 2. 구조

```text
관리자 화면 (시스템 관리 › AI Provider 관리)
  └─ /api/ai/providers/**  (AiProviderRestController, ADMIN, 요청 로그 본문 [PROTECTED])
        ├─ AiProviderService      설정 저장·검증 (연결하지 않는다)
        └─ AiProviderClients      연결 테스트 · 모델 목록 · 테스트 프롬프트 · call(이후 AI 기능)
              1. Provider 를 DB 에서 읽는다 (활성 여부 확인 — 호출 경로만)
              2. ExternalHttpClients.open(AI_HTTP, baseUrl, 실행 컨텍스트[, 제한 시간])
                   → SEC-1 판정·연결 시도 기록·주소 고정 (차단이면 연결하지 않음)
                   연결 테스트·모델 목록은 바로 연다. 모델 호출(테스트 프롬프트·call)은 첫 HTTP 요청 때 연다 (LazyTarget)
              3. AiProviderAdapter(유형별) 가 그 요청 팩토리로 Spring AI 모델 객체를 만든다
                   OllamaProviderAdapter : OllamaApi(RestClient = SEC-1 팩토리, WebClient = 거부)
                                           → OllamaChatModel(재시도 없음, 모델 내려받기 없음)
              4. ChatClient.create(chatModel) 로 호출, 끝나면 HTTP 대상을 닫는다
```

- `AiProviderAdapter` 는 유형별 **생성 책임**만 가진다 (`chatModel`, 선택 기능 `models`). 프롬프트·호출·구조화 응답·Tool Calling 은 Spring AI(`ChatModel`·`ChatClient`)를 그대로 쓰고 다시 만들지 않는다.
- `AiProviderClients.call(providerId, model, context, Function<ChatClient, T>)` : 이후 AI 기능의 호출 경로. ChatClient 는 함수 안에서만 쓴다 (끝나면 연결을 닫는다). 실패는 `AI_PROVIDER_CALL_FAILED`, SEC-1 차단은 `EXTERNAL_CONNECTION_BLOCKED`, 비활성은 `AI_PROVIDER_DISABLED`.
- DB 트랜잭션 밖에서 부른다 (AI 응답을 기다리는 동안 DB 연결을 잡지 않는다).

## 3. Provider 설정 (`TB_AI_PRVDR`)

| 컬럼 | 내용 |
|---|---|
| `AI_PRVDR_SN` | 일련번호 (`SQ_AI_PRVDR`) |
| `PRVDR_NM` | 이름 (필수, 100자, 중복 금지 — 서버는 대소문자 무시, DB 는 `UX_AI_PRVDR_01`) |
| `PRVDR_TYPE_CD` | 유형 코드 (지금 `OLLAMA`). 문자열이라 유형을 더해도 테이블을 바꾸지 않는다 |
| `BSC_URL_ADDR` | AI 서버 주소 : http·https, 호스트 필수(SEC-1 과 같은 호스트 형식 검증), 계정 정보·쿼리·# 금지, 255자, 끝의 `/` 제거. 경로는 허용 (리버스 프록시 접두 경로) |
| `BSC_MDL_NM` | 기본 모델 ID (선택. Ollama 형식 `[A-Za-z0-9][A-Za-z0-9._:/-]*`) |
| `ACTVTN_YN` | 활성 여부 |
| `PRVDR_EXPLN` | 설명 (1000자) |
| 감사 컬럼 | `BaseEntity` (등록·수정 일시와 사용자) |

- 기본 Provider·허용 규칙을 자동으로 만들지 않는다. 화면은 `http://127.0.0.1:11434` 를 **안내·입력 도움**으로만 보여 준다.
- 등록·수정은 저장만 한다 (이름을 풀거나 연결하지 않는다).
- 프로젝트 0(LEXORMA 예제)에 단어 `AI`·`제공자`, 용어 6개, 물리 테이블·컬럼 11개, 논리 엔터티·속성·식별자, 매핑, 예제 인덱스 `UX_AI_PRVDR_01` 을 넣었다 (V4 와 같은 규칙).

## 4. SEC-1 통합

| 요청 | Spring AI 전송 | SEC-1 |
|---|---|---|
| 연결 테스트 (`GET /api/tags`) | `OllamaApi.listModels` → RestClient | 적용 (요청 팩토리) |
| 모델 목록 (`GET /api/tags`) | 같음 | 적용 |
| Chat (`POST /api/chat`, stream=false) | `OllamaChatModel.call` → RestClient | 적용 |
| 스트리밍 Chat · 모델 내려받기(`/api/pull`) | WebClient | **보내지 않는다** (거부하는 교환 함수) |
| 모델 정보(`/api/show`)·임베딩 | RestClient | 쓰지 않는다 (쓰게 되면 같은 팩토리로 적용된다) |

- 요청 팩토리는 `GuardedHttpTarget.requestFactory()` 이다 : 판정 때 푼 주소에만 연결(DNS 재조회 없음), 소켓 단계 재확인(`PinnedSocket`), 프록시·리다이렉트·자동 재시도·연결 재사용 없음, TLS 검증(원래 호스트명) 그대로. 요청 한 번 = 판정·연결 시도 기록 한 번.
- Spring AI 의 자동 재시도(`RetryUtils.DEFAULT_RETRY_TEMPLATE`, 최대 10회)를 쓰지 않는다 (`RetryPolicy.withMaxRetries(0)`). 오류 응답 처리는 Spring AI 의 것(4xx → `NonTransientAiException`, 5xx → `TransientAiException`)에 3xx 를 더했다 — 리다이렉트를 본문으로 해석하지 않는다.
- `ExternalHttpClients` 보완 (정책은 그대로) : 차단 예외를 사유 코드를 담는 `ExternalConnectionBlockedException`(`EXTERNAL_CONNECTION_BLOCKED` 그대로)으로 바꿨고, 응답 제한 시간을 따로 받는 `open(..., responseTimeoutSeconds)` 를 더했다 (기본값보다 늘리지 못한다).
- 연결 테스트·모델 목록 응답 제한 : `lexorma.ai.test-timeout-seconds` (기본 15초). Chat : `lexorma.external.http.response-timeout-seconds` (기본 120초). 연결 제한 : `lexorma.external.http.connect-timeout-seconds` (기본 10초). 모두 설정하지 않아도 된다.
- 향후 Provider 도 같은 경로를 쓴다 : `AiProviderClients` 가 유형과 무관하게 HTTP 대상을 열고 어댑터는 팩토리만 받는다. Spring AI 의 OpenAI 모듈도 `RestClient.Builder` 를 받으므로 같은 방식으로 붙는다 (11단계에서 확인).

## 5. 스트리밍 (미지원) — 제약과 대안

> **결정 (2026-11-14)** : AI-3 은 스트리밍을 지원하지 않는다. 보안 우회를 막으려고 명시적으로 차단하고, 일반 Chat·모델 목록은 지원한다. 모델 자동 내려받기는 만들지 않는다.
> 스트리밍은 실제 필요가 생겼을 때 별도 작업으로 검토한다 — 그때 Apache HttpClient 5 비동기 전송 등으로 SEC-1 의 주소 고정·소켓 연결 보장을 유지할 수 있는지 먼저 검증한다 (아래 대안 1). 그 전까지 비동기 전송 계층을 만들지 않는다.

- Spring AI 2.0.2 의 Ollama 스트리밍(`OllamaApi.streamingChat`)과 모델 내려받기는 `WebClient` 로 보낸다. SEC-1 의 주소 고정 전송(`ExternalHttpClients`)은 동기(classic) httpclient5 클라이언트이고 `ClientHttpRequestFactory` 만 준다 — WebClient 가 쓰는 `ClientHttpConnector` 가 없다. 그대로 두면 WebClient 는 클래스패스의 기본 커넥터(JDK HttpClient)로 **SEC-1 을 거치지 않고** 나간다. 그래서 거부하는 교환 함수를 넣었다 (테스트로 서버에 요청이 가지 않음을 확인).
- 대안 (사용자 결정 필요) :
  1. **httpclient5 비동기 클라이언트 + `HttpComponentsClientHttpConnector`** (`httpcore5-reactive` 추가) : DNS 고정(DnsResolver)은 그대로 쓸 수 있지만 소켓 단계 재확인(`PinnedSocket`)은 비동기 I/O 에 없어 같은 방식으로 넣을 수 없다 → 연결 직전 주소 재확인을 다른 방법(연결 후 원격 주소 확인 등)으로 새로 만들고 검증해야 한다. 변경 범위 중간, 보안 검증 필요.
  2. **동기 전송 위에 스트리밍** : Spring AI 의 스트리밍을 쓰지 않고, 동기 RestClient 로 `stream=true` 응답 본문(NDJSON)을 읽는다 — SEC-1 은 그대로지만 Spring AI 의 스트리밍 처리(Tool Call 묶기 등)를 다시 만들게 된다 (원칙 위반 소지).
  3. **스트리밍 없이 유지** (지금) : 응답을 한 번에 받는다. 관리자 테스트·이후 배치형 AI 기능(사전 초안·품질 검사)에는 충분하다.
- 권장 : 사용자용 대화형 기능이 생기기 전까지 3 을 유지하고, 필요해지면 1 을 SEC-1 보강 작업으로 따로 진행한다.

## 6. 오류 분류

| `Failure` | 언제 | 비고 |
|---|---|---|
| `BLOCKED` | SEC-1 이 연결하지 않음 | `blockReason` : `NO_RULE`·`PORT_NOT_ALLOWED`·`HOST_NOT_ALLOWED`·`ADDRESS_NOT_ALLOWED`·`MIXED_ADDRESSES`·`BLOCKED_HOST`·`BLOCKED_ADDRESS`·`DNS_FAILED`·`INVALID_HOST`·`TARGET_CHANGED` 등 |
| `CONNECTION_REFUSED` | 연결 거부 (`ConnectException`) | |
| `TIMEOUT` | 연결·응답 제한 시간 초과 | |
| `CONNECTION_FAILED` | 그 밖의 연결 오류 (TLS 포함) | |
| `MODEL_NOT_FOUND` | Chat 의 404 | 모델 목록의 404 는 `SERVER_ERROR` (Ollama 가 아닌 서버) |
| `MODEL_REQUIRED` | 기본 모델도 요청 모델도 없음 | |
| `SERVER_ERROR` | 3xx(리다이렉트, 따라가지 않음)·4xx·5xx | `status` 에 HTTP 상태 |
| `INVALID_RESPONSE` | 응답을 해석하지 못함 (HTML·빈 JSON 등) | 연결 테스트는 응답 해석까지 해야 성공 |
| `UNSUPPORTED` | Provider 가 지원하지 않는 기능 (스트리밍, 모델 목록 없는 유형) | |

- Provider 없음 `AI_PROVIDER_NOT_FOUND`(404), 비활성 `AI_PROVIDER_DISABLED`(409), 입력 오류 `AI_PROVIDER_INVALID`(400), 미지원 유형 `AI_PROVIDER_UNSUPPORTED`(400).
- AI 서버의 오류 본문·내부 경로는 문구·로그에 넣지 않는다 (상태 코드와 분류만). 요청 로그는 `/api/ai/**` 라 본문이 `[PROTECTED]` 다.
- 비활성화된 Provider 도 연결 테스트·모델 목록은 할 수 있다 (활성화 전 확인). 테스트 프롬프트와 `call` 은 막는다.

## 7. 관리자 API · 화면

| API | 내용 |
|---|---|
| `GET /api/ai/providers` · `GET /{id}` · `GET /types` | 목록·상세·지원 유형(안내용 기본 주소, 모델 목록 지원 여부) |
| `POST` · `PUT /{id}` · `DELETE /{id}` | 등록·수정(활성화·비활성화 포함)·삭제 |
| `POST /{id}/test` | 연결 테스트 (경과 시간, 모델 수, 기본 모델 존재 여부) |
| `GET /{id}/models` | 모델 목록 (이름·계열·크기·수정 시각) |
| `POST /{id}/prompt` | 테스트 프롬프트 (4000자, 응답 20000자까지, 모델·토큰 수·경과 시간). 이력 없음 |

- 모두 `@PreAuthorize("hasRole('ADMIN')")`, 메뉴 `ai-provider` ADMIN (`MenuAuthorization`, [UI 권한 감사](ui-action-authorization-audit.md)).
- 화면 : 시스템 관리 › AI Provider 관리 (`/aiProvider`). 목록·등록/수정 대화상자·점검 대화상자(연결 테스트 / 모델 목록 — 기본 모델로 지정 / 테스트 프롬프트). 차단이면 외부 연결 보안 화면으로 안내한다.

## 8. 운영 안내

- **Ollama 등록 순서** : ① 외부 연결 보안에서 AI HTTP 규칙 등록 (예 : 호스트 `127.0.0.1`, 대역 `127.0.0.1/32`, 포트 `11434`) ② AI Provider 등록 (`http://127.0.0.1:11434`) ③ 점검 › 연결 테스트 ④ 모델 목록에서 기본 모델 지정 ⑤ 테스트 프롬프트.
- `localhost` 는 IPv4·IPv6 로 함께 풀리면 SEC-1 이 막는다 (`MIXED_ADDRESSES`) → `127.0.0.1` 을 쓴다.
- **Docker** : 컨테이너 안의 `127.0.0.1` 은 Lexorma 컨테이너 자신이다. 호스트의 Ollama 는 `host.docker.internal`(Docker Desktop) 또는 호스트 IP, 다른 컨테이너는 서비스 이름으로 등록하고 그 주소가 풀리는 IP 대역을 규칙으로 연다.
- Lexorma 는 Ollama 를 설치·실행하지 않고 모델을 내려받지 않는다 (`PullModelStrategy.NEVER`). 앱 기동 때 AI 서버에 연결하지 않는다.

## 9. 확장 방향

- **OpenAI 호환 (InfraMesh·vLLM)** : `AiProviderType.OPENAI_COMPATIBLE` 과 어댑터 하나를 더한다 — Spring AI `OpenAiApi`/`OpenAiChatModel` 에 같은 SEC-1 `RestClient.Builder` 를 넣는다. 확인할 것 : Chat Completions 형식, 모델 ID, API Key 방식, 스트리밍 형식(SSE), Tool Calling 호환, 오류 형식, `/v1/models` 지원 여부. 모델 목록이 없으면 `supportsModelList()=false` 로 두고 모델 ID 를 직접 입력한다 (화면·API 는 이미 그 경우를 처리한다).
- **인증 정보** : API Key 가 필요한 유형을 더할 때 `TB_AI_PRVDR` 에 암호문 칸을 새 마이그레이션으로 더하고 `SecretCipher.encrypt(key, SecretScope("ai-provider", …, providerId, "apiKey"))` 로만 저장한다. 그 유형만 비밀값 키가 필요하다. 화면에는 설정 여부만 보인다.
- **Tool Calling (4단계)** : `call` 의 ChatClient 에 Spring AI 의 `@Tool`·`ToolCallback` 을 붙인다. 도구는 Lexorma DB 만 읽는 별도 허용 목록이고, 프로젝트 범위는 검증한 실행 컨텍스트에서 정한다 (모델 인자로 받지 않는다). 원본 DB 도구는 AI 에 노출하지 않는다 (`ModuleBoundaryTest.executionPaths`).
- 사용할 Provider 선택(기본 Provider·프로젝트별 사용 여부, U-04 의 프로젝트 부분)과 운영 지표(U-22)는 AI 기능을 붙이는 단계에서 정한다.

## 10. 리소스 수명주기 (후속 안정화에서 확인)

| 객체 | 만드는 곳 | 소유·정리 |
|---|---|---|
| `CloseableHttpClient` + `BasicHttpClientConnectionManager` (연결 1개, 풀 없음) | `ExternalHttpClients.open` — 호출 하나마다 | `GuardedHttpTarget.close()` 가 클라이언트(와 연결 관리자·소켓)를 닫는다. `AiProviderClients` 가 try-with-resources 로 닫는다 (정상·예외·시간 초과 모두) |
| 연결 | 요청마다 새 연결, 재사용 안 함 (`ConnectionReuseStrategy` false) | 응답을 읽은 뒤 `HttpComponentsClientHttpResponse.close()` 가 본문을 소비하고 응답을 닫는다 (RestClient `retrieve().body()`) |
| `RestClient`·`OllamaApi`·`OllamaChatModel`·`ChatClient` | 호출마다 | 닫을 자원이 없다 (위 팩토리만 참조). 캐시가 없어 Provider 수정·삭제 뒤 이전 객체가 남지 않는다 |
| `WebClient` (Spring AI 가 함께 만든다) | 호출마다 | **커넥터를 직접 정한다 (거부만 하는 커넥터)**. 정하지 않으면 `WebClient.Builder.build()` 가 교환 함수와 상관없이 기본 커넥터를 만든다 — 이 클래스패스에서는 JDK `HttpClient`(SelectorManager 스레드)가 호출마다 생겨 GC 전까지 남았다 (반복 테스트에서 48개 → 수정 뒤 0개) |

전역 ChatModel 캐시·Provider 별 풀 관리자·정리 작업은 만들지 않았다 (필요 없음).

## 11. 테스트

`AiProviderIntegrationTest` (H2·PostgreSQL, 가짜 Ollama HTTP 서버 + 테스트 이름풀이, 비밀값 키 없음) :

- 기동 : ChatModel·ChatClient.Builder·OllamaApi·OllamaChatModel Bean 없음, 등록해도 연결·연결 시도 기록 없음
- 설정 : 이름·유형·주소(스킴·계정 정보·쿼리·#·비표준 숫자·와일드카드·포트 0·길이)·모델 ID·설명 검증, 이름 중복(대소문자 무시), 미지원 유형, 수정·삭제, 삭제 후 404
- SEC-1 : 규칙 없음(4가지 호출 모두 차단·기록, 서버 요청 없음) → 포트·대역 불일치 → 규칙 등록 후 재시작 없이 연결, 절대 차단 주소·메타데이터 이름, DNS 재바인딩(판정한 주소로만, 다시 풀지 않음), 혼합 응답, 이름 풀기 실패
- Spring AI : 모델 목록 해석, 테스트 프롬프트(요청 본문 model·stream=false, 응답·토큰), 지정 모델, `call` 경로, 기본 모델 변경·비활성화·삭제의 즉시 적용
- 오류 : 모델 없음 404, 서버 오류 500(재시도 없음 — 요청 1회), 모델 목록 404, 리다이렉트(대상에 요청 없음), HTML·빈 JSON, 연결 테스트 시간 초과(1초), 호출 시간 초과, 연결 거부(시도 결과 FAILED), 서버 본문이 문구에 없음
- 스트리밍 : 서버에 요청이 가지 않음, `AI_PROVIDER_UNSUPPORTED`, SEC-1 연결 시도 기록이 생기지 않음(허용 규칙이 있어도 없어도), 같은 Provider 의 일반 호출은 한 시도 한 행(CONNECTED)
- 리소스 : 정상 Chat·모델 목록·연결 테스트·`call`·연결 거부 각 30회, 시간 초과 3회, 설정 변경·비활성화 뒤에도 열린 파일 디스크립터·JDK HttpClient 스레드가 늘지 않음
- 권한 : MANAGER·DBA·USER·VIEWER 는 8개 API 모두 403

실제 Ollama 확인 결과는 12장.

## 12. 검증 기록 (2026-11-14)

| 항목 | 결과 |
|---|---|
| H2 전체 빌드 (`./gradlew build` : npm ci·React 빌드·전체 테스트) | 성공. 680개, 실패 0, 건너뜀 25 (PostgreSQL 전용) |
| PostgreSQL 전체 테스트 (임시 PostgreSQL 18.6 컨테이너의 빈 DB) | 680개 중 679개 성공. `ViewPostgresDdlTest` 1개는 임시 컨테이너의 연결 수 한도(`max_connections=100`, 캐시된 테스트 컨텍스트들의 연결 풀) 초과로 연결 실패 — 단독 실행에서 성공. AI·V0 시드·인덱스 시드 테스트의 PostgreSQL 변형도 성공 |
| V7 → V8 업그레이드 | 개발 DB(V7)를 `pg_dump` 로 복사한 DB 에서 실행 jar 기동 → V8 적용 성공. 기존 행 그대로, 예제만 추가(테이블 1·컬럼 11·단어 2·용어 6·인덱스 1). Provider 자동 등록 없음, 기동 중 AI_HTTP 연결 시도 없음. 개발 DB 자체는 바꾸지 않았다 |
| 실제 Ollama (로컬, `gemma4:e4b`) | 실행 jar(h2-local) API 로 확인 : 규칙 없음 → BLOCKED/NO_RULE, AI_HTTP 규칙 등록 → 연결 테스트 성공(152 ms, 모델 1개) → 모델 목록 → 기본 모델 지정 → 테스트 프롬프트 응답(약 10초, 토큰 수 표시) → 없는 모델 MODEL_NOT_FOUND(404, 한국어·영어 문구) → 비활성화 후 호출 409. 연결 시도는 모두 감사에 CONNECTED·DENIED 로 남았다 |
| 브라우저 (화면·라이트/다크·모바일) | **확인하지 못했다.** 처음에는 확장 미연결, 후속에서는 연결된 Chrome 이 다른 컴퓨터(Windows)라 이 컴퓨터의 개발 서버에 닿지 않았다. `npm run typecheck`·`npm run build`, 화면이 쓰는 API 를 같은 순서로 호출한 확인(한국어·영어 문구, `/api/auth/me` 의 `ai-provider` 메뉴), 코드 검토로 대신했다 |

**후속 안정화 (2026-11-14)**

| 항목 | 결과 |
|---|---|
| 스트리밍 차단 순서 | 모델 호출의 SEC-1 대상을 첫 HTTP 요청 때 열도록 바꿨다 (`AiProviderClients.LazyTarget`). 스트리밍은 HTTP 요청을 만들지 않으므로 `ExternalHttpClients.open` 에 닿지 않고 `ALLOWED` 행도 생기지 않는다. 감사 상태의 의미(ALLOWED·DENIED → CONNECTED·FAILED·BLOCKED, 한 시도 한 행)는 그대로다. `call` 의 미지원 기능은 `AI_PROVIDER_UNSUPPORTED` |
| HTTP 리소스 | 10장. JDK HttpClient 누적을 고쳤다 (거부 커넥터) |
| PostgreSQL `too many clients` | 원인 : 누수가 아니다. `pg_stat_activity` 를 0.5초마다 재 보니 테스트 컨텍스트가 하나 뜰 때마다 연결이 10개씩 늘고 모두 idle 이었다 — 테스트 컨텍스트 캐시가 구성이 다른 컨텍스트 9개를 살려 두고, Hikari 기본값(`minimum-idle` = `maximum-pool-size` = 10)이 각각 유휴 연결 10개를 잡아 최대 92개에서 한도(100 - 예약 3)에 닿았다 (AI-3 테스트가 컨텍스트 하나를 더해 넘었다). 테스트가 직접 연 JDBC 연결은 모두 닫힌다. 조치 : 테스트 프로필만 `spring.datasource.hikari.minimum-idle: 1`·`idle-timeout: 10000` (최대 크기는 기본 10). 최대 연결 92 → 18 |
| PostgreSQL 전체 (빈 DB, 원본 JDBC 테스트 포함 `LEXORMA_TEST_SOURCE_POSTGRES_URL`) | 681개, 실패 0, 건너뜀 8 (`LEXORMA_PERF` 성능 테스트) |
| H2 전체 (`./gradlew test --rerun`) | 681개, 실패 0, 건너뜀 25 (PostgreSQL 전용·성능) |
| 실제 Ollama (`gemma4:e4b`, 새 jar) | 차단 → 허용 → 연결 테스트 → 모델 목록 → 기본 모델 → 프롬프트 3회 → 없는 모델(영어) 정상, 감사는 시도마다 한 행, 실행 중 JDK HttpClient 스레드 0 |

남은 제약 : 스트리밍 미지원 (5장), 브라우저 화면 미확인 (라이트·다크·모바일은 사용자 확인 필요).

## 13. 시스템 기본 AI Provider (AI-3 후속, V9)

업무 AI 기능(사전 초안·품질 검사·관계 추정·Tool Calling 등)이 쓸 Provider 를 관리자가 **명시적으로 하나** 정한다. 로드맵에 따로 번호를 두지 않고 3단계의 후속으로 기록한다.

| 정책 | 내용 |
|---|---|
| 개수 | 최대 1개. 미지정 허용 (Provider 가 없어도, 있어도) |
| 지정 | ADMIN 이 명시적으로만. 첫 등록·연결 테스트 성공·모델 목록·활성화로 자동 지정하지 않는다. 같은 Provider 재지정은 그대로 성공 |
| 지정 조건 | 있음 · 활성 · 기본 모델 있음 · 지원하는 유형. **AI 서버 연결·SEC-1 허용 규칙은 보지 않는다** (서버가 꺼져 있어도 지정된다. 연결은 호출 때 확인) |
| 모델 | 그 Provider 의 기본 모델 (AI-3 설정 그대로, 따로 저장하지 않는다). 바꾸면 다음 요청부터. 지워지면 호출 실패 (`AI_DEFAULT_PROVIDER_MODEL_MISSING`) — 다른 모델을 고르지 않는다 |
| 비활성화 | 기본 Provider 도 비활성화할 수 있다 (긴급 차단). 지정은 남고 호출만 막힌다 (`AI_PROVIDER_DISABLED`). 다른 Provider 로 바꾸지 않는다. 다시 켜면 재지정 없이 쓴다. 화면은 경고를 보인다 |
| 삭제 | 기본 Provider 는 지울 수 없다 (`AI_PROVIDER_IN_USE`, DB `FK_AI_STNG_01` 도 막는다). 해제하거나 다른 Provider 를 지정한 뒤 지운다 |
| 장애 전환 | 하지 않는다 (자동 선택·우선순위·부하 분산 없음) |
| 반영 | 캐시 없음. 지정·변경·해제가 다음 요청부터 (재시작 불필요) |

- **저장 (`TB_AI_STNG`, V9)** : 시스템 설정 테이블이 없어 AI 설정 전용 단일 행 테이블을 만들었다. `AI_STNG_SN` = 1 (`CK_AI_STNG_01`), `BSC_AI_PRVDR_SN` → `TB_AI_PRVDR` (`FK_AI_STNG_01`, NO ACTION, NULL = 미지정). 행이 하나라 기본이 둘이 될 수 없고, Provider 마다 `기본 여부` 를 두고 여러 행을 고치는 방식의 정합성 문제가 없다. 행은 마이그레이션이 미지정으로 넣는다 — 업그레이드 때 기존 Provider 가 있어도 미지정.
- **동시성** : 지정·해제·Provider 삭제는 설정 행을 `SELECT … FOR UPDATE` 로 먼저 잡는다 (순서 : 설정 → Provider). 지정은 Provider 행도 잡고 다시 읽어 그 사이의 비활성화·수정과 차례를 지킨다. 비활성화·수정은 Provider 행만 바꾸므로 순환 대기가 없다. 결과 : 동시 지정은 마지막 것이 남고, 지정·삭제는 하나만 성공(다른 쪽 `AI_PROVIDER_IN_USE` 또는 `AI_PROVIDER_NOT_FOUND`), 지정·비활성화는 순서에 따라 "지정 + 비활성 경고" 또는 지정 거부. 실패한 요청은 롤백된다.
- **서비스** : `AiDefaultProviderService` — `get`(상태 `READY`·`NOT_CONFIGURED`·`DISABLED`·`MODEL_MISSING`·`UNSUPPORTED`)·`set`·`clear`·`resolve`(업무 기능용 : Provider ID·유형·주소·모델 검증 결과). `AiProviderClients.callDefault(context, chatClient -> …)` 가 resolve 한 Provider·모델로 AI-3 의 `call` 을 부른다 (SEC-1·지연 연결·스트리밍 차단 그대로). 유형에 따른 분기가 없다 — OpenAI 호환 유형을 더해도 그대로 쓴다.
- **API (ADMIN)** : `GET /api/ai/default-provider` (미지정이면 `status = NOT_CONFIGURED`, `provider = null`) · `PUT {providerId}` · `DELETE`. 업무 기능은 API 가 아니라 서비스를 쓴다. 메뉴 권한은 `ai-provider` 그대로.
- **화면** : AI Provider 관리 목록에 "시스템 기본" 열, 행마다 "기본으로 지정/변경"·"기본 해제" (비활성·기본 모델 없음이면 버튼을 막고 이유를 보인다), 상태 안내(미지정·비활성·모델 없음), 기본 Provider 삭제 시 안내, 요청 중 중복 클릭 방지.
- **확장** : 프로젝트별 Provider (U-04 의 프로젝트 부분)는 만들지 않았다. 더할 때는 "프로젝트 지정 → 없으면 시스템 기본" 순서로 `resolve` 앞에 한 단계를 두면 된다 (설정 행·API 는 그대로). API Key 가 필요한 유형도 같은 정책을 쓴다 (인증 정보는 그 유형을 더할 때).
- **테스트** : `AiDefaultProviderIntegrationTest` (H2·PostgreSQL) — 정책(미지정·자동 지정 없음·지정·변경·멱등·해제·지정 조건), 관리 과정의 외부 연결 없음(시도 기록·서버 요청·허용 규칙 변화 0), `callDefault`(변경·모델 변경 즉시 반영, 비활성 시 지정 유지·호출 차단·다른 Provider 로 안 바꿈, 재활성, 모델 삭제 시 실패), 삭제(서비스·FK·CHECK), 동시성(지정 대 지정 15회·지정 대 해제 15회·지정 대 삭제 8회·지정 대 비활성화 8회 — 매번 행 하나·참조 무결성), 업그레이드(V8 에 Provider 가 있어도 V9 뒤 미지정), 권한(비 ADMIN 403).
