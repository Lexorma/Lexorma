# DB 명명 규칙 (공통표준 기반)

> Lexorma 자체 DB 의 테이블·컬럼·시퀀스 이름 규칙과 전체 목록. 기준은 `lexorma-app/src/main/resources/static/excel/dataPortal.xlsx`
> (공공데이터 공통표준용어·단어·도메인). Flyway `V0__init.sql` (postgresql·h2) 과 JPA 엔티티 `@Table`/`@Column` 이 이 문서와 같아야 한다.

## 1. 규칙

- 테이블 : `TB_` + 표준 단어 영문약어 (`TB_TBL`, `TB_STD_WORD`). 다대다·값 목록 테이블도 같은 규칙 (`TB_ACNT_ROLE`, `TB_STD_WORD_SYNM`).
- 컬럼 : 한글 용어를 표준 단어로 나눠 영문약어를 `_` 로 잇는다 (`테이블_한글_명` → `TBL_KORN_NM`). 공통표준용어에 같은 용어가 있으면 그 약어를 쓴다 (`등록_일시` REG_DT, `IP_주소` IP_ADDR, `삭제_여부` DEL_YN).
- 숫자 대리키 : `{대상}_SN` (일련번호). 외래 키 컬럼은 참조하는 키와 같은 이름 (`TBL_SN`), 역할이 다르면 앞에 수식어 (`ORGNL_TBL_SN`, `MNGR_ACNT_SN`).
- 공통 감사 컬럼 (`BaseEntity`) : `REG_DT` 등록_일시, `MDFCN_DT` 수정_일시, `RGTR_ID` 등록자_아이디, `MDFR_ID` 수정자_아이디.
- 시퀀스 : `SQ_` + 테이블명에서 `TB_` 를 뺀 이름 (`SQ_TBL`). pooled, 50개씩 할당.
- 제약·인덱스 : `PK_{테이블}`, `FK_{테이블}_{nn}`, `IX_{테이블}_{nn}`, `UX_{테이블}_{nn}` (유니크).
- 이름은 따옴표 없이 쓴다. PostgreSQL 은 소문자, H2 는 대문자로 저장되지만 같은 이름으로 동작한다.
- 제외 : `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` (spring-session-jdbc 고정 스키마), `flyway_schema_history`.

> 화면의 **물리명 추천**(논리·물리 매핑 > 물리명 추천)도 같은 사전을 근거로 한다 : 컬럼명 = 표준 용어의 영문약어, 테이블명 = 접두어 + 승인된 표준 단어의 영문약어. 정책은 [physical-name-recommendation-m7.md](physical-name-recommendation-m7.md).

## 예제 데이터

`V1__seed_data.sql` 에서 기본 프로젝트 `LEXORMA`(PRJ_SN 0, 테이블 접두어 `TB_`)에 예제를 넣는다. 모두 승인 상태.

- 데이터 사전 : 이 문서의 이름에 쓰인 단어 113개(공통표준 71 + 2장의 기관 표준단어 42), 컬럼 용어 131개(공통표준 용어와 같은 27개는 설명·이음동의어를 그대로), 그 용어의 도메인 29개(공통표준 8개와 자체 도메인 21개), 용어-단어 매핑.
  **용어의 도메인은 그 용어를 쓰는 실제 컬럼의 타입·길이와 같아야 한다** (표준화 품질 진단의 도메인 정의 불일치가 0건, `SmokeTest.standardizationQuality`). 마지막 단어(형식단어)의 도메인 분류에서 컬럼 정의와 같은 공통표준 도메인이 있으면 그것을 쓰고, 없으면 같은 분류의 자체 도메인을 만든다. 공통표준 용어도 실제 컬럼과 다르면 자체 도메인으로 연결한다 (예: `등록자_아이디` 는 공통표준에서 명V256 이지만 컬럼이 `VARCHAR(255)` 라 명V255).

  자체 도메인 이름은 공통표준과 같이 `분류명 + 타입 문자 + 길이` 다. `V`(VARCHAR)·`N`(NUMERIC)은 공통표준 표기이고, 공통표준에 없는 타입은 자체 문자를 쓴다.

  | 타입 문자 | 타입 | 자체 도메인 |
  | --- | --- | --- |
  | `V` | `VARCHAR(n)` | `값V255`, `비밀번호V255`, `명V255`·`명V30`·`명V16`·`명V15`, `내용V255`, `주소V255`, `코드V20`·`코드V6`, `여부V255` |
  | `N` | `NUMERIC(p,s)` | `수N38,2` |
  | `L` | `BIGINT` | `일련번호L`, `번호L` |
  | `I` | `INTEGER` | `수I`, `코드I` |
  | `B` | `BOOLEAN` | `여부B` |
  | `T` | `TEXT` | `내용T` |
  | `F` | `DOUBLE PRECISION` | `수F`, `비율F` |
  | `A` | `BIGINT ARRAY` | `목록A` (고정 길이 없음) |

  `값V255` 는 기본값·SQL 표현식의 문자열 표현, `비밀번호V255` 는 인코더 접두사를 포함한 비밀번호 해시(평문 길이 규칙이 아님)다. 그대로 쓰는 공통표준 도메인은 내용V1000·내용V2000·명V20·명V100·명V200·명V300·여부C1·연월일시분초D 다 (`DATETIME` 은 PostgreSQL 에서 `TIMESTAMP` 로 만들어지므로 일치).
  자체 도메인은 공공데이터 공통표준이 아닌 Lexorma 자체 정의다. 배열은 기존 PostgreSQL·H2 모델을 유지하며 다른 DBMS의 배열 지원을 추가하지 않는다. 스키마(컬럼 타입·길이)를 바꾸면 예제 컬럼과 그 용어의 도메인도 함께 바꾼다.
