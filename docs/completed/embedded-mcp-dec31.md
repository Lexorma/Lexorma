# DEC-31 선택형 내장 MCP (Streamable HTTP, 읽기 전용)


> **AI-7 (2026-11-27 완료, [ai-quality-inspection-integration.md](ai-quality-inspection-integration.md) 22~25장)** : MCP `tools/list` 는 이제 16개 — 기존 13개 + 저장된 품질 진단 조회 `getQualityInspectionOverview(runId?, ruleCode?)`·`searchQualityFindings`·`getQualityFindingDetail` (읽기 전용, 같은 `MetadataToolset.run` 경계, 토큰 프로젝트 범위, 검사 실행·예외 처리 없음). `searchQualityFindings` 응답은 실제 적용 필터(`filters`, 기본값으로 채운 필드 `defaulted`)를 돌려주고, 0건이면 `zeroMeans`(0건 ≠ 문제 없음)와 `outsideDefaultFilters`(기본값만 넓힌 같은 조건의 존재 여부 EXISTS·NOT_EXISTS, 기본값이 없으면 NOT_CHECKED — 건수·내용 없음, 근거 아님)를 함께 준다 (25장). 이 문서의 '10개' 등 당시 숫자는 그때 기준이다.
> **8차 (2026-11-17)** : 코드는 `lexorma-ai` 모듈로 옮겼다 — `lexorma-data` `mcp/…` → `com.lexorma.ai.mcp.…`, 프로젝트 삭제 정리는 `ai/hook/AiProjectDataCleanup`. `/mcp`·`/api/mcp`·도구 10개·토큰·설정은 그대로 ([ai-module-extraction.md](ai-module-extraction.md)).

> 2026-11-16 구현 (5차 통합 구현). 정책 : [pending-work.md 7.2](../pending-work.md) DEC-31. 마이그레이션 `V6__embedded_mcp.sql` (PostgreSQL·H2 동일).
> 같은 Spring Boot 프로세스·같은 JAR 안의 엔드포인트다 — 별도 서버·프로세스·Redis·인증 서버 없음. 원본 DB 에 연결하지 않는다.

## 1. 켜기 · 끄기

- 운영 설정 `lexorma.mcp.enabled` (`LEXORMA_MCP_ENABLED`, **기본 false**) : 꺼져 있으면 `/mcp` 는 모든 메서드에 404 (SPA 로도 넘기지 않는다 — `SpaForwardController` 제외 목록).
- 프로젝트 설정 `TB_PRJ_MCP_STNG` (행 없음 = 꺼짐) : 프로젝트 관리자·ADMIN 이 화면에서 켠다. 둘 다 켜져 있어야 토큰이 통한다.
- MCP 가 꺼져 있어도 다른 기능은 그대로다. AI Provider 설정과 무관하다 (Provider 없이 조회된다).

## 2. 토큰 (`TB_MCP_TKN`)

- 발급 `POST /api/mcp/tokens {name, expiresAt}` : USER + 지금의 DB 로 프로젝트 관리자·ADMIN(멤버). 원문 `lxm_` + 32바이트 난수(base64url)는 응답에 한 번만 (`Cache-Control: no-store`, `@ProtectedLogBody`, 레코드 `toString` 에 원문 없음), DB 에는 SHA-256 해시와 앞 12글자만. 만료 필수(지금 이후 최대 365일).
- 폐기 `DELETE /api/mcp/tokens/{id}` (되돌릴 수 없다), 목록·최근 호출 20건 `GET /api/mcp` (목록은 관리자에게만), 사용 설정 `PUT /api/mcp {enabled}`.
- 토큰에 역할을 담지 않는다. 요청마다 : 해시로 찾기 → 폐기·만료 → 프로젝트 MCP 사용 → 프로젝트 활성 → **발급 계정이 지금도 그 프로젝트의 관리자·ADMIN(활성 멤버)** (`ProjectAuthority.canManage`). 도구 실행 때 다시 `ExecutionContextVerifier.verify(ASYNC 컨텍스트, VIEWER)`. 그래서 멤버십 해제·역할 강등·계정 비활성·프로젝트 비활성은 다음 요청부터 바로 막힌다. 실패는 이유를 나누지 않고 401 하나 (감사에는 `auth:<이유>`).

## 3. 엔드포인트 `POST /mcp` (`McpEndpointController`, `McpServerService`)

