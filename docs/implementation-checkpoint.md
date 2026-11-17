# 구현 체크포인트 (세션 연속 작업)

> 컨텍스트 한도로 진행 상황이 사라지지 않게 하는 작업 메모다. **구현 완료 보고서가 아니다** — 완료 기록은 `completed-work.md`, 남은 작업은 `pending-work.md`.
> 새 세션 재개 순서 : `CLAUDE.md` → 이 문서 → `pending-work.md` 7.2·7.3 → 실제 코드·Git 상태. 체크포인트와 코드가 다르면 코드를 기준으로 고친다.

## 7차 통합 품질 개선 · DEC-22 보완 (2026-11-17)

지시 : 요청 로그 상태 코드(P0) → `searchTables` 검색 방식 표시(P1) → DEC-22 모델 VIEW·ERD 배치 교환 → DEC-05 UNKNOWN 정책 → 대형 ERD 이미지 측정 → H2·PG 전체 → 문서. 커밋·인덱스 변경 금지, 마이그레이션 필요 시 V7.
시작 Git : HEAD `b9a1087`, **스테이징 158개(A 140·AM 17·AD 1)**, `git status --short` 310줄, 미추적 0 — 6차 끝(67개)에서 늘었다. `.git/index` 09:31:14 (세션 시작 직후의 `git status` 가 갱신한 시각일 수 있다), IntelliJ 실행 중. 이 세션은 인덱스를 바꾸는 명령을 실행하지 않았다 (스냅샷 scratchpad `git-before-7.txt`).

### 진행

- P0 요청 로그 **완료** : 원인 = `LogsInterceptor.afterCompletion` 저장 시점이 `ExceptionTranslationFilter` 의 403 변환 전. `RequestLogRecorder`(core/logs/service) + `CustomOncePerRequestFilter` 체인 뒤 저장, `LogsInterceptor` 는 속성만(가장 먼저), `LogsConfig.isExcludedPath`. 테스트 `LogsInterceptorTest` 재작성, `RequestLogStatusHttpTest` 새로, `McpEndpointIntegrationTest` TB_LOG 없음 확인
- P1 `searchTables` **완료** : `MetadataTools.TableList` 필드 추가(`TableMatchMode`·`TableMatch`), 도구 설명, 테스트 `MetadataToolIntegrationTest`·`McpEndpointIntegrationTest`
- DEC-22 **완료** : 형식 2 (`ModelExchangeDto.Layouts`·`Selection.views`·`SUPPORTED_VERSIONS`), `ModelExchangeService` VIEW·배치 계획/생성, 화면 `ModelExchangePage`, `ModelExchangeIntegrationTest.viewsAndLayouts`, 브라우저 `pw/e2e7a.mjs` (ko 다크 1440·en 라이트 390)
- DEC-05 UNKNOWN **완료** : `SourceImportService` 후보 `before`, `SourceImportPanel` 수동 검토 묶음, 테스트 보강, 실제 원본 화면 `pw/e2e7u.mjs` (임시 원본 DB `dec7_src`)
- 대형 ERD **완료** : 측정 `pw/perf7.mjs`·`perf1000.mjs`(결과 `pw/out7/perf-results.jsonl`), 크기 한도 `exportImage.ts`, 화면 확인 `pw/guard7.mjs`
- 실제 앱 확인 `pw/e2e7b.mjs` : MCP SDK 검색 방식 6가지, 요청 로그 403/404 = HTTP
- 발견(미수정) : 세션 행이 많이 남은 DB 에서 재로그인 시 새 세션 미인증 — 6차 JAR 로도 재현(7차와 무관), pending-work 7.3 에 올림
- 문서 : model-exchange-dec22 8·9장, embedded-mcp-dec31 8장, ai-tool-calling-ai4 표, source-sync 2.1, completed-work [DEC-IMPL-7], pending-work 7.3·이력, CLAUDE.md
- 전체 테스트 **완료** : H2 893·실패 0·건너뜀 39, PostgreSQL 893·실패 0·건너뜀 9 (scratchpad `full7.sh`)
- 임시 환경 정리함 : 앱 9951·9952·9953·9954 종료, DB `lexorma_dec7_app`·`_old`·`_new`·`_full`·`dec7_src` 삭제, JAR 삭제. 사용자 앱 9940(PID 86766)·개발 DB `lexorma` 손대지 않음
- 끝 Git : HEAD `b9a1087`, 스테이징 158개 그대로 (작업 트리만 바뀜 : A → AM 6개, 새 미추적 2개 `RequestLogRecorder.java`·`RequestLogStatusHttpTest.java`), `git status --short` 317줄
- **7차 종료 상태** : 남은 것은 pending-work.md 7.3 의 DEC-22·05·31·요청 로그·재로그인 세션 행. 다음 작업은 사용자와 정한다. 다음 마이그레이션 V7

