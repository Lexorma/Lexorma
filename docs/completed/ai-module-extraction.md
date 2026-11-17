# AI·MCP 모듈 분리 (`lexorma-ai`, 8차)

> 2026-11-17 구현 (8차 통합 인증 안정화 및 AI/MCP 모듈 분리). AI 기능을 별도 서버로 만든 것이 아니라 같은 Spring Boot 애플리케이션 안에서 AI 와 데이터 모델링의 코드 책임을 나눈 구조 개선이다. API·DB·보안 정책·화면은 바꾸지 않았다. 마이그레이션 없음 (다음은 그대로 V7).

## 1. 이관 전 구조

```text
common ← core ← (system, operation, data, source) ← app
```

| 위치 | AI 관련 내용 |
|---|---|
| `lexorma-core` `core/ai` (14) | AI Provider 계층 (AI-3) : 엔티티·저장소(`TB_AI_PRVDR`·`TB_AI_STNG`·`TB_PRJ_AI_STNG`), `AiProviderClients`·`AiCall`·기본/프로젝트 Provider 해석, Ollama 어댑터. Spring AI(`spring-ai-ollama`·`spring-ai-client-chat`)를 core 가 가지고 있었다 |
| `lexorma-system` `system/ai` (3) | Provider·기본 Provider·프로젝트 Provider 관리 API 컨트롤러 |
| `lexorma-data` `ai/` (43) | 메타데이터 Tool Calling(AI-4 : 도구·세션·토큰 예산·구조화 응답·근거 검증·서버 맥락), AI 비서 질의 API, AI 설명 제안·검토·일괄 작업·품질 평가(AI-5) |
| `lexorma-data` `mcp/` (11) | 내장 MCP (DEC-31) : `/mcp` 엔드포인트·도구 등록·토큰·설정·호출 이력 |
| `lexorma-data` 리소스 `ai/` | 구조화 응답 JSON Schema 2개, 설명 초안 프롬프트 v1·v2 |
| `lexorma-data` 테스트 | `DescriptionTextCheckTest`·`ProposalReasonFormatTest` (단위) |

조사 결과 (실제 import 기준) : data 의 다른 패키지는 `ai`·`mcp` 를 import 하지 않았다. 숨은 결합은 문자열 두 곳 — `ProjectDataCleanup` 이 AI·MCP 테이블 10개를 SQL 로 지우고, `SourceDeletionService` 가 JPQL `AiDescriptionProposal` 로 제안 수를 셌다. AI 승인은 data 의 엔티티(`TableInfo`·`ColumnInfo`)에 업무 설명을 직접 썼다. OpenAI 호환 Provider 는 코드에 없다 (Provider 종류는 `OLLAMA` 하나) — 이번에 만들지 않았다.

## 2. 이관 후 구조

```text
common ← core ← (system, operation, data, source) ← app
                              data ← ai ← app
```

- `lexorma-ai` : `lexorma-app` 에 들어가는 **라이브러리 모듈** (자체 bootJar·실행 클래스·별도 서버 없음). 패키지 `com.lexorma.ai`.
- `lexorma-data` 는 `lexorma-ai` 를 모른다. data 가 AI 쪽 정보를 써야 하면 data 에 AI 중립 계약을 두고 ai 가 구현한다.
- `lexorma-ai` 는 `lexorma-source` 를 참조하지 않는다 (원본 DB 도구를 AI 에 노출하지 않는 기존 원칙 그대로).

```text
lexorma-ai/src/main/java/com/lexorma/ai
├── provider/      AI Provider (core.ai → ai.provider, system.ai.controller → ai.provider.controller, core.ai.provider → ai.provider.adapter)
├── tool/ context/ answer/ evidence/ budget/ service/ controller/ domain/   메타데이터 Tool Calling·AI 비서 (data.ai → ai)
├── dictionary/    AI 설명 제안·검토·일괄 작업·품질 평가 (data.ai.dictionary → ai.dictionary)
├── mcp/           내장 MCP (data.mcp → ai.mcp)
└── hook/          데이터 모듈과의 연결 (새로) : AiProjectDataCleanup, AiProposalRecordCounter
lexorma-ai/src/main/resources/ai/   JSON Schema·프롬프트 (클래스패스 경로 그대로)
lexorma-ai/src/test/java/com/lexorma/ai/dictionary/service/   단위 테스트 2개
```

