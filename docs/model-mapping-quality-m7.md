# M-7 2차. 논리·물리 매핑 관리 UI 및 정합성 비교 — 설계 결정과 구현 기록

> [데이터 모델링 로드맵](data-modeling-roadmap.md) M-7 중 **2차(매핑 관리 화면·정합성 비교)** 의 기록이다. 1차(매핑 도메인·API)는 [logical-physical-mapping-m7.md](logical-physical-mapping-m7.md).
> 작성일 2026-11-11. **DB 스키마 변경 없음** (Flyway V0~V3 그대로). 예제 매핑 데이터만 `V4__mapping_example_data.sql` 로 추가했다 (8장). 정합성 비교는 조회 전용 — 논리 모델·물리 모델·표준 사전·매핑을 바꾸지 않는다.

## 1. 기존 구조 분석과 결정

| 확인 항목 | 결과 → 결정 |
|---|---|
| 1차 API | 목록(`/entity/data`, 서버 페이징·모델·스키마·매핑 여부 조건), 상세(`/entity/{id}` : 속성 전체 + 양쪽 표준 용어 + 후보 컬럼), 저장·삭제, 후보 테이블(`/table/data`, 서버 페이징), 현황(`/status`) → **매핑 관리 화면은 1차 API 를 그대로 쓴다.** 요청·응답 DTO 는 바꾸지 않았다 |
| 부족한 조회 | 엔터티 상세·테이블 상세에서 "이 엔터티(테이블)의 매핑"을 ID 로 찾는 API 가 없다 (목록 검색은 이름 부분 일치) → **`GET /entity/lookup` 1개 추가** |
| 비교에 쓸 수 있는 값 | 논리 속성 : 이름, 데이터 유형(자유 입력), 필수 여부, 표준 용어(→ 도메인), 식별자. 물리 컬럼 : 이름, 타입·길이·정밀도·소수 자릿수, NULL 허용·필수 입력·PK, 표준 용어 → 2장 |
| 명명 규칙 | 물리 컬럼명 규칙은 "컬럼명 = 표준 용어의 영문약어" 하나뿐 ([M-5 2차](standardization-quality-m5.md)). 논리 속성명 규칙은 없다 (용어를 고르면 이름을 채워 줄 뿐 강제하지 않는다) → **명칭은 논리 속성의 용어 약어 ↔ 컬럼명만 비교** |
| 타입 비교 | M-5 가 `TableDdlGenerator.mapType` 으로 도메인 ↔ 컬럼을 비교한다 → 그 비교를 `StandardizationQualityService.compareDomain` 으로 꺼내 **같은 코드**를 쓴다 (M-5 동작은 그대로) |
| 논리 데이터 유형 | `TB_LGCL_ATRB.DATA_TYPE_NM` 은 "문자·숫자·일시" 같은 자유 입력이고 DBMS 타입으로 바꾸는 규칙이 없다 → **비교에 쓰지 않는다** |
| 식별자 ↔ PK 정책 | 주 식별자 = PK 를 강제하는 규칙은 없다. V1 예제가 "PK 컬럼 → 주 식별자"로 만들어졌을 뿐이다 → **구성 차이를 알려 주되** 주 식별자가 없으면 비교하지 않는다 (2.6) |
| 진단 계산 | M-5 는 프로젝트의 컬럼 전체를 읽어 메모리에서 진단한다 → **모델 1개 단위**로 줄이고(modelId 필수), 모델·엔터티·이름 조건은 DB 에서 거른다. 결과는 저장하지 않는다 (3장) |
| 메뉴·권한 | `MENU_ROLES`·`MENU_GROUPS`·`menu.ts`·`MenuId` 를 함께 고친다 (1차 문서 8장). 변경 버튼 노출은 `logical-model-edit` 과 같은 방식의 `model-mapping-edit` |
| 프론트 재사용 | 공통 `Grid`(서버 페이징·정렬·검색), `useDialog`, 요약 카드(`dash-stat`·`quality-stats`), `useLogicalModels`(모델 선택 + `?modelId=`), `useSchemas`, 엔터티 상세·테이블 상세 대화상자, ERD 의 "저장하지 않은 배치" 확인 |
| 로드맵과의 차이 | 로드맵 M-7 완료 조건의 **표준 용어 기반 물리명 추천**은 이번 지시서 범위에 없다 → 구현하지 않았다. M-7 은 "매핑 상태·불일치 목록"까지 충족하고 물리명 추천이 남는다 (7장) |

## 2. 정합성 진단 기준

진단 단위는 **매핑된 속성 ↔ 컬럼 1쌍 × 진단 유형**이다. 매핑된 속성 1개에 6건(표준 용어·명칭·데이터 타입·길이·필수 여부·식별자)이 나오고, 매핑하지 않은 엔터티·속성은 `MAPPING` 유형 1건이 나온다.