- 물리 모델 : DEFAULT 스키마에 이 문서의 테이블(한글명 = 표준 단어를 공백으로 이은 이름)·컬럼(표준 용어 연결, 설명 = 한글 용어). 외래 키는 컬럼의 참조 테이블명 + `REF_COLUMN` 관계.
- 테이블 한글명을 표준 단어로 바꾸면 같은 테이블명이 나온다 (`ErdIntegrationTest.seededExample`).
마이그레이션은 PostgreSQL·H2 양쪽의 `V0__init.sql` 하나로 관리한다. 스키마 변경 시 예제 모델도 함께 확인한다.

## 2. 기관 표준단어 (공통표준에 없는 단어)

공통표준 단어에 없거나, 같은 약어가 다른 뜻으로 등록돼 있어 Lexorma 에서 정한 단어. 데이터 사전에 프로젝트 단어로 등록해 쓸 수 있다.

| 단어 | 영문약어 |
|---|---|
| DB | `DB` |
| ERD | `ERD` |
| NULL | `NULL` |
| X | `X` |
| Y | `Y` |
| 공통 | `CMN` |
| 금칙 | `PRHBT` |
| 기본키 | `PK` |
| 길이 | `LEN` |
| 노드 | `NODE` (공통표준 `NODE` = 교점) |
| 단어 | `WORD` |
| 도메인 | `DMN` |
| 레이아웃 | `LYT` |
| 마스터 | `MSTR` |
| 매핑 | `MPNG` |
| 목록 | `LIST` |
| 민감 | `SNSTV` |
| 배율 | `MGNF` |
| 버전 | `VER` |
| 사전 | `DIC` |
| 소관 | `JRSDC` |
| 소수점 | `DCPT` |
| 순서 | `ORD` |
| 스키마 | `SCHM` |
| 약어 | `ABBR` |
| 에이전트 | `AGNT` |
| 역할 | `ROLE` |
| 예시 | `EXMPL` |
| 용어 | `TRM` |
| 이음동의어 | `SYNM` |
| 인덱스 | `IDX` |
| 자동 | `AUTO` |
| 접두어 | `PFX` |
| 정밀도 | `PRCSN` |
| 제약조건 | `CNSTRT` |
| 제정 | `ENCT` |
| 좌표 | `CRDNT` |
| 최대 | `MAX` |
| 파생 | `DRVN` |
| 표현식 | `EXPR` |
| 프로젝트 | `PRJ` |
| 허용 | `ALWNC` |
| 형식 | `FRMT` |

- `용어` 는 공통표준 `TERM`(기한)과 겹쳐 `TRM`, `허용` 은 `PRMSN`(허가)과 겹쳐 `ALWNC` 로 정했다.
- `노드` 는 공통표준 단어 `교점`(NODE) 의 약어를 그대로 쓴다.
- 원본 카탈로그 수집 실행·스냅샷(AI-1c)에서 쓴 단어는 모두 공통표준단어다 (기관 표준단어가 아니다) : `수집`(CLCT), `실행`(EXCN), `작업`(JOB), `진행`(PRGRS), `반영`(RFLT), `서버`(SRVR), `시작`(BGNG), `최종`(LAST), `확인`(IDNTY), `종료`(END), `결과`(RSLT), `생성`(CRT), `항목`(ARTCL). 데이터 사전 예제에 단어로 넣었다. `범위` 는 공통표준에 없어 수집 대상 테이블을 `대상`(TRGT)으로 이름 지었다.
- 원본 카탈로그(AI-1b)에서 더한 단어 : `호스트`(HOST), `포트`(PORT), `옵션`(OPTN), `카탈로그`(CTLG), `비교`(CMPR), `존재`(EXST), `해시`(HASH), `규칙`(RULE), `조건`(CND), `따옴표`(QUOT). `주석` 은 공통표준 `댓글`(CMNT)과 겹쳐 `ANTN` 으로 정했다.

## 3. 테이블·컬럼 목록

### TB_ACNT (계정)

시퀀스 : `SQ_ACNT`