## 6차 통합 안정화 (2026-11-17 시작)

지시 : Git 스테이징 조사 → Flyway V6 → DEC-08·13 화면 충돌 E2E → DEC-05 롤백 → DEC-16·30 브라우저·권한 → DEC-31 Claude Code MCP → DEC-22 회귀 → H2·PG 전체 → 문서. 커밋·인덱스 변경 금지.
시작 Git : HEAD `b9a1087`, 스테이징 67개(A 53·AM 13·AD 1), `git status --short` 232줄.

### 진행

- Git 조사 **완료** (읽기 전용) : 이 세션 기록에 인덱스를 바꾸는 git 명령 없음. 스테이징된 새 파일 52개는 모두 21:12:30 이전 생성, 미추적 15개는 모두 21:14:40 이후 생성 — 21:12:30(ModelExchangeDto 생성)~21:12:44(`.git/index` 수정) 사이 한 번에 "그때까지의 미추적 새 파일"이 추가됨. 수정만 된 파일(` M`)은 추가되지 않음. 원인 프로세스는 확정 못 함 (IDE 의 새 파일 Git 추가 동작과 맞는 양상, `.idea` 있음). 스테이징 내용에 비밀값 없음 (테스트용 공개 키 `AAECAw…` 만), `docker-compose.secrets.yml` 은 스테이징 A·작업 트리 삭제(AD)
- Flyway V6 **완료** : `V6EmbeddedMcpMigrationTest`(새로 추가, V5 → V6 H2·PG, 기존 데이터·설정 행 없음=꺼짐·UNIQUE·FK·예제 표준 용어·재 migrate 0건) + V0SeedMigrationTest(신규 V0~V6) H2·PG 통과. 임시 앱 재기동 : PG(`lexorma_dec6_app`)·H2 파일 DB 각각 V5 로 띄워 데이터 넣고 → V6 적용 → 다시 기동 "No migration necessary"·validate 통과·데이터 유지. 사용자 DB `lexorma` 는 V0·V1 만 적용 상태(읽기만 함)
- DEC-08·13 브라우저 E2E **완료** (scratchpad `pw/e2e0813.mjs`, 서로 다른 계정 admin·e2e_user, PG 임시 앱) : 논리 모델·논리 관계·논리 식별자·물리 ERD FK 규칙 — A 저장 → B 409 문구·B 입력 보존·서버 값 A·새로고침 최신·재편집 저장·확인 연타 요청 1번, 기준값 없음 400
- **결함 1 (DEC-22)** : 기본 스키마가 아직 없는 프로젝트(새 프로젝트는 처음 쓸 때 만든다)로 모델을 복사하면 DEFAULT 를 일반 스키마로 만들어, 그 뒤 물리 ERD 등이 기본 스키마를 만들다 UNIQUE 위반 500. 수정 : `ModelExchangeService.create` 가 그때 DEFAULT 를 기본 스키마로 만든다. 회귀 : `ModelExchangeIntegrationTest.roundTrip` (수정 전 실패 확인)
- **결함 2 (DEC-08)** : 식별자 구성 속성·인덱스 컬럼만 바꾼 저장은 부모 행 UPDATE 가 없어 MDFCN_DT 가 그대로 → 예전 기준값 저장이 409 없이 구성을 되돌림 (실제 API 로 재현). 수정 : `BaseEntity.markModified()`, `LogicalModelService.saveIdentifier`·`PhysicalModelServiceImpl.apply(index)` 가 구성이 바뀌면 호출. 회귀 : `ModelOptimisticConflictIntegrationTest.compositionOnlyChangeMovesBase` (수정 전 실패 확인)
- 충돌 문구 정리 : 상세가 코드 문구를 되풀이하던 것을 `충돌한 객체 : {0}` 로 (ModelBase·PhysicalGenerationService·EnglishMessages)
- DEC-05 롤백 **완료** : `SourceSyncIntegrationTest.replaceRollsBackWhenLaterItemFails` — UNIQUE·INDEX 교체 한 계획, 테스트에서만 거는 임시 CHECK 로 두 번째(INDEX) INSERT 실패 → 기존 UNIQUE·인덱스·모델 버전·변경 이력·가져오기 기록·매핑 이력 그대로, 원인 제거 뒤 재계획 반영 성공. H2·PG 통과
- **결함 3** : 로그인 `project_id` 가 숫자가 아니면 NumberFormatException 500 → `FormWebAuthenticationDetails` 가 null 로 받아 "접근할 수 없는 프로젝트" (`ProjectRecoveryLoginTest.malformedProjectIdIsRejected`)
- DEC-16 **완료** : `pw/e2e16.mjs` (실제 PG 원본 `dec16b_src`, 47K 자 VIEW) ko·다크·1440 / en·라이트·390, USER·VIEWER 403, 로그·Export·MCP 원문 없음. 화면에 원문 확인 이력 추가 (`SourceCatalogPage` `ViewDefinitionPanel`, `model.ts` `ViewDefinitionAccess`, en.ts)
- DEC-30 **완료** : `pw/e2e30.mjs` VIEWER 27·USER 28·MANAGER 30·ADMIN 33, 이미지 404 0
- DEC-31 **완료** : 실제 Claude Code 2.1.311 (`scratchpad/mcp/config.json`, `--strict-mcp-config`), 폐기 401, HTTPS 프록시(`scratchpad/tls/proxy.mjs`) — 문서 embedded-mcp-dec31.md 7장
- DEC-22 **완료** : `pw/e2e22.mjs`·`pw/copied.mjs` (새 프로젝트로 복사 → 물리 ERD 열림)
- 문서 갱신 : completed-work [DEC-IMPL-6], pending-work 7.3, CLAUDE.md, completed/ dec16·dec22·dec31·source-sync
- 전체 테스트 **완료** : H2 886·실패 0·건너뜀 39, PostgreSQL 886·실패 0·건너뜀 9 (첫 실행 H2 1건 실패 = 새 롤백 테스트의 주입 조건이 새 행 번호에 기댄 탓 → 기존 인덱스 번호 기준으로 고쳐 재실행)
- 임시 환경 정리함 : 임시 앱 9951·HTTPS 프록시 8443/8444 종료, 임시 DB(lexorma_dec6_app·_test·_full·_full2, dec16b_src) 삭제, MCP 토큰 파일·TLS 키·H2 임시 폴더 삭제. 사용자 앱 9940·개발 DB `lexorma` 손대지 않음
- **6차 종료 상태** : 남은 것은 pending-work.md 7.3 각 행의 '남은 것' (DEC-22 모델 VIEW·ERD 배치 교환은 정책 결정 대기). 다음 마이그레이션 V7
- 다음 세션 재개 : 사용자와 다음 작업 선택 (자동으로 고르지 않는다)
- 임시 환경 : PG 임시 DB `lexorma_dec6_test`(테스트)·`lexorma_dec6_app`(앱, 9951, 계정 e2e_user·e2e_viewer·e2e_manager·e2e_user2, 프로젝트 100 'E2E 충돌'), H2 `scratchpad/h2v6`. 끝나면 지운다