| 상태 | 뜻 |
|---|---|
| `MATCH` | 기준에 따라 일치 |
| `MISMATCH` | 명확한 불일치 |
| `NOT_COMPARABLE` | 비교 기준이 되는 정보가 없다. **불일치로 세지 않는다** |
| `UNMAPPED` | 매핑이 없다. 속성 불일치와 따로 센다 (`MAPPING` 유형에서만 나온다) |

### 2.1 매핑 여부 (`MAPPING`)

| 경우 | 결과 |
|---|---|
| 엔터티에 매핑이 없다 | 엔터티 1행 `UNMAPPED` + 그 엔터티의 속성마다 1행 `UNMAPPED` (다른 진단은 하지 않는다) |
| 엔터티는 매핑됐고 속성에 매핑이 없다 | 속성 1행 `UNMAPPED` |
| 매핑됐다 | 행 없음 (나머지 6개 유형을 진단한다) |

`UNMAPPED` 건수 = 미매핑 엔터티 수 + 미매핑 속성 수 (매핑 현황과 같은 집계). 매핑되지 않은 **물리** 테이블·컬럼은 진단 대상이 아니다 (논리 모델 기준 목록이다. 매핑 상세의 "매핑하지 않은 물리 컬럼"에서 본다).

### 2.2 표준 용어 (`TERM`)

표준 용어는 프로젝트 단위 데이터이고 속성·컬럼 모두 같은 프로젝트의 용어만 참조하므로 **ID 로 비교**한다 (이름이 같아도 다른 용어는 다르다).

| 논리 속성 | 물리 컬럼 | 결과 (사유) |
|---|---|---|
| 용어 A | 용어 A | `MATCH` |
| 용어 A | 용어 B | `MISMATCH` (논리 속성과 물리 컬럼의 표준 용어가 다릅니다) |
| 용어 A | 없음 | `MISMATCH` (논리 속성에만 표준 용어가 연결되어 있습니다) |
| 없음 | 용어 B | `MISMATCH` (물리 컬럼에만 표준 용어가 연결되어 있습니다) |
| 없음 | 없음 | `NOT_COMPARABLE` |

### 2.3 명칭 (`NAME`)

논리명(회원번호)과 물리명(`MBR_ID`)의 문자열은 비교하지 않는다. 기준은 기존 규칙 "컬럼명 = 표준 용어의 영문약어"다.

| 경우 | 결과 |
|---|---|
| 컬럼명 = 논리 속성 용어의 영문약어 (앞뒤 공백 제거, 대소문자 무시) | `MATCH` |
| 다르다 | `MISMATCH` (논리 측 값 = 약어, 물리 측 값 = 컬럼명) |
| 논리 속성에 용어가 없다 / 용어에 영문약어가 없다 | `NOT_COMPARABLE` |

컬럼명이 **컬럼 자신의** 용어 약어와 같은지는 M-5 품질 진단의 몫이다. 여기서는 논리 속성 기준의 기대 컬럼명만 본다 (양쪽 용어가 같으면 두 진단의 결과도 같다).

### 2.4 데이터 타입 (`DATA_TYPE`)·길이·정밀도 (`LENGTH`)

**논리 속성의 표준 용어의 도메인** ↔ 컬럼. M-5 와 같은 비교(`compareDomain`)라서 DBMS 표기만 다른 타입(`VARCHAR` ↔ `VARCHAR2`, Oracle 의 `INT` ↔ `NUMBER(10)`)은 일치다. 기준 DB 는 프로젝트의 `DbType`.

| 경우 | `DATA_TYPE` | `LENGTH` |
|---|---|---|
| 논리 속성에 용어 없음 / 용어에 도메인 없음 / 컬럼에 타입 없음 | `NOT_COMPARABLE` | `NOT_COMPARABLE` |
| 도메인에 타입이 없다 | `NOT_COMPARABLE` | 아래 규칙 (컬럼 타입 기준으로 길이만 비교) |
| 기본 타입(괄호 앞)이 다르다 | `MISMATCH` | `NOT_COMPARABLE` (타입이 다르면 길이의 뜻이 다르다) |
| 기본 타입이 같다 | `MATCH` | 도메인에 길이(0 은 미정의)·소수점 길이가 모두 없으면 `NOT_COMPARABLE`, 정의된 항목이 다르면 `MISMATCH`(길이 / 정밀도 / 소수 자릿수), 같으면 `MATCH` |

논리 측 값·물리 측 값은 비교에 쓴 DB 타입 문자열이다 (예: `VARCHAR(100)` / `VARCHAR(50)`, 타입 불일치는 `TIMESTAMP` / `BIGINT`).