## 3. 옮긴 클래스·패키지

| 원래 모듈 | 원래 패키지 | 새 패키지 | 클래스 |
|---|---|---|---|
| `lexorma-core` | `com.lexorma.core.ai.provider` | `com.lexorma.ai.provider.adapter` | AiProviderAdapter, OllamaProviderAdapter |
| `lexorma-core` | `com.lexorma.core.ai` | `com.lexorma.ai.provider` | AiProviderType |
| `lexorma-core` | `com.lexorma.core.ai.repository` | `com.lexorma.ai.provider.repository` | AiProviderRepository, AiSettingRepository, ProjectAiSettingRepository |
| `lexorma-core` | `com.lexorma.core.ai.service` | `com.lexorma.ai.provider.service` | AiCall, AiDefaultProviderService, AiProviderClients, AiProviderService |
| `lexorma-core` | `com.lexorma.core.ai.domain.dto` | `com.lexorma.ai.provider.domain.dto` | AiProviderDto |
| `lexorma-core` | `com.lexorma.core.ai.domain.entity` | `com.lexorma.ai.provider.domain.entity` | AiProvider, AiSetting, ProjectAiSetting |
| `lexorma-system` | `com.lexorma.system.ai.controller` | `com.lexorma.ai.provider.controller` | AiDefaultProviderRestController, AiProviderRestController, ProjectAiProviderRestController |
| `lexorma-data` | `com.lexorma.data.mcp.controller` | `com.lexorma.ai.mcp.controller` | McpEndpointController, McpSettingRestController |
| `lexorma-data` | `com.lexorma.data.mcp.service` | `com.lexorma.ai.mcp.service` | McpServerService, McpTokenService |
| `lexorma-data` | `com.lexorma.data.mcp.repository` | `com.lexorma.ai.mcp.repository` | McpCallHistoryRepository, McpProjectSettingRepository, McpTokenRepository |
| `lexorma-data` | `com.lexorma.data.mcp.domain.dto` / `.entity` | `com.lexorma.ai.mcp.domain.dto` / `.entity` | McpDto / McpCallHistory, McpProjectSetting, McpToken |
| `lexorma-data` | `com.lexorma.data.ai.tool` | `com.lexorma.ai.tool` | MetadataToolSession, MetadataTools, MetadataToolset |
| `lexorma-data` | `com.lexorma.data.ai.answer` | `com.lexorma.ai.answer` | JsonSchemaOutput, StructuredAnswer, StructuredAnswerParser |
| `lexorma-data` | `com.lexorma.data.ai.budget` | `com.lexorma.ai.budget` | TokenBudget, TokenEstimator, TokenMeter |
| `lexorma-data` | `com.lexorma.data.ai.evidence` | `com.lexorma.ai.evidence` | EvidenceCatalog, EvidenceType, EvidenceVerifier |
| `lexorma-data` | `com.lexorma.data.ai.context` | `com.lexorma.ai.context` | ServerContextAssembler |
| `lexorma-data` | `com.lexorma.data.ai.controller` / `.service` / `.domain.dto` | `com.lexorma.ai.controller` / `.service` / `.domain.dto` | AiMetadataQueryRestController / AiMetadataQueryService / AiMetadataQueryDto |
| `lexorma-data` | `com.lexorma.data.ai.dictionary.controller` | `com.lexorma.ai.dictionary.controller` | AiDictionaryEvaluationRestController, AiDictionaryRestController |
| `lexorma-data` | `com.lexorma.data.ai.dictionary.service` | `com.lexorma.ai.dictionary.service` | AiDescriptionDraftService, AiDescriptionEvaluationService, AiDescriptionJobService, AiDescriptionReviewService, AiDictionaryQueryService, DescriptionDraft, DescriptionTextCheck, ProposalEvaluationGate, ProposalMapper, ProposalVersions, ProposalWriter |
| `lexorma-data` | `com.lexorma.data.ai.dictionary.repository` | `com.lexorma.ai.dictionary.repository` | AiDescriptionEvaluationHistoryRepository, AiDescriptionEvaluationRepository, AiDescriptionEvaluationTargetRepository, AiDescriptionJobRepository, AiDescriptionProposalHistoryRepository, AiDescriptionProposalRepository |
| `lexorma-data` | `com.lexorma.data.ai.dictionary.domain.dto` / `.entity` | `com.lexorma.ai.dictionary.domain.dto` / `.entity` | AiDictionaryDto, AiEvaluationDto / AiDescriptionEvaluation, AiDescriptionEvaluationHistory, AiDescriptionEvaluationTarget, AiDescriptionJob, AiDescriptionProposal, AiDescriptionProposalHistory |
| `lexorma-data` 리소스 | `ai/` | `ai/` (lexorma-ai) | description-draft.schema.json, metadata-answer.schema.json, prompts/description-draft-v1·v2.txt |
| `lexorma-data` 테스트 | `com.lexorma.data.ai.dictionary.service` | `com.lexorma.ai.dictionary.service` | DescriptionTextCheckTest, ProposalReasonFormatTest |

