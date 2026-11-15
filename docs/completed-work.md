# Lexorma — 완료 작업 및 구현 현황

## 1. 문서 개요

- **목적** : 지금 Lexorma 에 무엇이 구현되어 있는지를 한 문서에서 확인한다. 작업마다 구현 범위, 그 작업에서 확정한 설계 결정, 기록된 검증 결과를 요약하고 상세는 각 기능의 설계·구현 기록으로 연결한다 — 완료된 작업의 상세 기록은 `completed/`, 상태와 무관한 기술 기준·결정 기록(두 로드맵 포함)은 `reference/` 에 있다. `completed/` 에 기록이 있어도 그 기능의 후속이 모두 끝난 것은 아니다 (남은 범위는 pending-work.md).
- **관리 기준**
  - 이 문서와 [pending-work.md](pending-work.md) 가 **작업 상태의 공식 기준**이다. 두 로드맵([data-modeling-roadmap.md](reference/data-modeling-roadmap.md), [ai-metadata-roadmap.md](reference/ai-metadata-roadmap.md))의 상태 표·"현재 구현 상태" 문구는 2026-11-15 시점에서 더 갱신하지 않는다 (설계 원칙·결정 기록으로만 유지).
  - 실제 구현과 기존 기록에서 완료가 확인된 범위만 적는다. 일부만 끝난 작업은 끝난 범위만 여기 두고, 남은 범위는 같은 작업 ID 로 pending-work.md 에 둔다.
  - 검증 결과는 각 기록에 남은 사실만 옮긴다. 기록에 없는 검증을 했다고 쓰지 않는다.
  - 작업 ID 는 기존 로드맵의 것을 유지한다 : `M-n`(데이터 모델링), `AI-n`(AI 로드맵 n단계), `SEC-1`, `D-nn`(모델링 결정), `U-nn`(AI 결정).
- **최종 갱신일** : 2026-11-15

## 2. 프로젝트 기반 구조

### [기반] 기술 스택 · 배포 구조

- 상태: 완료 (React 전환 포함 — 로드맵에 다시 등록하지 않는다)
- 구현 내용
  - Java 21 + Spring Boot 4.1 멀티 모듈 Gradle (`common ← core ← system·operation·data·source ← app`, 가운데 모듈끼리는 서로 참조하지 않음 — `ModuleBoundaryTest`).
  - React 19 + TypeScript(Vite) 화면을 같은 JAR 의 정적 파일로 제공하는 단일 서버. 기존 Thymeleaf·jQuery 화면(로그인·회원가입·내 정보, 대시보드, 공지, 사용자·프로젝트·시스템 로그, 데이터 사전, 테이블·컬럼 관리, 도움말)과 공통 UI(그리드, 대화상자, 반응형, 다크 모드)는 React 로 전환 완료.
  - 단일 JAR, Docker 이미지 3종, Flyway(PostgreSQL·H2). `h2-local` 은 H2 파일 DB(`~/.lexorma/data`, JAR 교체 후에도 유지) — [h2-file-storage.md](reference/h2-file-storage.md).
  - 한국어 원문 + 영어 조회 방식의 i18n (백엔드 `EnglishMessages`·`errors_{ko,en}`, 프론트 `t()`·`en.ts`, 누락은 `MessagesCoverageTest`·`typecheck` 가 잡는다).

### [기반] 인증 · 세션 · 프로젝트 테넌시

- 상태: 완료
- 구현 내용 : 2단계 로그인(프로젝트 선택), 초기 비밀번호 변경 강제, Spring Session JDBC(재시작 후 유지, 동시 세션 1개), JSON 401/403, CSRF 토큰, `@PreAuthorize` 역할 계층(`ADMIN > MANAGER > DBA > USER > VIEWER > NOT_APPROVE`), 프로젝트 단위 데이터 격리, `ProjectAccessInterceptor` 의 요청마다 재검증(회수 시 세션 종료).
- 관련 후속 기록
  - **프로젝트 복구 로그인** (P1 운영 안정화) : 모든 프로젝트가 비활성일 때 ADMIN 이 `ROLE_RECOVERY` 세션으로 `/api/recovery/**` 만 호출해 프로젝트를 다시 활성화 — [project-recovery-login.md](completed/project-recovery-login.md).
  - **프로젝트 삭제 정합성** (2026-11-12) : 프로젝트 데이터를 SQL 로 FK 순서대로 지운다(모델 변경 이력·버전 포함), 원본 카탈로그는 `ProjectDeletedEvent` 로 같은 트랜잭션에서 정리. 검증 : 삭제 뒤 모든 `TB_` 테이블 행 수가 생성 전과 같음, 헤드리스 Chrome(PostgreSQL) — [project-deletion.md](completed/project-deletion.md).

### [기반] 권한별 UI 액션 점검 (M-9 착수 전 안정화)

- 상태: 완료 (2026-11-12, 1차·2차)
- 구현 내용 : 버튼·메뉴를 변경 API 의 역할과 같은 액션 권한으로 맞춤 (`MenuAuthorization.MENU_ROLES` 단일 맵 → `/api/auth/me` → 화면). 2차에서 데이터 사전 승인에 승인 권한 확인 추가(DBA 역할만으로 승인 불가), 컬럼 조회 API 를 VIEWER 에 개방.
- 검증 결과 : H2·PostgreSQL 전체 테스트, 역할별 API·브라우저 확인 (`UiActionAuthorizationIntegrationTest`) — [ui-action-authorization-audit.md](reference/ui-action-authorization-audit.md). 남은 정책 결정은 pending-work.md [AUTH-1].

### [기반] 데이터베이스 · 마이그레이션 체계