### 2.5 필수 여부 (`REQUIRED`)

| 값 | 기준 |
|---|---|
| 논리 필수 | 필수 속성(`ESNTL_YN`) **또는 주 식별자 속성** — 논리 ERD 의 필수 표시(`*`)와 같다 |
| 물리 NOT NULL | PK **또는** 필수 입력(`ESNTL_YN`) **또는** NULL 허용 = N — `TableDdlGenerator` 가 `NOT NULL` 을 붙이는 기준과 같다. NULL 허용 값이 비어 있는 컬럼은 NOT NULL 이 아니다 |

같으면 `MATCH`, 다르면 `MISMATCH` 이고 방향을 사유로 구분한다 (필수 속성인데 컬럼이 NULL 을 허용 / 선택 속성인데 컬럼이 NOT NULL). 이것은 MetaMora 에 등록한 물리 모델의 값이며, 원본 DB 가 실제로 강제하는 제약과 다를 수 있다 (원본 비교는 M-10).

### 2.6 식별자 (`IDENTIFIER`)

| 경우 | 결과 |
|---|---|
| 엔터티에 주 식별자가 없다 | `NOT_COMPARABLE` |
| 주 식별자 속성 ↔ PK 컬럼, 또는 둘 다 아님 | `MATCH` |
| 주 식별자 속성인데 컬럼이 PK 가 아니다 / PK 컬럼인데 속성이 주 식별자가 아니다 | `MISMATCH` (사유를 구분) |

- **주 식별자 = PK 를 강제하는 정책은 없다.** 대리 키(일련번호 PK + 업무 식별자)처럼 의도한 차이도 `MISMATCH` 로 나온다 — "구성이 다르다"는 사실만 알려 주는 진단이고, 고칠지는 사용자가 판단한다.
- 속성 단위로만 본다. 주 식별자 속성이나 PK 컬럼이 매핑되지 않았으면 이 유형에는 나오지 않는다 (`MAPPING` 의 `UNMAPPED` 로 나온다). 보조 식별자 ↔ UNIQUE 는 비교하지 않는다.

## 3. 백엔드 (`metamora-data` `mapping/`)

| 구성 | 내용 |
|---|---|
| `GET /api/modelMapping/quality/summary?modelId` (VIEWER) | `ModelMappingQualityDto.Summary` : 엔터티·속성의 전체·매핑·미매핑 수(= `/status`, 같은 서비스 호출) + `match`·`mismatch`·`notComparable`·`unmapped`. `modelId` 필수 |
| `GET /api/modelMapping/quality/data?modelId&entityId&type&status&page&size&sort&searchColumn&searchQuery` (VIEWER) | 그리드 형식. 1행 = 모델·엔터티·속성·스키마·테이블·컬럼 + `type`·`status`·`logicalValue`·`physicalValue`·`reason`. 검색 `entityName`(기본)·`attributeName`·`columnName`, 정렬 `entityName`·`attributeName`·`columnName`·`type`·`status` (그 밖은 무시, 기본 엔터티명 → 속성 순서 → 진단 유형). 잘못된 `type`·`status` 는 400 |
| `GET /api/modelMapping/entity/lookup?entityId` 또는 `?tableId` (VIEWER) | 매핑 1건(`EntityRow`). 엔터티는 매핑이 없어도 1행(`mappingId` null), 테이블은 매핑된 경우만. 없거나 다른 프로젝트면 본문 없음(200). 둘 다 주거나 둘 다 없으면 `INVALID_MODEL_MAPPING` |
| `ModelMappingQualityService` | 읽기 전용. 요약과 목록이 **같은 `diagnose`** 를 쓴다 |
| `ModelMappingQueryRepository.findQualitySources` | 엔터티 × 속성에 매핑·컬럼·양쪽 용어·논리 속성의 도메인을 left join 한 DTO 프로젝션 1번 (엔티티·N+1 없음) + 주 식별자 속성 조회 1번 |
| `StandardizationQualityService.compareDomain` | M-5 의 도메인 비교를 public static 으로 꺼냈다 (로직 변경 없음) |
| `MenuAuthorization` | `model-mapping` = VIEWER (`logical-modeling` 그룹), `model-mapping-edit` = USER (버튼 노출용) |

**계산 방식 :** 저장 테이블·배치 없이 조회할 때 계산한다. 타입 매핑이 자바 코드라 진단 유형·상태는 SQL 로 거를 수 없다. 그래서 모델 1개의 속성을 (모델·엔터티·이름 조건은 DB 에서 걸러) 한 번에 읽고 메모리에서 진단 → 유형·상태 조건 → 정렬 → 페이징한다. 프로젝트 전체가 아니라 **모델 단위**다. 화면당 조회 : 요약 3번(현황 + 원본 + 주 식별자) + 목록 3번(모델 확인 + 원본 + 주 식별자) + 프로젝트 DB 타입.