- MCP Streamable HTTP 중 JSON 응답만 쓴다 (SSE 스트림·세션 ID 없음 — `GET`/`DELETE` 는 405). JSON-RPC 2.0 : `initialize`(지원 판 2025-06-18·2025-03-26·2024-11-05), `notifications/*`(202), `ping`, `tools/list`, `tools/call`. 그 밖의 메서드는 -32601, 배치(배열)는 거절.
- 세션 로그인과 분리 : Bearer 토큰만 본다 (세션 쿠키로는 인증되지 않는다), CSRF 대상이 아니다 (`SecurityConfig.ignoringRequestMatchers("/mcp")`), CORS 헤더를 주지 않는다, `Origin` 이 있으면 요청 Host 와 같아야 한다 (DNS rebinding 방지, 아니면 403). 외부 공개는 HTTPS 리버스 프록시 뒤에 둔다.
- 상한 : 토큰마다 분당 120 요청(429 + Retry-After), 요청 본문 1MB, 도구 결과 24,000 글자. 오류 응답에 내부 정보를 넣지 않는다.
- 감사 `TB_MCP_CALL_HSTRY` : 토큰·도구(또는 `auth:<이유>`)·결과(OK/DENIED/ERROR)·일시만 — 입력값·응답·토큰 원문 없음. `LAST_USE_DT` 는 1분 단위로만 갱신.

## 4. 도구 (읽기 전용)

| 도구 | 출처 |
|---|---|
| `listSchemas` · `searchTables` · `getTableDetail` · `getTableColumns` · `getTableRelations` · `searchStandardTerms` · `searchStandardWords` · `searchStandardDomains` | AI-4 `MetadataToolset.run` 그대로 (실행 컨텍스트 재검증·읽기 전용 트랜잭션·결과 크기 상한, 프로젝트는 컨텍스트에서만) |
| `getProjectInfo` | 프로젝트 이름·DBMS·객체 수 |
| `getLogicalModel {modelName?}` | 논리 모델 목록, 또는 그 모델의 엔터티(속성·식별자)·관계 |

- 도구 입력에 프로젝트를 받지 않는다 (토큰의 프로젝트만). 없는 것 : SQL 실행, 원본 DB 접근(원본 도구는 AI·MCP 어디에도 노출하지 않는다), DDL·DML·모델 변경·승인, 토큰·비밀번호 조회, 원본 VIEW 정의(DEC-16), AI 제안(확정되지 않은 것). AI-4 의 `findSimilarColumns`(규칙 점수 추정)·`getMetadataHistory` 는 넣지 않았다.
- 내부 AI Tool Calling(AI-4)은 Lexorma 안의 모델이 부르는 것, MCP 는 외부 클라이언트가 부르는 것 : 같은 읽기 경계를 쓰지만 허용 목록·인증·감사가 따로다.

## 5. 화면

- AI 관리 > MCP 연결 (`/mcpAccess`, 메뉴 `mcp-access` VIEWER — AI Provider 가 없어도 보인다, 관리 버튼 `mcp-manage` USER) : 서버·프로젝트 상태, 사용 설정, 연결 안내(`claude mcp add --transport http …`), 토큰 발급(이름·만료일)·원문 한 번 표시(복사·닫기)·목록·폐기, 최근 호출.

## 6. 검증

| 테스트 | 내용 |
|---|---|
| `McpEndpointIntegrationTest` (실제 서버, JDK HttpClient) | 발급(원문 형식·해시만 저장·목록에 원문 없음), 프로젝트 꺼짐 401, initialize·initialized 202·tools/list 10개, searchTables 는 토큰 프로젝트 것만, getProjectInfo·getLogicalModel, 모르는 도구·메서드 오류, 감사(입력 없음), 다른 Origin 403, 토큰 없음·잘못된 토큰 401, GET 405, 멤버십 해제 401 → 복구 200, 프로젝트 끔 401, 만료 401, 폐기 401, 감사의 거절 이유, 관리자 아닌 구성원 발급 거절·목록 없음, 만료 없음·400일 거절, 로그에 토큰 없음 |
| `McpDisabledIntegrationTest` | 기본값 꺼짐 : POST·GET 404, SPA 화면 아님 |
| 고정값 | 예제 인덱스 132·인덱스 컬럼 187·마이그레이션 7, `ProjectDeletionIntegrationTest`(세 테이블), `ProjectDataCleanup` |
| E2E (2026-11-16, 단일 JAR `LEXORMA_MCP_ENABLED=true`, 기존 임시 DB 에 V6 증분 적용, 헤드리스 Chrome + 공식 `@modelcontextprotocol/sdk@1.32.2` 클라이언트) | 화면에서 사용 켜기·토큰 발급 → SDK `StreamableHTTPClientTransport` 연결(serverInfo lexorma)·listTools 10개·callTool searchTables/getProjectInfo/getLogicalModel/getTableDetail → 화면 목록·최근 호출 → 폐기 → SDK 재연결 401. AI Provider 없음. 앱 로그에 토큰 없음, 콘솔 오류 없음, 영어·라이트 390px 넘침 0 |