| 컬럼 | 한글 용어 |
|---|---|
| `ACNT_SN` | 계정_일련번호 |
| `USER_ID` | 사용자_아이디 |
| `PSWD` | 비밀번호 |
| `USER_NM` | 사용자_명 |
| `ACTVTN_YN` | 활성_여부 |
| `PSWD_CHG_YN` | 비밀번호_변경_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_ACNT_ROLE (계정 역할)

시퀀스 : 없음 (조인·값 목록 테이블)

| 컬럼 | 한글 용어 |
|---|---|
| `ACNT_SN` | 계정_일련번호 |
| `ROLE_SN` | 역할_일련번호 |

### TB_ROLE (역할)

시퀀스 : `SQ_ROLE`

| 컬럼 | 한글 용어 |
|---|---|
| `ROLE_SN` | 역할_일련번호 |
| `ROLE_NM` | 역할_명 |
| `ROLE_EXPLN` | 역할_설명 |
| `EXPR_YN` | 표현식_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_PRJ (프로젝트)

시퀀스 : `SQ_PRJ`

| 컬럼 | 한글 용어 |
|---|---|
| `PRJ_SN` | 프로젝트_일련번호 |
| `PRJ_NM` | 프로젝트_명 |
| `MNGR_ACNT_SN` | 관리자_계정_일련번호 |
| `ACTVTN_YN` | 활성_여부 |
| `AUTO_APRV_YN` | 자동_승인_여부 |
| `DIC_ABBR_USE_YN` | 사전_약어_사용_여부 |
| `TBL_PFX_NM` | 테이블_접두어_명 |
| `DB_TYPE_CD` | DB_유형_코드 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_PRJ_MBR (프로젝트 회원)

시퀀스 : `SQ_PRJ_MBR`

| 컬럼 | 한글 용어 |
|---|---|
| `PRJ_MBR_SN` | 프로젝트_회원_일련번호 |
| `ACNT_SN` | 계정_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `ACTVTN_YN` | 활성_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_LOG (로그)

시퀀스 : IDENTITY

| 컬럼 | 한글 용어 |
|---|---|
| `LOG_SN` | 로그_일련번호 |
| `DMND_URL_ADDR` | 요청_URL_주소 |
| `DMND_MTHD_NM` | 요청_방법_명 |
| `CNTN_IP_ADDR` | 접속_IP_주소 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `USER_ID` | 사용자_아이디 |
| `ROLE_NM` | 역할_명 |
| `RSPNS_STTS_CD` | 응답_상태_코드 |
| `DMND_CN` | 요청_내용 |
| `RSPNS_CN` | 응답_내용 |
| `ERR_CN` | 오류_내용 |
| `USER_AGNT_CN` | 사용자_에이전트_내용 |
| `LOG_DT` | 로그_일시 |

### TB_PST (게시물)

시퀀스 : `SQ_PST`

| 컬럼 | 한글 용어 |
|---|---|
| `PST_SN` | 게시물_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `WRTR_ID` | 작성자_아이디 |
| `PST_TTL` | 게시물_제목 |
| `PST_CN` | 게시물_내용 |
| `DEL_YN` | 삭제_여부 |
| `PST_MDFCN_DT` | 게시물_수정_일시 |
| `CTGRY_CD` | 범주_코드 |
| `PRJ_SN_LIST` | 프로젝트_일련번호_목록 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_PST_ACNT (게시물 계정)

시퀀스 : 없음 (조인·값 목록 테이블)

| 컬럼 | 한글 용어 |
|---|---|
| `PST_SN` | 게시물_일련번호 |
| `ACNT_SN` | 계정_일련번호 |

### TB_PST_CTGRY (게시물 범주)

시퀀스 : 없음 (조인·값 목록 테이블)

| 컬럼 | 한글 용어 |
|---|---|
| `CTGRY_CD` | 범주_코드 |
| `CTGRY_NM` | 범주_명 |
| `SORT_ORD` | 정렬_순서 |
| `USE_YN` | 사용_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_ATCH_FILE (첨부 파일)

시퀀스 : `SQ_ATCH_FILE`

| 컬럼 | 한글 용어 |
|---|---|
| `FILE_SN` | 파일_일련번호 |
| `PST_SN` | 게시물_일련번호 |
| `FILE_NM` | 파일_명 |
| `ORGNL_FILE_NM` | 원본_파일_명 |
| `FILE_EXTN_NM` | 파일_확장자_명 |
| `FILE_PATH_NM` | 파일_경로_명 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_STD_WORD (표준 단어)

시퀀스 : `SQ_STD_WORD`