## 5차 통합 구현 (2026-11-16 시작)

지시 순서 : DEC-16 → DEC-22 → DEC-31 → DEC-30, 그 뒤 4차 남은 검증 (DEC-06 TABLE 이름 변경 후 재가져오기 기준값, DEC-05 UNIQUE·CHECK·FK 교체 테스트, DEC-07 화면 E2E, DEC-08·13 브라우저 충돌 E2E).
시작 Git : HEAD `b9a1087`, 스테이징 15개 그대로, `git status --short` 178줄 (4차 끝과 같음).

### 현재 진행

- DEC-16 백엔드 : **구현·테스트 통과** (화면·문서 남음)
  - V5 `V5__source_view_definition.sql` (pg·h2 동일, 생성기 scratchpad `gen_v5.py`) : `TB_SRC_VIEW_DFN`(정의 원문·암호화·해시·이전 해시·상태·길이·수집 일시·변경 반영 순서, VIEW 행 FK CASCADE, UNIQUE) · `TB_SRC_VIEW_DFN_IDNTY_HSTRY`(원문 확인 감사, 해시만). 새 용어 9, 예제 인덱스 +5(컬럼 +5) → 127/182, 마이그레이션 6
  - 도구 `source.view-definition.discover`(PostgresSourceTools, pg_get_viewdef, 256K 글자 한도 → TOO_LARGE), `SourceRefreshService.viewDefinitions` (마지막에 따로, 권한 오류 → NO_PRIVILEGE, 그 밖 → FAILED, 수집은 계속)
  - 정규화 : `SourceCatalogCanonical.Table.definitionHash` (JSON 에 있을 때만 — TABLE 해시 불변, `d1:` SHA-256), 못 읽은 VIEW 는 지난 해시 이어 씀(`PreparedCollection.Table.withDefinitionHash`), 변경 이력 항목 `definitionHash`
  - 반영 `SourceCatalogLoader.writeDefinition` (바뀐 것만, SecretCipher 있으면 암호화 scope `source-view-definition`), 조회 `SourceViewDefinitionService` (summary = 상태만 `TableDetail.viewDefinition`, read = verifyProjectManager + 확인 이력, accesses), API `GET /api/source/tables/{id}/viewDefinition`(+`/accesses`)
  - 테스트 : `SourceViewDefinitionIntegrationTest` 2 (실제 PG 원본, `LEXORMA_TEST_SOURCE_POSTGRES_URL=jdbc:postgresql://127.0.0.1:15439/postgres`), `V5SourceViewDefinitionMigrationTest` (H2·PG), 고정값 테스트 127/182/6, `V4SourceRenameMigrationTest` 는 target 4 로 고정, `ProjectDeletionIntegrationTest` 목록·준비 데이터
