# AI-4 Lexorma DB 기반 AI Tool Calling — 읽기 전용 메타데이터 조회 Tool

> **8차 (2026-11-17)** : 코드는 `lexorma-ai` 모듈로 옮겼다 — `lexorma-data` `ai/…` → `com.lexorma.ai.…`(tool·context·answer·evidence·budget·service·controller), 리소스 `ai/` 경로 그대로. API·도구 계약은 그대로 ([ai-module-extraction.md](ai-module-extraction.md)).

> **상태 : AI-4 전체 완료 (2026-11-15).** 기반(2026-11-14) → 후속 : 구조화 응답·근거 검증([11장](#11-ai-4-후속--구조화-응답근거-검증)) → 잔여 : `findSimilarColumns`·`getMetadataHistory`·토큰 예산·Tool Calling 미지원 모델의 서버 맥락 대체 흐름([12장](#12-ai-4-잔여--유사-컬럼변경-이력토큰-예산미지원-모델-대체-흐름)).
> 로드맵 [4단계](../reference/ai-metadata-roadmap.md#4단계-lexorma-db-기반-ai-tool-calling)의 완료 조건 점검은 [12.9](#129-로드맵-4단계-완료-조건-점검), 남은 기술적 제약은 [8장](#8-남은-작업과-제약). 앞 단계 : [AI-3 Provider](ai-provider-ai3.md), [SEC-1](../reference/external-connection-security-sec1.md).

## 1. 범위

| 구분 | 구현 | 비고 |
|---|---|---|
| Tool | 읽기 전용 메타데이터 Tool 10개 (2장 8개 + 12장 `findSimilarColumns`·`getMetadataHistory`) | — |
| 실행 범위 | 서버 실행 컨텍스트(USER·ASYNC·SCHEDULED)로 프로젝트 고정, Tool 호출마다 DB 재검증 — **세 실행 유형 모두 테스트로 검증** | — |
| 호출 제한 | Tool 호출 횟수(Spring AI 공식 상한)·전체 시간·결과 건수·문자열 길이·결과 크기 + **(12장) 토큰 예산** (요청별 컨텍스트·출력 한도, 보내기 전 입력 추정, 질의 누적) | 토큰 수는 추정 — 정확한 토크나이저 없음 |
| 응답 | 최종 답변 + 실제 실행된 Tool 기록 + **(후속) 구조화 응답(JSON Schema 검증·1회 재요청)·근거 검증(존재·범위·Tool 결과 연결·이름·소속·버전)** — 11장 | — |
| 미지원 모델 | **(12장) 서버가 질문을 규칙으로 해석해 최소한만 조회하고 그 맥락으로 답하는 대체 흐름** (`AI_TOOL_CALLING_UNSUPPORTED` 는 대체 흐름이 없는 호출에만 남는다) | 실제 미지원 모델은 설치돼 있지 않아 Mock·프록시로 검증 (12.8) |
| 화면·API | `POST /api/ai/metadata/query`, `GET /api/ai/metadata/status`, 오른쪽 아래 **AI 도우미** 버튼·창 | — |

만들지 않은 것 (지시 범위 밖) : 데이터 사전 자동 생성, 품질 점검, 관계 추정, 메타데이터 변경·승인, SQL·DDL·DML 실행, 원본 DB 조회 Tool, MCP, OpenAI 호환 Provider, 프로젝트별 Provider, 자동 장애 전환, 스트리밍, 대화 이력 저장, RAG·Vector DB, 별도 Agent 프레임워크.

## 2. Tool 목록과 계약

위치 : `lexorma-data` `com.lexorma.data.ai.tool.MetadataTools`. Spring AI `FunctionToolCallback` 으로 10개를 직접 만들고, 입력은 Tool 마다 record(`NoInput`·`TableSearch`·`TableRef`·`KeywordSearch`·`SimilarColumnSearch`·`HistoryRequest`, 필드에 `@ToolParam` 설명·필수 여부)다. 이름은 로드맵 4단계의 예시를 따랐고 영어 camelCase 다.

> `@Tool` 메서드 방식을 쓰지 않은 이유 : `@Tool` 은 Java 메서드 인자 이름으로 입력 스키마를 만들고 모델의 JSON 인자를 묶는데, 이 빌드는 `-parameters` 없이 컴파일해서 인자 이름이 `arg0` 이 된다. `-parameters` 를 전체에 켜면 Jackson·Spring 의 생성자 바인딩이 바뀌어 기존 기능(회원가입·공지 저장·변경 이력 등 17개 테스트)이 깨져 되돌렸다. record 구성요소 이름은 컴파일 옵션과 상관없이 남으므로 입력 계약이 고정된다 (`MetadataToolIntegrationTest.allowList` 가 `arg0` 이 없음을 확인).
모든 Tool 은 **현재 실행 컨텍스트의 프로젝트 하나**만 읽는다. 인자에 프로젝트·계정 ID 가 없다 (`ToolContext` 로 서버가 넘긴다).

| Tool | 인자 | 재사용한 조회 | 결과 (요약) |
|---|---|---|---|
| `listSchemas` | 없음 | `SchemaInfoRepository.findSchemaList` (`SchemaService.selectSchemas` 와 같은 쿼리) | `schemaId·schemaName·description·defaultSchema·tableCount`, 최대 20 |
| `searchTables` | `keyword?`, `schemaId?`, `limit?` | `TableDesignService.selectTableInfoData` (테이블명·테이블 코멘트·설명 검색을 ID 로 합침. 통째로 맞는 것이 없으면 단어별로 찾아 많이 맞은 테이블부터) | `tableId·schemaName·tableName·tableComment·description·approved` (+ 7차 : 결과 `matchMode`·`exactMatch`·`approximate`·`matchedTerms`·`resultCount`, 테이블별 `match` — [embedded-mcp-dec31.md 8장](embedded-mcp-dec31.md)) |
| `getTableDetail` | `tableId` 또는 `tableName` | `TableDesignService.selectTable`, `PhysicalModelService.selectTableModel`, `ColumnManageService.selectTableColumn` | 테이블 + 컬럼 수 + 인덱스(키·조건) + 제약(PK·UNIQUE·CHECK) |
| `getTableColumns` | `tableId` 또는 `tableName` | `ColumnManageService.selectTableColumn` | `columnName·description·dataType·length·precision·scale·primaryKey·nullable·unique·defaultValue·identity·referencedTable·standardTerm·sensitive·approved` |
| `getTableRelations` | `tableId` 또는 `tableName` | `PhysicalModelService.selectTableModel` | `outgoing`(이 테이블이 자식)·`incoming`(부모) : `kind`(FK=등록된 FK, LOGICAL=DB 제약 없는 모델 관계)·`origin`·컬럼 쌍·ON DELETE/UPDATE, `referenceColumnsWithoutRelation` |
| `searchStandardTerms` | `keyword`, `limit?` | `DataDictionaryService.getStandardTermsData` (용어명·약어) | 용어명·약어·도메인·데이터 타입·설명·허용값·동의어·승인 |
| `searchStandardWords` | `keyword`, `limit?` | `DataDictionaryService.getStandardWordsData` (단어명·약어·영문명) | 단어명·약어·영문명·설명·도메인 분류·형식 단어·동의어·금칙어·승인 |
| `searchStandardDomains` | `keyword`, `limit?` | `DataDictionaryService.getStandardDomainsData` (도메인명·분류) | 도메인명·그룹·분류·데이터 타입·길이·소수·단위·허용값·설명·승인 |
| `findSimilarColumns` (12장) | `columnId` 또는 `keyword`, `limit?` | 새 읽기 전용 `SimilarColumnService` (프로젝트 조건이 붙은 컬럼 저장소 조회 + 표준 용어 정확 일치) | 기준(`reference`)과 컬럼별 `columnId·columnName·logicalName·tableId·tableName·dataType·length·standardTerm·standardDomain·score·reasons`, 최대 20 |
| `getMetadataHistory` (12장) | `targetType`(TABLE·COLUMN·RELATION), `targetId` 또는 `targetName`, `includeChildren?`, `limit?` | `ModelChangeHistoryService.recent` (기존 이력 서비스에 대상 조건 조회 하나 추가) | `historyId·changedAt·targetType·targetId·targetName·action·source·changes[{field,before,after}]`, 최신 순 최대 20 |

- 검색어는 그대로와 **공백을 `_` 로 이은 것** 둘 다 찾는다 (표준 용어·단어명·테이블 코멘트는 단어를 `_` 로 잇는다 : "AI 설정 일련번호" → `AI_설정_일련번호`. 실제 모델 검증에서 띄어 쓴 검색어로 용어를 못 찾은 경우를 보완). 판정은 기존 조회 서비스의 부분 일치 그대로다.
- **근거 식별자 (후속)** : 결과에 `schemaId`·`tableId`·`columnId`·`relationId`(+ `childTableId`·`parentTableId`)·`termId`·`wordId`·`domainId`(내부 PK)를 싣는다. Tool 은 돌려준 대상의 그 시점 스냅숏(이름·소속·종류·수정 일시)을 질의 세션에 남긴다 (11장).
- `tableName` 은 물리 테이블명만 받는다 — 한글명·설명이면 `searchTables` 로 먼저 찾으라고 인자 설명과 "없음" 결과에 적었다 (실제 모델이 한글명을 넣는 경우가 있었다). 다른 이름 기반 조회와 같은 규칙(`TableNameResolver` : `스키마명.테이블명` 가능, 여러 스키마에 있으면 모호 → `tableId` 로 지정하라는 인자 오류)으로 찾는다.
- 조회 범위는 화면 조회 API 와 같다 : 모든 조회 API 가 VIEWER 이고 승인 상태로 거르지 않으므로 Tool 도 승인·미승인을 모두 보여 주고 `approved` 로 구분한다.
- `listSchemas` 만 서비스 대신 같은 조회 쿼리를 쓴다 : `selectSchemas` 는 기본 스키마가 없으면 만들기(쓰기, 프로젝트 행 `FOR UPDATE`) 때문이다.
- **보내지 않는 것** : 컬럼 예시 값(`example`, 실제 데이터일 수 있다 — U-12 결정), 원본 DB 연결·비밀값(이 Tool 들은 원본 연결을 읽지 않는다), 사용자·세션 정보, 업무 데이터 행(저장하지 않으므로 없다). 민감 컬럼은 표시(`sensitive`)만 보낸다.
- **오류 결과** (모델에게 JSON 으로 돌려주고 다음 판단을 맡긴다) : `{"error":"NOT_FOUND"|"INVALID_ARGUMENT"|"FAILED"|"RESULT_TOO_LARGE"|"UNKNOWN_TOOL","message":…}`. 업무 오류는 그 사유, 예상하지 못한 예외는 원문 없이 `Lookup failed.`.

## 3. 실행 구조

```text
POST /api/ai/metadata/query (VIEWER)  ──  ExecutionContext.user(로그인 세션)
  └ AiMetadataQueryService.query          (DB 트랜잭션 밖)
      ├ 질문 검증 (1~1000자), ExecutionContextVerifier.verify(context, VIEWER)
      ├ MetadataToolSession (실행 컨텍스트·요청 ID·마감 시각·실행 기록)  →  ToolContext
      └ AiProviderClients.callDefault(context, chatClient ->              (AI-3 : 기본 Provider·기본 모델, SEC-1 HTTP, 재시도·스트리밍 없음)
            chatClient.prompt().system(서버 지시).user(질문)
                .tools(MetadataToolset.callbacks())       ← 요청마다 등록하는 허용 목록 10개
                .toolContext(session)                     ← 모델에게 보내지 않는다
                .advisors(MetadataToolset.advisor())      ← Spring AI ToolCallingAdvisor + DefaultToolCallingManager(상한)
                .call().chatResponse())
            모델이 tool_calls → Spring AI 가 Tool 실행 → 결과를 tool 메시지로 다시 보냄 → … → 최종 답변
```

- **Spring AI 공식 기능만 쓴다** (2.0.2) : `FunctionToolCallback`·`@ToolParam`(입력 record), 요청별 `tools(...)`·`toolContext(...)`, 반복 실행은 `ToolCallingAdvisor`(2.0 에서 모델의 내부 실행을 대신한다), 호출 상한은 `DefaultToolCallingManager.maxTotalToolCalls/maxCallsPerTool` + `ToolCallLimitBehavior.THROW`. Tool 호출 JSON 프로토콜·Ollama 전용 파서·Agent 프레임워크를 만들지 않았다.
- **Provider 유형 독립** : Tool·세션·실행 경계는 `ChatClient` 만 본다. Provider 유형에 따라 다른 것은 "Tool 미지원 거절" 판별 하나뿐이고 어댑터에 있다 (`AiProviderAdapter.isToolCallingUnsupported`, Ollama : 400 + `does not support tools`). OpenAI 호환 유형은 어댑터만 더하면 같은 Tool 을 쓴다.
- **SEC-1** : AI-3 의 `LazyTarget` 그대로 — 질의 하나에서 SEC-1 판정·연결 시도 기록이 한 번이고, 모델 요청이 여러 번이어도 같은 고정 주소 대상으로 보낸다. 스트리밍 경로는 계속 막혀 있다.
- **시스템 기본 Provider 정책** 그대로 : 미지정 `AI_DEFAULT_PROVIDER_NOT_CONFIGURED`, 비활성 `AI_PROVIDER_DISABLED`, 기본 모델 없음 `AI_DEFAULT_PROVIDER_MODEL_MISSING`, 변경은 다음 질의부터, 다른 Provider·모델로 바꿔 부르지 않는다.

## 4. 보안 — 서버가 범위를 정한다

`MetadataToolset` 이 Tool 마다 감싸는 실행 경계 (`Guarded`) :

1. **ToolContext 의 세션이 없으면 실행하지 않는다** (`PROJECT_ACCESS_DENIED`) — 질의 서비스를 거치지 않은 직접 실행·위조된 값 포함.
2. **Tool 호출마다 `ExecutionContextVerifier.verify(context, "VIEWER")`** : 프로젝트 존재·활성, (USER·ASYNC) 요청자 계정 활성·멤버십·현재 DB 역할. 실패는 모델에게 돌려주지 않고 **질의 전체를 끝낸다** (`CustomException` 이 Spring AI 를 지나 그대로 나간다).
3. **읽기 전용 트랜잭션** (`TransactionTemplate.readOnly`, 30초) 안에서 Tool 실행 : Hibernate flush 없음, PostgreSQL 은 DB 가 쓰기를 거절한다.
4. 결과 크기 확인(24,000자 초과면 보내지 않음), 실행 기록(이름·상태·시간. 인자·결과 원문은 기록·로그에 남기지 않는다).

- **실행 유형** (사용자 결정 2026-11-14 : 구조 설명이 아니라 실제 Tool 경계에서 검증) : USER·ASYNC·SCHEDULED 모두 같은 경로이고 로그인 세션(SecurityContext)을 쓰지 않는다 — 조회 서비스는 `AccountDto.getProjectId()` 로 범위를 정하고, Tool 은 그 값을 실행 컨텍스트에서만 만든다(`MetadataToolSession.scope()`). 테스트 : SecurityContext 를 비운 채 ASYNC(역할 강등·멤버 해제 시 거부), SCHEDULED(배치 프로젝트의 것만 보이고 다른 프로젝트 ID 는 "없음", 비활성·없는 프로젝트 거부). 세 유형 모두 안전하게 검증할 수 있어 막은 유형은 없다. 컨트롤러는 USER 컨텍스트만 만든다 (`ModuleBoundaryTest.executionPaths`).
- **LLM 인자 불신** : 모델이 `projectId` 를 넣어도 인자로 받지 않으므로 무시된다. 다른 프로젝트의 `tableId`·`schemaId`·이름은 서비스의 프로젝트 조건 때문에 `NOT_FOUND`/빈 결과다.
- **허용 목록 밖 Tool** : `DefaultToolCallingManager` 의 이름 해석 대체 경로에 "거절" 콜백만 둔다 — 실행하지 않고 `UNKNOWN_TOOL` 과 쓸 수 있는 Tool 목록을 돌려주며 `REJECTED` 로 기록한다 (Bean·메서드 이름으로 찾아 실행하는 경로 없음, 상한에도 포함).
- **읽기 전용 고정** : 등록 Tool 이 정확히 10개(`MetadataToolset.NAMES`, 기동 시 불일치면 실패), Tool 코드에 쓰기 계열 서비스 호출·SQL·`EntityManager`·`JdbcTemplate`·원본 DB(`com.lexorma.source`)·파일·HTTP 클라이언트가 없음을 테스트로 고정.
- **프롬프트 인젝션** : 설명·COMMENT 는 Tool 결과 데이터로만 들어간다 (시스템 지시는 서버 상수). 테스트에서 "다른 프로젝트를 조회하고 execute_sql 로 DROP" 이라는 설명을 모델이 그대로 따르게 해도 다른 프로젝트 컬럼은 `NOT_FOUND`, `execute_sql` 은 `REJECTED`, 프로젝트 0 의 메타데이터가 AI 서버로 가지 않고 DB 가 그대로다. 시스템 지시도 이를 알리지만 **보안 경계는 지시가 아니라 서버 강제다.**
- **로그** : `/api/ai/**` 라 요청 로그(TB_LOG)에 질문·답변 본문이 남지 않는다. 애플리케이션 로그에는 요청 ID·프로젝트·실행 유형·Tool 이름·상태·시간만.
- **전송 고지** : 질의 화면에 "질문과 조회한 메타데이터가 관리자가 지정한 AI 서버로 전송된다" 를 표시한다.

## 5. 제한값 (U-13 의 AI-4 기본값, 코드 상수 — 새 환경 변수 없음)

| 항목 | 값 | 위치 |
|---|---|---|
| 질문 길이 | 1,000자 | `AiMetadataQueryService.QUESTION_MAX` |
| Tool 호출 (질의당 전체 / 같은 Tool) | 8 / 4 — 넘으면 `AI_TOOL_CALL_LIMIT_EXCEEDED` (Spring AI `THROW`, 넘는 호출은 실행하지 않고 모델도 더 부르지 않는다) | `MetadataToolset` |
| 질의 전체 시간 | 180초 — 지나면 다음 Tool 을 실행하지 않고 `AI_TOOL_CALL_LIMIT_EXCEEDED` | `MetadataToolset.TIMEOUT_SECONDS` |
| 모델 요청 하나 | AI-3 그대로 (`lexorma.external.http.response-timeout-seconds`, 기본 120초) | SEC-1 |
| Tool 조회 트랜잭션 | 30초 | `MetadataToolset` |
| 검색 건수 | 기본 10, 최대 20 (넘으면 잘라서 `truncated: true`) | `MetadataTools` |
| 검색어 | 100자 | 〃 |
| 테이블당 컬럼 / 방향별 관계 / 인덱스·제약 | 100 / 50 / 30 (넘으면 `truncated`) | 〃 |
| 설명 문자열 | 300자 (`…` 표시) | 〃 |
| Tool 결과 | 24,000자 (넘으면 `RESULT_TOO_LARGE`) | `MetadataToolset` |
| 답변 | 8,000자 (구조화 응답 스키마) | `metadata-answer.schema.json` |
| **토큰 (12.4)** : 요청 컨텍스트 / 응답 최대 / 질의 누적 | 16,384 (모델 최대 이하로 줄임) / 4,096 / 100,000 — 설정 `lexorma.ai.query.context-tokens`·`output-tokens`·`total-tokens` (선택) | `TokenBudget` |
| 구조화 재요청 | 1회 | `AiMetadataQueryService.STRUCTURED_RETRIES` |
| 서버 맥락 조회 (대체 흐름) | 질의당 5번 | `ServerContextAssembler.MAX_LOOKUPS` |

모델 요청 수는 Tool 호출 상한·재요청 상한으로 묶인다 (Tool Calling 최대 11번 : Tool 라운드 9 + 구조화 2, 서버 맥락 최대 2번).

## 6. API · 화면 · 오류

- `GET /api/ai/metadata/status` (VIEWER) : 시스템 기본 Provider 상태만 (`READY`·`NOT_CONFIGURED`·`DISABLED`·`MODEL_MISSING`·`UNSUPPORTED`). Provider 주소·이름은 주지 않는다 (ADMIN API 와 분리). AI 서버에 연결하지 않는다.
- `POST /api/ai/metadata/query` (VIEWER, 본문 `{question}`) → `{answer, model, tools:[{name,status,durationMillis}], durationMillis}` + (11장) `evidence`·`verification`·`structuredRetries` + (12장) `mode`·`fallback`·`usage`. 프로젝트는 요청에 두지 않는다 (로그인 프로젝트). POST 지만 조회라 "변경 API 는 USER 이상" 규칙의 예외 목록에 넣었다 (`UiActionAuthorizationIntegrationTest`).
- 화면 : 메뉴 화면이 아니라 **오른쪽 아래 AI 도우미 버튼 → 질의 창** (`components/ai/AiAssistant.tsx`, `AppLayout` 에 한 번 둔다. 사용자 결정 2026-11-14). 관리자 설정(AI Provider 관리)과 섞지 않는다.
  - 보이는 조건 : 메뉴 권한 `ai-metadata-query`(VIEWER, 화면 메뉴가 아니라 버튼 노출용) **그리고** 시스템 기본 Provider 가 `READY`. Provider 가 없거나 미지정·비활성·기본 모델 없음이면 버튼을 숨긴다 (상태 API 는 AI 서버에 연결하지 않고, 화면을 불러올 때·브라우저 창으로 돌아올 때 다시 본다). 창을 연 채 쓸 수 없게 되면 창에 안내를 보이고, 닫으면 버튼도 사라진다.
  - 창 : 조회 범위(현재 프로젝트), 전송 고지, 질문·답변 목록(이 브라우저 화면에만 있다 — 서버로 앞 대화를 보내거나 저장하지 않는다, 프로젝트가 바뀌면 비운다), 답변은 Markdown(공지와 같은 렌더러, HTML 실행 없음), **서버에서 실제로 실행된 Tool** 을 상태 색으로 표시, Tool 을 안 쓴 답변 경고, Enter 보내기·Shift+Enter 줄바꿈(한글 조합 중 Enter 무시), 요청 중 중복 실행 방지, Esc 로 닫기, 대화 지우기.
  - **푸터·화면 요소를 가리지 않는다** : 버튼이 보이는 동안 본문과 푸터 사이에 버튼 자리를 비운다(`.has-ai-assistant .app-footer` 여백). 푸터 윗부분이 화면에 보이면 버튼·창을 푸터 위(그 빈자리)로 올리고, 푸터가 화면 밖인 동안(긴 화면을 스크롤하는 중)에는 버튼을 본문 오른쪽 여백(스크롤바 안쪽) 안의 얇은 세로 탭(✦ + AI)으로 접는다. 모바일에서 창은 화면 전체를 쓰되 푸터 위에서 멈춘다.
  - 모양 : 파란 계열 그라데이션(흐르는 애니메이션)·반짝이는 ✦·은은한 파동, 지름 40px. 움직임 줄이기(`prefers-reduced-motion`) 설정이면 애니메이션을 끈다.
- 오류 코드 : 새로 `AI_TOOL_CALLING_UNSUPPORTED`(409, 12장부터 메타데이터 질의에서는 나지 않음), `AI_TOOL_CALL_LIMIT_EXCEEDED`(502), `AI_QUERY_INVALID`(400), (11장) `AI_STRUCTURED_OUTPUT_INVALID`(502), (12장) `AI_TOKEN_BUDGET_EXCEEDED`(422)·`COLUMN_IS_NOT_DEFINED`(400). 나머지는 재사용 — 기본 Provider 3종, `AI_PROVIDER_CALL_FAILED`(연결 거부·시간 초과·서버 오류), `EXTERNAL_CONNECTION_BLOCKED`, `PROJECT_ACCESS_DENIED`·`PROJECT_NOT_FOUND`·`EXECUTION_PERMISSION_DENIED`. Tool 인자 오류·대상 없음은 질의를 끝내지 않고 모델에게 돌려준다.

## 7. `findSimilarColumns` · `getMetadataHistory` — 기반 단계에서 남긴 이유 (12장에서 구현)

- 기반 단계(2026-11-14)에서 남긴 이유 : 기존 조회는 부분 일치 검색·같은 테이블 안 중복 검사뿐이라 "비슷한 컬럼" 의 기준을 새로 정해야 했고, 변경 이력은 결과가 크고 보낼 필드·범위를 정해야 했다.
- 12장의 결정 : 유사도는 **정해진 규칙의 점수**(12.2 — 임베딩·Vector DB·AI 판단 없음), 이력은 **바뀐 필드의 변경 전후만·최신 순 20건·예시 값과 사용자 ID 제외**(12.3). 5·12단계에서 기준을 바꾸면 이 서비스를 고친다.

## 8. 남은 작업과 제약

로드맵 4단계의 작업 범위·완료 조건은 모두 구현했다 (점검 : 12.9). 4단계 밖이거나 기술적 제약으로 남는 것 :

1. **버전 검증의 한계** (11.5) : 행 수정 일시(`MDFCN_DT`) 기준 — DB 를 직접 고친 변경, 같은 시각 정밀도 안의 연속 변경은 보이지 않고, 테이블의 버전은 그 행만이다(컬럼 변경은 컬럼 근거로 드러난다). 전역 모델 버전은 도입하지 않았다.
2. **토큰 수는 추정** (12.4) : 서버는 모델의 토크나이저를 모른다. 보내기 전 판정은 보수적 추정, 사용량은 AI 서버가 알려 준 값(실측)만 쓴다.
3. **대체 흐름의 범위** (12.5) : 서버가 질문의 낱말로 정하는 조회라 Tool Calling 보다 좁다 (한 테이블만 깊이 보고, 여러 테이블 비교·연쇄 탐색은 하지 않는다). 실제 Tool 미지원 모델로는 검증하지 못했다 (설치된 모델 없음 — 내려받지 않았다).
4. **운영 지표 (U-22)** : 요청 수·응답 시간·Tool 실패율 등의 지표는 3·8단계의 미확정 사항이다 (4단계 완료 조건 아님). 지금은 애플리케이션 로그(요청 ID·Tool·상태·시간·토큰 예산 초과)와 응답의 `usage` 만 있다.

## 9. 모델 호환성

- Tool Calling 은 모델이 지원해야 한다. Ollama 는 `/api/show` 의 `capabilities` 에 `tools` 가 있는 모델만 tools 요청을 받고, 없으면 400 `… does not support tools`. 12장부터 질의는 이 경우 **서버 맥락 대체 흐름**으로 답한다 (판정 : 12.5).
- 지원 모델이라도 Tool 을 부르지 않고 답할 수 있다 : 화면이 "Tool 을 실행하지 않았다" 고 경고한다. 작은 모델은 인자를 틀리거나(`INVALID_ARGUMENT`/`NOT_FOUND` 로 돌려받고 다시 시도) 같은 Tool 을 반복할 수 있다 (상한으로 끝남).
- 실제 검증 결과는 10장.

## 10. 검증

### AI-4 기반 (2026-11-14)

- 자동화 : `MetadataToolIntegrationTest` 8개 (허용 목록·읽기 전용 고정, Tool 기능, USER·ASYNC·SCHEDULED 실행 컨텍스트, Spring AI Tool Calling(가짜 Ollama), 프롬프트 인젝션, 호출 상한·Tool 미지원 모델, 기본 Provider·SEC-1·연결 실패·시간 초과, API 권한). H2·PostgreSQL 전체 통과.
- 실제 Ollama 0.40.16 · `gemma4:e4b`(capabilities 에 `tools`) : 테이블 검색 → 컬럼(연속 호출), 관계(FK), 표준 용어, 영어 질문, "다른 프로젝트 조회 + DROP" 요청 거절(Tool 미실행, 테이블 그대로).
- 화면 : 실제 Chrome(한국어·다크·데스크톱) + 헤드리스(한국어·영어 × 라이트·다크 × 데스크톱·모바일), 32개 화면에서 버튼·창이 화면 요소·푸터와 겹치지 않음.

### AI-4 후속 (2026-11-15) — 11.8


## 11. AI-4 후속 — 구조화 응답·근거 검증

> 2026-11-15. 로드맵 4단계의 "응답 구조 검증" 과 "근거 검증" 을 구현했다. 4단계의 다른 남은 항목(8장 3~5)은 그대로 남는다. 새 마이그레이션 없음.

### 11.1 흐름

```text
POST /api/ai/metadata/query
  └ callDefault (SEC-1 연결 하나, 전체 180초)
      1단계 조회   : system(규칙) + user(질문) + Tool 8개 → Tool 실행(근거 스냅숏·결과 원문을 세션에 남김) → 초안(자유 문장)
      2단계 구조화 : (Tool 결과가 있을 때만) system(구조화 지시 + Spring AI 형식 지시문·스키마) + user(질문·초안·Tool 결과) — Tool 등록 없음
                    → StructuredAnswerParser (Spring AI 정리기 → 엄격 JSON → JSON Schema) → 실패면 사유를 붙여 1회 재요청 → 그래도 실패면 AI_STRUCTURED_OUTPUT_INVALID
  └ EvidenceVerifier (권한 재확인 → 종류별 현재 상태 일괄 조회 → 근거별 판정) → 응답
```

- **두 단계로 나눈 이유** : 처음에는 Tool 을 쓰는 같은 요청에 JSON 형식 지시를 붙였는데, 실제 `gemma4:e4b` 가 Tool 을 건너뛰고 바로 답하거나("등록되어 있지 않다") 근거를 비워 두는 경우가 있었다. 조회는 AI-4 그대로 두고 구조화를 따로 하자 같은 질문들이 Tool 을 쓰고 근거를 들었다. 대가 : Tool 을 쓴 질의마다 모델 요청이 한 번 늘어난다.
- Tool 결과가 없으면(일반 질문) 근거가 될 것이 없으므로 2단계를 건너뛰고 근거 없는 답변(`NO_EVIDENCE`)이 된다. 초안이 이미 구조화 형식이면 그대로 검증한다.
- 2단계에서는 Tool 을 등록하지 않는다. 모델이 Tool 을 불러도 허용 목록 밖 거절 콜백만 돌고(`REJECTED`, 실행 없음), 1단계의 호출 상한·마감 시각을 넘지 못한다. 이미 받은 Tool 결과(최대 24,000자)를 넘기므로 Tool 을 다시 실행하지 않는다.

### 11.2 구조화 응답 Schema (`lexorma-data/src/main/resources/ai/metadata-answer.schema.json`, draft 2020-12)

| 필드 | 규칙 |
|---|---|
| `answer` | 필수, 문자열 1~8,000자 (Markdown) |
| `evidence` | 필수, 배열 최대 30개 |
| `evidence[].type` | 필수, `SCHEMA`·`TABLE`·`COLUMN`·`RELATION`·`TERM`·`WORD`·`DOMAIN` |
| `evidence[].id` | 필수, 정수 ≥ 0 (예제 프로젝트는 ID 0 부터 쓴다) |
| `evidence[].name` | 필수, 문자열 1~200자 |
| `evidence[].tableId` | 선택, 정수 ≥ 0 또는 null (COLUMN 의 소속 테이블) |
| `evidence[].kind` | 선택, `FK`·`LOGICAL`·null (RELATION 의 종류) |
| 그 밖의 필드 | 거절 (`additionalProperties: false`) — 모델이 `status`·`verification` 같은 검증 결과를 써 넣을 수 없다 |

- **Spring AI 공식 기능** : `BeanOutputConverter`(이 스키마로 형식 지시문 `getFormat()`, 통과한 JSON 의 객체 변환), 응답 정리기(`WhitespaceCleaner`·`ThinkingTagCleaner`·`MarkdownCodeBlockCleaner`). 스키마 검증은 Spring AI 가 함께 가져오는 networknt `json-schema-validator` 3.0.1 (Lexorma 가 직접 선언한 의존성은 아니다 — Spring AI 를 올릴 때 확인).
- **서버 검증** : 파싱만 된 것은 통과가 아니다. 엄격한 파싱(뒤에 남는 글자·중복 키 거절) 뒤 스키마로 필수·타입·허용 값·길이·개수·정의하지 않은 필드를 본다. JSON 에 없는 이스케이프(`\_`)만 그 글자로 읽는다 — 실제 모델이 Markdown 의 `_` 를 `\_` 로 적어 두 번 다 실패한 일이 있었다.
- 모델 자체의 JSON 모드(Ollama `format`)는 쓰지 않았다 : Provider 마다 다르고, 쓰더라도 서버 검증은 그대로 해야 한다.

### 11.3 재요청 정책

- 조건 : JSON 아님·스키마 불일치(필수 누락·타입·허용하지 않은 근거 종류·구조 오류) — `StructuredAnswerParser.Result.errors()` (최대 5개, 영어).
- 횟수 : 최대 **1회** (`AiMetadataQueryService.STRUCTURED_RETRIES`, 코드 상수 — 설정으로 늘릴 수 없다).
- 내용 : 처음 질문, 1단계 초안, 이미 받은 Tool 결과, 실패 사유. Tool 정의는 보내지 않는다.
- 시간 : 같은 마감 시각(180초) 안. 지났으면 `AI_TOOL_CALL_LIMIT_EXCEEDED`. 재요청에 새 시간을 주지 않는다.
- 최종 실패 : `AI_STRUCTURED_OUTPUT_INVALID` (502). 형식이 틀린 답변은 보이지 않는다.

### 11.4 근거 검증 (`EvidenceVerifier`)

| 상태 | 뜻 | 사유(`reason`) |
|---|---|---|
| `VERIFIED` | 이 질의의 Tool 결과에 있었고, 지금도 현재 프로젝트에 있으며, 이름·소속 테이블(컬럼)·관계 종류가 맞고, 조회 뒤 수정되지 않았다 | — |
| `NOT_FOUND` | 그런 ID 가 없다 | — |
| `OUT_OF_SCOPE` | 다른 프로젝트의 행이다 — 그 행의 내용(이름·설명)은 응답에 내지 않는다 (보이는 이름은 AI 가 쓴 것) | — |
| `UNVERIFIABLE` | 이 질의에서 Tool 이 돌려주지 않은 근거 / 버전(수정 일시)이 없어 변경 여부를 알 수 없다 | `NOT_RETRIEVED` / `VERSION_UNKNOWN` |
| `STALE` | Tool 조회 뒤 수정됐다 / 지워졌다 | `MODIFIED` / `DELETED` |
| `MISMATCH` | 이름이 다르다 / 컬럼이 그 테이블 소속이 아니다 / 관계 종류(FK·LOGICAL)가 다르다 | `NAME` / `PARENT` / `KIND` |

- 판정 순서 : 없음 → 다른 프로젝트 → 조회하지 않음 → 조회 뒤 변경 → 이름·소속·종류 → 버전 없음 → 확인. 같은 대상을 여러 번 들면 처음 것만 본다.
- 이름 비교 : 대소문자·앞뒤 공백 무시, 공백과 `_` 를 같게. 테이블은 물리명·테이블 코멘트, 컬럼은 컬럼명·설명, 용어는 용어명·약어, 단어는 단어명·약어·영문명, 도메인·스키마·관계는 이름.
- 답변 전체 : `VERIFIED`(모두 확인) · `PARTIAL`(일부) · `UNVERIFIED`(하나도 확인 못 함) · `NO_EVIDENCE`(근거 없음) + 개수.
- **VERIFIED 의 범위** : 그 근거의 존재·프로젝트 범위·이 질의의 Tool 결과와의 연결·이름·소속·버전을 서버가 확인했다는 뜻이다. 답변 문장 전체의 의미가 맞다는 뜻은 아니다 (화면에도 적었다).
- 권한 : 검증 직전에 실행 컨텍스트를 다시 확인한다 (Tool 과 같은 VIEWER). 회수됐으면 질의 전체가 실패한다. USER·ASYNC·SCHEDULED 모두 실행 컨텍스트의 프로젝트로만 판정한다 — AI 가 준 프로젝트 ID 는 없다(스키마에 없는 필드).
- 조회 : 종류별로 기존 저장소의 `findAllById` 한 번 (최대 7번, N+1 없음), 읽기 전용 트랜잭션 30초. 쓰기·SQL·원본 DB 없음.
- 로그 : 요청 ID·프로젝트·전체 상태·개수와, 확인되지 않은 근거의 종류·ID·상태·사유 (이름·답변 원문은 남기지 않는다).

### 11.5 메타데이터 버전

- **기준 : 행의 마지막 수정 일시 `MDFCN_DT`** (`BaseEntity` 의 `@LastModifiedDate`). 근거가 되는 7개 엔터티가 모두 가지고, 이 테이블들에는 JPA 를 거치지 않는 쓰기(벌크 UPDATE·네이티브 SQL)가 없어 앱의 모든 수정에서 바뀐다. `@Version` 열은 없고, 모델 변경 이력(`TB_MDL_CHG_HSTRY`)은 물리 테이블·컬럼·관계만 다뤄 표준 사전·스키마에는 쓸 수 없다. 그래서 새 버전 열·마이그레이션 없이 이 값을 쓴다.
- Tool 이 대상을 돌려줄 때 그 행의 수정 일시를 질의 세션에 남기고(`EvidenceCatalog` — 종류별 ID 조회 한 번), 검증 때 지금 값과 비교한다.
- 한계 : DB 를 직접 고친 변경은 보이지 않는다. 테이블의 버전은 그 행만이다 — 컬럼이 바뀌어도 테이블 행은 그대로이므로 컬럼 근거는 컬럼의 버전으로 본다. 수정 일시가 없는 행은 `UNVERIFIABLE(VERSION_UNKNOWN)` 로 둔다 (확인됨으로 처리하지 않는다).
- 엔터티 7개(`SchemaInfo`·`TableInfo`·`ColumnInfo`·`TableRelation`·`StandardTerm`·`StandardWord`·`StandardDomain`)에 `audit()` 읽기 메서드만 더했다 (기존 `TableIndex`·`AiSetting` 과 같은 방식, JSON 프로퍼티가 아니다).

### 11.6 API · 화면

- `POST /api/ai/metadata/query` 응답에 필드를 **더했다** (기존 `answer`·`model`·`tools`·`durationMillis` 그대로) : `evidence[] {type, id, name, status, reason}`, `verification {status, total, verified, invalid}`, `structuredRetries`(0·1).
- AI 도우미 창 : 답변 아래에 검증 상태 상자 — 모두 확인이면 초록("근거 n개를 … 확인했습니다"), 일부·실패·근거 없음이면 주의 색 안내. 근거 목록은 종류·이름과 ✓(확인) 또는 ! + 상태·사유. 내부 ID 는 보이지 않는다. "확인됨" 의 범위 안내 한 줄.
- 오류 코드 : `AI_STRUCTURED_OUTPUT_INVALID` (502) 추가.

### 11.7 AI-5 에서의 재사용

- 데이터 사전 초안처럼 서버가 단계를 정하는 기능은 1단계 Tool 조회(또는 서버가 고른 조회) → 2단계 구조화(그 기능의 스키마) → `EvidenceVerifier` 를 그대로 쓴다. 스키마만 기능별로 하나 더 만들고 `StructuredAnswerParser` 와 같은 방식(형식 지시문 + 엄격 파싱 + JSON Schema + 1회 재요청)으로 검증한다.
- 승인 시 재검증(5단계)에는 근거 스냅숏의 수정 일시를 저장해 두면 같은 비교를 쓸 수 있다 (지금은 질의 안에서만 들고 있다).

### 11.8 검증 결과

- 자동화 (가짜 Ollama) : `AiEvidenceIntegrationTest` 7개 — 스키마 위반 17가지(필수 누락·타입·근거 종류·ID 범위·이름 누락·정의하지 않은 필드·`status`/`verification` 써 넣기·관계 종류 값·길이·개수·JSON 아님·뒤에 남는 글자·중복 키)와 코드 블록·`\_` 처리, 1회 재요청 성공(재요청에 Tool 정의 없음·Tool 결과 전달), 2회 실패 → `AI_STRUCTURED_OUTPUT_INVALID`(세 번째 요청 없음), 재요청 중 Tool 호출 거절, 근거 13가지 판정(테이블·컬럼·FK 관계·용어·단어·도메인·스키마 확인, 없는 ID, 다른 프로젝트 테이블·컬럼, 소속 불일치, 조회하지 않은 근거, 이름·관계 종류 불일치), 근거 없음·Tool 없이 든 근거, 조회 뒤 수정·삭제(가짜 서버가 최종 답변 직전에 DB 를 바꿈), 수정 일시 없음, 검증 직전 권한 회수, SCHEDULED·ASYNC 범위. `MetadataToolIntegrationTest` 8개도 두 단계 흐름으로 통과.
- 실제 Ollama 0.40.16 · `gemma4:e4b` (격리 인스턴스) : 컬럼(테이블 1 + 컬럼 6, 7/7 확인), 관계(테이블 2 + FK 1, 3/3), 표준 용어(1/1), 영어 질문(12/12), 일반 질문(Tool 없음 → `NO_EVIDENCE`) — 모두 재요청 0회. 처음 시도(한 단계)에서 드러난 Tool 건너뛰기·`\_` 이스케이프 실패를 고친 뒤의 결과다. 일부 실패·조회 뒤 변경 같은 상태는 실제 모델로 재현하기 어려워 가짜 서버로만 확인했다.
- 화면 : 실제 Chrome(한국어·다크)에서 실제 답변의 "근거 3개 확인" 표시. 헤드리스로 일부 실패 응답(화면에 넣은 응답) 표시를 한국어·영어 × 라이트·다크 × 데스크톱·모바일에서 확인 (가로 넘침 없음, 콘솔 오류 없음).
- H2 전체 708개·PostgreSQL 전체 708개 통과 (실패 0).

## 12. AI-4 잔여 — 유사 컬럼·변경 이력·토큰 예산·미지원 모델 대체 흐름

> 2026-11-15. 로드맵 4단계의 남은 작업 범위(`findSimilarColumns`·`getMetadataHistory`, 토큰 상한, Tool 미지원 모델의 대체 흐름)를 구현하고 완료 조건을 점검했다. 새 마이그레이션·새 필수 환경 변수 없음.

### 12.1 의존성

- JSON Schema 검증기 `com.networknt:json-schema-validator:3.0.1` 을 `lexorma-data` 의 **직접 의존성**으로 선언했다 (`gradle/libs.versions.toml` `json-schema-validator`, 루트 `build.gradle` 의 `project(':lexorma-data')`). Spring AI(`spring-ai-client-chat` 2.0.2)가 가져오는 것과 같은 버전이라 해석 결과는 3.0.1 하나다 (`dependencyInsight` 로 확인). Spring AI 를 올릴 때 이 버전과 맞는지 확인한다.

### 12.2 `findSimilarColumns` (`column/service/SimilarColumnService`)

- 기존에 유사 컬럼 서비스가 없어 **읽기 전용 서비스**를 새로 만들었다 (프로젝트 조건이 붙은 컬럼 저장소 조회 + 표준 용어 정확 일치 조회만, 쓰기 없음). 임베딩·Vector DB·AI 판단을 쓰지 않는다 — **점수는 아래 규칙으로만** 계산하고, 결과에 규칙 설명(`scoring`)과 근거(`reasons`)를 함께 보낸다.
- 기준 : `columnId`(그 컬럼의 물리명·설명·표준 용어·도메인. 자기 자신은 결과에서 뺀다) 또는 `keyword`(물리명 `MBR_SN` → 그 약어의 표준 용어도 / 한글 논리명·용어명 `회원 일련번호` → 같은 이름의 용어가 있으면 그 약어·도메인도).

| 근거 | 점수 | 규칙 |
|---|---|---|
| `NAME_EXACT` | 100 | 물리명이 같다 (대소문자 무시) |
| `SAME_TERM` | 90 | 같은 표준 용어 |
| `LOGICAL_NAME_EXACT` | 85 | 설명(논리명·COMMENT)이 같다 (공백·`_` 를 빼고 비교 — 띄어쓰기 차이 무시) |
| `NAME_PARTIAL` | 60 | 한 물리명이 다른 물리명을 `_` 단위로 포함 (`UPPR_MBR_SN` ⊃ `MBR_SN`) |
| `NAME_TOKENS` | 40 + 40×겹침률 | `_` 단위 단어가 절반 이상 겹친다 (공통 단어 / 합친 단어) |
| `LOGICAL_NAME_PARTIAL` | 45 | 한 설명이 다른 설명을 포함 (공백·`_` 제외 2자 이상) |
| `SAME_DOMAIN` | +10, 단독이면 20 | 표준 용어의 도메인이 같다 |

  점수 = 가장 높은 규칙 + 도메인 가점(최대 100). 정렬 : 점수 → 테이블명 → 컬럼명. 후보는 이름·설명·용어로 300건, 같은 도메인으로 100건까지 모아 점수를 매기고 **최대 20건**(넘으면 `truncated`).
- 보안 : 현재 프로젝트만 (다른 프로젝트의 `columnId` 는 `NOT_FOUND`), VIEWER 재검증·읽기 전용 트랜잭션은 다른 Tool 과 같은 경계. 결과의 컬럼·테이블 ID 는 근거 후보로 남아 `EvidenceVerifier` 가 검증한다 — **"비슷하다" 는 판정과 "그 컬럼이 있다" 는 검증은 별개**다 (검증은 존재·범위·이름·소속·버전만 본다).

### 12.3 `getMetadataHistory` (`ModelChangeHistoryService.recent`)

- 대상 : 모델 변경 이력(`TB_MDL_CHG_HSTRY`)이 실제로 있는 **물리 테이블·컬럼·관계**만. 표준 단어·용어·도메인은 이력이 없으므로 받지 않는다(`INVALID_ARGUMENT`) — 지어내지 않는다.
- 조회 : 기존 이력 서비스에 "대상 조건(종류 + ID 목록 / 기록된 이름 / 이름 앞부분)" 조회 하나를 더했다. 로그인 프로젝트 조건이 항상 붙는다 (이력의 프로젝트로 판단 — 삭제된 대상의 이력도 같은 프로젝트면 보인다). 최신 순(`CHG_DT` → ID), 기본 10·최대 20건(`truncated`).
  - `TABLE` : `includeChildren`(기본 true)이면 지금 그 테이블의 컬럼(ID)·관계(ID)와, 이름이 `테이블명.` 으로 시작하는 컬럼 이력(지워진 컬럼)까지.
  - `targetName` 은 기록된 **물리명**이다 (컬럼은 `TABLE.COLUMN`). 지워진 대상도 찾는다.
  - **대상 없음과 변경 없음을 구분** : 이력이 없고 대상도 지금 프로젝트에 없으면 `NOT_FOUND` + "한글명이면 `searchTables` 로 먼저" 안내. 실제 모델이 `targetName` 에 한글명("AI 설정")을 넣고, 빈 목록을 받아 "이력이 없다" 고 답한 일이 있어 고쳤다.
- 결과 : 이력마다 `historyId·changedAt·targetType·targetId·targetName·action(CREATE·UPDATE·DELETE·APPROVE·REVOKE)·source(USER·AUTO_SYNC·IMPORT)` 와 **바뀐 필드만의 변경 전후**(생성·삭제는 그 시점 값, 필드 15개·값 300자까지). 사용자 ID 와 컬럼 예시 값(`example`, 실제 데이터일 수 있다 — U-12)은 보내지 않는다. 결과에 "과거 변경이며 지금 메타데이터가 아니다" 는 안내(`note`)를 싣고, 시스템 지시에도 적었다.
- 근거 : 새 근거 종류 **`HISTORY`**(이력 한 건, `historyId`). 이력은 추가만 하는 기록(수정·삭제 API·벌크 쓰기 없음)이라 기록 시각을 버전으로 쓰고, 이름은 기록된 대상 이름(컬럼은 `TABLE.COLUMN` 과 컬럼명). 다른 프로젝트 이력 ID 는 `OUT_OF_SCOPE`. **현재 컬럼 근거(`COLUMN`)와 변경 이력 근거(`HISTORY`)는 다른 종류**라, 지워진 컬럼의 이력은 `HISTORY` 로 확인되고 같은 컬럼 ID 를 `COLUMN` 으로 들면 `NOT_FOUND` 다. 스키마의 근거 종류에 `HISTORY` 를 더했다 (그 밖의 계약 그대로).
- 한계 : Flyway 시드(예제 프로젝트)처럼 SQL 로 넣은 데이터는 이력이 없다 (실제 검증에서도 처음에는 "기록 없음" — 화면 API 로 한 번 고친 뒤 이력이 생겼다).

### 12.4 토큰 예산 (`ai/budget` : `TokenBudget`·`TokenEstimator`·`TokenMeter`)

- **공식 기능** : 요청마다 Ollama `num_ctx`(컨텍스트)·`num_predict`(응답 최대 토큰)를 `OllamaChatOptions` 로 보낸다 (`AiProviderAdapter.chatOptions`, 유형별). 모델 정보는 Ollama `/api/show` 의 `model_info."<아키텍처>.context_length"` (`AiProviderAdapter.modelInfo`, Spring AI `OllamaApi.showModel`, 같은 SEC-1 연결).
- 예산 :

| 구분 | 값 | 처리 |
|---|---|---|
| 요청 컨텍스트 | min(설정 16,384, 모델 최대) | 서버 기본값에 맡기면 넘친 앞부분을 서버가 말없이 잘라 의미가 바뀔 수 있어 정해서 보낸다 |
| 응답 최대 | 4,096 (모델 컨텍스트가 설정보다 작으면 그 1/4) | 응답이 이 한도에서 잘리면(`done_reason=length`) 쓰지 않고 `AI_TOKEN_BUDGET_EXCEEDED` |
| 요청 입력 | 컨텍스트 − 응답 최대 | **보내기 전** 추정(지시·질문·Tool 호출 인자·Tool 결과·Tool 정의)이 넘으면 보내지 않는다 — 잘라 보내지 않는다 |
| 질의 누적 | 100,000 | 모든 모델 호출(Tool 반복·구조화·재요청·대체 흐름)의 누적 + 다음 요청(추정 입력 + 응답 최대)이 넘으면 보내지 않는다 |

- 위치 : `TokenMeter.advisor()` 를 `ToolCallingAdvisor` **다음 순서**로 넣어 Tool 반복 안의 모델 호출 하나하나에서 판정한다. 재요청·Tool 재실행을 늘리지 않으며 180초·Tool 상한·구조화 1회 재요청은 그대로다. 예산 초과는 Provider 오류(`AI_PROVIDER_CALL_FAILED`)와 다른 코드다.
- **추정과 실측의 구분** : 추정 = ASCII 3자당 1토큰 + 그 밖의 글자 1자당 1토큰(많게 잡는다). 실측 = AI 서버가 알려 준 `prompt_eval_count`·`eval_count` 만. Spring AI 2.0.2 는 없는 값을 0 으로 채우므로, 0 이면서 그 소요 시간도 없으면 "알려 주지 않음" 으로 본다 — 0 으로 기록하지 않는다. 응답 `usage` : `modelCalls`, `promptTokens`·`completionTokens`(실측 합, 하나도 없으면 null), `unmeasuredCalls`, `estimatedInputTokens`(추정), `contextTokens`·`maxOutputTokens`·`totalBudget`. 화면은 실측만 보이고 추정은 "실측 아님" 으로 마우스 올림 설명에만.
- 설정 : `lexorma.ai.query.context-tokens`(2,048~262,144)·`output-tokens`(256~context/2)·`total-tokens`(context 이상) — 선택, 범위 밖이면 기동 실패. Provider 화면·DB 컬럼은 더하지 않았다 (모델 최대는 서버가 알려 주는 값으로 자동 반영).
- 한계 : Ollama 의 프롬프트 토큰은 "이번에 평가한" 수라 캐시로 재사용한 앞부분은 빠질 수 있다. 정확한 입력 토큰 수는 서버가 알 수 없다.

### 12.5 Tool Calling 미지원 모델 — 서버 맥락 대체 흐름

```text
callDefaultWith (SEC-1 연결 하나)
  ├ 모델 정보 (/api/show) ── SEC-1 차단·연결 실패 → 그대로 오류 (우회하지 않는다)
  ├ capabilities 에 tools 없음 ─────────────────────────────┐  (MODEL_CAPABILITIES)
  ├ 있음·모름 → Tool Calling (11장 흐름)                     │
  │     └ 아직 Tool 실행 전 + 서버의 명시적 거절(400 "does not support tools") ┤  (TOOLS_REJECTED)
  │       (일반 400·5xx·시간 초과·SEC-1 → 그대로 오류)      │
  └ 서버 맥락 ◀──────────────────────────────────────────────┘
       ServerContextAssembler.analyze(질문) → 의도·검색어 (규칙)
       → MetadataToolset.run (허용 목록 Tool 을 서버가 실행 : 재검증·읽기 전용·크기·기록·근거 스냅숏 = Tool Calling 과 같은 경계, 최대 5번)
       → 구조화 요청 한 번 (지시 + 형식 + 질문 + 서버 조회 결과, Tool 정의 없음) → 스키마 검증 → 1회 재요청 → 근거 검증
```

- **판정** : 모델 정보가 명시적으로 tools 미지원이거나, 정보가 없어 Tool Calling 을 시도했는데 서버가 명시적으로 거절한 경우만. 모든 400·연결 실패를 미지원으로 보지 않는다. SEC-1 차단은 모델 정보 조회에서 이미 끝난다.
- **질문 해석 (규칙)** : 낱말에서 의도(컬럼·관계·이력·유사·용어·단어·도메인·스키마)를 찾고, 조사·안내 낱말을 뺀 나머지를 검색어로 (물리명처럼 보이는 낱말은 따로). 조회 : 스키마 의도 → `listSchemas` / 유사 → `findSimilarColumns` / 용어·단어·도메인 → 각 검색 / 그 밖 → `searchTables` 후 첫 테이블(물리명이 같은 것 우선) **하나만** 의도에 따라 `getTableColumns`·`getTableRelations`·`getMetadataHistory`. 검색어가 없으면(해석 불가) **아무것도 조회하지 않고** 그 사실을 모델에게 알린다 — 많은 메타데이터를 대신 보내지 않는다. 질문 속 SQL·프로젝트 ID 는 검색어일 뿐 실행·범위에 쓰이지 않는다.
- 응답 : `mode`(`TOOL_CALLING`·`SERVER_CONTEXT`), `fallback`(`MODEL_CAPABILITIES`·`TOOLS_REJECTED`), `tools` 는 서버가 실행한 조회. 근거의 `source` 가 `SERVER_CONTEXT` — 서버가 실제로 조회해 세션에 남긴 것만 VERIFIED 가 될 수 있다.
- 화면 : "현재 모델은 Tool Calling 을 지원하지 않아 서버가 조회한 메타데이터를 바탕으로 답변했습니다…(찾는 범위가 좁을 수 있습니다)" 안내, Tool 칩 앞에 "서버 조회", 조회하지 않았으면 "조회할 대상을 찾지 못했다" 안내.

### 12.6 근거 검증 연계

- 상태 체계(VERIFIED·NOT_FOUND·OUT_OF_SCOPE·MISMATCH·STALE·UNVERIFIABLE / 전체 VERIFIED·PARTIAL·UNVERIFIED·NO_EVIDENCE)·판정 순서·`MDFCN_DT` 버전은 11장 그대로. 바뀐 것 : 근거 종류 `HISTORY`, 결과의 `source`(조회 방식, 조회하지 않은 근거는 null). "확인됨 ≠ 답변 문장 전체가 정확함" 안내는 그대로다.

### 12.7 보안 점검

- 프로젝트 격리 : 두 Tool·서버 맥락 모두 실행 컨텍스트의 프로젝트만 (다른 프로젝트 컬럼·테이블·이력은 `NOT_FOUND`/결과에 없음, 이력 ID 는 `OUT_OF_SCOPE`). USER·ASYNC·SCHEDULED 같은 경로 (SCHEDULED 서버 맥락 테스트).
- 재검증 : Tool·서버 조회마다, 근거 검증 직전에. 모델 응답 중 멤버에서 빠지면 질의 전체가 `PROJECT_ACCESS_DENIED`.
- 읽기 전용 : 새 코드도 허용 목록 테스트의 정적 검사(쓰기 메서드·SQL·`EntityManager`·`JdbcTemplate`·원본 DB·파일·HTTP 없음)를 통과한다. `SimilarColumnService`·`ModelChangeHistoryService` 는 `@Transactional(readOnly = true)`.
- 제한 우회 방지 : 서버 맥락도 Tool 호출 상한·180초·결과 크기·토큰 예산을 같이 쓴다. Tool Calling 을 쓰지 않는다고 더 많은 데이터를 보내지 않는다 (조회 5번, 결과 원문 24,000자).
- SEC-1 : 모델 정보 조회도 같은 AI_HTTP 대상(질의당 판정·시도 기록 한 번). 허용 규칙이 없으면 `EXTERNAL_CONNECTION_BLOCKED`, AI 서버에 아무 요청도 가지 않는다.

### 12.8 검증 결과

- 자동화 (가짜 Ollama) : `AiToolCompletionIntegrationTest` 5개 — 유사 컬럼(규칙별 점수·사유 7가지, 띄어쓰기 차이, 물리명·한글명 검색어, 20건 제한, 다른 프로젝트, 권한 회수), 이력(테이블+컬럼+관계, 하위 제외, 컬럼, 관계, 삭제된 컬럼, 변경 없음 vs 대상 없음, 한글명 안내, 다른 프로젝트, 건수 제한, 이력 없는 종류, 예시 값·사용자 ID 미전송, `HISTORY` 근거 VERIFIED·OUT_OF_SCOPE), 토큰(요청별 `num_ctx`·`num_predict`, 실측 합·미측정, 출력 잘림, 질의 누적 초과 시 다음 요청을 보내지 않음, 작은 컨텍스트에서 아무것도 보내지 않음), 대체 흐름(질문 해석 8가지, 관련 조회만, Tool 정의 미전송, `SERVER_CONTEXT` 근거 검증, 해석 불가 시 조회 없음, 유사·이력 의도, SCHEDULED), 판정·안전(모델 정보 없음 → 명시적 거절에만 대체, 일반 400 은 호출 실패, tools 지원이면 Tool Calling, SEC-1 차단 시 우회 없음, 다른 프로젝트, 형식 오류 1회 재요청, 응답 중 권한 회수). 기존 `MetadataToolIntegrationTest`(허용 목록 10개·미지원 → 대체 흐름)·`AiEvidenceIntegrationTest` 통과.
- **실제 Ollama 0.40.16 · `gemma4:e4b`** (capabilities : completion·vision·audio·tools·thinking, context_length 131072 → 요청 컨텍스트 16,384) — 격리 인스턴스(별도 포트·임시 H2 파일 DB) :

| 질문 | 방식 | Tool | 근거 | 재요청 | 시간 | 토큰(실측 입력+출력, 호출 수) |
|---|---|---|---|---|---|---|
| 회원일련번호와 유사한 컬럼 | Tool Calling | findSimilarColumns | 근거 없음 (예제에 회원 없음 — "등록되어 있지 않다") | 0 | 17.8s | 4,727+1,201 (3) |
| AI 제공자 일련번호와 유사한 컬럼 | Tool Calling | findSimilarColumns | 1/1 | 0 | 8.8–11.8s | 6,559+1,289 (3) |
| Which columns are similar to AI_PRVDR_SN? | Tool Calling | findSimilarColumns | 10/10 | 0 | 26.1s | 6,778+3,447 (3) |
| AI 설정 테이블의 변경 이력 (수정 전 : 시드라 이력 없음) | Tool Calling | getMetadataHistory | 근거 없음 ("기록 없음") | 0 | 3.5s | 4,624+292 (3) |
| 〃 (화면 API 로 설명 수정 후, Tool 수정 전) | Tool Calling | getMetadataHistory (한글명으로 호출) | **잘못된 "이력 없음"** → 12.3 의 대상 없음 구분으로 수정 | 0 | 4.5s | — |
| 〃 (수정 후) | Tool Calling | searchTables → getMetadataHistory | 1/1 | 1 | 22.6s | 10,971+2,735 (5) |
| What changed recently in the TB_AI_STNG table? | Tool Calling | getMetadataHistory | HISTORY 2/2 | 0 | 14.1s | 5,288+1,908 (3) |

- **대체 흐름 — 실제 모델 + capabilities 만 가린 프록시** : 설치된 Tool 미지원 모델이 없어 내려받지 않았다. 대신 실제 Ollama 앞에 `/api/show` 의 capabilities 에서 `tools` 만 빼는 검증용 프록시(스크래치 포트)를 두고 실제 `gemma4:e4b` 로 서버 맥락 흐름을 돌렸다 (`MODEL_CAPABILITIES`, 모델 호출 1번) :

| 질문 | 서버 조회 | 근거 | 재요청 | 시간 | 토큰 |
|---|---|---|---|---|---|
| AI 설정 테이블의 컬럼 | searchTables → getTableColumns | 7/7 | 0 | 3.2s | 1,531+352 |
| AI 제공자 일련번호와 유사한 컬럼 | findSimilarColumns | 처음 0 (서버가 검색어 공백을 지움) → 수정 후 3/3 | 0 | 5.1–6.0s | 1,135+690 |
| TB_AI_STNG 테이블의 변경 이력 | searchTables → getMetadataHistory | 3/3 | 0 | 6.7s | 1,435+905 |
| AI 설정 일련번호 표준 용어 | searchStandardTerms | 1/1 | 0 | 2.6s | 1,103+358 |
| 데이터 표준화가 뭐야? | searchTables(결과 없음) | 근거 없음 ("등록되어 있지 않다") | 0 | 3.5s | 1,095+437 |
| Which columns does the AI provider table have? | searchTables → getTableColumns | 1/1 | 0 | 2.5s | 1,992+178 |

  관찰 : 대체 흐름은 모델 호출 1번이라 Tool Calling(3~5번)보다 빠르고 토큰이 적지만, 서버가 정한 범위만 본다. 일반 질문도 메타데이터 범위로만 답한다 (Tool Calling 은 일반 지식으로 답할 수 있다). 실제 미지원 모델의 판정·거절 처리는 Mock 으로만 검증했다.
- 화면 : 헤드리스 Chrome — 대체 흐름 안내·"서버 조회" 칩·새 Tool 라벨(유사 컬럼 찾기·변경 이력)·`HISTORY` 근거·토큰 줄(실측, 추정은 마우스 올림 설명)을 한국어·영어 × 라이트·다크 × 데스크톱·모바일에서 확인 (가로 넘침 없음, 콘솔 오류 없음, 응답은 화면에 넣은 값). `/modelMapping` 모바일 검색 영역도 본문 안 그대로.
- 전체 : H2 713개(실패 0, 건너뜀 26)·PostgreSQL 713개(실패 0, 건너뜀 8, 빈 임시 DB). React typecheck·빌드, 단일 JAR 빌드 통과.

### 12.9 로드맵 4단계 완료 조건 점검

| 완료 조건 / 작업 범위 | 상태 | 근거 |
|---|---|---|
| Ollama Tool 호출 요청-응답 동작, 호출 기록 | 충족 | 실제 `gemma4:e4b` (10·11·12.8), 응답 `tools` + 애플리케이션 로그 |
| 원본 DB 접근·SQL 실행 불가 | 충족 | 허용 목록 10개·정적 검사 (4장, 12.7) |
| 잘못된 인자·권한 없는 ID·파싱 실패·미지원 모델에서 안전하게 | 충족 | 오류 결과·`NOT_FOUND`·1회 재요청 후 `AI_STRUCTURED_OUTPUT_INVALID`·미지원 → 대체 흐름(해석 불가면 조회 없음) |
| 없는·범위 밖 근거 탐지·표시 | 충족 | 11장, `HISTORY` 포함 (12.6) |
| Tool 목록 (`findSimilarColumns`·`getMetadataHistory` 포함) | 충족 | 12.2·12.3 |
| 실행 범위 (USER·ASYNC·SCHEDULED, 단일 프로젝트) | 충족 | 4장, 12.7 |
| 응답 구조 검증·제한된 재요청 | 충족 | 11장 |
| 근거 검증 (존재·범위·스냅숏/버전) | 충족 (한계 있음) | `MDFCN_DT` 기준 — 8장 1 |
| 호출 제한 (횟수·크기·반복·시간) + 토큰 (U-13) | 충족 | 5장, 12.4 (토큰 수는 추정) |
| 모델별 지원 확인·미지원 대체 흐름 | 충족 (실제 미지원 모델 미검증) | 12.5, 12.8 |
| 전체 회귀 | 충족 | 12.8 |

→ **AI-4 전체 완료**로 기록한다. 기술적 제약(버전 정밀도·토큰 추정·대체 흐름 범위·실제 미지원 모델 미검증)과 4단계 밖의 운영 지표(U-22)는 8장에 남긴다.