| 컬럼 | 한글 용어 |
|---|---|
| `STD_WORD_SN` | 표준_단어_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `ENCT_CYCL` | 제정_차수 |
| `WORD_NM` | 단어_명 |
| `WORD_ENG_ABBR_NM` | 단어_영문_약어_명 |
| `WORD_ENG_NM` | 단어_영문_명 |
| `WORD_EXPLN` | 단어_설명 |
| `FRMT_WORD_YN` | 형식_단어_여부 |
| `DMN_CLSF_NM` | 도메인_분류_명 |
| `ABBR_USE_YN` | 약어_사용_여부 |
| `APRV_YN` | 승인_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_STD_WORD_SYNM (표준 단어 이음동의어)

시퀀스 : 없음 (조인·값 목록 테이블)

| 컬럼 | 한글 용어 |
|---|---|
| `STD_WORD_SN` | 표준_단어_일련번호 |
| `SYNM_NM` | 이음동의어_명 |

### TB_STD_WORD_PRHBT (표준 단어 금칙)

시퀀스 : 없음 (조인·값 목록 테이블)

| 컬럼 | 한글 용어 |
|---|---|
| `STD_WORD_SN` | 표준_단어_일련번호 |
| `PRHBT_WORD_NM` | 금칙_단어_명 |

### TB_STD_TRM (표준 용어)

시퀀스 : `SQ_STD_TRM`

| 컬럼 | 한글 용어 |
|---|---|
| `STD_TRM_SN` | 표준_용어_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `ENCT_CYCL` | 제정_차수 |
| `TRM_NM` | 용어_명 |
| `TRM_EXPLN` | 용어_설명 |
| `TRM_ENG_ABBR_NM` | 용어_영문_약어_명 |
| `PBADMS_STD_CD_NM` | 행정_표준_코드_명 |
| `JRSDC_INST_NM` | 소관_기관_명 |
| `APRV_YN` | 승인_여부 |
| `STD_DMN_SN` | 표준_도메인_일련번호 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_STD_TRM_SYNM (표준 용어 이음동의어)

시퀀스 : 없음 (조인·값 목록 테이블)

| 컬럼 | 한글 용어 |
|---|---|
| `STD_TRM_SN` | 표준_용어_일련번호 |
| `SYNM_NM` | 이음동의어_명 |

### TB_STD_TRM_WORD (표준 용어 단어)

시퀀스 : `SQ_STD_TRM_WORD`

| 컬럼 | 한글 용어 |
|---|---|
| `STD_TRM_WORD_SN` | 표준_용어_단어_일련번호 |
| `STD_TRM_SN` | 표준_용어_일련번호 |
| `STD_WORD_SN` | 표준_단어_일련번호 |
| `SORT_ORD` | 정렬_순서 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_STD_DMN (표준 도메인)

시퀀스 : `SQ_STD_DMN`

| 컬럼 | 한글 용어 |
|---|---|
| `STD_DMN_SN` | 표준_도메인_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `ENCT_CYCL` | 제정_차수 |
| `DMN_GROUP_NM` | 도메인_그룹_명 |
| `DMN_CLSF_NM` | 도메인_분류_명 |
| `DMN_NM` | 도메인_명 |
| `DMN_EXPLN` | 도메인_설명 |
| `DATA_TYPE_NM` | 자료_유형_명 |
| `DATA_LEN` | 자료_길이 |
| `DATA_DCPT_LEN` | 자료_소수점_길이 |
| `STRG_FRMT_CN` | 저장_형식_내용 |
| `INDCT_FRMT_CN` | 표시_형식_내용 |
| `UNIT_NM` | 단위_명 |
| `ALWNC_VL_CN` | 허용_값_내용 |
| `APRV_YN` | 승인_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_SCHM (스키마)

시퀀스 : `SQ_SCHM`

| 컬럼 | 한글 용어 |
|---|---|
| `SCHM_SN` | 스키마_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SCHM_NM` | 스키마_명 |
| `SCHM_EXPLN` | 스키마_설명 |
| `BSC_YN` | 기본_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_TBL (테이블)

시퀀스 : `SQ_TBL`

| 컬럼 | 한글 용어 |
|---|---|
| `TBL_SN` | 테이블_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SCHM_SN` | 스키마_일련번호 |
| `TBL_NM` | 테이블_명 |
| `TBL_KORN_NM` | 테이블_한글_명 |
| `TBL_EXPLN` | 테이블_설명 |
| `MSTR_TBL_YN` | 마스터_테이블_여부 |
| `SORT_ORD` | 정렬_순서 |
| `APRV_YN` | 승인_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_COL (컬럼)

시퀀스 : `SQ_COL`