파일 77개를 옮기고(일반 파일 이동 — Git 인덱스는 건드리지 않았다), 이를 참조하던 소스 93개의 패키지·import 를 바꿨다 (`lexorma-app` 통합 테스트 21개 포함). 파일 경로를 직접 읽는 테스트 2개(`MetadataToolIntegrationTest` 의 읽기 전용 규칙, `ReviewReasonContractTest`)는 새 경로로 — 읽기 전용 규칙은 예전과 같은 범위(옛 `data.ai`)만 보도록 `provider`·`mcp`·`hook` 을 뺐다.

새로 만든 것 (책임 분리) :

| 클래스 | 이유 |
|---|---|
| `ai/hook/AiProjectDataCleanup` | 프로젝트 영구 삭제 때 AI·MCP 프로젝트 데이터 10개 테이블을 지운다 (같은 `ProjectDeletedEvent`, 같은 트랜잭션). 예전에는 data 의 `ProjectDataCleanup` 이 문자열 SQL 로 지웠다 — 그 줄을 옮겼다. AI·MCP 테이블의 FK 는 `TB_PRJ`·`TB_AI_PRVDR` 로만 가서 data 정리와 순서가 필요 없다 |
| `data/sourcemapping/service/RelatedRecordCounter` (data, 계약) ← `ai/hook/AiProposalRecordCounter` (구현) | 원본 삭제 반영(DEC-04) 영향 표시의 "AI 설명 제안 수" : data 가 AI 엔티티를 JPQL 로 직접 세던 것을 계약으로. 구현이 없으면 0 (응답 필드 `aiProposals` 그대로) |
| `data/table/service/BizDescriptionService.writeAiApproval(TableInfo|ColumnInfo, text)` | AI 설명 제안 승인의 최종 모델 쓰기 (업무 설명 + 이력 출처 AI_APPROVAL) 를 data 가 한다. 승인 워크플로(제안 잠금·버전 비교·블라인드 평가 게이트·잠금 순서 제안 → 모델 행)는 ai 가 그대로 하고, 같은 트랜잭션에서 이 메서드를 부른다 (`Propagation.MANDATORY`) |

## 4. data 모듈에 남긴 것과 이유

| 남긴 것 | 이유 |
|---|---|
| 업무 설명 `TASK_EXPLN`(`TableInfo`·`ColumnInfo.bizDescription`), `BizDescriptions`·`BizDescriptionService`(직접 편집·충돌 검사) | 업무 설명은 AI 없이도 쓰는 모델 데이터다. AI 는 승인 때 data 의 API 로 쓴다 |
| `ModelChangeRecorder.markAiApproval`, 이력 출처 `AI_APPROVAL` | 모델 변경 이력은 data 의 책임이다 (출처 값만 AI 를 가리킨다) |
| `TableDesignService`·`ColumnManageService`·`PhysicalModelService`·`SimilarColumnService`·`ModelChangeHistoryService` 등 조회 서비스 | 일반 데이터 조회 서비스다. AI Tool·MCP 가 그대로 부른다 (ai → data) |
| 오류 코드(`ErrorCode` 의 AI_*·MCP_*)·`EnglishMessages`(common), 메뉴 권한(`MenuAuthorization`, core), `LogBodyPolicy` 의 `/api/ai/**`(core), `SecurityConfig` 의 `/mcp` CSRF 제외(core), `SpaForwardController` 의 `mcp` 제외(app), `ExternalHttpClients`(SEC-1, core) | 공통·보안 정책 한 곳 규칙 (기존 계약을 바꾸지 않는다). 문자열 키만 AI 를 가리킨다 |
| Flyway V0~V6 (app) | DB 스키마는 나누지 않았다 (테이블명·데이터·마이그레이션 위치 그대로) |