| 6차 실제 Claude Code (2026-11-17, Claude Code 2.1.311 `claude -p --mcp-config <임시 파일> --strict-mcp-config` — 사용자 MCP 설정은 건드리지 않음, 토큰은 환경변수 `${LEXORMA_MCP_TOKEN}` 로만 전달, PostgreSQL 임시 앱) | 연결 connected·도구 10개 인식 → getProjectInfo(LEXORMA)·searchTables·getTableColumns(TB_MCP_TKN 13컬럼)·searchStandardTerms(토큰 → 표준 도메인)·getLogicalModel 호출 성공. 다른 프로젝트(100)에만 있는 테이블은 나오지 않음. 원본 VIEW 정의 문구는 어떤 응답에도 없음. 화면·API 로 폐기 → 같은 설정으로 다시 띄우면 연결 failed(401), 감사 `auth:REVOKED` |
| 6차 HTTPS 리버스 프록시 (자체 서명 인증서, Node 내장 `https` 로 만든 프록시 — 외부 이미지·패키지 없음) | Host 를 넘기는 프록시 : 공식 SDK·Claude Code(`NODE_EXTRA_CA_CERTS`) 연결·호출 성공, 같은 Origin 200·다른 Origin 403, 토큰 없음 401(JSON-RPC 오류), GET 405, `X-Forwarded-Proto` 로 쿠키 `Secure`. Host 를 upstream 주소로 바꾸는 프록시(nginx 기본값) : Origin 을 보내는 요청은 403 (안전한 쪽으로 실패), Origin 없는 클라이언트(Claude Code·SDK)는 200 |

## 7. 운영 조건 (6차 확인)

- **리버스 프록시** : 원래 `Host` 를 그대로 넘긴다 (nginx `proxy_set_header Host $host;`, `X-Forwarded-Proto` 도). Origin 검사는 요청의 `Host` 헤더와 비교하므로 Host 를 바꾸는 프록시 뒤에서는 브라우저에서 오는(Origin 있는) 요청이 모두 403 이 된다. MCP 클라이언트(Claude Code·IDE·SDK)는 Origin 을 보내지 않아 영향이 없다.
- **프록시 헤더 신뢰 범위** : `server.forward-headers-strategy: native` (Tomcat `RemoteIpValve`) 는 기본값으로 내부 주소(127/8·10/8·172.16/12·192.168/16·169.254/16 등)에서 온 `X-Forwarded-*` 를 믿는다 — 같은 사설망의 다른 호스트가 직접 붙으면 `X-Forwarded-For`(요청 로그의 접속 IP)·`X-Forwarded-Proto` 를 꾸밀 수 있다. 외부 공개 시 앱 포트는 프록시만 닿게 막고, 필요하면 `server.tomcat.remoteip.internal-proxies` 를 프록시 주소로 좁힌다. 토큰 인증·프로젝트 범위·호출 상한은 이 헤더를 쓰지 않는다.
- **여러 서버 인스턴스** : 호출 상한(토큰마다 분당 120)은 서버 메모리에 있어 인스턴스끼리 나누지 않는다 — N 대면 실제 상한이 최대 N 배가 된다. 토큰·폐기·만료·프로젝트 설정·발급자 권한은 DB 에서 요청마다 다시 보므로 어느 인스턴스에서나 바로 적용되고, `LAST_USE_DT` 는 인스턴스마다 1분 단위로 갱신해 조금 늦을 수 있다. Lexorma 의 배포 기준은 **단일 서버** 다 (세션 1개 정책·스케줄·AI 일괄 작업의 단일 실행기도 같은 전제). 여러 인스턴스로 운영하려면 호출 상한을 프록시(예 nginx `limit_req`, 토큰 헤더 기준)에서 걸거나 공유 저장소 기반 상한을 따로 도입해야 한다 (이번에는 도입하지 않음).
- 단일 서버 권장 조건 : 서버 한 대 + 앞단 HTTPS 프록시 1개, `LEXORMA_MCP_ENABLED=true` 는 MCP 를 쓸 때만, 프로젝트마다 관리자가 켜고 만료 있는 토큰 발급, 쓰지 않는 토큰은 폐기.
- 알아 둘 동작 : `searchTables` 는 AI-4 와 같은 검색이라 통째로 맞는 테이블이 없으면 단어별(공백·`_` 기준)로 다시 찾는다 — `TB_ONLY_X` 처럼 `TB` 가 들어간 검색어는 관련 없는 `TB_` 테이블을 돌려줄 수 있다 (응답에 근사 검색 표시가 없어 Claude Code 가 이상하다고 지적함, 향후 개선 후보). → **7차에 검색 방식 표시를 더함** (8장).