**프로젝트 격리 :** 프로젝트는 항상 로그인 프로젝트. 다른 프로젝트의 `modelId` 는 `LOGICAL_MODEL_IS_NOT_DEFINED`(400), 다른 프로젝트·다른 모델의 `entityId` 는 결과 0건, lookup 은 본문 없음.

## 4. 프론트엔드 (`features/mapping/`)

**화면 :** 논리 모델 관리 > **논리·물리 매핑** (`/modelMapping`, 메뉴 ID `model-mapping`). 논리 모델을 고르고 탭 2개를 쓴다.

| 구성 | 내용 |
|---|---|
| `ModelMappingPage.tsx` | 모델 선택(`useLogicalModels`, `?modelId=`), 탭 [매핑 관리]·[정합성 비교] |
| 매핑 관리 탭 | **현황 카드 6개** (`/status`) : 전체·매핑 완료·미매핑 엔터티(누르면 매핑 여부 조건), 전체·매핑된·미매핑 속성. 매핑률은 전체가 0 이면 `-`. "매핑률은 정합성 점수가 아니다" 안내. **목록** : 공통 `Grid` + `/entity/data` (서버 페이징·정렬, 엔터티명·테이블명 검색, 매핑 여부·물리 스키마 조건). 행 : 엔터티(승인 대기 표시)·테이블·스키마·매핑 상태·속성 매핑 수·관리 버튼 |
| 엔터티 매핑 대화상자 (`EntityMappingForm`) | 스키마 선택 + 테이블명·한글명 검색 → `/table/data` 후보(매핑하지 않은 현재 프로젝트의 테이블, 10건씩 서버 페이징) → 선택 → 저장. 변경도 같은 대화상자 (`id` 포함). 저장 후 목록·현황 다시 조회 |
| 변경·해제 차단 안내 | 속성 매핑이 있는 행의 [테이블 변경]·[해제] 는 서버에 보내기 전에 "속성 매핑이 N건 있어 … [속성 매핑] 에서 먼저 해제" 를 알린다. 그래도 서버가 거절하면(`INVALID_MODEL_MAPPING`·`RELATION_EXISTS`·`SAVED_DATA_EXISTS`) 서버 메시지를 공통 알림으로 보여 준다. **속성 매핑을 자동으로 지우지 않는다** |
| 속성 ↔ 컬럼 매핑 대화상자 (`AttributeMappingDetail`) | 논리 속성 · 논리 표준 용어 ｜ 물리 컬럼 · 물리 표준 용어 ｜ 매핑 상태 ｜ 관리. 속성마다 컬럼을 골라 [매핑]/[변경], [해제]. 컬럼 후보는 상세 응답의 `unmappedColumns`(매핑된 테이블의 미연결 컬럼) + 현재 컬럼뿐이라 다른 속성의 컬럼은 고를 수 없고, 저장 전 다른 행에서 고른 컬럼도 뺀다. 컬럼 검색(컬럼명·설명·용어). 용어가 다르면 "표준 용어 다름" 표시만 한다. 아래에 매핑하지 않은 물리 컬럼 목록. VIEWER 는 조회만 |
| 정합성 비교 탭 (`MappingQuality.tsx`) | **요약 카드 5개** : 매핑된 속성 / 일치 / 불일치 / 비교 불가 / 미매핑 (누르면 그 상태만). 진단 유형·진단 결과 조건, 엔터티·속성·컬럼 검색, 서버 페이징. 진단 결과는 "표준 용어 불일치"처럼 유형을 함께 적는다. **행을 누르면 상세** : 판정, 모델·엔터티·속성·테이블·컬럼, 유형별 이름이 붙은 논리 측 값·물리 측 값, 사유, [매핑 관리에서 보기]. 수정 기능 없음 |
| 스타일 | `styles/mapping.css` (카드 6열, 후보 목록, 강조 행). 색은 `--gh-*` 토큰만 써서 다크 모드를 따른다 |
| 컬럼 폭 고정 | 공통 `Grid` 의 컬럼 정의에 `width`(선택)를 추가했다. 하나라도 주면 `<colgroup>` + `table-layout: fixed` 로 그려 페이지·검색·조건이 바뀌어도 컬럼 폭이 그대로다 (긴 값은 셀 안에서 줄바꿈). 매핑 목록·정합성 목록·속성 ↔ 컬럼 매핑 표와 함께 **다른 화면의 그리드도 전부** 폭을 지정했다 : 표준 용어·단어·도메인, 사용 현황(대화상자), 표준화 품질 진단, 테이블·컬럼 관리, 공지사항, 사용자·프로젝트 관리, 시스템 로그 (보이는 컬럼마다 %, 체크박스 컬럼은 40px), 그리고 공통 `Grid` 를 쓰지 않는 스키마 관리·논리 모델 관리(엔터티·관계) 목록. 헤드리스 Chrome 으로 화면마다 페이지 이동·빈 검색 결과에서 폭이 같고 가로 넘침·잘림이 없는 것을 확인했다 (ADMIN, 1440px, 다크 모드). 새 그리드를 만들 때는 보이는 컬럼에 `width` 를 준다 |
| 다국어 | 새 문구 105개를 `locales/en.ts` 에, 서버 메시지 26개를 `EnglishMessages` 에 등록 |