## 5. Gradle

| 파일 | 변경 |
|---|---|
| `settings.gradle` | `include 'lexorma-ai'`, 의존 방향 주석 |
| `lexorma-ai/build.gradle` (새) | `api project(':lexorma-data')`, `implementation spring-ai-ollama`, `api spring-ai-client-chat`, `implementation libs.json.schema.validator`, 테스트 `testFixtures(':lexorma-core')`. 버전은 기존 그대로(Spring AI BOM·카탈로그) |
| `lexorma-core/build.gradle` | Spring AI 두 줄 제거 |
| 루트 `build.gradle` | data 의 `json.schema.validator` 제거 (ai 로) |
| `lexorma-app/build.gradle` | `implementation project(':lexorma-ai')` |

확인 : `:lexorma-core`·`:lexorma-system`·`:lexorma-data`·`:lexorma-source` 의 runtimeClasspath 에 `project :lexorma-ai`·`org.springframework.ai`·`networknt` 가 없다. `lexorma-ai` 는 data → core → common. 순환 없음. `ModuleBoundaryTest` : 모든 모듈의 소스(테스트 포함)가 `com.lexorma.ai.` 를 import 하지 않고(ai 와 app 빼고), ai 는 system·operation·source 를 import 하지 않으며, ai 빌드 파일은 data 를 받고 bootJar·Boot 플러그인이 없다.

## 6. Spring 설정

- 컴포넌트·엔티티·저장소 스캔 : `LexormaApplication`(`com.lexorma`) 의 기본 스캔이 `com.lexorma.ai` 를 덮는다 — 설정 추가 없음. QueryDSL APT 는 루트 `subprojects` 설정이라 새 모듈에도 적용.
- 보안 필터 체인·요청 로그 순서 : 바뀐 코드 없음 (`/mcp` 는 문자열 규칙).
- 메시지 : `MessagesCoverageTest` 는 `lexorma-*` 모듈을 모두 읽어 새 모듈도 검사한다.
- 리소스 : `ai/…` 클래스패스 경로 그대로 (`PROMPT_VERSION`·SHA-256 도 그대로).
- AI 는 선택 기능 그대로 : Spring AI 자동 설정이 없고, Provider·MCP 설정이 없으면 호출 때만 오류(`AI_PROVIDER_NOT_CONFIGURED` 등)다.

## 7. DB · API 호환

- DB : 변경 없음 (테이블·데이터·Flyway V0~V6 그대로, V7 만들지 않음). 클래스 이름이 DB 에 저장되는 곳은 없다 (세션의 `AccountDto` 는 core 그대로).
- API : URL·요청/응답 DTO 필드·오류 코드·메시지 그대로 (`/api/ai/**`, `/api/mcp/**`, `/mcp`). MCP 도구 이름·설명·결과 형식 그대로.

## 8. 검증 (2026-11-17)