- 상태: 완료 (정책은 계속 유효)
- 구현 내용
  - Flyway 가 스키마를 소유하고 Hibernate 는 `validate`. 모든 스키마 변경은 PostgreSQL·H2 두 디렉터리에 같은 내용으로 둔다.
  - **2026-11-15 Flyway 초기화 (소유자 결정)** : 예전 V2~V10 을 `V0__init.sql`·`V1__seed_data.sql` 기준선으로 합침. 이후 증분 : `V2__ai_dictionary_evaluation.sql`(AI-5 품질 평가), `V3__notice_ai_menu.sql`(안내 공지의 AI 절, 데이터만). **다음 스키마 변경은 V4.**
  - 원칙 : 적용된 마이그레이션은 고치지 않는다(주석·공백 포함), 증분 업그레이드를 검증한다, 데이터 이전은 새 마이그레이션에 명시한다, 새 `TB_` 테이블의 COMMENT·예제 데이터는 같은 마이그레이션에 넣는다 (`V0SeedMigrationTest`·`SmokeTest`). 경과 기록은 [data-modeling-roadmap.md 마이그레이션 정책](reference/data-modeling-roadmap.md#마이그레이션-정책).
  - Lexorma 자체 테이블 명명 규칙(`TB_` + 표준 단어 약어, `{X}_SN`, `SQ_*`, `PK_/FK_/IX_/UX_`) — [db-naming-standard.md](reference/db-naming-standard.md). 예제 프로젝트(LEXORMA, 프로젝트 0)는 실제 스키마와 FK·UNIQUE·CHECK·기본값·IDENTITY 까지 일치 — [lexorma-example-sync.md](reference/lexorma-example-sync.md).
  - **스키마 단위 TABLE/VIEW 이름** (2026-11-13, D-25 변경 · D-03 관련) : 이름 유일성 범위를 프로젝트 → 스키마로 변경 (`UX_TBL_01 (SCHM_SN, TBL_NM)`), 같은 스키마의 TABLE·VIEW 는 이름 공간 잠금 + 서비스 검사, 이름 기반 참조는 `TableNameResolver`(`테이블명` 또는 `스키마명.테이블명`) — [schema-scoped-naming.md](reference/schema-scoped-naming.md).

### [AI-5-REASON] AI 재검토 사유의 사용자 문구

- 상태: 완료 (2026-11-15) · 관련 기능: AI (`features/dictionary/ai/reviewReason.ts`·`ReasonView.tsx`, 목록·상세·검토 이력)
- 문제 : 재검토 사유가 저장된 문자열 그대로 보였다 (예 : `BIZ_DESCRIPTION_CHANGED COLUMN:28 TB_PRJ.DB_TYPE_CD [bizDescription]`, 이력의 동작 코드 `REVIEW_REQUIRED`·`REVALIDATE`, 변경 목록의 필드 키 `standardTermsId`·`schemaId` 와 그 ID 값).
- 실제 사유 (서버 코드 확인) : `AiDescriptionReviewService.reason` 이 변경마다 `종류 유형:ID 이름 [필드, …]` 를 `; ` 로 잇는다 (1000자에서 뒤 변경을 뺀다). 종류는 `ProposalVersions`·`AiDescriptionReviewService` 의 8가지 — BIZ_DESCRIPTION_CHANGED, TERM_LINK_CHANGED, MODIFIED, MODIFIED_UNKNOWN, TARGET_DELETED, OBJECT_DELETED, SOURCE_CHANGED, SOURCE_TABLE_MISSING. 이 형식은 제안의 `reviewReason`(재검토 필요로 바꿀 때 : 승인 시 감지·근거 변경 재확인)과 이력 REVIEW_REQUIRED·RECHECK_FLAGGED 에 저장된다. 그 밖의 서버 고정 문구 : 생성 중 변경("생성 중 대상이 바뀌었습니다" — 제안 사유·GENERATE 이력), 근거 재검증(REVALIDATE : "남은 변경 없음" / "변경 확인 후 계속 검토 (변경들) — 검토자 사유"), 대체(SUPERSEDE "새 제안 #N 으로 대체", GENERATE "제안 #… 을 대체"). 반려·보류·종결·검토함·승인의 사유는 사용자가 쓴 글이다. 일괄 승인 결과는 원래 건수(승인·재검토 필요·실패)만 보여 사유 코드가 나오지 않았다 — 바꾸지 않았다.
- 구현 내용
  - **저장·API 그대로** : DB·응답·사유 문자열·판정 로직을 바꾸지 않았다 (마이그레이션·서버 변경 없음, AI 호출 없음). 화면이 읽을 때만 바꾼다.
  - **해석** (`reviewReason.ts`, React·i18n 없는 순수 함수) : 이력 동작으로 사유의 출처를 정한다 — 사용자 동작(REVIEW·APPROVE·APPROVE_EDITED·REJECT·HOLD·CLOSE)은 글 그대로(코드처럼 보여도), 그 밖(모르는 새 동작 포함)은 서버 사유로 보고 알려진 형식만 해석한다. 모르는 종류·잘못된 형식은 "근거를 다시 확인해야 합니다" 기본 안내 하나로 (원인을 꾸며내지 않고 코드·ID 를 보이지 않는다). 빈 값·null 은 "기록 없음". 잘린 재검증 문구도 읽는다.
  - **표시** (`ReasonView.tsx`) : 사유마다 제목 · 확인할 것 · 대상(유형 + 기록 당시 이름, "기록 당시 이름" 표시 — 지금 이름과 다를 수 있다) · 바뀐 항목(모델 이력의 필드 이름표 재사용, 연관 `…Id`·`…Name` 은 하나로, 원본 비교 `컬럼:comment` 등은 "컬럼 원본 COMMENT"). 내부 ID 는 보이지 않는다. 기록에 없는 이전 값은 보이지 않는다 (변경 전후 값은 기존처럼 지금 계산한 변경 목록에만).
  - **적용 화면** : 목록의 재검토 사유(짧은 이름 — 예 "다른 사용자가 업무 설명을 수정함"), 상세의 "재검토 사유 (기록)", 검토 이력(동작 이름 + 사유), 생성 뒤 변경 목록의 필드 이름과 ID 값 숨김. 업무 설명 충돌 비교(생성 당시·현재·AI 제안)는 기존 화면을 그대로 쓴다.
  - **문구** : 한국어·영어 (제목 14개 · 설명 · 동작 이름 · 필드 라벨). 업무 설명 변경은 "제안이 틀렸다는 뜻은 아니고 기준이 바뀌었다" 를 함께 안내한다. 목록 조회에 쿼리를 더하지 않았다 (이미 받은 문자열을 읽는다).
- 검증 결과
  - 형식 계약 : `ProposalReasonFormatTest`(lexorma-data, 8종류·유형/이름/필드 없음·1000자 자르기에서 화면 파서와 같은 정규식으로 읽힘), `ReviewReasonContractTest`(서버 소스의 종류 = 화면 파서의 종류, 종류마다 문구 존재 — 서버에 새 종류를 더하면 실패). 화면 파서는 Node 26 의 TypeScript 실행으로 따로 확인 (실제 형식·여러 변경·유형 없음·원본 필드·생성 중 변경·모르는 코드·잘못된 형식·null/빈 값·사용자 글·대체·재검증·잘림·모르는 새 동작 — 저장소에 테스트 실행기는 더하지 않았다).
  - H2 전체 786건 실패 0 (건너뜀 34), PostgreSQL 18.6 격리 DB 전체 786건 실패 0 (건너뜀 25). 승인 안전성 테스트(`AiDictionaryApprovalSafetyIntegrationTest` 등)는 바꾸지 않고 통과. `npm run typecheck`·빌드 통과.
  - 실제 브라우저 (격리 h2-local :19941, 가짜 Ollama) : 생성 뒤 업무 설명 수정 → 승인 시도 → 재검토 필요가 목록 "다른 사용자가 업무 설명을 수정함", 상세 제목·안내·대상·바뀐 항목, 이력 "재검토 필요 (검토 대기 → 재검토 필요)" 로 보이고 승인 버튼은 그대로 없음. 대체·대체됨 이력, 사용자가 쓴 반려 사유("OK 확인함 …") 그대로. 일괄 승인 승인 1·재검토 필요 1·실패 0 과 목록의 사유. 응답 주입으로 : 모르는 코드·모르는 새 동작 → 기본 안내, 표준 용어 연결 변경·메타데이터·원본 변경·원본 테이블 없음·근거 없음·대상 삭제·재검증 이력 → 각 문구, 화면 어디에도 코드·ID 없음 (표준 용어 연결 변경은 실제 사용자 조작으로 만든 것이 아니다 — [COL-TERM] 이후). 한국어 라이트·다크, **영어**(빈 창의 `navigator.language` 를 바꾸고 앱 HTML 을 써 넣는 방법 — 텍스트로 확인, 그 창은 스크린샷 불가), 390·606px 창에서 상세의 가로 넘침 없음(수치).
  - **확인하지 못한 것** : 영어 다크·라이트의 화면 모습(스크린샷), 390px 화면 모습.

### [AI-5-PAGE] AI 설명 제안 대상 선택의 서버 쪽 나누기

- 상태: 완료 (2026-11-15) · 관련 기능: AI · 데이터 사전 (`AiDictionaryQueryService.targetTables`, `GET /api/ai/dictionary/targets/tables`, `features/dictionary/ai/TargetPicker.tsx`)
- 문제 : 대상 테이블 목록은 서버가 프로젝트 전체 `TableInfo` 를 읽어 Java 에서 정렬·거르기·검색한 뒤 앞 500개만 주고, 화면이 그 안에서 쪽을 나눴다. 테이블이 500개를 넘으면 뒤쪽 테이블은 검색해도 찾을 수 없었다 (예 : 예제 테이블 `TB_STD_WORD_PRHBT` 가 1,273개 프로젝트에서 빠졌다).
- 구현 내용
  - **서버** : 새 API `GET /targets/tables?onlyMissing&keyword&page&size` (VIEWER, 응답은 기존 제안 목록과 같은 Page 형식 `content`·`number`·`totalElements`·`totalPages`). page 0 부터, size 기본 30·최대 100(0 이하 → 30, 음수 쪽 → 0, 범위 밖 쪽 → 빈 목록 + 건수). Specification + Pageable 로 DB 에서 한 쪽만 읽고 같은 조건으로 건수를 센다. 정렬은 테이블 이름 → ID 고정(요청으로 바꿀 수 없음, DB 콜레이션을 따른다 — H2 는 코드 순, PostgreSQL 은 언어 규칙). 검색은 예전과 같은 범위(이름·테이블 코멘트 부분 일치, 대소문자 무시)이고 `%`·`_`·`\` 는 글자 그대로. "업무 설명 빠진 것만"(업무 설명이 없거나 업무 설명 없는 컬럼이 있는 테이블)은 프로젝트 단위 비상관 `IN` 하위 쿼리. 컬럼 수·최근 제안은 그 쪽의 테이블 ID 에만 묻는다 (새 저장소 메서드 2개). 프로젝트 범위·VIEWER 재검증은 기존 `ExecutionContextVerifier` 그대로.
  - **기존 계약 유지** : `GET /targets` 는 그대로 — tableId 가 있으면 그 테이블·컬럼 상세, 없으면 같은 조건·순서로 앞 500개(품질 평가 화면의 테이블 고르기가 쓴다, 이제 DB 에서 자른다).
  - **화면** : 서버 쪽 요청(30개), 전체 건수 "테이블 N개", 처음·이전·n/N·다음·마지막(연달아 눌러도 누른 만큼 이동), 검색어는 입력이 300ms 멈춘 뒤 요청, 검색·거르기를 바꾸면 첫 쪽, 목록이 줄어 지금 쪽이 범위를 벗어나면 마지막 쪽, 조건별 캐시(`keepPreviousData`)라 늦게 온 예전 응답이 지금 결과를 덮지 않는다. 500개 안내 문구는 지웠다.
  - **선택 (A안, 2026-11-15 결정)** : 선택은 원래 쪽과 따로(`Set<테이블 ID>`·`Map<컬럼 ID, 테이블 ID>`) 두던 구조를 그대로 쓴다 — 쪽 이동·검색·거르기를 바꿔도 남는다. 머리줄 체크는 **지금 쪽의 테이블만** 고르거나 푼다(이름 "현재 페이지 테이블 전체 선택 / 선택 해제", 일부 선택 표시). 선택 요약 "선택 : 테이블 a개 · 컬럼 b개 · 지금 쪽에 보이지 않는 테이블 c개 포함"과 기존 [선택 해제]. 테이블·컬럼 선택 관계(고른 테이블은 옵션대로 테이블·설명 없는 컬럼, 고른 컬럼은 그 컬럼)는 바꾸지 않았다. 대상 선택 영역은 프로젝트 ID 로 키를 걸어 프로젝트가 바뀌면 새로 시작한다 (프로젝트 변경은 재로그인이라 캐시도 비워진다).
  - **미리 보기·생성** : 기존대로 화면의 선택 전체로 요청을 만든다 (지금 쪽 목록을 다시 모으지 않는다). 서버는 대상마다 프로젝트 소속을 다시 확인하고(다른 프로젝트 ID 가 섞이면 `TABLE_IS_NOT_DEFINED` 로 거절), 생성 한도(`MAX_TARGETS` 100)·동시 실행 하나·근거 검증·승인 안전성은 그대로다.
  - **제외 (결정)** : 검색 결과 전체·프로젝트 전체를 한 번에 고르는 기능, 모든 쪽 자동 선택, "전체 선택 후 일부 제외" 모델은 만들지 않았다. 요구가 생기면 따로 검토한다.
- 검증 결과
  - `AiDictionaryTargetPageIntegrationTest` (1,054 테이블 + 다른 프로젝트) : 처음·중간·마지막 쪽, 모든 쪽을 이으면 DB 순서와 같고 빠짐·겹침 없음(같은 이름 두 스키마는 ID 순), 쪽 크기·번호 범위, 한 쪽의 SQL 문 수가 위치와 무관하게 일정(8 — 실행 컨텍스트 재검증 포함), 예전 500개 밖 테이블 검색, 테이블 코멘트 한글·대소문자, LIKE 문자, 공백 검색, 업무 설명 빠진 것만(컬럼 기준 포함), 0건, 예전 목록과 같은 순서, 다른 프로젝트 건수 제외·멤버 아님 거부, 여러 쪽에서 고른 대상의 미리 보기와 다른 프로젝트 ID 거절. `SmokeTest` 에 HTTP 바인딩(`/targets/tables`) 추가.
  - H2 전체 783건 실패 0 (건너뜀 34), PostgreSQL 18.6 격리 DB 전체 783건 실패 0 (건너뜀 25). `npm run typecheck`·빌드 통과 (프론트엔드 테스트 실행기는 없다).
  - 성능 (`AiDictionaryTargetPagePerformanceTest`, `LEXORMA_PERF=true`, 테이블마다 컬럼 5개, 7회 평균 ms · 요청당 할당 MB) :

    | 테이블 | DB | 첫 쪽 | 중간 쪽 | 마지막 쪽 | 검색 1건 | 업무 설명 빠진 것만 | 예전 방식(전체 로드·500) |
    |---|---|---|---|---|---|---|---|
    | 30 | H2 / PG | 3.4 / 6.2 | 3.0 / 5.0 | 2.5 / 4.7 | 1.7 / 3.9 | 2.8 / 5.3 | 1.2 / 4.1 |
    | 500 | H2 / PG | 2.8 / 5.5 | 3.0 / 5.9 | 1.5 / 4.1 | 1.9 / 3.6 | 3.4 / 7.4 | 3.0 / 5.8 |
    | 1,000 | H2 / PG | 3.7 / 6.3 | 2.4 / 6.7 | 1.7 / 4.5 | 1.8 / 4.5 | 5.5 / 8.7 | 4.8 / 8.2 |
    | 3,000 | H2 / PG | 2.2 / 10.5 | 2.0 / 9.9 | 1.8 / 9.6 | 2.3 / 5.2 | 11.9 / 15.5 | 13.2 / 21.0 |

    요청당 할당은 새 방식 0.3~1.1MB(PG 0.3MB, H2 는 DB 가 같은 JVM 이라 더 크다), 예전 방식 3,000 테이블 6.6(H2)·9.2(PG)MB. 처음 구현한 "업무 설명 빠진 것만"(OR 안의 상관 EXISTS)은 PostgreSQL 3,000 테이블에서 2,249ms 였다 → 비상관 IN 으로 바꿔 15.5ms. 예전 방식 수치는 권한 재검증·최근 제안 연결을 뺀 데이터 접근만 다시 돌린 값이다.
  - 실제 브라우저 (격리 인스턴스 h2-local :19941, 1,273 테이블) : 예전 500개 밖 테이블 검색, 43쪽 순회(1,273개·겹침 없음·정렬 유지), 쪽 간 선택 유지(10개), 지금 쪽 전체 선택·해제가 다른 쪽 선택에 영향 없음, 검색어 바꿔도 선택 유지, 미리 보기·생성 요청이 2·3·10쪽 선택 8개 전체를 담고 생성 작업 7개(업무 설명 있는 1개는 서버가 제외)가 정확히 그 테이블에 제안을 만듦, 늦게 도착한 예전 검색 응답이 최신 결과를 덮지 않음, 연달아 누른 쪽 이동, 업무 설명 빠진 것만 973개(서버와 같음), 프로젝트 B 로 바꾸면 0개·선택 없음. 한국어·다크 모드.
  - **확인하지 못한 것** : 영어 화면(언어가 브라우저 언어만 따른다 — [AI-5-UI] 와 함께), 390·606px 폭(이 브라우저 창 크기가 바뀌지 않았다), 라이트 모드 화면.
- 남은 제한 : 품질 평가 화면의 테이블 고르기는 아직 예전 목록(앞 500개)이다 — pending-work.md [AI-5-EVAL] 참고 사항. PostgreSQL 3,000 테이블의 쪽 조회가 1,000 테이블보다 느려지는 것(6→10ms)은 건수 계산·정렬이 프로젝트 전체를 보기 때문이다 (`TB_TBL (PRJ_SN, TBL_NM)` 인덱스는 없다 — 마이그레이션이 필요해 이번에 넣지 않았다).

### [UI-DIALOG] 공통 대화상자 위치 · 끌기 버그 수정

- 상태: 완료 (2026-11-15) · 관련 기능: 공통 UI (`components/dialog/DialogProvider.tsx`, `styles/layout.css`)
- 원인 : CSS 는 대화상자를 뷰포트 기준(`position: fixed`, `top/left: 50%` + `translate(-50%,-50%)`)으로 가운데 두는데, 끌기 훅(`useDraggable`)이 열자마자 인라인 `position: absolute` 로 덮어썼다. 최상위 레이어(`showModal`)의 absolute 는 문서 첫 화면 기준이라, 스크롤한 화면에서 연 대화상자(예 : AI 설명 제안 일괄 승인 확인)가 스크롤한 만큼 화면 위로 벗어났다. 끌기 대상(알림·확인·일반 대화상자)이 모두 해당됐고, 끌기가 없는 팝업(`popup`)은 영향이 없었다.
- 구현 내용
  - 위치는 CSS 그대로 `fixed` + 가운데 정렬로 둔다. **끌기를 시작할 때만** 지금 보이는 자리(`getBoundingClientRect`, 뷰포트 좌표)를 `left/top` 으로 옮기고 `translate` 를 끈다. 끌기 계산도 같은 좌표계(`clientX/Y`)라 튀지 않는다. 입력 요소·버튼·표·링크 위에서는 끌지 않는 기존 규칙, 왼쪽 버튼만.
  - 화면 경계 : 옮긴 대화상자는 스크롤바를 뺀 뷰포트(`documentElement.clientWidth/Height`) 안에 머문다. 뷰포트보다 크면 왼쪽·위에 맞춰 제목·닫기 버튼이 보이고 내용은 안에서 스크롤한다. 창 크기가 바뀌면 다시 맞춘다. 새로 연 대화상자는 요소마다 인라인 위치라 늘 가운데에서 시작한다(이전 이동 좌표 재사용 없음).
  - 알림·확인(`dialog-message` 클래스 추가)은 세로 flex + `max-height: calc(100vh - 32px)` 로 긴 문구만 스크롤해 확인·닫기 버튼이 화면 밖으로 밀리지 않는다.
  - 호출 API(`useDialog().alert/confirm/open/popup`)와 ESC·닫기·확인 동작은 그대로. 터치 끌기는 원래 없었고 더하지 않았다.
- 검증 결과
  - `npm run typecheck`·`npm run build` 통과. 프론트엔드 테스트 실행기가 없어 자동 레이아웃 테스트는 없다 (브라우저로 검증).
  - 실제 브라우저 (격리 인스턴스 h2-local :19941, 9940 과 별개) : 최상단·중간·최하단 스크롤에서 열기 → 뷰포트 중앙(오차 0px), 실제 마우스 끌기(이동량 = 마우스 이동량), 오른쪽 아래·왼쪽 위 끝까지 끌어도 뷰포트 안(스크롤바 제외), 다시 열면 가운데, ESC·닫기 취소(승인 없음)·확인 시 일괄 승인 3건 반영, 입력 폼 대화상자(테이블 추가)에서 옮긴 뒤 입력칸 포커스·입력, AI 제안 상세(`dialog-wide`)의 끌기·내부 스크롤·ESC, 상세 위에 겹친 확인 창 순서대로 닫힘. 390×500·390×800·606×700 창(실제 클릭으로 연 창, 수치 측정) : 가운데 표시, 가로 넘침 없음, 끝까지 끌기·창 크기 변경 후 재배치, 긴 문구에서도 확인·닫기 버튼 노출. 라이트·다크 확인.
  - **영어 화면은 확인하지 못했다** (언어가 브라우저 언어만 따르고 바꿀 수 없음 — [AI-5-UI] 와 함께 확인). 다른 브라우저(Safari·Firefox)는 확인하지 않았다.
- 함께 한 문서 경로 정리 : 코드 주석 20곳·테스트 주석 8곳·설정 주석 4곳(`application.yml`, `docker/lexorma/docker-compose.h2.yml`)의 `docs/<이름>.md` 를 `docs/completed/`·`docs/reference/` 경로로 고쳤다 (주석만, 로직 변경 없음, 가리키는 장 번호 확인). 적용된 Flyway 마이그레이션 주석의 옛 경로 50곳(PostgreSQL·H2 각 25곳)은 체크섬 때문에 그대로 둔다. 백엔드 컴파일과 H2 전체 테스트 779건(실패 0, 건너뜀 33) 통과.

## 3. 데이터 표준 관리

### [기존 기능] 표준 단어 · 용어 · 도메인

- 상태: 완료 (보존 대상)
- 구현 내용 : 표준 단어(한글명·영문 약어·영문명·형식단어·이음동의어·금칙어·중복 검사·`_` 금지), 표준 용어(단어 조합 = 논리명/물리명, 단어 수 일치, 도메인 연결), 표준 도메인(그룹·분류·타입·길이·소수점·형식·단위·허용값), 사용 중 삭제 차단, 데이터 사전 승인, 엑셀 업로드·다운로드·템플릿.

### [M-5] 데이터 표준화 유지·확장 — 1차 · 2차

- 상태: 완료 범위만 기록 (잔여는 pending-work.md [M-5])
- 구현 내용
  - 1차 : 단어·용어·도메인 사용 현황 조회 API·화면 — [standardization-usage-m5.md](completed/standardization-usage-m5.md).
  - 2차 : 표준화 품질 진단(용어 미연결·컬럼명 불일치·도메인 정의 불일치) API·화면 — [standardization-quality-m5.md](completed/standardization-quality-m5.md).
- 설계 결정 : 예제 데이터는 실제 스키마를 바꾸지 않고 용어의 도메인을 실제 정의에 맞춰 진단 결과 0건 (`SmokeTest` 가 0건을 단언).
- 검증 결과 : H2/PostgreSQL 테스트, 헤드리스 Chrome 브라우저 검증 (기록 기준).

## 4. 데이터 모델링

### [M-0] ERD 기술 검토 · 라이브러리 선정

- 상태: 완료 (PoC, 2026-11-10)
- 설계 결정 (D-01) : React Flow 12.12.0 + ELK.js 0.12.0(layered, Web Worker). ELK EPL-2.0 고지·라이선스 전문을 `/jsons/licenses/` 로 배포. **법률 검토는 별도 확인 필요** (pending-work.md [M-0]).
- 검증 결과 : Mock 데이터 PoC, headless Chrome 측정 — [erd-poc-m0.md](completed/erd-poc-m0.md).

### [M-1] 물리 모델 보강 — 스키마 · 관계 · 인덱스/제약

- 상태: 완료 (화면은 M-3·M-8 에서 구현)
- 구현 내용 : 스키마 엔티티, FK 컬럼 쌍(`TB_TBL_REL_COL`), 복합 인덱스, UNIQUE/CHECK, `refTableName` → 관계 이전. **관계 동기화 공통 경계** (2026-11-11, `ReferenceSyncBoundary` + `ReferenceSyncListener`, 커밋 직전 재동기화) — [physical-model-m1.md](completed/physical-model-m1.md).
- 설계 결정 : D-03 스키마는 별도 엔티티(프로젝트마다 기본 스키마 1개, DDL 무접두), D-04 `refTableName` 은 단일 FK 약식 입력으로 유지(확정되는 것만 `REF_COLUMN` 관계로 동기화, 미해결은 값 보존 + DDL 경고).

### [M-2] 물리 ERD 조회 · 레이아웃 저장

- 상태: 완료 (브라우저·대규모 성능 실측은 pending-work.md [M-2])
- 구현 내용 : 조회 API·화면, 사용자별 레이아웃 저장 — [physical-erd-m2.md](completed/physical-erd-m2.md).
- 설계 결정 : D-02 한 번에 최대 300 테이블(기본 100), 조건·이웃 단위 조회, 140px 미만 컬럼 생략, 노드당 20컬럼. D-05 메뉴 = 물리 ERD, VIEWER 이상. D-10 레이아웃 = 사용자 × 프로젝트 1건. D-15 [배치 저장] 버튼으로만 저장, 오래된 탭은 409.
- 검증 결과 : H2/PostgreSQL 테스트.

### [M-3] ERD ↔ 메타데이터 관리 연동 · 스키마 관리 UI

- 상태: 완료 (2026-11-11)
- 구현 내용 : 스키마 관리 화면(`/schemaManage`), 테이블·컬럼 화면의 스키마 연동, ERD ↔ 테이블·컬럼 관리·데이터 사전 이동, 기본 스키마 이름 고정, `SCHEMA_PERMISSION_DENIED` — [physical-erd-m3.md](completed/physical-erd-m3.md).
- 검증 결과 : H2/PostgreSQL 테스트, 이후 M-5 와 함께 헤드리스 Chrome 검증 (로드맵 M-5 기록). 스키마 이동 UI·AI 결과 표시는 하지 않음 (pending-work.md).

### [M-6] 논리 데이터 모델 — 1차 · 2차

- 상태: 완료 (완료 조건 충족)
- 구현 내용 : 논리 모델·엔터티·속성·식별자(복합)·관계(양쪽 최소·최대 참여 수), `/api/logical/**`, 표준 용어 연결, 승인. 2차 : 관리 화면(`/logicalModel`), 논리 ERD(`/logicalErd`, 모델별·사용자별 레이아웃 `TB_LGCL_ERD_LYT`), 사이드바 개편(시스템 관리 / 데이터 사전 / 논리 모델 관리 / 물리 모델 관리 / 데이터 품질 관리 / 운영 관리) — [logical-model-m6.md](completed/logical-model-m6.md).
- 설계 결정 : D-07 프로젝트에 논리 모델 여러 개, 주제 영역·서브타입은 후속.
- 검증 결과 : H2/PostgreSQL 테스트 + Chrome 브라우저 검증(ADMIN).

### [M-7] 논리 · 물리 모델 매핑 — 1차 · 2차 · 3차

- 상태: 완료 (완료 조건 충족)
- 구현 내용
  - 1차 : 엔터티 ↔ 테이블, 속성 ↔ 컬럼 1:1 매핑(`TB_ENTT_TBL_MPNG`·`TB_ATRB_COL_MPNG`), `/api/modelMapping/**`, 현황·후보 조회, 매핑된 원본 삭제 차단 — [logical-physical-mapping-m7.md](completed/logical-physical-mapping-m7.md).
  - 2차 : 매핑 관리 화면(`/modelMapping`), 정합성 비교(매핑·표준 용어·명칭·타입·길이·필수·식별자, 조회 시 계산), 예제 전체 매핑 — [model-mapping-quality-m7.md](completed/model-mapping-quality-m7.md).
  - 3차 : 표준 사전 기반 물리명 추천(컬럼명·테이블명) API·화면 — [physical-name-recommendation-m7.md](completed/physical-name-recommendation-m7.md).
- 설계 결정 : D-08 논리 → 물리 일괄 생성은 초기 제외(연결·추천만).
- 검증 결과 : H2/PostgreSQL 테스트 + 헤드리스 Chrome (ADMIN·MANAGER·DBA·USER·VIEWER·미승인).

### [M-8] ERD 편집 (내부 모델) — 1차 · 2차 · 3차

- 상태: 완료 (2026-11-12)
- 구현 내용 : 1차 편집 기반 + 논리 엔터티·속성, 2차 논리 관계·식별자, 3차 물리 ERD 의 테이블·컬럼·FK·인덱스·제약 편집 (기존 API 재사용, DB 변경 없음) — [erd-editing-m8.md](completed/erd-editing-m8.md), [physical-erd-editing-m8.md](completed/physical-erd-editing-m8.md).
- **물리 모델 정합성 안정화** : 승인된 테이블 수정의 서버 차단, FK 참조 키·타입 검사 — [model-integrity-hardening.md](completed/model-integrity-hardening.md).
- 설계 결정 : 연결은 폼을 열 뿐이고 저장 성공 후 선이 생긴다. 관계와 식별자는 서로 자동 변환하지 않는다. DDL 승인 상태 정책(2026-11-13 결정) — 기본은 설계용 + 안내, 통합 DDL Export 에 승인 기준 모드.
- 검증 결과 : H2/PostgreSQL 테스트 + 헤드리스 Chrome. 레이아웃 확장·동시 편집 정책(D-09)은 M-8 에서 떼어 pending-work.md 로.

### [M-9] 모델 버전 · 변경 이력 — 1~3차

- 상태: 완료 (2026-11-12, 1~3차 + 최종 검증)
- 구현 내용 : 공통 변경 이력 `TB_MDL_CHG_HSTRY`(변경 전후 JSON, 변경 그룹, Hibernate 이벤트 → 커밋 직전 같은 트랜잭션, 화이트리스트 필드만), 물리·논리·매핑 이력과 조회·비교 화면(`/modelHistory`), 모델 버전 `TB_MDL_VER`(사용자가 명시적으로 만드는 프로젝트 전체 JSON 스냅샷, 수정·삭제 없음)과 비교 화면(`/modelVersion`) — [model-change-history-m9.md](completed/model-change-history-m9.md).
- 설계 결정 : D-11 공통 이력 테이블 + 전후 JSON (이벤트 소싱·엔티티별 이력 테이블 채택 안 함). 모델 엔티티에 영속 속성을 더하면 recorder 필드 목록에도 넣어야 한다(`ModelSnapshotFieldCoverageTest`). 모델 테이블에 JPQL/네이티브 일괄 쓰기를 더하지 않는다. 사용자 버전 만들기는 프로젝트 행을 배타로 잡은 뒤 READ COMMITTED (M-4e T-09 결정).

### [M-10 ③] 모델 버전 비교 결과의 ERD 시각화

- 상태: ③ 만 완료 (2026-11-12). ①②는 pending-work.md [M-10]
- 구현 내용 : `GET /api/modelVersion/compare/erd`, 변경 전후 ERD(추가·삭제·수정 테이블·컬럼·관계, 논리 엔터티·속성·관계), 변경 목록 ↔ ERD 이동, 읽기 전용 — [model-version-erd-m10.md](completed/model-version-erd-m10.md).

### [M-11] 통합 DDL Export

- 상태: 일부 완료 (2026-11-13) — 남은 범위는 pending-work.md [M-11]
- 구현 내용 : 프로젝트·스키마 단위 한 스크립트(SCHEMA → TABLE → INDEX → FK → VIEW, 순환 FK 는 ALTER 분리), DBMS 선택, 설계용(기본)·승인 기준 모드, 범위 밖 참조·이름 충돌 경고, 미리보기·복사·`.sql` 다운로드 — [integrated-ddl-export-m11.md](completed/integrated-ddl-export-m11.md). 테이블 1개 DDL 조회(PostgreSQL/Oracle/MySQL/MariaDB)는 기존 기능, 출력은 골든 파일로 고정.
- 검증 결과 : PostgreSQL 18 실제 실행 검증. Oracle·MySQL·MariaDB 는 생성 결과만 테스트.

### [M-14] 인덱스 관리

- 상태: 완료 (2026-11-13)
- 구현 내용 : 1차(저장 기준 통합·수정 API·변경 요청·관리 화면·유형 DDL), 안정화, 2차(UNIQUE 우선 정책, PostgreSQL 표현식·부분·정렬·NULLS·INCLUDE), 브라우저 E2E, UI/UX 통일 — [index-management-m14.md](completed/index-management-m14.md).
- 설계 결정 : D-19 `TB_TBL_IDX` 단일 기준(`IDX_YN` 은 출처 COLUMN 인덱스로 동기화) + 2차 방안 C(UNIQUE 우선), D-20 미승인은 직접 수정 · 승인된 인덱스는 변경 요청, D-21 고급 속성은 PostgreSQL 만(다른 DBMS 지원 불가 명시).

### [M-15] VIEW 관리 — 1차

- 상태: 완료 (2026-11-13, 안정화·최종 검증 포함)
- 구현 내용 : 독립 VIEW 모델(`TB_VIEW`·출력 컬럼·참조 객체·변경 요청), TABLE/VIEW 공통 이름 공간, 정의 SQL 원문 저장·정적 검사, 참조·순환·삭제 제한, 승인·변경 요청(정의 전체 원자적 반영), 이력·모델 버전, CREATE VIEW DDL·내보내기, VIEW 관리 화면, 물리 ERD 조회·이동 — [view-management-m15.md](completed/view-management-m15.md).
- 설계 결정 : D-22 독립 모델, D-23 정의 SQL, D-24 참조 객체(지시서 번호 기준), D-25 이름 공간(→ 스키마 단위로 변경), D-26 승인 대기 이름은 선점하지 않음, D-27 SQL 파서 미도입.
- 검증 결과 : H2·PostgreSQL 동시성, USER·VIEWER 권한, 브라우저.

### [기존 기능] 모델 공통

- 테이블 관리(한글명 → 승인 단어 + 접두어로 테이블명), 컬럼 관리(표준 용어 기반, 도메인으로 타입, 일괄 편집, 공통 컬럼 세트), 엑셀 업·다운로드는 보존 대상 기존 기능.
- **FK 삭제·수정 규칙과 IDENTITY** (2026-11-14, 예전 V7) : `TB_TBL_REL.DEL_RULE_CD`·`MDFCN_RULE_CD`, `TB_COL.AUTO_CRT_MTHD_CD` 저장·DDL·비교·가져오기·이력 — [lexorma-example-sync.md](reference/lexorma-example-sync.md).

### [COL-TERM] 기존 컬럼의 표준 용어 재연결 (DEC-01)

- 상태: 완료 (2026-11-15). 범위는 **표준 용어가 연결된 컬럼 하나의 재연결**만이다 — 처음 연결(용어 없는 컬럼), 연결 해제, 일괄 재연결, AI 추천은 제외 (필요 여부는 pending-work DEC-26).
- 구현 내용 : `PUT /api/saveColumn/{id}/term` (`ColumnTermService.relink`, 요청 `termId` + 화면이 읽은 `baseTermId`). 컬럼 관리 상세의 "표준 용어 변경" 버튼(승인 전 컬럼, `physical-model-edit` USER, 용어가 연결된 컬럼만) → 용어 검색(용어명·영문 약어) → 현재·새 연결 비교(용어·약어·도메인·권장 타입), 그대로 유지되는 속성 목록, 영문 약어≠컬럼명·권장 타입≠컬럼 타입·미승인 용어 경고 → 명시적 확정. 취소·같은 용어 선택은 아무것도 바꾸지 않는다(같은 용어는 서버도 `changed=false`, 이력 없음). 상세 화면에 지금 표준 용어를 표시.
- 설계 결정 : 연결만 바꾸고 컬럼명·타입·길이·정밀도·NULL·기본값·PK/FK·인덱스·제약·설명·업무 설명은 자동으로 바꾸지 않는다 (`@DynamicUpdate` 라 UPDATE 에 `STD_TRM_SN` 과 감사 칸만). 다른 점은 막지 않고 경고 + M-5 품질 진단에 맡긴다. 권한·승인은 기존 컬럼 수정과 같다(USER, 승인된 컬럼 거부 — 컬럼에는 변경 요청 절차가 없다). 새 용어는 같은 프로젝트의 용어, 승인 전 용어도 컬럼 등록처럼 허용(화면 경고). 동시성은 컬럼 행 잠금 + 다시 읽은 연결 ↔ `baseTermId` 비교 → 409 `COLUMN_TERM_CONFLICT`. DDL 은 표준 용어를 쓰지 않아 바뀌지 않고 실행도 하지 않는다. 이력은 모델 변경 이력(출처 USER, `standardTermsName` 전후)이며, 그 컬럼을 근거로 만든 AI 설명 제안은 `TERM_LINK_CHANGED` 로 재검토된다. 스키마·마이그레이션 변경 없음.
- 검증 결과 : `ColumnTermRelinkIntegrationTest` 5건 (속성 유지·UPDATE 칸·DDL 동일·이력, 같은 용어·409, 승인 컬럼·용어 없는 컬럼·다른 프로젝트·없는 대상·필수값·VIEWER 거부, 동시 재연결 하나만 반영, **실제 재연결**로 AI 제안 `TERM_LINK_CHANGED` 미리 보기·승인 시 REVIEW_REQUIRED·업무 설명 유지, 같은 용어 재선택·다른 컬럼 제안은 영향 없음). H2·PostgreSQL 전체 실행(9장). 격리 인스턴스(H2 파일 DB, 가짜 Ollama) 브라우저 : 재연결 후 컬럼 속성 9개 그대로, 영향 경고, 취소 무변경, 다른 세션이 먼저 바꾼 뒤 확정 → 충돌 문구·연결 유지, 실제 재연결 뒤 AI 제안 화면에 "표준 용어 연결 변경" 전후 표시·승인 시 재검토 필요로 반영 안 됨, 한국어·영어, 라이트·다크, 390·606px(가로 넘침 없음·버튼 표시, 수치 측정). VIEWER 거부는 통합 테스트(`AccessDeniedException`)로만 확인 — 브라우저에서 VIEWER 계정으로는 확인하지 않았다.

## 5. 원본 DB 연동 · 데이터 사전

### [AI-0] 공통 기반 결정

- 상태: 완료 (2026-11-12)
- 설계 결정 : U-01 새 기능은 `lexorma-data` 기능 패키지(도메인 무관 부품은 `lexorma-core`), U-02 비밀값 = 환경변수 키 + AES-256-GCM(`SecretCipher`, AAD 결합), U-03 원본 카탈로그를 물리 모델과 분리 + 명시적 매핑, U-04 전역 Provider = ADMIN · 프로젝트 연결 = 프로젝트 관리자 또는 ADMIN, U-07 MCP 는 필수가 아닌 후속 선택 기능(지금 만들지 않음). 원칙·불변 조건은 [ai-metadata-roadmap.md 2장](reference/ai-metadata-roadmap.md#2-불변-원칙).

### [AI-1a · AI-1b · AI-1c] 공통 기반 · 원본 연결 · 원본 카탈로그 · 수집 실행

- 상태: 완료 (AI-1a·1b 2026-11-12, AI-1c 2026-11-13). AI-1d 는 아래 참고
- 구현 내용
  - AI-1a : 요청 로그 본문 보호(`LogBodyPolicy`), `SecretCipher`, `ExecutionContext`·`ExecutionContextVerifier`, `ProjectAuthority`.
  - AI-1b : 원본 연결 `TB_SRC_CNTN`(JDBC URL·드라이버를 받지 않고 부분값으로 조립, 비밀번호 암호화), 원본 카탈로그 `TB_SRC_SCHM/TBL/COL/CNSTRT/IDX/REL`(물리 모델과 FK 없음), 식별자 정규화(U-24), 연결 삭제 시 카탈로그 정리(U-25), 내부 도구 계약(`SourceToolExecutor` 만 연결) — [source-catalog-ai1b.md](completed/source-catalog-ai1b.md), [source-tool-architecture.md](reference/source-tool-architecture.md).
  - AI-1c : 수집 실행·대상, 테이블·스키마 단위 Copy-on-Write 버전, 객체별 변경 이력, 반영 순서(`RFLT_ORD`), 부분 실패 시 전체 미반영(U-26), 보관 방식(U-06, 보관 기간은 미정) — [source-catalog-versioning.md](completed/source-catalog-versioning.md).
- **AI-1d** : 원본 ↔ 물리 매핑은 M-4c·M-4e 로, 업무 설명 칸(`TASK_EXPLN` TEXT)은 AI-5 로 구현. 남은 반영 범위는 pending-work.md [AI-1d].

### [AI-2b] PostgreSQL JDBC 메타데이터 수집

- 상태: 완료 (2026-11-13, 배포 검증 포함). 다른 DBMS 는 보류 (pending-work.md [AI-2])
- 구현 내용 : 도구 7개(`PostgresSourceTools`), 읽기 전용 REPEATABLE READ 세션·제한 시간·취소, 소켓 단위 주소 확인, 연결 테스트·호출 제한, 비동기 수동 최신화·취소(`SourceRefreshService`), 표현식·부분·정렬 인덱스 모델 — [source-postgresql-collection-ai2b.md](completed/source-postgresql-collection-ai2b.md).
- 검증 결과 : 실제 PostgreSQL 통합 테스트, 실행 jar·Docker 이미지에서 연결 테스트·차단 주소·인증 실패·FULL 수집·변경 없는 재수집 확인 (2026-11-13).

### [M-4a ~ M-4g] 원본 DB 관리 · ERD · 매핑 · 비교 · 가져오기 · 재가져오기

- 상태: 완료 (M-4 의 남은 범위는 pending-work.md [M-4])
- 구현 내용
  - **M-4a** 원본 DB 관리 화면 : 연결·수집·카탈로그·변경 이력 — [source-management-ui-m4a.md](completed/source-management-ui-m4a.md).
  - **M-4b** 원본 DB ERD : 수집 시점별 읽기 전용 ERD, 실제 FK 만 — [source-erd-m4b.md](completed/source-erd-m4b.md).
  - **M-4c** 원본 스키마·TABLE 매핑 (2026-11-13) : 상태는 저장하지 않고 조회 시 계산, 기준 연결 정책(D-17), 이름이 같아도 자동 연결하지 않음(D-06). 후속 12.1 연결 삭제 ↔ 매핑 확정 경합 **해결 (2026-11-14)** — 원본 연결 행 공유 잠금 (`SourceCatalogReader.lockConnection`, 잠금 순서 project → connection → base row). 검증 : H2 611/0/20, PostgreSQL 611/0/14 — [source-mapping-m4c.md](completed/source-mapping-m4c.md), [m4-foundation.md](reference/m4-foundation.md).
  - **M-4g** 매핑 예외 TABLE : 비교 목록에서 제외하고 `excludedTables` 로만 셈 (source-mapping-m4c.md 13장).
  - **M-4d** 원본 ↔ 모델 비교 (2026-11-14, 읽기 전용, `/api/sourceCompare`, PostgreSQL 규칙만). 검증 : H2 625/0/21, PostgreSQL 625/0/15, ko/en·라이트/다크·1440/375px, 3,000 TABLE 약 0.35초 — [source-compare-m4d.md](completed/source-compare-m4d.md).
  - **M-4e** 선택 가져오기 (2026-11-14) : 대상 → 계획(`planToken`) → 잠금 아래 재계획·확정, 미승인 객체 생성, 승인된 객체는 건드리지 않음, 원본 COMMENT 는 `SRC_ANTN_CN` 에만, IMPORT 이력, 확정 직전 프로젝트 전체 모델 버전(`IMPORT-<요청>`), 프로젝트 이후 잠금은 NOWAIT(409). T-09 사용자 버전 생성 시점 문제 해결(READ COMMITTED) — [source-import-m4e.md](completed/source-import-m4e.md).
    - **원본 속성 처리 (코드·테스트로 확인, 2026-11-15)** : 새 FK 는 원본 ON DELETE·ON UPDATE 규칙(NO ACTION·RESTRICT·CASCADE·SET NULL·SET DEFAULT)을 그대로 가져오고 모르는 규칙 표기만 막는다. 새 컬럼의 IDENTITY 는 모델 IDENTITY(ALWAYS·BY_DEFAULT)로 가져온다 (예전 V7 부터, 기본값으로 넣지 않음). 기존 컬럼의 IDENTITY 변경은 가져오지 않는다(`NO_FIELD`). 생성 컬럼(`GENERATED ALWAYS AS (…) STORED`)과 DDL 이 재현하지 못하는 기본값을 가진 컬럼은 만들지 않는다(`UNSUPPORTED_ATTRIBUTE`). 규칙·IDENTITY 판정은 PostgreSQL 원본 기준이고, DDL 생성은 DBMS 가 지원하지 않는 규칙을 빼고 경고한다(Oracle 은 ON DELETE CASCADE·SET NULL 만, MySQL·MariaDB 는 SET DEFAULT 없음) — `SourceImportService`, `SourceImportIntegrationTest`·`SourceReimportIntegrationTest`, [lexorma-example-sync.md](reference/lexorma-example-sync.md).
  - **M-4f** 선택적 재가져오기 (2026-11-14) : 마지막 반영 값 기준 3자 판정(새 저장 구조 없음), 모델 수정·양쪽 변경 보호. 검증 : H2·PostgreSQL 645 통과, 3,000 TABLE 확정 0.77초 — [source-reimport-m4f.md](completed/source-reimport-m4f.md).

### [SEC-1] 외부 연결 보안 정책 · 관리자 허용 목록

- 상태: 완료 (2026-11-14)
- 구현 내용 : ADMIN 허용 규칙(`TB_EXTRL_CNTN_RULE`, JDBC·AI_HTTP, 기본 차단, 한 규칙이 유형·포트·호스트·대역을 모두 만족해야 허용), 절대 차단 대역, DNS 재바인딩 방어(검증 주소로 소켓 고정), 연결·정책 감사(`TB_EXTRL_CNTN_HSTRY`, 감사 실패 = 연결 안 함), AI HTTP 어댑터(`ExternalHttpClients`), 환경 변수는 원본 JDBC 운영자 상한으로만 — [external-connection-security-sec1.md](reference/external-connection-security-sec1.md).
- 설계 결정 : U-27~U-32 의 쟁점(정책 범위·우선순위·빈 목록·DNS·허용 대역·적용 시점)은 이 구현의 규칙으로 정해졌다 (전역 규칙, 기본 차단, 환경 설정 = 상한, 접속마다 이름풀이 결과를 규칙으로 재확인 후 고정, 루프백·사설망은 명시 규칙으로만). AI 로드맵 9장 표는 갱신하지 않았다.

### [데이터 사전] 업무 설명 직접 관리 (AI-5 후속, AI 없이 동작)

- 상태: 완료 (2026-11-15)
- 구현 내용 : 테이블·컬럼 업무 설명(`TB_TBL`/`TB_COL.TASK_EXPLN`, TEXT, 화면 이름 "업무 설명")을 AI Provider 없이 직접 작성·수정·삭제(`PUT /api/bizDescription`, USER). 행 잠금 + 화면이 읽은 값(`base`) 비교 → 다르면 409 `BIZ_DESCRIPTION_CONFLICT`. 이력 출처 USER(직접) · AI_APPROVAL(AI 승인). DDL COMMENT(`TBL_EXPLN`/`COL_EXPLN`), 원본 COMMENT(`SRC_ANTN_CN`)와 분리.
- 설계 결정 : 업데이트 DTO 는 null 업무 설명을 "변경 없음"으로 본다(업무 설명을 모르는 화면·엑셀이 지우지 않도록). `TableInfo`/`ColumnInfo` 는 `@DynamicUpdate`. 새로 `TASK_EXPLN` 을 쓰는 경로는 잠그고 다시 읽은 뒤 비교해야 한다 — [ai-dictionary-draft-ai5.md 15·19장](completed/ai-dictionary-draft-ai5.md).

## 6. AI 기능

### [AI-3] AI Provider 관리 · Ollama 연동 · 시스템 기본 Provider

- 상태: 완료 (2026-11-14)
- 구현 내용 : 전역 Provider `TB_AI_PRVDR`(OLLAMA, ADMIN 전용 `/api/ai/providers/**`, 저장 시 접속하지 않음), 요청마다 DB 행으로 Spring AI 객체를 만듦(캐시 없음, 재시도 없음, 모델 자동 내려받기 없음), 모든 HTTP 는 SEC-1 요청 팩토리, 호출 실패 분류. 시스템 기본 Provider(`TB_AI_STNG` 한 행) — [ai-provider-ai3.md](completed/ai-provider-ai3.md).
- 설계 결정 : U-10 Spring AI 2.0.2 비-starter 모듈만(자동 구성·`spring.ai.*` 설정 없음). **스트리밍 미지원**(WebClient 는 SEC-1 을 거칠 수 없어 명시 차단, 2026-11-14 결정). 자동 선택·대체 모델 없음.

### [AI-4] Lexorma DB 기반 AI Tool Calling (AI 질문 위젯)

- 상태: 완료 (2026-11-15)
- 구현 내용 : 읽기 전용 메타데이터 Tool 10개(`FunctionToolCallback` + record 입력, 서버가 만든 `ExecutionContext`, Tool 마다 재검증·읽기 전용 트랜잭션), 2단계 질의(Tool 단계 → 구조화 단계, `StructuredAnswerParser` + JSON Schema, 재요청 1회), `EvidenceVerifier`(행 `MDFCN_DT` 버전), 유사 컬럼(`SimilarColumnService`, 규칙 점수)·변경 이력 Tool, 토큰 예산(`TokenMeter`, 프롬프트를 자르지 않음), Tool 미지원 모델의 서버 맥락 대체 흐름, 우측 하단 AI 도우미(`ai-metadata-query`, VIEWER, 기본 Provider 가 READY 일 때만) — [ai-tool-calling-ai4.md](completed/ai-tool-calling-ai4.md).
- 설계 결정 : U-12 `example` 은 보내지 않고 민감 컬럼은 표시만, 설명은 300자까지. U-13 호출·토큰 기본 한도(코드 상수 + 선택 설정). 원본 DB 도구는 AI 에 노출하지 않는다.
- 검증 결과 : 로드맵 4단계 완료 조건 점검 [12.9](completed/ai-tool-calling-ai4.md#129-로드맵-4단계-완료-조건-점검). Tool 미지원 실제 모델 검증은 하지 못함 (pending-work.md [AI-4]).

### [AI-5] AI 데이터 사전 초안 생성 및 승인 — 완료된 범위

- 상태: **부분 완료** — 여기에는 구현·검증된 범위만 둔다. 사람이 판정한 업무 정확성 평가는 pending-work.md [AI-5-EVAL]
- 구현 내용 (상세 [ai-dictionary-draft-ai5.md](completed/ai-dictionary-draft-ai5.md))
  - **초안 생성** : 서버가 `MetadataToolset.run` 으로 맥락 수집(모델의 Tool Calling 없음), 한 번 호출, JSON Schema → 대상 → `DescriptionTextCheck`(자기 평가 문구·Lexorma ID 노출 거절, 재요청 1회) → `EvidenceVerifier`, 확실성은 서버가 낮추기만 함. 프롬프트 v2(`dictionary-draft/2`, 지시문+스키마 SHA-256 기록, v1 은 기록용 보존).
  - **생성 대상 선택** : 테이블 + 설명 없는 하위 컬럼, 컬럼만, 고른 컬럼, 중복 제거, 업무 설명 있는 대상 제외(다시 만들기 선택 시 포함), 검토 대기 제안은 건너뜀. 대상 상세(스키마·논리명·물리 설명·원본 COMMENT·업무 설명·타입·PK/FK·표준 용어·도메인), 생성 미리 보기(`POST /jobs/preview`), 대량 생성(서비스 전용 단일 스레드, 프로젝트당 하나, 재시작 시 INTERRUPTED).
  - **검토** : 승인 · 수정 후 승인 · 반려 · 보류 · 검토함 · 종결(CLOSED, 사유 필수, 지표 제외), 일괄 승인·반려·보류(항목마다 독립 트랜잭션). 승인은 USER + 승인 권한(DB 재확인), `TASK_EXPLN` 만 쓴다(객체 승인 상태·표준 용어·DDL 설명·원본 COMMENT 는 바꾸지 않음).
  - **승인 시 재검증** (`ProposalVersions`) : 제안 잠금 → 대상 행 잠금 → `VER_NO` 비교(409 `AI_PROPOSAL_CONFLICT`) → 생성 당시 업무 설명·근거 객체 버전·변경 이력 필드·매핑 원본 값 비교 → 달라졌으면 반영하지 않고 REVIEW_REQUIRED. 실제 원본 최신화 기반 재검증 확인(14장).
  - **재검토 해결** : 근거 재검증(`revalidate` — 남은 변경 없으면 검토 중, 내용 변경은 사유와 함께 확인 시 기준 이동, 대상·근거 삭제·업무 설명 변경은 막음, 자동 승인 없음), 새 초안 생성(앞 제안 SUPERSEDED, 서로 이력 연결, 실패 시 앞 제안 유지), 종결.
  - **업무 설명 보호** (2026-11-15, 20장) : 기준 업무 설명은 **AI 호출 전** 읽은 값(생성 중 수정되면 처음부터 REVIEW_REQUIRED — 이전에는 AI 응답 뒤에 읽어 생성 중 수정을 덮어쓸 수 있었다). 상세의 "승인 및 교체" 안내·생성 당시/현재/AI 제안 비교·충돌 시 승인 차단, 목록의 "기존 설명 교체" 표시, 일괄 승인의 교체 건수 안내. API 추가 필드 `replacesBizDescription`·`baseBizDescription`.
  - **재검토 사유** : `TERM_LINK_CHANGED`(표준 용어 연결 변경 — 변경 전후 용어 이름·시점, 다른 필드는 `MODIFIED` 로 분리), 변경을 알 수 없으면 기존 `MODIFIED_UNKNOWN`.
  - **품질 평가 기능** (V2) : 평가 대상·사람이 확인한 실제 의미·사람의 판정 저장, 판정 기반 지표. AI 판정·AI 확실성을 정확성으로 쓰지 않고, 실제 의미는 AI 에 보내지 않는다.
  - **화면** : 사이드바 `AI 관리`(AI Provider 설정 `/aiProvider`, AI 설명 제안 `/aiDictionary`, AI 품질 평가 `/aiEvaluation`), `useAiStatus` 공유 상태, `requires: 'ai'` 메뉴는 NOT_CONFIGURED 일 때만 숨김. 안내 공지의 AI 절(V3).
- 설계 결정 : U-05 확정 업무 정보 재사용은 현재 프로젝트 안, U-14 프롬프트 버전은 리소스 + 코드 상수, U-15 서버 안 스레드 1·대기 10. 표준 용어·도메인 추천은 참고만(승인해도 연결하지 않음).
- 검증 결과 (2026-11-15) : `AiDictionaryApprovalSafetyIntegrationTest` 9건 포함 H2 779건(실패 0, 건너뜀 33)·PostgreSQL 779건(실패 0, 건너뜀 24, 격리 컨테이너). 격리 인스턴스 + 가짜 Ollama 로 브라우저(교체 승인·오래된 제안 차단·일괄 승인·다크 모드) 확인. **영어 화면·390px 모바일은 브라우저 미확인**, 표준 용어 연결 변경 화면은 응답 주입으로 렌더링만 확인 (pending-work.md).

## 7. 보안 및 권한

- 역할 계층 `RoleHierarchyConfig` : `ADMIN > MANAGER > DBA > USER > VIEWER > NOT_APPROVE`. `/api` 핸들러는 `@PreAuthorize` 가 없으면 로그인한 모든 사용자에게 열린다.
- 승인 동작은 `AuthUtil.isApprovalAvailable()`(ADMIN·MANAGER 역할 또는 현재 프로젝트 관리자)을 추가로 요구. 역할은 계정 전역이며 프로젝트별 역할은 없다.
- `MenuAuthorization.MENU_ROLES` 가 메뉴·액션 버튼 최소 역할의 단일 맵 — 핸들러 역할을 바꾸면 함께 바꾼다 (`UiActionAuthorizationIntegrationTest`).
- 원본·AI API 는 요청 로그에 `[PROTECTED]` 로만 남고(`/api/source/**`, `/api/ai/**` 등), 그 밖은 `SensitiveDataMasker`.
- 외부 연결은 모두 SEC-1 을 거친다. AI Tool 은 프로젝트 범위를 검증된 실행 컨텍스트에서만 가져온다.

## 8. 주요 기술적 결정

| 영역 | 결정 | 근거 기록 |
|---|---|---|
| 모델 계층 | 물리 모델 = 기존 테이블·컬럼 확장, 원본 카탈로그는 별도 저장(서로 자동으로 덮어쓰지 않음), 원본 DB 에 DDL·DML 을 실행하지 않음 | [data-modeling-roadmap.md 4장](reference/data-modeling-roadmap.md#4-개념-모델과-핵심-결정) |
| 관계 유형 | 실제 FK / 모델 관계 / 추정 관계 / 승인 관계를 구분, 추정 관계는 실제 FK 로 취급하지 않음 | 같은 문서 4.2 |
| 독립성 | 원본 DB·Ollama 가 없어도 메타데이터·모델·ERD·표준화·데이터 사전·업무 설명은 관리 가능 | 같은 문서 4.3, AI 로드맵 2장 |
| 제외 범위 | 코드 규칙·메서드명 생성, 코드 생성 템플릿, 설계서 생성, 원본 DB 에 대한 SQL 실행 — 다시 넣지 않음 | 같은 문서 8장 |
| 이력 | 공통 변경 이력 + 전후 JSON, 원본 변경 이력은 별도 테이블 | D-11, AI-1c |
| 동시성 | 모델 쓰기는 `ProjectWriteGuard`(커밋 시 프로젝트 공유 잠금), 원본 쓰기의 잠금 순서 project → connection → base row, 업무 설명 쓰기는 행 잠금 + 기준값 비교 | CLAUDE.md, M-4c, AI-5 15장 |
| AI 경계 | AI 결과는 승인 전 모델·데이터 사전에 확정되지 않음, 근거 버전이 바뀌면 재검토, 원본 DB 도구는 AI 에 노출하지 않음 | AI 로드맵 2장, AI-4, AI-5 |
| 마이그레이션 | 적용된 파일은 고치지 않고 다음 버전(V4)으로 더한다, 두 벤더 같은 내용, 증분 업그레이드 검증 | 2장 [기반] 데이터베이스 · 마이그레이션 체계 |

## 9. 검증 및 테스트 현황

- **최근 전체 실행 (2026-11-15, COL-TERM)** : H2 791건 실패 0 · 건너뜀 34, PostgreSQL 791건 실패 0 · 건너뜀 25 (격리된 PostgreSQL 18.6 컨테이너, `LEXORMA_TEST_POSTGRES_URL`).
- **단계별 기록된 검증** : 각 작업 항목의 "검증 결과"와 연결된 기록. 대부분의 단계는 H2·PostgreSQL 통합 테스트와 헤드리스 Chrome 화면 검증을 기록하고 있다. 기록에 미검증으로 남은 항목(대규모 성능, Oracle·MySQL·MariaDB 실제 실행, 일부 브라우저·역할·언어 조합)은 pending-work.md 에서 관리한다.
- **검증 환경** : 단위 테스트(Mockito, 모듈별), 통합 테스트(`lexorma-app`, `@SpringBootTest` + Flyway H2), `SmokeTest`(실제 서버 + JDK `HttpClient`), PostgreSQL 은 빈 DB 를 가리켜 같은 테스트를 실행. 프론트엔드 검사는 `npm run typecheck` 뿐이다.

## 10. 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-11-15 | 문서 신설. 두 로드맵의 상태 표·단계별 "현재 구현 상태"와 각 기능 기록을 기준으로 완료 범위를 통합했다. AI-5 업무 설명 승인 보호·표준 용어 연결 변경 사유(같은 날) 반영 |
| 2026-11-15 | 문서 구조 정리 : 상세 기록을 `completed/`·`reference/` 로 옮기고 링크를 새 경로로 바꿨다 (기능 완료 항목 변동 없음). 가져오기의 IDENTITY·FK 규칙·생성 컬럼 정책을 코드·테스트로 확인해 M-4 항목에 명시 |
| 2026-11-15 | [UI-DIALOG] 공통 대화상자 위치·끌기 버그 수정 추가, 코드·테스트·설정 주석의 문서 경로 정리 |
| 2026-11-15 | [AI-5-PAGE] AI 설명 제안 대상 선택의 서버 쪽 나누기 추가 |
| 2026-11-15 | [AI-5-REASON] AI 재검토 사유의 사용자 문구 추가 |
| 2026-11-15 | [COL-TERM] 기존 컬럼의 표준 용어 재연결 추가 (DEC-01) |