**기존 화면 연동**

| 화면 | 내용 |
|---|---|
| 엔터티 상세 대화상자 (논리 모델 관리·논리 ERD 공용) | 상태 줄에 "물리 테이블 `TB_…` · 속성 n / m 매핑" 또는 "미매핑", [매핑 관리] 버튼 → `/modelMapping?modelId=&entityId=` |
| 논리 ERD | 엔터티 패널에 [매핑 관리]. 노드 클릭·상세·배치 기능은 그대로. 저장하지 않은 배치가 있으면 기존 확인을 거친다 (상세 대화상자에서 이동할 때도) |
| 테이블 상세 대화상자 (테이블 관리·물리 ERD·사용 현황·품질 진단 공용) | "매핑된 논리 엔터티 : 엔터티명 (모델명)" 또는 "미매핑", 매핑돼 있으면 [매핑 관리] 버튼 |
| 물리 ERD | 테이블 패널 → [테이블 상세] 의 위 표시·버튼으로 이동한다 (배치 확인 포함). ERD 캔버스·패널 자체는 바꾸지 않았다 |

**URL 상태 :** `/modelMapping?modelId=1&entityId=10[&tab=quality]`. `modelId` 는 M-6 의 `useLogicalModels` 그대로(URL → 마지막 선택 → 첫 모델). `entityId` 가 고른 모델의 엔터티면 목록을 그 엔터티명으로 검색한 상태로 열고 행을 강조하며, 매핑돼 있으면 속성 매핑 상세를 한 번 연다. [전체 보기] 나 모델 변경으로 풀린다. 다른 프로젝트·다른 모델의 `entityId` 는 무시한다.

## 5. 권한

| 역할 | 화면 | API |
|---|---|---|
| NOT_APPROVE | 메뉴 없음, 주소로 들어오면 접근 거부 화면 | 조회 포함 전부 403 |
| VIEWER | 매핑 목록·현황·상세·정합성 조회. 매핑·변경·해제 버튼 없음 | 조회 200, 변경 403 |
| USER 이상 | + 매핑 등록·변경·해제 | 전부 |

버튼을 숨기는 것과 별개로 서버 `@PreAuthorize` 가 그대로 검사한다 (1차와 같다).

## 6. 검증

검증은 작업 트리를 복사한 별도 폴더에서 했다 (IDE 에서 실행 중인 애플리케이션의 빌드 산출물과 섞이지 않게). 개발 DB(`localhost:15439/metamora`)는 읽지도 쓰지도 않았다.

```bash
./gradlew clean build      # 354 tests, 실패 0, 건너뜀 2 (환경 변수가 필요한 테스트). npm ci + typecheck + vite build 포함
# PostgreSQL 18.6 : 빈 격리 DB(metamora_m7q_test)를 만들어 실행 후 삭제
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15439/metamora_m7q_test SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
METAMORA_TEST_POSTGRES_URL=jdbc:postgresql://localhost:15439/metamora_m7q_test \
./gradlew :metamora-app:test -PfrontendPrebuilt=true \
  --tests 'com.koboolean.metamora.ModelMappingQualityIntegrationTest' --tests 'com.koboolean.metamora.ModelMappingIntegrationTest' \
  --tests 'com.koboolean.metamora.LogicalModelIntegrationTest' --tests 'com.koboolean.metamora.StandardizationQualityIntegrationTest' \
  --tests 'com.koboolean.metamora.DictionaryUsageIntegrationTest' --tests 'com.koboolean.metamora.SmokeTest' \
  --tests 'com.koboolean.metamora.ReferenceSyncIntegrationTest' --tests 'com.koboolean.metamora.SchemaManagementIntegrationTest' \
  --tests 'com.koboolean.metamora.PhysicalModelIntegrationTest' --tests 'com.koboolean.metamora.ErdIntegrationTest' \
  --tests 'com.koboolean.metamora.DataPortalTemplateTest' --tests 'com.koboolean.metamora.TermUploadNameGenerationTest' \
  --tests 'com.koboolean.metamora.V0SeedMigrationTest'      # 110 tests, 실패 0, 건너뜀 0. Flyway 이력 V0~V3
```