| 항목 | 결과 |
|---|---|
| 전체 테스트 | H2 899건 실패 0·건너뜀 39, PostgreSQL(빈 임시 DB) 899건 실패 0·건너뜀 9 (7차 893 + `SessionReloginHttpTest` 6). `lexorma-ai` 단위 테스트 2건 포함, `lexorma-data`·`lexorma-core` 테스트는 ai 없는 클래스패스로 돈다 |
| 경계 | `ModuleBoundaryTest`(소스·빌드 파일), Gradle runtimeClasspath 확인 (5장) |
| 단일 JAR | `lexorma.jar` 하나 — `BOOT-INF/lib/lexorma-ai.jar`(AI 클래스 298개·프롬프트·스키마) 와 Spring AI·JSON Schema 검증기, data·core jar 에 AI 클래스 0, React·도움말 이미지 69·Flyway·메시지 포함 |
| AI 미설정·MCP 꺼짐 기동 | H2 standalone(`h2-local`, 임시 데이터 폴더)·PostgreSQL(새 DB) 각각 : 기동, 초기 비밀번호·로그인, AI 상태 `NOT_CONFIGURED`, AI 질의 `AI_DEFAULT_PROVIDER_NOT_CONFIGURED`, `/mcp` 404, 프로젝트 만들기·스키마·테이블·논리 모델·물리 ERD·표준화 품질·모델 진단 API 200, 화면 11개(대시보드·프로젝트·표준 용어/단어/도메인·테이블·컬럼·논리 모델·논리/물리 ERD·모델 교환) 표시, 사이드바 AI 그룹·AI 비서 숨김, 5xx·화면 오류 0, 로그에 AI 서버 접속 없음. 외부 네트워크를 실제로 끊지는 않았다 — SEC-1 은 허용 규칙이 없으면 나가지 않는다 |
| AI Provider·Tool Calling (실제 Ollama `gemma4:e4b`, 단일 JAR·PostgreSQL) | SEC-1 허용 → Provider 연결 시험 성공(모델 1·기본 모델 있음), 허용하지 않은 포트 `BLOCKED/PORT_NOT_ALLOWED`, 허용했지만 서버 없음 `CONNECTION_REFUSED`(안내 문구), 기본 Provider → 상태 READY, 프로젝트별 Provider 를 실패 Provider 로 → 질의 실패(기본 Provider 로 대신 부르지 않음), 해제 → 질의 성공(도구 `searchTables` → `getTableColumns`, `TB_AI_PRVDR` 컬럼 답변, 34초). AI 화면(Provider·설명 제안·품질 평가) 한국어·다크·1440 / 영어·라이트·390 넘침 0 |
| MCP | 화면에서 켜기·토큰 → 공식 SDK 도구 10개·`searchTables` 검색 방식 표시, 다른 프로젝트 TABLE 결과 없음·상세 NOT_FOUND, 다른 Origin 403, 폐기 뒤 401. **실제 Claude Code**(임시 설정 파일·`--strict-mcp-config`·토큰은 환경변수)로 HTTP 와 HTTPS 리버스 프록시(자체 서명, Host 유지) 둘 다 연결 — 도구 10개·프로젝트 LEXORMA·`matchMode` PARTIAL(TB_ACNT 는 EXACT) 답변 |
| 승인 쓰기 경로 | `AiDictionary*IntegrationTest`(승인 → 업무 설명·이력 출처 AI_APPROVAL·충돌·블라인드 평가 게이트) 전체 통과 — `BizDescriptionService.writeAiApproval` 경유 |
| 프로젝트 삭제 | `ProjectDeletionIntegrationTest` (남은 행 검사에 AI·MCP 테이블 포함) 통과 — `AiProjectDataCleanup` 경유 |

## 9. 향후 확장 기준

- 새 AI·MCP 기능은 `lexorma-ai` 에 둔다. 데이터 조회는 data 의 서비스를 부르고, 모델 데이터의 최종 쓰기는 data 의 API 로 한다 (AI 승인 = `BizDescriptionService.writeAiApproval`).
- data 가 AI 쪽 정보를 알아야 하면 data 에 AI 중립 계약 인터페이스를 두고 ai 가 구현한다 (`RelatedRecordCounter`). ai 의 프로젝트 데이터 정리는 ai 가 `ProjectDeletedEvent` 로 한다.
- 새 Provider 종류(예 : OpenAI 호환)는 `ai/provider/adapter` 에 어댑터 하나 + `AiProviderType` 값으로 (SEC-1 `ExternalHttpClients` 전송만). 공통 모듈을 따로 만들지 않는다 — DTO 공유만을 위한 새 Gradle 모듈은 만들지 않는다.
