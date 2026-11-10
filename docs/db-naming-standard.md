# DB 명명 규칙 (공통표준 기반)

> MetaMora 자체 DB 의 테이블·컬럼·시퀀스 이름 규칙과 전체 목록. 기준은 `metamora-app/src/main/resources/static/excel/dataPortal.xlsx`
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

## 예제 데이터

`V0__init.sql` 끝에서 기본 프로젝트 `META-MORA`(PRJ_SN 0, 테이블 접두어 `TB_`)에 예제를 넣는다. 모두 승인 상태.

- 데이터 사전 : 이 문서의 이름에 쓰인 단어 113개(공통표준 71 + 2장의 기관 표준단어 42), 컬럼 용어 131개(공통표준 용어와 같은 27개는 설명·도메인·이음동의어를 그대로), 그 용어의 도메인 22개(공통표준 19개와 자체 도메인 3개), 용어-단어 매핑.
  공통표준 용어가 없는 용어의 도메인은 마지막 단어(형식단어)의 도메인 분류에서 컬럼 길이에 맞는 것을 골랐다. `기본_값`, `비밀번호`, `프로젝트_일련번호_목록`은 실제 컬럼 타입에 맞는 자체 도메인으로 연결한다.

  | 용어 | 자체 도메인 | 타입 | 의미 |
  | --- | --- | --- | --- |
  | `기본_값` | `값V255` | `VARCHAR(255)` | 기본값·SQL 표현식의 문자열 표현 |
  | `비밀번호` | `비밀번호V255` | `VARCHAR(255)` | 인코더 접두사를 포함한 비밀번호 해시; 평문 길이 규칙이 아님 |
  | `프로젝트_일련번호_목록` | `목록A` | `BIGINT ARRAY` | 일련번호 배열; A는 자체 배열 표기, 고정 길이 없음 |

  세 도메인은 공공데이터 공통표준이 아닌 MetaMora 자체 정의다. 배열은 기존 PostgreSQL·H2 모델을 유지하며 다른 DBMS의 배열 지원을 추가하지 않는다.
- 물리 모델 : DEFAULT 스키마에 이 문서의 테이블(한글명 = 표준 단어를 공백으로 이은 이름)·컬럼(표준 용어 연결, 설명 = 한글 용어). 외래 키는 컬럼의 참조 테이블명 + `REF_COLUMN` 관계.
- 테이블 한글명을 표준 단어로 바꾸면 같은 테이블명이 나온다 (`ErdIntegrationTest.seededExample`).
마이그레이션은 PostgreSQL·H2 양쪽의 `V0__init.sql` 하나로 관리한다. 스키마 변경 시 예제 모델도 함께 확인한다.

## 2. 기관 표준단어 (공통표준에 없는 단어)

공통표준 단어에 없거나, 같은 약어가 다른 뜻으로 등록돼 있어 MetaMora 에서 정한 단어. 데이터 사전에 프로젝트 단어로 등록해 쓸 수 있다.

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