| 컬럼 | 한글 용어 |
|---|---|
| `COL_SN` | 컬럼_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `TBL_SN` | 테이블_일련번호 |
| `TBL_NM` | 테이블_명 |
| `COL_NM` | 컬럼_명 |
| `COL_EXPLN` | 컬럼_설명 |
| `DATA_TYPE_NM` | 자료_유형_명 |
| `MAX_LEN` | 최대_길이 |
| `DATA_PRCSN` | 자료_정밀도 |
| `DCPT_LEN` | 소수점_길이 |
| `NULL_ALWNC_YN` | NULL_허용_여부 |
| `BSC_VL` | 기본_값 |
| `SORT_ORD` | 정렬_순서 |
| `MSTR_DATA_YN` | 마스터_자료_여부 |
| `RFRNC_TBL_NM` | 참조_테이블_명 |
| `ESNTL_YN` | 필수_여부 |
| `SNSTV_INFO_YN` | 민감_정보_여부 |
| `PK_YN` | 기본키_여부 |
| `UNQ_YN` | 고유_여부 |
| `IDX_YN` | 인덱스_여부 |
| `ENCPT_YN` | 암호화_여부 |
| `EXMPL_CN` | 예시_내용 |
| `APRV_YN` | 승인_여부 |
| `STD_TRM_SN` | 표준_용어_일련번호 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_CMN_COL (공통 컬럼)

시퀀스 : `SQ_CMN_COL`

| 컬럼 | 한글 용어 |
|---|---|
| `CMN_COL_SN` | 공통_컬럼_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `STD_TRM_SN` | 표준_용어_일련번호 |
| `SORT_ORD` | 정렬_순서 |
| `NULL_ALWNC_YN` | NULL_허용_여부 |
| `IDX_YN` | 인덱스_여부 |
| `BSC_VL` | 기본_값 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_TBL_REL (테이블 관계)

시퀀스 : `SQ_TBL_REL`

| 컬럼 | 한글 용어 |
|---|---|
| `REL_SN` | 관계_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `ORGNL_TBL_SN` | 원본_테이블_일련번호 |
| `TRGT_TBL_SN` | 대상_테이블_일련번호 |
| `REL_NM` | 관계_명 |
| `REL_TYPE_CD` | 관계_유형_코드 |
| `REL_SRC_CD` | 관계_출처_코드 |
| `DRVN_COL_SN` | 파생_컬럼_일련번호 |
| `APRV_YN` | 승인_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_TBL_REL_COL (테이블 관계 컬럼)

시퀀스 : `SQ_TBL_REL_COL`

| 컬럼 | 한글 용어 |
|---|---|
| `REL_COL_SN` | 관계_컬럼_일련번호 |
| `REL_SN` | 관계_일련번호 |
| `ORGNL_COL_SN` | 원본_컬럼_일련번호 |
| `TRGT_COL_SN` | 대상_컬럼_일련번호 |
| `COL_ORD` | 컬럼_순서 |

### TB_TBL_IDX (테이블 인덱스)

시퀀스 : `SQ_TBL_IDX`

| 컬럼 | 한글 용어 |
|---|---|
| `IDX_SN` | 인덱스_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `TBL_SN` | 테이블_일련번호 |
| `IDX_NM` | 인덱스_명 |
| `IDX_TYPE_NM` | 인덱스_유형_명 |
| `UNQ_YN` | 고유_여부 |
| `APRV_YN` | 승인_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_TBL_IDX_COL (테이블 인덱스 컬럼)

시퀀스 : `SQ_TBL_IDX_COL`

| 컬럼 | 한글 용어 |
|---|---|
| `IDX_COL_SN` | 인덱스_컬럼_일련번호 |
| `IDX_SN` | 인덱스_일련번호 |
| `COL_SN` | 컬럼_일련번호 |
| `COL_ORD` | 컬럼_순서 |

### TB_TBL_CNSTRT (테이블 제약조건)

시퀀스 : `SQ_TBL_CNSTRT`

| 컬럼 | 한글 용어 |
|---|---|
| `CNSTRT_SN` | 제약조건_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `TBL_SN` | 테이블_일련번호 |
| `CNSTRT_NM` | 제약조건_명 |
| `CNSTRT_TYPE_CD` | 제약조건_유형_코드 |
| `INSP_EXPR_CN` | 검사_표현식_내용 |
| `APRV_YN` | 승인_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_TBL_CNSTRT_COL (테이블 제약조건 컬럼)

시퀀스 : `SQ_TBL_CNSTRT_COL`

| 컬럼 | 한글 용어 |
|---|---|
| `CNSTRT_COL_SN` | 제약조건_컬럼_일련번호 |
| `CNSTRT_SN` | 제약조건_일련번호 |
| `COL_SN` | 컬럼_일련번호 |
| `COL_ORD` | 컬럼_순서 |

### TB_ERD_LYT (ERD 레이아웃)

시퀀스 : `SQ_ERD_LYT`

| 컬럼 | 한글 용어 |
|---|---|
| `ERD_LYT_SN` | ERD_레이아웃_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `ACNT_SN` | 계정_일련번호 |
| `NODE_PSTN_CN` | 노드_위치_내용 |
| `SCRN_X_CRDNT` | 화면_X_좌표 |
| `SCRN_Y_CRDNT` | 화면_Y_좌표 |
| `SCRN_MGNF` | 화면_배율 |
| `VER_NO` | 버전_번호 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_ENTT_TBL_MPNG (엔터티 테이블 매핑)