- 확인하지 못한 것 : 실제 nginx·클라우드 로드밸런서 뒤 배포(같은 동작을 Node 프록시로 재현), 여러 서버 인스턴스 실제 운영.

## 8. 7차 : `searchTables` 검색 방식 표시 (2026-11-17)

정책 (7차 지시) : 검색 결과의 일치 방식과 근사 여부를 명시한다. 검색 알고리즘은 그대로이고, 기존 필드(`keyword`·`tables`·`truncated`, 테이블별 기존 필드)는 지우지 않고 필드만 더했다 — 내부 AI Tool Calling(AI-4)과 MCP 가 같은 도구(`MetadataTools.searchTables`)라 둘 다 같다.

| 필드 | 뜻 |
|---|---|
| `matchMode` | `ALL` 검색어 없음(목록) · `EXACT` 결과가 **모두** 테이블명(대소문자 무시)·테이블 코멘트가 검색어(또는 공백을 `_` 로 이은 형태, `스키마.테이블`)와 같다 · `PARTIAL` 테이블명·코멘트·설명이 검색어 전체를 포함 (같은 이름이 섞여 있을 수 있다 — 테이블별 `match`) · `TOKEN_FALLBACK` 검색어 전체로는 없어 단어별(공백·`_`, 2글자 이상)로 다시 찾은 근사 결과 · `NONE` 결과 없음 |
| `exactMatch` | `matchMode = EXACT` 일 때만 true (결과가 있다는 이유만으로 true 가 되지 않는다) |
| `approximate` | `matchMode = TOKEN_FALLBACK` 일 때만 true |
| `matchedTerms` | 결과를 낸 검색어 형태 (부분 일치 : 원래 검색어·공백을 `_` 로 이은 형태 중 맞은 것, 근사 : 맞은 단어) |
| `resultCount` | 돌려준 테이블 수 (`truncated` 는 그대로 더 있었는지) |
| 테이블별 `match` · `matchedTerms` | `EXACT` / `PARTIAL` / `TOKEN`(+ 그 테이블에 맞은 단어). 검색어 없는 목록과 다른 도구(`getTableDetail` 의 table)에는 없다 (JSON 에서 빠진다) |

- 도구 설명에 각 값의 뜻과 "TOKEN_FALLBACK 결과는 관련 없을 수 있으니 matchedTerms 를 보고 답으로 단정하지 말라" 를 적었다 (MCP `tools/list` 의 description 에 그대로 나간다).
- 검증 : `MetadataToolIntegrationTest.toolsReadExampleProject` (정확 `tb_ai_prvdr` EXACT·부분 `AI 설정` PARTIAL·여러 테이블 `TB_AI` PARTIAL·부분 중 같은 이름 `TB_AI_STNG` 의 테이블별 EXACT·단어별 `AI Provider` TOKEN_FALLBACK·결과 없음 NONE·목록 ALL·다른 프로젝트 테이블 미노출·기존 필드 유지), `McpEndpointIntegrationTest` (MCP `tools/call` 결과의 기존 필드 + `matchMode`·`approximate`·`resultCount`, 근사 결과도 토큰 프로젝트 것만, `tools/list` 설명), 내부 Tool Calling 회귀 `AiToolCompletionIntegrationTest`·`AiEvidenceIntegrationTest` 통과. 실제 앱(단일 JAR·PostgreSQL 임시 DB) 에서 공식 MCP SDK 로 `tb_acnt_role` EXACT · `TB_ACNT` PARTIAL(TB_ACNT 는 EXACT) · `계정` PARTIAL(코멘트가 같은 TB_ACNT 는 EXACT) · `AI Provider` TOKEN_FALLBACK(approximate, TB_AI_PRVDR 이 두 단어) · `없는테이블XYZ` NONE · 검색어 없음 ALL 확인. 실제 Claude Code 로 다시 묻지는 않았다.
- 요청 로그 : MCP 는 `/api` 밖이라 요청 로그(TB_LOG)에 남지 않고 자기 호출 이력(`TB_MCP_CALL_HSTRY`)에만 남는다 (7차 확인 : 토큰 없는 401 이 TB_LOG 에 없다).