### 6.1 자동 테스트

| 항목 | H2 | PostgreSQL 18.6 |
|---|---|---|
| `ModelMappingQualityServiceTest` 4건 (판정 규칙) : DBMS 별 타입 동등(Oracle `VARCHAR2`·`NUMBER(10)`)과 불일치, 길이·정밀도·소수 자릿수(미정의·0·타입에 적은 길이), 미매핑(엔터티 행은 한 번, 이름 검색 시 제외), 필수·식별자(주 식별자 = 필수, 주 식별자 없음) | 통과 (DB 불필요) | — |
| `ModelMappingQualityIntegrationTest` 1건 : 표준 용어(일치·다름·논리만·물리만·둘 다 없음), 명칭(일치·불일치·용어 없음·약어 없음), 타입(일치·불일치·용어·도메인·도메인 타입 없음), 길이(불일치·타입 불일치 시 비교 불가·소수 자릿수), 필수(양방향), 식별자(PK 불일치·주 식별자 없음), 미매핑 5건, 요약 = 매핑 현황 = 상태별 목록 건수, 불일치 11건(비교 불가 제외), 유형·상태·엔터티·이름 조건, 페이징·정렬(허용하지 않은 필드 무시), 조회 후 데이터 불변, 다른 프로젝트 격리, lookup, VIEWER 허용·NOT_APPROVE 거절 | 통과 | 통과 |
| `SmokeTest.modelMappingQuality` (HTTP : 요약 = 상태별 목록, lookup, `modelId` 누락·잘못된 `type`·`status` 400, 없는 모델, `/api/auth/me` 의 메뉴), `gridSortAndSearch` (매핑·정합성 그리드의 정렬 × 검색 조합 포함) | 통과 | 통과 |
| `MenuAuthorizationTest` (`model-mapping` VIEWER, `model-mapping-edit` USER, 미승인 숨김, 그룹) | 통과 | — |
| 회귀 : `ModelMappingIntegrationTest` 6 (1차 API), `StandardizationQualityServiceTest`·`IntegrationTest` (M-5, `compareDomain` 추출 뒤), `LogicalModelIntegrationTest` 8, `PhysicalModelIntegrationTest` 4, `ErdIntegrationTest` 7 (레이아웃 저장 포함), `ReferenceSyncIntegrationTest` 6, `SchemaManagementIntegrationTest` 4, `DictionaryUsageIntegrationTest` 1, `DataPortalTemplateTest` 3, `TermUploadNameGenerationTest` 3, `V0SeedMigrationTest`, `SmokeTest` 63 | 통과 | 통과 |
| `npm run typecheck`, `npm run build` | 통과 | — |

### 6.2 브라우저 검증

헤드리스 Chrome(별도 프로필)을 DevTools 프로토콜로 조작했다 (의존성 추가 없음). 대상은 빌드한 `metamora.jar` 를 `h2-local`(인메모리)·포트 19941 로 따로 띄운 인스턴스다. **확인 79건 모두 통과, 콘솔 오류 0** (HTTP 4xx 10건은 서버 거절을 확인하려고 일부러 보낸 요청이다).

| 구분 | 확인한 것 |
|---|---|
| 매핑 관리 (ADMIN) | 사이드바 메뉴, 현황 카드(37 / 0 / 37, 357 / 0 / 357, 매핑률), 엔터티명 검색, 2쪽 이동, 엔터티 매핑 대화상자(후보 10건 페이징·다음 쪽·검색·선택·저장 확인) → 목록·현황 갱신, 매핑된 테이블이 후보에서 빠짐, 속성 매핑 등록 3건·변경·해제·컬럼 검색, 다른 속성의 컬럼이 후보에 없음, "표준 용어 다름" 표시, 속성 매핑이 있을 때 [테이블 변경]·[해제] 차단 안내, 매핑 완료 카드·매핑 여부·물리 스키마 조건, 엔터티 매핑 변경(`TB_ACNT` → `TB_ACNT_ROLE`)·해제 후 엔터티·테이블 유지 |
| 서버 거절 (화면 세션에서 직접 호출) | 같은 테이블·같은 컬럼 중복 매핑 `SAVED_DATA_EXISTS`, 속성 매핑이 있는 매핑의 테이블 변경 `INVALID_MODEL_MAPPING` |
| 정합성 비교 | 요약 카드 = API, 진단 건수 = 매핑된 속성 × 6, 상태 카드·진단 유형·진단 결과 조건, 미매핑 목록 페이징, 일치·불일치 상세(양쪽 표준 용어·판정·사유), "표준 용어 불일치"·"명칭 불일치" 표시, 상세 → 매핑 관리, 매핑 상세 → 한 엔터티로 좁힌 정합성 비교, `?tab=quality` |
| URL·연동 | `?modelId=&entityId=` → 좁혀 보기·행 강조·상세 자동 열림·주소 유지·[전체 보기], 엔터티 상세의 매핑 표시(매핑·미매핑)·이동, 논리 ERD(포커스·엔터티 상세 유지, [매핑 관리] 이동), 테이블 상세의 매핑된 엔터티(매핑·미매핑)·이동, 물리 ERD(포커스 유지, 테이블 상세 → 이동) |
| 권한 | VIEWER : 메뉴·목록·상세·정합성 조회, 변경 버튼·입력 없음, 변경 API 403. USER : 버튼 표시, 속성 매핑 등록·해제. 미승인 : 메뉴 없음, 접근 거부 화면, 조회·변경 API 403 |
| 회귀 (화면 표시) | 표준 용어·단어·도메인, 표준화 품질 진단, 스키마·테이블·컬럼 관리, 논리 모델 관리 |
| 다크 모드 | 검증 환경이 다크 모드였다 — 매핑 목록·카드, 속성 매핑 대화상자, 진단 상세, 엔터티·테이블 상세를 화면 캡처로 확인 |