시퀀스 : `SQ_ENTT_TBL_MPNG`. `V2__logical_physical_mapping.sql` 에서 만든다 (V0 의 예제 물리 모델에는 없다).

| 컬럼 | 한글 용어 |
|---|---|
| `ENTT_TBL_MPNG_SN` | 엔터티_테이블_매핑_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `LGCL_ENTT_SN` | 논리_엔터티_일련번호 |
| `TBL_SN` | 테이블_일련번호 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_ATRB_COL_MPNG (속성 컬럼 매핑)

시퀀스 : `SQ_ATRB_COL_MPNG`. `V2__logical_physical_mapping.sql` 에서 만든다 (V0 의 예제 물리 모델에는 없다).

| 컬럼 | 한글 용어 |
|---|---|
| `ATRB_COL_MPNG_SN` | 속성_컬럼_매핑_일련번호 |
| `ENTT_TBL_MPNG_SN` | 엔터티_테이블_매핑_일련번호 |
| `LGCL_ATRB_SN` | 논리_속성_일련번호 |
| `COL_SN` | 컬럼_일련번호 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_MDL_CHG_HSTRY (모델 변경 이력)

시퀀스 : `SQ_MDL_CHG_HSTRY`. `V5__model_change_history.sql` 에서 만든다 (M-9). 추가만 하는 이력이라 `BaseEntity` 감사 컬럼 대신 변경한 사용자·일시를 직접 둔다. 대상이 삭제돼도 남도록 모델 테이블로 가는 FK 가 없다.
새 단어 `이력`(HSTRY)·`이전`(BFR)·`이후`(AFTR)는 공통표준단어, `변경_일시` 는 공통표준용어다. 설명은 [model-change-history-m9.md](model-change-history-m9.md).

| 컬럼 | 한글 용어 |
|---|---|
| `MDL_CHG_HSTRY_SN` | 모델_변경_이력_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `CHG_GROUP_ID` | 변경_그룹_아이디 |
| `TRGT_TYPE_CD` | 대상_유형_코드 |
| `TRGT_SN` | 대상_일련번호 |
| `TRGT_NM` | 대상_명 |
| `CHG_TYPE_CD` | 변경_유형_코드 |
| `CHG_BFR_CN` | 변경_이전_내용 |
| `CHG_AFTR_CN` | 변경_이후_내용 |
| `CHG_SRC_CD` | 변경_출처_코드 |
| `USER_ID` | 사용자_아이디 |
| `CHG_DT` | 변경_일시 |

## 4. 원본 카탈로그 (AI-1b)

프로젝트의 외부 DB 연결과 수집한 원본 구조 (`lexorma-source` 모듈). `SRC`(출처)는 원본 DB 쪽을 뜻한다 — `ORGNL`(원본)은 관계의 원본 테이블로 이미 쓰고 있다. 설명은 [source-catalog-ai1b.md](source-catalog-ai1b.md).

### TB_SRC_CNTN (출처 접속)

