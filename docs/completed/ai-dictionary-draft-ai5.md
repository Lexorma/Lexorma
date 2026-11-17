# AI-5 데이터 사전 초안 생성 및 승인

> **8차 (2026-11-17)** : 코드는 `lexorma-ai` 모듈로 옮겼다 — `lexorma-data` `ai/dictionary/…` → `com.lexorma.ai.dictionary.…`. 승인의 최종 업무 설명 쓰기는 data 의 `BizDescriptionService.writeAiApproval` 로 (같은 트랜잭션·잠금 순서 그대로), 프로젝트 삭제 정리는 `ai/hook/AiProjectDataCleanup`. API·DB·평가 정책은 그대로 ([ai-module-extraction.md](ai-module-extraction.md)).

> **상태 : 기능 구현 완료 / 품질 평가 기준 데이터·업무 정확도 검증 미완료 (2026-11-15) — AI-5 일부 완료.** 로드맵 [5단계](../reference/ai-metadata-roadmap.md#5단계-ai-데이터-사전-초안-생성-및-승인).
> 앞 단계 : [AI-4 Tool Calling](ai-tool-calling-ai4.md) (Tool·구조화 응답·근거 검증·토큰 예산), [AI-3 Provider](ai-provider-ai3.md), [SEC-1](../reference/external-connection-security-sec1.md).
> 스키마 : 처음 V10 으로 만들었고, 2026-11-15 Flyway 초기화로 `V0__init.sql`·`V1__seed_data.sql` 의 '예전 V10' 구간에 합쳤다 (PostgreSQL·H2 같은 내용).

## 1. 사용자 결정 (2026-11-15, 구현 전 보고 후)

| 질문 | 결정 |
|---|---|
| 확정 업무 설명을 어디에 둘까 (로드맵 AI-1d 미구현) | **새 업무 설명 칸** `TB_TBL.TASK_EXPLN`·`TB_COL.TASK_EXPLN` (TEXT, 엔터티 `bizDescription`). 기존 `TBL_EXPLN`·`COL_EXPLN`(DDL COMMENT·논리명)·`SRC_ANTN_CN`(원본 COMMENT)은 그대로 |
| 승인된 객체(isApproval)의 제안 승인 | **업무 설명만 허용** : 제안 승인은 객체 승인 상태와 무관하게 업무 설명 칸만 쓴다. 다른 속성의 승인 잠금은 그대로 |
| 진입 화면 | **데이터 사전의 'AI 설명 제안'** (기존 표준 도메인·단어·용어 화면 그대로). 테이블·컬럼 상세와 ERD 상세 패널에는 업무 설명·제안 상태·제안으로 이동 |
| 승인 시 버전 기준 | **물리 모델 + 원본 매핑** : 대상·실제로 쓴 근거의 물리 모델 버전(수정 일시 + 모델 변경 이력 ID)과, 원본 테이블에 매핑된 대상은 원본 값(반영 순서·근거 컬럼의 타입·NULL·COMMENT, 테이블 COMMENT)을 비교. 근거와 무관한 변경은 승인을 막지 않는다 |

- 데이터 사전의 "탭" 은 지금 코드에서 메뉴 그룹 '데이터 사전' 안의 화면들이다 (`/standardDomain`·`/standardWord`·`/standardTerm`, 예전 `/dataDictionary?tab=…` 는 이동). 그래서 'AI 설명 제안' 은 같은 그룹의 네 번째 화면 `/aiDictionary` 이고, `/dataDictionary?tab=aiDescriptions&…` 도 그리로 옮긴다. 기능은 `features/dictionary/ai/` 의 독립 컴포넌트·API 모듈이다 (`DataDictionaryPage` 에 로직을 넣지 않았다).
- 공통표준(공공데이터포털 템플릿)과 맞추려고 새 표준 단어의 약어를 그 템플릿에 맞췄다 : 업무 `TASK`, 처리자 `PRCR`, 검토 `RVW`, 검토자 `RVWR`, 기준 `CRTR`, 상세 `DTL`, 처리 `PRCS`, 완료 `CMPTN`, 실패 `FAIL`, 확정 `CFMTN`. 템플릿에 없는 단어 : 제안 `SGSTN`(템플릿의 `PRPSL` 은 '발의'), 확실성 `CRTNTY`, 근거 `EVDNC`, 프롬프트 `PRMPT`, 건너뜀 `SKIP`, 재검토 `RRVW`, 필요 `NEED`.

## 2. 데이터 모델 (예전 V10, 지금은 V0·V1)

| 데이터 | 저장 | AI 생성 시 | 승인 시 |
|---|---|---|---|
| 원본 COMMENT | `SRC_ANTN_CN` (가져오기만 쓴다) | 바꾸지 않음 | 바꾸지 않음 |
| DDL 설명·논리명 | `TBL_EXPLN`·`TBL_KORN_NM`·`COL_EXPLN` | 바꾸지 않음 | 바꾸지 않음 |
| **업무 설명** | `TASK_EXPLN` (새, 사람이 직접 쓰거나 제안 승인) | 바꾸지 않음 | **이것만 쓴다** |
| AI 제안 | `TB_AI_EXPLN_SGSTN` | 저장 | 상태·확정 문구 |
| 검토 이력 | `TB_AI_EXPLN_SGSTN_HSTRY` (추가만) | GENERATE | APPROVE·APPROVE_EDITED 등 |
| 대량 생성 실행 | `TB_AI_EXPLN_CRT_EXCN` | 진행 상태 | — |
| 표준 용어·도메인 | 기존 | 추천만 (참고) | **바꾸지 않음** (연결은 기존 컬럼 화면에서) |

- `TB_AI_EXPLN_SGSTN` (AI 설명 제안) : 대상(프로젝트·유형 TABLE/COLUMN·테이블 ID·컬럼 ID·생성 시점 이름), 차수(같은 대상의 몇 번째), 제안 설명, 확실성(`VERIFIED`·`INFERRED`·`UNKNOWN`), 검토 상태, 상세 JSON(이름 해석·표준 용어/도메인 추천과 그 검증 결과·연관 객체·불확실한 부분·확인 질문·AI 가 주장한 확실성·사용량), 근거 JSON(근거별 종류·ID·이름·검증 상태·출처), **기준 버전 JSON**, 생성 정보(Provider·모델·프롬프트 버전·프롬프트 해시·생성 방법·실행 ID), 검토 정보(검토자·일시·확정 문구·사유), 재검토 필요 여부(승인 뒤 근거 변경), 낙관적 잠금 `VER_NO`.
- 출처·확실성·검토 상태를 서로 다른 축으로 둔다 : 출처는 이 테이블의 행이면 AI, 업무 설명 칸은 사람 또는 승인된 AI. 기존 `isApproval` 은 그대로이고 관계없다.
- 대상 테이블·컬럼이 지워져도 제안·이력은 남는다 (모델 테이블에 FK 없음 — 모델 변경 이력과 같은 방식). 프로젝트 삭제 때 `ProjectDataCleanup` 이 함께 지운다.
- 업무 설명 칸은 모델 변경 이력의 기록 필드(`bizDescription`)에 넣었다 (변경 이력·모델 버전 스냅숏에 남는다). 화면 : 테이블 수정 화면의 '업무 설명', 컬럼 일괄 편집 행의 `bizDescription`(보내지 않으면 그대로).
- 예제(LEXORMA) : 새 단어 17개·용어 30개, 테이블 3개·컬럼, 테이블·컬럼의 업무 설명 컬럼, 논리 엔터티·속성·매핑, 예제 인덱스 4개 (`V0SeedMigrationTest`·`SmokeTest` 의 표준화 품질 0건 유지).

## 3. 생성 흐름

```text
POST /api/ai/dictionary/proposals/generate (USER)   ·   대량 : 실행기 스레드에서 같은 메서드 (ASYNC 컨텍스트)
  AiDescriptionDraftService.generate
    ├ ExecutionContextVerifier.verify(USER) · 대상은 실행 컨텍스트의 프로젝트에서만 (다른 프로젝트 ID = 없음)
    ├ 검토 대기 제안이 있으면 끝 (SKIPPED) — 재생성이면 앞 제안 SUPERSEDED
    ├ 기본 Provider 확인 (미지정·비활성이면 메타데이터를 모으지 않고 오류)
    ├ 서버가 맥락을 모은다 : MetadataToolset.run (AI-4 Tool 경계 그대로 — 재검증·읽기 전용·크기 제한·근거 스냅숏, 최대 8번)
    │    TABLE : getTableDetail · getTableColumns · getTableRelations
    │    COLUMN : getTableColumns · getTableRelations · findSimilarColumns · searchStandardWords(이름 단어별, 최대 3) ·
    │             searchStandardTerms(컬럼 설명) · searchStandardDomains(마지막 단어의 도메인 분류)
    ├ 요청 : [1] 물리 메타데이터 [2] 구조·명명 규칙 [3] 확정된 업무 정보 로 나눈 맥락 + 버전 붙은 지시문 + 형식(스키마). Tool 정의 없음
    ├ callDefaultWith (SEC-1 연결 하나, 토큰 예산 TokenMeter) → JsonSchemaOutput 검증 + 의미 검증(대상 일치, 테이블 대상은 추천 없음) → 실패면 사유 붙여 1회 재요청
    ├ EvidenceVerifier : 근거 · 표준 용어/도메인 추천 · 연관 객체 (서버가 조회하지 않은 것은 UNVERIFIABLE)
    ├ 확실성 낮추기 : 확인된 근거가 없으면 UNKNOWN, 일부만 확인되면 VERIFIED → INFERRED (높이지 않는다)
    ├ 기준 버전 (ProposalVersions.capture, 맥락 조회 시점 스냅숏 기준). 그 사이 대상이 바뀌었으면 처음부터 REVIEW_REQUIRED
    └ ProposalWriter.create (짧은 쓰기 트랜잭션, 대상 행 잠금으로 같은 대상 동시 생성 직렬화, 이력 GENERATE)
```

- **Tool Calling 대신 서버가 단계를 정한다** : 로드맵 4단계의 하이브리드 원칙(정형 작업은 서버가 단계·입력을 통제)대로다. 그래서 Tool Calling 을 지원하지 않는 모델도 같은 흐름이고(생성 방법 `SERVER_CONTEXT`), 프로젝트 전체를 보내지 않는다. 메타데이터 조회는 AI-4 의 Tool 10개를 그대로 쓴다 (중복 조회 코드 없음). Tool 결과에 업무 설명(`businessDescription`)·원본 COMMENT(`sourceComment`)를 더했다.
- **구조화 응답** : `ai/description-draft.schema.json` (draft 2020-12, 정의하지 않은 필드 거절 — 상태·검증 필드를 AI 가 써 넣을 수 없다) : targetType·targetId·description(2000자)·certainty·nameInterpretation·termRecommendation/domainRecommendation(id·name·reason)·related(10)·evidence(30)·unknowns(10)·questions(10). AI-4 의 파서를 `JsonSchemaOutput<T>` 로 일반화해 같이 쓴다.
- **추측 방지** : 지시문이 코드값 의미·부가세 포함·통화·날짜 기준·개인정보·처리 순서·실제 용도를 이름만으로 정하지 말고 `unknowns`·`questions` 로 남기라고 한다. 근거가 없으면 확실성 UNKNOWN. AI 의 확신을 정확도로 표시하지 않는다 — 화면은 근거 검증 결과와 "확인됨 ≠ 문장 전체가 맞음" 안내를 보인다.
- **프롬프트·모델 버전 (U-14)** : 지시문은 리소스 파일(`ai/prompts/description-draft-v2.txt`)과 코드 상수 `PROMPT_VERSION = dictionary-draft/2`(2026-11-15 v1 → v2, 17장. v1 파일은 기록으로 남긴다), 제안마다 지시문+스키마의 SHA-256(`PRMPT_HASH_VL`)·Provider ID·모델명·생성 방법·사용량을 남긴다. 지시문을 바꾸면 버전을 올린다 (과거 제안의 재현 정보는 남는다). 관리자 편집 화면은 없다.

## 4. 검토·승인

| 처리 | 가능한 상태 → 결과 | 비고 |
|---|---|---|
| 승인 | PROPOSED·REVIEWED·ON_HOLD → APPROVED | 제안 문구를 업무 설명에 |
| 수정 후 승인 | 〃 | 사용자가 고친 문구를 업무 설명에 (`APPROVE_EDITED`) |
| 반려 | PROPOSED·REVIEWED·ON_HOLD·REVIEW_REQUIRED → REJECTED | 사유 필수, 반영 안 함 |
| 보류 | PROPOSED·REVIEWED·REVIEW_REQUIRED → ON_HOLD | 사유 선택 |
| 검토함 | PROPOSED·ON_HOLD → REVIEWED | 보류 해제에도 쓴다 |
| 재검토 필요 | 승인 시·재확인 때 근거 변경 → REVIEW_REQUIRED | 그대로 승인 불가 — 반려하거나 지금 메타데이터로 다시 만든다 |
| 대체 | 재생성 → SUPERSEDED | 반려와 구분(반려율을 왜곡하지 않는다) |

- **권한** : 조회 VIEWER, 생성·재확인 USER, 검토(승인·반려·보류·검토함·일괄) = USER + 기존 승인 권한(ADMIN·MANAGER·프로젝트 관리자, `AuthUtil.isApprovalAvailable` 과 같은 기준)을 **DB 에서 다시 확인**(`ExecutionContextVerifier`). 로그인 사용자 실행만(비동기 실행으로 승인할 수 없다). 버튼 숨김에 기대지 않는다. 메뉴 ID : `ai-dictionary`(VIEWER), `ai-dictionary-generate`(USER), `ai-dictionary-review`(USER, 화면은 `approvalAvailable` 과 함께).
- **동시성** : 제안 행을 `PESSIMISTIC_WRITE` 로 잠그고 화면이 본 `VER_NO` 와 비교 → 다르면 `AI_PROPOSAL_CONFLICT`(409). 끝난 제안은 `AI_PROPOSAL_INVALID_STATE`(409, 중복 승인 차단). 승인은 대상 행(테이블·컬럼)도 잠근다 (잠금 순서 : 제안 → 대상). 상태 변경·업무 설명 반영·이력은 한 트랜잭션.
- **일괄** : 승인·반려·보류, 최대 100개, 항목마다 각자의 트랜잭션·재검증 (하나가 실패해도 계속, 권한이 없어지면 멈춘다).

## 5. 승인 시 재검증 (`ProposalVersions`)

- 기준(생성 때) : 대상과 **검증된 근거** 객체마다 수정 일시(맥락 조회 시점의 스냅숏) + 그 객체의 모델 변경 이력 마지막 ID, 그때의 업무 설명, 원본 매핑이 있으면 원본(반영 순서·테이블 COMMENT·근거 컬럼의 타입·길이·정밀도·NULL·COMMENT).
- 비교(승인·재확인 때) :
  - 대상이 없음 → `TARGET_DELETED` · 근거 객체가 없음 → `OBJECT_DELETED`
  - 업무 설명이 생성 때와 다름(다른 사용자의 수정) → `BIZ_DESCRIPTION_CHANGED` — 덮어쓰지 않는다
  - 물리 객체(테이블·컬럼·관계)의 수정 일시가 다르면 그 뒤의 변경 이력(ID 가 크거나 기준 시각 이후 — 시퀀스 블록 때문에 둘 다 본다)에서 바뀐 필드를 모은다. **업무 설명·승인 여부·정렬 순서만 바뀐 것은 영향 없음**. 그 밖이면 `MODIFIED`(필드 목록). 수정 일시가 바뀌었는데 이력이 없으면(DB 직접 수정 등) `MODIFIED_UNKNOWN`(안전한 쪽)
  - 표준 용어·단어·도메인·스키마 근거 : 변경 이력이 없어 수정 일시가 다르면 `MODIFIED_UNKNOWN`
  - 원본 : 반영 순서가 같으면 그대로. 다르면 테이블 COMMENT·근거 컬럼 값을 비교해 `SOURCE_CHANGED`(`컬럼:comment`·`컬럼:definition`·`컬럼:missing`·`tableComment`), 원본 테이블이 없으면 `SOURCE_TABLE_MISSING`
- 근거로 들지 않은 다른 컬럼·테이블의 변경, 테이블 전체·프로젝트 전체 버전은 보지 않는다 → **영향받은 제안만** 재검토 대상.
- **재확인**(`POST /proposals/recheck`, 화면 '근거 변경 확인') : 검토 대기 제안 중 바뀐 것 → REVIEW_REQUIRED, **승인된 제안 중 그 뒤 근거·원본 COMMENT 가 바뀐 것 → '재검토 필요' 표시만**(상태·업무 설명·이력 그대로, 이력에 `RECHECK_FLAGGED`). 원본 COMMENT 가 바뀐 승인 설명은 화면에서 지금 메타데이터로 다시 만들어(새 제안) 기존 설명과 비교해 유지·수정을 정한다. 승인된 설명을 자동으로 지우거나 무효화하지 않는다.
- **변경 전후 값** (2026-11-15 후속) : 변경마다 `diffs`(필드 · 생성 때 값 → 지금 값)를 함께 돌려준다 — 원본은 기준 버전과 지금 카탈로그(COMMENT, 정의 = 타입 · 길이/정밀도 · NULL), 물리 모델은 그 뒤 변경 이력의 처음 전 값·마지막 값, 업무 설명은 기준과 지금 값. 상세·승인 결과 화면이 그대로 보인다 (저장하지 않고 볼 때마다 계산).
- 한계 : DB 를 직접 고친 변경은 `MODIFIED_UNKNOWN` 으로만 잡힌다(이력이 없을 때). 같은 시각 정밀도 안의 두 변경은 이력 ID 로 구분한다. 원본 값은 매핑된 테이블만 (매핑 없는 대상은 물리 모델만).

## 6. 대량 생성 (U-15)

- 단일 서버 안의 제한된 실행기 (`AiDescriptionJobService` 전용 `ThreadPoolTaskExecutor` : 스레드 1 = AI 서버에 한 번에 한 요청, 대기 10, 가득 차면 `AI_JOB_BUSY`) + DB 실행 상태(`TB_AI_EXPLN_CRT_EXCN`). 별도 큐·Redis·외부 배치 없음. 실행기를 Bean 으로 두지 않는다 (Spring Boot 의 기본 작업 실행기를 대신하지 않도록).
- 범위 : `MISSING_TABLES`(업무 설명 없는 테이블), `TABLE_COLUMNS`(테이블의 컬럼, 업무 설명 없는 것만 선택), `SELECTED`. 서버가 요청 시점에 대상을 정하고(로그인 프로젝트만) 최대 100개.
- 프로젝트당 진행 중 실행 하나 (프로젝트 행 잠금 후 확인 → `AI_JOB_ALREADY_RUNNING` 409).
- 대상마다 ASYNC 실행 컨텍스트(요청자·프로젝트 고정)로 단건과 같은 경로 — 권한·근거 검증·토큰 예산이 같다. 검토 대기 제안이 있으면 건너뜀. 재시도 없음.
- 실패 격리 : 형식·예산·시간 초과·AI 서버 오류는 그 대상만 실패. 권한 회수·프로젝트 비활성·기본 Provider 미지정/비활성·SEC-1 차단은 멈춤, AI 서버 호출 실패 3번 연속도 멈춤. 이미 만든 제안은 남는다. 상태 `COMPLETED`·`PARTIAL_FAILED`·`FAILED`, 오류 요약은 대상·오류 코드만(50건).
- 재시작 : 기동 때 끝나지 않은 실행을 `INTERRUPTED` 로 닫는다 (자동 재개 없음 — 다시 요청하면 만든 것은 건너뛴다). 진행 상태는 대상마다 짧은 트랜잭션으로 남긴다 (AI 호출 동안 DB 트랜잭션 없음).

## 7. 화면

- **AI 관리 › AI 설명 제안** (`/aiDictionary`, 2026-11-15 데이터 사전에서 옮김 — 18장) : 대상 선택(테이블 목록 / 테이블의 컬럼, 업무 설명 없는 것만, 원본 COMMENT·업무 설명 유무·최근 제안 상태를 따로 표시) → 선택 초안 만들기(1개 = 단건, 여러 개 = 대량), 업무 설명 없는 테이블·컬럼 모두, 근거 변경 확인 / 대량 생성 진행(2초마다 갱신, 끝나면 목록 갱신) / 제안 목록(상태·이름·승인 뒤 근거 변경 필터, 일괄 승인·반려·보류) / 상세 대화상자.
- **상세** : 원본 COMMENT · 테이블 코멘트/컬럼 설명 · 현재 업무 설명 · AI 제안(검토 권한이면 고쳐서 수정 후 승인) · 확실성(AI 주장보다 낮췄으면 표시) · 생성 뒤 바뀐 것 · 이름 해석 · 표준 추천(참고) · 근거(n/m 확인, "확인됨 ≠ 문장 전체" 안내) · 연관 객체 · 확인할 수 없는 부분 · 확인 질문 · 생성 정보(모델·프롬프트 버전·시각·실행·토큰) · 검토 이력. 주소 `?proposal=ID`, `?tableId=&columnId=`(그 대상 선택·최근 제안 열기).
- **테이블 상세**(테이블 관리 등) : 업무 설명·제안 상태·이동 링크, 컬럼 목록에 '업무 설명' 칸(설명·상태 링크). **컬럼 상세**(컬럼 관리) · **물리 ERD 상세 패널·컬럼 상세** : 같은 정보와 링크. 테이블 수정 화면에 업무 설명 입력.
- **업무 설명 직접 수정** (2026-11-15 후속) : 테이블 수정 화면은 업무 설명을 고쳤을 때만 보내고 화면이 읽은 값(`bizDescriptionBase`)을 함께 보낸다. 서버는 잠근 행의 지금 값이 그와 다르고 요청이 지금 값과도 다르면 `BIZ_DESCRIPTION_CONFLICT`(409)로 막는다 (그 사이 다른 사용자·AI 제안 승인이 쓴 값을 덮어쓰지 않는다). 컬럼 일괄 편집 행도 같은 규칙(행 오류). `bizDescriptionBase` 를 보내지 않는 API 호출은 예전처럼 요청 값을 쓴다.
- AI 도우미(AI-4)는 질의 기능 그대로 — 승인 워크플로를 넣지 않았다.
- 한국어·영어, 라이트·다크, 데스크톱·모바일(표는 가로 스크롤, 상세는 한 줄 배치), 아이콘은 Lucide.

## 8. 품질 평가 (U-20)

- `GET /api/ai/dictionary/metrics` : 모델·프롬프트 버전별 생성·승인·수정 후 승인·반려·보류·재검토 필요·대체·대기 수, 승인율(승인/(승인+반려)), 수정 비율(수정 후 승인/승인), 반려율, **존재하지 않거나 범위 밖·불일치 근거 비율**, **근거 없는 단정 비율**(확인된 근거 0 인데 AI 가 VERIFIED·INFERRED 라고 한 제안), **잘못된 관계 근거 비율**(확인되지 않은 RELATION 근거), UNKNOWN 비율, **표준 용어 추천 적합성**(컬럼에 지금 연결된 **승인된** 표준 용어를 기준 데이터로 — 추천이 같은 비율), 생성→첫 검토 시간 중앙값(작성 시간 절감의 참고값).
- 사용자의 승인·수정·반려와 근거 검증 결과가 그대로 평가 자료로 쌓인다 (제안·이력 테이블).
- **하지 않은 것** : 테이블·컬럼 설명의 업무적 정확성은 사람이 판정한 기준 데이터가 있어야 해서 계산하지 않았다. 실제 레거시 메타데이터 기준 데이터를 만들지 않았다(접근 가능한 실제 데이터 없음). 수작업 작성 시간과의 직접 비교도 없다. 목표치는 정하지 않았다.

## 9. 보안

- 프로젝트 격리 : 대상·제안·이력·실행 모두 실행 컨텍스트의 프로젝트로만 (다른 프로젝트 ID = 없음/404). 모델 응답의 ID 는 검증 대상일 뿐 범위를 바꾸지 못한다 (스키마에 프로젝트 필드 없음).
- 비동기 실행 중 권한 회수 → 다음 대상에서 `PROJECT_ACCESS_DENIED` 로 멈춤.
- AI 안전성 : 원본 DB 접근·SQL 실행 없음(Tool 경계), 원본 COMMENT·DDL 설명·표준 용어 연결을 바꾸지 않음, 자동 승인 없음(생성은 PROPOSED 또는 REVIEW_REQUIRED 만), 상태·검증 필드를 AI 가 쓸 수 없음(스키마), 프롬프트 인젝션 문구가 있어도 상태가 바뀌지 않음, 예시 값(실제 데이터일 수 있음) 미전송, SEC-1 그대로.
- 트랜잭션 : AI 호출 동안 DB 트랜잭션 없음. 맥락 조회·기준 버전은 짧은 읽기 전용, 저장·승인은 짧은 쓰기.
- 로그 : `/api/ai/**` 라 요청 로그에 본문 없음. 애플리케이션 로그에는 요청 ID·대상 ID·결과·근거 수만.

## 10. API

| 메서드·경로 | 권한 | |
|---|---|---|
| `GET /api/ai/dictionary/proposals?status&targetType&tableId&keyword&recheck&page&size` | VIEWER | 목록 (`OPEN` = 검토 대기 전체) |
| `GET /api/ai/dictionary/proposals/{id}` | VIEWER | 상세 + 변경 미리 보기 + 이력 |
| `GET /api/ai/dictionary/targets?tableId&onlyMissing&keyword` | VIEWER | 생성 대상 후보 |
| `GET /api/ai/dictionary/objects/{tableId}` | VIEWER | 테이블·컬럼의 업무 설명·제안 상태 |
| `GET /api/ai/dictionary/metrics` | VIEWER | 품질 지표 |
| `GET /api/ai/dictionary/jobs`, `/jobs/{id}` | VIEWER | 실행 |
| `POST /api/ai/dictionary/proposals/generate` | USER | 단건 생성 `{tableId, columnId?, regenerate?}` |
| `POST /api/ai/dictionary/jobs` | USER | 대량 `{scope, tableId?, onlyMissing?, targets?}` |
| `POST /api/ai/dictionary/proposals/recheck` | USER | 근거 변경 재확인 |
| `POST /api/ai/dictionary/proposals/{id}/approve·reject·hold·review` | USER + 승인 권한 | `{version, description?, reason?}` |
| `POST /api/ai/dictionary/proposals/bulk` | USER + 승인 권한 | `{action, items:[{id,version}], reason}` |

오류 코드(새로) : `AI_PROPOSAL_NOT_FOUND`(404), `AI_PROPOSAL_CONFLICT`(409), `AI_PROPOSAL_INVALID_STATE`(409), `AI_JOB_ALREADY_RUNNING`(409), `AI_JOB_INVALID`(400), `AI_JOB_BUSY`(503). Provider·SEC-1·예산·형식 오류는 AI-3·AI-4 그대로.

## 11. 검증

- 자동화 (가짜 Ollama) : `AiDictionaryDraftIntegrationTest` 7개 (생성 — 세 그룹 맥락·Tool 정의 미전송·근거/추천/연관 검증·확실성 낮춤·불확실성·질문·중복 방지·재생성·대상 불일치 재요청·형식 2회 실패·범위·권한 / 검토 — 승인·수정 후 승인·반려·보류·검토함·권한(USER·VIEWER·ASYNC 거부)·충돌·중복 승인·일괄·다른 프로젝트 / 재검증 — 근거와 무관한 변경·승인 여부만 변경은 승인, 근거 컬럼 타입·관계 규칙 변경·다른 사용자의 업무 설명 수정·대상 삭제는 REVIEW_REQUIRED, 재확인(승인 설명 보존) / 동시 승인 / 대량 — 부분 실패·건너뜀·중복 실행·권한 회수 중단·재시작 INTERRUPTED·범위 / Provider — 미지정·비활성·연결 실패·SEC-1·토큰 예산·Tool 미지원 모델·프롬프트 인젝션 / 품질 지표), `AiDictionarySourceRecheckIntegrationTest`(원본 카탈로그 읽기 계약을 가짜로 : 반영 순서만 변경·근거 아닌 컬럼 변경은 승인, 근거 컬럼 원본 COMMENT·타입 변경·원본 테이블 없음은 REVIEW_REQUIRED), `AiDictionaryMigrationTest`(신규 설치, H2·PostgreSQL — 처음엔 V9→V10 업그레이드를 검증했으나 Flyway 초기화로 업그레이드 경로가 없어졌다), `SmokeTest`(실제 HTTP 로 조회 파라미터·경로 변수·선택 필드 바인딩).
- **실제 Ollama 0.40.16 · `gemma4:e4b`** (격리 인스턴스, 예제 프로젝트) : 12장.
- 화면 : 헤드리스 Chrome — AI 설명 제안 화면·재검토 필요 제안 상세·대상으로 이동, 한국어·영어 × 라이트·다크 × 데스크톱·모바일 (가로 넘침 없음, 콘솔 오류 없음), 예전 주소 이동, 테이블 상세의 업무 설명·컬럼 칸, 물리 ERD 상세 패널.

## 12. 실제 Ollama 검증 결과

| 시나리오 | 결과 |
|---|---|
| A 테이블 (`TB_AI_STNG`) | 4.6s, 모델 호출 1번(2,494+425 토큰), 근거 4/4(테이블·컬럼 2·FK) 확인, 연관 테이블 `TB_AI_PRVDR` 확인, 확실성 VERIFIED, PROPOSED, 업무 설명 그대로 |
| B 컬럼 (`TB_AI_PRVDR.PRVDR_TYPE_CD`, 업무 설명 없음) | 13.9s, 4,343+1,486 토큰, 이름 해석 `PRVDR(제공자)+TYPE(유형)+CD(코드)`, 표준 용어 `제공자_유형_코드`·도메인 `코드V20` 추천 모두 확인, 근거 3/3. 불확실한 부분·질문은 비어 있었다(표준 용어 설명에 허용 값이 있어서로 보이나, 코드값 의미를 다 확인한 것은 아니다 — 아래 관찰) |
| C 수정 후 승인 | 고친 문구가 업무 설명에 반영, 컬럼 설명(DDL)·원본 COMMENT 그대로, 이력 GENERATE → APPROVE_EDITED |
| D 근거 변경 | `PRVDR_NM` 제안 뒤 화면 API 로 승인취소 + 길이 100→200 → 미리 보기와 승인 모두 `MODIFIED [maxLength]`(승인 여부 변경은 무시) → REVIEW_REQUIRED, 업무 설명 그대로. 같은 때 근거와 무관한 `TB_AI_STNG` 테이블 제안은 승인됨. C 의 승인 설명 보존 |
| E 대량 | `TB_AI_STNG` 컬럼 6개 72초 COMPLETED 6/6 / `TB_AI_PRVDR` 컬럼 10개 9 생성·1 건너뜀(이미 제안 있음) / 진행 중 다른 실행 요청 409 / 70개 실행 중 서버를 내렸다 올리자 INTERRUPTED, 만든 2개는 남음 |
| 장애 | 닫힌 포트의 Provider 로 생성 → `AI_PROVIDER_CALL_FAILED`(연결 거부), 제안 없음, 데이터 사전·테이블 조회 정상 |
| 지표 | 생성 9, 승인 2(수정 1), 재검토 필요 1, 근거 오류 비율 0.0345, 표준 용어 적합성 1.0(기준 8건) — 표본이 작아 일반화하지 않는다 |

- Mock 으로만 확인한 것 : 대량 생성의 일부 실패(형식 오류), 권한 회수 중단, 동시 승인 충돌, 원본 카탈로그 변경(처음엔 읽기 계약을 가짜로 — 실제 원본 DB 최신화는 14장), 토큰 예산 초과, Tool 미지원 모델, SEC-1 차단.
- 관찰 : 작은 모델은 표준 용어·설명이 있으면 확실성을 VERIFIED 로 두고 `unknowns`·`questions` 를 비우는 경향이 있었다 (지시문은 코드값 의미 등을 남기라고 한다). 확실성은 서버가 근거 검증으로만 낮출 수 있어 이 경우 그대로다 — 사람의 검토가 전제다.
- 검증 중 고친 것 : 컨트롤러의 요청 파라미터 이름(빌드에 `-parameters` 가 없어 실제 HTTP 에서 500) · 선택 필드를 기본형으로 받아 생략 시 400 → `SmokeTest` 에 HTTP 바인딩 검사 추가.

## 13. 남은 것과 제약

1. **품질 평가 기준 데이터** : 사람이 판정한 기준 데이터(실제 레거시 일부 테이블)와 업무적 정확성 평가가 없다. 지표 계산·자료 축적은 된다.
2. ~~원본 카탈로그 재검증의 실제 최신화 검증~~ → **2026-11-15 후속에서 확인** (14장).
3. 업무 설명 칸은 AI-1d 의 최소 범위만 했다 : 엑셀 업·다운로드, 컬럼 등록 화면의 업무 설명 입력(일괄 편집 행은 API 만), DDL 출력에는 넣지 않았다 (DDL COMMENT 는 기존 설명). → 엑셀 템플릿·업로드·컬럼 등록·일괄 편집 화면은 **2026-11-16 AI-1d 후속에서 구현** (26장). DDL 출력(DEC-03)과 데이터 내려받기는 남음. 원본 COMMENT 변경 시 "유지/수정 제안" 은 사람이 다시 만들기로 만든다 (자동 생성 없음).
4. 표준 용어·도메인 추천은 참고만 — 승인해도 연결하지 않는다 (기존 컬럼 화면의 용어 연결로).
5. 승인 설명의 재사용 범위(U-05)는 현재 프로젝트 안 (맥락 그룹 [3]).

## 14. 후속 : 실제 원본 카탈로그 최신화 기반 재검증 (2026-11-15)

실제 원본 PostgreSQL → JDBC 수집(최신화) → 원본 매핑·가져오기 → AI 설명 제안 → 원본 변경 → 다시 최신화 → 승인까지 확인했다.

- **경로** : 원본 최신화는 수집 실행(`SourceCollectionService`)이 끝날 때 바뀐 테이블만 새 버전과 반영 순서(`RFLT_ORD`)를 남긴다. 제안의 기준 버전에는 매핑된 원본 테이블의 반영 순서·테이블 COMMENT·근거 컬럼의 원본 값이 들어 있고, 승인·상세·재확인 때 `SourceCatalogReader.latest`·`tableDetails`(마지막 반영 순서)로 지금 값을 읽어 비교한다. 반영 순서가 같으면 원본은 보지 않는다 — 바뀐 것이 없는 최신화는 반영 순서를 늘리지 않는다. 모델의 원본 COMMENT 칸(`SRC_ANTN_CN`)은 최신화가 아니라 가져오기만 바꾼다.
- **자동화 (실제 원본 DB, AI 서버만 가짜)** : `AiDictionarySourceRefreshIntegrationTest` 5개 (`LEXORMA_TEST_SOURCE_POSTGRES_URL` 이 있을 때)

| 시나리오 | 결과 |
|---|---|
| 근거 컬럼 원본 COMMENT 변경 → 최신화 → 승인 | REVIEW_REQUIRED, `PAY_AMT:comment` 변경 전후 표시. 확정 업무 설명·모델 원본 COMMENT 칸·컬럼 설명·승인 여부 그대로 |
| 근거 컬럼 타입 / 길이 / NULL 허용 변경 | 각각 REVIEW_REQUIRED (`definition` 전후 : `integer · NOT NULL → bigint · NOT NULL` 등) |
| 근거가 아닌 컬럼의 타입·길이·NULL 변경 | 승인 |
| 테이블 대상 : 근거가 아닌 컬럼 변경 / 테이블 COMMENT 변경 | 승인 / REVIEW_REQUIRED (`tableComment` 전후) |
| 모델 : 근거가 아닌 컬럼 수정, 대상의 정렬 순서·승인 여부 변경, 바뀐 것 없는 최신화 | 승인 (객체 승인 여부는 AI 승인으로 바뀌지 않음) |
| 모델 : 대상의 데이터 타입 변경 | REVIEW_REQUIRED `MODIFIED [dataType]` 전후 |
| 원본 매핑이 없는 모델 (같은 이름의 원본 컬럼이 바뀜) | 승인 (기준에 원본 없음 — 모델만 본다) |
| 원본 컬럼 삭제 / 원본 테이블 삭제 / 모델 대상 삭제 | `SOURCE_CHANGED [PAY_MEMO:missing]` / `SOURCE_TABLE_MISSING` / `TARGET_DELETED` |
| 원본 연결 삭제 (매핑·카탈로그 정리 → 원본 값을 확인할 수 없음) | `SOURCE_TABLE_MISSING` (안전하게 재검토) |
| 재확인 | 검토 대기 1건 REVIEW_REQUIRED, 승인 1건 '재검토 필요' 표시만 (상태·업무 설명 그대로) |
| 원본 COMMENT 다시 가져오기 | 모델 원본 COMMENT 칸만 바뀜, 업무 설명·설명·승인 여부 그대로. 그 전 제안은 `SOURCE_CHANGED` + `MODIFIED [sourceComment]` |
| 업무 설명 직접 수정 | 안 고치면 그대로, 읽은 뒤 승인으로 바뀐 값을 고치려 하면 `BIZ_DESCRIPTION_CONFLICT`, 지금 값 기준이면 저장, VIEWER 는 403(`AccessDenied`). 컬럼 일괄 편집도 충돌은 행 오류 |

- **실제 E2E (격리 인스턴스 + 실제 원본 PostgreSQL + 실제 Ollama 0.40.16 `gemma4:e4b`)** : HTTP API 로 SEC-1 규칙·Provider·원본 연결 → 수집 → 기준 연결·스키마 매핑 → 가져오기(TABLE 1, 컬럼 4, COMMENT 3) → 생성. 모델은 가져온 원본 COMMENT "결제 금액(원)" 을 근거로 설명을 만들고(근거 1/1 확인, 부가세 포함 여부·통화를 확인할 수 없는 부분·질문으로 남김) 승인 → 원본 COMMENT 를 "결제 금액(부가세 포함)" 으로 바꾸고 최신화(반영 순서 2) → 다음 제안 승인은 REVIEW_REQUIRED (전후 표시), 확정 업무 설명 그대로 / 근거가 아닌 컬럼 타입 변경 후 승인 / 근거 컬럼 NULL 허용 변경 후 REVIEW_REQUIRED / 재확인으로 승인 2건 표시. 화면(헤드리스 Chrome) : 상세의 변경 전후 목록, 한국어·영어 × 데스크톱 라이트·모바일 다크, 가로 넘침·콘솔 오류 없음.
- **고친 것** : ① 변경 전후 값이 없었다 (필드 이름만) → `diffs` 추가. ② 테이블 수정 화면이 열 때 읽은 업무 설명을 늘 보내, 화면을 연 사이 AI 제안 승인이 쓴 업무 설명을 저장 때 되돌렸다(비어 있었으면 지웠다) → 고쳤을 때만 보내고 읽은 값과 비교 (`BIZ_DESCRIPTION_CONFLICT`).
- **위험 (2026-11-15 결정 → 15장에서 해결)** : 잠그지 않은 모델 쓰기의 전체 행 UPDATE 가 승인된 업무 설명을 되돌렸다 (H2·PostgreSQL 재현). 사용자 결정 (a) `@DynamicUpdate`.

## 15. 후속 : 업무 설명 유실(Lost Update) 방지 (2026-11-15)

사용자 결정 (a) : `TableInfo`·`ColumnInfo` 에 `@DynamicUpdate`. 그것만으로 끝났다고 보지 않고 저장 경로·같은 칸 충돌·성능을 함께 확인했다.

- **UPDATE SQL** (테스트 `SqlCapture` — 테스트 프로필의 `StatementInspector`, 켜 둔 동안만 모은다)

| 저장 | 전 | 후 |
|---|---|---|
| 컬럼 설명만 수정 | `update tb_col set task_expln=?,col_expln=?,col_nm=?, …(28칸)… where col_sn=?` | `update tb_col set col_expln=?,mdfcn_dt=? where col_sn=?` |
| 테이블 설명만 수정 | `update tb_tbl set task_expln=?,aprv_yn=?, …(12칸)… where tbl_sn=?` | `update tb_tbl set tbl_expln=?,mdfcn_dt=? where tbl_sn=?` |
| AI 제안 승인 | 전체 칸 | `update tb_col set task_expln=?,mdfcn_dt=? where col_sn=?` |

- **저장 경로 점검** : `TASK_EXPLN` 을 쓰는 곳은 AI 제안 승인(제안 → 대상 행 잠금, 기준 값 비교)·테이블 수정(행 잠금 + refresh, `bizDescriptionBase` 비교)·컬럼 일괄 편집(null 이면 그대로) 셋뿐이다. 업무 설명이 요청에 없을 때 null 로 만드는 코드는 없다. `toEntity()` 는 ID 없이 새 객체만 만든다(등록). 엑셀 업로드는 테이블·컬럼을 INSERT 만 한다(같은 이름은 건너뛰거나 오류). 원본 가져오기는 원본 COMMENT 칸만 쓰고 행을 NOWAIT 로 잠근다. `BeanUtils.copyProperties`·`merge`·모델 테이블의 JPQL/Native 일괄 UPDATE 는 없다. 잠그지 않고 읽어 쓰는 경로(컬럼 일괄 편집, 승인 처리, 테이블 이름 변경의 컬럼 테이블명, 스키마 이름 변경의 참조 테이블명, 정렬 순서, 참조 동기화 등)는 이제 바꾼 칸만 쓰므로 업무 설명을 되돌리지 않는다.
- **같은 칸 충돌** : `@DynamicUpdate` 로는 막지 못한다. 테이블 수정은 잠근 행 값과 `bizDescriptionBase` 비교(409 `BIZ_DESCRIPTION_CONFLICT`). 컬럼 일괄 편집은 업무 설명을 고치는 행만 **ID 순서로 잠그고 다시 읽은 뒤** 비교하도록 고쳤다 — 잠그기 전에 비교해 동시에 고친 두 저장 중 뒤의 것이 앞의 것을 덮어썼다(테스트로 재현). AI 승인은 기준 값 비교(`BIZ_DESCRIPTION_CHANGED`).
- **테스트** `AiDictionaryLostUpdateIntegrationTest` (H2·PostgreSQL, 두 트랜잭션을 실제로 엇갈려 실행) : UPDATE SQL 모양 / 컬럼 — 일괄 편집 서비스가 승인 전에 읽고 승인 뒤 커밋해도 승인 설명·편집 내용 모두 남고 제안은 APPROVED, 반대 순서는 근거 변경으로 REVIEW_REQUIRED / 테이블 — 잠그지 않은 쓰기가 늦게 커밋해도 승인 설명·설명·정렬 순서 모두 남고 객체 승인 여부 그대로 / 같은 칸 — 테이블 두 사용자 409, 승인 뒤 오래된 기준으로 일괄 편집 행 오류, 동시 일괄 편집 두 개는 뒤의 것이 잠금을 기다린 뒤 막힘. `@DynamicUpdate` 를 빼면 컬럼 테스트가 실패하는 것을 확인했다.
- **성능 (측정, 로컬)** : Hibernate 는 `@DynamicUpdate` 엔터티의 UPDATE 를 JDBC 배치로 묶지 않는다 — SQL 이 같아도 한 건씩 보낸다 (prepared UPDATE 2,000 → 2,000, 전에는 배치 1). 컬럼 2,000행 한 트랜잭션 : PostgreSQL 같은 칸 180~330ms → 550~790ms, 다른 칸 섞임 180~250ms → 560~660ms (약 2.5~3배), H2 차이 거의 없음. 실제 컬럼 일괄 편집 200행은 전후 비슷(60~135ms). 엑셀 업로드는 INSERT 라 영향 없음. 원격 DB 는 왕복 시간만큼 더 늘어난다(행당 1회). 대량 UPDATE 경로(여러 컬럼 일괄 승인, 테이블·스키마 이름 변경에 따른 컬럼 갱신)가 느려질 수 있다.
- **사용자 결정 (2026-11-15)** : `@DynamicUpdate` 유지, 위 배치 성능 저하는 수용한다. 대량 UPDATE 전용 SQL·별도 저장 구조는 만들지 않고, 운영에서 대량 UPDATE 병목이 확인되면 별도 최적화 과제로 한다. 일반 모델 속성의 동시 수정 충돌도 별도 개선 과제다.
- **남은 위험** : 업무 설명 외의 같은 칸을 두 쪽이 동시에 고치는 경우는 예전처럼 나중 커밋이 이긴다(이번 범위 밖). 업무 설명을 DB 를 직접 고치면 비교·이력이 없다. 컬럼 일괄 편집이 아닌 새 쓰기 경로가 업무 설명을 쓰게 되면 같은 잠금·비교(`BizDescriptions`)를 써야 한다.

## 16. 후속 : 업무 설명 품질 평가 (2026-11-15) — 기능 구현, 평가 미완료

목적 : AI 설명을 사람이 확인한 실제 업무 의미와 견줘 평가한다. **AI 가 판정하지 않는다** — 정확성 등은 사람의 판정만, AI 의 확실성과 근거 검증(VERIFIED)은 정확성으로 보지 않는다.

- **기존 기능 분석** : 제안마다 모델·프롬프트 버전·해시·근거 검증·기준 버전·검토 이력이 남고, `/api/ai/dictionary/metrics` 가 모델·프롬프트별 승인율·수정 비율·근거 오류·근거 없는 단정(AI 주장 기준)·용어 일치(연결된 승인 용어 기준)·검토 시간을 낸다(화면 없음). 사람이 확인한 실제 의미와 판정을 담을 곳이 없어 업무적 정확성은 계산할 수 없었다.
- **데이터 (V2 `V2__ai_dictionary_evaluation.sql`, PostgreSQL·H2 같은 내용)**
  - `TB_AI_EXPLN_EVL_TRGT` 평가 대상 = 기준 데이터 : 테이블·컬럼, 범주(JSON : 의미 명확·이름만으로 판단 어려움·상태 코드·금액·날짜/시간·표준 용어 연결·관계·원본 COMMENT 없음·원본 COMMENT≠업무 설명), **실제 의미**(사람이 적음, AI 에 보내지 않음), 확인 상태(미확인·확인·확인 불가), 확인한 사람·시각, 등록 시점 메타데이터 버전(수정 일시 + 모델 변경 이력 ID), `VER_NO`.
  - `TB_AI_EXPLN_EVL` 판정 (제안 × 검토자 하나, `UX_AI_EXPLN_EVL_01`) : 업무적 정확성(맞음·일부·틀림·평가 불가), 근거 없는 주장 여부, 누락 정보, 불확실성 처리(적절·단정·과잉·해당 없음), 표준 용어·도메인 추천 적절성, 수정 필요 부분, 검토 시간·직접 작성 시간(분), 모델·프롬프트 버전, 검토자·시각, `VER_NO`. 최종 승인·수정·반려는 제안의 검토 상태·이력에서 읽는다(중복 저장 안 함).
  - 새 표준 단어 : 평가 EVL, 판정 JGMT, 추천 RCMDTN, 결과 RSLT, 시간 HR, 작성 WRT, 의미 MNNG, 실제 ACTL, 확인 IDNTY, 소요 REQ (공통표준 템플릿), 주장 CLM, 무근거 UNFND, 누락 OMSN, 불확실 UNCRT (템플릿에 없음). 예제 물리·논리 모델·매핑·인덱스 3개.
- **규칙** : 정확성 판정(맞음·일부·틀림)은 실제 의미를 확인한 대상에만 (아니면 평가 불가만). 확인 불가 대상은 정확성 분모에서 빼고 따로 센다. 정확성 판정이 있는 대상은 확인 상태를 되돌릴 수 없다. (2026-11-16 강화 : 판정이 있으면 의미 문구·확인 상태 모두 고정, 판정은 CONFIRMED 대상에만, 확인 전 블라인드, 평가 중 승인 제한 — 23장. 2차 : 일반 제안 조회도 블라인드, 등록·승인 동시성, '근거 없는 주장' 필수 선택 — 24장) 범주는 이름·타입·연결·관계로 고른 제안값이고 사람이 고친다. 등록은 모델 대상 행(테이블·컬럼) 잠금으로 중복과 승인 경합을 막는다(24.3, 그 전에는 프로젝트 공유 잠금이라 PostgreSQL 에서 중복을 막지 못했다). 권한 : 조회 VIEWER, 작성 USER(로그인 실행만, 작성자 기록).
- **지표 (`GET /api/ai/dictionary/evaluation/metrics`)** — 모델·프롬프트 버전별, 모두 분자/분모를 함께 : 업무적 정확성(맞음 / 맞음+일부+틀림, 확인된 대상만), 일부 맞음, 평가 불가 수, 근거 없는 주장(사람 판정), **허구 객체**(근거·추천·연관 객체 중 서버 검증이 존재하지 않음·범위 밖·불일치로 본 것이 있는 제안 — DB 조회 결과라 AI 판단이 아니다), 표준 용어·도메인 추천 적절(추천 없음·평가 불가 제외), 불확실성 처리 적절·단정·과잉, 수정 후 승인·승인율(판정한 제안), 검토·직접 작성 시간 중앙값과 절감률(둘 다 적은 판정만), 등록 시점 메타데이터로 만든 판정 수(모델 비교의 같은 조건 확인). 기준 데이터 현황(대상·확인·확인 불가·미확인·범주별). 합격 기준은 정하지 않았다. 판정이 없으면 `evaluated=false`.
- **같은 조건의 모델 비교** : 평가 대상은 등록 시점의 메타데이터 버전을 갖고, 제안마다 생성 시점 기준 버전이 있어 "같은 메타데이터로 만든 제안인지" 를 표시·집계한다. 모델 비교는 기본 Provider 의 모델을 바꿔 같은 대상을 다시 만들고(앞 제안은 대체됨으로 남는다) 판정한다. 메타데이터 전체 스냅숏을 고정하지는 않는다 — 대상 자신의 버전만 본다.
- **화면** : AI 관리 › AI 품질 평가(`/aiEvaluation`. 처음엔 AI 설명 제안의 '품질 평가' 탭 `?tab=evaluation` — 그 주소는 옮겨 간다, 18장) — 현황·지표 표(분자/분모)·미완료 안내, 테이블별 후보(범주 제안) 등록, 대상 목록, 대상 상세(실제 의미·확인 상태·범주, 모델별 제안 — AI 확실성은 "정확성 아님" 표시, 다른 메타데이터·존재하지 않는 객체 표시, 불확실한 부분·질문·추천, 판정 입력). 한국어·영어, 라이트·다크, 모바일.
- **테스트** : `AiDictionaryEvaluationIntegrationTest` (기준 데이터·판정·지표, 실제 의미가 AI 요청 본문에 없음 확인 — 여기의 판정 값은 기능 검증용 테스트 데이터), `SmokeTest`(HTTP 바인딩), `ProjectDeletionIntegrationTest`, 예제·인덱스 기준값(114·167).
- **실제 준비 (격리 인스턴스, 실제 Ollama `gemma4:e4b`)** : LEXORMA 예제(Lexorma 자체 스키마)에서 26개 대상(테이블 3·컬럼 23, 범주 : 상태 코드 6·날짜/시간 8·표준 용어 연결 23·관계 12·원본 COMMENT 없음 26)을 등록하고 대량 생성으로 제안 26개(391초, 실패 0). **실제 의미와 판정은 적지 않았다** — 검토자가 없고, AI(작업자 포함)가 정답을 만들면 평가가 아니다. 지표 `evaluated=false`.
  - 서버 검증으로 보인 것(사람의 판정 아님) : 26개 중 3개가 존재하지 않거나 맞지 않는 객체를 근거로 들었다(테이블 ID 를 스키마로, 관계 ID 와 이름 불일치) → 확실성 INFERRED 로 낮춤. 확인할 수 없는 부분이 빈 제안 10개.
  - 설명 문구의 문제(서버가 막지 못함) : 모델이 자신의 확신을 문구에 넣었다("(신뢰도: 확정됨)") — 확실성은 서버가 낮췄지만 문구에 남는다. 내부 ID 를 문구에 넣었다("ID 7001"). 표준 용어의 사전적 정의를 그대로 옮긴 설명이 여럿 있다(업무 의미인지는 사람이 판정).
- **평가 미완료와 한계** : 사람이 확인한 실제 의미·판정이 0건이라 업무적 정확성·근거 없는 주장·추천 적절성·불확실성 처리·시간 절감은 아직 값이 없다. 예제 스키마에는 금액 컬럼·표준 용어 없는 컬럼·업무 설명·원본 COMMENT 가 없어 '금액·통화', '이름만으로 판단 어려움', '의미 명확', '원본 COMMENT 다름' 범주를 채울 수 없다 — 실제 레거시 DB(원본 연결·가져오기)가 필요하다. 격리 인스턴스의 준비 데이터는 검증 뒤 지웠다.

## 17. 후속 : 프롬프트 v2 · 설명 문구 검증 (2026-11-15)

16장의 준비 과정에서 본 생성 결과의 문제(자기 확신 문구, 내부 ID 노출, 근거 ID 오용, 사전적 정의 복사)를 지시문과 서버 검증으로 줄인다. 업무적 정확성 평가는 아니다 (사람의 기준 데이터 없음).

- **분석** : 응답 처리 순서는 형식(`JsonSchemaOutput`, 스키마) → 대상(대상 ID·테이블 대상의 추천 금지, 실패하면 사유를 붙여 1회 재요청) → 근거·추천·연관 객체(`EvidenceVerifier`, 재요청 없이 상태만 매기고 확실성을 낮춘다) → 확실성(`cap` : AI 주장보다 높이지 않음) → 저장이었다. 문구 자체를 보는 검사는 없었다. 근거 ID 오용 두 사례(테이블 ID 를 SCHEMA 로, 관계 ID 와 이름 불일치)는 이미 `NOT_FOUND`·`MISMATCH` 로 잡히고 있었다 — 계약은 그대로 두고 회귀 테스트로 고정했다.
- **프롬프트 v2** (`ai/prompts/description-draft-v2.txt`, `dictionary-draft/2`, 지시문+스키마 SHA-256) : v1 규칙에 더해 ⑧ 확실성은 `certainty` 필드에만 — 설명·불확실한 부분·질문에 자기 평가(신뢰도 라벨·"확인됨" 꼬리표·"AI 가 검증"·"정확성 보장"·"100% 확실") 금지, 어느 메타데이터에서 왔는지는 말해도 된다 ⑨ 내부 ID 는 근거·추천·연관 객체 필드에만, 문구에서는 이름으로 ("주문 ID" 같은 업무 표현은 허용) ⑩ 근거의 ID 는 그 종류의 ID 를 자기 이름과 함께 (tableId 는 TABLE 로만 …) ⑪ 표준 용어·단어의 사전적 정의는 업무 설명이 아니다 — 확정 업무 설명이 없으면 기술적 사실과 확인이 필요한 업무 의미를 나누고 후자는 불확실한 부분·질문으로. 규칙 ② 에 날짜·시간의 업무 기준(어떤 사건의 시각·시간대)을 더하고, 확정 업무 정보가 그 점을 정하면 `unknowns` 가 비어도 된다고 적었다. 스키마는 바꾸지 않았다. v1 파일은 기록으로 남긴다.
- **문구 검사** (`DescriptionTextCheck`, 대상 검사 뒤 · 근거 검증 앞, 같은 재요청 루프) : 설명·이름 해석·불확실한 부분·확인 질문만 본다 (근거·추천·연관 객체 필드의 ID 는 정상).
  - 자기 평가 : 명백한 형태만 — "신뢰도/확신도/확실성/confidence" 라벨 + 값, "(확인됨)"·"(메타데이터에서 확인됨)"·"(verified)" 같은 괄호 꼬리표, "AI 가 검증/확인한", "verified by AI", "정확성이 보장", "100% 확실/정확". "확정·신뢰·검증" 낱말만으로는 걸지 않고("확정된 업무 설명에 따르면" 통과), "정확도"·"accuracy" 는 업무 속성일 수 있어 라벨로 보지 않는다. 같은 표현이 서버가 준 메타데이터(확정 업무 설명 등)에 있으면 걸지 않는다.
  - 내부 ID : ID 를 뜻하는 말(id·tableId·Term ID·아이디·식별자) 바로 뒤의 숫자가 **서버가 모델에 준 메타데이터의 ID**(도구 결과의 `id`·`*Id` 정수 + 대상 ID)일 때만. 숫자 없는 "주문 ID"·"고객 식별자", 메타데이터에 없는 숫자는 통과.
  - 걸리면 걸린 부분과 사유를 모델에 주고 1회 재요청(기존 재시도 정책). 다시 걸리면 **제안을 만들지 않고** `AI_DESCRIPTION_TEXT_INVALID`(502, 대량 생성에서는 그 대상만 실패) — 서버가 문구를 고치거나 잘라내지 않는다. 형식·대상 실패는 예전처럼 `AI_STRUCTURED_OUTPUT_INVALID`. 거절 사유 종류는 제안 상세(`rejections`, 예 `[["SELF_ASSESSMENT"]]`)에 남고 로그에는 종류만 남는다(문구·메타데이터 없음).
- **호환** : 기존 v1 제안·승인 설명·검토 이력·품질 평가 판정은 그대로(버전·해시 유지), 자동 재생성·재승인 없음. 새 제안만 v2. 지표는 프롬프트 버전별로 나뉜다.
- **테스트** : `DescriptionTextCheckTest`(23 : 자기 평가 12형 검출, 업무 표현 8형 통과, 내부 ID 검출·업무 ID 통과·근거 필드 제외, 필드·확정 정보 예외, ID 수집), `AiDictionaryDraftIntegrationTest` 추가 3개 (재요청 성공 — 거절 사유 기록·두 번째 요청에 사유, 두 번 실패 — 제안 없음·앞 제안 그대로, 업무 ID 통과 / 근거 회귀 — SCHEMA 로 든 테이블 ID 와 이름이 다른 관계 ID 는 VERIFIED 아님·확실성 INFERRED, 확인된 근거 없으면 UNKNOWN, AI 의 UNKNOWN 유지 / v1 호환).
- **실제 Ollama 비교** (`gemma4:e4b`, 격리 인스턴스, 같은 26개 대상 — 새 설치 예제는 v1·v2 모두 같은 메타데이터, 같은 대상 선택 규칙. 모델 출력은 매번 달라 같은 v1 도 16장 실행과 결과가 달랐다) — **자동 검증 지표일 뿐 사람의 업무적 정확성 평가가 아니다**

| 항목 | v1 | v2 |
|---|---|---|
| 생성 성공 | 26/26 | 26/26 |
| 근거 검증 오류가 있는 제안 | 0 (16장의 v1 실행에서는 3) | 0 |
| 최종 문구의 자기 평가 (같은 검사기를 적용) | 2 — "(Metadata에 의해 확인됨)", "(VERIFIED)" | 0 |
| 최종 문구의 내부 ID (같은 검사기, 근거 필드의 ID 기준) | 1 — "termId: 7", "domainId: 13" | 0 |
| 재요청한 제안 | 0 (v1 에는 문구 검사 없음) | 3 — 내부 ID 1, 형식 2. 모두 재요청 뒤 통과 |
| `unknowns` 가 빈 제안 | 13 | 11 |
| 확실성 VERIFIED/INFERRED/UNKNOWN | 26/0/0 | 25/1/0 |
| 평균 생성 시간 (실행 전체 ÷ 26) | 14.6초 | 23.8초 |

  - v1 은 문구 검사가 없어 걸린 문구가 그대로 저장되었고, v2 는 검사를 통과한 문구만 저장되었다. 표본이 26개 한 번씩이라 비율을 개선율로 일반화하지 않는다.
  - 비용 : 지시문이 길어지고 재요청이 생겨 생성 시간이 약 1.6배가 되었다.
  - 관찰 : v2 에서도 표준 용어의 사전적 정의를 옮긴 설명이 남아 있다("'바로잡아 고친 날짜와 시각'을 나타내는 것으로 제안됩니다") — 규칙 ⑪ 은 지시일 뿐 서버가 판정하지 않는다 (업무 의미인지는 사람이 판정). 확실성이 거의 모두 VERIFIED 인 것도 같다 — 확실성은 근거 존재로만 낮추므로 "근거가 있다" 는 뜻이지 업무 의미가 맞다는 뜻이 아니다.

## 18. 후속 : 사이드바 AI 관리 메뉴 (2026-11-15)

AI 화면을 한 대메뉴로 모았다. 화면·기능·API 는 그대로이고 사이드바 구조와 노출 조건만 바꿨다.

- **전** : 시스템 관리 › AI Provider 관리(`/aiProvider`, ADMIN), 데이터 사전 › AI 설명 제안(`/aiDictionary`, VIEWER — 품질 평가는 그 안의 탭 `?tab=evaluation`), 오른쪽 아래 AI 도우미.
- **후** : 맨 아래 대메뉴 **AI 관리**(Sparkles 아이콘) — AI Provider 설정(`/aiProvider`, ADMIN) · AI 설명 제안(`/aiDictionary`, VIEWER) · AI 품질 평가(`/aiEvaluation`, VIEWER, 새 메뉴 ID `ai-evaluation`). 시스템 관리·데이터 사전에서 AI 메뉴를 뺐다. 다른 그룹·순서는 그대로 (운영 → 시스템 → 데이터 사전 → 논리 → 물리 → 원본 DB → 데이터 품질 → AI 관리).
- **역할(서버)** : `MenuAuthorization.MENU_GROUPS` 에 `ai-management` = [`ai-provider`, `ai-dictionary`, `ai-evaluation`] — `/api/auth/me` 의 menus 가 역할로 보일 수 있는 메뉴를 정한다 (그룹은 보이는 하위 메뉴가 있을 때만). API 권한(`@PreAuthorize`)과 서비스 검증은 바꾸지 않았다.
- **기본 Provider 상태(화면)** : `hooks/useAiStatus` 가 `/api/ai/metadata/status`(VIEWER, AI 서버에 연결하지 않고 DB 설정만 본다)를 읽는다. 사이드바·AI 도우미·AI 화면 안내가 같은 상태(같은 질의 키)를 쓰고, AI Provider 설정 화면에서 바꾸면 무효화한다.
  - 메뉴 항목의 `requires: 'ai'` (설명 제안·품질 평가) : 기본 Provider 가 **정해져 있으면**(READY·DISABLED·MODEL_MISSING·UNSUPPORTED) 보이고 NOT_CONFIGURED 면 숨긴다. 정해져 있지만 쓸 수 없는 상태도 보이는 것은 기존 제안·검토 이력·평가를 보게 하려는 것 — 화면 위에 상태별 안내("새 AI 초안은 만들 수 없고, 기존 … 볼 수 있습니다")를 띄우고 생성은 서버가 상태별 오류로 거절한다(AI-3 계약 그대로).
  - AI 서버 일시 장애는 상태를 바꾸지 않는다(READY 유지) → 메뉴·조회 그대로, 생성만 오류.
  - AI 도우미는 기존 규칙 그대로 READY 일 때만 (AI-4).
  - 숨김은 편의다 : 경로 가드(`RequireMenu`)는 역할 기준이라 기본 Provider 가 없어도 `/aiDictionary`·`/aiEvaluation` 직접 주소와 테이블·ERD 의 "AI 설명 제안으로" 링크는 열린다(안내 표시). 프로젝트별 AI 사용 설정은 없다.

| 상태 | ADMIN | USER (VIEWER 도 같다, 작성 버튼만 역할대로) |
|---|---|---|
| NOT_CONFIGURED | AI 관리 › Provider 설정만 | AI 관리 없음 |
| READY | 3개 + AI 도우미 | 설명 제안·품질 평가 + AI 도우미 |
| READY + AI 서버 장애 | 3개 (조회 정상, 생성 오류) | 2개 (조회 정상, 생성 오류) |
| DISABLED·MODEL_MISSING·UNSUPPORTED | 3개 + 화면 안내, 도우미 없음 | 2개 + 화면 안내, 도우미 없음 |
| NOT_APPROVE | AI 관리 없음 | — |

- **라우트** : `/aiProvider`·`/aiDictionary` 그대로, `/aiEvaluation` 새로(기존 `EvaluationPanel` 재사용, `AiEvaluationPage` 는 머리말·안내만). 예전 `/aiDictionary?tab=evaluation` → `/aiEvaluation`, `/dataDictionary?tab=aiDescriptions` → `/aiDictionary`. AI 설명 제안 화면의 탭은 없앴다(두 화면이 따로 메뉴가 되었다). 경로가 달라 활성 메뉴가 정확하다.
- **검증 (헤드리스 Chrome, 격리 인스턴스)** : ADMIN·USER·NOT_APPROVE × NOT_CONFIGURED·READY·AI 서버 장애(닫힌 포트)·DISABLED·기본 해제 후 직접 주소 — 위 표대로. 메뉴 클릭 3개·직접 주소·새로 고침·예전 주소 2개의 경로와 활성 메뉴, 접힌 사이드바에서 그룹을 누르면 펼쳐지며 열림, 한국어·영어 × 라이트·다크 × 데스크톱·모바일(가로 넘침·콘솔 오류 없음). 장애 중 USER 의 목록·지표 API 200, 생성은 명확한 오류, USER 의 Provider API 403.
- **전체 선택** (사용자 요청) : 제안 목록의 '이 페이지 전체 선택'(일괄 처리할 수 있는 검토 대기·검토 중·보류 제안만), 대상 선택 표와 품질 평가 후보 표의 머리글 체크박스(보이는 행, 평가 후보는 등록 안 된 것만). 일부만 고르면 중간 상태. 일괄 개수 제한(100)은 서버가 그대로 확인한다.
- **안내 공지** (사용자 결정) : `V3__notice_ai_menu.sql` 이 공지(PST_SN 1)의 "11. AI 기능" 절만 AI 관리 메뉴 문구(AI Provider 설정·AI 설명 제안·AI 품질 평가, 기본 Provider 가 없으면 AI 관리는 관리자에게만)로 바꾼다. `replace` 라 그 절이 예전 문구일 때만 바뀌고(고친 공지·다른 게시물은 그대로), 영어판(`EnglishMessages`)은 새 본문을 키로 한다. 검증 : `NoticeAiMenuMigrationTest`(H2·PostgreSQL, V2 → V3, 앞뒤 본문 보존, 고친 공지 그대로), `V0SeedMigrationTest`.

## 19. 후속 : 업무 설명 직접 관리 · 생성 대상 선택 · 재검토 해결 (2026-11-15)

업무 설명을 직접 쓰는 것부터 AI 초안 생성·검토·재검토 해결까지 이어지게 보완했다. 기존 AI-5 서비스(생성·검토·재검증 비교·대량 실행)를 그대로 쓰고 빈 곳만 채웠다. DB 마이그레이션 없음.

### 19.1 업무 설명 직접 관리 (AI 없이)

- **화면** : 업무 설명 칸(`BizDescriptionInfo` — 테이블 상세, 컬럼 상세, 물리 ERD 패널·컬럼 상세)에 작성·수정·지우기·취소. 버튼 `biz-description-edit`(USER). AI Provider 와 무관하게 동작한다.
- **API** `PUT /api/bizDescription {tableId, columnId?, bizDescription, base}` (USER, `BizDescriptionService`) : 행을 잠그고(`findForUpdate` + refresh) 화면이 읽은 값(base, 필수)과 지금 값을 비교 — 그 사이 다른 사용자나 AI 제안 승인이 바꿨으면 409 `BIZ_DESCRIPTION_CONFLICT`(같은 값으로 바꾸는 것은 충돌 아님). 업무 설명만 쓴다(`@DynamicUpdate`), 객체 승인 여부와 무관(AI 제안 승인과 같은 결정). 빈 값 = 지움.
- **AI 승인과의 관계** : AI 승인은 대상 행을 잠그고 생성 때 업무 설명과 비교하므로 사람이 고친 뒤의 오래된 제안은 `BIZ_DESCRIPTION_CHANGED` 로 재검토 필요가 되고 덮어쓰지 않는다. 반대로 승인 뒤 오래된 화면으로 고치면 409. 원본 최신화·가져오기는 업무 설명을 바꾸지 않는다(15·14장).
- **변경 이력** : 모델 변경 이력(`TB_MDL_CHG_HSTRY`)의 `bizDescription` 변경 전후·사용자·시각에 출처 `CHG_SRC_CD` 를 나눈다 — 직접 수정 `USER`, AI 제안 승인 `AI_APPROVAL`(새 값, `ModelChangeRecorder.markAiApproval`, 문자열 칸이라 마이그레이션 없음). 모델 변경 이력 화면에 "AI 제안 승인" 으로 보인다. 제안 쪽 이력(승인·반려 등)은 따로 그대로.

### 19.2 생성 대상 선택

- **판정** (`AiDescriptionJobService.resolve`, 미리 보기와 실행이 같이 쓴다) : `SELECTED` 에 `tables`(고른 테이블 자신, 기본 true) · `columns`(그 테이블의 컬럼도) · `onlyMissing`(업무 설명 있는 대상 제외). 고른 컬럼은 그 컬럼만. 중복 제거. 대상마다 CREATE · OPEN_PROPOSAL(검토 대기 제안 있음 — 실행 때 건너뜀, 기존 정책) · DESCRIBED(제외). 최대 100(기존).
- **미리 보기** `POST /api/ai/dictionary/jobs/preview` (USER — 생성 화면용, 아무것도 만들지 않음) : 대상 목록과 생성·건너뜀·제외 수.
- **화면** (`TargetPicker`) : 테이블 목록(검색, 업무 설명이 빠진 테이블만 — 테이블에 설명이 있어도 설명 없는 컬럼이 있으면 남는다, 30개씩) — 행마다 선택 체크와 **펼치기(따로)**, 컬럼 수·업무 설명 없는 컬럼 수, 최근 제안. 옵션 ☑ 테이블 업무 설명 생성 ☑ 설명이 없는 컬럼 함께 생성 ☐ 업무 설명이 있는 대상도 다시 만들기. "실제 생성 대상 n개 · 건너뜀 · 제외" 와 목록. 하나면 바로 만들어 상세를 열고, 여럿이면 대량 실행. 테이블 설명이 있으면 기본으로 컬럼만 만든다.
- 만든 제안은 대상마다 따로 검토한다. 한 대상이 실패해도 나머지는 남는다(기존 대량 실행).

### 19.3 대상 상세

- `GET /api/ai/dictionary/targets` : 목록은 개수만(설명 본문 없음, 테이블별 컬럼 수는 집계 쿼리 한 번), `?tableId=` 는 펼쳤을 때 한 번(캐시 30초) — 테이블 : 스키마·물리명·테이블 코멘트(논리명)·물리 모델 설명(`TBL_EXPLN`)·원본 COMMENT·업무 설명·최근 제안·컬럼 수·업무 설명 없는 컬럼 수, 컬럼 : 물리명·표준 용어명(논리명)·타입(길이/정밀도)·PK·FK(FK 관계의 원본 컬럼 또는 참조 테이블명)·표준 용어·도메인·물리 모델 설명(`COL_EXPLN`)·원본 COMMENT·업무 설명·최근 제안. 없는 설명은 "설명 없음".

### 19.4 재검토 필요(REVIEW_REQUIRED) 해결

- **전환 조건 (코드 기준)** : ① 생성 중 대상이 바뀜(생성 시점) ② 승인 시 비교 ③ "근거 변경 확인"(검토 대기 제안). 비교 결과 종류 : `TARGET_DELETED`(대상 삭제) · `OBJECT_DELETED`(근거 객체 삭제) · `MODIFIED`(근거 물리 객체의 필드 변경 — 변경 이력에서, 업무 설명·승인 여부·정렬 순서 제외) · `MODIFIED_UNKNOWN`(수정 일시는 바뀌었는데 이력이 없음, 표준 용어·단어·도메인·스키마 근거의 변경) · `BIZ_DESCRIPTION_CHANGED`(생성 뒤 업무 설명 수정) · `SOURCE_CHANGED`(매핑된 원본의 근거 컬럼 타입·NULL·COMMENT, 테이블 COMMENT) · `SOURCE_TABLE_MISSING`(원본 테이블·연결을 찾을 수 없음). 표준 용어 "연결" 변경은 컬럼의 `standardTerms` 필드 변경으로 `MODIFIED` 에 들어간다.
- **상세 화면** : 재검토 패널 — 기록된 사유(전환 때 남긴 종류), 전환 시각·처리자, 제안 생성 시각, 지금 남은 변경(변경 전후 값, 볼 때마다 다시 계산), 할 수 있는 조치. 목록은 빠른 필터(전체·검토 대기·보류·재검토 필요)와 재검토 사유 한 줄.
- **근거 재검증** `POST …/proposals/{id}/revalidate {version, acknowledge, reason}` (USER + 승인 권한, AI 서버를 부르지 않음) : 승인과 같은 비교를 다시 한다.
  - 남은 변경이 없으면 → 검토 중(REVIEWED), 이력 `REVALIDATE`. 승인은 따로(자동 승인 없음).
  - 내용 변경뿐(`MODIFIED`·`MODIFIED_UNKNOWN`·`SOURCE_CHANGED`)이면 그대로 두고 "확인 가능" 을 알린다 → 검토자가 변경을 보고 사유와 함께 확인(acknowledge)하면 **기준 버전을 지금으로 옮겨**(`ProposalVersions.rebase` : 같은 근거 객체의 지금 버전·이력 ID·원본 값, 지금 업무 설명) 검토 중으로. 그 뒤 또 바뀌면 승인 때 다시 걸린다.
  - `TARGET_DELETED`·`OBJECT_DELETED`·`SOURCE_TABLE_MISSING`·`BIZ_DESCRIPTION_CHANGED` 가 있으면 확인으로도 풀 수 없다 → 새 초안 또는 종결.
- **새 초안 생성** : 기존 재생성(USER, AI 필요). 앞 제안은 SUPERSEDED 로 남고 이력으로 잇는다 — 앞 제안 `SUPERSEDE` "새 제안 #n 으로 대체", 새 제안 `GENERATE` "제안 #m 을 대체". 실패하면 앞 제안·업무 설명 그대로(저장은 성공한 뒤에만).
- **종결** `POST …/proposals/{id}/close {version, reason}` (USER + 승인 권한, 사유 필수) : 검토 대기·검토 중·보류·재검토 필요 → 새 상태 **CLOSED**(종결 · 적용하지 않음, 끝 상태). 업무 설명 불변. 반려(제안이 틀림)와 구분 — 품질 지표의 승인율·반려율 분모에 넣지 않고 `closed` 로 따로 센다. 품질 평가 판정(16장)은 건드리지 않는다.
- AI Provider 가 없거나 멈춰도 조회·재검토 사유 확인·근거 재검증·종결·업무 설명 직접 수정은 된다. 새 초안만 Provider 상태에 따른다(버튼 비활성 + 서버 오류).

### 19.5 권한

| 기능 | 권한 |
|---|---|
| 업무 설명 직접 수정 | USER (`biz-description-edit`, 테이블·컬럼 편집과 같은 역할) |
| 대상 목록·상세 | VIEWER |
| 생성 대상 미리 보기 | USER (생성 화면용) |
| 초안 생성·새 초안 | USER (`ai-dictionary-generate`) |
| 근거 재검증·종결·승인 | USER + 승인 권한(ADMIN·MANAGER·프로젝트 관리자, 서비스가 DB 에서 확인) |

### 19.6 검증

- 자동화 `AiDictionaryWorkflowIntegrationTest` 5개 (직접 관리 — AI 없이 테이블·컬럼 작성·수정·삭제, 승인된 객체, 동시 수정 409, 다른 테이블 컬럼·기준 값 없음 거부, VIEWER 403, 이력 출처 AI_APPROVAL → USER, 직접 수정 뒤 오래된 제안 승인 → 재검토 필요·덮어쓰지 않음 / 대상 판정 — 테이블+누락 컬럼, 테이블 설명 있으면 컬럼만, 테이블만·컬럼만, 고른 컬럼과 중복 제거, 다시 만들기, 검토 대기 건너뜀, 실행이 미리 보기와 같음 / 대상 상세 / 재검증 — 승인 권한, 확인 가능·사유 필수·기준 이동 뒤 승인, 업무 설명 수정은 확인 불가, 원인이 없어진 제안은 바로 검토 중, AI 없이 / 종결·재생성 — 사유·권한, 업무 설명 불변, 승인 불가, 지표 분리, 재생성 이력 연결, 재생성 실패 시 앞 제안 그대로). `SmokeTest` 에 HTTP 바인딩(미리 보기·종결·재검증·업무 설명 수정 409/저장/되돌림).
- **실제 브라우저 + 실제 Ollama** (격리 인스턴스, 헤드리스 Chrome, gemma4:e4b) : ① `TB_AI_STNG` 검색 → 펼치기(선택 안 됨) → 테이블·컬럼 6개 상세("설명 없음" 표시) ② 테이블 선택 → 미리 보기 "실제 생성 대상 7개"(테이블 + 컬럼 6) → 초안 만들기 → 대량 실행 7/7 ③ 그 사이 API 로 한 컬럼의 물리 설명을 바꾸고(승인 취소 + 일괄 편집) 다른 컬럼의 업무 설명을 직접 씀 ④ "이 페이지 전체 선택" → 일괄 승인 → 4 승인, 재검토 필요 3(직접 쓴 업무 설명 보존, 그 컬럼을 근거로 든 테이블 제안 포함) ⑤ 테이블 관리 상세에서 업무 설명 직접 작성 ⑥ 물리 설명이 바뀐 제안 : 재검토 패널(사유·변경 전후) → 근거 재검증("확인 가능") → 사유 입력 → 변경 확인 후 계속 검토 → 검토 중 ⑦ 업무 설명을 직접 쓴 제안 : 근거 재검증("검토할 수 없음") → 새 초안 생성(실제 Ollama) → 새 제안 #8, 앞 제안 #4 대체됨 — 이력 연결, 직접 쓴 업무 설명 그대로 ⑧ 한국어·영어 × 라이트·다크 × 데스크톱·모바일 : 가로 넘침·콘솔 오류 없음(모바일에서 펼친 상세의 긴 설명이 잘리던 것을 고침). 재검토 원인은 이 브라우저 흐름에서는 원본 DB 대신 물리 모델 변경으로 만들었다 — 원본 COMMENT 변경 경로는 14장의 실제 원본 DB 검증과 자동화로 확인.


## 20. 후속 : 승인 시 기존 업무 설명 보호 · 표준 용어 연결 변경 사유 (2026-11-15)

### 20.1 분석 결과

- 승인(`AiDescriptionReviewService.approve`)은 이미 한 트랜잭션에서 제안 행 잠금(VER_NO 비교) → 대상 행 잠금(`findForUpdate`) → 생성 때 기준 업무 설명과 잠근 행의 값 비교 → 근거 재검증 → `TASK_EXPLN` 반영·상태·이력 순서다. 기준값이 다르면 반영하지 않고 REVIEW_REQUIRED(`BIZ_DESCRIPTION_CHANGED`, 기준 → 지금 값)로 바꾼다. 수동 수정(`BizDescriptionService`)도 같은 행을 잠그므로 둘은 줄을 선다. REVIEW_REQUIRED 는 승인할 수 없는 상태이고 `BIZ_DESCRIPTION_CHANGED` 는 재검증 확인(acknowledge)으로도 풀 수 없다(`BLOCKING`) — 다시 누르는 것으로 우회되지 않는다. 일괄 승인은 항목마다 같은 `approve` 를 따로의 트랜잭션으로 부른다. 승인 로직은 바꾸지 않았다.
- **고친 문제 하나** : 생성(`AiDescriptionDraftService.generate`)이 기준 업무 설명을 AI 응답을 받은 **뒤** 다시 읽었다. AI 를 기다리는 동안(수 초~수 분) 다른 사용자가 고치면 그 새 값이 기준이 되어, 그 수정을 모르는 제안이 승인으로 덮어쓸 수 있었다. 이제 기준값은 AI 를 부르기 전(대상 확인 때) 읽은 값이고, 저장 직전에 지금 값과 다르면 제안은 처음부터 REVIEW_REQUIRED("생성 중 대상이 바뀌었습니다")다. 재생성도 같은 경로라 앞 제안의 기준값을 물려받지 않는다.

### 20.2 화면

- 상세 : 현재 업무 설명이 있고 승인할 문구와 다르면 "승인하면 현재 업무 설명이 AI 제안 설명으로 교체됩니다" 안내와 함께 버튼이 "승인 및 교체"(고쳤으면 "수정 후 승인 및 교체"). 같은 문구면 "승인". 미리 보기 변경에 `BIZ_DESCRIPTION_CHANGED` 가 있으면 생성 당시 업무 설명 · 현재 업무 설명 · AI 제안 설명과 충돌 이유를 보이고 승인 버튼을 막으며 새 초안 생성을 안내한다 (서버도 막는다).
- 목록 · 일괄 승인 : 교체 대상에 "기존 설명 교체" 표시, 선택 줄에 교체 건수, 확인 대화상자에 교체 건수와 "생성 뒤 바뀐 제안은 재검토 필요로" 안내. 결과는 승인 · 재검토 필요 · 실패 건수를 따로 보인다.
- API 추가 필드 (기존 필드·요청은 그대로) : `ProposalSummary.replacesBizDescription`(생성 때 이 제안과 다른 업무 설명이 있었다), `ProposalDetail.baseBizDescription`(생성 때 기준값 — 저장 구조는 기존 `BASE_VERSIONS` JSON 그대로, 마이그레이션 없음).

### 20.3 표준 용어 연결 변경 (`TERM_LINK_CHANGED`)

- `ProposalVersions` 가 근거 컬럼(대상 포함)의 변경 이력에서 바뀐 필드가 정확히 `standardTermsId`/`standardTermsName`(모델 변경 이력이 연관을 남기는 키)이면 그 필드만 `TERM_LINK_CHANGED` 로, 나머지 필드는 기존 `MODIFIED` 로 나눠 준다. 변경 전후 용어 이름은 이력에 커밋 때 남은 이름이고, 없으면 만들어 보이지 않는다 ("확인할 수 없음"). `Change.changedAt` 은 그 필드가 마지막으로 바뀐 이력 시각.
- 이력이 없으면(수정 일시만 바뀜) 기존 `MODIFIED_UNKNOWN` 그대로. 재검증에서는 내용 변경으로 보아 확인(acknowledge)으로 검토 중으로 돌릴 수 있다 (`BLOCKING` 아님). 변경은 저장하지 않고 매번 계산하므로 예전 제안에도 적용되고, 이미 저장된 재검토 사유 문구(예: `MODIFIED … [standardTermsId, …]`)는 그대로 남는다.

### 20.4 검증

- `AiDictionaryApprovalSafetyIntegrationTest` : 업무 설명 없음 승인 · 기존 설명 교체 승인(이력 AI_APPROVAL, 변경 전 값) · 생성 뒤 수동 수정 차단(재승인·확인 우회 불가, 제안 이력 GENERATE→REVIEW_REQUIRED) · 수동 수정 뒤 새 초안 승인 · 재생성 뒤 다시 수정 차단 · **생성 중 수정 차단**(고치기 전 코드에서는 실패함을 확인) · 두 승인자 동시 승인 · 수동 저장과 승인 경합(3회) · 일괄 승인 일부 충돌 · 표준 용어 연결 변경(전후 이름·시점·다른 필드와 분리·해제) · 알 수 없는 변경은 MODIFIED_UNKNOWN.
- 후속 과제(이번 범위 밖) : AI 대상 선택 목록의 서버 500개 제한 — 서버 페이지네이션·검색·전체 건수. AI-5 업무 정확성 평가는 기준 데이터가 없어 "일부 완료" 그대로.

## 21. 후속 : 품질 평가 화면 대상 조회 · 실제 모델 예비 평가 (AI-5-EVAL, 2026-11-15)

### 21.1 평가 화면의 테이블 고르기 (500개 제한 해소)

- 원인 : `EvaluationPanel` 의 테이블 드롭다운이 쪽 없는 `GET /api/ai/dictionary/targets`(tableId 없음 → 이름 순 앞 500개, `AiDictionaryQueryService.TARGET_LIST_MAX`)를 썼다. 501번째 이후 테이블은 평가 대상으로 고를 수 없었다. 이 호출의 화면 쪽 사용처는 이것 하나였다.
- 변경 : `EvaluationTablePicker` 가 AI-5-PAGE 의 `GET /targets/tables`(30개씩, 이름·테이블 코멘트 부분 일치, LIKE 문자 그대로, 이름 → ID 순, 전체 건수, VIEWER·프로젝트 범위)를 그대로 쓴다. 검색 0.3초 지연, 처음·이전·다음·마지막, 건수 표시, 목록 실패 안내. 한 테이블만 고르며(예전과 같음 — 여러 테이블·전체 선택은 만들지 않음) 고른 테이블은 이름과 함께 따로 기억해 검색어·쪽이 바뀌어도 남는다. 프로젝트가 바뀌면 `AiEvaluationPage` 가 `key={projectId}` 로 화면을 새로 만든다. 고른 뒤의 평가 후보(`/evaluation/candidates?tableId=`)·등록·상세·판정 API 와 권한은 그대로다.
- 유지 : `GET /targets`(tableId 없음)의 500개 동작과 계약은 바꾸지 않았다 (`AiDictionaryTargetPageIntegrationTest` 가 여전히 확인). 화면에서는 더 이상 쓰지 않는다.
- 같이 고친 것 (평가 화면 표시) : 판정 양식의 숫자 칸(검토·직접 작성 시간)이 400px 높이로 그려지던 문제 — 세로 flex 인 `.ai-dict-field` 안에서 공통 `.search-input` 의 `flex: 0 1 400px` 가 높이가 됐다 (`flex: none`). 지표 카드의 범주가 없을 때 끝에 남던 "·".
- 테스트 : `AiDictionaryTargetPageIntegrationTest.evaluationBeyondOldLimit` — 1,052개 프로젝트에서 이름 순 1,001번째 테이블을 쪽 목록 검색으로 찾고(예전 목록에는 없음), 평가 후보 → 등록(VIEWER 거부) → 목록 → 상세, 다른 프로젝트 테이블 ID 는 후보·등록 모두 거부. 성능은 `AiDictionaryTargetPagePerformanceTest`(opt-in)에 평가 화면 흐름(예전 드롭다운, 검색 → 후보, 후보만)을 더했다.
- 성능 (2026-11-15 측정, 테이블마다 컬럼 5개, 워밍업 1 + 7회 평균 ms, 괄호는 요청당 SQL 문 수. PostgreSQL 은 빈 격리 18.6 컨테이너)

| 테이블 수 | DB | 첫 쪽 | 검색 (뒤쪽 1건) | 마지막 쪽 | 검색 → 평가 후보 | 예전 드롭다운 (500) |
|---|---|---|---|---|---|---|
| 30 | H2 | 8.15 (8) | 3.53 (7) | 5.57 (8) | 5.07 (18) | 2.56 (7) |
| 500 | H2 | 2.76 (8) | 1.81 (7) | 1.92 (7) | 3.27 (18) | 14.30 (8) |
| 1,000 | H2 | 4.35 (8) | 1.44 (7) | 1.32 (7) | 2.59 (18) | 12.29 (8) |
| 3,000 | H2 | 2.03 (8) | 1.52 (7) | 2.22 (8) | 2.28 (18) | 9.99 (8) |
| 30 | PostgreSQL | 13.73 (8) | 7.13 (7) | 7.84 (8) | 12.29 (18) | 5.80 (7) |
| 500 | PostgreSQL | 8.14 (8) | 4.69 (7) | 4.69 (7) | 10.70 (18) | 9.52 (8) |
| 1,000 | PostgreSQL | 6.39 (8) | 4.25 (7) | 4.27 (7) | 8.65 (18) | 8.39 (8) |
| 3,000 | PostgreSQL | 9.98 (8) | 5.10 (7) | 9.93 (8) | 9.44 (18) | 9.56 (8) |

  - 쪽 목록은 테이블 수와 거의 무관하고(요청당 SQL 7~8), 전체 건수는 모든 경우 실제 건수와 같았다. 예전 드롭다운은 500개를 모두 읽어 요청당 할당이 약 2.2~2.9MB 다 (쪽 목록 약 0.3~0.9MB). 30개 열의 PostgreSQL 수치가 큰 것은 첫 측정의 준비 비용이 섞인 것으로 보인다. `TB_TBL (PRJ_SN, TBL_NM)` 인덱스는 추가하지 않았다 — 3,000개에서 병목이 보이지 않는다.
  - 평가 후보(`/evaluation/candidates?tableId=`)는 고른 테이블만 보지만 등록 여부·제안 수를 위해 **프로젝트의 평가 대상·제안 전체**를 읽는다. 측정 데이터에는 제안이 없어 이 비용이 드러나지 않았다 — 제안이 수만 건인 프로젝트에서는 확인이 필요하다 (pending-work [AI-5-EVAL]).

### 21.2 실제 모델 예비 평가 — 방법

- **이 평가의 판정자는 사람(업무 담당자)이 아니라 작업자(AI 코딩 도우미)다.** 16장의 원칙(사람이 확인한 실제 의미·판정만 지표로 쓴다)은 그대로이고, 아래 판정은 **사람 판정의 대체가 아닌 예비 검토**다. 실제 의미는 작업자가 지어내지 않고 Lexorma 소스(엔티티·enum·서비스 코드와 주석)에서 확인한 것만 적었고 대상마다 출처를 달았다 — 사람이 같은 출처로 다시 확인할 수 있다. 판정 값은 격리 인스턴스에만 기록했고 검증 뒤 지웠다.
- 환경 : 격리 인스턴스(H2 파일 DB, 포트 19941, 이번 실행만 `lexorma.external.http.response-timeout-seconds=600`), 실제 Ollama `gemma4:e4b`(로컬, 7.5B Q4_K_M), 프롬프트 `dictionary-draft/2`, 생성 방식 SERVER_CONTEXT. 2026-11-15 20:05 ~ 20:12.
- 대상 (LEXORMA 예제 = Lexorma 자체 스키마, 20개 : 테이블 4 · 컬럼 16) : `TB_PRJ_MBR`·`TB_LOG`·`TB_TBL_REL`·`TB_SRC_IMPRT_EXCN`, 식별자(`TB_ACNT.ACNT_SN`), FK(`TB_PRJ_MBR.PRJ_SN`), 코드(`TB_PRJ.DB_TYPE_CD`·`TB_TBL_REL.REL_TYPE_CD`·`DEL_RULE_CD`·`TB_AI_EXPLN_SGSTN.RVW_STTS_CD`·`TB_LOG.RSPNS_STTS_CD`), 여부(`TB_ACNT.PSWD_CHG_YN`·`TB_PRJ.AUTO_APRV_YN`), 날짜·시간(`TB_LOG.LOG_DT`), 본문(`TB_LOG.DMND_CN`), 이름(`TB_ATCH_FILE.FILE_NM`·`ORGNL_FILE_NM`), 이름이 모호한 것(`TB_TBL_REL.DRVN_COL_SN`·`TB_AI_EXPLN_SGSTN.SGSTN_CYCL`·`TB_SRC_IMPRT_EXCN.RFLT_ORD`). 표본은 이름만으로 뜻이 갈리는 것·코드·관계를 일부러 넣었다 (쉬운 것만 고르지 않음).
- 근거 조건을 바꾼 대상 (격리 DB 의 시험 데이터) : 원본 COMMENT 맞음 — `FILE_NM` 에 "서버에 저장한 파일 이름 (확장자 제외)", 원본 COMMENT 틀림(사례 F) — `ORGNL_FILE_NM` 에 "파일이 저장된 경로" (원본 COMMENT 는 가져오기로만 쓰이므로 H2 셸로 넣음), 확정 업무 설명 있음 — `RSPNS_STTS_CD` 에 업무 설명을 `PUT /api/bizDescription` 으로 넣고 다시 생성 (제안 21개). 그 밖은 예제 그대로(원본 COMMENT·업무 설명 없음, 모두 표준 용어 연결).
- 판정 : 기존 평가 기능의 항목을 그대로 썼다 — 업무적 정확성(맞음·일부 맞음·틀림·평가할 수 없음), 근거 없는 주장, 누락 정보, 불확실성 처리(적절·단정·과잉), 용어·도메인 추천, 수정 필요 부분. 새 점수·등급·테이블은 만들지 않았다.

### 21.3 결과 (판정 21건, 판정자 1 = 작업자)

| 기준 | 대상 | 적합 | 보완 필요 | 부적합 | 판정 불가 |
|---|---:|---:|---:|---:|---:|
| 업무 정확성 (맞음 / 일부 / 틀림 / 평가 불가) | 21 | 9 | 11 | 1 | 0 |
| 근거 적합성 (근거 없는 주장·출처 오기) | 21 | 17 | 2 | 2 | 0 |
| 정보 누락 (누락 정보 기록 여부) | 21 | 5 | 16 | 0 | 0 |
| 잘못된 추론 (불확실성 처리 단정·근거 없는 주장) | 21 | 16 | 3 | 2 | 0 |

- 분모는 판정한 제안 21건(선정 20개 대상 + 업무 설명을 넣고 다시 만든 1건). 판정 불가가 0 인 것은 모든 실제 의미를 소스에서 확인할 수 있었기 때문이다. 화면 지표(같은 데이터) : 정확성 42.9% (9/21), 일부 맞음 52.4% (11/21), 근거 없는 주장 9.5% (2/21), 허구 객체(서버 검증) 9.5% (2/21), 용어·도메인 추천 적절 17/17(추천이 있던 컬럼만), 불확실성 적절 76.2% (16/21) · 단정 4 · 과잉 1.
- **AI 확실성 ≠ 정확성** : 모델이 VERIFIED 라고 주장한 18건 중 11건이 일부 맞음·틀림이었다 (서버가 근거 검증으로 2건을 INFERRED 로 낮춤). 확실성은 "근거로 쓴 객체가 있다" 이상을 말하지 않는다.
- 잘 된 것 : 근거가 거의 없는 테이블(이름·코멘트뿐)에도 사실을 지어내지 않고 질문으로 남겼다. **틀린 원본 COMMENT**(`ORGNL_FILE_NM`)는 이름과 충돌한다고 밝히고 UNKNOWN 으로 두었다. 확정 업무 설명이 있으면(`RSPNS_STTS_CD` 2차) 그 문구를 그대로 썼다. 코드 값을 이름에서 지어낸 경우는 없었다.
- 생성 : 21건 모두 성공(재시도 0, 문구 검증 거절 0), 건당 6.8~23.2초(중앙 16.3초), 입력 약 3,000~5,000 토큰.

### 21.4 대표 문제 사례

| 대상 | AI 제안 (요지) | 확인된 근거 | 실제 의미 (출처) | 문제 유형 | 원인 후보 |
|---|---|---|---|---|---|
| `TB_TBL_REL.DRVN_COL_SN` | "파생 컬럼의 순차적인 번호를 기록" (VERIFIED) | 이름·용어 정의 "파생 컬럼 일련번호." 뿐, 관계 등록 없음 | REF_COLUMN 관계를 만든 컬럼의 ID (`TableRelation` 주석) | 업무 정확성(틀림)·잘못된 추론 | 근거 부족(의미가 코드 주석에만 있음) + 모델(이름 → '순번' 추론) |
| `TB_AI_EXPLN_SGSTN.RVW_STTS_CD` | 값 6개(PROPOSED…REVIEW_REQUIRED)를 가진다고 단정 | 표준 용어 `검토_상태_코드` 정의가 그 6개만 나열 | 8개 — SUPERSEDED·CLOSED 빠짐 (`ReviewStatus`) | 근거 데이터 오류 + 단정 | **근거 데이터**(예제 용어 정의가 낡음) + 모델(정의를 완전한 목록으로 씀) |
| `TB_ACNT.ACNT_SN` | "… (소스 DB COMMENT: 계정 일련번호)" | 원본 COMMENT 없음, 그 값은 컬럼 설명(COL_EXPLN) | 계정 대리 키 | 근거 출처 오기 | 프롬프트·맥락(컬럼 설명과 원본 COMMENT 표시가 비슷함) + 모델. 문구 검증이 출처 언급을 확인하지 않음 |
| `TB_ACNT.PSWD_CHG_YN`·`TB_LOG.DMND_CN`·`LOG_DT` 등 | 표준 용어 사전 정의를 그대로 옮김 ("비밀번호를 다르게 바꾸어 새롭게 고쳤는지 여부") + VERIFIED | 용어 정의 | 초기 비밀번호를 바꿨는지(false 면 로그인 차단) / 요청 본문(보호·마스킹) | 정보 누락·단정 | 모델(프롬프트 규칙 11 미준수) + 검증 로직(정의 복사를 잡지 않음) |
| `TB_PRJ_MBR.PRJ_SN` | "어떤 식별자로 쓰이는지 알 수 없다" | FK `FK_PRJ_MBR_02` 가 근거에 있음 | TB_PRJ 참조 FK | 과잉 불확실 | 모델 |
| 코드·여부 컬럼 다수 (`DB_TYPE_CD`·`REL_TYPE_CD`·`AUTO_APRV_YN`) | 타입·길이·NULL 만 다시 말함 ("관계 유형 코드.") + VERIFIED | 이름·타입·용어 정의(이름 반복) | 대상 DBMS / FK·LOGICAL / 가입 시 바로 활성 | 정보 누락 | 근거 데이터(용어 정의가 이름 반복) — 모델은 지어내지 않았다(적절) |

- 서버가 잡은 것 : 3건이 스키마 근거에 테이블 ID 를 넣었다 → NOT_FOUND 2·UNVERIFIABLE 1, 확실성 INFERRED 로 낮춤 (16장에서 본 유형과 같음).
- 재현 : 위 격리 환경에서 대상마다 `POST /api/ai/dictionary/proposals/generate {tableId, columnId, regenerate:true}` → `GET /proposals/{id}`. 모델 출력은 같은 입력에서도 달라질 수 있다.

### 21.5 개선 후보 (구현하지 않음 — 사용자와 정한다)

- 근거 데이터 : 예제 표준 용어 `검토_상태_코드` 정의에 SUPERSEDED·CLOSED 추가(시드 변경은 새 마이그레이션 필요). 코드성 용어 정의가 이름 반복("DB 유형 코드.")인 것을 값·의미로 보강.
- 프롬프트 : 컬럼 설명(DDL COMMENT/논리명)과 원본 COMMENT 를 다른 이름으로 구분해 보이기, 용어 사전 정의를 설명으로 옮기지 말라는 규칙(11)을 예시와 함께 강화. (바꾸면 `PROMPT_VERSION` 올림)
- 응답 검증 : 설명이 연결 용어의 정의와 같거나 거의 같으면 표시·재시도, 원본 COMMENT 를 언급했는데 대상에 원본 COMMENT 가 없으면 거절, 근거가 이름·용어 정의뿐이면 확실성 상한 INFERRED.
- 화면 : 평가 대상 상세에 그 제안의 근거 목록(검증 상태)을 함께 보이기.

### 21.6 한계

- 판정자가 작업자 1명이고 사람의 확인이 없다 — 업무 정확성 평가 **완료가 아니다**. 표본 20개(Lexorma 자체 스키마)로 실제 레거시 DB 의 품질을 일반화할 수 없다. 금액·통화, 표준 용어 없는 컬럼은 예제에 없어 빠졌다. 모델 1개(`gemma4:e4b`)만 봤다. 같은 입력에서도 출력이 달라질 수 있어 1회 생성 결과다.

## 22. 후속 : 근거 출처·예제 코드 용어 정의 정확성 (AI-5-QUAL-1, 2026-11-15)

21장 예비 평가에서 찾은 문제 중 근거 데이터·출처에 관한 두 가지를 확인하고 고쳤다. 21장의 판정 수치(21건)는 그대로 두며, 아래는 별도 기록이다.

### 22.1 원본 COMMENT 출처 오기 (`TB_ACNT.ACNT_SN`)

- 경로 확인 : 원본 COMMENT 는 `TB_COL.SRC_ANTN_CN`(가져오기만 씀), 업무 설명은 `TASK_EXPLN`, 컬럼 설명(DDL COMMENT)은 `COL_EXPLN`. 근거 수집 `MetadataTools.ColumnItem` 이 각각 `sourceComment`·`businessDescription`·`description` 으로 싣고(테이블은 `TableItem` 같은 방식), 모델 맥락은 이 JSON 을 그대로 넣는다(`AiDescriptionDraftService` 의 [1]~[3] 묶음). 제안 상세의 `current`(지금 값)와 화면 라벨(원본 COMMENT · 컬럼 설명 · 현재 업무 설명)도 같은 칸을 쓴다.
- 원인 : 예비 평가 당시 ACNT_SN 은 원본 COMMENT 가 없고 컬럼 설명 "계정 일련번호" 만 있었다. 서버가 보낸 근거는 `description` 이었고 `sourceComment` 는 없었다 — "(소스 DB COMMENT: 계정 일련번호)" 는 **모델이 만든 문구**다(저장·수집·맥락·화면 오류 아님). 맥락 안내가 "[1] … names, comments, … source database COMMENTs" 로 DDL COMMENT 와 원본 COMMENT 를 같은 낱말로 부르는 점이 혼동을 부를 수 있다 → AI-5-QUAL-2 후보(프롬프트 변경은 이번 범위 밖).
- 수정 : 서버·화면 코드는 바꾸지 않았다. 결정적 회귀 테스트 `AiEvidenceSourceIntegrationTest`(가짜 Ollama 로 모델에 실제로 보낸 요청 본문을 읽음) : 원본 COMMENT 만 / 업무 설명만 / 둘 다(다름) / 둘 다(같은 문자열) / 둘 다 없음 / 컬럼 설명만(ACNT_SN 조건) / 표준 용어 정의만 — 값이 제 칸에만 있고 없는 칸은 만들지 않으며, 용어 정의는 `searchStandardTerms` 결과에만 있다. 제안 상세 `current`·저장 칸도 같고, 모델이 "(소스 DB COMMENT: …)" 를 써도 원본 COMMENT·업무 설명은 바뀌지 않는다. `sourceComment`·`businessDescription` 매핑을 일부러 바꾸면 이 테스트가 실패함을 확인했다.

### 22.2 낡은 예제 코드 용어 정의 (`TB_AI_EXPLN_SGSTN.RVW_STTS_CD`)

- 실제 값 : `AiDescriptionProposal.ReviewStatus` = PROPOSED, REVIEWED, APPROVED, REJECTED, ON_HOLD, REVIEW_REQUIRED, SUPERSEDED(새 제안으로 대체), CLOSED(적용하지 않고 종결) — 8개, 끝 상태 APPROVED·REJECTED·SUPERSEDED·CLOSED. `AiDescriptionProposalHistory.Action` = GENERATE, REVIEW, APPROVE, APPROVE_EDITED, REJECT, HOLD, REVIEW_REQUIRED, RECHECK_FLAGGED, SUPERSEDE, REVALIDATE, CLOSE — 11개.
- 예제 정의(V1) : `검토_상태_코드` "검토 상태 : PROPOSED·REVIEWED·APPROVED·REJECTED·ON_HOLD·REVIEW_REQUIRED." (6개), `처리_유형_코드` "처리 유형 : GENERATE·APPROVE·APPROVE_EDITED·REJECT·HOLD·REVIEW·REVIEW_REQUIRED·SUPERSEDE." (8개). 19·20장에서 값이 늘 때 예제 정의를 고치지 않았다. 근거 수집은 정의를 그대로 실었다(누락 아님) — 모델은 받은 정의대로 6개라고 했다. V4(예제 업무 설명)가 이 컬럼의 업무 설명에는 8개를 적어, V4 뒤로는 근거 안에 8개(업무 설명)와 6개(용어 정의)가 함께 있었다.
- 수정 (사용자 결정 : V5 마이그레이션) : `V5__example_term_code_definitions.sql`(H2·PostgreSQL 같은 내용). 프로젝트 0 의 두 용어만, `TRM_NM`·`TRM_ENG_ABBR_NM` 이 맞고 `TRM_EXPLN` 이 V1 원문과 정확히 같을 때만 갱신(조건은 한 UPDATE 의 WHERE). 승인 여부·수정 일시는 그대로(V1·V4 와 같음 — 그래서 예전 정의를 근거로 만든 기존 제안이 재검토로 바뀌지 않는다). 새 정의는 "검토 상태 : PROPOSED(제안)·…·SUPERSEDED(새 제안으로 대체)·CLOSED(적용하지 않고 종결). 끝 상태는 APPROVED·REJECTED·SUPERSEDED·CLOSED." 와 "처리 유형 : GENERATE(생성)·…·RECHECK_FLAGGED(승인 뒤 근거 변경 표시)·SUPERSEDE(새 제안으로 대체)·REVALIDATE(근거 재검증)·CLOSE(종결)." — 변경 2행.
- 회귀 테스트 `ExampleTermCodeDefinitionTest` : 값 목록을 적은 예제 코드 용어(검토 상태·처리 유형·확실성·생성 방법·인덱스 출처·참조 유형·접속 유형·제공자 유형)의 정의에서 " : " 뒤 괄호 밖 대문자 값 집합 = enum 상수 집합(기대값은 enum 에서 읽는다, 빠진 값·없는 값 모두 실패). V5 안전성 : 이미 적용된 DB 에서 다시 실행하면 0행, V1 원문으로 되돌리면 프로젝트 0 만 갱신(다른 프로젝트의 같은 이름·약어·원문 용어는 그대로, 승인 여부·수정 일시 그대로), 사용자가 고친 정의는 그대로. `AiEvidenceSourceIntegrationTest.statusDefinitionReachesPrompt` : 그 정의를 쓰는 컬럼의 초안 맥락에 8개 상태가 모두 간다.
- 업그레이드 확인 : H2 파일 DB·PostgreSQL 18.6 에서 V4 까지(`spring.flyway.target=4`) → V5. PostgreSQL 은 V4 상태에서 프로젝트 0 의 `처리_유형_코드` 를 사용자 정의로 바꾸고 다른 프로젝트에 V1 원문 `검토_상태_코드` 를 넣은 뒤 올렸다 — 프로젝트 0 의 `검토_상태_코드` 만 갱신, 나머지 그대로. 재기동해도 V5 를 다시 적용하지 않는다.
- 범위 밖으로 남긴 것 : 예제 `실행_상태_코드` 정의("생성·실행 중·완료·실패·취소")가 AI 대량 생성 상태(PENDING·RUNNING·COMPLETED·PARTIAL_FAILED·FAILED·INTERRUPTED)와 다르다(원본 수집 실행과 함께 쓰는 용어, 한글 목록이라 비교 테스트 대상 아님) — pending-work [AI-5-QUAL]. 이전·이후_상태_코드, 삭제·수정_규칙_코드는 값 목록을 적지 않아 해당 없음.

### 22.3 실제 모델 보조 확인 (로컬 Ollama `gemma4:e4b`, `dictionary-draft/2`, 격리 인스턴스, 2026-11-15)

| 대상·조건 | 횟수 | 결과 |
|---|---:|---|
| ACNT_SN, V4 업무 설명 있음 | 2 | 원본 COMMENT 언급 없음 (1회는 업무 설명을 "(원문: …)" 으로 인용) |
| ACNT_SN, 업무 설명 비움 (21장 조건) | 2 | 원본 COMMENT 언급 없음, 둘 다 VERIFIED |
| RVW_STTS_CD, V5 정의 + V4 업무 설명 | 2 | 8개 상태 모두 |
| RVW_STTS_CD, 업무 설명 비움 (V5 정의만) | 2 | 8개 상태 모두 — 정의를 거의 그대로 옮기고 VERIFIED (규칙 11·VERIFIED 문제는 그대로) |
| FILE_NM, 원본 COMMENT ≠ 업무 설명 | 1 | "(Source COMMENT: 첨부 파일 저장 이름)" — 실제 원본 COMMENT 를 맞게 인용 |

- LLM 응답은 비결정적이라 위는 1회성 관찰이다. 출처 오기가 다시 나오지 않았다고 해결됐다고 보지 않는다 — 결정적인 보장은 서버 근거(22.1·22.2 테스트)까지다. 모델이 출처를 지어내는 것을 막는 규칙·검증은 AI-5-QUAL-2.
- 사람의 업무 정확성 판정은 여전히 없다 (AI-5-EVAL).

### 22.4 V0·V1 두 번째 합침 (2026-11-15, 사용자 결정)

- 이 장의 V5 와 16장의 V2(품질 평가 테이블·예제)·18장의 V3(안내 공지 AI 절)·예제 업무 설명 V4 를 V0·V1 로 다시 합쳤다. V2 의 스키마는 V0 끝 'AI-5 품질 평가' 구간, 예제 행은 V1 끝 구간, V3·V5 는 V1 의 공지 본문·두 용어 정의에 최종 문구로 바로 넣었고, V4 는 V1 마지막 구간이다. 합치기 전후 H2 DB 의 데이터·스키마가 같고, PostgreSQL 에서도 같다(2026-11-16, 방법·결과는 completed-work.md 의 마이그레이션 항목).
- 그래서 V5 의 "V1 원문일 때만 갱신·재실행 안전" 은 더 이상 해당되지 않고 그 테스트도 없앴다. 예제 정의 ↔ enum 비교(`ExampleTermCodeDefinitionTest.definitionsMatchEnums`)는 그대로다. V2 이상으로 만든 DB 는 새로 만든다.

## 23. 후속 : 평가 신뢰성 보완 (AI-5-EVAL, 2026-11-16) — 사전 보완, 평가 미완료

실제 레거시 평가 전에 평가 기준이 흔들리는 경로를 막았다. 새 평가 시스템·테이블·마이그레이션은 없다. 확정 정책 D4·D5·D6·D1, D9(판정 변경 이력)는 제외. (D 번호는 이 작업 지시서의 결정 번호다 — 로드맵의 D-nn 과 다르다. D9 는 pending-work DEC-27)

### 23.1 분석 (작업 전 구조)

- 평가 대상 ↔ 제안은 FK 없이 (프로젝트, `TBL_SN`, `COL_SN`)으로 이어진다. 등록은 프로젝트 잠금으로 대상당 하나. 판정은 (제안, 검토자) 하나(`UX_AI_EXPLN_EVL_01`), 필수 칸(정확성·근거 없는 주장·불확실성·용어/도메인 추천)은 NOT NULL.
- `updateTarget`·`judge` 는 이미 평가 대상 행을 `PESSIMISTIC_WRITE` 로 잠갔다 — 그러나 판정 뒤 의미 문구는 고칠 수 있었고(확인 상태를 되돌리는 것만 막음), 판정은 화면이 본 의미와 비교하지 않았다.
- 승인(`AiDescriptionReviewService.approve`)은 제안 → 테이블·컬럼 행을 잠그고 `TASK_EXPLN` 을 쓴다. 평가와 무관하게 승인됐다. 평가 상세 API 는 의미 확인 전에도 제안 전문을 보냈고, 화면은 정확성 '맞음'·불확실성 '적절'·추천 '적절'을 미리 골랐다.

### 23.2 규칙

| 정책 | 서버 | 화면 |
|---|---|---|
| F1 판정 뒤 의미 고정 | 판정이 하나라도 있으면 의미 문구·확인 상태 변경 → `AI_EVALUATION_MEANING_LOCKED`(409). 같은 값(앞뒤 공백 차이 포함)·범주만 수정은 허용(확인한 사람·시각 그대로). 판정 요청은 `targetVersion` 필수, 다르면 `AI_EVALUATION_CONFLICT` | 판정이 있으면 의미 칸·상태 선택을 잠그고 안내 |
| 판정 대상 (사용자 결정) | CONFIRMED 대상만 판정 (미확인·확인 불가는 '평가할 수 없음'도 거부) | 블라인드라 양식이 없다 |
| F2 기본값 제거 | 필수 칸이 비면 거부 (기존) | 정확성·불확실성·용어·도메인 추천을 빈 값으로 시작, 모두 골라야 저장. 추천이 없으면 '추천 없음'(기존 규칙). 저장된 판정은 그대로 |
| F2 블라인드 | CONFIRMED 전 `blind=true`, 제안·판정을 비워 보냄. `metadata`(이름·코멘트/설명·타입·표준 용어·원본 COMMENT·업무 설명). 업무 설명이 승인된 AI 제안의 확정 문구와 같으면 `bizDescriptionFromAi`, 블라인드에는 값 대신 `bizDescriptionHidden` | 메타데이터와 "확인함으로 저장하면 제안 n개가 보인다" 안내 |
| F4 평가 중 승인 금지 | `ProposalEvaluationGate` : 평가 대상의 열린 제안은 CONFIRMED + 그 제안의 정확성 판정(맞음·일부·틀림)이 있어야 승인. 아니면 `AI_PROPOSAL_EVALUATION_PENDING`(409, 일괄은 항목 FAILED). 사유 `MEANING_PENDING`·`MEANING_UNCONFIRMABLE`·`NOT_JUDGED` | 일반 제안 상세(`evaluationHold`)·평가 상세(`approvalHold`)에 사유, 일반 화면은 승인 버튼 비활성 |

- `sameMetadata` 는 지표의 같은 조건 비교에만 쓰고 승인 조건이 아니다(사용자 결정). 대상 삭제·근거 변경은 기존 승인 재검증(`ProposalVersions`, REVIEW_REQUIRED)이 본다. 승인은 실제 의미·등록 시점 버전·판정을 바꾸지 않는다(테스트로 확인).
- ~~일반 제안 검토 API(`/api/ai/dictionary/proposals/{id}`)는 블라인드하지 않는다~~ → 2차 보완에서 일반 제안 조회도 같은 판단으로 가린다 ([24장](#24-후속--평가-신뢰성-2차-보완-ai-5-eval-2026-11-16--평가-미완료)).
- 반려·보류·종결·재검증은 제한하지 않는다. 평가 대상이 아닌 제안은 그대로.

### 23.3 동시성

- 의미 수정 ↔ 판정 : 둘 다 평가 대상 행 잠금. 수정이 먼저면 판정은 기다렸다가 버전이 달라 충돌, 판정이 먼저면 수정은 기다렸다가 판정이 있어 거부.
- 판정 ↔ 승인 : 승인은 제안 → 모델 대상 → **평가 대상** 순으로 잠근다(판정·의미 수정은 평가 대상만 잠그므로 순환 없음). 판정을 '평가할 수 없음'으로 고치는 트랜잭션이 커밋되면 기다리던 승인은 미완료로 보고 거부.
- ~~남은 틈 : 평가 대상 등록(프로젝트 잠금)과 승인이 겹치면 등록 직후 승인이 통과할 수 있다~~ → 2차 보완에서 등록이 승인과 같은 모델 대상 행을 잠가 줄 세운다 ([24.3](#243-등록--승인-동시성-eval-2-02)).

### 23.4 기존 데이터 호환

- 예전 규칙으로 미확인·확인 불가 대상에 저장된 '평가할 수 없음' 판정은 그대로 남고 지표의 '평가 불가'로 센다. 그 대상은 판정이 있으므로 실제 의미·확인 상태를 바꿀 수 없다. 시드에는 평가 행이 없다. 개발 DB 는 조회·변경하지 않았다.
- API : `JudgmentRequest.targetVersion` 추가(필수), `TargetDetail.blind`·`metadata`, `ProposalItem.approvalHold`, `ProposalDetail.evaluationHold` 추가.

### 23.5 검증

- 통합 테스트 `AiDictionaryEvaluationIntegrationTest` 8개 : 기준 데이터·판정·지표(갱신 : 미확인 판정 거부, 필수 칸 누락·버전 충돌, 판정 뒤 승인, 두 번째 검토자) + `meaningLockedAfterJudgment`·`concurrentMeaningAndJudgment`·`blindBeforeConfirmed`·`approvalNeedsCompletedEvaluation`·`concurrentJudgmentAndApproval`. `SmokeTest` 는 HTTP 응답의 `blind`·`metadata` 와 `targetVersion` 바인딩. 판정 값은 기능 검증용 테스트 데이터다.
- 전체 : H2 807건(실패 0, 건너뜀 33), PostgreSQL 18.6 807건(실패 0, 건너뜀 25 — 같은 컨테이너의 별도 빈 DB, 끝나고 삭제), `npm run typecheck`·`npm run build`.
- **E2E (격리 인스턴스, H2 파일 DB, 실제 Ollama `gemma4:e4b`, `dictionary-draft/2`) 34/34** : 대상 6개 — `TB_LOG`(테이블), `TB_LOG.LOG_DT`(날짜·시간), `TB_PRJ.DB_TYPE_CD`(코드), `TB_TBL_REL.DRVN_COL_SN`(이름이 모호, 확인 불가로 둠), `TB_PRJ_MBR.PRJ_SN`(FK 관계), `TB_ACNT.PSWD_CHG_YN`(등록 전 일반 제안을 승인해 업무 설명이 AI 에서 온 값). 후보 → 등록 → 제안 생성 → 실제 의미 입력 → 블라인드(API 직접 호출, AI 승인 업무 설명 가림) → 미확인 판정·승인 거부 → CONFIRMED 5·확인 불가 1 → 제안 공개 → 필수 칸 누락·버전 충돌 거부 → 판정 5건 → 의미·상태 수정 거부, 같은 값 허용 → 평가 불가·확인 불가 승인 거부, 일괄 승인(미완료 FAILED·완료 APPROVED), 완료 제안 승인과 기준 데이터 불변 → 지표(모델 1·프롬프트 1, 판정 5, 정확성 1/4, 평가 불가 1, 같은 메타데이터 5/5) → 재기동 뒤 지표·상세 동일. 실제 의미는 "[E2E 테스트용 고정 값 · 사람이 확인한 의미 아님]" 으로 시작하는 문구, 판정은 고정 값이다 — **사람의 판정이 아니며 지표로 보고하지 않는다.** 격리 데이터는 지웠다.
- 예제에 없어 뺀 범주 : 원본 COMMENT 있음/다름(`COMMENT_DIFFERS`), 표준 용어 미연결, 업무 설명 없음(`NAME_ONLY`) — 예제 테이블·컬럼은 모두 업무 설명이 있고 원본 COMMENT 가 없다(그래서 모두 `CLEAR`·`NO_SOURCE_COMMENT`). 금액(`AMOUNT`)도 없다.
- 못 한 것 : 브라우저 화면 확인 — Chrome 확장이 격리 인스턴스(`localhost`/`127.0.0.1`)를 오류 페이지로 열어 멈췄다. 빈 초기값·블라인드·수정 불가·승인 불가 안내는 코드·타입 검사로만 확인. → 2차 보완에서 원인(연결된 Chrome 이 다른 컴퓨터)을 찾고 로컬 headless Chrome 으로 확인 ([24.6](#246-브라우저-검증-eval-2-04)).

## 24. 후속 : 평가 신뢰성 2차 보완 (AI-5-EVAL, 2026-11-16) — 평가 미완료

1차 보완(23장) 뒤 남은 신뢰성 문제를 막았다. 스키마·마이그레이션·새 의존성·새 동시성 구조 없음. 23장의 확정 정책(판정 뒤 의미 고정, 필수 선택, CONFIRMED 만 판정, 평가 완료 전 승인 금지, `sameMetadata` 는 지표 조건, `ProposalVersions` 재검증 유지, 판정 변경 이력 제외)은 그대로다.

### 24.1 일반 검토 화면 블라인드 (EVAL-2-01)

- 판단 하나 : `ProposalEvaluationGate.blind` — 제안의 대상(프로젝트, 테이블, 컬럼)에 평가 대상이 있고 그 실제 의미가 CONFIRMED 가 아니면(미확인·확인 불가) 블라인드. 평가 상세(23장)도 같은 판단(`blind(target)`)을 쓴다. 클라이언트 값은 받지 않고 서버의 평가 대상 상태로만 정한다. 블라인드를 풀 수 있는 기능은 없다(실제 의미 확인뿐).
- 노출 경로 조사 : AI 제안 내용을 응답에 싣는 곳은 `ProposalMapper.summary`/`history` 를 쓰는 곳뿐이다 — 제안 목록(검색 조건 포함, `GET /proposals`), 상세(`GET /proposals/{id}` : 요약·`detail`·`evidence`·이력), 생성 응답(`POST /proposals/generate`), 검토 응답(승인·반려·보류·검토함·종결·근거 재검증). 일괄 검토·실행·대상 목록·테이블/컬럼 상태·지표는 제안 내용을 싣지 않는다(상태·ID·건수). 내보내기 API 는 없다. AI-4 메타데이터 도구는 제안 테이블을 읽지 않는다.
- 가리는 것 : 요약의 `certainty`·`proposedDescription`·`confirmedDescription`(null), `evidenceTotal`·`evidenceVerified`(0), `replacesBizDescription`(false), 상세의 `detail`(이름 해석·추천·연관·불확실한 부분·질문·사용량)·`evidence`(null), 이력의 `confirmedDescription`. 남기는 것 : ID·대상·차수·상태·모델·프롬프트 버전·생성 방법·검토자·사유·버전, 대상의 지금 메타데이터, 생성 뒤 변경, 승인 제한 사유. 응답에 `blind=true` 를 싣는다.
- AI 승인 업무 설명 : 블라인드이고 지금 업무 설명이 그 대상의 승인된 AI 제안 확정 문구와 같으면(`ProposalEvaluationGate.fromAi`, 평가 상세와 같은 규칙) 상세의 현재 업무 설명·생성 당시 업무 설명을 비우고 그 문구가 든 변경 전후 값은 뺀다(`bizDescriptionHidden=true`). 테이블·컬럼 관리 화면·ERD 의 업무 설명은 일반 메타데이터 조회라 그대로다.
- 화면 : 목록은 "평가 블라인드" 표시와 안내, 상세는 AI 제안 칸 자리에 안내("평가 대상의 실제 업무 의미가 확정되기 전까지 AI 제안 내용은 표시되지 않습니다.")·근거·추천·질문 칸을 그리지 않는다. 메타데이터·생성 정보·이력·승인 제한 사유는 보인다. 반려·보류·종결은 막지 않는다(정책 변경 없음).

### 24.2 '근거 없는 주장' 기본값 제거 (EVAL-2-03)

- 화면 : 체크 상자(기본 '없음') → 다른 필수 항목과 같은 선택 칸("선택하세요" / 있음 / 없음, 필수). 고르지 않으면 저장 버튼이 꺼진다. 저장된 판정은 그 값으로 채운다.
- 서버 : `unfoundedClaim` 은 이미 boxed 필수 값이었다(비면 `AI_EVALUATION_INVALID`) — 바꾸지 않았다. 저장된 판정은 그대로.

### 24.3 등록 · 승인 동시성 (EVAL-2-02)

- 분석 : 승인은 제안 → 모델 대상 행(테이블 대상은 `TB_TBL`, 컬럼 대상은 `TB_COL`) → 평가 대상 행을 잠근다. 평가 대상 행이 아직 없으면 잠글 행이 없어(조회 결과 없음) 등록과 줄 서지 못했다. 등록은 `ProjectWriteGuard.lock`(프로젝트 행 **공유** 잠금)만 잡아 PostgreSQL 에서는 등록끼리도 줄 서지 않았다 — 평가 대상 테이블에는 유일 제약이 없다(`IX_AI_EXPLN_EVL_TRGT_01` 은 일반 인덱스, 테이블 대상은 `COL_SN` 이 null). 그래서 (1) 등록이 먼저 커밋돼도 그보다 먼저 확인을 마친 승인이 뒤에 커밋할 수 있었고, (2) 같은 테이블 대상이 동시에 두 번 등록될 수 있었다(그러면 대상 하나를 찾는 조회가 여러 행에 실패한다).
- 해결 (기존 행 잠금, 최소 변경) : 등록은 요청 대상의 모델 대상 행을 ID 순(테이블 → 컬럼)으로 `PESSIMISTIC_WRITE` 로 잠근 **뒤에** 이미 등록된 대상을 읽는다. 승인이 잠그는 행과 같다.
  - 등록이 먼저 잠금 → 승인은 그 행을 기다렸다가, READ COMMITTED 로 커밋된 평가 대상을 보고 `AI_PROPOSAL_EVALUATION_PENDING`(일괄 승인도 같다).
  - 승인이 먼저 잠금 → 등록은 기다렸다가 승인 커밋 뒤 등록한다. 이미 된 승인은 취소하지 않는다(기존 정책 : 승인된 제안의 대상도 등록할 수 있다 — 업무 설명은 'AI 제안 승인' 값으로 표시·블라인드 중 가림).
  - 같은 대상 동시 등록 → 늦은 쪽이 기다렸다가 이미 등록된 것을 보고 건너뛴다.
- 잠금 순서 : 승인 = 제안 → 테이블 또는 컬럼 하나 → 평가 대상. 등록 = 테이블들 → 컬럼들(제안을 잠그지 않음). 판정·의미 수정 = 평가 대상. 순환 없음. 등록은 프로젝트 행을 먼저 잡지 않는다 — H2 는 그 공유 잠금을 배타로 잡아, 같은 행을 쥔 승인이 커밋 직전 프로젝트 확인(`ProjectWriteGuard`)에서 교착한다. 프로젝트 확인은 다른 모델 저장처럼 커밋 직전에 자동으로 한다(프로젝트 영구 삭제와는 일반 모델 저장과 같은 관계).
- 남은 위험 : 등록이 여러 테이블·컬럼 행을 잡는 동안 다른 모델 저장이 같은 행들을 다른 순서로 잡으면 PostgreSQL 이 교착을 감지해 한쪽을 실패시킬 수 있다(기존 모델 저장끼리와 같은 성격, 재시도하면 된다).

### 24.4 기존 평가 데이터 호환 (EVAL-2-05)

- 개발 DB(PostgreSQL `lexorma`, 읽기 전용 트랜잭션 `default_transaction_read_only=on` 으로 조회만) : 평가 대상 0건, 판정 0건 — 미확인·확인 불가 대상의 판정, CONFIRMED 의 '평가할 수 없음', 중복 대상, 평가 대상의 승인 제안 모두 0건. 충돌 데이터 없음. 수정·삭제·보정 마이그레이션 없음.
- 격리 테스트(`legacyNotEvaluableOnPendingTarget`) : 예전 규칙의 행(미확인 대상의 '평가할 수 없음' 판정)을 직접 넣으면 — 판정 행은 그대로, 지표는 평가 불가 1·정확성 분모 0, 대상은 블라인드(판정 내용도 보내지 않음, 판정 수만), 실제 의미·상태 변경은 `AI_EVALUATION_MEANING_LOCKED`, 제안 승인은 `AI_PROPOSAL_EVALUATION_PENDING`. 이런 대상은 지금 기능으로는 확인할 수도 지울 수도 없다(판정 있는 대상은 삭제 불가) — 실제로 생기면 정리 정책을 사용자가 정한다.

### 24.5 테스트

- `AiDictionaryEvaluationIntegrationTest` 11개 (이번에 3개 추가, 2개 갱신) : `generalReviewBlind`(목록·검색·상세·이력 확정 문구·AI 승인 업무 설명·생성 응답·보류 응답, 평가 대상 아닌 제안 그대로, 다른 프로젝트 없음, 확인 뒤 공개, 확인 불가 계속 가림), `concurrentRegisterAndApproval`(등록 먼저 → 직접 승인 거부, 등록 먼저 → 일괄 승인 FAILED, 승인 먼저 → 승인 유지 후 등록, 같은 테이블 대상 동시 등록 하나만 — 래치로 한쪽이 잠금을 쥔 동안 다른 쪽이 기다리는 것을 확인), `legacyNotEvaluableOnPendingTarget`, `blindBeforeConfirmed`(일반 상세도 가림으로 갱신), `judgments`('근거 없는 주장' 있음·없음 저장값).
- 결과 : 전체 H2 `./gradlew build` 810건(실패 0, 건너뜀 33), PostgreSQL 18.6(같은 컨테이너의 별도 빈 DB, 끝나고 삭제) `--rerun` 810건(실패 0, 건너뜀 25 — 동시성 테스트 포함 실제 트랜잭션 경합), `npm run typecheck`·`npm run build` 통과

### 24.6 브라우저 검증 (EVAL-2-04)

- 1차 실패 원인 : 이 세션에 연결된 Chrome 확장은 **다른 컴퓨터(Windows)** 의 브라우저였다(`isLocal=false`). 그 브라우저의 `localhost` 는 이 컴퓨터가 아니라서 오류 페이지가 났다. 격리 인스턴스를 네트워크에 열지 않고, 이 컴퓨터의 Chrome 을 headless 로 띄워 CDP(DevTools 프로토콜, Node 내장 WebSocket — 프로젝트 의존성 아님)로 조작·스크린샷했다.
- 환경 : 격리 인스턴스(H2 파일 DB, 임시 폴더, 포트 19941), 실제 Ollama `gemma4:e4b`. 일반 제안 1개(평가 대상 아님) + 평가 대상 2개의 제안. 실제 의미는 "[E2E 테스트용 고정 값 · 사람이 확인한 의미 아님]" 문구, 판정은 고정 값 — 사람의 판정이 아니다.
- 확인한 것 (한국어·라이트·1440px) : 일반 목록의 "평가 블라인드" 표시·안내와 평가 대상 아닌 제안의 정상 내용 / 일반 상세의 블라인드 안내·메타데이터·생성 정보·이력, 승인 버튼 비활성(제목에 사유)·승인 제한 안내 / 평가 상세의 미확인 상태·메타데이터·블라인드 안내 / 실제 의미 입력 → 확인함 저장 → 제안 공개·'판정 전 승인 불가' 사유 / 판정 칸 5개 모두 빈 값, 4개만 골라도 저장 버튼 꺼짐('근거 없는 주장' 미선택) / 다 고른 뒤 저장 → 의미 칸·상태 선택 잠김과 안내 / 일반 상세에서 내용 공개·'승인 및 교체' 버튼 활성 → 승인 반영. 생성 응답도 블라인드(평가 대상 제안 생성 시 내용 없음).
- 보지 못한 것 : 영어 화면·다크·390px(AI-5-UI), 확인 불가 대상의 화면.
- 발견 : 제안 상세의 사유 입력칸(`.ai-dict-reason`, `search-input`)이 대화상자 안 세로 flex 에서 `.search-input { flex: 0 1 400px }` 때문에 높이 400px 로 늘어난다. 이번 변경과 무관한 기존 CSS 문제라 고치지 않았다 (pending-work [AI-5-UI]).

## 25. 실제 레거시 DB 평가 PoC 준비 (AI-5-EVAL, 2026-11-16) — 분석·운영 절차, 평가 미실행

기능을 추가하지 않고 기존 구현으로 실제 레거시 평가를 할 수 있는지 분석하고 운영 절차를 정했다. 실제 원본 DB 연결·AI 생성·사람 판정은 하지 않았다 (연결 정보·사용 승인 없음). **사람 판정은 여전히 0건**이다.

### 25.1 현재 구현으로 가능한 범위

| 단계 | 경로 | 비고 |
|---|---|---|
| 원본 연결 | `/api/source/connections` (USER, 프로젝트 관리자·ADMIN 이 실행) | 비밀번호는 `SecretCipher` 암호화. JDBC URL 은 받지 않고 서버가 만든다 |
| 연결 허용 | SEC-1 외부 연결 규칙 (ADMIN, JDBC 유형) | 기본 거부. 개발 DB 에는 규칙이 0건이라 지금은 어떤 원본에도 접속할 수 없다 |
| 카탈로그 수집 | 수동 최신화 `POST /api/source/connections/{id}/collections` (AI-2b) | 사용자가 누를 때만. 스키마·테이블·컬럼·제약조건·인덱스·관계를 `pg_catalog` 고정 조회로 읽는다 |
| 모델로 가져오기 | 원본 매핑 → 비교 → 가져오기(M-4e: 후보 → 계획 → 반영) | 이름·타입·제약조건·인덱스·FK·IDENTITY 와 원본 COMMENT(`SRC_ANTN_CN`, 고른 경우만)를 넣는다. 테이블 코멘트(`TBL_KORN_NM`)·컬럼 설명(`COL_EXPLN`)·업무 설명·표준 용어 연결은 넣지 않는다. 미승인 객체로 만든다 |
| 평가 | 평가 대상 등록 → 생성 → 실제 의미 확정 → 판정 → 지표 (16·23·24장) | 기존 API·화면 그대로 |

- **지원 DBMS : PostgreSQL 12 이상만** (수집 도구 `PostgresSourceTools`, 접속 속성 `SourceJdbcSettings.connectionProperties`, 비교·가져오기 규칙 `PostgresCompareRules`). Oracle·MySQL·MariaDB 는 연결 정보 저장은 되지만 수집·비교·가져오기를 할 수 없다. 그런 원본은 Excel·직접 입력으로 모델을 만들 수 있으나 원본 COMMENT 칸(`SRC_ANTN_CN`)을 채우는 경로가 가져오기뿐이라, COMMENT 는 컬럼 설명으로 들어가고 'COMMENT 없음' 범주가 모든 대상에 붙는다 — 이번 PoC 는 PostgreSQL 원본을 권장한다.
- **원본 쓰기 가능성 : 없음(구조상).** 접속은 `default_transaction_read_only=on`·`setReadOnly(true)`·REPEATABLE READ, `statement_timeout`, `search_path=pg_catalog`. 조회는 시스템 카탈로그 고정 SQL 이고 입력 문자열을 SQL 에 잇지 않는다. 업무 데이터 값은 읽지 않는다. AI 는 원본 DB 도구를 쓸 수 없다(AI 도구는 Lexorma DB 만 읽는다). 다만 원본 DB 계정은 운영 쪽에서도 **카탈로그 조회만 되는 읽기 전용 계정**으로 만든다 (방어 이중화).
- 필요한 설정 : SEC-1 JDBC 허용 규칙(호스트·CIDR·포트, ADMIN), 원본 연결(호스트·포트·DB 이름·계정·비밀번호·`sslmode`), 암호화 키(`local`·`h2-local` 은 고정 개발 키가 있음, 운영 프로필은 `LEXORMA_SECRET_KEYS`), 평가용 프로젝트와 프로젝트 관리자, AI 제공자와 시스템 기본 제공자(개발 DB 는 제공자 0건). 로컬 Ollama 에 받아 둔 모델은 `gemma4:e4b` 하나다.

### 25.2 평가 대상 선정 기준

- 규모 (잠정) : 테이블 5~10개, 컬럼 50~100개, 판정자 1명, 로컬 Ollama 모델. 원본 분포를 본 뒤 사용자와 확정한다. 평가 대상 등록은 한 번에 100개까지다.
- 기존 범주(규칙이 제안하고 사람이 고친다 — `AiDescriptionEvaluationTarget.Category`)와 지시서 유형의 대응 :

| 지시서 유형 | 기존 범주 | 메모 |
|---|---|---|
| 업무 의미가 명확한 컬럼 | `CLEAR` (규칙 : 업무 설명이 있음) | 가져온 직후에는 업무 설명이 없어 규칙이 붙이지 않는다 — 사람이 고른다. 업무 설명이 있는 대상은 AI 입력에 그 설명이 들어가므로(25.3) 고르지 않는다 |
| 원본 COMMENT 없음 | `NO_SOURCE_COMMENT` | 가져오기에서 COMMENT 를 고르지 않으면 모든 대상이 이 범주가 된다 |
| 이름이 모호함 | `NAME_ONLY` 로 근사 | 규칙은 "업무 설명·COMMENT·표준 용어가 모두 없음"이지 모호함이 아니다 — 사람이 고른다 |
| 코드·상태 | `STATUS_CODE` (`_CD` 끝, `STTS` 포함) | 레거시 이름 규칙이 다르면 사람이 고친다 |
| 표준 용어 연결됨 / 없음 | `STANDARD_LINKED` / (범주 없음 = 연결 없음) | 표준 사전은 프로젝트별이다. 새 평가 프로젝트는 사전이 비어 있어 연결 대상이 없다 |
| FK 관계 | `RELATION` | 가져온 FK 를 쓴다 |
| 금액 · 날짜 | `AMOUNT` · `DATE_TIME` | 단어 목록은 Lexorma 약어 기준(`AMT`·`PRC`·`DT`·`YMD` 등) |
| 수량 | 없음 | 범주가 없다 (지표·범주 신규 개발은 이번 제외) |

- 제외 : 개인정보·민감 업무 의미가 드러나는 테이블·컬럼(이름·COMMENT 만으로도), 원본 COMMENT 에 업무 규칙 외 비밀이 적힌 대상, **평가 대상 등록 전에 이미 AI 제안을 승인한 대상**(그 업무 설명은 일반 메타데이터 화면에 보인다 — 24.1), 업무 설명이 이미 채워진 대상. 실제 업무 데이터 값은 수집하지 않는다 (수집 기능도 값을 읽지 않는다).
- 후보별로 정리할 것 : 스키마·테이블·컬럼, 타입, 원본 COMMENT 유무, 표준 용어 연결, 관계, 범주, 선정 사유. 평가 후보 API(`/api/ai/dictionary/evaluation/candidates?tableId=`)가 범주 제안·등록 여부·제안 수를 준다.
- 유형별 결과 : 지표 API 는 모델·프롬프트별로만 묶는다(범주는 대상 수만 센다). 유형별 정확성은 대상 목록(범주) + 대상 상세(판정)를 사람이 교차 집계해야 한다.

### 25.3 데이터 독립성 — 확인 결과

생성 입력(서버가 모으는 맥락) : 대상 테이블 상세·컬럼 목록·관계, 컬럼 대상이면 비슷한 컬럼, 표준 단어·용어·도메인 검색. **컬럼 목록·테이블 상세에는 각 객체의 업무 설명(`TASK_EXPLN`)과 원본 COMMENT 가 들어간다.**

| 변경 | 평가에 주는 영향 | `sameMetadata` 가 잡는가 |
|---|---|---|
| 대상 자신의 수정(테이블·컬럼 관리 화면, 표준 용어 연결, 업무 설명 직접 수정·AI 승인) | 입력이 바뀐다 | 잡는다 — 대상의 수정 일시·변경 이력 마지막 ID 를 비교 |
| 같은 테이블의 다른 컬럼 변경, 다른 컬럼의 업무 설명 승인 | 컬럼 목록 입력이 바뀐다. 앞 모델 제안을 승인하면 뒤 모델이 그 글을 본다 | **못 잡는다** (대상만 비교) |
| 관계·비슷한 컬럼·표준 단어·용어·도메인 변경 | 입력이 바뀐다 | 못 잡는다 |
| 원본 카탈로그 재수집 | 모델을 다시 가져오지 않으면 생성 입력은 그대로 (승인 재검증의 원본 비교에는 영향) | 해당 없음 / 다시 가져와 대상이 바뀌면 잡는다 |
| 평가 대상 등록 전 생성 | 사람이 실제 의미를 쓰기 전에 일반 화면에서 AI 제안을 볼 수 있었다 | 블라인드는 등록 뒤부터 |

그래서 평가 기간 운영 규칙(기능 잠금은 추가하지 않는다) :

1. 평가 전용 프로젝트를 쓴다 (예제 프로젝트 0 이나 실제 작업 프로젝트를 쓰지 않는다). 구성원은 평가 운영자·판정자만.
2. 모델 가져오기·표준 연결 등 입력 준비를 **모두 끝낸 뒤** 평가 대상을 등록하고, 등록 뒤에 생성한다.
3. 평가 기간에는 평가 프로젝트의 모델·표준 사전 수정, 원본 재수집·다시 가져오기, **AI 제안 승인과 업무 설명 직접 입력**을 하지 않는다. 판정을 마친 제안은 승인하지 말고 그대로 두거나(검토 대기) 반려·종결한다 — 승인하면 다음 모델 입력에 그 글이 들어간다. 실제 의미를 업무 설명 칸에 적지 않는다.
4. 모델 비교는 같은 대상에 대해 모델(또는 프롬프트)만 바꿔 다시 생성한다 : 시스템 기본 제공자의 기본 모델을 바꾸고(ADMIN) 재생성 — 앞 제안은 SUPERSEDED 로 남고 판정할 수 있다. 지표에서 모델별 `sameMetadata` 수가 판정 수와 같은지 본다. 다르면 그 판정은 다른 입력 조건이다.
5. 평가 시작·종료 시점에 프로젝트 모델 버전을 만들어 둔다(모델 버전 기능, 이름 예 `EVAL-START`) — 비교로 기간 중 모델 변경이 없었음을 확인한다. 표준 사전은 모델 버전에 없으므로 운영 규칙으로 지킨다.

### 25.4 평가 절차

1. 원본 선정·사용 승인(사용자) — PostgreSQL, 읽기 전용 계정, 민감 대상 검토
2. 평가 프로젝트 생성, SEC-1 허용 규칙, 원본 연결·연결 시험 (ADMIN·프로젝트 관리자)
3. 카탈로그 수동 최신화 → 원본 매핑 → 비교 → 가져오기 (COMMENT 포함 여부 결정)
4. (선택) 표준 사전 구성·용어 연결 — 표준 범주를 넣는 경우만
5. 후보 분석(평가 후보 API, 범주 제안) → 사용자와 최종 대상 확정 → 범주를 사람이 고쳐 등록. 모델 버전 `EVAL-START`
6. AI 설명 생성 (대상별 단건 또는 SELECTED 작업). 이 시점부터 평가 대상 제안은 블라인드 — 일반 화면에서도 내용이 보이지 않는다
7. 판정자가 AI 제안을 보지 않은 상태로 실제 의미를 작성 (원본 COMMENT·소스 코드·업무 문서·담당자 확인; 확인 못 하면 '확인 불가')
8. 실제 의미 CONFIRMED → 제안 공개
9. 정확성·근거 없는 주장·누락·불확실성·추천 적절성·검토/작성 시간을 판정 (판정 뒤 실제 의미 고정)
10. 다른 모델·프롬프트로 재생성 → 9 반복 (실제 의미는 이미 고정되어 있다)
11. 지표 확인 (모델·프롬프트별, `sameMetadata`), 유형별 교차 집계, 모델 버전 `EVAL-END` 와 비교
12. 목표치(DEC-02) 검토는 결과를 본 뒤 사용자와

판정자가 1명이면 같은 사람이 실제 의미를 쓰고 판정한다. 블라인드는 실제 의미를 쓰는 동안 AI 제안을 못 보게 할 뿐, 판정 단계의 확증 편향은 막지 못한다.

### 25.5 지표 활용

지표 API(`/evaluation/metrics`)에 이미 있는 것 : 정확성(맞음 / 맞음+일부+틀림)·부분 정답·틀림 수(분모에서 계산)·평가 불가 수, 근거 없는 주장 비율, 허구 객체 비율(서버 근거 검증), 용어·도메인 추천 적절성, 불확실성 적절·단정·과잉, 수정 후 승인률·승인률, 검토·작성 시간 중앙값과 절감률, `sameMetadata` 판정 수, 대상 수(확인·확인 불가·미확인)·범주별 대상 수. 평가 운영 규칙 3(승인하지 않음)을 따르면 수정률·승인률은 비어 있다. 확인 불가 대상은 판정을 받지 않으므로 '평가 불가 수'가 아니라 대상 현황의 확인 불가 수로 본다.

### 25.6 개발 DB 확인 (읽기 전용 트랜잭션, 2026-11-16)

프로젝트 1개(예제 0 : 테이블 73·컬럼 811), 원본 연결 0, 수집 실행 0, 원본 카탈로그 테이블·컬럼 0, 원본 매핑 0, 가져오기 0, SEC-1 규칙 0, AI 제공자 0(기본 제공자 없음), 평가 대상·판정·제안 0. 실제 레거시 원본 데이터는 없다.

## 26. 후속 : 업무 설명 엑셀 · 컬럼 등록 · 일괄 편집 (AI-1d, 2026-11-16)

AI-1d 의 남은 입력 경로. 저장 구조·API·권한은 그대로이고 DB 마이그레이션 없음. 요약과 검증은 [completed-work.md](../completed-work.md) [AI-1d].

### 26.1 분석 (구현 전)

- 엑셀 업로드(컬럼 `uploadColumnExcelFile`, 테이블 `uploadTableExcelFile`)는 **등록(INSERT) 전용**이다 — 컬럼은 이미 있는 (테이블, 컬럼명)이 하나라도 있으면 `SAVED_DATA_EXISTS` 로 전체 취소, 테이블은 같은 이름을 건너뛴다. 기존 행을 고치는 엑셀 경로는 없다.
- 컬럼·테이블 **데이터**를 내려받는 엑셀은 없다 (빈 템플릿과 데이터 사전 용어·단어·도메인뿐, 데이터 사전에는 `TASK_EXPLN` 이 없다).
- 파서는 헤더 문자열을 보지 않고 열 순서로 읽고(`ExcelUtils` SAX), 없는 열은 빈 문자열이다 → 끝에 열을 덧붙이면 예전 파일도 그대로 읽힌다.
- 컬럼 템플릿은 최초 커밋부터 20열(S 엑셀 컬럼 헤더 표시명, T 데이터 예시)인데 파서는 19개 키라 S열을 `example` 로 저장하고 T열을 버렸다. 업무 설명을 20번째 키로 붙이면 T열 데이터 예시가 업무 설명이 되므로 U열에 둬야 했다. S열을 담는 모델 속성은 없다.
- 단건 등록 API(`ColumnInfoDto.toEntity`)와 일괄 편집 API(`bizDescription`·`bizDescriptionBase`, 고치는 행만 ID 순 잠금 후 다시 읽기)는 이미 업무 설명을 받았고 화면만 없었다. 컬럼 "수정" 화면은 컬럼 상세의 업무 설명 칸(19.1)이다.

### 26.2 사용자 결정 (2026-11-16)

- 빈 셀 = 기존 값 유지 (A안, 지시서). 엑셀에 지우기용 특수 값은 없다.
- 등록 전용 계약 유지 — 엑셀로 기존 컬럼의 업무 설명을 고치지 않는다. 다운로드는 템플릿 열만. S/T 불일치는 이번에 고치되 S열에 새 속성을 만들지 않고 이미 저장된 값은 옮기지 않는다. 테이블 템플릿 E열도 추가.

### 26.3 구현

| 위치 | 열 | 처리 |
|---|---|---|
| `columnTemplate.xlsx` | S 엑셀 컬럼 헤더 표시명 | 읽기만, 저장 안 함 (예전엔 `example`) |
| | T 데이터 예시 | `example` (예전엔 버림) |
| | U 업무 설명 (새 열) | `BizDescriptions.normalize` → 비면 NULL |
| `tableTemplate.xlsx` | E 업무 설명 (새 열) | 같음 |

- 파일 형식별 : 19열(A~S) → 데이터 예시·업무 설명 없음, 20열(지금까지의 템플릿) → T 가 데이터 예시, 21열 → 업무 설명까지. 테이블 4열 → 업무 설명 NULL. 한국어·영어 템플릿 모두 같은 위치(영어는 셀 문자열만 바꾼다).
- 이미 있는 컬럼은 업로드 전체가 거부되고, 이미 있는 테이블은 건너뛰므로 업로드가 기존 `TASK_EXPLN` 을 바꾸는 일은 없다 → 15장의 잠금·비교 규칙이 필요한 새 쓰기 경로가 생기지 않았다.
- 화면 : `ColumnForm` 업무 설명 textarea(4,000자, 선택). `ColumnBulkEditor` 행 상세의 업무 설명 칸 — 신규 행은 값, 기존 행은 고쳤을 때만 `bizDescription` + `bizDescriptionBase`(읽은 값, 없으면 빈 문자열), 승인된 컬럼은 잠김, 복사한 컬럼은 업무 설명 제외.
- 테스트 중 확인한 점 : `BizDescriptionService.update` 를 **한 트랜잭션에서** 컬럼 → 테이블 순서로 부르면 테이블 `refresh` 가 `columns`(cascade ALL)로 이어져 아직 flush 하지 않은 컬럼 변경을 되돌린다. API 는 요청마다 트랜잭션이 따로라 해당하지 않지만, 두 업무 설명을 한 트랜잭션에서 쓰는 새 경로를 만들면 주의해야 한다(테스트는 테이블 먼저).

### 26.4 남은 것

DEC-03(DDL COMMENT 에 넣을지), 데이터 엑셀 내려받기, 업무 설명 4,000자 상한을 직접 편집 API 만 검사하는 불일치 — pending-work.md [AI-1d]. → 길이 검증은 27장에서 통일, 데이터 내려받기는 M-11 로, DEC-03 은 결정 보류 (2026-11-16 사용자 결정). AI-1d 는 28.4 에서 완료 처리, DEC-03 은 pending-work.md 7.1 결정 항목. S열에 데이터 예시를 직접 넣어 쓰던 사용자 파일이 있다면 이제 데이터 예시가 비어 저장된다(저장소에서는 그런 파일을 찾지 못했다).

## 27. 후속 : 업무 설명 길이 4,000자 통일 (AI-1d, 2026-11-16)

사용자 결정 : 모든 입력 경로 4,000자, DDL COMMENT 그대로(DEC-03 보류), 데이터 엑셀 Export 는 M-11, 엑셀 업로드 등록 전용 유지, 기존 데이터는 자르거나 고치지 않는다. 마이그레이션·새 서비스·설정 없음.

### 27.1 분석

`TASK_EXPLN` 을 쓰는 곳 (코드 전수, `setBizDescription`·`bizDescription(` 빌더) :

| 경로 | 이전 검사 | 지금 |
|---|---|---|
| 직접 편집 `PUT /api/bizDescription` (`BizDescriptionService`) | 4,000 (`normalize` 뒤 `String.length`, 잠그기 전) | 공통 검사, 잠근 행 값과 비교 뒤 |
| 테이블 등록 `saveTable` (`TableInfoDto.toEntity`) · 수정 `updateTable` | 없음 | 공통 검사 (오류 → 트랜잭션 전체 롤백) |
| 컬럼 단건 등록 `insertColumn` (`ColumnInfoDto.toEntity`) | 없음 | 공통 검사 |
| 컬럼 일괄 편집 `saveColumnsBulk` (신규·기존 행) | 없음 | 행 오류(기존 응답 형식) → 요청 전체 저장 안 함 |
| 컬럼·테이블 엑셀 업로드 | 없음 | 오류(테이블.컬럼 / 테이블명) → 업로드 전체 롤백 |
| AI 제안 승인 (`AiDescriptionReviewService`) | 고친 문구 4,000 (`AI_QUERY_INVALID`), AI 초안은 스키마 2,000 | 같은 상수(`BizDescriptions.MAX`), 오류 코드는 그대로 |

그 밖에 쓰는 곳 없음 (원본 가져오기·재가져오기·버전·논리 모델·ERD 편집은 `TASK_EXPLN` 을 쓰지 않는다).

### 27.2 규칙 (`BizDescriptions`)

- `MAX = 4000`. 길이는 **앞뒤 공백을 뺀 값의 `String.length()`** (UTF-16 단위) — 예전 직접 편집과 같고, 브라우저 `maxLength`·JS `length` 와 같은 기준이다 : 한글·영문 1, 이모지(보조 평면) 2, 가운데 줄바꿈 1 (Chrome 157 에서 확인). DB 칸은 TEXT 라 DB 쪽 제한은 없다.
- `tooLong(requested, current)` : 바꾸려는 값이 지금 값(정규화)과 **같으면 보지 않는다** — 예전에 들어간 4,000자 초과 값을 그대로 두는 저장(다른 칸만 수정, 같은 값 다시 보냄)이 막히지 않게. null·빈 값·공백만은 길이 대상이 아니다(null = 변경 없음, 빈 값 = 지움 규칙 그대로). 자르지 않는다.
- 오류 : 예외 경로는 직접 편집과 같은 `INVALID_PHYSICAL_MODEL` + "업무 설명은 {0}자까지 입력할 수 있습니다."(이미 있는 한국어·영어 문구), 일괄 편집은 행 오류 "컬럼명 : …", 엑셀은 "{대상} : …". 새 문구·새 오류 코드 없음. `INVALID_PHYSICAL_MODEL` 의 기본 문구("관계·인덱스·제약조건 정의가 올바르지 않습니다")가 앞에 붙는 것은 예전 직접 편집과 같다. → 28장에서 전용 오류 코드 `BIZ_DESCRIPTION_TOO_LONG` 로 바꿨다.
- 엑셀 테이블 업로드에서 건너뛰는 행(이미 있는 테이블)은 검사하지 않는다(쓰지 않으므로). 일괄 편집의 승인된 컬럼 행은 예전처럼 반영하지 않으므로 검사하지 않는다.

### 27.3 화면

업무 설명 입력칸 5곳(테이블 폼, 컬럼 등록 폼, 컬럼 일괄 편집 행, 직접 편집, AI 제안 수정)이 같은 상수 `BIZ_DESCRIPTION_MAX` 와 글자 수 표시 `BizLength`("n / 4,000자", 상한에 닿으면 강조, 한국어·영어)를 쓴다 (`BizDescriptionInfo.tsx`). 일괄 편집의 잠긴(승인) 행은 글자 수를 숨긴다. 서버는 앞뒤 공백을 빼고 세므로 화면 쪽이 같거나 더 엄격하다.

### 27.4 같은 트랜잭션의 컬럼 → 테이블 업무 설명 되돌림 (분석 — 28장에서 B안으로 고침)

- 재현 (임시 테스트, 실행 뒤 지움) : ① 한 트랜잭션에서 컬럼 → 테이블 직접 편집 → 컬럼 변경이 사라짐 ② 테이블 → 컬럼 → 둘 다 남음 ③ 사이에 `flush` → 둘 다 남음 ④ 따로 트랜잭션(실제 API) → 둘 다 남음 ⑤ 컬럼 일괄 편집 → 테이블 직접 편집(한 트랜잭션) → 둘 다 남음(일괄 편집 안의 조회가 자동 flush).
- 원인 : `BizDescriptionService` 가 테이블 행을 잠근 뒤 `entityManager.refresh(table)` 로 다시 읽는데, `TableInfo.columns` 가 `CascadeType.ALL`(REFRESH 포함)이라 영속성 컨텍스트에 올라온 그 테이블의 컬럼도 DB 값으로 다시 읽힌다 — flush 하지 않은 변경(업무 설명뿐 아니라 모든 칸)이 버려진다.
- 실제 경로 : 테이블 `refresh` 는 `BizDescriptionService`(직접 편집 API 만 호출, 요청당 한 트랜잭션)와 `TableDesignServiceImpl.updateTable`(트랜잭션 처음, 컬럼을 바꾸기 전 — 컨트롤러만 호출) 두 곳뿐이다. 둘을 한 트랜잭션에서 묶어 부르는 코드는 없다 → **지금은 일어나지 않는다.**
- 대안 (사용자 결정 전 적용 안 함) : (a) 테이블 `refresh` 앞에 `flush` — 가장 작음, 다른 칸의 지연 쓰기 순서가 앞당겨진다 (b) `refresh` 대신 잠금 조회 + 업무 설명만 스칼라로 다시 읽기 — 이 서비스만 바뀜 (c) `TableInfo.columns` 의 cascade 에서 REFRESH 제외 — 모든 테이블 refresh 에 영향, 범위 큼 (d) 그대로 두고 "한 트랜잭션에서 두 업무 설명을 쓰는 경로를 만들지 않는다" 규칙만 유지(지금 상태).

### 27.5 검증

`BizDescriptionLengthIntegrationTest` 6건 : 길이 규칙(3,999·4,000·4,001, 한글·영문·이모지·줄바꿈, 앞뒤 공백, null·빈 값·공백만, 같은 긴 값), 직접 편집·테이블 등록/수정·컬럼 단건 등록의 경계와 거부 시 무변경(테이블 수정은 다른 칸도 롤백), 일괄 편집(초과 행 → 요청 전체 미저장·다른 행/칸 그대로, 4,000 저장, 승인 컬럼 불변), 엑셀(앞 행까지 롤백, 오류에 대상 이름, 건너뛰는 행은 검사 안 함), 예전 초과 데이터(다른 칸 수정·같은 값 허용, 더 긴 값 거부, 줄이기 허용, 자르지 않음), VIEWER 거부. 전체 실행과 브라우저 결과는 [completed-work.md](../completed-work.md) [AI-1d].

## 28. 후속 : 직접 편집 트랜잭션 안전성 · 길이 초과 전용 오류 (AI-1d, 2026-11-16)

사용자 결정 : 트랜잭션은 B안(필요한 값만 다시 조회), 길이 초과 문구 "업무 설명은 최대 4,000자까지 입력할 수 있습니다." / "Business description must not exceed 4,000 characters.". cascade·전역 트랜잭션·마이그레이션 변경 없음.

### 28.1 트랜잭션 안전성 (`BizDescriptionService`)

- 재현 : 영구 테스트 `BizDescriptionTransactionIntegrationTest` 의 TX-01(같은 트랜잭션 컬럼 → 테이블)과 TX-05(다른 컬럼의 반영 전 수정 + 컬럼 → 테이블)가 고치기 전 코드에서 실패 — 커밋 뒤 JDBC 로 읽은 컬럼 업무 설명이 옛 값이었다. 원인은 27.4 (`refresh(table)` → `TableInfo.columns` cascade ALL).
- 고침 : 테이블·컬럼 모두 `entityManager.refresh` 를 없앴다. 순서는 그대로 — `findForUpdate`(같은 PESSIMISTIC_WRITE 잠금 쿼리, 같은 프로젝트·소속 테이블 검증) → **업무 설명만 스칼라 JPQL 로 다시 읽기**(`TableInfoRepository`·`ColumnInfoRepository.findBizDescription`) → 길이·충돌 비교 → 바뀔 때만 엔터티에 쓰기(@DynamicUpdate, 변경 이력·감사 칸은 예전과 같은 엔터티 경로). 잠금 순서·트랜잭션 경계·권한(USER)·승인 정책은 그대로.
- 최신 값 근거 : 스칼라 조회는 영속성 컨텍스트가 아니라 JDBC 결과에서 값을 만들고, 행을 잠근 뒤(READ COMMITTED) 실행하므로 커밋된 최신 값이다. 같은 트랜잭션에서 그 엔터티(같은 테이블 칸)에 반영 전 변경이 있으면 Hibernate 의 자동 flush(조회 대상 테이블이 겹칠 때만)로 그 값이 보인다 — 명시적 flush 는 넣지 않았다.
- 낡은 엔터티 : 같은 트랜잭션이 잠그기 전에 엔터티를 읽어 두었고 그 사이 다른 트랜잭션이 업무 설명을 커밋했으면(엔터티 값 ≠ DB 값) 더티 체킹과 변경 이력의 변경 전 값이 낡은 스냅숏을 기준으로 하므로 쓰지 않고 `BIZ_DESCRIPTION_CONFLICT`. refresh 없이 한 속성만 맞출 방법이 없어 거부로 안전하게 둔다. API 는 요청마다 새 트랜잭션에서 잠그며 처음 읽으므로 이 경우가 없다 (예전 refresh 는 이 경우 저장했다 — 작은 동작 차이).
- 바꾸지 않은 것 : `updateTable` 의 refresh(트랜잭션 처음, 컬럼을 바꾸기 전), `TableInfo.columns` cascade, 다른 서비스.

### 28.2 길이 초과 전용 오류

- 기존 구조 : `CustomException(code, 사유)` 의 응답 문구는 항상 "코드 기본 문구 \n[사유]" 라서 `INVALID_PHYSICAL_MODEL` 로는 "관계·인덱스·제약조건 정의가 올바르지 않습니다." 가 앞에 붙는다 — 기존 계약으로는 전용 문구만 보낼 수 없다.
- 선택 : 새 오류 코드 `BIZ_DESCRIPTION_TOO_LONG`(400) 하나와 `errors_ko/en.properties` 두 줄. 예외 처리 구조는 그대로. 영향 : 이 경우의 응답 `code` 가 `INVALID_PHYSICAL_MODEL`(AI 승인은 `AI_QUERY_INVALID`)에서 바뀐다 — 화면은 이 코드들로 분기하지 않는다(코드 검색). 다른 `INVALID_PHYSICAL_MODEL` 문구는 그대로.
- 경로별 : 단건(직접 편집·테이블 등록/수정·컬럼 단건 등록·AI 제안 승인) → 문구만 / 일괄 편집 → 행 오류 "컬럼명 : 문구"(요청 전체 저장 안 함 그대로) / 엑셀 → "문구 \n[테이블.컬럼]" 또는 "[테이블]"(업로드 전체 롤백 그대로). 영어 요청이면 영어. 쓰지 않게 된 예전 문구 "업무 설명은 {0}자까지 입력할 수 있습니다." 의 영어 항목은 지웠다.

### 28.3 검증

- `BizDescriptionTransactionIntegrationTest` 9건 (모두 트랜잭션 종료 뒤 JDBC 로 DB 확인) : TX-01~08 + 최신 값 재조회(먼저 읽은 엔터티가 낡았을 때 DB 값으로 판단해 충돌 — 엔터티 값으로 판단했다면 통과했을 요청, 옛 값으로 되돌리기·새 값 모두 거부하고 DB 그대로, 낡지 않았으면 저장, 같은 트랜잭션의 반영 전 변경은 최신 값). TX-04 는 이력 수·`MDFCN_DT` 불변, TX-06 은 뒤 요청이 잠금을 기다린 뒤 409(테이블·컬럼).
- `BizDescriptionLengthIntegrationTest` 7건 : 기존 6건의 오류 단언을 새 코드·정확한 문구로, 오류 문구 1건(응답 본문 `code`·`message`, 한국어·영어, 일괄 편집 행·엑셀 대상, AI 제안 승인, 일반 `INVALID_PHYSICAL_MODEL` 문구 그대로).
- 전체 실행 : H2 835건(실패 0, 건너뜀 33, `./gradlew build`), PostgreSQL 835건(실패 0, 건너뜀 25, 빈 DB, 실행 뒤 삭제).
- 브라우저 (격리 인스턴스, headless Chrome) : 4,001자 셀이 든 컬럼·테이블 엑셀 업로드 → 알림에 전용 문구와 대상, 저장 안 됨 (한국어 라이트 1440, 영어 다크 1440, 영어 라이트 390). API 직접 4,001자 → `BIZ_DESCRIPTION_TOO_LONG` 문구만, 다른 `INVALID_PHYSICAL_MODEL`(필수값 누락)은 예전 문구. 컬럼 상세에서 화면으로 직접 편집 저장 정상.
- 확인하지 못한 것 : AI 제안 승인 화면에서 4,000자 넘는 문구 승인(화면 입력칸이 4,000자로 막고 AI 제공자도 없어 서버 테스트로만), 일괄 편집 행 오류의 화면 표시(입력칸이 막아 화면에서 넘길 수 없음 — 서버 테스트로만).

### 28.4 확정 정책 (2026-11-16 사용자 결정 — 코드 변경 없음)

구현이 아래 정책과 같음을 코드·테스트로 확인했다. 이로써 AI-1d 는 완료 (completed-work.md [AI-1d]).

1. 낡은 엔터티의 `BIZ_DESCRIPTION_CONFLICT`(409)는 지금 구현대로 둔다 (`BizDescriptionService.latest` — 엔터티 값 ≠ 잠근 행의 DB 값이면 거부).
2. DB 최신 값과 영속성 컨텍스트 값이 달라 변경 이력의 정합성을 보장할 수 없으면 저장하지 않는다.
3. 같은 트랜잭션의 정상적인 반영 전 변경은 충돌로 보지 않는다 : 비교는 업무 설명 한 칸만이고, 같은 칸의 반영 전 변경은 스칼라 조회 전 자동 flush(FlushMode AUTO — 프로젝트에서 바꾸는 곳 없음)로 DB 값과 같아진다. TX-01·TX-05 와 28.3 의 "반영 전 변경" 단언을 회귀 테스트로 유지한다. (전제 : 호출하는 트랜잭션이 FlushMode 를 COMMIT 등으로 바꾸면 같은 칸의 반영 전 변경이 충돌로 보일 수 있다 — 지금 그런 코드는 없다.)
4. 충돌 시 예외로 트랜잭션이 롤백되어 기존 데이터·변경 이력은 그대로다. 호출자는 최신 상태를 다시 조회한 뒤 재시도한다 — 화면(`BizDescriptionInfo`)은 충돌 안내와 함께 최신 내용을 다시 불러온다.
5. DEC-03(업무 설명을 DDL COMMENT 에) 은 결정 보류로 pending-work.md 7.1 에서만 관리하고, 데이터 엑셀 Export 는 M-11 에서 한다. → 2026-11-16 DEC-03 확정 : 넣지 않는다 (pending-work.md 7.2). 엑셀 Export 는 M-11 에서 구현됨.


## 29. 후속 : 판정 변경 이력 · 블라인드 처리 보강 · 평가 입력 맥락 · 실패 사유 문구 (DEC-27·29·32, 2026-11-16)

정책 출처 : pending-work.md 7.2 (DEC-27·DEC-29·DEC-32 사용자 최종 결정), 상태는 7.3.

### 29.1 판정 변경 이력 (DEC-27)

- 저장 : V2 `TB_AI_EXPLN_EVL_HSTRY` (`AiDescriptionEvaluationHistory`, 시퀀스 `SQ_AI_EXPLN_EVL_HSTRY`). 판정(`TB_AI_EXPLN_EVL`) 하나를 저장할 때마다 한 행을 **추가만** 한다 — 처음 저장 `INSERT`, 수정 `UPDATE`. 칸 : 판정 ID·평가 대상·제안·판정자·변경 전(처음이면 null)·변경 후(판정·근거 없는 주장·누락 정보·불확실성·용어·도메인 추천·필요한 수정·검토/작성 분)·사유(최대 1,000자)·시각. 고치거나 지우는 경로는 프로젝트 삭제(`ProjectDataCleanup`)뿐이다.
- 규칙 : 같은 값으로 다시 저장하면 아무것도 바꾸지 않고 이력도 남기지 않는다. 판정을 고칠 때는 사유가 필수(`AI_EVALUATION_INVALID` "판정을 고친 사유를 입력해주세요."). 기존 판정의 이력은 소급 생성하지 않는다. 지표는 지금처럼 판정 테이블의 최종 판정만 센다.
- 조회 : `GET /api/ai/dictionary/evaluation/judgments/{id}/history` (VIEWER, 이 프로젝트의 판정만 — 아니면 NOT_FOUND). 평가 대상 화면의 판정 행에서 [판정 변경 이력] 으로 바뀐 칸만 전후로 보인다. 블라인드 규칙은 판정 목록과 같다(실제 의미 확인 전에는 판정 자체가 화면에 오지 않는다).
- 예제 시드 : 같은 마이그레이션에 용어 `AI_설명_평가_이력_일련번호`, `TB_TBL`/`TB_COL`, 논리 엔터티 "AI 설명 평가 이력"·속성·식별자·매핑, 예제 인덱스 `IX_AI_EXPLN_EVL_HSTRY_01`, 업무 설명을 넣었다 (V0SeedMigrationTest·LexormaIndexSeedTest 의 수 115/168, AI 테이블 6).

### 29.2 블라인드 처리 보강 (DEC-29)

- 반려 : 승인과 같이, 실제 의미를 확인하기 전(PENDING)·확인 불가(UNCONFIRMABLE) 대상의 제안은 반려할 수 없다 (`AI_PROPOSAL_EVALUATION_PENDING`). 화면은 블라인드 제안의 [반려] 를 막고 이유를 툴팁으로 보인다.
- 보류 : 사유 필수 ("보류 사유를 입력해주세요."). 일괄 처리 화면도 반려·보류 모두 사유를 받는다.
- 종결 : 평가 불가(UNCONFIRMABLE) 대상의 제안을 종결하면 이력 동작이 `CLOSE_UNCONFIRMABLE`(사유 필수, 화면 "제안 종결 (평가 확인 불가)"). 예제 코드 용어 `처리_유형_코드` 정의에도 이 값을 V2 에서 더했다 (`ExampleTermCodeDefinitionTest`).

### 29.3 평가 입력 맥락 (DEC-29)

- 기준값 : 새 저장 없이 평가 대상 등록 시각과 제안 생성 시각을 쓴다. 두 시각 사이(어느 쪽이 먼저든)에 같은 테이블 — 테이블 자신(`PHYSICAL_TABLE`, ID) 과 컬럼(`PHYSICAL_COLUMN`, 이력 대상 이름 `테이블명.%`) — 의 모델 변경 이력이 있으면 입력 맥락이 바뀐 것으로 본다 (`ModelChangeHistoryRepository.countTableChanges`). 이름이 같은 다른 스키마 테이블의 컬럼도 함께 세므로 "바뀌었다" 쪽으로 넓다.
- 쓰임 : 제안 항목의 `sameContext`, 화면의 [입력 맥락 변경] 표시, 지표 `sameMetadata`("같은 입력 조건으로 만든 판정") = 대상 자신의 버전이 같고(기존 `sameMetadata`) 입력 맥락도 같은 판정 수. 기존 예제 지표 테스트의 값이 3 → 1 로 바뀌었다(앞 제안의 승인이 같은 테이블 업무 설명을 바꿨기 때문 — 의도한 변화).
- 하지 않은 것 : 프로젝트 전체 편집 차단(정책 : 기준값 검증 우선), 관계·표준 사전·다른 테이블 변경 감지, 승인 시점의 추가 차단(승인은 기존 `ProposalVersions` 재검증).

### 29.4 일괄 생성 실패 사유 (DEC-32)

- `JobError(target, code, message)` : 코드는 그대로(하위 호환), `message` 는 그 코드의 오류 문구를 요청 언어로(`Messages.error`, 없으면 "이 대상의 설명 초안을 만들지 못했습니다."). 화면은 "문구 (코드)" 로, 같은 코드는 한 번만 보인다.

### 29.5 검증

- `AiDictionaryEvaluationIntegrationTest` 14건(판정 이력·사유·같은 값 무기록, 블라인드 반려 거부·종결 `CLOSE_UNCONFIRMABLE`, 입력 맥락 변경과 지표, 실패 사유 문구), `AiDictionaryMigrationTest`, `V0SeedMigrationTest`, `LexormaIndexSeedTest`, `ExampleTermCodeDefinitionTest`, `ProjectDeletionIntegrationTest`, `MessagesCoverageTest` — 전체 실행 결과는 completed-work.md 9장.