**미검증 (완료로 보지 않음)**

- 라이트 모드 화면 캡처, 영어 화면(문구 등록은 타입 검사로만 확인), 좁은 화면(1012px 이하), Chrome 이외 브라우저, 키보드만으로 조작.
- DBA·MANAGER 계정 화면 (USER 이상이라 USER 와 같은 버튼이 보이는 것은 권한 계층 단위 테스트로만 확인).
- 논리 ERD·물리 ERD 에서 **저장하지 않은 배치가 있는 상태**로 [매핑 관리] 를 눌렀을 때의 확인 대화상자 (기존 `leave` 를 그대로 호출하지만 화면에서 눌러 보지 않았다).
- PostgreSQL 을 붙인 상태의 화면 (브라우저 검증은 H2 인메모리로만 했다). 개발 DB 에서 실행 중인 애플리케이션의 화면.
- 성능 : 큰 모델에서의 정합성 조회 시간. 측정하지 않았다.
- 조회 실패 문구(현황·요약·후보 조회 실패), 동시에 두 사용자가 같은 컬럼을 고르는 경우의 화면 (서버 1:1 은 1차 동시성 테스트가 확인).

## 7. 확인된 사실 · 제약 · 후속

- **예제(META-MORA) : V4 로 전부 매핑되어 있다** (8장). 정합성 요약은 일치 1,913 / 불일치 0 / 비교 불가 229 / 미매핑 0 이다. 6.2 의 브라우저 검증은 V4 를 넣기 전(예제가 전부 미매핑인 상태)에 한 것이다.
- **대량 데이터 :** 모델 1개의 속성 × 6행을 조회마다 계산한다. 속성 수천 개 규모는 문제없을 크기지만 실행 시간·메모리를 재지 않았다. 모델 하나가 수만 속성이 되면 진단 결과 저장(AI-6)이나 SQL 로 걸러낼 수 있는 유형(매핑 여부·표준 용어·필수·식별자)의 분리가 필요하다.
- **정합성 진단 확장 후보 :** 논리 관계 ↔ 물리 FK, 보조 식별자 ↔ UNIQUE, 매핑되지 않은 물리 테이블·컬럼 목록(물리 기준 역방향), 논리 데이터 유형 ↔ 타입 분류(유형 코드 체계가 생기면), 엔터티명 ↔ 테이블 한글명.
- **물리명 추천 :** 3차에서 구현했다 ([physical-name-recommendation-m7.md](physical-name-recommendation-m7.md)). `NAME` 진단의 기대 컬럼명은 이제 추천 서비스(`PhysicalNameRecommendationService.recommendColumnName`)가 계산한다 (판정·사유는 그대로).
- **자동 매핑 후보 :** 같은 표준 용어 ID 를 가진 속성 ↔ 컬럼을 후보로 제안하는 기능은 없다 (지시서 제외). 지금은 속성 매핑 대화상자에서 양쪽 용어를 보고 직접 고른다.
- **ERD :** 논리 ERD·물리 ERD 에 매핑 여부를 노드에 표시하지 않는다 (패널·상세 대화상자에서만 보인다). 논리 ERD ↔ 물리 ERD 상호 이동(매핑된 상대 노드로 포커스)은 후속.
- 목록의 엔터티 포커스는 2차에서 엔터티명 부분 일치 검색이었다. 3차에서 **엔터티 ID 조건**(`/entity/data?entityId=`)으로 바꿔 그 엔터티 1건만 보인다. ERD 에서 저장하지 않은 배치가 있을 때의 이동 확인도 3차에서 화면으로 검증했다.