시퀀스 : `SQ_SRC_CNTN`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_CNTN_SN` | 출처_접속_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `CNTN_NM` | 접속_명 |
| `DB_TYPE_CD` | DB_유형_코드 |
| `HOST_NM` | 호스트_명 |
| `PORT_NO` | 포트_번호 |
| `DB_NM` | DB_명 |
| `DB_NM_TYPE_CD` | DB_명_유형_코드 |
| `CNTN_USER_NM` | 접속_사용자_명 |
| `CNTN_PSWD_CN` | 접속_비밀번호_내용 |
| `CNTN_OPTN_CN` | 접속_옵션_내용 |
| `IDNTFR_CMPR_CD` | 식별자_비교_코드 |
| `CNTN_EXPLN` | 접속_설명 |
| `ACTVTN_YN` | 활성_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_SRC_SCHM (출처 스키마)

시퀀스 : `SQ_SRC_SCHM`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_SCHM_SN` | 출처_스키마_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_CNTN_SN` | 출처_접속_일련번호 |
| `CTLG_NM` | 카탈로그_명 |
| `SCHM_NM` | 스키마_명 |
| `PATH_CN` | 경로_내용 |
| `EXST_YN` | 존재_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |
| `CRT_CYCL` | 생성_차수 |

### TB_SRC_TBL (출처 테이블)

시퀀스 : `SQ_SRC_TBL`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_TBL_SN` | 출처_테이블_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_CNTN_SN` | 출처_접속_일련번호 |
| `SRC_SCHM_SN` | 출처_스키마_일련번호 |
| `TBL_NM` | 테이블_명 |
| `CMPR_NM` | 비교_명 |
| `QUOT_YN` | 따옴표_여부 |
| `TBL_TYPE_CD` | 테이블_유형_코드 |
| `ANTN_CN` | 주석_내용 |
| `HASH_VL` | 해시_값 |
| `EXST_YN` | 존재_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |
| `CRT_CYCL` | 생성_차수 |

### TB_SRC_COL (출처 컬럼)

시퀀스 : `SQ_SRC_COL`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_COL_SN` | 출처_컬럼_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_TBL_SN` | 출처_테이블_일련번호 |
| `COL_NM` | 컬럼_명 |
| `CMPR_NM` | 비교_명 |
| `QUOT_YN` | 따옴표_여부 |
| `COL_ORD` | 컬럼_순서 |
| `DATA_TYPE_NM` | 자료_유형_명 |
| `MAX_LEN` | 최대_길이 |
| `DATA_PRCSN` | 자료_정밀도 |
| `DCPT_LEN` | 소수점_길이 |
| `NULL_ALWNC_YN` | NULL_허용_여부 |
| `BSC_VL_CN` | 기본_값_내용 |
| `ANTN_CN` | 주석_내용 |
| `HASH_VL` | 해시_값 |
| `EXST_YN` | 존재_여부 |
| `REG_DT` | 등록_일시 |
| `MDFCN_DT` | 수정_일시 |
| `RGTR_ID` | 등록자_아이디 |
| `MDFR_ID` | 수정자_아이디 |

### TB_SRC_CNSTRT (출처 제약조건)

시퀀스 : `SQ_SRC_CNSTRT`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_CNSTRT_SN` | 출처_제약조건_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_TBL_SN` | 출처_테이블_일련번호 |
| `CNSTRT_NM` | 제약조건_명 |
| `CNSTRT_TYPE_CD` | 제약조건_유형_코드 |
| `INSP_CND_CN` | 검사_조건_내용 |

### TB_SRC_CNSTRT_COL (출처 제약조건 컬럼)

시퀀스 : `SQ_SRC_CNSTRT_COL`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_CNSTRT_COL_SN` | 출처_제약조건_컬럼_일련번호 |
| `SRC_CNSTRT_SN` | 출처_제약조건_일련번호 |
| `SRC_COL_SN` | 출처_컬럼_일련번호 |
| `COL_ORD` | 컬럼_순서 |

### TB_SRC_IDX (출처 인덱스)

시퀀스 : `SQ_SRC_IDX`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_IDX_SN` | 출처_인덱스_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_TBL_SN` | 출처_테이블_일련번호 |
| `IDX_NM` | 인덱스_명 |
| `IDX_TYPE_NM` | 인덱스_유형_명 |
| `UNQ_YN` | 고유_여부 |

### TB_SRC_IDX_COL (출처 인덱스 컬럼)

시퀀스 : `SQ_SRC_IDX_COL`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_IDX_COL_SN` | 출처_인덱스_컬럼_일련번호 |
| `SRC_IDX_SN` | 출처_인덱스_일련번호 |
| `SRC_COL_SN` | 출처_컬럼_일련번호 |
| `COL_ORD` | 컬럼_순서 |

### TB_SRC_REL (출처 관계)

시퀀스 : `SQ_SRC_REL`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_REL_SN` | 출처_관계_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_CNTN_SN` | 출처_접속_일련번호 |
| `SRC_TBL_SN` | 출처_테이블_일련번호 |
| `RFRNC_SRC_TBL_SN` | 참조_출처_테이블_일련번호 |
| `RFRNC_SCHM_NM` | 참조_스키마_명 |
| `RFRNC_TBL_NM` | 참조_테이블_명 |
| `REL_NM` | 관계_명 |
| `DEL_RULE_CD` | 삭제_규칙_코드 |
| `MDFCN_RULE_CD` | 수정_규칙_코드 |
| `RFRNC_CTLG_NM` | 참조_카탈로그_명 |

### TB_SRC_REL_COL (출처 관계 컬럼)

시퀀스 : `SQ_SRC_REL_COL`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_REL_COL_SN` | 출처_관계_컬럼_일련번호 |
| `SRC_REL_SN` | 출처_관계_일련번호 |
| `SRC_COL_SN` | 출처_컬럼_일련번호 |
| `RFRNC_SRC_COL_SN` | 참조_출처_컬럼_일련번호 |
| `RFRNC_COL_NM` | 참조_컬럼_명 |
| `COL_ORD` | 컬럼_순서 |

## 5. 원본 카탈로그 수집 실행 · 스냅샷 · 변경 이력 (AI-1c)

수집 한 번(실행)과 그 대상, 바뀐 스키마·테이블만 쌓는 버전, 객체별 변경 이력 (`lexorma-source` 의 `collection/`). 설명은 [source-catalog-versioning.md](source-catalog-versioning.md).
버전·이력은 만든 뒤 고치지 않으므로 `BaseEntity` 감사 컬럼 대신 실행의 일시를 쓴다. 실행·버전·이력은 접속 일련번호를 함께 묶은 FK 로 같은 접속의 것끼리만 이어진다.

### TB_SRC_CLCT_EXCN (출처 수집 실행)

