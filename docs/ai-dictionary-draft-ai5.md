# AI-5 데이터 사전 초안 생성 및 승인

> **상태 : 기능 구현 완료 / 품질 평가 기준 데이터·업무 정확도 검증 미완료 (2026-11-15) — AI-5 일부 완료.** 로드맵 [5단계](ai-metadata-roadmap.md#5단계-ai-데이터-사전-초안-생성-및-승인).
> 앞 단계 : [AI-4 Tool Calling](ai-tool-calling-ai4.md) (Tool·구조화 응답·근거 검증·토큰 예산), [AI-3 Provider](ai-provider-ai3.md), [SEC-1](external-connection-security-sec1.md).
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
3. 업무 설명 칸은 AI-1d 의 최소 범위만 했다 : 엑셀 업·다운로드, 컬럼 등록 화면의 업무 설명 입력(일괄 편집 행은 API 만), DDL 출력에는 넣지 않았다 (DDL COMMENT 는 기존 설명). 원본 COMMENT 변경 시 "유지/수정 제안" 은 사람이 다시 만들기로 만든다 (자동 생성 없음).
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
- **규칙** : 정확성 판정(맞음·일부·틀림)은 실제 의미를 확인한 대상에만 (아니면 평가 불가만). 확인 불가 대상은 정확성 분모에서 빼고 따로 센다. 정확성 판정이 있는 대상은 확인 상태를 되돌릴 수 없다. 범주는 이름·타입·연결·관계로 고른 제안값이고 사람이 고친다. 등록은 프로젝트 잠금으로 중복을 막는다. 권한 : 조회 VIEWER, 작성 USER(로그인 실행만, 작성자 기록).
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