- DEC-16 화면·문서·브라우저 E2E 완료 (Chrome 확장, 한국어 다크·라이트). 문서 completed/source-view-definition-dec16.md
- DEC-22 **완료** : ERD PNG/SVG(`features/erd/exportImage.ts`, `html-to-image@1.11.11` package.json 추가), 모델 Export/Import/복사(`lexorma-data/exchange/`, `/api/modelExchange`, 화면 `/modelExchange`, 메뉴 `model-exchange`·`model-exchange-import`, 오류 코드 MODEL_EXCHANGE_*), `ModelExchangeIntegrationTest` 2 (H2 통과), 헤드리스 Chrome E2E (scratchpad `pw/e2e22.mjs` — playwright-core 는 scratchpad 에만 설치, Chrome 확장 연결이 끊겨 대신 씀). 문서 completed/model-exchange-dec22.md
- H2 전체 (DEC-16 반영 뒤) : 874건 중 실패 1 (`SourceCatalogIntegrationTest` 도구 목록 — 고침, 단독 재실행 통과). DEC-22 뒤 전체 재실행 아직
- DEC-31 **완료** : V6 `V6__embedded_mcp.sql`(생성기 scratchpad `gen_v6.py`), `lexorma-data/mcp/`, `/mcp`·`/api/mcp`, 화면 `/mcpAccess`, 메뉴 `mcp-access`·`mcp-manage`, 오류 코드 MCP_REQUEST_INVALID, 고정값 132/187/7, `McpEndpointIntegrationTest`·`McpDisabledIntegrationTest` 통과, 공식 SDK E2E(scratchpad `pw/e2e31.mjs`). 문서 completed/embedded-mcp-dec31.md
- DEC-30 **완료** : 가이드 33개(+2) 이미지 66장 `public/images/help/{ko,en}`, guide.{ko,en}.json `image`, 캡처 scratchpad `pw/guide.mjs`
- 4차 보완 DEC-06 **완료** : 재가져오기 기준값 별칭(`SourceImportService.renamedKeys` — 매핑 이력 RENAME 의 변경 전 원본 TABLE, 컬럼은 모델 컬럼 이름 보조 항목), `SourceRenameReimportJdbcTest`(실제 PG 원본, 수정 전 코드로 실패 확인)
- 4차 보완 DEC-05 **완료** : `SourceSyncIntegrationTest.replaceUniqueCheckForeignKey` (UNIQUE·CHECK·FK 교체, 판단 불가 CHECK 제외, 승인 거부, 계획 뒤 변경 409·무변경). 반영 중간 실패 주입 롤백은 하지 않음 (단일 트랜잭션)
- DEC-07 화면 E2E **완료** (실제 PG 원본, ko·en, scratchpad `pw/e2e07.mjs`), DEC-08·13 : 스키마 화면 두 탭 충돌 E2E(`pw/e2e08.mjs`) + `ModelBaseToleranceIntegrationTest`. 논리 모델·관계·식별자·FK 규칙 화면 브라우저 확인은 **하지 못함**
- 임시 앱·임시 원본 DB(dec07_src) 정리함
- 전체 테스트 (마지막 코드) : H2 881·실패 0·건너뜀 38, PostgreSQL 881·실패 0·건너뜀 9. 도중에 고친 것 : V6 `TB_PRJ_MCP_STNG` 컬럼 USE_YN → ACTVTN_YN(표준 용어 도메인 BOOLEAN 일치 — SmokeTest 품질 진단), MenuAuthorizationTest 그룹 고정값
- **5차 종료 상태** : 지시서의 DEC-16·22·31·30 구현, 4차 보완(06·05·07, 08·13 일부). 남은 것은 pending-work.md 7.3 각 행의 '남은 것'
- Git (5차 끝, 2026-11-16 22:04) : HEAD `b9a1087` 그대로, 커밋·푸시 없음. **스테이징이 15 → 67개(A 53·AM 13·AD 1)로 늘었다** — `.git/index` 수정 시각 21:12, 이 세션은 git add 를 실행하지 않았다 (그 시각까지 있던 새 파일들이 추가됨 : IDE 자동 추가 등 외부 동작으로 보인다). 지시대로 reset 하지 않고 그대로 둔다. `git status --short` 232줄
- 다음 세션 재개 : 논리 모델·논리 관계·논리 식별자·FK 규칙 화면의 브라우저 충돌 E2E(두 계정), 권한 낮은 계정으로 가이드 숨김 확인, DEC-16 영어 화면 → 4차 보완(DEC-06 TABLE 이름 변경 재가져오기 기준값, DEC-05 교체 테스트, DEC-07 E2E, DEC-08·13 E2E) → H2·PG 전체
- 임시 앱 : scratchpad e2e.jar 를 9951 에서 띄워 둔 상태일 수 있다 (`pkill -f e2e.jar`), 데이터 scratchpad/e2e-data