시퀀스 : `SQ_SRC_CLCT_EXCN`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_CLCT_EXCN_SN` | 출처_수집_실행_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_CNTN_SN` | 출처_접속_일련번호 |
| `JOB_ID` | 작업_아이디 |
| `EXCN_TYPE_CD` | 실행_유형_코드 |
| `ACNT_SN` | 계정_일련번호 |
| `CLCT_TYPE_CD` | 수집_유형_코드 |
| `EXCN_STTS_CD` | 실행_상태_코드 |
| `RFLT_ORD` | 반영_순서 |
| `PRGRS_CNTN_SN` | 진행_접속_일련번호 |
| `EXCN_SRVR_ID` | 실행_서버_아이디 |
| `BGNG_DT` | 시작_일시 |
| `LAST_IDNTY_DT` | 최종_확인_일시 |
| `END_DT` | 종료_일시 |
| `RFLT_DT` | 반영_일시 |
| `TBL_CNT` | 테이블_수 |
| `COL_CNT` | 컬럼_수 |
| `VER_CNT` | 버전_수 |
| `CHG_CNT` | 변경_수 |
| `ERR_CD` | 오류_코드 |
| `ERR_CN` | 오류_내용 |

### TB_SRC_CLCT_TRGT (출처 수집 대상)

시퀀스 : `SQ_SRC_CLCT_TRGT`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_CLCT_TRGT_SN` | 출처_수집_대상_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_CLCT_EXCN_SN` | 출처_수집_실행_일련번호 |
| `PATH_CN` | 경로_내용 |
| `CTLG_NM` | 카탈로그_명 |
| `SCHM_NM` | 스키마_명 |
| `SRC_SCHM_SN` | 출처_스키마_일련번호 |
| `CLCT_RSLT_CD` | 수집_결과_코드 |
| `TBL_CNT` | 테이블_수 |
| `COL_CNT` | 컬럼_수 |
| `ERR_CD` | 오류_코드 |

### TB_SRC_SCHM_VER (출처 스키마 버전)

시퀀스 : `SQ_SRC_SCHM_VER`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_SCHM_VER_SN` | 출처_스키마_버전_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_CNTN_SN` | 출처_접속_일련번호 |
| `SRC_SCHM_SN` | 출처_스키마_일련번호 |
| `SRC_CLCT_EXCN_SN` | 출처_수집_실행_일련번호 |
| `RFLT_ORD` | 반영_순서 |
| `PATH_CN` | 경로_내용 |
| `CRT_CYCL` | 생성_차수 |
| `CHG_TYPE_CD` | 변경_유형_코드 |
| `HASH_VL` | 해시_값 |
| `FRMT_VER_NO` | 형식_버전_번호 |
| `SCHM_CN` | 스키마_내용 |

### TB_SRC_TBL_VER (출처 테이블 버전)

시퀀스 : `SQ_SRC_TBL_VER`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_TBL_VER_SN` | 출처_테이블_버전_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_CNTN_SN` | 출처_접속_일련번호 |
| `SRC_SCHM_SN` | 출처_스키마_일련번호 |
| `SRC_TBL_SN` | 출처_테이블_일련번호 |
| `SRC_CLCT_EXCN_SN` | 출처_수집_실행_일련번호 |
| `RFLT_ORD` | 반영_순서 |
| `PATH_CN` | 경로_내용 |
| `CMPR_NM` | 비교_명 |
| `CRT_CYCL` | 생성_차수 |
| `CHG_TYPE_CD` | 변경_유형_코드 |
| `HASH_VL` | 해시_값 |
| `FRMT_VER_NO` | 형식_버전_번호 |
| `TBL_CN` | 테이블_내용 |

### TB_SRC_CHG_HSTRY (출처 변경 이력)

시퀀스 : `SQ_SRC_CHG_HSTRY`

| 컬럼 | 한글 용어 |
|---|---|
| `SRC_CHG_HSTRY_SN` | 출처_변경_이력_일련번호 |
| `PRJ_SN` | 프로젝트_일련번호 |
| `SRC_CNTN_SN` | 출처_접속_일련번호 |
| `SRC_CLCT_EXCN_SN` | 출처_수집_실행_일련번호 |
| `RFLT_ORD` | 반영_순서 |
| `SRC_SCHM_SN` | 출처_스키마_일련번호 |
| `SRC_TBL_SN` | 출처_테이블_일련번호 |
| `TRGT_TYPE_CD` | 대상_유형_코드 |
| `TRGT_IDNTFR_CN` | 대상_식별자_내용 |
| `TRGT_NM` | 대상_명 |
| `CRT_CYCL` | 생성_차수 |
| `CHG_TYPE_CD` | 변경_유형_코드 |
| `CHG_ARTCL_CN` | 변경_항목_내용 |
| `CHG_BFR_CN` | 변경_이전_내용 |
| `CHG_AFTR_CN` | 변경_이후_내용 |