## 8. 예제 매핑 데이터 (V4)

`V4__mapping_example_data.sql` (postgresql·h2 같은 내용) : META-MORA 예제의 논리 모델과 물리 모델을 매핑 행으로 잇는다. **스키마는 바꾸지 않는다** (V0~V3 파일도 그대로).

| 대상 | 규칙 | 결과 (새 DB) |
|---|---|---|
| 엔터티 ↔ 테이블 | 프로젝트 0 의 `META-MORA` 모델 엔터티명 = 프로젝트 0 테이블의 한글명 (V1·V3 이 물리 예제의 한글명으로 엔터티를 만들었다) | 37건 (엔터티 37 전부) |
| 속성 ↔ 컬럼 | 위로 이어진 엔터티·테이블 안에서 같은 표준 용어(`STD_TRM_SN`)를 참조하는 속성과 컬럼 | 357건 (속성 357 전부) |

- **예제 데이터에만 해당하는 규칙이다.** 애플리케이션은 여전히 이름·용어가 같다고 자동으로 매핑하지 않는다 (1차 정책 "자동 매핑 없음"은 화면·API 의 동작에 대한 것이고 그대로다).
- **기존 DB 에서도 안전하게** : 이미 매핑된 엔터티·테이블·속성·컬럼은 건너뛰고(`not exists`), 짝이 하나로 정해지지 않는 경우(같은 한글명의 테이블이 여럿, 한 엔터티·테이블 안에 같은 용어가 여럿)도 건너뛴다. 그래서 UNIQUE 위반으로 실패하지 않고, 사용자가 등록한 매핑을 바꾸지 않는다. 다만 사용자가 예제의 짝(엔터티명 = 테이블 한글명)을 직접 매핑해 둔 경우 그 매핑의 **빠진 속성 매핑은 채운다.**
- 등록자는 `SYSTEM`. ID 는 시퀀스에서 받는다 (V1·V3 과 같다).
- **관리 규칙 (V3 의 규칙에 추가)** : 테이블·컬럼을 추가하는 마이그레이션은 COMMENT·예제(물리·논리)와 함께 **예제 매핑**도 넣는다. `V0SeedMigrationTest` 가 "예제의 엔터티·속성이 모두 매핑되어 있고, 엔터티명 = 테이블 한글명·같은 표준 용어끼리 이어져 있다"를 검사한다.
- 매핑된 원본은 삭제할 수 없다 (1차 정책) : 예제의 엔터티·속성·테이블·컬럼을 지우려면 이제 매핑을 먼저 해제해야 한다.

**검증 (V4 추가 후 다시 실행)**

| 항목 | 결과 |
|---|---|
| `./gradlew clean build` (H2) | 354 tests, 실패 0, 건너뜀 2 |
| PostgreSQL 18.6 빈 격리 DB (`metamora_m7q_test`, 실행 후 삭제) | 6장과 같은 110 tests, 실패 0. Flyway 이력 V0~V4 |
| 기존 데이터가 있는 DB : 개발 DB 를 `pg_dump` 로 복사한 격리 DB (`metamora_m7q_upgrade`, 실행 후 삭제) | V0~V3 체크섬 변화 없이 V4 만 적용. 복사본에는 사용자가 등록한 `계정 ↔ TB_ACNT` 매핑(속성 매핑 0건)이 있었고, V4 뒤 엔터티 매핑 37건(기존 1 + 추가 36)·속성 매핑 357건이 됐다. 이어서 매핑·정합성·논리·물리 통합 테스트 19건 통과 |
| 예제의 정합성 요약 (빌드한 jar, H2 인메모리) | 엔터티 37 / 37, 속성 357 / 357 매핑. 일치 1,913, 불일치 0, 비교 불가 229 (도메인에 길이 없음 223, 주 식별자 없는 엔터티 6), 미매핑 0. M-5 품질 진단도 0건 그대로 |
| `SmokeTest.modelMapping` | 예제의 계정 매핑을 해제 → 등록·오류 코드 확인 → 같은 짝으로 복원하도록 바꿨다 |

- **개발 DB(`localhost:15439/metamora`)에는 직접 적용하지 않았다.** IDE 에서 실행 중인 애플리케이션이 다시 시작할 때 Flyway 가 V4 를 적용한다 (위 복사본 검증과 같은 경로). 적용된 뒤에는 V4 파일을 바꾸면 안 된다.
- V4 를 넣은 뒤의 화면은 브라우저로 다시 확인하지 않았다 (API 응답만 확인).