## 4차 통합 구현 (2026-11-16 시작)

지시 순서 : DEC-06 → DEC-04 → DEC-05 → DEC-07 → DEC-16 → DEC-22(ERD 이미지·모델 Export/Import) → DEC-31 → DEC-30(이미지).

### 현재 진행

- DEC-06 : 백엔드·화면·H2 테스트 완료 (PostgreSQL 전체·증분 업그레이드·브라우저 E2E 는 DEC-04~07 묶음으로 미룸). 상세 [completed/source-rename-mapping-dec06.md](completed/source-rename-mapping-dec06.md)
- DEC-04 : 백엔드(`SourceDeletionService`·`/api/sourceMapping/deletions`)·화면(`SourceDeletionPanel.tsx`)·H2 테스트 완료. core `SourceCatalogReader.dropSuspected` 추가. 상세 [completed/source-sync-dec04-05-07.md](completed/source-sync-dec04-05-07.md)
- DEC-07 : 가져오기 항목 `TABLE:COMMENT_CLEAR`·`COLUMN:<c>:COMMENT_CLEAR` (Action CLEAR), 화면 문구, H2 테스트 완료 (05 와 의존 없어 먼저 함)
- DEC-05 : 가져오기 항목 `<종류>:<이름>:REPLACE` (Action REPLACE, Reason REFERENCED 추가), 화면 문구, H2 테스트 완료
- 완료 기록 : completed-work.md [DEC-IMPL-4], pending-work.md 7.3 (DEC-06·04·05·07 행), CLAUDE.md (V4·다음 V5·예제 인덱스 122/177/5·M-4 계약 문구)
- E2E : DEC-06·04 화면 확정 완료 (실제 PostgreSQL 원본, 한국어·다크·1280). 임시 앱·임시 DB(`dec06_src`·`lexorma_dec4_test`) 정리함
- **중단 지점 : DEC-16 착수 전** (이 세션의 컨텍스트 한도)
- 다음 마이그레이션 번호 : V5 (V4 = `V4__source_rename_mapping.sql`, 생성기 스크립트는 세션 scratchpad `gen_v4.py` — 세션이 끝나면 사라진다)

### Git 보호 (작업 시작 시점)

- HEAD `b9a1087`, 브랜치 `dev`. `git status --short` 147줄 : `??` 17 · `A` 11 · `AD` 1 (`docker/lexorma/docker-compose.secrets.yml`) · `AM` 3 · ` M` 115.
- 금지 : git add · commit · push · reset · stash · clean, 기존 변경 되돌리기. 스테이징 상태를 건드리지 않는다.

### 완료한 기능 (코드 작성, 검증 전)

- 테스트 클래스 `SourceSyncIntegrationTest` (DEC-06 2 · DEC-04 2 · DEC-07 1). 예전 이름 SourceRenameMappingIntegrationTest 는 없다.
- DEC-06 화면 : `features/source/SourceRenamePanel.tsx` (원본 매핑 관리 화면 아래), 비교 화면 `RENAME_MAPPED` 문구, `en.ts`.
- DEC-06 백엔드 : `TB_SRC_COL_MPNG`·`TB_SRC_MPNG_HSTRY` 엔티티·저장소, `SourceMappingHistoryRecorder`, `SourceRenameService`·`SourceRenameRestController` (`/api/sourceMapping/renames/**`),
  비교기·가져오기의 컬럼 키(`Context.columnKey`, `Work.key`), TABLE 매핑 확정·변경·해제·가져오기 생성 이력, `ProjectDataCleanup` 목록.

### 신규 DB 마이그레이션

- `V4__source_rename_mapping.sql` (postgresql·h2 동일, 시퀀스 문법만 다름) : 2 테이블, 새 용어 2, 예제 인덱스 +6 (컬럼 +8), FK 2 (CASCADE·SET NULL). 아직 적용 검증 전.

### 실행한 테스트

- 2026-11-16 H2 전체 (`./gradlew test -PfrontendPrebuilt=true`, DEC-06 반영 후) : 864건 · 실패 0 · 건너뜀 33.
- React `npm run typecheck` 통과 (DEC-06·04·05·07 화면).
- 2026-11-16 PostgreSQL 전체 (`--rerun`, 임시 DB `lexorma_dec4_test` @ localhost:15439, DEC-06·04·05·07 반영 후) : 868건 · 실패 0 · 건너뜀 25.
- `V4SourceRenameMigrationTest` (V3 → V4 증분) · `V0SeedMigrationTest` · `LexormaIndexSeedTest` : H2·PostgreSQL 통과.

### 실패·미해결

- 개발 DB `lexorma`(사용자 DB)에는 V4 를 적용하지 않았다 — 사용자가 다음에 앱을 띄울 때 V4 만 적용된다 (증분 경로는 테스트로 확인).
- 임시 DB 는 모두 지웠다.

### 다음 작업 (정확한 재개 지점)

1. (남은 검증) DEC-06·04 영어·라이트·390px, DEC-05 교체·DEC-07 비우기 화면 확정. E2E 준비 스크립트는 세션 scratchpad `e2e.py`(로그인·SEC-1 규칙·연결·수집·가져오기) — 세션이 끝나면 사라진다.
2. **DEC-16 원본 VIEW 정의 수집** : `PostgresSourceTools` TABLES 쿼리에 `pg_get_viewdef` (relkind v), `SourceCatalogSpec.Table` 에 정의, `SourceCatalogCanonical` 형식 올리기(v2), 버전 JSON·변경 이력으로 정의 변경 비교, 정의 조회는 프로젝트 관리자·ADMIN 만·로그 본문 보호·AI 경로 제외 확인, 원본 카탈로그 화면 표시.
3. DEC-22 (ERD PNG/SVG → 모델 Export → 모델 복사·Import), DEC-31 (내장 MCP), DEC-30 (가이드 이미지) 순서.
